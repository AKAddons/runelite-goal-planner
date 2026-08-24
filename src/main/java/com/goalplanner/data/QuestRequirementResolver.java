package com.goalplanner.data;

import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import java.util.HashSet;
import java.util.Set;

/**
 * Resolves a {@link QuestRequirements.Reqs} entry into concrete goal
 * templates, filtering out any requirement the player already meets.
 *
 * <p>Split from {@code GoalPlannerApiImpl} so the API layer stays free
 * of {@link Client} dependencies - the API receives pre-filtered
 * templates and doesn't know or care about live player state. The
 * resolver lives next to {@link QuestRequirements} because its output
 * shape is dictated by that data.
 *
 * <p><b>Lookup injection.</b> The core {@link #resolve(Quest, ToIntFunction,
 * Function)} overload takes functional lookups so tests can fake
 * player state without mocking {@link Quest#getState(Client)} (which
 * is a final method reading a varp - painful to stub without
 * mockito-inline). Production code uses the {@link #resolve(Quest,
 * Client)} convenience overload which builds the lambdas from a live
 * {@link Client}.
 *
 * <p><b>Skipping rules.</b>
 * <ul>
 *   <li><b>Skill req</b>: skipped if {@code skillLevelLookup} returns
 *       a value &gt;= the required level. The "real" (unboosted) level
 *       is used because skill goals target base XP, not boosted XP.</li>
 *   <li><b>Quest prereq</b>: skipped if {@code questStateLookup}
 *       returns {@link QuestState#FINISHED}. Any {@link Exception}
 *       thrown during lookup is treated as "not finished" (defensive).</li>
 *   <li><b>Quest points / combat level / kudos</b>: resolved to
 *       {@code GoalType.ACCOUNT} goal templates.</li>
 * </ul>
 */
public final class QuestRequirementResolver
{
	/** Sprite id for the blue quest book icon. Mirrors
	 *  {@code GoalPlannerApiImpl.QUEST_SPRITE_ID} - kept as a local
	 *  constant to avoid a dependency on the api package. */
	private static final int QUEST_SPRITE_ID = 899;


	/** Output of {@link #resolve}. */
	public static final class Resolved
	{
		/** Goal templates to feed into {@code findOrCreateRequirement}. */
		public final List<Goal> templates;
		/** Count of skill reqs skipped because the player already meets the level. */
		public final int skippedSkills;
		/** Count of quest prereqs skipped because the player already finished them. */
		public final int skippedQuests;
		/** Quest-point requirement from the data table (0 = none). Not turned
		 *  into a template - stubbed until QP goals ship. */
		public final int stubbedQuestPoints;
		/** Combat-level requirement from the data table (0 = none). Not turned
		 *  into a template - stubbed until combat-level goals ship. */
		public final int stubbedCombatLevel;

		public Resolved(List<Goal> templates, int skippedSkills, int skippedQuests,
			int stubbedQuestPoints, int stubbedCombatLevel)
		{
			this.templates = templates;
			this.skippedSkills = skippedSkills;
			this.skippedQuests = skippedQuests;
			this.stubbedQuestPoints = stubbedQuestPoints;
			this.stubbedCombatLevel = stubbedCombatLevel;
		}

		/** True iff there's nothing for the caller to do - no templates AND
		 *  no QP / combat-level stub. The secondary menu entry uses this
		 *  to decide whether to render. */
		public boolean isEmpty()
		{
			return templates.isEmpty() && stubbedQuestPoints == 0 && stubbedCombatLevel == 0;
		}
	}

	/**
	 * Production overload: resolve against a live RuneLite {@link Client}.
	 */
	public static Resolved resolve(Quest quest, Client client)
	{
		return resolve(
			quest,
			client::getRealSkillLevel,
			q ->
			{
				try
				{
					return q.getState(client);
				}
				catch (Exception e)
				{
					return null;
				}
			},
			() ->
			{
				net.runelite.api.Player p = client.getLocalPlayer();
				return p != null ? p.getCombatLevel() : 0;
			},
			// Live current values for the hard account-metric requirements, so
			// "Incomplete only" doesn't seed a QP/combat/Kudos goal the player
			// already meets (it would auto-complete on the next tick - the bug
			// this closes). currentValue covers every metric generically.
			metric -> metric.currentValue(client));
	}

