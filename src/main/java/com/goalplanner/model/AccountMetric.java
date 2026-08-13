package com.goalplanner.model;

import java.awt.Color;

/**
 * Account-wide metrics that can be tracked as goals. Each metric
 * defines how to display the goal and what client API to read.
 * The actual client API reading is done by
 * {@link com.goalplanner.tracker.AccountTracker}.
 */
public enum AccountMetric
{
	/**
	 * maxTarget is a static fallback (341 as of Fallen From Grace, 2026-07) -
	 * goal creation prefers the live sum of the client's quest DB table via
	 * {@link #effectiveMaxTarget}, which grows as new quests release.
	 */
	QUEST_POINTS("Quest Points", new Color(65, 155, 222), 899, null, 1, 341,
		AccountMetric.MONOTONIC),
	COMBAT_LEVEL("Combat Level", new Color(200, 60, 60), 168, null, 3, 126,
		AccountMetric.MONOTONIC),
	TOTAL_LEVEL("Total Level", new Color(76, 175, 80), 898, null, 1, 2376,
		AccountMetric.MONOTONIC),
	// MUSIC_TRACKS deferred - no reliable bulk API for counting unlocked tracks
	CA_POINTS("CA Points", new Color(139, 69, 19), 0, null, 1, 2630,
		AccountMetric.MONOTONIC),
	/**
	 * Slayer reward points. The varbit is the SPENDABLE balance, not lifetime
	 * earned - buying unlocks, task skips or blocks subtracts from it - so a
	 * "have N points" goal stops being met the moment you spend them.
	 */
	SLAYER_POINTS("Slayer Points", new Color(0, 160, 160), 0, null, 1, 64000,
		AccountMetric.DECAYS),
	KUDOS("Museum Kudos", new Color(200, 170, 50), 0, "item:11182", 1, 243,
		AccountMetric.MONOTONIC),
	/** Combined Attack + Strength level (e.g. 130 for Warriors Guild entry). */
	ATT_STR_COMBINED("Att + Str", new Color(46, 125, 50), 0, "item:8850", 2, 198,
		AccountMetric.MONOTONIC),
	/**
	 * Kingdom of Miscellania approval rating (0-127, where 127 = 100%).
	 * Favour decays every day you stay away from the kingdom, so completion
	 * has to follow the varbit back down - see {@link #decays()}.
	 */
	MISC_APPROVAL("Misc. Approval", new Color(200, 170, 50), 0, null, 1, 127,
		AccountMetric.DECAYS),
	/**
	 * Tears of Guthix personal best (max tears collected in a single game).
	 * Collection time in the minigame is capped by quest points, so this
	 * ceiling IS the quest-point maximum - the static fallback matches
	 * QUEST_POINTS and {@link #effectiveMaxTarget} shares its live read.
	 */
	TOG_MAX_TEARS("Tears of Guthix PB", new Color(100, 180, 220), 0, null, 1, 341,
		AccountMetric.MONOTONIC),
	/** Chompy bird kill count. */
	CHOMPY_KILLS("Chompy Kills", new Color(139, 69, 19), 0,
		"item:" + net.runelite.api.ItemID.RAW_CHOMPY, 1, 4000, AccountMetric.MONOTONIC),
	/** Fortis Colosseum personal best glory (highest single-run glory). */
	COLOSSEUM_GLORY("Colosseum Glory", new Color(212, 175, 55), 0,
		"item:" + net.runelite.api.ItemID.SMOL_HEREDIT, 1, 100000, AccountMetric.MONOTONIC),
	/** Doom of Mokhaiotl deepest delve level reached (1-8+). */
	DOM_DEEPEST_LEVEL("DoM Deepest Level", new Color(80, 40, 120), 0, null, 1, 8,
		AccountMetric.MONOTONIC),
	/**
	 * Collection log unique slots obtained (VarPlayer COLLECTION_COUNT,
	 * transmitted on login since the Jan 2025 collection log update; reads 0
	 * until the account has synced by opening the collection log once).
	 * maxTarget is a static fallback for the slot total - goal creation
	 * prefers the live total from VarPlayer COLLECTION_COUNT_MAX, which
	 * grows as new slots are added to the game.
	 */
	COLLECTION_LOG_SLOTS("Collection Log Slots", new Color(181, 137, 60), 0,
		"item:" + net.runelite.api.ItemID.COLLECTION_LOG, 1, 1701, AccountMetric.MONOTONIC),
	/**
	 * Achievement diary tiers completed across all 12 areas and 4 tiers
	 * (0-48). Summed from the same per-tier completion varbit refdata that
	 * DIARY goals use (AchievementDiaryData), including the Karamja
	 * Easy/Medium/Hard task-count varbits.
	 * Icon: SpriteID.AchievementDiaryIcons.GREEN_ACHIEVEMENT_DIARIES.
	 */
	DIARY_TIERS_COMPLETED("Diary Tiers", new Color(76, 175, 80), 836, null, 1, 48,
		AccountMetric.MONOTONIC),
	/**
	 * League Points - lifetime points earned during the active OSRS Leagues
	 * event (VarPlayer LEAGUE_POINTS_COMPLETED). Only increments; spending
	 * currency does not affect this counter. Reads 0 outside a leagues world.
	 * Icon: TAB_QUESTS_ORANGE_ADVENTURE_PATHS - the orange quest-tab sprite
	 * used for the leagues pane (orange counterpart to the green diary /
	 * red minigames tab icons).
	 */
	LEAGUE_POINTS("League Points", new Color(255, 152, 0), 1713, null, 1, 500000,
		AccountMetric.MONOTONIC),
	/**
	 * Total Leagues tasks completed across all difficulty tiers
	 * (Varbit LEAGUE_TOTAL_TASKS_COMPLETED). Reads 0 outside a leagues world.
	 * Icon: TAB_QUESTS_ORANGE_ADVENTURE_PATHS - the orange quest-tab sprite
	 * used for the leagues pane (orange counterpart to the green diary /
	 * red minigames tab icons).
	 */
	LEAGUE_TASKS("Leagues Tasks", new Color(255, 152, 0), 1713, null, 1, 1500,
		AccountMetric.MONOTONIC);

