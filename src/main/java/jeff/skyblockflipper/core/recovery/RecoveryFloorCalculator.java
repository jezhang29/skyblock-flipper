package jeff.skyblockflipper.core.recovery;

import jeff.skyblockflipper.core.pricing.Fees;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Applies the recovery floor formula without allowing overflow or invalid evidence to create value. */
public final class RecoveryFloorCalculator {
	private RecoveryFloorCalculator() {}

	public static Optional<RecoveryComponentQuote> quote(RecoveryLeg leg, double safetyBuffer,
			Fees fees) {
		if (leg == null || fees == null || !Double.isFinite(safetyBuffer)
				|| safetyBuffer < 0.0d || safetyBuffer >= 1.0d) {
			return Optional.empty();
		}
		if (!leg.credited()) {
			return Optional.of(copyZero(leg));
		}

		try {
			long bufferedGross = haircut(leg.grossQuickSale(), safetyBuffer);
			long fee = venueFee(bufferedGross, leg.exitVenue(), fees);
			if (fee < 0L || fee > bufferedGross) {
				return Optional.empty();
			}
			long contribution = Math.subtractExact(Math.subtractExact(bufferedGross, fee),
					leg.removalCost());
			return Optional.of(new RecoveryComponentQuote(leg.kind(), leg.stableComponentId(),
					leg.displayName(), leg.quantity(), leg.exitVenue(), leg.grossQuickSale(),
					bufferedGross, fee, leg.removalCost(), contribution, leg.sampleCount(),
					leg.salesPerDay(), leg.medianSellingTimeMillis(), leg.quotedDepth(),
					leg.confidence(), true, leg.warnings()));
		} catch (ArithmeticException failure) {
			return Optional.empty();
		}
	}

	public static Optional<RecoveryOpportunity> compose(String auctionUuid, String itemId,
			String displayName, long purchasePrice, Instant observedAt,
			RecoveryComponentQuote cleanHostQuote, List<RecoveryComponentQuote> components) {
		if (auctionUuid == null || auctionUuid.isBlank() || itemId == null || itemId.isBlank()
				|| displayName == null || displayName.isBlank() || purchasePrice <= 0L
				|| observedAt == null || cleanHostQuote == null || components == null
				|| cleanHostQuote.kind() != RecoveryComponentKind.HOST) {
			return Optional.empty();
		}

		try {
			long floor = cleanHostQuote.netContribution();
			EnumSet<RecoveryWarning> warnings = EnumSet.noneOf(RecoveryWarning.class);
			warnings.addAll(cleanHostQuote.warnings());
			RecoveryConfidence confidence = cleanHostQuote.confidence();
			for (RecoveryComponentQuote component : components) {
				if (component == null || component.kind() == RecoveryComponentKind.HOST) {
					return Optional.empty();
				}
				floor = Math.addExact(floor, component.netContribution());
				warnings.addAll(component.warnings());
				confidence = lower(confidence, component.confidence());
			}
			floor = Math.max(0L, floor);
			long profit = Math.subtractExact(floor, purchasePrice);
			double margin = profit / (double) purchasePrice;
			List<RecoveryComponentQuote> immutable = List.copyOf(components);
			return Optional.of(new RecoveryOpportunity(auctionUuid, itemId, displayName,
					purchasePrice, observedAt, cleanHostQuote, immutable, floor, profit, margin,
					confidence, warnings, fingerprint(cleanHostQuote, immutable)));
		} catch (ArithmeticException failure) {
			return Optional.empty();
		}
	}

	private static RecoveryComponentQuote copyZero(RecoveryLeg leg) {
		return new RecoveryComponentQuote(leg.kind(), leg.stableComponentId(), leg.displayName(),
				leg.quantity(), RecoveryExitVenue.NONE, 0L, 0L, 0L, 0L, 0L,
				leg.sampleCount(), leg.salesPerDay(), leg.medianSellingTimeMillis(),
				leg.quotedDepth(), RecoveryConfidence.NONE, false, leg.warnings());
	}

	private static long haircut(long gross, double safetyBuffer) {
		BigDecimal retained = BigDecimal.ONE.subtract(BigDecimal.valueOf(safetyBuffer));
		return BigDecimal.valueOf(gross).multiply(retained).setScale(0, RoundingMode.FLOOR)
				.longValueExact();
	}

	private static long venueFee(long gross, RecoveryExitVenue venue, Fees fees) {
		return switch (venue) {
			case AH -> Math.addExact(fees.binListingFee(gross), fees.claimTax(gross));
			case BAZAAR -> BigDecimal.valueOf(gross)
					.multiply(BigDecimal.valueOf(fees.bazaarTaxRate()))
					.setScale(0, RoundingMode.CEILING).longValueExact();
			case NONE -> throw new ArithmeticException("credited leg has no exit venue");
		};
	}

	private static RecoveryConfidence lower(RecoveryConfidence left, RecoveryConfidence right) {
		return left.ordinal() <= right.ordinal() ? left : right;
	}

	private static String fingerprint(RecoveryComponentQuote host,
			List<RecoveryComponentQuote> components) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			List<RecoveryComponentQuote> legs = new ArrayList<>(components.size() + 1);
			legs.add(host);
			legs.addAll(components);
			legs.sort(Comparator.comparing((RecoveryComponentQuote quote) -> quote.kind().name())
					.thenComparing(RecoveryComponentQuote::stableComponentId));
			for (RecoveryComponentQuote leg : legs) {
				digest.update((leg.kind() + "\0" + leg.stableComponentId() + "\0" + leg.quantity()
						+ "\0" + leg.bufferedGross() + "\0" + leg.fee() + "\0"
						+ leg.removalCost() + "\0" + leg.netContribution() + "\n").getBytes(
						java.nio.charset.StandardCharsets.UTF_8));
			}
			return HexFormat.of().formatHex(digest.digest(), 0, 16);
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