	/**
	 * Backwards-compatible 3-arg overload for tests that don't care
	 * about the recommended-combat-level filter. Defaults the combat
	 * level to 3 (fresh account) - matches the semantic "no boosts,
	 * lowest plausible value". New tests that want to exercise the
	 * recommended-combat pre-filter should use the 4-arg overload.
	 */
	public static Resolved resolve(
		Quest quest,
		ToIntFunction<Skill> skillLevelLookup,
		Function<Quest, QuestState> questStateLookup)
	{
		return resolve(quest, skillLevelLookup, questStateLookup, () -> 3);
	}

	/**
	 * Core overload: resolve using injected lookups. Tests use this
	 * directly with fake lookups.
	 *
	 * @param quest             quest to look up in {@link QuestRequirements}
	 * @param skillLevelLookup  maps a {@link Skill} to the player's current
	 *                          unboosted level
	 * @param questStateLookup  maps a prereq {@link Quest} to the player's
	 *                          current {@link QuestState}; may return null
	 * @param combatLevelLookup supplies the player's current combat level;
	 *                          used to pre-filter recommended-combat-level
	 *                          templates so already-met recommendations
	 *                          don't seed as pre-completed cards
	 */
	public static Resolved resolve(
		Quest quest,
		ToIntFunction<Skill> skillLevelLookup,
		Function<Quest, QuestState> questStateLookup,
		IntSupplier combatLevelLookup)
	{
		// Default account lookup = "nothing met" (0), so the floor-based "All"
		// path and existing tests emit every account requirement. The live
		// (client) path supplies real current values to pre-filter met ones.
		return resolve(quest, skillLevelLookup, questStateLookup, combatLevelLookup,
			metric -> 0);
	}

