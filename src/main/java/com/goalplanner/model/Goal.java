package com.goalplanner.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Goal
{
	@Builder.Default
	private String id = UUID.randomUUID().toString();

	private GoalType type;

	@Builder.Default
	private GoalStatus status = GoalStatus.ACTIVE;

	private String name;
	private String description;

	@Builder.Default
	private int priority = Integer.MAX_VALUE;

	// Progress
	private int targetValue;
	private int currentValue;

	// Type-specific references (nullable)
	private String skillName;     // For SKILL goals - matches net.runelite.api.Skill name
	private String questName;     // For QUEST goals - matches net.runelite.api.Quest name
	private String accountMetric; // For ACCOUNT goals - matches AccountMetric enum name
	private String bossName;      // For BOSS goals - matches BossKillData display name
	private int varbitId;         // For DIARY/COMBAT_ACHIEVEMENT goals
	private int itemId;           // For ITEM_GRIND goals
	private int spriteId;         // Optional sprite icon (e.g. CA tier sword); 0 = unset
	private String tooltip;       // Optional hover tooltip text (e.g. CA full description)
	private String sectionId;     // Section this goal belongs to; null = unassigned (migrated on load)
	// When a completed goal is auto-archived OUT of a user section to the
	// Completed bucket, this remembers its home section so it can flip back if
	// that section switches to keep-inline. null = a genuine default-bucket goal.
	private String archivedFromSectionId;

	// For COMBAT_ACHIEVEMENT goals: the wiki / in-game CA task id. Used to look up
	// the bit-packed completion state from one of the 20 CA_TASK_COMPLETED varplayers.
	// Sentinel -1 = not set; tracker skips when negative.
	@Builder.Default
	private int caTaskId = -1;

	/**
	 * User-set background color override packed as 0xRRGGBB. -1 means "use the
	 * GoalType default color". Only custom goals can meaningfully set this
	 * (enforced by API) - other types have category-driven colors.
	 */
	@Builder.Default
	private int customColorRgb = -1;

	// Tag references - IDs into the GoalStore tag collection.
	// Tags themselves are first-class entities; goals only carry references.
	@Builder.Default
	private List<String> tagIds = new ArrayList<>();

	// Default tag id snapshot from creation, for "Restore Defaults"
	@Builder.Default
	private List<String> defaultTagIds = new ArrayList<>();

	// ---- Relations ----
	// Outgoing edges in the requires-DAG: IDs of other goals this one depends
	// on. "Horror from the Deep requires 35 Agility" → HFTD's requiredGoalIds
	// contains the Agility goal's id. Incoming edges ("required by") are NOT
	// stored - they're derived at query time by scanning all goals.
	//
	// Cross-section references ARE allowed. Cycles are rejected by
	// GoalStore.addRequirement; load-time cycle detection drops offending
	// edges rather than failing the load.
	@Builder.Default
	private List<String> requiredGoalIds = new ArrayList<>();

	/** OR-prerequisite edges: ANY one of these completing (combined with ALL
	 *  AND-prereqs in requiredGoalIds being met) satisfies this goal's
	 *  requirement. Empty = no OR-prereqs. Used by unlock goals like
	 *  Warriors Guild Entry (99 Attack OR 99 Strength OR 130 combined). */
	@Builder.Default
	private List<String> orRequiredGoalIds = new ArrayList<>();

	/** True when this goal was created by the find-or-create requirement
	 *  flow as a seed (user didn't manually add it). Default false. */
	@Builder.Default
	private boolean autoSeeded = false;

	/** True when the user marks this goal as optional. Optional goals are
	 *  still tracked but visually de-emphasized (e.g. reduced opacity).
	 *  Default false. */
	@Builder.Default
	private boolean optional = false;

	/** True when this goal's nested prerequisite subtree is collapsed (hidden)
	 *  in the nested view. Persisted per profile. Default false. */
	@Builder.Default
	private boolean nestCollapsed = false;

	// ---- Repetition ----
	/**
	 * How often this goal resets and becomes completable again.
	 * {@link RepeatPeriod#NONE} is the ordinary one-shot goal.
	 *
	 * <p>Read through {@link #getRepeatEvery()}, never the field: goals stored
	 * before this feature existed deserialize with a null here, because Gson
	 * bypasses Lombok's {@code @Builder.Default} and leaves absent fields null.
	 */
	@Builder.Default
	private RepeatPeriod repeatEvery = RepeatPeriod.NONE;

	/**
	 * The period key this goal last reset in - see
	 * {@link com.goalplanner.util.RepeatSchedule#periodKey}. A reset is due
	 * whenever the live key differs from this. Zero means "never stamped",
	 * which the first check resolves without firing a spurious reset.
	 */
	private long lastPeriodKey;

	/**
	 * For a derived repeatable goal: how much to gain each period ("300k XP a
	 * day", "20 Graardor kills a week"). Zero means a plain manual repeat,
	 * where the goal just un-checks.
	 *
	 * <p>This is what lets an auto-tracked type repeat without a baseline
	 * field. At rollover the target is re-based to {@code currentValue + chunk}
	 * rather than the progress being reset, so the tracker keeps reporting the
	 * same cumulative counter it always has and needs no changes at all.
	 * Crucially the re-base reads only stored state, so it still works while
	 * the player is logged out.
	 */
	private int repeatChunk;

	/**
	 * The long-term goal this repeatable goal was derived from ("99
	 * Woodcutting" for a daily XP chunk), or null if it stands alone. A
	 * reference for display and cleanup only - it is deliberately NOT a
	 * requires-DAG edge, because a daily chunk does not gate its parent.
	 */
	private String derivedFromGoalId;

	// Integrations
	private String wikiUrl;
	private String inventorySetup;  // Inventory Setups loadout name

	// Metadata
	@Builder.Default
	private long createdAt = System.currentTimeMillis();
	private long completedAt;

	/**
	 * How often this goal repeats, never null. Lombok skips generating a getter
	 * when one is declared, so every read - including Lombok's own
	 * equals/hashCode/toString - routes through this null-guard. That matters
	 * because Gson leaves the field null on any goal persisted before
	 * repetition existed.
	 */
	public RepeatPeriod getRepeatEvery()
	{
		return repeatEvery == null ? RepeatPeriod.NONE : repeatEvery;
	}

	/** Whether this goal resets on a period boundary rather than completing once. */
	public boolean isRepeating()
	{
		return getRepeatEvery().isRepeating();
	}

	/**
	 * Progress to show the player. For a repeatable goal this is progress
	 * WITHIN the current period, not the lifetime counter: a daily 10k XP chunk
	 * on a 9.8M account reads "0 / 10,000", not "9,800,000 / 9,810,000", which
	 * would make the day's actual work invisible against the total.
	 *
	 * <p>Derived from the re-based target - the period started at
	 * {@code targetValue - repeatChunk} - so no extra state is stored.
	 */
	public int getDisplayCurrent()
	{
		if (repeatChunk <= 0)
		{
			return currentValue;
		}
		int periodStart = targetValue - repeatChunk;
		return Math.max(0, Math.min(repeatChunk, currentValue - periodStart));
	}

	/** The denominator to show: the chunk for a repeatable goal, else the target. */
	public int getDisplayTarget()
	{
		return repeatChunk > 0 ? repeatChunk : targetValue;
	}

	public double getProgressPercent()
	{
		if (targetValue <= 0)
		{
			return isComplete() ? 100.0 : 0.0;
		}
		return Math.max(0.0, Math.min(100.0, (currentValue * 100.0) / targetValue));
	}

	/**
	 * A goal is complete iff it has a non-zero completion timestamp.
	 * This is the canonical check used everywhere for completion state.
	 * The {@link GoalStatus#COMPLETE} value is kept in sync by setters but is no
	 * longer authoritative - completedAt is.
	 */
	public boolean isComplete()
	{
		return completedAt > 0;
	}

	/**
	 * Whether the goal's current value has reached or exceeded its target.
	 * Used by trackers to decide when to <em>set</em> the completion timestamp;
	 * separate from {@link #isComplete()} which is a state check.
	 */
	public boolean meetsTarget()
	{
		return targetValue > 0 && currentValue >= targetValue;
	}
}