	/**
	 * Constructor marker: the live value can fall during normal play, so a goal
	 * on it is only "complete" while the live value still meets the target.
	 */
	static final boolean DECAYS = true;
	/**
	 * Constructor marker: the live value never falls, so completion is sticky -
	 * once earned, a transient low read must not un-complete the goal.
	 */
	static final boolean MONOTONIC = false;

	private final String displayName;
	private final Color color;
	private final int spriteId;
	/** Item or bundled icon key (e.g. "item:11182"). Null = use spriteId. */
	private final String iconKey;
	private final int minTarget;
	private final int maxTarget;
	private final boolean decays;

	AccountMetric(String displayName, Color color, int spriteId, String iconKey,
		int minTarget, int maxTarget, boolean decays)
	{
		this.displayName = displayName;
		this.color = color;
		this.spriteId = spriteId;
		this.iconKey = iconKey;
		this.minTarget = minTarget;
		this.maxTarget = maxTarget;
		this.decays = decays;
	}

	/**
	 * Whether this metric's live value can FALL during normal play.
	 *
	 * <p>Completion is sticky for every other goal in the plugin: 99 Attack and
	 * a finished quest cannot be un-earned, so once {@code completedAt} is
	 * stamped the trackers leave the goal alone (which also stops a transient
	 * pre-sync zero read from clobbering the completion date). A decaying metric
	 * breaks that assumption - Miscellania favour falls a little every day you
	 * stay away - so for these two metrics completion is derived ABSOLUTELY from
	 * the live value: below target re-opens the goal, back at target completes
	 * it again. See {@link com.goalplanner.tracker.AccountTracker}.
	 *
	 * <p>Classification, one line per metric:
	 * <ul>
	 *   <li>MISC_APPROVAL - DECAYS. Kingdom favour drops ~1%/day of absence.</li>
	 *   <li>SLAYER_POINTS - DECAYS. The varbit is the spendable balance;
	 *       unlocks/skips/blocks subtract from it.</li>
	 *   <li>QUEST_POINTS, DIARY_TIERS_COMPLETED, CA_POINTS, KUDOS,
	 *       CHOMPY_KILLS, COLLECTION_LOG_SLOTS - monotonic counters of things
	 *       completed or obtained; the game never takes them back.</li>
	 *   <li>TOTAL_LEVEL, COMBAT_LEVEL, ATT_STR_COMBINED - real (unboosted)
	 *       levels, which never drop.</li>
	 *   <li>TOG_MAX_TEARS, COLOSSEUM_GLORY, DOM_DEEPEST_LEVEL - personal
	 *       bests: a worse run does not lower the record.</li>
	 *   <li>LEAGUE_POINTS - monotonic despite being a currency: the varp read
	 *       is LEAGUE_POINTS_COMPLETED (lifetime EARNED), not the spendable
	 *       balance, so shopping does not move it.</li>
	 *   <li>LEAGUE_TASKS - monotonic count of tasks completed.</li>
	 * </ul>
	 */
	public boolean decays() { return decays; }

