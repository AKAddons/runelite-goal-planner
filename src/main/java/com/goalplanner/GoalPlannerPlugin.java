package com.goalplanner;

import com.goalplanner.api.GoalPlannerApi;
import com.goalplanner.api.GoalPlannerApiImpl;
import com.goalplanner.data.AchievementDiaryData;
import com.goalplanner.data.CombatAchievementData;
import com.goalplanner.data.CombatAchievementData.Tier;
import com.goalplanner.data.ItemSourceData;
import com.goalplanner.data.SourceAttributes;
import com.goalplanner.data.WikiCaRepository;
import com.google.inject.Binder;
import com.goalplanner.model.AccountMetric;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.model.ItemTag;
import com.goalplanner.model.TagCategory;
import com.goalplanner.persistence.GoalStore;
import com.goalplanner.tracker.ItemTracker;
import com.goalplanner.tracker.SkillTracker;
import com.goalplanner.ui.GoalPanel;
import com.goalplanner.ui.PanelFonts;
import com.goalplanner.util.FormatUtil;
import com.google.inject.Provides;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import com.google.gson.Gson;
import com.goalplanner.share.ShareBundle;
import com.goalplanner.share.ShareCodec;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.events.PluginMessage;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.game.chatbox.ChatboxItemSearch;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

@Slf4j
@PluginDescriptor(
	name = "Goal Planner",
	description = "Plan and track OSRS goals that auto-update from game state - "
		+ "skills, quests, diaries, bosses, items; organize into sections and share as codes",
	tags = {"goals", "tracker", "progress", "skills", "quests", "diary", "bosses", "achievement", "share", "plan"}
)
public class GoalPlannerPlugin extends Plugin
{
	// Enabled under `./gradlew run` (-ea), disabled in production RuneLite.exe.
	private static final boolean DEV_MODE = GoalPlannerPlugin.class.desiredAssertionStatus();

	@Inject
	private Client client;

	@Inject
	private GoalPlannerConfig config;

	@Inject
	private net.runelite.client.eventbus.EventBus eventBus;

	@Inject
	private net.runelite.client.plugins.PluginManager pluginManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private GoalStore goalStore;

	@Inject
	private SkillTracker skillTracker;

	@Inject
	private com.goalplanner.tracker.QuestTracker questTracker;

	@Inject
	private com.goalplanner.tracker.DiaryTracker diaryTracker;

	@Inject
	private com.goalplanner.tracker.CombatAchievementTracker combatAchievementTracker;

	@Inject
	private ItemTracker itemTracker;

	@Inject
	private com.goalplanner.tracker.AccountTracker accountTracker;

	@Inject
	private com.goalplanner.tracker.BossKillTracker bossKillTracker;

	@Inject
	private SkillIconManager skillIconManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private WikiCaRepository wikiCaRepository;

	@Inject
	private ChatboxItemSearch chatboxItemSearch;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GoalPlannerApiImpl goalTrackerApi;

	@Inject
	private com.goalplanner.service.GoalReorderingService reorderingService;

	@Inject
	private Gson gson;

	@Inject
	private ChatMessageManager chatMessageManager;

	/** Share-code codec, built from the injected Gson at start-up. */
	private ShareCodec shareCodec;

	@Inject
	private com.goalplanner.persistence.SavedPlanStore savedPlanStore;

	/** Local player's display name, cached on the client thread (onGameTick)
	 *  so the share UI can read it off the EDT. */
	private volatile String localPlayerName;

	private GoalPanel panel;
	private NavigationButton navButton;

