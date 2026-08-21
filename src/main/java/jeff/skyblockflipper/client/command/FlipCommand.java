package jeff.skyblockflipper.client.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import jeff.skyblockflipper.SkyblockFlipper;
import jeff.skyblockflipper.client.CandidateFeed;
import jeff.skyblockflipper.client.FlipIntentsService;
import jeff.skyblockflipper.client.LedgerService;
import jeff.skyblockflipper.client.MarketDataService;
import jeff.skyblockflipper.client.NpcCheckInService;
import jeff.skyblockflipper.client.NpcProbeService;
import jeff.skyblockflipper.client.TapeSyncService;
import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.client.gui.FlipKeybinds;
import jeff.skyblockflipper.client.gui.Settings;
import jeff.skyblockflipper.client.track.CaptureService;
import jeff.skyblockflipper.client.track.MenuMemory;
import jeff.skyblockflipper.client.track.TrackerService;
import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.config.FlipperConfig;
import jeff.skyblockflipper.core.ledger.LedgerEntry;
import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.MayorInfo;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.CombineJob;
import jeff.skyblockflipper.core.strategy.CraftJob;
import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.NpcBasket;
import jeff.skyblockflipper.core.strategy.NpcProbe;
import jeff.skyblockflipper.core.strategy.NpcReprice;
import jeff.skyblockflipper.core.strategy.StrategyKind;
import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.text.Guide;
import jeff.skyblockflipper.core.track.BazaarSlots;
import jeff.skyblockflipper.core.track.CaptureLog;
import jeff.skyblockflipper.core.track.CapturedMenu;
import jeff.skyblockflipper.core.track.CapturedSlot;
import jeff.skyblockflipper.core.track.TradeTracker;
import jeff.skyblockflipper.core.valuation.NpcEdge;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import java.io.IOException;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * The {@code /flip} command tree. Client-side only: the command never reaches the server, so it
 * works on Hypixel without the server knowing it exists.
 */
public final class FlipCommand {
	private static final int DEFAULT_LIMIT = 10;