	/**
	 * Core overload with an account-metric current-value lookup, used to
	 * pre-filter hard QP / combat / Kudos requirements the player already
	 * meets (mirrors the skill/quest pre-filtering). {@code accountValueLookup}
	 * returns the player's current value for a metric, or 0 to treat it as
	 * unmet (emit the requirement).
	 */
	public static Resolved resolve(
		Quest quest,
		ToIntFunction<Skill> skillLevelLookup,
		Function<Quest, QuestState> questStateLookup,
		IntSupplier combatLevelLookup,
		ToIntFunction<com.goalplanner.model.AccountMetric> accountValueLookup)
	{
		QuestRequirements.Reqs reqs = QuestRequirements.lookup(quest);
		if (reqs == null)
		{
			return new Resolved(List.of(), 0, 0, 0, 0);
		}

		List<Goal> templates = new ArrayList<>();
		int skippedSkills = 0;
		int skippedQuests = 0;

		for (QuestRequirements.SkillReq req : reqs.skills)
		{
			int currentLevel = skillLevelLookup.applyAsInt(req.skill);
			if (currentLevel >= req.level)
			{
				skippedSkills++;
				continue;
			}
			int targetXp = Experience.getXpForLevel(req.level);
			templates.add(Goal.builder()
				.type(GoalType.SKILL)
				.name(req.skill.getName() + " - Level " + req.level)
				.skillName(req.skill.name())
				.targetValue(targetXp)
				.build());
		}

		for (Quest prereq : reqs.prereqQuests)
		{
			QuestState state;
			try
			{
				state = questStateLookup.apply(prereq);
			}
			catch (Exception e)
			{
				state = null;
			}
			if (state == QuestState.FINISHED)
			{
				skippedQuests++;
				continue;
			}
			// Mirrors GoalPlannerApiImpl.addQuestGoal: set the blue quest
			// book sprite so the card shows the proper icon instead of
			// the GoalType default color dot.
			templates.add(Goal.builder()
				.type(GoalType.QUEST)
				.name(prereq.getName())
				.description("Quest")
				.questName(prereq.name())
				.targetValue(1)
				.spriteId(QUEST_SPRITE_ID)
				.build());
		}

		// Account-wide requirements: QP, combat level, and Kudos as real ACCOUNT
		// goal templates. Pre-filtered against the player's current value (like
		// skills/quests above) so "Incomplete only" never seeds an already-met
		// account requirement that would auto-complete on the next tracker tick.
		// The "All" / test paths pass a 0-lookup, so nothing is filtered there.
		if (reqs.questPoints > 0
			&& accountValueLookup.applyAsInt(com.goalplanner.model.AccountMetric.QUEST_POINTS) < reqs.questPoints)
		{
			templates.add(Goal.builder()
				.type(GoalType.ACCOUNT)
				.name(reqs.questPoints + " Quest Points")
				.accountMetric(com.goalplanner.model.AccountMetric.QUEST_POINTS.name())
				.targetValue(reqs.questPoints)
				.build());
		}
		// Hard combat gate. Filtered via the ACCOUNT lookup (not combatLevelLookup),
		// so the floor/"All" path - which passes a 0-lookup - always emits it
		// (a hard gate stays visible), while the live "Incomplete only" path,
		// which supplies the real combat level, skips it once met.
		if (reqs.combatLevel > 0
			&& accountValueLookup.applyAsInt(com.goalplanner.model.AccountMetric.COMBAT_LEVEL) < reqs.combatLevel)
		{
			templates.add(Goal.builder()
				.type(GoalType.ACCOUNT)
				.name(reqs.combatLevel + " Combat Level")
				.accountMetric(com.goalplanner.model.AccountMetric.COMBAT_LEVEL.name())
				.targetValue(reqs.combatLevel)
				.build());
		}
		if (reqs.kudos > 0
			&& accountValueLookup.applyAsInt(com.goalplanner.model.AccountMetric.KUDOS) < reqs.kudos)
		{
			templates.add(Goal.builder()
				.type(GoalType.ACCOUNT)
				.name(reqs.kudos + " Museum Kudos")
				.accountMetric(com.goalplanner.model.AccountMetric.KUDOS.name())
				.targetValue(reqs.kudos)
				.build());
		}

		// Recommended combat level (wiki suggestion) - seeded as optional.
		// Only added when there's no hard combat level requirement (which
		// takes precedence and is already added above as non-optional),
		// AND the player's current combat level is below the recommendation.
		// Pre-filtering here (rather than relying on isComplete after
		// creation) prevents the "already-met recommendation ships as a
		// pre-completed card" bug - a freshly-created ACCOUNT goal has
		// currentValue=0 so the post-creation isComplete check misses.
		if (reqs.combatLevel == 0)
		{
			int recommended = QuestRequirements.recommendedCombatLevel(quest);
			if (recommended > 0 && combatLevelLookup.getAsInt() < recommended)
			{
				templates.add(Goal.builder()
					.type(GoalType.ACCOUNT)
					.name(recommended + " Combat Level (recommended)")
					.accountMetric(com.goalplanner.model.AccountMetric.COMBAT_LEVEL.name())
					.targetValue(recommended)
					.optional(true)
					.build());
			}
		}

		// Recommended skills (wiki suggestions) - seeded as optional.
		// Only added for skills not already in the hard requirements.
		Set<String> hardSkillNames = new HashSet<>();
		for (QuestRequirements.SkillReq req : reqs.skills)
		{
			hardSkillNames.add(req.skill.name());
		}
		for (QuestRequirements.SkillReq rec : QuestRequirements.recommendedSkills(quest))
		{
			if (hardSkillNames.contains(rec.skill.name())) continue;
			int currentLevel = skillLevelLookup.applyAsInt(rec.skill);
			if (currentLevel >= rec.level) continue;
			int targetXp = Experience.getXpForLevel(rec.level);
			templates.add(Goal.builder()
				.type(GoalType.SKILL)
				.name(rec.skill.getName() + " - Level " + rec.level + " (recommended)")
				.skillName(rec.skill.name())
				.targetValue(targetXp)
				.optional(true)
				.build());
		}

		return new Resolved(templates, skippedSkills, skippedQuests, reqs.questPoints, reqs.combatLevel);
	}

	private QuestRequirementResolver() {}
}