	public String getDisplayName() { return displayName; }
	public Color getColor() { return color; }
	public int getMinTarget() { return minTarget; }
	public int getMaxTarget() { return maxTarget; }

	/**
	 * Effective maximum target. For COLLECTION_LOG_SLOTS the true ceiling grows
	 * with the game, so prefer the live slot total transmitted to the client
	 * (VarPlayer COLLECTION_COUNT_MAX); every other metric uses the shipped
	 * static {@link #getMaxTarget()}.
	 *
	 * <p>QUEST_POINTS / TOG_MAX_TEARS deliberately stay static: a quest-DB-table
	 * sum was tried and over-counted (miniquest / multi-part rows the wiki
	 * doesn't count toward the cap), so the wiki-authoritative static (bumped
	 * per release) is the reliable source.
	 */
	public int effectiveMaxTarget(net.runelite.api.Client client)
	{
		if (client == null || this != COLLECTION_LOG_SLOTS)
		{
			return maxTarget;
		}
		// getVarpValue is a client-thread op; off the client thread the dev
		// build (-ea) raises AssertionError. Callers SHOULD invoke this on the
		// client thread, but catch Throwable defensively so a stray off-thread
		// call degrades to the static instead of killing the calling UI action.
		try
		{
			int live = client.getVarpValue(net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT_MAX);
			return live > 0 ? live : maxTarget;
		}
		catch (Throwable t)
		{
			return maxTarget;
		}
	}

	/**
	 * The player's current live value for this metric, read from the client, or
	 * -1 if unreadable. Single source of truth shared by the AccountTracker and
	 * the requirement resolvers (which pre-filter already-met account
	 * requirements). Reads are client-thread operations; callers invoke on the
	 * client thread (the catch is defensive against a stray off-thread call).
	 *
	 * <p>Note: no leagues-world guard here - that's tracker policy and lives in
	 * {@link com.goalplanner.tracker.AccountTracker}.
	 */
	public int currentValue(net.runelite.api.Client client)
	{
		if (client == null)
		{
			return -1;
		}
		try
		{
			switch (this)
			{
				case QUEST_POINTS:
					return client.getVarpValue(net.runelite.api.gameval.VarPlayerID.QP);
				case COMBAT_LEVEL:
				{
					net.runelite.api.Player p = client.getLocalPlayer();
					return p != null ? p.getCombatLevel() : -1;
				}
				case TOTAL_LEVEL:
					return client.getTotalLevel();
				case CA_POINTS:
					return client.getVarbitValue(net.runelite.api.gameval.VarbitID.CA_POINTS);
				case SLAYER_POINTS:
					return client.getVarbitValue(net.runelite.api.gameval.VarbitID.SLAYER_POINTS);
				case KUDOS:
					return client.getVarbitValue(net.runelite.api.gameval.VarbitID.VM_KUDOS);
				case ATT_STR_COMBINED:
					return client.getRealSkillLevel(net.runelite.api.Skill.ATTACK)
						+ client.getRealSkillLevel(net.runelite.api.Skill.STRENGTH);
				case MISC_APPROVAL:
					return client.getVarbitValue(net.runelite.api.gameval.VarbitID.MISC_APPROVAL);
				case TOG_MAX_TEARS:
					return client.getVarbitValue(net.runelite.api.gameval.VarbitID.TOG_MAX_TEARS_COLLECTED);
				case CHOMPY_KILLS:
					return client.getVarpValue(net.runelite.api.gameval.VarPlayerID.CHOMPYBIRD);
				case COLOSSEUM_GLORY:
					return client.getVarpValue(net.runelite.api.gameval.VarPlayerID.COLOSSEUM_GLORY);
				case DOM_DEEPEST_LEVEL:
					return client.getVarpValue(net.runelite.api.gameval.VarPlayerID.DOM_DEEPEST_LEVEL);
				case COLLECTION_LOG_SLOTS:
				{
					int slots = client.getVarpValue(net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT);
					return slots > 0 ? slots : -1;
				}
				case DIARY_TIERS_COMPLETED:
					return com.goalplanner.data.AchievementDiaryData
						.countCompletedTiers(client::getVarbitValue);
				case LEAGUE_POINTS:
					return client.getVarpValue(net.runelite.api.gameval.VarPlayerID.LEAGUE_POINTS_COMPLETED);
				case LEAGUE_TASKS:
					return client.getVarbitValue(net.runelite.api.gameval.VarbitID.LEAGUE_TOTAL_TASKS_COMPLETED);
				default:
					return -1;
			}
		}
		catch (Throwable t)
		{
			return -1;
		}
	}

