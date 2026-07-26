package jeff.skyblockflipper.core.ledger;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.StrategyKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A record of flips actually taken, and the only feedback loop the mod has.
 *
 * <p>Without this, every number the mod prints is a claim about the future that is never checked
 * against anything. With it, {@link #stats(StrategyKind)} answers the one question that decides
 * whether any of the rest is worth trusting: of the profit the strategies quoted, how much showed
 * up? Entries are opened by hand and closed by hand, because the mod is advisory and cannot see
 * your purse.
 *
 * <p>Unlike {@link jeff.skyblockflipper.core.tape.SalesTape}, this file is rewritten rather than
 * appended: entries are mutated when they close, there are tens of them rather than millions, and
 * a whole-file rewrite through a temp file and an atomic move cannot leave a half-written entry
 * behind.
 *
 * <p>Not thread-safe; the command layer owns it and touches it from the client thread.
 */
public final class Ledger {
	/** Short enough to type back into {@code /flip close}, long enough not to collide. */
	private static final int ID_LENGTH = 4;

	private final Path file;
	private final Gson gson = new Gson();
	private final Map<String, LedgerEntry> entries = new LinkedHashMap<>();

	public Ledger(Path file) {
		this.file = file;
	}

	/** Reads the ledger from disk, discarding entries too corrupt to parse. */
	public void load() throws IOException {
		entries.clear();

		if (!Files.exists(file)) {
			return;
		}

		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (line.isBlank()) {
				continue;
			}

			try {
				LedgerEntry entry = gson.fromJson(line, LedgerEntry.class);

				if (entry != null && entry.id() != null && entry.status() != null) {
					entries.put(entry.id(), entry);
				}
			} catch (JsonSyntaxException ignored) {
				// One unreadable entry must not cost the whole trading history.
			}
		}
	}

	/** Records a candidate as taken, at the numbers it was quoting when you took it. */
	public LedgerEntry open(FlipCandidate candidate, long now) throws IOException {
		LedgerEntry entry = LedgerEntry.open(nextId(), candidate, now);
		entries.put(entry.id(), entry);
		save();
		return entry;
	}

	/**
	 * Closes a position with what actually happened.
	 *
	 * @param unitSellPrice the price the sale filled at, as displayed - fees are applied here on
	 *                      the same basis the quote used, so quoted and realized stay comparable
	 */
	public Optional<LedgerEntry> close(String id, long unitsSold, double unitSellPrice, Fees fees)
			throws IOException {
		LedgerEntry entry = entries.get(id);

		if (entry == null || !entry.isOpen()) {
			return Optional.empty();
		}

		double net = netProceedsPerUnit(entry.kind(), unitSellPrice, fees) - entry.unitBuyPrice();
		LedgerEntry updated = entry.closed(System.currentTimeMillis(), unitsSold, unitSellPrice, net);

		entries.put(id, updated);
		save();
		return Optional.of(updated);
	}

	/** Marks a position as never having worked out. Counts against the fill rate, not the capture rate. */
	public Optional<LedgerEntry> abandon(String id) throws IOException {
		LedgerEntry entry = entries.get(id);

		if (entry == null || !entry.isOpen()) {
			return Optional.empty();
		}

		LedgerEntry updated = entry.abandoned(System.currentTimeMillis());
		entries.put(id, updated);
		save();
		return Optional.of(updated);
	}

	public List<LedgerEntry> all() {
		return List.copyOf(entries.values());
	}

	public List<LedgerEntry> openEntries() {
		return entries.values().stream().filter(LedgerEntry::isOpen).toList();
	}

	public Optional<LedgerEntry> get(String id) {
		return Optional.ofNullable(entries.get(id));
	}

	/** Coins currently tied up in positions that have not come back yet. */
	public long committedCapital() {
		return openEntries().stream().mapToLong(LedgerEntry::capital).sum();
	}

	/** @param kind null for every strategy together */
	public LedgerStats stats(StrategyKind kind) {
		int closed = 0;
		int abandoned = 0;
		long unitsPlanned = 0L;
		long unitsFilled = 0L;
		double quoted = 0.0d;
		double realized = 0.0d;

		for (LedgerEntry entry : entries.values()) {
			if (entry.isOpen() || (kind != null && entry.kind() != kind)) {
				continue;
			}

			// Abandoned positions carry no realized price, but their unsold units are real
			// evidence about how much of a plan is reachable, so they count toward the fill rate.
			unitsPlanned += entry.units();
			unitsFilled += entry.unitsSold();

			if (entry.status() == LedgerEntry.Status.ABANDONED) {
				abandoned++;
				continue;
			}

			closed++;
			quoted += entry.quotedOnFilled();
			realized += entry.realizedTotal();
		}

		return new LedgerStats(closed, abandoned, unitsPlanned, unitsFilled, quoted, realized);
	}

	public Path file() {
		return file;
	}

	/**
	 * Where the sale lands and therefore which fees apply. The quote used the same basis, so both
	 * sides of the capture rate move together if the fee model is ever wrong.
	 */
	private static double netProceedsPerUnit(StrategyKind kind, double unitSellPrice, Fees fees) {
		return switch (kind) {
			// NPCs pay their fixed price flat; there is no tax on the counter.
			case NPC_FLIP -> unitSellPrice;
			case AUCTION_VALUE -> fees.binNetProceeds(Math.round(unitSellPrice));
			// Craft outputs are assumed to be sold on the bazaar until craft flips exist and can
			// say where they actually go.
			case BAZAAR_SPREAD, CRAFT -> fees.bazaarSaleProceeds(unitSellPrice);
		};
	}

	private String nextId() {
		String id;

		do {
			id = Long.toString(ThreadLocalRandom.current().nextLong(36L * 36 * 36 * 36), 36);
			id = "0".repeat(ID_LENGTH - id.length()) + id;
		} while (entries.containsKey(id));

		return id;
	}

	private void save() throws IOException {
		Files.createDirectories(file.getParent());

		StringBuilder out = new StringBuilder();

		for (LedgerEntry entry : entries.values()) {
			out.append(gson.toJson(entry)).append('\n');
		}

		// Write beside the target and move it into place, so a crash mid-write leaves the previous
		// ledger intact rather than a truncated one.
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, out.toString(), StandardCharsets.UTF_8);

		try {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
