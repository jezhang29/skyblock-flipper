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

import jeff.skyblockflipper.SkyblockFlipper;
import jeff.skyblockflipper.client.CandidateFeed;
import jeff.skyblockflipper.client.MarketDataService;
import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.core.config.ConfigSchema;
import jeff.skyblockflipper.core.config.FlipperConfig;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings screen, generated from {@link ConfigSchema}.
 *
 * <p>Nothing here knows what any individual setting means. Every label, help string, bound and
 * accessor comes from the schema, so a new field in {@link FlipperConfig} appears on this screen as
 * soon as it has a schema entry - and {@code ConfigSchemaTest} already fails the build if it does
 * not have one. That is the whole reason the schema exists: a settings UI that restates the bounds
 * drifts from {@code validated()} silently, and the drift shows up as a value the screen let you
 * pick and the mod then ignored.
 *
 * <p><b>Cloth Config is optional</b>, so this class must never be loaded when it is absent - the
 * missing types would fail to resolve. The guard cannot live here, because a guard inside a class
 * that cannot be loaded is not a guard: it lives in {@link Settings}, which is what callers use.
 *
 * <p>Edits go straight into the live {@link FlipperConfig}, but only when Cloth runs the save
 * consumers, which it does on Save and not on Cancel. {@link #apply()} then does what {@code /flip
 * reload} does, for the same reasons: clamp, write the file, and re-rank, because a changed
 * bankroll or {@code maxAdverseDrift} reorders the list without the order book moving.
 */
public final class FlipConfigScreen {
	/** Roughly the width of the value column, past which a tooltip line looks like a paragraph. */
	private static final int TOOLTIP_LINE_CHARS = 48;

	private FlipConfigScreen() {
	}

	/** Call through {@link Settings#open(Screen)}, which checks that Cloth is there first. */
	public static Screen create(Screen parent) {
		FlipperConfig config = SkyblockFlipperClient.config();

		// A throwaway instance is the one honest source of "what this setting ships as", which is
		// what Cloth's reset arrow puts back. Restating the defaults here would be a third copy.
		FlipperConfig defaults = new FlipperConfig();

		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.literal("Skyblock Flipper"))
				.setSavingRunnable(FlipConfigScreen::apply);

		ConfigEntryBuilder entries = builder.entryBuilder();

		for (ConfigSchema.Group group : ConfigSchema.groups()) {
			ConfigCategory category = builder.getOrCreateCategory(Component.literal(group.title()));

			for (ConfigSchema.Entry entry : group.entries()) {
				category.addEntry(entry(entries, entry, config, defaults));
			}
		}

		return builder.build();
	}

	/**
	 * One schema entry as one Cloth widget.
	 *
	 * <p>A switch over the sealed hierarchy rather than a chain of {@code instanceof}: when a new
	 * kind of setting is added to the schema, this stops compiling instead of silently omitting it.
	 */
	private static AbstractConfigListEntry<?> entry(ConfigEntryBuilder entries,
			ConfigSchema.Entry entry, FlipperConfig config, FlipperConfig defaults) {
		Component label = Component.literal(entry.label());
		Component[] tooltip = tooltip(entry.help());

		return switch (entry) {
			case ConfigSchema.Entry.Flag flag -> entries
					.startBooleanToggle(label, flag.get().test(config))
					.setDefaultValue(flag.get().test(defaults))
					.setTooltip(tooltip)
					.setSaveConsumer(value -> flag.set().accept(config, value))
					.build();

			// Every int setting is a small count - lines, days, hours, a perk level - so a slider
			// is both usable and incapable of producing a value validated() would clamp.
			case ConfigSchema.Entry.IntRange range -> entries
					.startIntSlider(label, range.get().applyAsInt(config), range.min(), range.max())
					.setDefaultValue(range.get().applyAsInt(defaults))
					.setTooltip(tooltip)
					.setSaveConsumer(value -> range.set().accept(config, value))
					.build();

			// Coin amounts are not: a slider from zero to a trillion cannot express ten million.
			case ConfigSchema.Entry.LongRange range -> entries
					.startLongField(label, range.get().applyAsLong(config))
					.setDefaultValue(range.get().applyAsLong(defaults))
					.setMin(range.min())
					.setMax(range.max())
					.setTooltip(tooltip)
					.setSaveConsumer(value -> range.set().accept(config, value))
					.build();

			case ConfigSchema.Entry.Ratio ratio -> entries
					.startDoubleField(label, ratio.get().applyAsDouble(config))
					.setDefaultValue(ratio.get().applyAsDouble(defaults))
					.setMin(ratio.min())
					.setMax(ratio.max())
					.setTooltip(tooltip)
					.setSaveConsumer(value -> ratio.set().accept(config, value))
					.build();

			// A cycling button, not a dropdown: no choice here has more than seven options, and the
			// values are already strings the schema maps to and from the field.
			case ConfigSchema.Entry.Choice choice -> entries
					.startSelector(label, choice.options().toArray(new String[0]),
							choice.get().apply(config))
					.setDefaultValue(choice.get().apply(defaults))
					.setTooltip(tooltip)
					.setSaveConsumer(value -> choice.set().accept(config, value))
					.build();

			case ConfigSchema.Entry.Text text -> entries
					.startStrField(label, text.get().apply(config))
					.setDefaultValue(text.get().apply(defaults))
					.setTooltip(tooltip)
					.setSaveConsumer(value -> text.set().accept(config, value))
					.build();
		};
	}

	/**
	 * The schema's help text as tooltip lines.
	 *
	 * <p>Cloth treats each component as one line and does not wrap, so a two-sentence explanation
	 * arrives as one line wider than the window. Wrapped on words at a fixed width rather than
	 * measured against the font: the tooltip is built once here, the window it will be shown in can
	 * be resized afterwards, and a character count is close enough for prose that is already short.
	 */
	private static Component[] tooltip(String help) {
		List<Component> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();

		for (String word : help.split(" ")) {
			if (!line.isEmpty() && line.length() + 1 + word.length() > TOOLTIP_LINE_CHARS) {
				lines.add(Component.literal(line.toString()));
				line.setLength(0);
			}

			if (!line.isEmpty()) {
				line.append(' ');
			}

			line.append(word);
		}

		if (!line.isEmpty()) {
			lines.add(Component.literal(line.toString()));
		}

		return lines.toArray(new Component[0]);
	}

	/** Runs on Save only. Mirrors {@code /flip reload}, minus the re-read that just happened. */
	private static void apply() {
		FlipperConfig config = SkyblockFlipperClient.config().validated();

		if (!SkyblockFlipperClient.saveConfig()) {
			SkyblockFlipper.LOGGER.error("Settings changed in game but could not be written to {}",
					SkyblockFlipperClient.configFile());
		}

		// Only when the switch actually moved: a restart drops the poller's backoff state and makes
		// the next sweep a cold one, which is not what changing the HUD margin should cost.
		if (config.pollingEnabled != MarketDataService.isRunning()) {
			MarketDataService.restart();
		}

		// The book has not moved, but bankroll, minProfitPerFlip, minConfidence and maxAdverseDrift
		// all rerank it, and the HUD's cache is only rebuilt when the revision changes.
		CandidateFeed.invalidate();
	}
}
