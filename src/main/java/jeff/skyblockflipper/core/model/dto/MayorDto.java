package jeff.skyblockflipper.core.model.dto;

import jeff.skyblockflipper.core.model.MayorInfo;

import java.util.ArrayList;
import java.util.List;

/** Wire format of {@code /v2/resources/skyblock/election}. */
public final class MayorDto {
	public boolean success;
	public MayorEntry mayor;

	public static final class MayorEntry {
		public String key;
		public String name;
		public List<Perk> perks;
		public Minister minister;
	}

	public static final class Perk {
		public String name;
	}

	public static final class Minister {
		public String name;
	}

	public MayorInfo toMayorInfo() {
		if (mayor == null || mayor.key == null) {
			return MayorInfo.unknown();
		}

		List<String> perkNames = new ArrayList<>();

		if (mayor.perks != null) {
			for (Perk perk : mayor.perks) {
				if (perk != null && perk.name != null) {
					perkNames.add(perk.name);
				}
			}
		}

		String minister = mayor.minister != null && mayor.minister.name != null ? mayor.minister.name : "";

		return new MayorInfo(mayor.key, mayor.name != null ? mayor.name : mayor.key, perkNames, minister);
	}
}