	/**
	 * Bind the public {@link GoalPlannerApi} interface to its implementation so
	 * other plugins can {@code @Inject GoalPlannerApi} after declaring
	 * {@code @PluginDependency(GoalPlannerPlugin.class)}.
	 */
	@Override
	public void configure(Binder binder)
	{
		binder.bind(GoalPlannerApi.class).to(GoalPlannerApiImpl.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		log.info("Goal Planner started");

		// Load the bundled requirement tables using the client's injected Gson
		// (the hub forbids plugins from constructing their own). Idempotent - safe
		// across restarts. Must precede any requirement seeding / blocked-prereq scan.
		com.goalplanner.data.DiaryRequirements.init(gson);
		com.goalplanner.data.BossKillData.init(gson);

		goalStore.load();
		// Pre-login the panel shows the LAST character seen on this profile -
		// the same answer the main/leagues split gives for the same question.
		// setAccount no-ops at 0 (fresh install), which leaves the pre-account
		// namespace visible exactly as before this feature existed.
		goalStore.setAccount(goalStore.getLastAccount());
		goalStore.setAutoArchiveDefault(config.autoArchiveCompleted());
		goalStore.setIndentDependenciesDefault(config.showDependenciesIndented());
		seedCanonicalSystemTags();
		wikiCaRepository.loadAsync();
		// Migrate any pre-existing CA goals (from before the bit-packed varp tracking
		// switch) by looking up their wiki id by name. No-op if the wiki cache hasn't
		// populated yet - they'll get filled on a later startup.
		migrateCaTaskIds();

		// Resolve the configured panel font (family + size scale) before the first
		// build so the panel renders with it from the start.
		PanelFonts.configure(config.fontFamily(), config.fontScale());

		panel = new GoalPanel(goalStore, skillIconManager, itemManager, spriteManager,
			goalTrackerApi, reorderingService, this::openItemSearch, config);
		panel.setClient(client);
		// Context-menu / dialog actions that read live Client state must run on
		// the client thread (skill levels, quest states, the quest DB table all
		// assert it - off the EDT they throw and the action silently no-ops).
		panel.setClientThreadExecutor(clientThread::invokeLater);

		// Share support: codec + the Options-menu share/import actions.
		shareCodec = new ShareCodec(gson);
		savedPlanStore.load();
		panel.setShareSupport(shareCodec, () -> localPlayerName, savedPlanStore);

		// Loadout Lab link-in: boss cards offer "Search in Loadout Lab" when
		// that plugin is enabled, a disabled nudge when it's installed but
		// turned off, and nothing at all when it isn't installed (no upsell).
		panel.setLoadoutLabSupport(this::loadoutLabState, this::searchLoadoutLab);

		// Wire the API's UI-refresh hooks with debouncing.
		// Multiple rapid onGoalsChanged calls (e.g. tracker updates for
		// every skill chain goal) produce exactly one rebuild.
		rebuildDebounce = new Timer(200, e -> panel.rebuild());
		rebuildDebounce.setRepeats(false);

		// Repeatable-goal rollover. A clock timer, NOT onGameTick: stage-1
		// repeatable goals are CUSTOM goals that need no client data, and the
		// day boundary is usually crossed while logged out - when no game tick
		// fires. One minute is deliberate: the check is idempotent so a missed
		// run self-heals, and second-granularity would buy nothing but repaints.
		repeatResetService = new com.goalplanner.service.RepeatResetService(goalStore);
		repeatResetTimer = new Timer(60_000, e -> applyRepeatResets());
		repeatResetTimer.setRepeats(true);
		repeatResetTimer.start();
		// And once now, so a plugin that was closed overnight catches up on
		// startup rather than a minute later.
		applyRepeatResets();
		wireRepeatBoundary();
		goalTrackerApi.setOnGoalsChanged(() ->
		{
			SwingUtilities.invokeLater(rebuildDebounce::restart);
			// Any structural change (add/seed/import) may have introduced skill or
			// account goals that need their first value sync - do it now rather
			// than waiting for the next in-game XP event.
			refreshSkillGoalsNow();
		});
		goalTrackerApi.setOnSelectionChanged(
			() -> SwingUtilities.invokeLater(() -> panel.refreshSelection()));

		BufferedImage icon;
		try
		{
			icon = ImageUtil.loadImageResource(getClass(), "/goal_icon.png");
		}
		catch (Exception e)
		{
			log.warn("Could not load goal_icon.png, using fallback");
			icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		}

		navButton = NavigationButton.builder()
			.tooltip("Goal Planner")
			.icon(icon)
			.priority(5)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Goal Planner stopped");
		goalStore.saveNow();
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
		// Release everything that pins the panel/card graph across a
		// disable -> re-enable cycle. goalTrackerApi is a @Singleton whose
		// callbacks would otherwise keep pointing at this (now-dead) panel.
		if (rebuildDebounce != null)
		{
			rebuildDebounce.stop();
		}
		// A repeating timer, unlike the debounces - it must be stopped or it
		// keeps firing at a dead panel across a disable -> re-enable cycle.
		if (repeatResetTimer != null)
		{
			repeatResetTimer.stop();
			repeatResetTimer = null;
		}
		if (goalTrackerApi != null)
		{
			goalTrackerApi.setOnGoalsChanged(null);
			goalTrackerApi.setOnSelectionChanged(null);
			goalTrackerApi.clearSelectionState();
		}
		trackersDirty = false;
		panel = null;
		navButton = null;
		shareCodec = null;
	}

	/**
	 * Cross-plugin API: another plugin in the same client can hand Goal Planner a
	 * share bundle to import by posting a {@link PluginMessage} to namespace
	 * {@code "goalplanner"}, name {@code "import-share"}, with a {@code "code"}
	 * string in the data. Just the raw RuneLite PluginMessage convention (the
	 * shortest-path / quest-helper interop pattern) - a caller needs no dependency
	 * on Goal Planner. See README → "Cross-plugin API".
	 */
	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (!"goalplanner".equals(event.getNamespace()) || !"import-share".equals(event.getName()))
		{
			return;
		}
		Object code = event.getData().get("code");
		if (!(code instanceof String))
		{
			return;
		}
		final ShareBundle bundle;
		try
		{
			bundle = shareCodec.decode((String) code);
		}
		catch (RuntimeException e)
		{
			log.debug("Postie import-share: unreadable code", e);
			return;
		}
		final int count = bundle.totalGoalCount();
		SwingUtilities.invokeLater(() ->
		{
			String sectionId;
			try
			{
				sectionId = goalTrackerApi.importShareBundle(bundle);
			}
			catch (RuntimeException ex)
			{
				log.warn("Postie import-share: importShareBundle threw", ex);
				postGameMessage(new ChatMessageBuilder()
					.append(ChatColorType.HIGHLIGHT).append("[Goal Planner] ")
					.append(ChatColorType.NORMAL).append("Postie import FAILED: " + ex)
					.build());
				return;
			}
			panel.rebuild();
			log.info("Postie import-share: result sectionId={} (payload had {} goals)", sectionId, count);
			String msg = sectionId != null
				? "imported " + count + " shared goal(s) via Postie to section " + sectionId
				: "Postie import returned NO section (nothing added)";
			postGameMessage(new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT).append("[Goal Planner] ")
				.append(ChatColorType.NORMAL).append(msg)
				.build());
		});
	}

	/**
	 * Cross-plugin link-in (outbound): ask Loadout Lab to search its gear
	 * optimizer for a monster, by display name (its PluginMessage contract:
	 * namespace "loadoutlab", name "search"; the receiver's search is
	 * punctuation-insensitive and level-suffix tolerant, so display names
	 * like "K'ril Tsutsaroth" go as-is). Fire-and-forget - unheard if
	 * Loadout Lab isn't installed or predates the contract.
	 */
	private void searchLoadoutLab(String monsterName)
	{
		if (monsterName == null || monsterName.isEmpty()) return;
		clientThread.invokeLater(() -> eventBus.post(new PluginMessage(
			"loadoutlab", "search", Map.of(
				"monster", monsterName,
				"source", "goal-planner"))));
	}

	/**
	 * Loadout Lab's install/enable state, resolved fresh on every call (so a
	 * mid-session install or toggle is picked up at the next menu open).
	 */
	private GoalPanel.LoadoutLabState loadoutLabState()
	{
		return GoalPanel.LoadoutLabState.resolve(
			isPluginEnabled("Loadout Lab"), isPluginInstalled("Loadout Lab"));
	}

	private boolean isPluginEnabled(String name)
	{
		// Same-named plugins can coexist (a hub copy alongside a sideloaded dev
		// copy); RuneLite runs at most one, so report enabled if ANY copy is -
		// returning on the first match could hit the disabled loser.
		for (net.runelite.client.plugins.Plugin p : pluginManager.getPlugins())
		{
			if (name.equals(p.getName()) && pluginManager.isPluginEnabled(p))
			{
				return true;
			}
		}
		return false;
	}

	/** True when ANY plugin with this name is loaded, enabled or not. */
	private boolean isPluginInstalled(String name)
	{
		for (net.runelite.client.plugins.Plugin p : pluginManager.getPlugins())
		{
			if (name.equals(p.getName()))
			{
				return true;
			}
		}
		return false;
	}

	/** Queue a Goal Planner game-chat message, marshalled to the client thread. */
	private void postGameMessage(String runeLiteFormattedMessage)
	{
		clientThread.invokeLater(() -> chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(runeLiteFormattedMessage)
			.build()));
	}

	/**
	 * Opens the in-game chatbox item search.
	 * After selection, shows a confirmation dialog with item name + quantity.
	 */
	public void openItemSearch(int targetQty, String preferredSectionId, int positionInSection)
	{
		chatboxItemSearch
			.tooltipText("Select item for goal")
			.onItemSelected(itemId ->
			{
				// All ItemManager calls (canonicalize, getItemComposition,
				// buildItemTags' fallback) require the client thread. Compute
				// everything we need from item data here, then hand only the
				// pure data + the dialog show to the Swing thread. Doing
				// buildItemTags inside SwingUtilities.invokeLater previously
				// triggered an AssertionError ("must be called on client thread")
				// for any item not in ItemSourceData (lyres, runes, etc.) - the
				// EDT swallowed the exception and the goal was silently dropped.
				clientThread.invokeLater(() ->
				{
					int canonicalId = itemManager.canonicalize(itemId);
					String rawItemName = itemManager.getItemComposition(canonicalId).getName();
					final String itemName = rawItemName != null && rawItemName.endsWith("(Members)")
						? rawItemName.substring(0, rawItemName.length() - "(Members)".length()).trim()
						: rawItemName;
					List<ItemTag> autoTags = buildItemTags(canonicalId);
					List<String> autoTagIds = new ArrayList<>();
					for (ItemTag spec : autoTags)
					{
						com.goalplanner.model.Tag tag =
							goalStore.findOrCreateSystemTag(spec.getLabel(), spec.getCategory());
						if (tag != null) autoTagIds.add(tag.getId());
					}

					SwingUtilities.invokeLater(() ->
					{
						// Set sectionId at build time so addGoal places the
						// goal in the right section instead of defaulting to
						// Incomplete. Position-within-section is applied as a
						// follow-up call after the goal exists.
						Goal goal = Goal.builder()
							.type(GoalType.ITEM_GRIND)
							.name(itemName)
							.description(FormatUtil.formatNumber(targetQty) + " total")
							.itemId(canonicalId)
							.targetValue(targetQty)
							.currentValue(-1)
							.sectionId(preferredSectionId)  // null → addGoal falls back to Incomplete
							.tagIds(new ArrayList<>(autoTagIds))
							.defaultTagIds(new ArrayList<>(autoTagIds))
							.build();

						// Route through the command path so this is undoable.
						// Wrap create + position in a single compound entry so
						// one undo reverses the whole gesture.
						final String capturedGoalId = goal.getId();
						final String displayName = itemName;
						goalTrackerApi.beginCompound("Add goal: " + displayName);
						try
						{
							goalTrackerApi.executeCommand(new com.goalplanner.command.Command()
							{
								@Override public boolean apply()
								{
									boolean exists = goalStore.getGoals().stream()
										.anyMatch(x -> capturedGoalId.equals(x.getId()));
									if (exists) return false;
									goalStore.addGoal(goal);
									return true;
								}
								@Override public boolean revert()
								{
									goalStore.removeGoal(capturedGoalId);
									return true;
								}
								@Override public String getDescription() { return "Add goal: " + displayName; }
							});
							if (preferredSectionId != null && positionInSection >= 0)
							{
								goalTrackerApi.positionGoalInSection(
									capturedGoalId, preferredSectionId, positionInSection);
							}
						}
						finally
						{
							goalTrackerApi.endCompound();
						}
						refreshItemGoalsNow();
					});
				});
			})
			.build();
	}

	/**
	 * Build tags for an item, including source tags and inherited attributes (e.g., Slayer Task).
	 */
	/**
	 * Route an already-built Goal through the undo/redo command
	 * system so user-triggered "Add Goal" actions from menu entries (CA,
	 * diary, inventory/bank/collection log) land on the undo stack.
	 */
	private void addGoalUndoable(Goal goal, String description)
	{
		final String goalId = goal.getId();
		final Goal captured = goal;
		goalTrackerApi.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				boolean exists = goalStore.getGoals().stream()
					.anyMatch(x -> goalId.equals(x.getId()));
				if (exists) return false;
				goalStore.addGoal(captured);
				return true;
			}
			@Override public boolean revert()
			{
				goalStore.removeGoal(goalId);
				return true;
			}
			@Override public String getDescription() { return description; }
		});
	}

	private List<ItemTag> buildItemTags(int itemId)
	{
		// Callers MUST pass an already-canonicalized item id and MUST invoke
		// from the client thread. The earlier in-method canonicalize fallback
		// was removed because (a) every call site now canonicalizes upstream,
		// and (b) calling itemManager.canonicalize from the Swing EDT throws
		// AssertionError ("must be called on client thread"), which the EDT
		// silently swallowed and dropped the goal-add silently.
		List<ItemTag> tags = new ArrayList<>(ItemSourceData.getTags(itemId));

		// Check for inherited attributes. Slayer inheritance now comes from
		// two independent signals unioned together:
		//   1. Boss-source inheritance: any of this item's boss sources is a
		//      registered slayer-task monster (Abyssal Sire, Alchemical
		//      Hydra, Kraken, etc.). This is how Sire drops like the
		//      Abyssal whip / dagger pick up Slayer.
		//   2. Direct item inheritance: the item id is in the
		//      DIRECT_SLAYER_ITEMS set (SourceAttributes.isSlayerItem), which
		//      covers slayer-monster drops whose source isn't currently
		//      registered as a boss (Gargoyles, Wyverns, Cockatrice, etc.)
		//      and sourceless drops (Imbued heart, Eternal gem).
		// The old dedupe bandaid ("strip non-SKILLING Slayer before appending
		// SKILLING Slayer") is gone - the OTHER "Slayer" category no longer
		// exists, so there's nothing to collide with.
		boolean needsSlayerTag = SourceAttributes.isSlayerItem(itemId);
		boolean isPet = false;
		for (ItemTag tag : tags)
		{
			if (!needsSlayerTag && SourceAttributes.isSlayerTask(tag.getLabel()))
			{
				needsSlayerTag = true;
			}
			if ("All Pets".equals(tag.getLabel()))
			{
				isPet = true;
			}
		}
		if (needsSlayerTag)
		{
			tags.add(new ItemTag("Slayer", TagCategory.SKILLING));
		}
		if (isPet)
		{
			tags.removeIf(t -> "All Pets".equals(t.getLabel()));
			// SPECIAL category removed; the Pet tag now lives in OTHER
			// with a per-tag pink color override seeded by seedCanonicalSystemTags.
			tags.add(0, new ItemTag("Pet", TagCategory.OTHER));
		}

		return tags;
	}

	/**
	 * Varbit/varp changes drive quest, diary, CA, and account tracking.
	 * Deferred to the next client tick via invokeLater because
	 * Quest.getState() runs a script internally, and VarbitChanged fires
	 * mid-script - calling getState() synchronously would cause a
	 * "scripts are not reentrant" assertion error.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"goalplanner".equals(event.getGroup()))
		{
			return;
		}
		// Re-resolve the panel font and rebuild so a family/size change applies live.
		if ("fontFamily".equals(event.getKey()) || "fontScale".equals(event.getKey()))
		{
			PanelFonts.configure(config.fontFamily(), config.fontScale());
			if (panel != null)
			{
				SwingUtilities.invokeLater(panel::rebuild);
			}
		}
		// Global "indent dependencies" toggled - sync the flag (nested sections
		// keep completed inline), reconcile so completed goals re-sort between
		// inline/Completed, and rebuild so every section re-renders to match.
		if ("showDependenciesIndented".equals(event.getKey()))
		{
			goalStore.setIndentDependenciesDefault(config.showDependenciesIndented());
			goalStore.reconcileDerivedSections();
			goalStore.save();
			if (panel != null)
			{
				SwingUtilities.invokeLater(panel::rebuild);
			}
		}
		// Global auto-archive default changed - sync to the store, re-sort the
		// default-following sections, and rebuild.
		if ("autoArchiveCompleted".equals(event.getKey()))
		{
			goalStore.setAutoArchiveDefault(config.autoArchiveCompleted());
			goalStore.reconcileDerivedSections();
			goalStore.save();
			if (panel != null)
			{
				SwingUtilities.invokeLater(panel::rebuild);
			}
		}
	}

	/**
	 * The user switched RuneLite config profiles. Goals are stored per config
	 * profile (RuneLite scopes our {@code getConfiguration} reads to the active
	 * profile's {@code .properties}); RuneLite has already swapped that file, so
	 * re-read the goal set for the current namespace. Without this the previous
	 * profile's in-memory goals linger in the sidebar and leak into the new
	 * profile on the next save. The active main/leagues namespace is unchanged -
	 * only the backing store moved - so this is a reload(), not a setProfile().
	 */
	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		if (goalStore == null) return;
		goalStore.reload();
		// Saved Plans are likewise per config profile (their key isn't prefixed
		// with main/leagues, but RuneLite scopes config per profile) - re-read so
		// the library reflects this profile and doesn't leak into it on next save.
		if (savedPlanStore != null) savedPlanStore.reload();
		seedCanonicalSystemTags();
		// Same settle window as a main↔leagues switch: let client state resync
		// for the (possibly different) account before trackers apply values.
		trackingSuspendedUntil = System.currentTimeMillis() + PROFILE_SWITCH_SUSPEND_MS;
		lastMiscApproval = -1;
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::rebuild);
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// Many varbits change every tick (and dozens at login / on region change).
		// Running the full tracker suite per event was the dominant client-thread
		// cost; instead just mark dirty and drain ONCE per tick in onGameTick,
		// which is already a safe client-thread point (no Quest.getState() reentry
		// mid-varbit). Suspension/GameState are re-checked at drain time.
		if (client.getGameState() != GameState.LOGGED_IN) return;
		trackersDirty = true;
	}

	/** Debounce timer coalescing rapid rebuild requests. Hoisted to a field so
	 *  shutDown() can stop it (otherwise its ActionListener pins the panel). */
	private Timer rebuildDebounce;

	/** Repeating one-minute rollover check for repeatable goals. */
	private Timer repeatResetTimer;
	private com.goalplanner.service.RepeatResetService repeatResetService;

	/**
	 * Roll over any repeatable goal whose period has ended, and refresh the
	 * panel if anything actually changed. Idempotent, so the minute timer, the
	 * startup call, and a profile switch can all invoke it freely.
	 */
	/**
	 * Hand the API the boundary config so goal views can carry "due by ..." for
	 * repeating goals. The plugin owns the config; the API layer must not read
	 * it directly.
	 */
	private void wireRepeatBoundary()
	{
		goalTrackerApi.setNextBoundaryFn(
			period -> com.goalplanner.util.RepeatSchedule.nextBoundary(
				period, Instant.now(),
				com.goalplanner.util.RepeatSchedule.zoneFor(config.resetBoundary()),
				com.goalplanner.util.RepeatSchedule.hourFor(
					config.resetBoundary(), config.resetHour())),
			() -> com.goalplanner.util.RepeatSchedule.zoneFor(config.resetBoundary()));
	}

	private void applyRepeatResets()
	{
		if (repeatResetService == null)
		{
			return;
		}
		int reset = repeatResetService.applyResets(
			Instant.now(), config.resetBoundary(), config.resetHour());
		if (panel == null)
		{
			return;
		}
		// A rollover always needs a repaint. Otherwise repaint only to advance the
		// "resets in" countdowns, and only when a goal is actually showing one, so
		// a player with no repeatable goals pays nothing. Once a minute is ~600x
		// rarer than the per-event rebuilds the 0.4.0 perf pass removed. It is
		// still a full rebuild: if this ever needs to tick faster than a minute it
		// must mutate the label directly instead.
		if (reset > 0 || hasRepeatingGoal())
		{
			SwingUtilities.invokeLater(panel::rebuild);
		}
	}

	private boolean hasRepeatingGoal()
	{
		if (goalStore == null)
		{
			return false;
		}
		for (Goal g : goalStore.getGoals())
		{
			if (g.isRepeating())
			{
				return true;
			}
		}
		return false;
	}

	/** Set by onVarbitChanged/onStatChanged (which fire many times per tick) and
	 *  drained once per tick in onGameTick, so the tracker suite runs at most once
	 *  per tick instead of per event. Client-thread only. */
	private boolean trackersDirty = false;

	/** Last Kingdom of Miscellania approval value seen this session (-1 = not yet
	 *  read, so the first reading only establishes a baseline). Reset on login. */
	private int lastMiscApproval = -1;

	/** Approval increases observed since this login. The 1st increase is the
	 *  login data-sync jump (0 -> real value), so only the 2nd+ counts as a real
	 *  favour gain (see {@link com.goalplanner.tracker.MiscellaniaAutoGoal}).
	 *  Reset on login. */
	private int miscApprovalIncreases = 0;

	/**
	 * Auto-add a Miscellania 100% approval goal when the player raises their
	 * approval (i.e. is actively managing the kingdom) and no such goal exists.
	 * Client-thread only; called from {@link #onVarbitChanged}. Gated by the
	 * config toggle and skipped while a Misc. Approval goal is already present -
	 * so it re-adds whenever the goal is missing and approval rises again. The
	 * added goal is a normal undoable account goal.
	 */
	private void maybeAutoAddMiscApprovalGoal()
	{
		int current = client.getVarbitValue(net.runelite.api.gameval.VarbitID.MISC_APPROVAL);
		int previous = lastMiscApproval;
		lastMiscApproval = current;

		// First reading after login just establishes the baseline - never fires.
		if (previous < 0)
		{
			return;
		}
		// Only an increase is interesting.
		if (current <= previous)
		{
			return;
		}

		// Ignore the 1st increase (the login data-sync jump); act on the 2nd+.
		miscApprovalIncreases++;
		if (!com.goalplanner.tracker.MiscellaniaAutoGoal.shouldAdd(previous, current,
			miscApprovalIncreases, config.autoTrackMiscellania(), hasMiscApprovalGoal()))
		{
			return;
		}

		// The add runs through the command system (EDT-owned selection + undo
		// history), so marshal it off this client-thread drain onto the EDT.
		SwingUtilities.invokeLater(() ->
		{
			String id = goalTrackerApi.addAccountGoal(AccountMetric.MISC_APPROVAL.name(),
				com.goalplanner.tracker.MiscellaniaAutoGoal.FULL_APPROVAL);
			if (id == null)
			{
				return;
			}
			log.debug("Auto-added Miscellania 100% approval goal {}", id);
			postGameMessage(new ChatMessageBuilder()
				.append("Goal Planner: added a Miscellania 100% approval goal. "
					+ "You can turn this off in the plugin settings (Auto-track Miscellania).")
				.build());
		});
	}

	/** True if a Misc. Approval account goal already exists on this profile -
	 *  active OR completed (getGoals/exists scan the full store), so we never
	 *  auto-add a duplicate of a goal the player is already tracking or has
	 *  finished. */
	private boolean hasMiscApprovalGoal()
	{
		return goalStore.exists(g -> AccountMetric.MISC_APPROVAL.name().equals(g.getAccountMetric()));
	}

	/**
	 * Last profile we applied to the store. Used to detect transitions so
	 * we only call {@link com.goalplanner.persistence.GoalStore#setProfile(String)}
	 * when the current world actually changes scope.
	 */
	private String lastAppliedProfile = com.goalplanner.persistence.GoalStore.PROFILE_MAIN;

	/**
	 * Whether we've performed the deferred legacy migration yet. We can't
	 * migrate at startUp because we don't know the profile until the user
	 * logs in - migrating early could strand leagues users' legacy goals
	 * in the main profile. Fires exactly once on first profile detection.
	 */
	private boolean legacyMigrationDone = false;

	/**
	 * Epoch-millis deadline after which trackers may resume. Set whenever
	 * the active profile changes. The window lets client state (skill XP
	 * cache, quest varbits) fully sync for the new account before any
	 * tracker reads would apply stale values from the previous account
	 * to the newly-loaded profile's goals.
	 */
	private long trackingSuspendedUntil = 0;
	private static final long PROFILE_SWITCH_SUSPEND_MS = 5_000;

	private boolean isTrackingSuspended()
	{
		return System.currentTimeMillis() < trackingSuspendedUntil || !accountMatchesGoalSet();
	}

	private final com.goalplanner.util.AccountBindingGate accountGate =
		new com.goalplanner.util.AccountBindingGate();

	/**
	 * Whether the loaded goal set belongs to the logged-in account. The decision
	 * logic lives in {@link com.goalplanner.util.AccountBindingGate} so it can be
	 * tested without a client - this method is only the client/store plumbing
	 * around it.
	 *
	 * <p>{@link #PROFILE_SWITCH_SUSPEND_MS} does not cover this case: it waits
	 * for client state to settle after a switch, which delays a wrong pairing by
	 * five seconds rather than preventing it.
	 */
	private boolean accountMatchesGoalSet()
	{
		if (client == null || goalStore == null) return false;

		long live = client.getAccountHash();
		// Per-character namespace: follow the logged-in character BEFORE the
		// binding gate evaluates, so the gate compares against the right plan.
		// setAccount no-ops when unchanged; on a real switch it migrates,
		// reloads, and this drain sits out the settle window like a profile hop.
		if (live > 0 && live != goalStore.getActiveAccount())
		{
			goalStore.setAccount(live);
			trackingSuspendedUntil = System.currentTimeMillis() + PROFILE_SWITCH_SUSPEND_MS;
			lastMiscApproval = -1;
			if (panel != null)
			{
				SwingUtilities.invokeLater(panel::rebuild);
			}
			return false; // this drain waits; next one sees the settled state
		}
		long bound = goalStore.getBoundAccountHash();
		com.goalplanner.util.AccountBindingGate.Decision decision =
			accountGate.evaluate(live, bound);

		switch (decision)
		{
			case ADOPT:
				goalStore.setBoundAccountHash(live);
				log.info("Goal set for profile {} bound to the logged-in account",
					goalStore.getActiveProfile());
				return true;
			case PAUSE:
				// Loud on purpose. Silently freezing tracking just becomes a
				// different bug report ("my goals stopped updating"), and the
				// user is the only one who can tell which pairing is wrong.
				if (accountGate.shouldAnnounceMismatch())
				{
					log.warn("Goal tracking paused: profile {} is bound to a different account",
						goalStore.getActiveProfile());
					postGameMessage("<col=ff3333>Goal Planner:</col> this profile's goals belong "
						+ "to a different account, so tracking is paused - otherwise they would "
						+ "complete themselves against the wrong account. Switch to this "
						+ "account's RuneLite profile to resume.");
				}
				return false;
			case TRACK:
				return true;
			case NO_ACCOUNT:
			default:
				return false;
		}
	}

	/**
	 * Gates goal-prereq seeding until the client's skill cache has synced
	 * for the current login. Seeding reads {@code getRealSkillLevel} to skip
	 * already-met skill requirements; reading before the post-login stat
	 * block arrives returns the default level (1) and over-seeds already-met
	 * skills as spurious level-1 cards. Fed by onLogin/onLogout (game-state),
	 * onStatChanged, and onTick below. One quiet game tick after the stat
	 * burst means skills are in; the wall-clock fallback covers accounts that
	 * fire no StatChanged so an add is never stranded.
	 */
	private static final int SKILL_SYNC_SETTLE_TICKS = 1;
	private static final long SKILL_SYNC_FALLBACK_MS = 5_000;
	private final com.goalplanner.util.SkillSyncGate skillSyncGate =
		new com.goalplanner.util.SkillSyncGate(
			() -> client != null && client.getGameState() == GameState.LOGGED_IN,
			System::currentTimeMillis,
			SKILL_SYNC_SETTLE_TICKS,
			SKILL_SYNC_FALLBACK_MS);

	/**
	 * Derive the current profile from client state. Leagues iff the world is
	 * SEASONAL or the LEAGUE_ACCOUNT varbit is set; otherwise main.
	 */
	private String detectProfile()
	{
		Set<net.runelite.api.WorldType> wt = client.getWorldType();
		// Deadman first: a DMM world could conceivably also read as seasonal,
		// and its progress must never bleed into the leagues plan.
		if (wt != null && wt.contains(net.runelite.api.WorldType.DEADMAN))
		{
			return com.goalplanner.persistence.GoalStore.PROFILE_DEADMAN;
		}
		boolean seasonal = wt != null && wt.contains(net.runelite.api.WorldType.SEASONAL);
		int leagueAccount = client.getVarbitValue(net.runelite.api.gameval.VarbitID.LEAGUE_ACCOUNT);
		return (seasonal || leagueAccount != 0)
			? com.goalplanner.persistence.GoalStore.PROFILE_LEAGUES
			: com.goalplanner.persistence.GoalStore.PROFILE_MAIN;
	}

	/**
	 * Apply the current profile if it differs from the last applied one.
	 * Triggers a GoalStore reload from the new namespace and rebuilds the
	 * panel so the sidebar reflects the correct goal set for this world.
	 */
	private void checkProfile()
	{
		String now = detectProfile();

		// Run legacy migration exactly once, after we've confirmed which
		// profile the user is actually on. This handles the edge case where
		// a pre-profile user upgrades while logged into a leagues account -
		// their legacy data lands in the leagues profile, not stranded in main.
		if (!legacyMigrationDone)
		{
			legacyMigrationDone = true;
			// Put the store on the detected profile first, then migrate. The
			// store's setProfile() is a no-op if we're already on that profile
			// (the default is main, so we only call setProfile for leagues).
			boolean profileSwitched = false;
			if (!now.equals(goalStore.getActiveProfile()))
			{
				goalStore.setProfile(now);
				seedCanonicalSystemTags();
				trackingSuspendedUntil = System.currentTimeMillis() + PROFILE_SWITCH_SUSPEND_MS;
				lastAppliedProfile = now;
				lastMiscApproval = -1;
				profileSwitched = true;
			}
			boolean migrated = goalStore.migrateLegacyIntoActiveProfile();
			// Rebuild on EITHER: a fresh legacy migration that just changed
			// the goal set, OR a first-time profile switch (e.g. user logs
			// directly into leagues at startup - startUp() loaded the main
			// namespace into the panel; the setProfile() above swapped the
			// store to leagues but the panel still shows main's empty
			// state). Without this, the panel only refreshes when some
			// later event (tracker tick, config change) incidentally
			// triggers a rebuild - the "appears after a little bit" bug.
			if ((profileSwitched || migrated) && panel != null)
			{
				SwingUtilities.invokeLater(panel::rebuild);
			}
			return;
		}

		if (now.equals(lastAppliedProfile)) return;
		log.info("Profile change: {} → {}", lastAppliedProfile, now);
		goalStore.setProfile(now);
		seedCanonicalSystemTags();
		trackingSuspendedUntil = System.currentTimeMillis() + PROFILE_SWITCH_SUSPEND_MS;
		lastAppliedProfile = now;
		lastMiscApproval = -1;
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::rebuild);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Login complete → world type is populated; re-derive profile.
		// Intentionally NOT subscribing to WorldChanged: that event fires
		// during HOPPING (before the client disconnects from the old world)
		// and calling checkProfile at that point would switch the in-memory
		// goal set to the new profile while client state (quest varbits,
		// skill XP cache) is still the old account's - any queued tracker
		// task firing in that window falsely completes new-profile goals
		// using old-profile state. LOGGED_IN is fired only after the new
		// world's login fully completes, at which point client.getWorldType()
		// and the player's character/state reflect the new world.
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// Re-arm the skill-sync gate: the stat block for this login has
			// not arrived yet, so any prereq seeding must wait for it.
			skillSyncGate.onLogin();
			// Reset Miscellania favour-gain detection on EVERY login (checkProfile
			// only fires on profile *changes*). The login approval-varbit sync
			// shows up as the first increase and is ignored - see
			// maybeAutoAddMiscApprovalGoal.
			lastMiscApproval = -1;
			miscApprovalIncreases = 0;
			// Same sync jump, other consumer: hold off re-opening a completed
			// decaying account goal (favour, slayer points) until the varp
			// block has landed, so a pre-sync 0 can't un-complete it.
			accountTracker.onLogin();
			checkProfile();
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING
			|| event.getGameState() == GameState.CONNECTION_LOST)
		{
			// Left the logged-in state: drop any seeding queued for the
			// previous session so it can't seed into a different profile.
			skillSyncGate.onLogout();
			// Re-arm the item tracker's pre-bank guard. The bank container is
			// cleared on leaving a world, so a relog must reopen the bank before
			// a full count is trustworthy again - otherwise a bank-less count
			// overwrites persisted item-goal values with 0 right after login.
			itemTracker.onLogout();
		}
	}


	/**
	 * Map from Skills-tab widget id (InterfaceID.Stats.&lt;SKILL&gt;) to the
	 * corresponding {@link net.runelite.api.Skill}. Used by the right-click handler
	 * to inject "Add Goal" on a hovered skill row.
	 */
	private static final Map<Integer, net.runelite.api.Skill> SKILL_TAB_WIDGET_TO_SKILL;
	static
	{
		Map<Integer, net.runelite.api.Skill> m = new HashMap<>();
		m.put(net.runelite.api.gameval.InterfaceID.Stats.ATTACK, net.runelite.api.Skill.ATTACK);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.STRENGTH, net.runelite.api.Skill.STRENGTH);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.DEFENCE, net.runelite.api.Skill.DEFENCE);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.RANGED, net.runelite.api.Skill.RANGED);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.PRAYER, net.runelite.api.Skill.PRAYER);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.MAGIC, net.runelite.api.Skill.MAGIC);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.RUNECRAFT, net.runelite.api.Skill.RUNECRAFT);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.CONSTRUCTION, net.runelite.api.Skill.CONSTRUCTION);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.HITPOINTS, net.runelite.api.Skill.HITPOINTS);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.AGILITY, net.runelite.api.Skill.AGILITY);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.HERBLORE, net.runelite.api.Skill.HERBLORE);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.THIEVING, net.runelite.api.Skill.THIEVING);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.CRAFTING, net.runelite.api.Skill.CRAFTING);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.FLETCHING, net.runelite.api.Skill.FLETCHING);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.SLAYER, net.runelite.api.Skill.SLAYER);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.HUNTER, net.runelite.api.Skill.HUNTER);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.MINING, net.runelite.api.Skill.MINING);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.SMITHING, net.runelite.api.Skill.SMITHING);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.FISHING, net.runelite.api.Skill.FISHING);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.COOKING, net.runelite.api.Skill.COOKING);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.FIREMAKING, net.runelite.api.Skill.FIREMAKING);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.WOODCUTTING, net.runelite.api.Skill.WOODCUTTING);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.FARMING, net.runelite.api.Skill.FARMING);
		m.put(net.runelite.api.gameval.InterfaceID.Stats.SAILING, net.runelite.api.Skill.SAILING);
		SKILL_TAB_WIDGET_TO_SKILL = Collections.unmodifiableMap(m);
	}

	/**
	 * Prompt the user for a Level/XP target and add a skill goal via the API.
	 * Called from the right-click handler on the Skills tab.
	 */


	/** Quest list widget group ID (InterfaceID.QUESTLIST). */
	private static final int QUESTLIST_GROUP_ID = 399;

	/** Widget ID of the clickable list inside the quest list (InterfaceID.Questlist.LIST). */
	private static final int QUESTLIST_LIST_WIDGET = 26148871;


	/**
	 * Strip &lt;col=...&gt; tags from a menu target string and trim whitespace.
	 */
	private static String stripColorTags(String raw)
	{
		if (raw == null) return null;
		return raw.replaceAll("<col=[^>]*>", "")
			.replaceAll("</col>", "")
			.trim();
	}

	/**
	 * Reverse-lookup a {@link net.runelite.api.Quest} by display name. Case-insensitive.
	 * Returns null if no quest matches.
	 */
	private static net.runelite.api.Quest findQuestByDisplayName(String displayName)
	{
		if (displayName == null || displayName.isEmpty()) return null;
		for (net.runelite.api.Quest q : net.runelite.api.Quest.values())
		{
			if (displayName.equalsIgnoreCase(q.getName()))
			{
				return q;
			}
		}
		return null;
	}


	/**
	 * Read a row's child widget from a per-row dynamic-children container.
	 * Returns null if the widget or the row index is out of range.
	 */
	private net.runelite.api.widgets.Widget getCaRowChild(int widgetId, int row)
	{
		net.runelite.api.widgets.Widget w = client.getWidget(widgetId);
		if (w == null) return null;
		net.runelite.api.widgets.Widget[] children = w.getDynamicChildren();
		if (children == null || row < 0 || row >= children.length) return null;
		return children[row];
	}

	/**
	 * Build a combat achievement Goal from the clicked row index in the CA tasks interface.
	 * Returns null if the row can't be resolved.
	 */
	private Goal buildCombatAchievementGoal(int row)
	{
		net.runelite.api.widgets.Widget nameW = getCaRowChild(CombatAchievementData.TASKS_NAME, row);
		net.runelite.api.widgets.Widget tierW = getCaRowChild(CombatAchievementData.TASKS_TIER, row);
		net.runelite.api.widgets.Widget monsterW = getCaRowChild(CombatAchievementData.TASKS_MONSTER, row);

		if (nameW == null || tierW == null)
		{
			log.warn("CA row {} missing name/tier widget", row);
			return null;
		}

		String name = nameW.getText();
		if (name == null || name.isEmpty())
		{
			log.warn("CA row {} has empty name", row);
			return null;
		}

		int tierSpriteId = tierW.getSpriteId();
		Tier tier = CombatAchievementData.tierFromSpriteId(tierSpriteId);
		String monster = monsterW != null ? CombatAchievementData.parseMonsterName(monsterW.getText()) : null;

		// Prefer the wiki repository for description (works for any row, expanded or not).
		// Fall back to the widget if the wiki cache hasn't populated yet - TASKS_DESCRIPTION
		// is single-instance (only for the currently expanded row), so that fallback is
		// best-effort.
		String fullDesc = null;
		WikiCaRepository.CaInfo wiki = wikiCaRepository.get(name);
		if (wiki != null && wiki.task != null && !wiki.task.isEmpty())
		{
			fullDesc = wiki.task;
		}
		else
		{
			net.runelite.api.widgets.Widget descW = client.getWidget(CombatAchievementData.TASKS_DESCRIPTION);
			fullDesc = descW != null ? CombatAchievementData.parseDescription(descW.getText()) : null;
		}
		String tooltip = fullDesc != null ? name + " - " + fullDesc : null;

		// Wiki provides the canonical CA task id; the tracker uses it to read the
		// bit-packed CA_TASK_COMPLETED varplayers. -1 = unknown.
		int caTaskId = (wiki != null) ? wiki.id : -1;

		List<String> tagIds = new ArrayList<>();
		if (monster != null)
		{
			boolean isRaid = CombatAchievementData.isRaidBoss(monster);
			String tagLabel = isRaid ? CombatAchievementData.abbreviateRaid(monster) : monster;
			com.goalplanner.model.Tag bossTag = goalStore.findOrCreateSystemTag(
				tagLabel, isRaid ? TagCategory.RAID : TagCategory.BOSS);
			if (bossTag != null) tagIds.add(bossTag.getId());
			if (SourceAttributes.isSlayerTask(monster))
			{
				com.goalplanner.model.Tag slayerTag = goalStore.findOrCreateSystemTag(
					"Slayer", TagCategory.SKILLING);
				if (slayerTag != null) tagIds.add(slayerTag.getId());
			}
		}

		String description = tier != null
			? tier.getDisplayName() + " Combat Achievement"
			: "Combat Achievement";

		return Goal.builder()
			.type(GoalType.COMBAT_ACHIEVEMENT)
			.name(name)
			.description(description)
			.tooltip(tooltip)
			.targetValue(1)
			.currentValue(0)
			.spriteId(tierSpriteId > 0 ? tierSpriteId : 0)
			.caTaskId(caTaskId)
			.tagIds(new ArrayList<>(tagIds))
			.defaultTagIds(new ArrayList<>(tagIds))
			.build();
	}

	/**
	 * Backfill caTaskId for existing CA goals by looking up their name in the wiki repo.
	 * Run on startup once the wiki cache is loaded; no-op if the cache hasn't populated yet.
	 */
	/**
	 * Pre-create the canonical system tags so the Tag Management dialog has
	 * something to display before any goals are added. Idempotent -
	 * findOrCreateSystemTag is a no-op if the tag already exists.
	 *
	 * <p>Seeded set:
	 * <ul>
	 *   <li>One SKILLING tag per Skill enum value (Attack, Strength, ..., Sailing)
	 *       - these are the read-only "skill tags" used for icon-based filtering</li>
	 *   <li>"Pet" in the OTHER category with a pink color override -
	 *       canonical pet collection tag</li>
	 * </ul>
	 *
	 * <p>Other system tags (per-monster Boss/Raid, per-item source tags) are still
	 * created lazily by {@code findOrCreateSystemTag} as goals are added - the
	 * universe is too large to pre-seed.
	 */
	private void seedCanonicalSystemTags()
	{
		// One-time category reclassifications. Run BEFORE the seed so the
		// source BOSS entity gets flipped in place (no merge needed) before
		// the seed would otherwise create a fresh MINIGAME entity alongside
		// the orphaned BOSS one. Idempotent: after the first run, each call
		// finds no source tag in the old category and no-ops.
		applyCategoryReclassifications();

		for (net.runelite.api.Skill skill : net.runelite.api.Skill.values())
		{
			com.goalplanner.model.Tag tag = goalStore.findOrCreateSystemTag(
				skill.getName(), com.goalplanner.model.TagCategory.SKILLING);
			// Skill tags render via the uniform iconKey path.
			// setTagIcon is idempotent on the same value.
			if (tag != null)
			{
				goalStore.setTagIcon(tag.getId(), skill.name());
			}
		}
		// Seed canonical BOSS / RAID / CLUE / MINIGAME tags from TagOptions so the
		// dropdowns are populated up-front instead of trickling in as goals are
		// added. findOrCreateSystemTag is idempotent.
		com.goalplanner.model.TagCategory[] seedCats = {
			com.goalplanner.model.TagCategory.BOSS,
			com.goalplanner.model.TagCategory.RAID,
			com.goalplanner.model.TagCategory.CLUE,
			com.goalplanner.model.TagCategory.MINIGAME,
		};
		for (com.goalplanner.model.TagCategory cat : seedCats)
		{
			for (String label : com.goalplanner.data.TagOptions.getOptions(cat))
			{
				goalStore.findOrCreateSystemTag(label, cat);
			}
		}
		// Pet: OTHER category with a per-tag pink color override. OTHER is the
		// only category that supports per-tag colors - every
		// other category uses a category-wide color, but OTHER tags each
		// carry their own. recolorTag is idempotent on the same value.
		com.goalplanner.model.Tag pet = goalStore.findOrCreateSystemTag(
			"Pet", com.goalplanner.model.TagCategory.OTHER);
		if (pet != null && pet.getColorRgb() < 0)
		{
			goalStore.recolorTag(pet.getId(), 0xFF69B4);
		}
		log.debug("Seeded canonical system tags");
	}

	/**
	 * Apply any one-shot category moves for system tags. Runs on every plugin
	 * start but is idempotent - {@link GoalStore#recategorizeSystemTag} finds
	 * no source tag after the first successful migration and returns false.
	 *
	 * <p>Current migrations:
	 * <ul>
	 *   <li>Tempoross: BOSS → MINIGAME (skilling boss, community treats as minigame)</li>
	 *   <li>Wintertodt: BOSS → MINIGAME (same)</li>
	 * </ul>
	 */
	private void applyCategoryReclassifications()
	{
		String[][] moves = {
			{ "Tempoross",  "BOSS", "MINIGAME" },
			{ "Wintertodt", "BOSS", "MINIGAME" },
		};
		for (String[] move : moves)
		{
			com.goalplanner.model.TagCategory from =
				com.goalplanner.model.TagCategory.valueOf(move[1]);
			com.goalplanner.model.TagCategory to =
				com.goalplanner.model.TagCategory.valueOf(move[2]);
			if (goalStore.recategorizeSystemTag(move[0], from, to))
			{
				log.info("Recategorized system tag '{}': {} → {}", move[0], from, to);
			}
		}
	}

	private void migrateCaTaskIds()
	{
		if (wikiCaRepository.size() == 0) return;
		int filled = 0;
		for (Goal g : goalStore.getGoals())
		{
			if (g.getType() != GoalType.COMBAT_ACHIEVEMENT) continue;
			if (g.getCaTaskId() >= 0) continue;
			WikiCaRepository.CaInfo info = wikiCaRepository.get(g.getName());
			if (info != null)
			{
				g.setCaTaskId(info.id);
				filled++;
			}
		}
		if (filled > 0)
		{
			log.info("Backfilled caTaskId for {} existing CA goals", filled);
			goalStore.save();
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		MenuEntry[] entries = event.getMenuEntries();
		if (entries.length == 0)
		{
			return;
		}
		boolean bulkQuestMenuAdded = false;

		// Find the first entry to determine context
		MenuEntry first = entries[entries.length - 1];

		// Look for item or combat achievement context
		for (MenuEntry entry : entries)
		{
			int widgetGroupId = entry.getParam1() >> 16;
			int itemId = entry.getItemId();

			// Skills tab: right-click a skill row -> add Skill goal. Each skill has
			// its own widget id under InterfaceID.Stats.<SKILL>; we map param1 → Skill
			// and prompt for Level / XP via a small dialog.
			net.runelite.api.Skill skillFromWidget = SKILL_TAB_WIDGET_TO_SKILL
				.get(entry.getParam1());
			if (skillFromWidget != null)
			{
				final net.runelite.api.Skill skill = skillFromWidget;
				client.createMenuEntry(1)
					.setOption("Add Goal")
					.setTarget("<col=ff9040>" + skill.getName() + "</col>")
					.setType(MenuAction.RUNELITE)
					.onClick(e -> SwingUtilities.invokeLater(() ->
						panel.openSeededCreate(com.goalplanner.model.GoalType.SKILL, skill, null, null, null, null)));
				break;
			}

			// Achievement diary: right-click an area row -> add 4 "Add Goal: <Tier>" entries
			boolean isDiaryRow = widgetGroupId == AchievementDiaryData.GROUP_ID
				&& entry.getParam1() == AchievementDiaryData.TASKBOX;
			if (isDiaryRow && entry.getOption() != null && entry.getOption().startsWith("Open"))
			{
				String areaDisplayName = AchievementDiaryData.parseAreaFromOption(entry.getOption());
				if (areaDisplayName == null)
				{
					continue;
				}
				final String diaryMenuTarget = "<col=ff9040>" + areaDisplayName + "</col>";

				// One TOP-LEVEL "Add Goal <Tier>" entry per tier, each opening
				// straight into [Default + each user section] - one hover instead
				// of the old Add Goal ▸ Tier ▸ section double hop. Every leaf does
				// a BARE add (no prereq auto-seeding) - organising/seeding is left
				// to the panel's "Add requirements to this section". Inserting
				// EASY→ELITE at index 1 stacks them Easy/Medium/Hard/Elite top to
				// bottom, below the vanilla Open/Wiki entries.
				for (final AchievementDiaryData.Tier tier : AchievementDiaryData.Tier.values())
				{
					final GoalPlannerApi.DiaryTier apiTier;
					switch (tier)
					{
						case EASY:   apiTier = GoalPlannerApi.DiaryTier.EASY; break;
						case MEDIUM: apiTier = GoalPlannerApi.DiaryTier.MEDIUM; break;
						case HARD:   apiTier = GoalPlannerApi.DiaryTier.HARD; break;
						case ELITE:  apiTier = GoalPlannerApi.DiaryTier.ELITE; break;
						default:     continue;
					}
					// Add Goal <Tier> ▸ [Default + each user section].
					final net.runelite.api.Menu tierSub = client.createMenuEntry(1)
						.setOption("Add Goal " + tier.getDisplayName())
						.setTarget(diaryMenuTarget)
						.setType(MenuAction.RUNELITE)
						.createSubMenu();
					// Dedup probe matching insertDiaryGoal's identity (name=area,
					// description="<Tier> Achievement Diary").
					final Goal diaryProbe = Goal.builder()
						.type(GoalType.DIARY)
						.name(areaDisplayName)
						.description(tier.getDisplayName() + " Achievement Diary")
						.build();
					// Bare add for both Default and section entries (diaries don't
					// auto-seed). Gate on skill sync for parity with the quest menu.
					addGoalSectionItems(tierSub, diaryProbe,
						areaDisplayName + " " + tier.getDisplayName(),
						/*defaultAdd=*/() -> goalTrackerApi.addDiaryGoalNoPrereqs(areaDisplayName, apiTier),
						/*sectionAdd=*/() -> goalTrackerApi.addDiaryGoalNoPrereqs(areaDisplayName, apiTier));
				}
				break;
			}

			// Quest list: right-click a row -> add Quest goal
			boolean isQuestList = widgetGroupId == QUESTLIST_GROUP_ID
				&& entry.getParam1() == QUESTLIST_LIST_WIDGET;
			// Bulk quest actions - dev-only testing helpers, trigger on any right-click within the quest list pane.
			if (DEV_MODE && !bulkQuestMenuAdded && widgetGroupId == QUESTLIST_GROUP_ID)
			{
				bulkQuestMenuAdded = true;
				client.createMenuEntry(1)
					.setOption("Add All Unfinished Quests (F2P)")
					.setTarget("")
					.setType(MenuAction.RUNELITE)
					.onClick(e -> skillSyncGate.runWhenSynced(() -> addAllUnfinishedQuests(true)));
				client.createMenuEntry(1)
					.setOption("Add All Unfinished Quests")
					.setTarget("")
					.setType(MenuAction.RUNELITE)
					.onClick(e -> skillSyncGate.runWhenSynced(() -> addAllUnfinishedQuests(false)));
				client.createMenuEntry(1)
					.setOption("Add All Bosses (1 KC)")
					.setTarget("")
					.setType(MenuAction.RUNELITE)
					.onClick(e -> skillSyncGate.runWhenSynced(() -> addAllBossGoals()));
			}

			if (isQuestList)
			{
				final String menuTarget = entry.getTarget();
				final net.runelite.api.Quest quest = findQuestByDisplayName(stripColorTags(menuTarget));
				if (quest == null)
				{
					continue;
				}

				// One "Add Goal" submenu: Default (auto-resolves + seeds prereqs into
				// the default list) followed by each user section (bare add - use the
				// panel's "Add requirements to this section" to seed into a section).
				// Replaces the old separate "Add Goal" + "Add to Section" entries.
				net.runelite.api.Menu questAddSub = client.createMenuEntry(1)
					.setOption("Add Goal")
					.setTarget(menuTarget)
					.setType(MenuAction.RUNELITE)
					.createSubMenu();
				addGoalSectionItems(questAddSub,
					Goal.builder().type(GoalType.QUEST).questName(quest.name()).build(),
					quest.getName(),
					/*defaultAdd=*/() -> goalTrackerApi.addQuestGoal(quest),
					/*sectionAdd=*/() -> goalTrackerApi.addQuestGoalWithPrereqs(quest, Collections.emptyList()));
				break;
			}

			// Combat achievements (CA_TASKS widget group 715)
			boolean isCombatAchievement = widgetGroupId == CombatAchievementData.GROUP_ID
				&& entry.getParam1() == CombatAchievementData.TASKS_BACKGROUND;
			if (isCombatAchievement)
			{
				final int row = entry.getParam0();
				final Goal preview = buildCombatAchievementGoal(row);
				if (preview == null)
				{
					continue;
				}

				// One "Add Goal" submenu: Default + each user section (matches the
				// quest standard; replaces the old separate Add Goal + Add to
				// Section entries). addCombatAchievementGoal dedups internally.
				net.runelite.api.Menu caAddSub = client.createMenuEntry(1)
					.setOption("Add Goal")
					.setTarget(entry.getTarget())
					.setType(MenuAction.RUNELITE)
					.createSubMenu();
				final Supplier<String> caAdd = () ->
				{
					Goal g = buildCombatAchievementGoal(row);
					return g == null ? null : goalTrackerApi.addCombatAchievementGoal(g.getCaTaskId());
				};
				addGoalSectionItems(caAddSub, preview, preview.getName(), caAdd, caAdd);
				break;
			}

			// Total level: right-click the "Total level" box in the stats tab
			// (group=320, child=25) OR any child in the stats group that
			// isn't a skill row.
			if (entry.getParam1() == net.runelite.api.gameval.InterfaceID.Stats.TOTAL)
			{
				client.createMenuEntry(1)
					.setOption("Add Goal")
					.setTarget("<col=ff9040>Total Level</col>")
					.setType(MenuAction.RUNELITE)
					.onClick(e -> SwingUtilities.invokeLater(() ->
						panel.openSeededCreate(com.goalplanner.model.GoalType.ACCOUNT, null, null, null, null,
							com.goalplanner.model.AccountMetric.TOTAL_LEVEL.name())));
				break;
			}

			// Character summary pane (group=712, child=3): the tab buttons
			// for quest list, CA overview, etc. Use param0 to distinguish.
			if (widgetGroupId == 712 && (entry.getParam1() & 0xFFFF) == 3)
			{
				int buttonIndex = entry.getParam0();
				// param0=3: Quest List button → QP goal
				if (buttonIndex == 3 && "Quest List".equals(entry.getOption()))
				{
					client.createMenuEntry(1)
						.setOption("Add Goal")
						.setTarget("<col=ff9040>Quest Points</col>")
						.setType(MenuAction.RUNELITE)
						.onClick(e -> SwingUtilities.invokeLater(() ->
							panel.openSeededCreate(com.goalplanner.model.GoalType.ACCOUNT, null, null, null, null,
							com.goalplanner.model.AccountMetric.QUEST_POINTS.name())));
					break;
				}
				// param0=5: CA Overview/Tasks/Bosses/Rewards → one entry per CA tier
				if (buttonIndex == 5 && ("Overview".equals(entry.getOption())
					|| "Tasks".equals(entry.getOption())
					|| "Bosses".equals(entry.getOption())
					|| "Rewards".equals(entry.getOption())))
				{
					// Iterate tiers so Easy is at the top, Grandmaster at the bottom
					for (int ti = 0; ti < com.goalplanner.model.AccountMetric.CA_TIER_NAMES.length; ti++)
					{
						final int tierTarget = com.goalplanner.model.AccountMetric.CA_TIER_VALUES[ti];
						final String tierName = com.goalplanner.model.AccountMetric.CA_TIER_NAMES[ti];
						client.createMenuEntry(1)
							.setOption("Add Goal: " + tierName)
							.setTarget("<col=ff9040>CA Points</col>")
							.setType(MenuAction.RUNELITE)
							.onClick(e -> goalTrackerApi.addAccountGoal(
								com.goalplanner.model.AccountMetric.CA_POINTS.name(), tierTarget));
					}
					break;
				}
			}


			// Check if this is an inventory, bank, or collection log item
			boolean isInventory = widgetGroupId == InterfaceID.INVENTORY;
			boolean isBank = widgetGroupId == InterfaceID.BANKMAIN;
			boolean isCollectionLog = widgetGroupId == InterfaceID.COLLECTION;

			if (!isInventory && !isBank && !isCollectionLog)
			{
				continue;
			}

			// Collection log: check if the entry name matches a known boss for KC goals.
			// For raids and multi-tier bosses, show one menu entry per tier.
			if (isCollectionLog)
			{
				String targetName = stripColorTags(entry.getTarget());
				List<String> bossCandidates = com.goalplanner.data.BossKillData.resolveCollectionLogName(targetName);
				if (!bossCandidates.isEmpty())
				{
					for (String resolvedName : bossCandidates)
					{
						final String bossName = resolvedName;
						// Quick-add at KC 1, matching the collection-log item flow and
						// the section-submenu pattern other goals use - adjust the
						// kill count afterward via the card's Change Amount action.
						// addBossGoal seeds prereqs (Quest.getState / getRealSkillLevel),
						// which must run on the client thread; the RUNELITE onClick fires
						// there and runWhenSynced preserves it (immediate or tick-drained).
						final Goal bossProbe = Goal.builder()
							.type(GoalType.BOSS).bossName(bossName).build();
						final Supplier<String> bossAdd =
							() -> goalTrackerApi.addBossGoal(bossName, 1);
						if (!userSections().isEmpty())
						{
							net.runelite.api.Menu bossSub = client.createMenuEntry(1)
								.setOption("Add " + bossName + " KC Goal")
								.setTarget(entry.getTarget())
								.setType(MenuAction.RUNELITE)
								.createSubMenu();
							addGoalSectionItems(bossSub, bossProbe, bossName, bossAdd, bossAdd);
						}
						else
						{
							client.createMenuEntry(1)
								.setOption("Add " + bossName + " KC Goal")
								.setTarget(entry.getTarget())
								.setType(MenuAction.RUNELITE)
								.onClick(e -> skillSyncGate.runWhenSynced(bossAdd::get));
						}
					}
					continue;
				}
			}
			if (itemId <= 0)
			{
				continue;
			}

			// Get the real item ID (noted items have different IDs)
			final int realItemId = itemManager.canonicalize(itemId);
			String rawName = itemManager.getItemComposition(realItemId).getName();
			final String itemName = rawName != null && rawName.endsWith("(Members)")
				? rawName.substring(0, rawName.length() - "(Members)".length()).trim()
				: rawName;
			final boolean fromCollectionLog = isCollectionLog;

			// Collection log: an "Add Goal" submenu (Default + each user section)
			// so the item lands where you want, matching quests / CAs. Inventory
			// and bank keep the single quick "Add Goal" (-> default Incomplete).
			if (fromCollectionLog && !userSections().isEmpty())
			{
				net.runelite.api.Menu itemSub = client.createMenuEntry(1)
					.setOption("Add Goal")
					.setTarget(entry.getTarget())
					.setType(MenuAction.RUNELITE)
					.createSubMenu();
				itemSub.createMenuEntry(0)
					.setOption("Default")
					.setType(MenuAction.RUNELITE)
					.onClick(e -> quickAddItemGoal(realItemId, itemName, null));
				for (com.goalplanner.api.SectionView section : userSections())
				{
					final String sid = section.id;
					itemSub.createMenuEntry(0)
						.setOption(section.name)
						.setType(MenuAction.RUNELITE)
						.onClick(e -> quickAddItemGoal(realItemId, itemName, sid));
				}
			}
			else
			{
				// Add at index 1 to put it near the bottom of the menu. Collection-log
				// items (no user sections to pick from here) still quick-add at 1;
				// inventory/bank items prompt for a stockpile quantity.
				client.createMenuEntry(1)
					.setOption("Add Goal")
					.setTarget(entry.getTarget())
					.setType(MenuAction.RUNELITE)
					.onClick(e ->
					{
						if (fromCollectionLog) quickAddItemGoal(realItemId, itemName, null);
						else panel.openSeededCreate(com.goalplanner.model.GoalType.ITEM_GRIND, null, null, realItemId, itemName, null);
					});
			}

			// Only add one "Add Goal" entry
			break;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (client.getGameState() != GameState.LOGGED_IN) return;
		// Record the post-login stat burst for the seed gate before any
		// early-return below - the burst arrives regardless of whether
		// tracking is currently suspended.
		skillSyncGate.onStatChanged();
		// Coalesce: a single XP drop can fire several StatChanged (and 23 in a
		// burst at login). Mark dirty and run the trackers once per tick in
		// onGameTick. The suspension window (stale post-profile-switch skill XP
		// cache) is honoured by the drain, which skips while suspended.
		trackersDirty = true;
	}

	/**
	 * Advances the skill-sync gate and drains any prereq seeding that was
	 * deferred while the skill cache was still loading. Runs on the client
	 * thread, so drained seed actions (which call getRealSkillLevel /
	 * Quest.getState) read live state safely.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN) return;
		skillSyncGate.onTick();
		// Cache the RSN on the client thread so the share UI can read it off the EDT.
		net.runelite.api.Player local = client.getLocalPlayer();
		if (local != null && local.getName() != null)
		{
			localPlayerName = local.getName();
		}
		drainTrackerUpdates();
	}

	/**
	 * Run the tracker suite once, on the client thread, if a varbit/stat change
	 * marked it dirty this tick. Coalesces the many per-tick varbit/stat events
	 * into a single pass. Skips (leaving the dirty flag set) while tracking is
	 * suspended, so it drains on the first tick after the window ends.
	 */
	private void drainTrackerUpdates()
	{
		if (!trackersDirty || isTrackingSuspended()) return;
		trackersDirty = false;
		List<Goal> goals = goalStore.getGoals();
		boolean updated = skillTracker.checkGoals(goals);
		updated |= questTracker.checkGoals(goals);
		updated |= diaryTracker.checkGoals(goals);
		updated |= combatAchievementTracker.checkGoals(goals);
		updated |= accountTracker.checkGoals(goals);
		updated |= bossKillTracker.checkGoals(goals);
		flushIfUpdated(updated);
		maybeAutoAddMiscApprovalGoal();
	}

	/**
	 * Immediately recheck item goals against the currently-cached bank/inventory
	 * containers. Runs on the client thread so ItemContainer reads are safe.
	 * This lets a newly-added ITEM_GRIND goal get its count populated right away
	 * if the bank is already open (otherwise it waits for the next
	 * ItemContainerChanged event, which only fires on open).
	 */
	private void refreshItemGoalsNow()
	{
		clientThread.invokeLater(() ->
			flushIfUpdated(itemTracker.checkGoals(goalStore.getGoals())));
	}

	/**
	 * Immediately sync skill + account goals against current client state. Skill
	 * goals are created at currentValue 0 and otherwise only sync on the next
	 * {@link StatChanged}, so a freshly-added or seeded "70 Agility" goal would
	 * sit at 0 XP until the player next gains XP in some skill. Running on the
	 * client thread (getSkillExperience is client-thread-only) populates them at
	 * once. Idempotent - already-synced goals are no-ops.
	 */
	private void refreshSkillGoalsNow()
	{
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN) return;
			List<Goal> goals = goalStore.getGoals();
			boolean updated = skillTracker.checkGoals(goals);
			updated |= accountTracker.checkGoals(goals);
			flushIfUpdated(updated);
			// Recompute "blocked by prereqs" badges (live skill/quest/account
			// reads - client thread). If the set changed, rebuild the panel so
			// the badges update. Doesn't fire onGoalsChanged, so no refresh loop.
			if (goalTrackerApi.recomputeBlockedRequirements())
			{
				SwingUtilities.invokeLater(panel::rebuild);
			}
		});
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int containerId = event.getContainerId();
		if (containerId != InventoryID.BANK.getId() && containerId != InventoryID.INVENTORY.getId())
		{
			return;
		}
		if (isTrackingSuspended()) return;

		flushIfUpdated(itemTracker.checkGoals(goalStore.getGoals()));
	}

	/**
	 * Add all unfinished quests as goals. If f2pOnly is true, only F2P
	 * quests are added. Skips quests already finished and quests that
	 * already have a goal.
	 */
	private void addAllUnfinishedQuests(boolean f2pOnly)
	{
		List<net.runelite.api.Quest> toAdd = new ArrayList<>();

		// Quests in the RuneLite enum but not yet released in-game.
		Set<net.runelite.api.Quest> UNRELEASED = EnumSet.of(
			net.runelite.api.Quest.THE_RED_REEF
		);

		for (net.runelite.api.Quest quest : net.runelite.api.Quest.values())
		{
			if (UNRELEASED.contains(quest)) continue;
			if (f2pOnly && !com.goalplanner.data.QuestRequirements.isF2P(quest))
			{
				continue;
			}
			try
			{
				if (quest.getState(client) == net.runelite.api.QuestState.FINISHED)
				{
					continue;
				}
			}
			catch (Exception e)
			{
				continue;
			}
			toAdd.add(quest);
		}

		goalTrackerApi.beginCompound(f2pOnly ? "Add all F2P quests" : "Add all quests");
		try
		{
			int added = 0;
			for (net.runelite.api.Quest quest : toAdd)
			{
				goalTrackerApi.addQuestGoal(quest);
				added++;
			}
			// Final pass: promote all zero-dependency quests to the top
			// across all goals added in this bulk action.
			reorderingService.promoteLeafGoalsToTop();

			log.info("Added {} unfinished {} quests with requirements", added, f2pOnly ? "F2P" : "all");
		}
		finally
		{
			goalTrackerApi.endCompound();
		}
	}

	/**
	 * Add all known bosses as kill count goals with target 1.
	 * Stress test utility.
	 */
	private void addAllBossGoals()
	{
		goalTrackerApi.beginCompound("Add all bosses (1 KC)");
		try
		{
			int added = 0;
			for (String bossName : com.goalplanner.data.BossKillData.getBossNames())
			{
				String id = goalTrackerApi.addBossGoal(bossName, 1);
				if (id != null) added++;
			}
			log.info("Added {} boss goals", added);
		}
		finally
		{
			goalTrackerApi.endCompound();
		}
	}

	// =====================================================================
	// "Add to Section" - drop an in-game goal straight into a user section
	// =====================================================================

	/** The user's (non-built-in) sections, in panel order. Empty if none. */
	private List<com.goalplanner.api.SectionView> userSections()
	{
		List<com.goalplanner.api.SectionView> out = new ArrayList<>();
		for (com.goalplanner.api.SectionView sv : goalTrackerApi.queryAllSections())
		{
			if (!sv.builtIn)
			{
				out.add(sv);
			}
		}
		return out;
	}

	/**
	 * Add a bare goal (no prereq seeding) into a user section as ONE undoable
	 * gesture. The {@code bareAdd} supplier creates the goal (landing in the
	 * default Incomplete) and returns its id; we then move it into the section,
	 * where it stays put even when already complete (user sections keep their
	 * completed goals inline). Seed-free - the user is curating exact goals.
	 */
	private void addToSection(String sectionId, Goal probe, String label,
		Supplier<String> bareAdd)
	{
		// Per-section dedup: if this section already holds the goal, do nothing
		// (prevents creating an orphan default goal when the target already has it).
		if (probe != null && goalStore.findEquivalentInNamespace(sectionId, probe) != null)
		{
			return;
		}
		goalTrackerApi.beginCompound("Add to section: " + label);
		try
		{
			String id = bareAdd.get();
			if (id != null)
			{
				goalTrackerApi.moveGoalToSection(id, sectionId);
			}
		}
		finally
		{
			goalTrackerApi.endCompound();
		}
	}

	/**
	 * Inject an "Add to Section" affordance next to an in-game "Add Goal" entry.
	 * No user sections → nothing added. One → a single direct entry. Several →
	 * an "Add to Section ▸ &lt;section&gt;" submenu.
	 */
	/**
	 * Populate an "Add Goal" submenu with a <b>Default</b> entry followed by one
	 * entry per user section. Default runs {@code defaultAdd} (e.g. the
	 * seed-prereqs add for quests); each section runs {@code sectionAdd} then moves
	 * the new goal into that section (via {@link #addToSection}, which dedups).
	 */
	/**
	 * Build an ITEM_GRIND goal: gather auto-tags (client thread), then prompt for
	 * a target quantity on the EDT and add it. A non-null {@code sectionId} lands
	 * it in that section; null uses the default Incomplete list. Must be called on
	 * the client thread (ItemManager tag lookup).
	 */
	// Inventory/bank items prompt for a target quantity (stockpile goals tend to
	// want a specific count). Collection-log items skip the prompt and land at 1
	// via quickAddItemGoal - you're bookmarking "get this drop", and the count is
	// adjustable afterward through the card's Change Amount action.

	private void quickAddItemGoal(int realItemId, String itemName, String sectionId)
	{
		final List<String> autoTagIds = resolveItemTagIds(realItemId);
		SwingUtilities.invokeLater(
			() -> addItemGoalInternal(realItemId, itemName, sectionId, 1, autoTagIds));
	}

	// Resolve auto-tags on the calling (client) thread - buildItemTags reads item
	// metadata that must not run on the EDT.
	private List<String> resolveItemTagIds(int realItemId)
	{
		List<ItemTag> autoTags = buildItemTags(realItemId);
		List<String> autoTagIds = new ArrayList<>();
		for (ItemTag spec : autoTags)
		{
			com.goalplanner.model.Tag tag =
				goalStore.findOrCreateSystemTag(spec.getLabel(), spec.getCategory());
			if (tag != null) autoTagIds.add(tag.getId());
		}
		return autoTagIds;
	}

	// Build + add the item goal. Must run on the EDT (addGoalUndoable / refresh).
	private void addItemGoalInternal(int realItemId, String itemName, String sectionId,
		int qty, List<String> autoTagIds)
	{
		Goal goal = Goal.builder()
			.type(GoalType.ITEM_GRIND)
			.name(itemName)
			.description(FormatUtil.formatNumber(qty) + " total")
			.itemId(realItemId)
			.targetValue(qty)
			.currentValue(-1)
			.sectionId(sectionId)
			.tagIds(new ArrayList<>(autoTagIds))
			.defaultTagIds(new ArrayList<>(autoTagIds))
			.build();
		addGoalUndoable(goal, "Add goal: " + itemName);
		refreshItemGoalsNow();
	}

	private void addGoalSectionItems(net.runelite.api.Menu sub, Goal probe, String label,
		Supplier<String> defaultAdd,
		Supplier<String> sectionAdd)
	{
		sub.createMenuEntry(0)
			.setOption("Default")
			.setType(MenuAction.RUNELITE)
			.onClick(e -> skillSyncGate.runWhenSynced(defaultAdd::get));
		for (com.goalplanner.api.SectionView section : userSections())
		{
			final String sectionId = section.id;
			sub.createMenuEntry(0)
				.setOption(section.name)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> skillSyncGate.runWhenSynced(() -> addToSection(sectionId, probe, label, sectionAdd)));
		}
	}


	/**
	 * Common flush after tracker updates: reconcile completed goals into
	 * the Completed section, persist, and rebuild the UI on the EDT.
	 */
	private void flushIfUpdated(boolean updated)
	{
		if (updated)
		{
			boolean sectionChanged = goalStore.reconcileDerivedSections();
			// Snapshot dirty IDs before save clears them
			Set<String> dirtyIds = goalStore.getDirtyGoalIds();
			goalStore.saveDirtyGoals();

			if (sectionChanged)
			{
				// Goals moved between sections - need a full rebuild. fireGoalsChanged
				// touches selection + command history (EDT-owned state) and triggers
				// the rebuild, so run it on the EDT rather than this client thread.
				SwingUtilities.invokeLater(() -> goalTrackerApi.fireGoalsChanged());
			}
			else
			{
				// O(dirty) incremental card update - no full rebuild
				SwingUtilities.invokeLater(() -> panel.refreshProgress(dirtyIds));
			}
		}
	}

	@Provides
	GoalPlannerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GoalPlannerConfig.class);
	}
}
