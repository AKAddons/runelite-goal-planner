package com.goalplanner.api;

import java.util.List;
import net.runelite.api.Quest;
import net.runelite.api.Skill;

/**
 * Public API for the Goal Planner plugin.
 *
 * Consumer plugins integrate by:
 * <ol>
 *   <li>Annotating their plugin class with
 *       {@code @PluginDependency(com.goalplanner.GoalPlannerPlugin.class)}</li>
 *   <li>Injecting this interface via {@code @Inject GoalPlannerApi goalTrackerApi}</li>
 *   <li>Calling {@code add*Goal(...)} methods to create goals programmatically</li>
 * </ol>
 *
 * <p>All {@code add*Goal} methods return the created goal's id (a UUID string)
 * on success, or {@code null} if the inputs failed validation. If a duplicate
 * goal already exists (same skill, item, quest, etc.) the existing goal's id is
 * returned and no new goal is created. Calls are idempotent and safe to retry.
 *
 * <p>This interface is intentionally minimal and doesn't expose the internal
 * {@code Goal} model, so future model changes don't break consumers.
 */
public interface GoalPlannerApi
{
	/**
	 * Add a skill goal targeting a raw XP amount. Canonical form - all skill
	 * goals are stored internally as XP targets.
	 *
	 * @param skill    the skill to track (must not be null)
	 * @param targetXp the target XP (1 .. 200,000,000)
	 * @return the created or existing goal's id, or {@code null} if validation failed
	 */
	String addSkillGoal(Skill skill, int targetXp);

	/**
	 * Convenience: add a skill goal targeting a specific level. Resolves to the
	 * XP threshold via {@link net.runelite.api.Experience#getXpForLevel(int)} and
	 * delegates to {@link #addSkillGoal(Skill, int)}.
	 *
	 * @param skill the skill to track (must not be null)
	 * @param level the target level (1 .. 126; 1-99 normal, 100-126 virtual)
	 * @return the created or existing goal's id, or {@code null} if validation failed
	 */
	String addSkillGoalForLevel(Skill skill, int level);

	/**
	 * Add an item goal by item id and target quantity. The item id corresponds
	 * to OSRS item ids (see {@link net.runelite.api.gameval.ItemID}).
	 *
	 * @param itemId         the item id (must be &gt; 0)
	 * @param targetQuantity the target count (must be &gt; 0)
	 * @return the created or existing goal's id, or {@code null} if validation failed
	 */
	String addItemGoal(int itemId, int targetQuantity);

	/**
	 * Add a quest goal. Auto-tracks completion via {@code Quest.getState(client)}.
	 *
	 * <p>Unmet skill and quest prerequisites (as defined by
	 * {@code QuestRequirements}) are automatically resolved against live
	 * player state and seeded as linked sub-goals under a single undo
	 * entry. Quests with no requirement data, or with all requirements
	 * already met, fall through to a plain single-goal insert.
	 *
	 * @param quest the quest (must not be null)
	 * @return the created or existing goal's id, or {@code null} if validation failed
	 */
	String addQuestGoal(Quest quest);

	/**
	 * Add an achievement diary goal by area display name and tier. Auto-tracks
	 * via the per-area-per-tier completion varbits.
	 *
	 * <p>Unmet skill, quest, and unlock prerequisites (as defined by
	 * {@code DiaryRequirements}) are automatically resolved against live
	 * player state and seeded as linked sub-goals under a single undo
	 * entry. Tiers with no requirement data, or with all requirements
	 * already met, fall through to a plain single-goal insert.
	 *
	 * @param areaDisplayName the area name as displayed in-game (e.g. "Ardougne",
	 *                        "Falador", "Karamja", "Kourend &amp; Kebos", etc.)
	 * @param tier            the tier
	 * @return the created or existing goal's id, or {@code null} if validation failed
	 */
	String addDiaryGoal(String areaDisplayName, DiaryTier tier);

	/**
	 * Add a combat achievement goal by wiki/in-game task id. Auto-tracks via the
	 * bit-packed CA_TASK_COMPLETED varplayers.
	 *
	 * @param caTaskId the wiki/in-game task id (0 .. 639)
	 * @return the created or existing goal's id, or {@code null} if validation failed
	 */
	String addCombatAchievementGoal(int caTaskId);

	/**
	 * Add a boss kill count goal. Auto-tracks via VarPlayerID.
	 *
	 * @param bossName   display name matching BossKillData
	 * @param targetKills target kill count
	 */
	String addBossGoal(String bossName, int targetKills);