	/** Parse an enum-constant name to its {@link AccountMetric}, or null. */
	public static AccountMetric parse(String name)
	{
		if (name == null)
		{
			return null;
		}
		try
		{
			return valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	/**
	 * Whether this metric is scoped to the OSRS Leagues short-lived event.
	 * Goals with leagues-scoped metrics are tracked only on SEASONAL worlds;
	 * all other metrics are tracked only on non-seasonal (main) worlds. This
	 * prevents cross-contamination where a leagues account's boosted state
	 * auto-completes main-account goals (and vice versa).
	 */
	public boolean isLeagues()
	{
		return this == LEAGUE_POINTS || this == LEAGUE_TASKS;
	}

	/** RuneLite sprite ID for the goal card icon, or 0 if no sprite. */
	public int getSpriteId() { return spriteId; }

	/** Item or bundled icon key, or null to use spriteId instead. */
	public String getIconKey() { return iconKey; }

	/** Packed 0xRRGGBB for use as a Goal background color override. */
	public int getColorRgb()
	{
		return (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
	}

	/**
	 * Resolve the sprite ID for a CA Points goal based on the target value.
	 * Returns the appropriate tier sword sprite, or 0 for non-CA metrics.
	 */
	public int resolveSpriteForTarget(int target)
	{
		if (this != CA_POINTS) return spriteId;
		return caTierSprite(target);
	}

	/**
	 * Resolve the icon key for a goal based on the target value.
	 * CA_POINTS below Easy tier uses a bronze knife item icon.
	 */
	public String resolveIconKeyForTarget(int target)
	{
		if (this == CA_POINTS && target <= 40) return "item:864";
		return iconKey;
	}

	// --- CA tier constants ---

	/** CA tier sword sprites: Easy=3399 .. Grandmaster=3404. */
	private static final int CA_SPRITE_BASE = 3399;

	private static final String[] CA_TIER_LABELS = {
		"None", "Easy", "Med", "Hard", "Elite", "Master", "GM"
	};
	private static final int[] CA_TIER_THRESHOLDS = {
		0, 41, 161, 416, 1064, 1904, 2630
	};

	private static int caTierIndex(int points)
	{
		for (int i = CA_TIER_THRESHOLDS.length - 1; i >= 1; i--)
		{
			if (points >= CA_TIER_THRESHOLDS[i]) return i;
		}
		return 0;
	}

	private static int caTierSprite(int points)
	{
		int idx = caTierIndex(points);
		return idx >= 1 ? CA_SPRITE_BASE + (idx - 1) : 0;
	}

	/**
	 * Returns the CA tier label for a given point target
	 * (e.g. 41 → "Easy", 2630 → "Grandmaster", 40 → "None").
	 */
	public static String caTierLabel(int points)
	{
		return CA_TIER_LABELS[caTierIndex(points)];
	}

	/** CA tier shortcut labels for the UI. */
	public static final String[] CA_TIER_NAMES = {
		"Easy (41)", "Medium (161)", "Hard (416)",
		"Elite (1064)", "Master (1904)", "Grandmaster (2630)"
	};
	/** CA tier shortcut values matching CA_TIER_NAMES. */
	public static final int[] CA_TIER_VALUES = {
		41, 161, 416, 1064, 1904, 2630
	};

	/**
	 * Leagues reward-shop tier thresholds (points required to unlock each
	 * trophy/tier). Useful as quick-pick goal targets in the create dialog.
	 * Tier 1 is omitted because it starts at 0 points.
	 */
	public static final String[] LEAGUE_TIER_NAMES = {
		"Tier 2 (600)", "Tier 3 (1,200)", "Tier 4 (2,600)",
		"Tier 5 (5,200)", "Tier 6 (8,500)", "Tier 7 (16,500)",
		"Tier 8 (28,000)"
	};
	/** League tier shortcut values matching LEAGUE_TIER_NAMES. */
	public static final int[] LEAGUE_TIER_VALUES = {
		600, 1200, 2600, 5200, 8500, 16500, 28000
	};

	/**
	 * Leagues area-unlock milestones - tasks required to unlock each
	 * additional region (1st area is free). Used as quick-pick targets
	 * for LEAGUE_TASKS goals.
	 */
	public static final String[] LEAGUE_AREA_NAMES = {
		"Area 2 (80)", "Area 3 (200)", "Area 4 (300)", "Area 5 (450)"
	};
	/** League area milestone values matching LEAGUE_AREA_NAMES. */
	public static final int[] LEAGUE_AREA_VALUES = {
		80, 200, 300, 450
	};
}
