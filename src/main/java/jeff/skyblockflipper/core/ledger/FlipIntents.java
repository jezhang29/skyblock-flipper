package jeff.skyblockflipper.core.ledger;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import jeff.skyblockflipper.core.strategy.StrategyKind;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Which strategy the player is running each item under, so a buy order can be told apart from
 * another strategy's buy order on the same book.
 *
 * <p>The one thing the orders menu cannot say. A resting buy order carries an item, a price and a
 * size, and nothing about why it was placed - so {@code NpcReprice} treated every buy order on an
 * NPC-sellable item as an NPC flip and told the player to reprice or cancel it. On an account also
 * running craft, combine or spread flips, an enchanted-book or ingredient order the player means to
 * hold got a cancel the moment its window ran out. Reported from play.
 *
 * <p><b>Intent is known only when the player acts on the mod's advice</b>, never from the trade
 * itself: chat announces a placement with an item and a total and never a reason. So this is written
 * at the act-on points - a basket line placed, a candidate taken, a craft or combine followed - and
 * read back when the NPC side decides whose order a resting order is. A buy the player placed while
 * just playing the game is never recorded, so it stays what it was: unknown, and left to the NPC
 * side to claim, which is the same reading {@code trackUnquotedTrades} takes of an unquoted buy.
 *
 * <p><b>Keyed on the item that rests on the book, not the one the strategy is named for.</b> A
 * combine sells a top-tier book but rests its buy order on the cheap source book; a craft sells an
 * output but rests its buy orders on the ingredients. Those source and ingredient ids are what
 * appear in the orders menu and what the NPC side would misread, so those are what is recorded.
 *
 * <p>Persisted, unlike {@link PlannedQuotes}, because an order outlives the moment it was placed and
 * the session it was placed in: a combine source order left resting overnight is exactly the one the
 * NPC side must not adopt as its own the next morning. Entries expire on {@link #ttl} so an item the
 * player stopped flipping a strategy is eventually released rather than shielded forever; the window
 * is generous because a craft or combine hold is measured in hours to days, not the resting window a
 * quote lives on.
 *
 * <p>Rewritten whole through a temp file and an atomic move, like {@link Ledger}: there are at most
 * a few dozen entries, they are mutated in place, and a half-written map is worse than a stale one.
 * Not thread-safe; the client owns it and touches it from the client thread.
 */
public final class FlipIntents {
	private static final Type MAP_TYPE = new TypeToken<Map<String, Stored>>() {
	}.getType();

	private final Path file;
	private final Gson gson = new Gson();
	private final Map<String, Stored> intents = new LinkedHashMap<>();
	private final Supplier<Duration> ttl;

	/** One item's intent, as it sits on disk. Kind is stored by name so a renamed enum fails safe. */
	private static final class Stored {
		private String kind;
		private long at;

		Stored(String kind, long at) {
			this.kind = kind;
			this.at = at;
		}

		StrategyKind kind() {
			try {
				return StrategyKind.valueOf(kind);
			} catch (IllegalArgumentException | NullPointerException e) {
				return null;
			}
		}
	}

	/**
	 * @param file where the register lives, injected because {@code core} does not look up Minecraft
	 *             paths
	 * @param ttl  how long an intent stays good, read at use time rather than held so {@code /flip
	 *             reload} can change it. A non-positive or null window drops everything, which is a
	 *             configuration mistake rather than a state, so it is clamped to a day
	 */
	public FlipIntents(Path file, Supplier<Duration> ttl) {
		this.file = file;
		this.ttl = ttl == null ? () -> Duration.ofDays(3) : ttl;
	}

	/** Reads the register from disk, discarding a file too corrupt to parse rather than failing. */
	public void load() throws IOException {
		intents.clear();

		if (!Files.exists(file)) {
			return;
		}

		String text = Files.readString(file, StandardCharsets.UTF_8);

		if (text.isBlank()) {
			return;
		}

		try {
			Map<String, Stored> loaded = gson.fromJson(text, MAP_TYPE);

			if (loaded != null) {
				loaded.forEach((id, stored) -> {
					if (id != null && !id.isEmpty() && stored != null && stored.kind() != null) {
						intents.put(id, stored);
					}
				});
			}
		} catch (JsonSyntaxException e) {
			throw new IOException("Corrupt flip-intents file: " + file, e);
		}
	}

	/**
	 * Records the strategy the player is running this item under, replacing any older intent for it.
	 *
	 * <p>Last write wins, on purpose: an item the player was combine-flipping and has now put in an
	 * NPC basket is an NPC flip now, and the fresh {@code NPC_FLIP} write is what releases it back to
	 * the NPC side. The in-memory time is always bumped so an actively followed job cannot expire
	 * under the player; the file is only rewritten when the strategy for the item actually changed,
	 * because a followed job records the same intent every poll and rewriting the file each time
	 * would be a disk write per frame for no new information.
	 */
	public void record(String itemId, StrategyKind kind, long now) throws IOException {
		if (itemId == null || itemId.isEmpty() || kind == null) {
			return;
		}

		Stored existing = intents.get(itemId);
		boolean changed = existing == null || existing.kind() != kind;

		intents.put(itemId, new Stored(kind.name(), now));

		if (changed) {
			prune(now);
			save();
		}
	}

	/** The strategy this item is being flipped under, if a live intent says. */
	public Optional<StrategyKind> kindFor(String itemId, long now) {
		if (itemId == null || itemId.isEmpty()) {
			return Optional.empty();
		}

		Stored stored = intents.get(itemId);

		return stored == null || expired(stored, now) || stored.kind() == null
				? Optional.empty()
				: Optional.of(stored.kind());
	}

	/**
	 * Items the player is flipping under a strategy that is not the NPC one, so a resting buy order
	 * on them is not the NPC side's to reprice or cancel.
	 *
	 * <p>Everything except {@link StrategyKind#NPC_FLIP}. An NPC-flipped item is recorded too, so a
	 * later NPC basket on an item once craft-flipped overwrites the old intent rather than leaving it
	 * shielded; it simply does not appear here.
	 */
	public Set<String> foreignItems(long now) {
		Set<String> foreign = new HashSet<>();

		for (Map.Entry<String, Stored> entry : intents.entrySet()) {
			Stored stored = entry.getValue();

			if (!expired(stored, now) && stored.kind() != null
					&& stored.kind() != StrategyKind.NPC_FLIP) {
				foreign.add(entry.getKey());
			}
		}

		return foreign;
	}

	/** Drops what has gone stale, then persists if anything went. Cheap: a few dozen entries. */
	public void prune(long now) throws IOException {
		if (intents.values().removeIf(stored -> expired(stored, now))) {
			save();
		}
	}

	public int size() {
		return intents.size();
	}

	public Path file() {
		return file;
	}

	private boolean expired(Stored stored, long now) {
		Duration window = ttl.get();

		if (window == null || window.isZero() || window.isNegative()) {
			window = Duration.ofDays(1);
		}

		return now - stored.at > window.toMillis();
	}

	private void save() throws IOException {
		Files.createDirectories(file.getParent());

		String out = gson.toJson(intents, MAP_TYPE);
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, out, StandardCharsets.UTF_8);

		try {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
