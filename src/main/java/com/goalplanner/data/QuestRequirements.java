package com.goalplanner.data;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Quest;
import net.runelite.api.Skill;

/**
 * Pre-defined quest → requirement associations.
 *
 * <p>Keyed on the RuneLite {@link Quest} enum - the same identity the
 * plugin uses in {@code Goal.questName} - so no string plumbing is
 * needed at the consumer. Consumed by the "Add Goal with Requirements"
 * flow in {@code GoalPlannerApiImpl.addQuestGoalWithPrereqs}, which
 * feeds each entry through {@code findOrCreateRequirement} inside a
 * compound command.
 *
 * <p><b>Coverage.</b> This table covers all 211 quests in the
 * RuneLite {@link Quest} enum. Requirements are wiki-sourced.
 *
 * <p><b>Account-wide requirements.</b> Quest points, combat level,
 * and museum kudos are recorded as numeric fields on {@link Reqs} and
 * resolved into {@code GoalType.ACCOUNT} goal templates by the
 * {@link QuestRequirementResolver}.
 */
@Slf4j
public final class QuestRequirements
{
	/** {@link Quest#valueOf} but null instead of throwing on a constant this
	 *  RuneLite API version doesn't have (a forward-declared new quest). */
	private static Quest questOrNull(String name)
	{
		try
		{
			return Quest.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	/** A single skill requirement (skill + minimum level). */
	public static final class SkillReq
	{
		public final Skill skill;
		public final int level;

		public SkillReq(Skill skill, int level)
		{
			this.skill = skill;
			this.level = level;
		}
	}

	/** The full requirement set for a quest. Any field may be empty. */
	public static final class Reqs
	{
		public final List<SkillReq> skills;
		public final List<Quest> prereqQuests;
		/** Minimum total quest points required. 0 = none. */
		public final int questPoints;
		/** Minimum combat level required. 0 = none. */
		public final int combatLevel;
		/** Minimum museum kudos required. 0 = none. */
		public final int kudos;
		public Reqs(List<SkillReq> skills, List<Quest> prereqQuests, int questPoints, int combatLevel)
		{
			this(skills, prereqQuests, questPoints, combatLevel, 0);
		}

		public Reqs(List<SkillReq> skills, List<Quest> prereqQuests, int questPoints, int combatLevel, int kudos)
		{
			this.skills = Collections.unmodifiableList(skills);
			this.prereqQuests = Collections.unmodifiableList(prereqQuests);
			this.questPoints = questPoints;
			this.combatLevel = combatLevel;
			this.kudos = kudos;
		}
	}

	private static final Map<Quest, Reqs> TABLE = new EnumMap<>(Quest.class);

	/** Miniquests - 0 QP, shorter, different description in the goal card. */
	private static final java.util.Set<Quest> MINIQUESTS = java.util.EnumSet.noneOf(Quest.class);

	/** The complete set of F2P quests, used to auto-tag quest goals. */
	private static final java.util.Set<Quest> F2P_QUESTS = java.util.EnumSet.noneOf(Quest.class);

	// ============================================================
	// XP reward tags: skills that a quest rewards XP in (fixed
	// rewards only - choice lamps are excluded).
	// ============================================================
	private static final Map<Quest, List<Skill>> XP_REWARDS = new EnumMap<>(Quest.class);

	// ============================================================
	// Quest point rewards
	// ============================================================
	private static final Map<Quest, Integer> QP_REWARDS = new EnumMap<>(Quest.class);

	/** Quests that reward an XP lamp (choice-based, not fixed skill). */
	private static final java.util.Set<Quest> LAMP_REWARD_QUESTS = java.util.EnumSet.noneOf(Quest.class);

	// ============================================================
	// Recommended skills (wiki-sourced suggestions, not hard
	// requirements). Seeded as optional goals.
	// ============================================================
	private static final Map<Quest, List<SkillReq>> RECOMMENDED_SKILLS = new EnumMap<>(Quest.class);

	// ============================================================
	// Recommended combat levels (wiki-sourced suggestions, not hard
	// requirements). Seeded as optional goals.
	// ============================================================
	private static final Map<Quest, Integer> RECOMMENDED_COMBAT = new EnumMap<>(Quest.class);

	// ============================================================
	// Display names - short labels for quests whose RuneLite enum
	// name is too long for tags or UI labels. Falls back to
	// Quest.getName() when not overridden.
	// ============================================================
	private static final Map<Quest, String> DISPLAY_NAMES = new EnumMap<>(Quest.class);

	/**
	 * Short display name for a quest, suitable for tags and UI labels.
	 * Falls back to {@code quest.getName()} if no override is defined.
	 */
	public static String displayName(Quest quest)
	{
		if (quest == null) return null;
		return DISPLAY_NAMES.getOrDefault(quest, quest.getName());
	}

	private static void put(Quest quest, List<SkillReq> skills, List<Quest> prereqQuests,
		int questPoints, int combatLevel)
	{
		TABLE.put(quest, new Reqs(skills, prereqQuests, questPoints, combatLevel));
	}

	/**
	 * Look up the requirements for a quest.
	 *
	 * @return the requirements, or {@code null} if this quest is not in
	 *         the data table. A null return means "no data", not "no
	 *         requirements" - an empty {@link Reqs} is used to express
	 *         the latter (see {@link Quest#COOKS_ASSISTANT}).
	 */
	public static Reqs lookup(Quest quest)
	{
		if (quest == null) return null;
		return TABLE.get(quest);
	}

	/**
	 * Skills that a quest rewards fixed XP in. Returns an empty list
	 * for quests with no fixed XP rewards or quests not in the table.
	 * Choice lamps are excluded.
	 */
	public static List<Skill> xpRewards(Quest quest)
	{
		if (quest == null) return Collections.emptyList();
		return XP_REWARDS.getOrDefault(quest, Collections.emptyList());
	}

	/**
	 * Recommended skills for a quest (wiki suggestions, not hard requirements).
	 * Seeded as optional goals. Returns an empty list if none.
	 */
	public static List<SkillReq> recommendedSkills(Quest quest)
	{
		if (quest == null) return Collections.emptyList();
		return RECOMMENDED_SKILLS.getOrDefault(quest, Collections.emptyList());
	}

	/**
	 * Recommended combat level for a quest (wiki suggestion), or 0 if none.
	 * Not a hard requirement - seeded as an optional goal.
	 */
	public static int recommendedCombatLevel(Quest quest)
	{
		if (quest == null) return 0;
		return RECOMMENDED_COMBAT.getOrDefault(quest, 0);
	}

	/**
	 * Quest point reward for a quest, or 0 if not in the table.
	 */
	public static int questPointReward(Quest quest)
	{
		if (quest == null) return 0;
		return QP_REWARDS.getOrDefault(quest, 0);
	}

	/** True iff the quest rewards an XP lamp (choice-based). */
	public static boolean rewardsLamp(Quest quest)
	{
		return quest != null && LAMP_REWARD_QUESTS.contains(quest);
	}

	/** True iff the quest is free-to-play. */
	/** True iff the quest is a miniquest (not a full quest). */
	public static boolean isMiniquest(Quest quest)
	{
		return quest != null && MINIQUESTS.contains(quest);
	}

	public static boolean isF2P(Quest quest)
	{
		return quest != null && F2P_QUESTS.contains(quest);
	}

	/** True iff the quest has a non-empty requirements entry. */
	public static boolean hasRequirements(Quest quest)
	{
		Reqs r = lookup(quest);
		if (r == null) return false;
		return !r.skills.isEmpty() || !r.prereqQuests.isEmpty()
			|| r.questPoints > 0 || r.combatLevel > 0 || r.kudos > 0
			|| recommendedCombatLevel(quest) > 0
			|| !recommendedSkills(quest).isEmpty();
	}

	static
	{
		try (InputStream in = QuestRequirements.class.getResourceAsStream("quest-requirements.tsv"))
		{
			if (in == null)
			{
				throw new IllegalStateException("missing resource: quest-requirements.tsv");
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					if (line.isEmpty())
					{
						continue;
					}
					// quest \t skills \t prereqs \t questPoints \t combatLevel
					//   \t xpRewards \t qpReward \t recSkills \t recCombat \t displayOverride \t flags(M=mini,F=f2p,L=lamp)
					String[] f = line.split("\t", -1);
					// A row may name a quest the current RuneLite API doesn't ship
					// yet (a just-released quest forward-declared here before the
					// dependency bump). Skip it rather than throwing and taking the
					// whole table down; it activates automatically once the enum lands.
					Quest quest = questOrNull(f[0]);
					if (quest == null)
					{
						log.debug("quest-requirements.tsv: skipping unknown Quest '{}' (not in this RuneLite API version)", f[0]);
						continue;
					}

					List<Quest> prereqs = new ArrayList<>();
					if (!f[2].isEmpty())
					{
						for (String pq : f[2].split(";"))
						{
							Quest p = questOrNull(pq);
							if (p != null)
							{
								prereqs.add(p);
							}
							else
							{
								log.debug("quest-requirements.tsv: {} lists unknown prerequisite '{}' - skipped", f[0], pq);
							}
						}
					}
					put(quest, parseSkillReqs(f[1]), prereqs,
						Integer.parseInt(f[3]), Integer.parseInt(f[4]));

					if (f.length > 5 && !f[5].isEmpty())
					{
						List<Skill> xp = new ArrayList<>();
						for (String s : f[5].split(";"))
						{
							xp.add(Skill.valueOf(s));
						}
						XP_REWARDS.put(quest, xp);
					}
					if (f.length > 6 && !f[6].isEmpty() && Integer.parseInt(f[6]) > 0)
					{
						QP_REWARDS.put(quest, Integer.parseInt(f[6]));
					}
					if (f.length > 7 && !f[7].isEmpty())
					{
						RECOMMENDED_SKILLS.put(quest, parseSkillReqs(f[7]));
					}
					if (f.length > 8 && !f[8].isEmpty() && Integer.parseInt(f[8]) > 0)
					{
						RECOMMENDED_COMBAT.put(quest, Integer.parseInt(f[8]));
					}
					if (f.length > 9 && !f[9].isEmpty())
					{
						DISPLAY_NAMES.put(quest, f[9]);
					}
					if (f.length > 10 && !f[10].isEmpty())
					{
						if (f[10].indexOf('M') >= 0) MINIQUESTS.add(quest);
						if (f[10].indexOf('F') >= 0) F2P_QUESTS.add(quest);
						if (f[10].indexOf('L') >= 0) LAMP_REWARD_QUESTS.add(quest);
					}
				}
			}
		}
		catch (java.io.IOException e)
		{
			throw new java.io.UncheckedIOException("failed to load quest-requirements.tsv", e);
		}
	}

	private static List<SkillReq> parseSkillReqs(String field)
	{
		List<SkillReq> skills = new ArrayList<>();
		if (!field.isEmpty())
		{
			for (String sk : field.split(";"))
			{
				int c = sk.indexOf(':');
				skills.add(new SkillReq(Skill.valueOf(sk.substring(0, c)),
					Integer.parseInt(sk.substring(c + 1))));
			}
		}
		return skills;
	}


	private QuestRequirements() {}
}
