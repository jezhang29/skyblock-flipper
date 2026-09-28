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
package jeff.skyblockflipper.client.gui;

import jeff.skyblockflipper.core.recovery.RecoveryOpportunity;

import java.util.List;

/** Rows and UUID-stable selection for the special Recovery tab. */
final class RecoveryTableModel {
	private List<RecoveryOpportunity> rows = List.of();
	private String selectedUuid = "";

	void setRows(List<RecoveryOpportunity> replacement) {
		rows = List.copyOf(replacement);
		if (!selectedUuid.isEmpty() && rows.stream()
				.noneMatch(row -> row.auctionUuid().equals(selectedUuid))) {
			selectedUuid = "";
		}
	}

	List<RecoveryOpportunity> rows() {
		return rows;
	}

	RecoveryOpportunity selection() {
		return rows.stream().filter(row -> row.auctionUuid().equals(selectedUuid))
				.findFirst().orElse(null);
	}

	int selectedIndex() {
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).auctionUuid().equals(selectedUuid)) {
				return i;
			}
		}
		return -1;
	}

	void select(int index) {
		selectedUuid = index >= 0 && index < rows.size() ? rows.get(index).auctionUuid() : "";
	}

	void move(int delta) {
		if (rows.isEmpty()) {
			selectedUuid = "";
			return;
		}
		int current = selectedIndex();
		select(Math.clamp(current < 0 ? (delta >= 0 ? 0 : rows.size() - 1) : current + delta,
				0, rows.size() - 1));
	}
}
