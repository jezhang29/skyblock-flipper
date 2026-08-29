/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package jeff.skyblockflipper.core.recovery;

import jeff.skyblockflipper.core.item.DetailedDecodedItem;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.valuation.ValueEstimate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Recovery-only consumer of decoded active listings. It never creates actions or worked jobs. */
public final class RecoveryListingScan {
	private final RecoveryValueModel values;
	private final BazaarSnapshot bazaar;
	private final Fees fees;
	private final RecoveryScanPolicy policy;
	private final Instant observedAt;
	private final List<RecoveryOpportunity> found = new ArrayList<>();

	private int decoded;
	private int rejected;

	public RecoveryListingScan(RecoveryValueModel values, BazaarSnapshot bazaar, Fees fees,
			RecoveryScanPolicy policy, Instant observedAt) {
		this.values = values;
		this.bazaar = bazaar;
		this.fees = fees;
		this.policy = policy;
		this.observedAt = observedAt;
	}

	public boolean mightUse(ActiveListing listing) {
		return found.size() < policy.maximumResults()
				&& values.mightHaveRecovery(listing.itemName(), listing.rarity());
	}

	public void offerDecoded(ActiveListing listing, DetailedDecodedItem detailed) {
		decoded++;
		if (!detailed.recovery().hasRecoverableParts()
				|| detailed.recovery().warnings().contains(RecoveryWarning.MALFORMED_METADATA)
				|| detailed.recovery().warnings().contains(RecoveryWarning.UNSUPPORTED_SLOT)) {
			return;
		}

		RecoveryComponentQuote host = hostQuote(detailed);
		List<RecoveryComponentQuote> components = new ArrayList<>();
		for (RecoveryAttachment attachment : detailed.recovery().attachments()) {
			components.add(componentQuote(attachment));
		}
		if (detailed.recovery().previewRequired()) {
			components.add(RecoveryFloorCalculator.quote(RecoveryLeg.uncredited(
					RecoveryComponentKind.LEGACY_SHARD, "LEGACY_ATTRIBUTES", "Legacy salvage preview",
					1L, RecoveryWarning.PREVIEW_REQUIRED), policy.safetyBuffer(), fees).orElseThrow());
		}

		Optional<RecoveryOpportunity> opportunity = RecoveryFloorCalculator.compose(listing.uuid(),
				detailed.item().skyblockId(), detailed.item().displayName(), listing.price(), observedAt,
				host, components);
		if (opportunity.isPresent()
				&& opportunity.orElseThrow().expectedProfit() >= policy.minimumProfit()
				&& opportunity.orElseThrow().margin() >= policy.minimumMargin()) {
			found.add(opportunity.orElseThrow());
		} else {
			rejected++;
		}
	}

	public List<RecoveryOpportunity> results() {
		return found.stream().sorted(Comparator.comparingLong(RecoveryOpportunity::expectedProfit)
				.reversed()).toList();
	}

	public int decoded() {
		return decoded;
	}

	public int rejected() {
		return rejected;
	}

	private RecoveryComponentQuote hostQuote(DetailedDecodedItem detailed) {
		Optional<ValueEstimate> estimate = values.cleanHostValue(detailed);
		RecoveryLeg leg = estimate.filter(this::liquid).map(value -> credited(
				RecoveryComponentKind.HOST, detailed.item().skyblockId(),
				detailed.item().displayName() + " (clean host)", 1L, RecoveryExitVenue.AH,
				value, 0L)).orElseGet(() -> RecoveryLeg.uncredited(RecoveryComponentKind.HOST,
				detailed.item().skyblockId(), detailed.item().displayName() + " (clean host)", 1L,
				estimate.isEmpty() ? RecoveryWarning.INSUFFICIENT_SAMPLES : RecoveryWarning.ILLIQUID));
		return RecoveryFloorCalculator.quote(leg, policy.safetyBuffer(), fees).orElseThrow();
	}

	private RecoveryComponentQuote componentQuote(RecoveryAttachment attachment) {
		Optional<RecoveryComponentCatalog.Entry> catalog = RecoveryComponentCatalog.find(attachment);
		if (catalog.isEmpty() || catalog.orElseThrow().removalCost().isEmpty()) {
			return RecoveryFloorCalculator.quote(RecoveryComponentCatalog.uncredited(attachment),
					policy.safetyBuffer(), fees).orElseThrow();
		}
		RecoveryComponentCatalog.Entry entry = catalog.orElseThrow();
		if (entry.exitVenue() == RecoveryExitVenue.BAZAAR) {
			Optional<BazaarProduct> product = bazaar.product(entry.stableComponentId());
			return product.flatMap(value -> BazaarRecoveryExit.quote(attachment, value,
					policy.minimumBazaarInstantSellsPerHour(), policy.safetyBuffer(), fees))
					.orElseGet(() -> RecoveryFloorCalculator.quote(RecoveryLeg.uncredited(
							attachment.kind(), attachment.stableComponentId(), entry.displayName(),
							attachment.quantity(), RecoveryWarning.INSUFFICIENT_DEPTH),
							policy.safetyBuffer(), fees).orElseThrow());
		}

		Optional<ValueEstimate> estimate = values.bareComponentValue(attachment.stableComponentId());
		RecoveryLeg leg = estimate.filter(this::liquid).map(value -> credited(attachment.kind(),
				attachment.stableComponentId(), entry.displayName(), attachment.quantity(),
				RecoveryExitVenue.AH, value, Math.multiplyExact(
						entry.removalCost().orElseThrow(), attachment.quantity()))).orElseGet(() ->
				RecoveryLeg.uncredited(attachment.kind(), attachment.stableComponentId(),
						entry.displayName(), attachment.quantity(), estimate.isEmpty()
								? RecoveryWarning.INSUFFICIENT_SAMPLES : RecoveryWarning.ILLIQUID));
		return RecoveryFloorCalculator.quote(leg, policy.safetyBuffer(), fees).orElseThrow();
	}

	private RecoveryLeg credited(RecoveryComponentKind kind, String id, String displayName,
			long quantity, RecoveryExitVenue venue, ValueEstimate estimate, long removalCost) {
		long gross = (long) Math.floor(estimate.median() * quantity);
		return new RecoveryLeg(kind, id, displayName, quantity, venue, gross, removalCost,
				estimate.samples(), estimate.salesPerHour() * 24.0d,
				(long) (estimate.hoursToSell() * 3_600_000.0d), 0L, confidence(estimate), true,
				Set.of());
	}

	private boolean liquid(ValueEstimate estimate) {
		return estimate.samples() >= policy.minimumAhSamples()
				&& estimate.salesPerHour() * 24.0d >= policy.minimumAhSalesPerDay()
				&& estimate.hoursToSell() <= policy.maximumAhHoursToSell();
	}

	private static RecoveryConfidence confidence(ValueEstimate estimate) {
		if (estimate.samples() >= 20 && estimate.dispersion() <= 0.25d) {
			return RecoveryConfidence.HIGH;
		}
		return estimate.samples() >= 10 ? RecoveryConfidence.MEDIUM : RecoveryConfidence.LOW;
	}
}
