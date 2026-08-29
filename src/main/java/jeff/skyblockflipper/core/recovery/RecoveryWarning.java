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

/** Reasons a recovery leg was rejected, reduced, or needs manual evidence. */
public enum RecoveryWarning {
	UNKNOWN_MAPPING,
	UNKNOWN_REMOVAL_COST,
	UNSUPPORTED_SLOT,
	MALFORMED_METADATA,
	INSUFFICIENT_SAMPLES,
	INSUFFICIENT_DEPTH,
	ILLIQUID,
	PARTIAL_DEPTH_ZERO_CREDIT,
	PREVIEW_REQUIRED,
	STALE_EVIDENCE,
	ARITHMETIC_OVERFLOW
}