	private FlipCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(build()));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> build() {
		return ClientCommands.literal("flip")
				.executes(ctx -> {
					// Honours the configured filter, unlike the named subcommands: typing /flip
					// bazaar is a statement of intent, typing /flip is not.
					StrategyKind only = SkyblockFlipperClient.config().filteredKind();
					showTop(ctx.getSource(), only, only == null
							? "Top flips right now"
							: "Top " + only.label().toLowerCase(Locale.ROOT) + " flips right now");
					return 1;
				})
				.then(ClientCommands.literal("bazaar")
						.executes(ctx -> {
							showTop(ctx.getSource(), StrategyKind.BAZAAR_SPREAD, "Best bazaar spreads");
							return 1;
						}))
				.then(ClientCommands.literal("npc")
						.executes(ctx -> {
							showTop(ctx.getSource(), StrategyKind.NPC_FLIP, "Bazaar prices below NPC buy price");
							return 1;
						})
						// The ranked list answers "which one item is worth the most"; the basket
						// answers "what do I do with all my order slots", which is the question
						// this strategy is actually about.
						.then(ClientCommands.literal("plan")
								.executes(ctx -> {
									showBasket(ctx.getSource());
									return 1;
								}))
						.then(ClientCommands.literal("reprice")
								.executes(ctx -> {
									showReprice(ctx.getSource());
									return 1;
								}))
						// The one question the tape cannot answer, because every sample in it was
						// taken from a book with none of your own orders in it. See NpcProbe.
						.then(ClientCommands.literal("probe")
								.executes(ctx -> {
									showProbe(ctx.getSource());
									return 1;
								})
								.then(ClientCommands.literal("stop")
										.executes(ctx -> stopProbe(ctx.getSource()))
										.then(ClientCommands.argument("id", StringArgumentType.greedyString())
												.suggests(FlipCommand::suggestProbedItems)
												.executes(ctx -> stopProbe(ctx.getSource(),
														StringArgumentType.getString(ctx, "id")))))
								// Greedy, because the way in is the name off the screen and names have
								// spaces in them.
								.then(ClientCommands.argument("id", StringArgumentType.greedyString())
										.suggests(FlipCommand::suggestBazaarItems)
										.executes(ctx -> startProbe(ctx.getSource(),
												StringArgumentType.getString(ctx, "id"))))))
				.then(ClientCommands.literal("craft")
						.executes(ctx -> {
							showCrafts(ctx.getSource());
							return 1;
						})
						.then(ClientCommands.literal("stop")
								.executes(ctx -> stopCraft(ctx.getSource()))))
				.then(ClientCommands.literal("combine")
						.executes(ctx -> {
							showCombines(ctx.getSource());
							return 1;
						})
						.then(ClientCommands.literal("stop")
								.executes(ctx -> stopCombine(ctx.getSource()))))
				.then(ClientCommands.literal("snipe")
						.executes(ctx -> {
							showSnipes(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("guide")
						.executes(ctx -> {
							showGuide(ctx.getSource(), null);
							return 1;
						})
						.then(ClientCommands.argument("topic", StringArgumentType.word())
								.executes(ctx -> showGuide(ctx.getSource(),
										StringArgumentType.getString(ctx, "topic")))))
				.then(ClientCommands.literal("status")
						.executes(ctx -> {
							showStatus(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("config")
						.executes(ctx -> {
							showConfig(ctx.getSource());
							return 1;
						})
						.then(ClientCommands.literal("edit")
								.executes(ctx -> editConfig(ctx.getSource()))))
				.then(ClientCommands.literal("take")
						.then(ClientCommands.argument("rank", IntegerArgumentType.integer(1))
								.executes(ctx -> take(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "rank")))))
				.then(ClientCommands.literal("close")
						.then(ClientCommands.argument("id", StringArgumentType.word())
								.suggests((ctx, builder) -> suggestLedger(builder, true))
								.then(ClientCommands.argument("units", LongArgumentType.longArg(0))
										.then(ClientCommands.argument("price", DoubleArgumentType.doubleArg(0))
												.executes(ctx -> close(
														ctx.getSource(),
														StringArgumentType.getString(ctx, "id"),
														LongArgumentType.getLong(ctx, "units"),
														DoubleArgumentType.getDouble(ctx, "price")))))))
				.then(ClientCommands.literal("abandon")
						.then(ClientCommands.argument("id", StringArgumentType.word())
								.suggests((ctx, builder) -> suggestLedger(builder, true))
								.executes(ctx -> abandon(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
				.then(ClientCommands.literal("ledger")
						.executes(ctx -> {
							showLedger(ctx.getSource());
							return 1;
						})
						.then(ClientCommands.literal("forget")
								.then(ClientCommands.argument("id", StringArgumentType.word())
										.suggests((ctx, builder) -> suggestLedger(builder, false))
										.executes(ctx -> forget(ctx.getSource(),
												StringArgumentType.getString(ctx, "id")))))
						// Deleting history cannot be undone, so the first call only counts what it
						// would delete and the word "confirm" is what actually does it.
						.then(ClientCommands.literal("clear")
								.executes(ctx -> offerClear(ctx.getSource(), false))
								.then(ClientCommands.literal("confirm")
										.executes(ctx -> clear(ctx.getSource(), false)))
								.then(ClientCommands.literal("unquoted")
										.executes(ctx -> offerClear(ctx.getSource(), true))
										.then(ClientCommands.literal("confirm")
												.executes(ctx -> clear(ctx.getSource(), true))))))
				.then(ClientCommands.literal("hud")
						.executes(ctx -> {
							toggleHud(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("capture")
						.executes(ctx -> {
							toggleCapture(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("track")
						.executes(ctx -> {
							toggleAutoTrack(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("menu")
						.executes(ctx -> {
							describeLastMenu(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("sync")
						.executes(ctx -> {
							syncTape(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("gui")
						.executes(ctx -> {
							// Deferred rather than opened inline: the chat screen is still closing
							// when a command executes, and replacing it mid-teardown leaves the
							// new screen without a size.
							Minecraft.getInstance().execute(FlipKeybinds::open);
							return 1;
						}))
				.then(ClientCommands.literal("reload")
						.executes(ctx -> {
							if (SkyblockFlipperClient.reloadConfig()) {
								// Picks up a flipped pollingEnabled without a game restart.
								MarketDataService.restart();
								// A new bankroll changes the ranking without the book moving.
								CandidateFeed.invalidate();
								ctx.getSource().sendFeedback(Chat.prefixed(
										Component.literal("Config reloaded.").withStyle(ChatFormatting.GREEN)));
								return 1;
							}

							ctx.getSource().sendError(Component.literal("Config reload failed - see the log.")
									.withStyle(ChatFormatting.RED));
							return 0;
						}));
	}

	/** @param kind null for the merged ranking across every strategy */
	private static void showTop(FabricClientCommandSource source, StrategyKind kind, String heading) {
		if (!marketReady(source)) {
			return;
		}

		MarketData data = MarketDataService.data();

		// Ranked on demand rather than read from the HUD's cache: someone who just typed the
		// command is asking about the book as it is now.
		List<FlipCandidate> candidates = CandidateFeed.rank(kind, DEFAULT_LIMIT);

		CandidateRenderer.renderList(source, candidates, heading);
		LedgerRenderer.renderCaptureWarning(source, LedgerService.ledger().stats(kind), kind);

		if (data.mayor().isDerpy()) {
			source.sendFeedback(Component.literal("Derpy is mayor: auction fees are 4x. Bazaar is unaffected.")
					.withStyle(ChatFormatting.RED));
		}
	}

	/**
	 * The whole trip: what to do with the orders already resting, then the new ones to place.
	 *
	 * <p>Every order slot and the bankroll allocated once, rather than a ranked list where each row
	 * is sized against the whole bankroll on its own - and, since the worklist, allocated over what
	 * is actually free rather than over an account with nothing in it.
	 */
	private static void showBasket(FabricClientCommandSource source) {
		if (!marketReady(source)) {
			return;
		}

		// Placing a basket is the start of a cycle, so the next reminder is due a check-in interval
		// from here rather than from whenever the last one happened to fire.
		NpcCheckInService.acknowledge();
		NpcRenderer.renderWorklist(source, CandidateFeed.worklist());

		if (!TrackerService.enabled()) {
			source.sendFeedback(Component.literal(
							"  Automatic tracking is off, so this assumes every order slot is empty. "
									+ "/flip track makes it size around what you already have resting.")
					.withStyle(ChatFormatting.YELLOW));
		}
	}

	/**
	 * Which resting buy orders have been outbid, and which have been outbid past the point of being
	 * worth a slot.
	 *
	 * <p>Reads the orders as the orders menu last described them, which is the only place their real
	 * posted price exists - a plan's quoted cost includes chasing it had not paid yet. That makes
	 * this depend on automatic tracking being on and on the menu having been opened at least once,
	 * and both are worth saying out loud rather than reporting an empty list.
	 */
	private static void showReprice(FabricClientCommandSource source) {
		if (!marketReady(source)) {
			return;
		}

		if (!TrackerService.enabled()) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"Nothing has read your orders menu: automatic tracking is off. Run /flip track, "
							+ "then open Bazaar -> Manage Orders once.").withStyle(ChatFormatting.YELLOW)));
			return;
		}

		// Asking counts as having been reminded, whatever the answer turns out to be.
		NpcCheckInService.acknowledge();

		if (TrackerService.restingBuyOrders().isEmpty()) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"No resting buy orders are known yet. Open Bazaar -> Manage Orders and run this "
							+ "again.").withStyle(ChatFormatting.YELLOW)));
			return;
		}

		NpcRenderer.renderResting(source, CandidateFeed.worklist());
	}

	/**
	 * Quotes the premium price for one item and starts watching what happens to an order there.
	 *
	 * <p>Settles the one assumption no amount of tape can: every sample on it came from a book with
	 * none of the player's orders in it, so whether competitors re-post above whatever is on top is
	 * unobservable from outside. One order and one session answers it. It answered no on 2026-08-16,
	 * which is why the mod no longer posts above the book at all - so this is now an experiment
	 * rather than a setup step, and the only thing that could re-open that. See {@link NpcProbe}.
	 */
	private static int startProbe(FabricClientCommandSource source, String id) {
		if (!marketReady(source)) {
			return 0;
		}

		String itemId = resolveBazaarItem(source, id);

		if (itemId == null) {
			return 0;
		}

		MarketData data = MarketDataService.data();
		Optional<BazaarProduct> product = data.bazaar().product(itemId);

		if (product.isEmpty()) {
			source.sendError(Component.literal("No bazaar product called " + itemId + ".")
					.withStyle(ChatFormatting.RED));
			return 0;
		}

		OptionalDouble outbid = product.get().outbidBuyOrder();

		if (outbid.isEmpty()) {
			source.sendError(Component.literal(itemId + " has no buy orders to sit above.")
					.withStyle(ChatFormatting.RED));
			return 0;
		}

		FlipperConfig config = SkyblockFlipperClient.config();
		NpcEdge edge = data.npcEdges().edgeFor(itemId).orElse(null);

		if (edge == null) {
			source.sendError(Component.literal(
					"Nothing on the tape has watched " + itemId + " long enough to measure its drift, "
							+ "so there is no premium to probe with.").withStyle(ChatFormatting.RED));
			return 0;
		}

		// Fixed rather than read from a setting: paying a premium is no longer something the strategy
		// does, so this is the experiment that would have to produce new evidence before it were.
		double premium = DEFAULT_PROBE_PREMIUM * edge.bidDriftPerHour() * config.npcRestingHours;
		double price = outbid.getAsDouble() + premium;

		if (premium <= 0.0d) {
			source.sendError(Component.literal(
					"The tape has " + itemId + " drifting upward by nothing at all, so there is no "
							+ "premium to pay and nothing to find out.").withStyle(ChatFormatting.RED));
			return 0;
		}

		String name = data.catalog().displayName(itemId);
		NpcProbeService.open(itemId, name, price, premium);

		source.sendFeedback(Chat.prefixed(Component.literal("Probing " + name)
				.withStyle(ChatFormatting.GREEN)));
		line(source, "post one buy order at", String.format("%.1f", price));
		line(source, "which is", String.format("%.1f above the book, %.2gx the %.1fh drift",
				premium, DEFAULT_PROBE_PREMIUM, config.npcRestingHours));
		line(source, "then", "leave it. /flip npc probe says whether anything outbid it.");
		source.sendFeedback(Component.literal(
						"  Being outbid by 0.1 is the increment button and is expected - watch for an "
								+ "outbid far bigger than that, which is the thing the premium is paid to sit above.")
				.withStyle(ChatFormatting.GRAY));
		source.sendFeedback(Component.literal(
						"  Memory only - a restart forgets it, so the game must stay open. Nothing is "
								+ "placed for you. Probe as many items at once as you like, one order each; "
								+ "do not probe an item you are also trading, since any fill on it ends "
								+ "the probe.")
				.withStyle(ChatFormatting.GRAY));

		return 1;
	}

	/**
	 * The bazaar item the player meant, or null after telling them why nothing was picked.
	 *
	 * <p>Ids are not guessable from names - Nether Wart Distillate is {@code NETHER_STALK_DISTILLATE}
	 * - so every command that takes an item accepts the name off the screen too, and says what it
	 * could have meant rather than failing blank. Restricted to what the bazaar actually trades,
	 * because an item the bazaar has never listed is no use to any of these commands.
	 */
	private static String resolveBazaarItem(FabricClientCommandSource source, String query) {
		MarketData data = MarketDataService.data();
		ItemCatalog.Lookup found = data.catalog().find(query, data.bazaar().products().keySet());

		if (found.only().isPresent()) {
			return found.only().get();
		}

		if (found.isEmpty()) {
			source.sendError(Component.literal("Nothing in the bazaar matches \"" + query + "\".")
					.withStyle(ChatFormatting.RED));
			return null;
		}

		source.sendError(Component.literal("\"" + query + "\" could mean " + found.candidates().size()
				+ " bazaar items. Did you want:").withStyle(ChatFormatting.RED));

		for (String candidate : found.candidates().stream().limit(AMBIGUITY_SHOWN).toList()) {
			line(source, data.catalog().displayName(candidate), candidate);
		}

		return null;
	}

	/** How many of an ambiguous query's matches are worth printing before the chat is a wall. */
	private static final int AMBIGUITY_SHOWN = 8;

	/** Tab completion over the items a probe is actually running on, for stopping one of them. */
	private static CompletableFuture<Suggestions> suggestProbedItems(
			CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
		String typed = builder.getRemaining().toLowerCase(Locale.ROOT);

		for (NpcProbe probe : NpcProbeService.all()) {
			if (probe.displayName().toLowerCase(Locale.ROOT).startsWith(typed)) {
				builder.suggest(probe.displayName(), Component.literal(probe.itemId()));
			} else if (probe.itemId().toLowerCase(Locale.ROOT).startsWith(typed)) {
				builder.suggest(probe.itemId(), Component.literal(probe.displayName()));
			}
		}

		return builder.buildFuture();
	}

	/**
	 * Tab completion for a ledger entry argument.
	 *
	 * <p>A ledger id is generated rather than spelled, so unlike an item there is no name to type
	 * instead and completion is the only way to get one right without reading it off {@code /flip
	 * ledger} first.
	 *
	 * @param openOnly true for the commands that can only act on a position still open
	 */
	private static CompletableFuture<Suggestions> suggestLedger(SuggestionsBuilder builder,
			boolean openOnly) {
		String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
		List<LedgerEntry> entries = openOnly
				? LedgerService.ledger().openEntries()
				: LedgerService.ledger().all();

		for (LedgerEntry entry : entries) {
			if (entry.id().toLowerCase(Locale.ROOT).startsWith(typed)) {
				builder.suggest(entry.id(), Component.literal(
						entry.displayName() + " x" + entry.units()));
			}
		}

		return builder.buildFuture();
	}

	/** Tab completion for an item argument, over the names and ids the bazaar is trading now. */
	private static CompletableFuture<Suggestions> suggestBazaarItems(
			CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
		MarketData data = MarketDataService.data();
		String typed = builder.getRemaining().toLowerCase(Locale.ROOT);

		for (String id : data.bazaar().products().keySet()) {
			String name = data.catalog().displayName(id);

			// Offering the id and the name separately is what makes both spellings tab-completable,
			// and the name is the one a player has actually seen.
			if (name.toLowerCase(Locale.ROOT).startsWith(typed)) {
				builder.suggest(name, Component.literal(id));
			} else if (id.toLowerCase(Locale.ROOT).startsWith(typed)) {
				builder.suggest(id, Component.literal(name));
			}
		}

		return builder.buildFuture();
	}

	/**
	 * The premium to probe with.
	 *
	 * <p>A whole resting window's measured drift, which is the largest premium the strategy ever
	 * asked for while it asked for one. The probe is asking whether a competitor will climb above
	 * your order whatever you paid, so the useful test is the generous end: an order that cannot
	 * hold the top at a full window's drift will not hold it at less.
	 */
	private static final double DEFAULT_PROBE_PREMIUM = 1.0d;

	private static void showProbe(FabricClientCommandSource source) {
		List<NpcProbe> probes = NpcProbeService.all();

		if (probes.isEmpty()) {
			source.sendFeedback(Chat.prefixed(Component.literal(
							"No probes running. /flip npc probe <item> starts one.")
					.withStyle(ChatFormatting.YELLOW)));
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal(
				probes.size() + (probes.size() == 1 ? " probe" : " probes") + " running")
				.withStyle(ChatFormatting.WHITE)));

		long now = System.currentTimeMillis();

		for (NpcProbe probe : probes) {
			source.sendFeedback(Component.literal("  " + probe.report(now)).withStyle(verdict(probe)));
		}

		if (!TrackerService.enabled()) {
			source.sendFeedback(Component.literal(
							"  Tracking is off, so a filled order cannot be told from an unbeatable one. "
									+ "Turn on autoTrackEnabled before trusting these.")
					.withStyle(ChatFormatting.RED));
		}
	}

	/**
	 * The colour a probe's finding deserves.
	 *
	 * <p>Green covers a fill and an order nothing has repriced over, which includes one nudged by the
	 * increment button that keeps taking the top back - that is the premium working, not failing.
	 * Yellow is reserved for a competitor bidding past it on purpose, which is the only outcome that
	 * argues against paying a premium on that item.
	 */
	private static ChatFormatting verdict(NpcProbe probe) {
		return !probe.everOutbid() || probe.nudgedOnly() ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
	}

	private static int stopProbe(FabricClientCommandSource source) {
		List<NpcProbe> ended = NpcProbeService.stopAll();

		if (ended.isEmpty()) {
			source.sendFeedback(Chat.prefixed(Component.literal("No probes were running.")
					.withStyle(ChatFormatting.YELLOW)));
			return 0;
		}

		long now = System.currentTimeMillis();

		source.sendFeedback(Chat.prefixed(Component.literal(
				"Stopped " + ended.size() + (ended.size() == 1 ? " probe" : " probes"))
				.withStyle(ChatFormatting.WHITE)));

		for (NpcProbe probe : ended) {
			source.sendFeedback(Component.literal("  " + probe.report(now)).withStyle(verdict(probe)));
		}

		return 1;
	}

	private static int stopProbe(FabricClientCommandSource source, String query) {
		String itemId = resolveBazaarItem(source, query);

		if (itemId == null) {
			return 0;
		}

		Optional<NpcProbe> ended = NpcProbeService.stop(itemId);

		if (ended.isEmpty()) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"No probe was running on " + itemId + ".").withStyle(ChatFormatting.YELLOW)));
			return 0;
		}

		source.sendFeedback(Chat.prefixed(Component.literal(
				"Probe ended. " + ended.get().report(System.currentTimeMillis()))
				.withStyle(verdict(ended.get()))));
		return 1;
	}

	/** Whether there is a book to answer with, with the reason there is not if there is not. */
	private static boolean marketReady(FabricClientCommandSource source) {
		if (MarketDataService.data().hasBazaar()) {
			return true;
		}

		source.sendFeedback(Chat.prefixed(Component.literal(
				MarketDataService.isRunning()
						? "Waiting for the first market fetch, try again in a few seconds."
						: "Polling is disabled - run /flip config and set pollingEnabled.")
				.withStyle(ChatFormatting.YELLOW)));
		return false;
	}

	/**
	 * Auction flips get their own explanation of why the list is empty, because there are several
	 * quite different reasons and "nothing found" reads like a broken feature for all of them.
	 */
	/**
	 * Craft flips, with the one refusal worth explaining said out loud.
	 *
	 * <p>An empty list with crafting switched off looks exactly like an empty list with nothing
	 * profitable on the book, and the player has no way to tell which they are looking at.
	 */
	private static void showCrafts(FabricClientCommandSource source) {
		if (!SkyblockFlipperClient.config().craftFlipsEnabled) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"Craft flips are off - turn them on in /flip config edit.")
					.withStyle(ChatFormatting.YELLOW)));
			return;
		}

		CraftJob job = CandidateFeed.craftJob();

		if (job != null) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"Working " + job.displayName() + " - the bazaar panel has the steps. "
							+ "/flip craft stop to leave it.").withStyle(ChatFormatting.GRAY)));
		}

		showTop(source, StrategyKind.CRAFT, "Best things to craft and sell");
	}

	/** Stops the bazaar panel following a craft, so it goes back to the NPC basket. */
	private static int stopCraft(FabricClientCommandSource source) {
		String following = CandidateFeed.craftOutputId();

		CandidateFeed.stopCraft();
		source.sendFeedback(Chat.prefixed(Component.literal(following == null
				? "No craft was being worked."
				: "Stopped working that craft.").withStyle(ChatFormatting.GRAY)));

		return 1;
	}

	/**
	 * Combine flips, ranked here rather than in the main list because their edge is per anvil click,
	 * not per hour, so profit-per-hour ranking buries them.
	 *
	 * <p>Says so out loud when combining is off, for the same reason craft does: an empty list with
	 * the strategy switched off looks identical to one with nothing profitable on the book.
	 */
	private static void showCombines(FabricClientCommandSource source) {
		if (!SkyblockFlipperClient.config().combineFlipsEnabled) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"Combine flips are off - turn them on in /flip config edit.")
					.withStyle(ChatFormatting.YELLOW)));
			return;
		}

		CombineJob job = CandidateFeed.combineJob();

		if (job != null) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"Working " + job.displayName() + " - the bazaar panel has the steps. "
							+ "/flip combine stop to leave it.").withStyle(ChatFormatting.GRAY)));
		}

		showTop(source, StrategyKind.COMBINE, "Best books to combine and sell");
	}

	/** Stops the bazaar panel following a combine, so it goes back to the NPC basket. */
	private static int stopCombine(FabricClientCommandSource source) {
		String following = CandidateFeed.combineOutputId();

		CandidateFeed.stopCombine();
		source.sendFeedback(Chat.prefixed(Component.literal(following == null
				? "No combine was being worked."
				: "Stopped working that combine.").withStyle(ChatFormatting.GRAY)));

		return 1;
	}

	private static void showSnipes(FabricClientCommandSource source) {
		FlipperConfig config = SkyblockFlipperClient.config();
		MarketData data = MarketDataService.data();

		if (!config.scanAuctions) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"Auction scanning is off - set scanAuctions in the config to enable it.")
					.withStyle(ChatFormatting.YELLOW)));
			return;
		}

		if (data.values().isEmpty()) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"No valuations yet. Item values are learned from realized sales, so this needs "
							+ "the game to have been running a while.")
					.withStyle(ChatFormatting.YELLOW)));
			return;
		}

		if (!data.hasScannedAuctions()) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"First auction sweep has not finished yet.").withStyle(ChatFormatting.YELLOW)));
			return;
		}

		showTop(source, StrategyKind.AUCTION_VALUE, "Listings below fair value");
	}

	/** Records the flip on the line the player is looking at, at the numbers they saw. */
	private static int take(FabricClientCommandSource source, int rank) {
		List<FlipCandidate> shown = CandidateRenderer.lastShown();

		if (shown.isEmpty()) {
			source.sendError(Component.literal("Nothing to take - run /flip first.")
					.withStyle(ChatFormatting.RED));
			return 0;
		}

		if (rank > shown.size()) {
			source.sendError(Component.literal("That list only had " + shown.size() + " entries.")
					.withStyle(ChatFormatting.RED));
			return 0;
		}

		FlipCandidate candidate = shown.get(rank - 1);

		try {
			LedgerEntry entry = LedgerService.ledger().open(candidate, System.currentTimeMillis());
			// So the NPC side does not later adopt this buy order as its own, on an item it could sell.
			FlipIntentsService.record(candidate.itemId(), candidate.kind(), System.currentTimeMillis());

			source.sendFeedback(Chat.prefixed(Component.literal("Took " + entry.displayName() + " as ")
					.withStyle(ChatFormatting.WHITE)
					.append(Component.literal(entry.id()).withStyle(ChatFormatting.YELLOW))));
			source.sendFeedback(Component.literal("  quoted " + Coins.format(candidate.totalNetProfit())
					+ " on " + candidate.units() + " units. Close it with /flip close " + entry.id()
					+ " <units sold> <sell price>").withStyle(ChatFormatting.DARK_GRAY));
			return 1;
		} catch (IOException e) {
			return ledgerWriteFailed(source, e);
		}
	}

	private static int close(FabricClientCommandSource source, String id, long unitsSold, double unitSellPrice) {
		try {
			// Fees come from live config so the realized side is computed on the same basis the
			// quote used; otherwise the capture rate would measure the fee model, not the market.
			return LedgerService.ledger()
					.close(id, unitsSold, unitSellPrice, CandidateFeed.context().fees())
					.map(entry -> {
						double realized = entry.realizedTotal();
						double quoted = entry.quotedOnFilled();

						source.sendFeedback(Chat.prefixed(Component.literal("Closed " + entry.displayName())
								.withStyle(ChatFormatting.WHITE)));
						source.sendFeedback(Component.literal("  realized " + Coins.format(realized)
								+ " against " + Coins.format(quoted) + " quoted on " + entry.unitsSold()
								+ "/" + entry.units() + " units")
								.withStyle(realized >= quoted ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
						return 1;
					})
					.orElseGet(() -> {
						source.sendError(Component.literal("No open position with id " + id + ".")
								.withStyle(ChatFormatting.RED));
						return 0;
					});
		} catch (IOException e) {
			return ledgerWriteFailed(source, e);
		}
	}

	private static int abandon(FabricClientCommandSource source, String id) {
		try {
			return LedgerService.ledger().abandon(id)
					.map(entry -> {
						source.sendFeedback(Chat.prefixed(Component.literal(
								"Abandoned " + entry.displayName() + " - counted against the fill rate.")
								.withStyle(ChatFormatting.GRAY)));
						return 1;
					})
					.orElseGet(() -> {
						source.sendError(Component.literal("No open position with id " + id + ".")
								.withStyle(ChatFormatting.RED));
						return 0;
					});
		} catch (IOException e) {
			return ledgerWriteFailed(source, e);
		}
	}

	/**
	 * Deletes one entry outright.
	 *
	 * <p>Separate from {@code abandon}, which is about a plan that did not work out and keeps its
	 * units in the fill rate. This is for an entry that should never have been recorded - the stack
	 * of materials you bought to play the game with, not to flip.
	 */
	private static int forget(FabricClientCommandSource source, String id) {
		try {
			return LedgerService.ledger().forget(id)
					.map(entry -> {
						source.sendFeedback(Chat.prefixed(Component.literal(
								"Forgot " + entry.displayName() + " - it is out of the ledger and out "
										+ "of every rate.").withStyle(ChatFormatting.GRAY)));
						return 1;
					})
					.orElseGet(() -> {
						source.sendError(Component.literal("No ledger entry with id " + id + ".")
								.withStyle(ChatFormatting.RED));
						return 0;
					});
		} catch (IOException e) {
			return ledgerWriteFailed(source, e);
		}
	}

	/** Says what a clear would delete and how to ask for it, without deleting anything. */
	private static int offerClear(FabricClientCommandSource source, boolean unquotedOnly) {
		long count = LedgerService.ledger().count(filter(unquotedOnly));

		if (count == 0L) {
			source.sendFeedback(Chat.prefixed(Component.literal(unquotedOnly
					? "No untracked-trade entries to clear."
					: "The ledger is already empty.").withStyle(ChatFormatting.GRAY)));
			return 1;
		}

		String command = unquotedOnly ? "/flip ledger clear unquoted confirm" : "/flip ledger clear confirm";

		source.sendFeedback(Chat.prefixed(Component.literal(unquotedOnly
				? count + " entries came from trades the mod never quoted."
				: count + " entries in the ledger, quoted flips included.")
				.withStyle(ChatFormatting.YELLOW)));
		source.sendFeedback(Component.literal("  This cannot be undone. Run " + command + " to delete them.")
				.withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	private static int clear(FabricClientCommandSource source, boolean unquotedOnly) {
		try {
			int removed = LedgerService.ledger().forgetAll(filter(unquotedOnly));

			source.sendFeedback(Chat.prefixed(Component.literal("Deleted " + removed + " ledger "
					+ (removed == 1 ? "entry." : "entries.")).withStyle(ChatFormatting.GREEN)));
			return 1;
		} catch (IOException e) {
			return ledgerWriteFailed(source, e);
		}
	}

	/**
	 * Which entries a clear touches.
	 *
	 * <p>The unquoted filter is the useful one: it deletes what automatic tracking recorded off your
	 * ordinary buying and selling, and keeps every flip you took from the mod's own list.
	 */
	private static Predicate<LedgerEntry> filter(boolean unquotedOnly) {
		return unquotedOnly ? entry -> !entry.isQuoted() : entry -> true;
	}

	private static void showLedger(FabricClientCommandSource source) {
		LedgerRenderer.renderOpen(source,
				LedgerService.ledger().openEntries(),
				LedgerService.ledger().committedCapital());
		LedgerRenderer.renderStats(source, LedgerService.ledger().stats(null));
	}

	private static int ledgerWriteFailed(FabricClientCommandSource source, IOException e) {
		SkyblockFlipper.LOGGER.error("Ledger write failed", e);
		source.sendError(Component.literal("Could not write the ledger - see the log.")
				.withStyle(ChatFormatting.RED));
		return 0;
	}

	private static void toggleHud(FabricClientCommandSource source) {
		FlipperConfig config = SkyblockFlipperClient.config();
		config.hudEnabled = !config.hudEnabled;

		if (!SkyblockFlipperClient.saveConfig()) {
			source.sendError(Component.literal("HUD toggled for this session, but the config could not be saved.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal("HUD " + (config.hudEnabled ? "on" : "off"))
				.withStyle(config.hudEnabled ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
	}

	/**
	 * Turns the trade-message capture on or off and says where the file is.
	 *
	 * <p>Reports the record count on the way out, because the failure mode of a capture session is
	 * finding out afterwards that nothing was written and having to play it again.
	 */
	/**
	 * Pulls the collector's tape now, rather than waiting for the next launch.
	 *
	 * <p>Run on a thread of its own and reported back when it lands. A first sync moves hundreds of
	 * megabytes and the merge rescans the local tape; doing that on the client thread would stop the
	 * game for as long as it took, which is exactly the freeze a player would report as a crash.
	 */
	private static void syncTape(FabricClientCommandSource source) {
		FlipperConfig config = SkyblockFlipperClient.config();
		TapeSyncService sync = MarketDataService.sync();

		// Read from the config, not from the service: the service object exists whenever polling
		// does, whether or not sync is configured, so a null check alone would send a sync at an
		// empty URL and get an unreported failure back.
		if (!config.tapeSyncEnabled || config.tapeSyncUrl.isEmpty()) {
			source.sendError(Component.literal(
					"Collector sync is off. Set tapeSyncEnabled and tapeSyncUrl, then /flip reload.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		if (sync == null) {
			source.sendError(Component.literal("Polling is off, so there is no tape to sync into.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal("Syncing from the collector...")
				.withStyle(ChatFormatting.GRAY)));

		Thread worker = new Thread(() -> {
			String outcome = sync.runNow();
			Minecraft.getInstance().execute(() -> source.sendFeedback(Chat.prefixed(
					Component.literal(outcome).withStyle(ChatFormatting.GREEN))));
		}, "skyblock-flipper-manual-sync");

		worker.setDaemon(true);
		worker.start();
	}

	/**
	 * Print the named slots of the last menu that was open.
	 *
	 * <p>The slot detector matches buttons by name, and the names of the screens an order is placed
	 * on were read off screenshots rather than a capture. This is how one gets confirmed without a
	 * play session: open the screen, close it, run the command, and every button on it is listed with
	 * the slot it sat in.
	 */
	private static void describeLastMenu(FabricClientCommandSource source) {
		Optional<CapturedMenu> menu = MenuMemory.last();

		if (menu.isEmpty()) {
			source.sendError(Component.literal(
					"No menu seen yet. Open one, close it, then run this."));
			return;
		}

		CapturedMenu last = menu.get();
		BazaarSlots.Screen screen = BazaarSlots.screenOf(last);

		source.sendFeedback(Chat.prefixed(Component.literal(
				last.title() + " - " + BazaarSlots.size(last) + " slots, read as " + screen)
				.withStyle(ChatFormatting.GOLD)));

		int shown = 0;

		for (CapturedSlot slot : last.slots()) {
			// The filler glass has no name and there is a lot of it. Everything a button could be
			// matched on is in the name.
			if (slot.name().isBlank()) {
				continue;
			}

			if (++shown > MENU_LINES) {
				source.sendFeedback(Component.literal("  ... and more")
						.withStyle(ChatFormatting.DARK_GRAY));
				return;
			}

			source.sendFeedback(Component.literal("  " + slot.index() + "  " + slot.name()
					+ (slot.itemId().isEmpty() ? "" : "  [" + slot.itemId() + "]"))
					.withStyle(ChatFormatting.GRAY));
		}
	}

	/** Chat holds about this many lines at once, and a full bazaar menu has fewer buttons than this. */
	private static final int MENU_LINES = 40;

	private static void toggleCapture(FabricClientCommandSource source) {
		FlipperConfig config = SkyblockFlipperClient.config();
		config.tradeCaptureEnabled = !config.tradeCaptureEnabled;

		if (!SkyblockFlipperClient.saveConfig()) {
			source.sendError(Component.literal(
					"Capture toggled for this session, but the config could not be saved.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		CaptureLog log = CaptureService.log();

		if (config.tradeCaptureEnabled) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"Capturing trade messages to " + CaptureService.file().getFileName()
							+ ". Buy, sell, cancel an order, and open your bazaar orders and your "
							+ "auctions so the menus get recorded too.")
					.withStyle(ChatFormatting.GREEN)));
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal(
				"Capture off. " + log.records() + " records this session, "
						+ (log.bytes() / 1024) + "KB in " + CaptureService.file())
				.withStyle(ChatFormatting.GRAY)));

		if (log.isFull()) {
			source.sendError(Component.literal("The capture file hit its size cap and stopped "
					+ "recording before you turned it off.").withStyle(ChatFormatting.RED));
		}
	}

	/**
	 * Turns automatic ledger filling on or off.
	 *
	 * <p>Says what it will and will not see, because the two limits are not guessable: a partial
	 * fill is announced in no chat line at all, and a sale of stock bought before tracking started
	 * settles against no position and is dropped.
	 */
	private static void toggleAutoTrack(FabricClientCommandSource source) {
		FlipperConfig config = SkyblockFlipperClient.config();
		config.autoTrackEnabled = !config.autoTrackEnabled;

		if (!SkyblockFlipperClient.saveConfig()) {
			source.sendError(Component.literal(
					"Tracking toggled for this session, but the config could not be saved.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		if (!config.autoTrackEnabled) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"Automatic tracking off. Positions already open stay in the ledger.")
					.withStyle(ChatFormatting.GRAY)));
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal(
				"Tracking your trades into the ledger. Open your bazaar orders menu now and then - "
						+ "a partial fill is announced nowhere else. Sales of stock you had before "
						+ "this was on settle against nothing and are skipped.")
				.withStyle(ChatFormatting.GREEN)));
	}

	private static void showStatus(FabricClientCommandSource source) {
		MarketData data = MarketDataService.data();

		if (!MarketDataService.isRunning()) {
			source.sendFeedback(Chat.prefixed(Component.literal("Poller stopped (pollingEnabled=false).")
					.withStyle(ChatFormatting.RED)));
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal("Poller running").withStyle(ChatFormatting.GREEN)));

		BazaarSnapshot bazaar = data.bazaar();
		line(source, "bazaar products", data.hasBazaar()
				? bazaar.products().size() + " (" + describeAge(data.bazaarAge()) + " ago)"
				: "waiting for first fetch");

		line(source, "item catalog", data.catalog().isEmpty()
				? "waiting for first fetch"
				: data.catalog().items().size() + " items");

		line(source, "sales recorded", data.salesRecorded() + " this session ("
				+ describeAge(data.salesAge()) + " ago)");

		// The rollup is what outlives retention, so it is worth saying out loud that it is filling.
		line(source, "sales rollup", data.salesRollupDays() == 0
				? "no completed days summarised yet"
				: data.salesRollupEntries() + " configurations over " + data.salesRollupDays()
						+ " day(s), kept past retention");

		line(source, "valuations", data.values().isEmpty()
				? "none yet (learned from realized sales)"
				: data.values().pricedConfigurations() + " item configurations from "
						+ data.values().salesConsidered() + " sales");

		TrendSnapshot trends = data.trends();
		String history = "off (bazaarTapeEnabled=false)";

		if (SkyblockFlipperClient.config().bazaarTapeEnabled) {
			history = trends.isEmpty()
					? "warming up (sampled every 5m)"
					: trends.samples() + " samples over " + trends.size() + " products, "
							+ trends.window().toHours() + "h window"
							+ (trends.productsWithDailyHistory() > 0
									? ", " + trends.productsWithDailyHistory() + " with daily rollup"
									: "")
							// Fills need an hour of uninterrupted sampling before they mean
							// anything, so a player who has just started the client would otherwise
							// have no way to tell whether the ranking is measured or assumed.
							+ (trends.productsWithMeasuredFills() > 0
									? ", " + trends.productsWithMeasuredFills() + " with measured fills"
									: ", fills still assumed (needs ~1h of uptime)");
		}

		line(source, "price history", history);

		String sweep = "disabled";

		if (SkyblockFlipperClient.config().scanAuctions) {
			sweep = data.hasScannedAuctions()
					? data.scanSummary() + " (" + describeAge(data.auctionsAge()) + " ago)"
					: "waiting for first sweep";
		}

		line(source, "auction sweep", sweep);

		MayorInfo mayor = data.mayor();
		if (mayor.isKnown()) {
			line(source, "mayor", mayor.name() + (mayor.isDerpy() ? " - AH FEES x4, avoid big flips" : ""));
		}

		line(source, "bazaar tax", String.format("%.3f%%",
				new Fees(SkyblockFlipperClient.config().bazaarFlipperLevel, mayor.isDerpy()).bazaarTaxRate() * 100.0d));
		// Resting orders are the half of tracking that has no other display: the ledger shows
		// positions, and an order that has filled but not been collected is not one yet.
		if (SkyblockFlipperClient.config().autoTrackEnabled) {
			TradeTracker tracker = TrackerService.tracker();
			int waiting = tracker.awaitingClaim().size();

			line(source, "trade tracking", tracker.resting().size() + " resting order(s), "
					+ tracker.settlements().size() + " trade(s) seen this session"
					+ (waiting > 0 ? ", " + waiting + " with coins to collect" : ""));
		}

		// The sync runs on its own thread five seconds after login and says nothing in chat, so
		// without this line the only report that it happened at all is the log file.
		TapeSyncService sync = MarketDataService.sync();

		if (sync != null && SkyblockFlipperClient.config().tapeSyncEnabled) {
			line(source, "collector sync", sync.lastRun() == null
					? "not run yet"
					: sync.lastOutcome() + " (" + describeAge(
							Duration.between(sync.lastRun(), Instant.now())) + " ago)");
		}

		line(source, "poll failures", String.valueOf(data.pollFailures()));

		if (!data.lastError().isEmpty()) {
			source.sendFeedback(Component.literal("  last error: " + data.lastError())
					.withStyle(ChatFormatting.RED));
		}
	}

	/**
	 * Opens the settings screen, or explains why there is not one.
	 *
	 * <p>Deferred through {@code Minecraft.execute} for the same reason {@code /flip gui} is: the
	 * chat screen is still tearing down while a command executes, and a screen set during that
	 * never gets a size.
	 */
	private static int editConfig(FabricClientCommandSource source) {
		if (!Settings.available()) {
			source.sendError(Component.literal(
					"The settings screen needs Cloth Config API. Without it, edit "
							+ SkyblockFlipperClient.configFile() + " and run /flip reload.")
					.withStyle(ChatFormatting.RED));
			return 0;
		}

		// No parent: this was opened from chat, so there is nothing to go back to.
		Minecraft.getInstance().execute(() -> Settings.open(null));
		return 1;
	}

	private static void showConfig(FabricClientCommandSource source) {
		FlipperConfig config = SkyblockFlipperClient.config();

		source.sendFeedback(Chat.prefixed(Component.literal(SkyblockFlipperClient.configFile().toString())
				.withStyle(ChatFormatting.GRAY)));
		line(source, "bankroll", Chat.coins(config.bankroll));
		line(source, "bazaar flipper level", config.bazaarFlipperLevel + " ("
				+ new Fees(config.bazaarFlipperLevel, false).bazaarOrderSlots() + " order slots)");
		line(source, "npc daily cap", Chat.coins(CandidateFeed.npcCapRemaining(config)) + " left of "
				+ Chat.coins(config.npcDailyCapCoins));
		line(source, "npc orders", (config.npcMaxOrderSlots > 0
						? config.npcMaxOrderSlots + " of "
						: "all ")
				+ new Fees(config.bazaarFlipperLevel, false).bazaarOrderSlots() + " slots, "
				+ String.format("%.0f%%", config.npcMinMarginRatio * 100.0d) + " min margin, "
				+ String.format("%.2gh", config.npcRestingHours) + " resting, "
				+ config.npcCheckInMinutes + "m reprice rounds");
		line(source, "min profit per flip", Chat.coins(config.minProfitPerFlip));
		line(source, "min confidence", String.format("%.2f", config.minConfidence));
		line(source, "max adverse drift", config.maxAdverseDrift <= 0.0d
				? "off"
				: String.format("%.1f%%", config.maxAdverseDrift * 100.0d));
		line(source, "bazaar tape", config.bazaarTapeEnabled
				? config.bazaarTapeRetentionDays + "d retained, " + config.trendWindowHours + "h trend window"
				: "off");
		line(source, "hud", config.hudEnabled
				? config.hudLines + " lines, " + config.anchor() + " +" + config.hudMarginX + "," + config.hudMarginY
				: "off");
		line(source, "gui zoom", config.guiZoom <= 0.0d
				? "auto"
				: String.format("%.2f", config.guiZoom));
		line(source, "polling enabled", String.valueOf(config.pollingEnabled));

		// The file path above is only actionable if you know editing it is the only way, and with
		// Cloth installed it is not.
		source.sendFeedback(Component.literal(Settings.available()
						? "  /flip config edit opens all of these as a screen"
						: "  edit the file, then /flip reload")
				.withStyle(ChatFormatting.DARK_GRAY));
	}

	/**
	 * The vocabulary, in chat.
	 *
	 * <p>Whole thing at once is a wall of text in a five-line chat box, so a bare {@code /flip
	 * guide} lists the sections and a topic prints one. The screen's Guide tab shows the same text
	 * from the same source with room to read it.
	 *
	 * @param topic section heading, matched on its first word; null lists what is available
	 */
	private static int showGuide(FabricClientCommandSource source, String topic) {
		if (topic == null) {
			source.sendFeedback(Chat.prefixed(Component.literal("Guide").withStyle(ChatFormatting.WHITE)));

			for (Guide.Section section : Guide.sections()) {
				source.sendFeedback(Component.literal("  /flip guide " + section.keyword())
						.withStyle(ChatFormatting.YELLOW)
						.append(Component.literal(" - " + section.heading())
								.withStyle(ChatFormatting.GRAY)));
			}

			source.sendFeedback(Component.literal("  or open the flip screen and pick the Guide tab")
					.withStyle(ChatFormatting.DARK_GRAY));
			return 1;
		}

		for (Guide.Section section : Guide.sections()) {
			if (section.keyword().equalsIgnoreCase(topic)) {
				source.sendFeedback(Chat.prefixed(Component.literal(section.heading())
						.withStyle(ChatFormatting.WHITE)));

				for (Guide.Term term : section.terms()) {
					source.sendFeedback(Component.literal("  " + term.name())
							.withStyle(ChatFormatting.YELLOW)
							.append(Component.literal(" - " + term.meaning())
									.withStyle(ChatFormatting.GRAY)));
				}

				return 1;
			}
		}

		source.sendError(Component.literal("No guide section called " + topic + " - run /flip guide.")
				.withStyle(ChatFormatting.RED));
		return 0;
	}

	private static void line(FabricClientCommandSource source, String key, String value) {
		source.sendFeedback(Component.literal("  " + key + ": ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
	}

	private static String describeAge(Duration age) {
		long seconds = age.toSeconds();

		if (seconds <= 0L) {
			return "just now";
		} else if (seconds < 60L) {
			return seconds + "s";
		}

		return age.toMinutes() + "m";
	}
}
