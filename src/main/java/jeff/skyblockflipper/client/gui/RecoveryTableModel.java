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