	/** Achievement diary tier; mirrors the internal AchievementDiaryData.Tier. */
	enum DiaryTier { EASY, MEDIUM, HARD, ELITE }

	// ===== Read API =====

	/**
	 * Return all goals as DTOs in canonical render order (sections sorted by their
	 * {@code order}, goals within each section sorted by priority). Includes both
	 * incomplete and completed goals; consumers can filter by {@code completedAt > 0}.
	 *
	 * <p>The returned list is a snapshot - mutating it has no effect on the plugin's
	 * internal state. Call again after mutations to get a fresh view.
	 */
	List<GoalView> queryAllGoals();

	/**
	 * Return all sections (built-in and user-defined) as DTOs, sorted by their
	 * {@code order} field.
	 */
	List<SectionView> queryAllSections();

	// ===== Mutation API =====

	/**
	 * Remove a single goal by id. Idempotent - returns false if no such goal exists.
	 */
	boolean removeGoal(String goalId);

	/**
	 * Add a custom-category tag to a goal. For non-CUSTOM goals, the tag is forced
	 * into the OTHER category regardless of inputs (the default tags from creation
	 * are protected and not modifiable). Returns false if the goal doesn't exist or
	 * the label is empty.
	 */
	boolean addTag(String goalId, String label);

	/**
	 * Remove a user-added tag from a goal. Default tags (auto-generated at goal
	 * creation) are protected and cannot be removed via this method. Returns true
	 * if a tag was removed, false otherwise.
	 */
	boolean removeTag(String goalId, String label);

	/**
	 * Change the target value of a SKILL or ITEM_GRIND goal. CA/quest/diary goals
	 * are binary and have immutable targets. Returns false on type mismatch or
	 * out-of-range value.
	 */
	boolean changeTarget(String goalId, int newTarget);

	/**
	 * Create an account-wide goal. Returns the new goal's id, or the
	 * existing id if a goal with the same metric and target already exists.
	 * Returns null if validation failed (unknown metric, non-positive target).
	 *
	 * @param metricName an {@link com.goalplanner.model.AccountMetric} enum name
	 * @param target     the target value (e.g. 200 for 200 Quest Points)
	 */
	String addAccountGoal(String metricName, int target);

	/**
	 * Create a custom goal. Returns the new goal's id, or null if validation failed
	 * (empty name).
	 */
	String addCustomGoal(String name, String description);

	/**
	 * Edit a CUSTOM goal's name and/or description. Either may be null to leave
	 * unchanged. Returns false if the goal isn't CUSTOM or doesn't exist.
	 */
	boolean editCustomGoal(String goalId, String newName, String newDescription);

	/**
	 * Mark a CUSTOM goal complete. Sets completedAt to now. Returns false if the
	 * goal isn't CUSTOM (other types are auto-tracked). Idempotent - calling on an
	 * already-complete goal updates the timestamp.
	 */
	boolean markGoalComplete(String goalId);

	/**
	 * Mark a CUSTOM goal incomplete. Clears completedAt. Returns false if the goal
	 * isn't CUSTOM.
	 */
	boolean markGoalIncomplete(String goalId);

	/**
	 * Set how often a goal repeats. A repeating goal moves into the built-in
	 * Repeatable section, stays there when checked off, and un-completes when
	 * its period rolls over; setting {@link com.goalplanner.model.RepeatPeriod#NONE}
	 * returns it to the section it came from.
	 *
	 * <p>Currently limited to CUSTOM goals - auto-tracked types measure absolute
	 * progress, so repeating them needs per-period baselines that do not exist yet.
	 *
	 * @return true if the goal's repeat period changed
	 */
	boolean setGoalRepeat(String goalId, com.goalplanner.model.RepeatPeriod period);

	/**
	 * Create a repeatable repeatable goal from a long-term one - a daily XP
	 * chunk off a skill goal, or a kill-count chunk off an item goal at the
	 * activity that drops it.
	 *
	 * @param activityName boss name for an item-derived goal; ignored for SKILL parents
	 * @return the new goal's id, or null if it could not be built
	 */
	String createDerivedRepeatGoal(String parentGoalId, com.goalplanner.model.RepeatPeriod period,
		int chunk, String activityName);

	/**
	 * Change how much a derived repeatable goal asks for each period. Keeps the
	 * current period's start fixed and moves only its end, so progress already
	 * made this period still counts.
	 *
	 * @return true if the amount changed
	 */
	boolean setGoalRepeatChunk(String goalId, int newChunk);

	/**
	 * Restore a goal's tags to its default snapshot from creation. Returns false
	 * if the goal has no defaults or doesn't exist.
	 */
	boolean restoreDefaultTags(String goalId);
}
