package jeff.skyblockflipper.core.config;

import jeff.skyblockflipper.core.config.ConfigSchema.Entry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSchemaTest {
	@Test
	void describesEverySettingTheConfigHas() {
		Set<String> described = new HashSet<>();
		for (Entry entry : ConfigSchema.entries()) {
			assertTrue(described.add(entry.key()), "duplicate schema entry for " + entry.key());
		}

		List<String> missing = new ArrayList<>();
		for (String field : settingFieldNames()) {
			if (!described.remove(field)) {
				missing.add(field);
			}
		}

		// The whole point of the schema: a new setting that no UI can reach is a setting that
		// still has to be hand-edited, and nothing else would say so.
		assertEquals(List.of(), missing, "FlipperConfig fields with no ConfigSchema entry");
		assertEquals(Set.of(), described, "schema entries naming no FlipperConfig field");
	}

	@Test
	void everyOfferedValueSurvivesValidation() {
		for (Entry entry : ConfigSchema.entries()) {
			for (Object value : offeredValues(entry)) {
				FlipperConfig config = new FlipperConfig();
				write(entry, config, value);
				config.validated();

				// A UI that offers a value validated() then clamps away would show the player a
				// setting that silently refuses to stick.
				assertEquals(value, read(entry, config),
						entry.key() + " does not survive validated() at " + value);
			}
		}
	}

	@Test
	void readsBackWhatTheConfigActuallyHolds() {
		FlipperConfig config = new FlipperConfig();

		assertEquals(config.bankroll, read(entry("bankroll"), config));
		assertEquals(config.minConfidence, read(entry("minConfidence"), config));
		assertEquals(config.hudEnabled, read(entry("hudEnabled"), config));
		assertEquals(HudAnchor.TOP_LEFT.name(), read(entry("hudAnchor"), config));
		// Zero is the auto sentinel, and a dropdown cannot show a sentinel.
		assertEquals(0.0d, config.guiZoom);
		assertEquals(ConfigSchema.ZOOM_AUTO, read(entry("guiZoom"), config));
	}

	@Test
	void mapsTheZoomChoiceOntoTheDoubleField() {
		FlipperConfig config = new FlipperConfig();

		write(entry("guiZoom"), config, "0.7");
		assertEquals(0.7d, config.validated().guiZoom, 1e-9);

		write(entry("guiZoom"), config, ConfigSchema.ZOOM_AUTO);
		assertEquals(0.0d, config.validated().guiZoom);
	}

	@Test
	void explainsEverySettingInPlainWords() {
		for (Entry entry : ConfigSchema.entries()) {
			assertFalse(entry.label().isBlank(), entry.key() + " has no label");
			// Help that only restates the label tells a player nothing they did not already see.
			assertTrue(entry.help().length() > entry.label().length() + 20,
					entry.key() + " has help that says no more than its label");
			assertTrue(entry.help().endsWith(".") || entry.help().endsWith("?"),
					entry.key() + " has help that is not a sentence");
		}
	}

	/** Public non-static fields of {@link FlipperConfig}: exactly the things a player can set. */
	private static List<String> settingFieldNames() {
		List<String> names = new ArrayList<>();
		for (Field field : FlipperConfig.class.getDeclaredFields()) {
			if (Modifier.isPublic(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())) {
				names.add(field.getName());
			}
		}
		return names;
	}

	private static Entry entry(String key) {
		return ConfigSchema.entries().stream()
				.filter(e -> e.key().equals(key))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no schema entry for " + key));
	}

	/** The extremes and a mid point of what a UI built from this entry could produce. */
	private static List<Object> offeredValues(Entry entry) {
		return switch (entry) {
			case Entry.Flag ignored -> List.of(Boolean.TRUE, Boolean.FALSE);
			case Entry.IntRange r -> List.of(r.min(), r.max(), (r.min() + r.max()) / 2);
			case Entry.LongRange r -> List.of(r.min(), r.max(), (r.min() + r.max()) / 2);
			case Entry.Ratio r -> List.of(r.min(), r.max(), (r.min() + r.max()) / 2.0d);
			case Entry.Choice c -> List.copyOf(c.options());
			case Entry.Text ignored -> List.of("", "some-key");
		};
	}

	private static Object read(Entry entry, FlipperConfig config) {
		return switch (entry) {
			case Entry.Flag e -> e.get().test(config);
			case Entry.IntRange e -> e.get().applyAsInt(config);
			case Entry.LongRange e -> e.get().applyAsLong(config);
			case Entry.Ratio e -> e.get().applyAsDouble(config);
			case Entry.Choice e -> e.get().apply(config);
			case Entry.Text e -> e.get().apply(config);
		};
	}

	private static void write(Entry entry, FlipperConfig config, Object value) {
		switch (entry) {
			case Entry.Flag e -> e.set().accept(config, (Boolean) value);
			case Entry.IntRange e -> e.set().accept(config, (Integer) value);
			case Entry.LongRange e -> e.set().accept(config, (Long) value);
			case Entry.Ratio e -> e.set().accept(config, (Double) value);
			case Entry.Choice e -> e.set().accept(config, (String) value);
			case Entry.Text e -> e.set().accept(config, (String) value);
		}
	}
}
