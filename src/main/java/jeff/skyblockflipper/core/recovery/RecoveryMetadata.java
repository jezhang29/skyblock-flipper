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

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Recovery-only facts decoded beside, but never added to, the ordinary valuation item. */
public record RecoveryMetadata(
		List<RecoveryAttachment> attachments,
		Map<String, Integer> legacyAttributes,
		Set<RecoveryWarning> warnings) {

	public static final RecoveryMetadata EMPTY = new RecoveryMetadata(List.of(), Map.of(), Set.of());

	public RecoveryMetadata {
		attachments = Objects.requireNonNull(attachments, "attachments").stream()
				.sorted(Comparator.comparing((RecoveryAttachment attachment) -> attachment.kind().name())
						.thenComparing(RecoveryAttachment::slot)
						.thenComparing(RecoveryAttachment::stableComponentId))
				.toList();
		legacyAttributes = Collections.unmodifiableMap(new TreeMap<>(
				Objects.requireNonNull(legacyAttributes, "legacyAttributes")));
		warnings = Set.copyOf(Objects.requireNonNull(warnings, "warnings"));
	}

	public boolean hasRecoverableParts() {
		return !attachments.isEmpty() || !legacyAttributes.isEmpty();
	}

	public boolean previewRequired() {
		return !legacyAttributes.isEmpty() && warnings.contains(RecoveryWarning.PREVIEW_REQUIRED);
	}
}
