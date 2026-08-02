package com.goalplanner.api;

import com.goalplanner.data.AchievementDiaryData;
import com.goalplanner.data.CombatAchievementData;
import com.goalplanner.data.ItemSourceData;
import com.goalplanner.data.WikiCaRepository;
import com.goalplanner.model.AccountMetric;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.model.ItemTag;
import com.goalplanner.model.Tag;
import com.goalplanner.model.TagCategory;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Experience;
import net.runelite.api.Quest;
import net.runelite.api.Skill;

/**
 * Encapsulates all goal-creation methods extracted from {@link GoalPlannerApiImpl}.
 * Package-private - only {@link GoalPlannerApiImpl} instantiates and delegates to this.
 */
@Slf4j
class GoalCreationService
{
	/** Max level supported (1-99 normal, 100-126 virtual). */
	private static final int MAX_LEVEL = 126;

	/** Max experience per skill in OSRS. */
	private static final int MAX_XP = 200_000_000;

	private final GoalPlannerApiImpl api;

	GoalCreationService(GoalPlannerApiImpl api)
	{
		this.api = api;
	}

	String addSkillGoal(Skill skill, int targetXp)
	{
		log.debug("API.public addSkillGoal(skill={}, targetXp={})", skill, targetXp);
		if (skill == null)
		{
			log.warn("addSkillGoal: skill is null");
			return null;
		}
		if (targetXp < 1 || targetXp > MAX_XP)
		{
			log.warn("addSkillGoal: targetXp {} out of range [1, {}]", targetXp, MAX_XP);
			return null;
		}

		// Duplicate guard: same skill + target XP within the default namespace.
		Goal existing = existingInDefault(Goal.builder()
			.type(GoalType.SKILL).skillName(skill.name()).targetValue(targetXp).build());
		if (existing != null)
		{
			log.info("addSkillGoal: duplicate of existing goal {} ({} → {} XP)",
				existing.getId(), skill.getName(), targetXp);
			return existing.getId();
		}

		int targetLevel = Experience.getLevelForXp(targetXp);
		Goal goal = Goal.builder()
			.type(GoalType.SKILL)
			.name(skill.getName() + " - Level " + targetLevel)
			.skillName(skill.name())
			.targetValue(targetXp)
			.build();

		final String goalId = goal.getId();
		final String displayName = goal.getName();
		// Wrap create + auto-link in a compound so one undo
		// reverses the whole gesture (the new goal AND all the chain
		// edges that got added to existing same-skill goals).
		//
		// The old GoalReorderingService.findInsertionIndex + goalStore.reorder
		// call that used to run here is gone - autoLinkSkillOrItemChain creates
		// explicit requirement edges between same-skill goals, and the
		// per-section topological sort in queryGoalsTopologicallySorted handles
		// the visual ordering based on those edges. No more implicit
		// priority-based chain sort.
		api.beginCompound("Add goal: " + displayName);
		try
		{
			executeAddGoal(goal, goalId, "Add goal: " + displayName);
			autoLinkSkillOrItemChain(goal);
		}
		finally
		{
			api.endCompound();
		}
		log.info("addSkillGoal created: {} ({} → {} XP)", goalId, skill.getName(), targetXp);
		return goalId;
	}

	/**
	 * When a new SKILL or ITEM_GRIND goal is added, scan all
	 * existing goals globally for same-identity same-type goals and
	 * auto-link them based on target ordering:
	 * <ul>
	 *   <li>If an existing goal has a LOWER target → new goal requires it
	 *       (the existing one is a prerequisite in the chain)</li>
	 *   <li>If an existing goal has a HIGHER target → it requires the new
	 *       goal (the new one is a prerequisite in the chain)</li>
	 *   <li>Equal target → no edge (they're either duplicates and one will
	 *       lose, or the caller has a reason to keep both)</li>
	 * </ul>
	 *
	 * <p>Per-section: auto-link is scoped to the new goal's namespace (the
	 * default Incomplete+Completed pair, or its user section). Same-skill
	 * ladders are self-contained per section - a 50 Prayer goal in section A
	 * does not chain to a 90 Prayer goal in section B. (Each section is its
	 * own bucket.)
	 *
	 * <p>Quiet on failure: cycle detection (via addRequirement) silently
	 * skips edges that would close a cycle, which shouldn't happen in
	 * practice for same-identity numeric chains but is defended against.
	 *
	 * <p>Caller MUST be inside a beginCompound/endCompound block so the
	 * auto-linked edges collapse into the same undo entry as the goal
	 * creation.
	 */
	private void autoLinkSkillOrItemChain(Goal newGoal)
	{
		if (newGoal == null) return;
		GoalType type = newGoal.getType();
		if (type != GoalType.SKILL && type != GoalType.ITEM_GRIND && type != GoalType.ACCOUNT) return;

		String newId = newGoal.getId();
		int newTarget = newGoal.getTargetValue();
		// Per-section: only chain to same-namespace goals (each section is its
		// own bucket; the default Incomplete+Completed pair is one namespace).
		String newNs = api.goalStore.namespaceKey(newGoal.getSectionId());
		// Copy the goal list so we're iterating a stable snapshot - addRequirement
		// doesn't mutate the list but future code might, and copying is cheap.
		List<Goal> snapshot = new ArrayList<>(api.goalStore.getGoals());
		for (Goal other : snapshot)
		{
			if (other.getId().equals(newId)) continue;
			if (other.getType() != type) continue;
			if (!java.util.Objects.equals(newNs, api.goalStore.namespaceKey(other.getSectionId()))) continue;
			// Identity check per type
			if (type == GoalType.SKILL)
			{
				if (newGoal.getSkillName() == null
					|| other.getSkillName() == null
					|| !newGoal.getSkillName().equals(other.getSkillName())) continue;
			}
			else if (type == GoalType.ACCOUNT)
			{
				if (newGoal.getAccountMetric() == null
					|| other.getAccountMetric() == null
					|| !newGoal.getAccountMetric().equals(other.getAccountMetric())) continue;
			}
			else // ITEM_GRIND
			{
				if (newGoal.getItemId() != other.getItemId()) continue;
			}
			int otherTarget = other.getTargetValue();
			if (otherTarget < newTarget)
			{
				// other is a prereq of new → edge new → other
				api.addRequirement(newId, other.getId());
			}
			else if (otherTarget > newTarget)
			{
				// new is a prereq of other → edge other → new
				api.addRequirement(other.getId(), newId);
			}
		}
	}

	String addSkillGoalForLevel(Skill skill, int level)
	{
		log.debug("API.public addSkillGoalForLevel(skill={}, level={})", skill, level);
		if (skill == null)
		{
			log.warn("addSkillGoalForLevel: skill is null");
			return null;
		}
		if (level < 1 || level > MAX_LEVEL)
		{
			log.warn("addSkillGoalForLevel: level {} out of range [1, {}]", level, MAX_LEVEL);
			return null;
		}
		return addSkillGoal(skill, Experience.getXpForLevel(level));
	}

	/**
	 * Existing goal in the DEFAULT namespace (built-in Incomplete + Completed)
	 * whose identity matches the probe, or null. The standard add methods land
	 * goals in the default, so their duplicate guards scope here: a goal living
	 * in a user section does NOT block a fresh default add (per-section identity).
	 */
	private Goal existingInDefault(Goal probe)
	{
		return api.goalStore.findEquivalentInNamespace(
			api.goalStore.getIncompleteSection().getId(), probe);
	}

	String addItemGoal(int itemId, int targetQuantity)
	{
		log.debug("API.public addItemGoal(itemId={}, qty={})", itemId, targetQuantity);
		if (itemId <= 0 || targetQuantity <= 0)
		{
			log.warn("addItemGoal: invalid input itemId={} qty={}", itemId, targetQuantity);
			return null;
		}

		// Duplicate guard: same item id (any qty) in the default namespace.
		Goal itemDup = existingInDefault(Goal.builder().type(GoalType.ITEM_GRIND).itemId(itemId).build());
		if (itemDup != null)
		{
			log.info("addItemGoal: duplicate of existing goal {} (item {})", itemDup.getId(), itemId);
			return itemDup.getId();
		}

		String itemName;
		try
		{
			itemName = api.itemManager.getItemComposition(itemId).getName();
			// Strip the "(Members)" suffix that some items have in-game
			if (itemName != null && itemName.endsWith("(Members)"))
			{
				itemName = itemName.substring(0, itemName.length() - "(Members)".length()).trim();
			}
		}
		catch (Exception e)
		{
			log.warn("addItemGoal: unknown item id {}", itemId);
			return null;
		}

		// Resolve item-source ItemTag specs into Tag entity ids via findOrCreateSystemTag
		List<String> tagIds = new ArrayList<>();
		for (ItemTag spec : ItemSourceData.getTags(itemId))
		{
			Tag tag = api.goalStore.findOrCreateSystemTag(spec.getLabel(), spec.getCategory());
			if (tag != null) tagIds.add(tag.getId());
		}
		Goal goal = Goal.builder()
			.type(GoalType.ITEM_GRIND)
			.name(itemName)
			.description(targetQuantity + " total")
			.itemId(itemId)
			.targetValue(targetQuantity)
			.currentValue(-1)
			.tagIds(new ArrayList<>(tagIds))
			.defaultTagIds(new ArrayList<>(tagIds))
			.build();

		final String goalId = goal.getId();
		final String displayName = itemName;
		executeAddGoal(goal, goalId, "Add item goal: " + displayName);
		log.info("addItemGoal created: {} ({} x {})", goalId, targetQuantity, itemName);
		return goalId;
	}

	/**
	 * Raw single-goal insert: validates, duplicate-checks, builds the Goal,
	 * auto-tags. Does NOT resolve or seed prerequisites. Internal primitive
	 * used by {@link #addQuestGoal(Quest)} and the seeding BFS when
	 * inserting individual child quest goals.
	 */
	String insertQuestGoal(Quest quest)
	{
		log.debug("API.internal insertQuestGoal(quest={})", quest);
		if (quest == null)
		{
			log.warn("insertQuestGoal: quest is null");
			return null;
		}
		api.clearGoalSelection();

		// Duplicate guard: same questName in the default namespace.
		String questName = quest.name();
		Goal questDup = existingInDefault(Goal.builder().type(GoalType.QUEST).questName(questName).build());
		if (questDup != null)
		{
			log.info("insertQuestGoal: duplicate of existing goal {} ({})", questDup.getId(), questName);
			return questDup.getId();
		}

		int qpReward = com.goalplanner.data.QuestRequirements.questPointReward(quest);
		String description;
		if (qpReward > 0)
		{
			description = qpReward + " Quest Point" + (qpReward != 1 ? "s" : "");
		}
		else if (com.goalplanner.data.QuestRequirements.isMiniquest(quest))
		{
			description = "Miniquest";
		}
		else
		{
			description = "Quest";
		}

		Goal goal = Goal.builder()
			.type(GoalType.QUEST)
			.name(quest.getName())
			.description(description)
			.questName(questName)
			.targetValue(1)
			.currentValue(0)
			.spriteId(GoalPlannerApiImpl.QUEST_SPRITE_ID)
			.build();

		final String goalId = goal.getId();
		final String displayName = goal.getName();
		api.beginCompound("Add quest: " + displayName);
		try
		{
			executeAddGoal(goal, goalId, "Add quest: " + displayName);
			// Auto-tag F2P quests with a gray "F2P" pill.
			if (com.goalplanner.data.QuestRequirements.isF2P(quest))
			{
				api.addTagWithCategory(goalId, "F2P", TagCategory.OTHER.name());
				Tag f2pTag = api.goalStore.findTagByLabel("F2P", TagCategory.OTHER);
				if (f2pTag != null && f2pTag.getColorRgb() < 0)
				{
					api.goalStore.recolorTag(f2pTag.getId(), GoalPlannerApiImpl.F2P_TAG_COLOR_RGB);
				}
			}
			// Auto-tag XP reward skills as SKILLING icons.
			for (net.runelite.api.Skill rewardSkill : com.goalplanner.data.QuestRequirements.xpRewards(quest))
			{
				api.addTagWithCategory(goalId, rewardSkill.getName(), TagCategory.SKILLING.name());
			}
			// Auto-tag lamp-reward quests with a lamp icon.
			if (com.goalplanner.data.QuestRequirements.rewardsLamp(quest))
			{
				api.addTagWithCategory(goalId, "Lamp", TagCategory.OTHER.name());
				Tag lampTag = api.goalStore.findTagByLabel("Lamp", TagCategory.OTHER);
				if (lampTag != null && lampTag.getIconKey() == null)
				{
					api.goalStore.setTagIcon(lampTag.getId(), GoalPlannerApiImpl.LAMP_ICON_KEY);
				}
			}
		}
		finally
		{
			api.endCompound();
		}
		log.info("insertQuestGoal created: {} ({})", goalId, quest.getName());
		return goalId;
	}

	/**
	 * Public API entry point: add a quest goal, auto-resolving unmet
	 * skill/quest prerequisites from live player state and seeding them
	 * into the goal tree. Merged entry point that replaces the separate
	 * plain / with-prereqs pair.
	 */
	String addQuestGoal(Quest quest)
	{
		log.debug("API.public addQuestGoal(quest={})", quest);
		if (quest == null)
		{
			log.warn("addQuestGoal: quest is null");
			return null;
		}
		if (!com.goalplanner.data.QuestRequirements.hasRequirements(quest))
		{
			return insertQuestGoal(quest);
		}
		com.goalplanner.data.QuestRequirementResolver.Resolved resolved =
			api.resolveQuestRequirements(quest);
		if (resolved == null || resolved.isEmpty())
		{
			return insertQuestGoal(quest);
		}
		if (resolved.skippedSkills > 0 || resolved.skippedQuests > 0)
		{
			log.info("addQuestGoal({}): skipped {} already-met skill reqs, {} already-finished quest prereqs",
				quest.getName(), resolved.skippedSkills, resolved.skippedQuests);
		}
		return addQuestGoalWithPrereqs(quest, resolved.templates);
	}

	String addQuestGoalWithPrereqs(Quest quest, java.util.List<Goal> prereqTemplates)
	{
		log.debug("API.internal addQuestGoalWithPrereqs(quest={}, prereqs={})",
			quest, prereqTemplates == null ? 0 : prereqTemplates.size());
		if (quest == null)
		{
			log.warn("addQuestGoalWithPrereqs: quest is null");
			return null;
		}
		api.clearGoalSelection();
		if (prereqTemplates == null)
		{
			prereqTemplates = java.util.Collections.emptyList();
		}

		// Degenerate case - no templates means no compound is needed, just
		// delegate to the primitive single-goal path.
		if (prereqTemplates.isEmpty())
		{
			return insertQuestGoal(quest);
		}

		// Wrap the whole gesture in a compound so one undo reverses the
		// quest goal, every seeded prereq (direct + transitive), every
		// requirement edge, and every tag attachment.
		api.beginCompound("Add quest with requirements: " + quest.getName());
		try
		{
			String questGoalId = insertQuestGoal(quest);
			if (questGoalId == null)
			{
				log.warn("addQuestGoalWithPrereqs: insertQuestGoal returned null for {}", quest);
				return null;
			}

			// Seed the prereq tree under the quest goal, assign priorities,
			// promote leaf goals, and select the gesture - shared with the
			// "Add requirements to this section" action.
			seedPrereqsAndPrioritize(questGoalId, quest, prereqTemplates, /*allMode=*/false);

			return questGoalId;
		}
		finally
		{
			api.endCompound();
		}
	}

	/**
	 * Shared post-root seeding pass: BFS-seed {@code rootQuest}'s prereq templates
	 * beneath {@code rootGoalId} (into its section - see {@link #seedPrereqsInto}),
	 * assign priorities for a clean topo render (leaf "do-first" quests on top,
	 * then reversed creation order), promote leaf goals, and select the whole
	 * gesture. Caller MUST already be inside a beginCompound/endCompound block and
	 * {@code rootGoalId} must already exist.
	 *
	 * @return every goal id touched by the gesture (root first)
	 */
	/**
	 * Set for the duration of ONE {@link #seedPrereqsAndPrioritize} gesture: when
	 * true (the "Add requirements → All" action), already-completed prereqs are
	 * kept and linked (so the guide shows the full tree as cards), and child
	 * quests are re-resolved with floor lookups so the FULL transitive tree seeds.
	 * Default false → quest-add and "Incomplete only" skip already-completed
	 * prereqs. Single-threaded seeding, so a plain field is safe.
	 */
	private boolean seedKeepCompleted = false;

	private java.util.List<String> seedPrereqsAndPrioritize(
		String rootGoalId, Quest rootQuest, java.util.List<Goal> prereqTemplates, boolean allMode)
	{
		java.util.Set<Quest> visited = new java.util.HashSet<>();
		if (rootQuest != null)
		{
			visited.add(rootQuest);
		}

		// Snapshot goal IDs present before the BFS so the hybrid skill helper only
		// reuses pre-existing goals, not ones created during this same gesture.
		java.util.Set<String> preExistingGoalIds = new java.util.HashSet<>();
		for (Goal g : api.goalStore.getGoals())
		{
			preExistingGoalIds.add(g.getId());
		}

		java.util.List<String> gestureGoalIds = new java.util.ArrayList<>();
		gestureGoalIds.add(rootGoalId);

		this.seedKeepCompleted = allMode;
		try
		{
			seedPrereqsInto(rootGoalId, rootQuest, prereqTemplates, visited, preExistingGoalIds, gestureGoalIds);
		}
		finally
		{
			this.seedKeepCompleted = false;
		}

		// Assign priorities so the topo sort renders correctly:
		// 1. Zero-dependency QUEST goals at the very top (leaf quests - do first)
		// 2. Everything else in reversed creation order (deepest leaves near top,
		//    root at bottom)
		java.util.Set<String> gestureSet = new java.util.HashSet<>(gestureGoalIds);
		java.util.List<String> zeroDegQuests = new java.util.ArrayList<>();
		java.util.List<String> others = new java.util.ArrayList<>();
		for (String id : gestureGoalIds)
		{
			Goal g = api.findGoal(id);
			if (g != null && g.getType() == GoalType.QUEST && g.getQuestName() != null)
			{
				boolean isKnownLeaf = false;
				try
				{
					Quest q = Quest.valueOf(g.getQuestName());
					com.goalplanner.data.QuestRequirements.Reqs reqs =
						com.goalplanner.data.QuestRequirements.lookup(q);
					if (reqs != null && !com.goalplanner.data.QuestRequirements.hasRequirements(q))
					{
						isKnownLeaf = true;
					}
				}
				catch (IllegalArgumentException ignored) {}

				if (isKnownLeaf)
				{
					boolean hasInGestureReqs = false;
					if (g.getRequiredGoalIds() != null)
					{
						for (String reqId : g.getRequiredGoalIds())
						{
							if (gestureSet.contains(reqId))
							{
								hasInGestureReqs = true;
								break;
							}
						}
					}
					if (!hasInGestureReqs)
					{
						zeroDegQuests.add(id);
						continue;
					}
				}
			}
			others.add(id);
		}
		java.util.Collections.reverse(others);

		int p = 0;
		for (String id : zeroDegQuests)
		{
			Goal g = api.findGoal(id);
			if (g != null) { g.setPriority(p++); api.goalStore.updateGoal(g); }
		}
		for (String id : others)
		{
			Goal g = api.findGoal(id);
			if (g != null) { g.setPriority(p++); api.goalStore.updateGoal(g); }
		}

		// Promote leaf quests (zero active requirements) to the top so the user
		// sees "do first" goals at the top of the list.
		api.reorderingService.promoteLeafGoalsToTop();
		// Select all goals created by the gesture (one onGoalsChanged at endCompound).
		api.selectedGoalIds.addAll(gestureGoalIds);
		return gestureGoalIds;
	}

	/**
	 * Seed an EXISTING quest goal's requirement tree (prior quests, skill levels,
	 * QP/combat) into that goal's own section, linked beneath it, as one undo.
	 * Reuses the same BFS seeder as quest creation, rooted at the existing goal
	 * (so prereqs land in its section - see {@link #seedPrereqsInto}).
	 *
	 * <p>{@code includeMet = false} seeds only requirements not yet satisfied
	 * (the resolver's live default); {@code true} seeds the entire tree including
	 * already-met requirements. Existing matching goals are reused, not
	 * duplicated, so it's safe to run more than once. No-op (returns 0) for
	 * non-quest goals or quests without requirement data.
	 *
	 * @return number of prerequisite goals seeded (created or reused), excluding the root
	 */
	int seedRequirementsForGoal(String goalId, boolean includeMet)
	{
		log.debug("API.internal seedRequirementsForGoal(goalId={}, includeMet={})", goalId, includeMet);
		Goal g = api.findGoal(goalId);
		if (g == null || g.getType() != GoalType.QUEST || g.getQuestName() == null)
		{
			return 0;
		}
		Quest quest;
		try
		{
			quest = Quest.valueOf(g.getQuestName());
		}
		catch (IllegalArgumentException e)
		{
			return 0;
		}
		if (!com.goalplanner.data.QuestRequirements.hasRequirements(quest))
		{
			return 0;
		}

		com.goalplanner.data.QuestRequirementResolver.Resolved resolved =
			includeMet ? resolveAllRequirements(quest) : api.resolveQuestRequirements(quest);
		if (resolved == null || resolved.isEmpty())
		{
			return 0;
		}

		api.clearGoalSelection();
		api.beginCompound((includeMet ? "Add all requirements: " : "Add requirements: ")
			+ com.goalplanner.data.QuestRequirements.displayName(quest));
		try
		{
			// "All" includes already-met requirements. Keep this section's
			// completed goals inline (as cards, sunk to the bottom) instead of
			// letting them auto-archive to Completed, where they'd appear only as
			// faint ghost stand-ins. No-op for built-in/unknown sections.
			if (includeMet)
			{
				api.setSectionAutoArchiveOverride(g.getSectionId(), Boolean.FALSE);
			}
			java.util.List<String> gestureGoalIds =
				seedPrereqsAndPrioritize(goalId, quest, resolved.templates, /*allMode=*/includeMet);
			return gestureGoalIds.size() - 1;
		}
		finally
		{
			api.endCompound();
		}
	}

	/**
	 * Seed a boss goal's requirement tree into its section - the boss parallel
	 * of {@link #seedRequirementsForGoal}. {@code includeMet=false} ("Incomplete
	 * only") skips skill/quest/account requirements the player already meets;
	 * {@code includeMet=true} ("All") seeds the whole tree. Returns the number of
	 * goals newly created.
	 */
	int seedBossRequirementsForGoal(String goalId, boolean includeMet)
	{
		log.debug("API.internal seedBossRequirementsForGoal(goalId={}, includeMet={})", goalId, includeMet);
		Goal g = api.findGoal(goalId);
		if (g == null || g.getType() != GoalType.BOSS || g.getBossName() == null)
		{
			return 0;
		}
		String bossName = g.getBossName();
		if (com.goalplanner.data.BossKillData.getPrereqs(bossName) == null)
		{
			return 0;
		}

		java.util.Set<String> before = new java.util.HashSet<>();
		for (Goal x : api.goalStore.getGoals()) before.add(x.getId());

		api.clearGoalSelection();
		api.beginCompound((includeMet ? "Add all requirements: " : "Add requirements: ") + bossName);
		try
		{
			// "All" keeps the section's completed prereqs inline (mirrors the
			// quest/diary variants) instead of auto-archiving them to Completed.
			if (includeMet)
			{
				api.setSectionAutoArchiveOverride(g.getSectionId(), Boolean.FALSE);
			}
			seedBossPrereqs(goalId, bossName, includeMet);
		}
		finally
		{
			api.endCompound();
		}

		int added = 0;
		for (Goal x : api.goalStore.getGoals()) if (!before.contains(x.getId())) added++;
		return added;
	}

	/** True if the player has finished {@code q} (live client read; client-thread only). */
	private boolean isQuestFinished(net.runelite.api.Quest q)
	{
		if (api.client == null) return false;
		try
		{
			return q.getState(api.client) == net.runelite.api.QuestState.FINISHED;
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	/** True if the player's current value for {@code metricName} already meets {@code target}. */
	private boolean isAccountMet(String metricName, int target)
	{
		if (api.client == null) return false;
		try
		{
			return com.goalplanner.model.AccountMetric.valueOf(metricName).currentValue(api.client) >= target;
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	/**
	 * Labels of a goal's DIRECT prerequisites that the player has NOT met AND
	 * that aren't already represented anywhere in the plan - i.e. what's
	 * "probably blocking" this goal. Empty for goals without requirement data
	 * (or no client). Drives the card's blocked-prereqs badge; the badge's
	 * click runs {@code seed*RequirementsForGoal(..., false)} to add them.
	 *
	 * <p>Client-thread only (reads live skill/quest/account state).
	 */
	java.util.List<String> missingDirectPrereqLabels(Goal goal)
	{
		if (goal == null || api.client == null || goal.getType() == null)
		{
			return java.util.List.of();
		}
		java.util.List<Goal> unmet;
		switch (goal.getType())
		{
			case QUEST:
			{
				if (goal.getQuestName() == null) return java.util.List.of();
				net.runelite.api.Quest q;
				try { q = net.runelite.api.Quest.valueOf(goal.getQuestName()); }
				catch (IllegalArgumentException e) { return java.util.List.of(); }
				if (!com.goalplanner.data.QuestRequirements.hasRequirements(q)) return java.util.List.of();
				com.goalplanner.data.QuestRequirementResolver.Resolved r = api.resolveQuestRequirements(q);
				unmet = (r == null) ? java.util.List.of() : r.templates;
				break;
			}
			case DIARY:
			{
				com.goalplanner.data.AchievementDiaryData.Tier tier =
					parseTierFromDescription(goal.getDescription());
				if (tier == null || goal.getName() == null
					|| !com.goalplanner.data.DiaryRequirements.hasRequirements(goal.getName(), tier))
				{
					return java.util.List.of();
				}
				com.goalplanner.data.DiaryRequirementResolver.Resolved r =
					com.goalplanner.data.DiaryRequirementResolver.resolve(goal.getName(), tier, api.client);
				unmet = (r == null) ? java.util.List.of() : r.templates;
				break;
			}
			case BOSS:
			{
				if (goal.getBossName() == null) return java.util.List.of();
				unmet = unmetBossPrereqTemplates(goal.getBossName());
				break;
			}
			default:
				return java.util.List.of();
		}

		java.util.List<String> labels = new java.util.ArrayList<>();
		for (Goal t : unmet)
		{
			if (!isInPlan(t) && t.getName() != null)
			{
				labels.add(t.getName());
			}
		}
		return labels;
	}

	/** Direct, unmet boss skill/quest/account prereqs as goal templates. */
	private java.util.List<Goal> unmetBossPrereqTemplates(String bossName)
	{
		com.goalplanner.data.BossKillData.BossPrereqs p =
			com.goalplanner.data.BossKillData.getPrereqs(bossName);
		if (p == null) return java.util.List.of();
		java.util.List<Goal> out = new java.util.ArrayList<>();
		for (com.goalplanner.data.BossKillData.SkillReq sr : p.skills)
		{
			if (api.client.getRealSkillLevel(sr.skill) >= sr.level) continue;
			out.add(Goal.builder().type(GoalType.SKILL)
				.name(sr.skill.getName() + " - Level " + sr.level)
				.skillName(sr.skill.name())
				.targetValue(net.runelite.api.Experience.getXpForLevel(sr.level)).build());
		}
		for (net.runelite.api.Quest q : p.quests)
		{
			if (isQuestFinished(q)) continue;
			out.add(Goal.builder().type(GoalType.QUEST).name(q.getName())
				.questName(q.name()).targetValue(1).build());
		}
		for (com.goalplanner.data.BossKillData.AccountReq ar : p.accountReqs)
		{
			if (isAccountMet(ar.metricName, ar.target)) continue;
			String label;
			try
			{
				label = ar.target + " " + com.goalplanner.model.AccountMetric.valueOf(ar.metricName).getDisplayName();
			}
			catch (RuntimeException e)
			{
				label = ar.metricName;
			}
			out.add(Goal.builder().type(GoalType.ACCOUNT).name(label)
				.accountMetric(ar.metricName).targetValue(ar.target).build());
		}
		return out;
	}

	/** True if an existing goal already represents {@code template} (covers it),
	 *  accounting for higher-target skill/item/account/boss goals. */
	private boolean isInPlan(Goal template)
	{
		if (template.getType() == GoalType.ACCOUNT)
		{
			for (Goal g : api.goalStore.getGoals())
			{
				if (g.getType() == GoalType.ACCOUNT && template.getAccountMetric() != null
					&& template.getAccountMetric().equals(g.getAccountMetric())
					&& g.getTargetValue() >= template.getTargetValue())
				{
					return true;
				}
			}
			return false;
		}
		if (template.getType() == GoalType.BOSS)
		{
			for (Goal g : api.goalStore.getGoals())
			{
				if (g.getType() == GoalType.BOSS && template.getBossName() != null
					&& template.getBossName().equalsIgnoreCase(g.getBossName())
					&& g.getTargetValue() >= template.getTargetValue())
				{
					return true;
				}
			}
			return false;
		}
		return api.goalStore.findMatchingGoal(template) != null;
	}

	/**
	 * Resolve a quest's FULL requirement tree, treating nothing as already met
	 * (level 1, every prereq quest NOT_STARTED, combat 3), so the entire tree is
	 * returned. Used by {@link #seedRequirementsForGoal} for the "All" variant.
	 */
	private com.goalplanner.data.QuestRequirementResolver.Resolved resolveAllRequirements(Quest quest)
	{
		return com.goalplanner.data.QuestRequirementResolver.resolve(
			quest,
			s -> 1,
			q -> net.runelite.api.QuestState.NOT_STARTED,
			() -> 3);
	}

	/**
	 * Individual queue entry for {@link #seedPrereqsInto}: one template
	 * paired with its parent goal/quest context. Skills and quests from
	 * the same parent are split into separate entries so the priority
	 * queue can interleave them globally.
	 */
	private static final class SeedEntry
	{
		final String parentGoalId;
		final Quest parentQuest;   // null for non-quest parents (diary, unlock)
		final String tagLabel;     // tag to apply to seeded goals
		final Goal template;

		SeedEntry(String parentGoalId, Quest parentQuest, Goal template)
		{
			this.parentGoalId = parentGoalId;
			this.parentQuest = parentQuest;
			this.tagLabel = parentQuest != null
				? com.goalplanner.data.QuestRequirements.displayName(parentQuest) : null;
			this.template = template;
		}

		SeedEntry(String parentGoalId, String tagLabel, Goal template)
		{
			this.parentGoalId = parentGoalId;
			this.parentQuest = null;
			this.tagLabel = tagLabel;
			this.template = template;
		}
	}

	/**
	 * Priority-queue BFS: seed prereq templates with skills always
	 * processed before quests. Whenever a quest's children are
	 * discovered, skill children jump to the front of the queue
	 * ahead of any pending quests. This produces a creation order
	 * where all reachable skill goals are created first, followed
	 * by quest goals.
	 *
	 * <p><b>QUEST templates</b> go through the primitive
	 * {@link #insertQuestGoal(Quest)} helper (canonical sprite, duplicate
	 * guard, future-proof). <b>SKILL templates</b> go through
	 * {@link #findOrCreateSkillGoalForSeed} which reuses pre-existing
	 * USER goals (non-{@code autoSeeded}) but always creates a new
	 * goal for each distinct target - so "15 Agility" and "32 Agility"
	 * in the same gesture both get their own card.
	 *
	 * <p>Caller must already be inside a {@code beginCompound}/
	 * {@code endCompound} block.
	 */
	/** Quest-rooted overload for backward compatibility. */
	private void seedPrereqsInto(
		String rootGoalId,
		Quest rootQuest,
		java.util.List<Goal> rootTemplates,
		java.util.Set<Quest> visited,
		java.util.Set<String> preExistingGoalIds,
		java.util.List<String> gestureGoalIds)
	{
		seedPrereqsInto(rootGoalId, rootQuest,
			com.goalplanner.data.QuestRequirements.displayName(rootQuest),
			rootTemplates, visited, preExistingGoalIds, gestureGoalIds);
	}

	/**
	 * Generalized prereq seeding with 3-queue priority BFS.
	 * Works for both quest prereqs and diary prereqs.
	 *
	 * @param rootGoalId   the parent goal to link prereqs to
	 * @param rootQuest    the quest (null for diary/unlock parents)
	 * @param rootTagLabel tag to apply to seeded goals
	 * @param rootTemplates templates to seed
	 * @param visited      cycle guard for recursive quest resolution
	 * @param preExistingGoalIds goal IDs that existed before this gesture
	 * @param gestureGoalIds accumulator for all created goal IDs
	 */
	private void seedPrereqsInto(
		String rootGoalId,
		Quest rootQuest,
		String rootTagLabel,
		java.util.List<Goal> rootTemplates,
		java.util.Set<Quest> visited,
		java.util.Set<String> preExistingGoalIds,
		java.util.List<String> gestureGoalIds)
	{
		// Three-queue priority system: optional recommendations (combat level,
		// recommended skills) process first, then required skills/account goals,
		// then quests. This puts optional goals at the top of the card list so
		// the user sees them prominently.
		java.util.ArrayDeque<SeedEntry> optionalPriority = new java.util.ArrayDeque<>();
		java.util.ArrayDeque<SeedEntry> highPriority = new java.util.ArrayDeque<>();
		java.util.ArrayDeque<SeedEntry> lowPriority = new java.util.ArrayDeque<>();

		// Sort skill templates by target value descending so higher-level
		// skills are processed (and thus positioned) first in the card list.
		java.util.List<Goal> sortedTemplates = new java.util.ArrayList<>(rootTemplates);
		sortedTemplates.sort((a, b) -> {
			boolean aSkill = a != null && a.getType() == GoalType.SKILL;
			boolean bSkill = b != null && b.getType() == GoalType.SKILL;
			if (aSkill && bSkill) return Integer.compare(a.getTargetValue(), b.getTargetValue());
			return 0; // preserve original order for non-skill types
		});

		// Seed initial templates into the appropriate queue
		for (Goal t : sortedTemplates)
		{
			if (t == null) continue;
			SeedEntry entry = new SeedEntry(rootGoalId, rootTagLabel, t);
			if (t.isOptional())
			{
				optionalPriority.add(entry);
			}
			else if (t.getType() == GoalType.QUEST)
			{
				lowPriority.add(entry);
			}
			else
			{
				highPriority.add(entry);
			}
		}

		while (!optionalPriority.isEmpty() || !highPriority.isEmpty() || !lowPriority.isEmpty())
		{
			// Drain optional first, then skills/account, then quests
			SeedEntry entry;
			if (!optionalPriority.isEmpty())
			{
				entry = optionalPriority.poll();
			}
			else if (!highPriority.isEmpty())
			{
				entry = highPriority.poll();
			}
			else
			{
				entry = lowPriority.poll();
			}

			Goal template = entry.template;
			final String parentTagLabel = entry.tagLabel;
			Goal parentGoal = api.findGoal(entry.parentGoalId);
			String sectionId = parentGoal == null ? null : parentGoal.getSectionId();

			String seedGoalId;
			Quest childQuestForNextLevel = null;

			// Cross-section dedup: if an equivalent goal already lives in the TARGET
			// section's namespace - e.g. a shared prerequisite reached via another
			// path and created earlier in THIS gesture - reuse it instead of
			// creating a fresh copy. Without this, the per-type creators
			// (insertQuestGoal/addAccountGoal/addBossGoal/addItemGoal/findOrCreate-
			// SkillGoalForSeed→addSkillGoal) dedup only against the DEFAULT namespace,
			// so the second encounter spawns a duplicate in Incomplete that the
			// per-section no-duplicate guard then strands. Identity is per-type
			// (GoalIdentity.sameIdentity): SKILL matches on exact target XP and
			// ACCOUNT/BOSS on metric+target / boss name, so different skill levels
			// or kc/combat targets are never merged. (The SKILL ">= target" reuse of
			// a pre-existing user goal still happens below in findOrCreateSkillGoalForSeed
			// when this exact-identity probe misses.)
			Goal reusableInSection = sectionId == null
				? null : api.goalStore.findEquivalentInNamespace(sectionId, template);

			if (reusableInSection != null)
			{
				seedGoalId = reusableInSection.getId();
				// A reused QUEST goal still needs its child tree walked (guarded by
				// `visited` below - already-walked quests are skipped).
				if (template.getType() == GoalType.QUEST && template.getQuestName() != null)
				{
					try
					{
						childQuestForNextLevel = Quest.valueOf(template.getQuestName());
					}
					catch (IllegalArgumentException ignored)
					{
						// Unknown enum name - nothing to recurse into; keep the reused goal.
					}
				}
			}
			else if (template.getType() == GoalType.QUEST && template.getQuestName() != null)
			{
				Quest childQuest;
				try
				{
					childQuest = Quest.valueOf(template.getQuestName());
				}
				catch (IllegalArgumentException e)
				{
					log.warn("seedPrereqsInto: unknown Quest enum name '{}'", template.getQuestName());
					continue;
				}
				seedGoalId = insertQuestGoal(childQuest);
				if (seedGoalId == null)
				{
					log.warn("seedPrereqsInto: addQuestGoal returned null for {}", childQuest);
					continue;
				}
				if (sectionId != null)
				{
					Goal created = api.findGoal(seedGoalId);
					if (created != null && !sectionId.equals(created.getSectionId()))
					{
						api.moveGoalToSection(seedGoalId, sectionId);
					}
				}
				childQuestForNextLevel = childQuest;
			}
			else if (template.getType() == GoalType.SKILL && template.getSkillName() != null)
			{
				net.runelite.api.Skill skill;
				try
				{
					skill = net.runelite.api.Skill.valueOf(template.getSkillName());
				}
				catch (IllegalArgumentException e)
				{
					log.warn("seedPrereqsInto: unknown Skill enum name '{}'", template.getSkillName());
					continue;
				}
				seedGoalId = findOrCreateSkillGoalForSeed(skill, template.getTargetValue(), preExistingGoalIds);
				if (seedGoalId == null)
				{
					log.warn("seedPrereqsInto: findOrCreateSkillGoalForSeed returned null for {}", skill);
					continue;
				}
			}
			else if (template.getType() == GoalType.ACCOUNT && template.getAccountMetric() != null)
			{
				seedGoalId = addAccountGoal(template.getAccountMetric(), template.getTargetValue());
				if (seedGoalId == null)
				{
					log.warn("seedPrereqsInto: addAccountGoal returned null for {} {}",
						template.getAccountMetric(), template.getTargetValue());
					continue;
				}
			}
			else if (template.getType() == GoalType.BOSS && template.getBossName() != null)
			{
				seedGoalId = addBossGoal(template.getBossName(), template.getTargetValue());
				if (seedGoalId == null)
				{
					log.warn("seedPrereqsInto: addBossGoal returned null for {}", template.getBossName());
					continue;
				}
			}
			else if (template.getType() == GoalType.ITEM_GRIND && template.getItemId() > 0)
			{
				seedGoalId = addItemGoal(template.getItemId(), template.getTargetValue());
				if (seedGoalId == null)
				{
					log.warn("seedPrereqsInto: addItemGoal returned null for itemId={}", template.getItemId());
					continue;
				}
			}
			else
			{
				GoalPlannerInternalApi.FindOrCreateResult result = api.findOrCreateRequirement(template, sectionId);
				if (result == null)
				{
					log.warn("seedPrereqsInto: findOrCreateRequirement returned null for template type={}",
						template.getType());
					continue;
				}
				seedGoalId = result.goalId;
			}

			// Place newly-created seeds in the parent's section so they follow that
			// section's rules (e.g. keep-inline) instead of stranding in the default
			// Incomplete section - which ALWAYS archives completed goals to Completed,
			// ignoring the section's keep-inline override. Only QUEST seeds were moved
			// above; SKILL/ACCOUNT/BOSS/ITEM create in the default section. Reused
			// pre-existing goals stay where the user already has them.
			if (sectionId != null && !preExistingGoalIds.contains(seedGoalId))
			{
				Goal placed = api.findGoal(seedGoalId);
				if (placed != null && !sectionId.equals(placed.getSectionId()))
				{
					api.moveGoalToSection(seedGoalId, sectionId);
				}
			}

			// Skip completed goals - they're already done, no need to link them as
			// requirements in the tree. EXCEPT in "All" mode (seedKeepCompleted),
			// where the whole point is to show the full tree including the parts
			// you've finished (they render as completed cards at the bottom).
			Goal seedGoal = api.findGoal(seedGoalId);
			if (!seedKeepCompleted && seedGoal != null && seedGoal.isComplete())
			{
				continue;
			}

			// Propagate optional flag from template to created goal.
			if (template.isOptional())
			{
				api.setGoalOptional(seedGoalId, true);
			}

			gestureGoalIds.add(seedGoalId);
			api.addRequirement(entry.parentGoalId, seedGoalId);
			try
			{
				api.addTagWithCategory(seedGoalId, parentTagLabel, TagCategory.QUEST.name());
			}
			catch (Exception e)
			{
				log.warn("seedPrereqsInto: failed to tag {} with '{}': {}", seedGoalId, parentTagLabel, e.getMessage());
			}

			// Discover child quest's prereqs and route to appropriate queue.
			if (childQuestForNextLevel != null)
			{
				if (!visited.add(childQuestForNextLevel))
				{
					continue;
				}
				// In "All" mode resolve the child's FULL requirement tree with floor
				// lookups (treat nothing as met) so transitive prereqs sitting under an
				// ALREADY-COMPLETED quest (e.g. Death Plateau under a finished Troll
				// Stronghold) seed too. Quest-add / "Incomplete only" use live state.
				com.goalplanner.data.QuestRequirementResolver.Resolved childResolved =
					seedKeepCompleted
						? resolveAllRequirements(childQuestForNextLevel)
						: api.resolveQuestRequirements(childQuestForNextLevel);
				// Sort child skill templates highest-level-first.
				java.util.List<Goal> sortedChildTemplates = new java.util.ArrayList<>(childResolved.templates);
				sortedChildTemplates.sort((a, b) -> {
					boolean aSkill = a != null && a.getType() == GoalType.SKILL;
					boolean bSkill = b != null && b.getType() == GoalType.SKILL;
					if (aSkill && bSkill) return Integer.compare(a.getTargetValue(), b.getTargetValue());
					return 0;
				});
				for (Goal childTemplate : sortedChildTemplates)
				{
					if (childTemplate == null) continue;
					SeedEntry childEntry = new SeedEntry(seedGoalId, childQuestForNextLevel, childTemplate);
					if (childTemplate.isOptional())
					{
						optionalPriority.add(childEntry);
					}
					else if (childTemplate.getType() == GoalType.QUEST)
					{
						lowPriority.add(childEntry);
					}
					else
					{
						highPriority.add(childEntry);
					}
				}
			}
		}
	}

	/**
	 * Hybrid skill-goal seeder: reuses a pre-existing USER goal
	 * (non-{@code autoSeeded}) if one already satisfies the target,
	 * otherwise creates a new goal via the public
	 * {@link #addSkillGoal(net.runelite.api.Skill, int)} API. This
	 * means each distinct target level gets its own card (15 Agility
	 * and 32 Agility coexist), but a pre-existing 99 Agility goal
	 * that the user manually created is reused and tagged rather than
	 * spawning a redundant lower-level seed.
	 *
	 * @param skill    the skill
	 * @param targetXp target XP (use {@code Experience.getXpForLevel})
	 * @return goal id (existing or newly created), or null on error
	 */
	private String findOrCreateSkillGoalForSeed(
		net.runelite.api.Skill skill, int targetXp, java.util.Set<String> preExistingGoalIds)
	{
		// 1. Check for a pre-existing USER goal (one that existed before
		//    this gesture started) that satisfies the requirement. Goals
		//    created during the same gesture are NOT eligible - otherwise
		//    Lunar's "61 Crafting" would swallow Lost City's "31 Crafting",
		//    making Lost City display the wrong requirement.
		for (Goal g : api.goalStore.getGoals())
		{
			if (!preExistingGoalIds.contains(g.getId())) continue;
			if (g.getType() != GoalType.SKILL) continue;
			if (g.getSkillName() == null
				|| !skill.name().equalsIgnoreCase(g.getSkillName())) continue;
			if (g.getTargetValue() >= targetXp)
			{
				return g.getId();
			}
		}
		// 2. No match - create via the public API. addSkillGoal handles
		//    its own exact-target dedupe (same skill + same XP → returns
		//    existing id) and auto-chains same-skill goals in the topo
		//    sort. Its executeCommand calls join the active compound.
		return addSkillGoal(skill, targetXp);
	}

	/**
	 * Raw single-goal insert for diary: validates, duplicate-checks,
	 * builds the Goal. Does NOT resolve or seed prerequisites. Internal
	 * primitive used by {@link #addDiaryGoal(String, GoalPlannerApi.DiaryTier)}
	 * and {@link #addDiaryGoalWithPrereqs}.
	 */
	String insertDiaryGoal(String areaDisplayName, GoalPlannerApi.DiaryTier tier)
	{
		log.debug("API.internal insertDiaryGoal(area={}, tier={})", areaDisplayName, tier);
		if (areaDisplayName == null || areaDisplayName.isEmpty() || tier == null)
		{
			log.warn("insertDiaryGoal: invalid input area={} tier={}", areaDisplayName, tier);
			return null;
		}

		AchievementDiaryData.Tier internalTier = mapDiaryTier(tier);
		String description = internalTier.getDisplayName() + " Achievement Diary";

		// Duplicate guard: same area + tier description in the default namespace.
		Goal diaryDup = existingInDefault(Goal.builder()
			.type(GoalType.DIARY).name(areaDisplayName).description(description).build());
		if (diaryDup != null)
		{
			log.info("insertDiaryGoal: duplicate of existing goal {} ({} {})",
				diaryDup.getId(), areaDisplayName, internalTier);
			return diaryDup.getId();
		}

		AchievementDiaryData.Tracking tracking = AchievementDiaryData.tracking(areaDisplayName, internalTier);
		int varbitId = tracking != null ? tracking.varbitId : 0;
		int targetValue = tracking != null ? tracking.requiredValue : 1;

		Goal goal = Goal.builder()
			.type(GoalType.DIARY)
			.name(areaDisplayName)
			.description(description)
			.targetValue(targetValue)
			.currentValue(0)
			.spriteId(AchievementDiaryData.DIARY_SPRITE_ID)
			.varbitId(varbitId)
			.build();

		final String goalId = goal.getId();
		final String displayName = goal.getName();
		final String tierStr = internalTier.getDisplayName();
		executeAddGoal(goal, goalId, "Add diary: " + displayName + " " + tierStr);
		log.info("insertDiaryGoal created: {} ({} {})", goalId, areaDisplayName, internalTier);
		return goalId;
	}

	/**
	 * Public API entry point: add a diary goal, auto-resolving unmet
	 * skill/quest/unlock prerequisites from live player state and seeding
	 * them into the goal tree. Merged entry point that replaces the
	 * separate plain / with-prereqs pair.
	 */
	String addDiaryGoal(String areaDisplayName, GoalPlannerApi.DiaryTier tier)
	{
		log.debug("API.public addDiaryGoal(area={}, tier={})", areaDisplayName, tier);
		if (areaDisplayName == null || tier == null)
		{
			return insertDiaryGoal(areaDisplayName, tier);
		}
		AchievementDiaryData.Tier internalTier = mapDiaryTier(tier);
		if (!com.goalplanner.data.DiaryRequirements.hasRequirements(areaDisplayName, internalTier))
		{
			return insertDiaryGoal(areaDisplayName, tier);
		}
		com.goalplanner.data.DiaryRequirementResolver.Resolved resolved =
			com.goalplanner.data.DiaryRequirementResolver.resolve(
				areaDisplayName, internalTier, api.client);
		if (resolved == null || resolved.isEmpty())
		{
			return insertDiaryGoal(areaDisplayName, tier);
		}
		return addDiaryGoalWithPrereqs(areaDisplayName, tier, resolved);
	}

	private static AchievementDiaryData.Tier mapDiaryTier(GoalPlannerApi.DiaryTier tier)
	{
		switch (tier)
		{
			case EASY:   return AchievementDiaryData.Tier.EASY;
			case MEDIUM: return AchievementDiaryData.Tier.MEDIUM;
			case HARD:   return AchievementDiaryData.Tier.HARD;
			case ELITE:  return AchievementDiaryData.Tier.ELITE;
			default:     throw new IllegalArgumentException("Unknown DiaryTier " + tier);
		}
	}

	/**
	 * Reuse-or-create a diary sub-goal inside the diary goal's section. If an
	 * equivalent goal already lives in {@code sectionId}'s namespace - a shared
	 * prereq reached via another path this gesture, or a pre-existing user goal -
	 * reuse it; otherwise run {@code create} and move the freshly-made goal into
	 * the section so it follows that section's rules instead of stranding in the
	 * default Incomplete. Mirrors the cross-section dedup in {@link #seedPrereqsInto}
	 * (commit e5e7383), applied to the boss/account/quest/unlock sub-goals that
	 * {@link #addDiaryGoalWithPrereqsCore} creates outside that BFS. No-op for the
	 * default namespace (new-goal flow): the per-type creators already dedup there
	 * and a move into the same section is refused, so behaviour is unchanged.
	 */
	private String reuseOrCreateInSection(String sectionId, Goal probe,
		java.util.Set<String> preExistingGoalIds, java.util.function.Supplier<String> create)
	{
		if (sectionId != null && probe != null)
		{
			Goal reuse = api.goalStore.findEquivalentInNamespace(sectionId, probe);
			if (reuse != null)
			{
				return reuse.getId();
			}
		}
		String id = create.get();
		if (id == null)
		{
			return null;
		}
		if (sectionId != null && (preExistingGoalIds == null || !preExistingGoalIds.contains(id)))
		{
			Goal placed = api.findGoal(id);
			if (placed != null && !sectionId.equals(placed.getSectionId()))
			{
				api.moveGoalToSection(id, sectionId);
			}
		}
		return id;
	}

	/**
	 * Add a diary goal with all unmet skill and quest requirements seeded
	 * as prerequisite goals. Parallel to addQuestGoalWithPrereqs.
	 */
	String addDiaryGoalWithPrereqs(String areaDisplayName, GoalPlannerApi.DiaryTier tier,
		com.goalplanner.data.DiaryRequirementResolver.Resolved resolved)
	{
		if (areaDisplayName == null || tier == null) return null;
		if (resolved == null || resolved.isEmpty())
		{
			return insertDiaryGoal(areaDisplayName, tier);
		}
		java.util.List<String> gesture =
			addDiaryGoalWithPrereqsCore(null, areaDisplayName, tier, resolved, /*keepCompleted=*/false);
		return gesture.isEmpty() ? null : gesture.get(0);
	}

	/**
	 * Core diary prereq seeder. When {@code existingDiaryGoalId} is null, inserts a
	 * fresh diary goal (the in-game add path); otherwise seeds INTO that existing
	 * goal (the panel "Add requirements to this section" path -
	 * {@link #seedDiaryRequirementsForGoal}). Seeds {@code resolved.templates} via
	 * the shared section-aware {@link #seedPrereqsInto}, plus {@code resolved.bossReqs}
	 * and {@code resolved.unlocks} (with their own sub-prereqs) - every sub-goal goes
	 * through {@link #reuseOrCreateInSection} so it lands in the diary goal's section
	 * and a shared prereq is reused, never duplicated/stranded. {@code keepCompleted}
	 * (the "All" variant) keeps already-met requirements linked as inline cards.
	 *
	 * @return every goal id touched by the gesture (diary goal first), or empty on failure
	 */
	private java.util.List<String> addDiaryGoalWithPrereqsCore(
		String existingDiaryGoalId, String areaDisplayName, GoalPlannerApi.DiaryTier tier,
		com.goalplanner.data.DiaryRequirementResolver.Resolved resolved, boolean keepCompleted)
	{
		log.debug("API.internal addDiaryGoalWithPrereqsCore(existing={}, area={}, tier={}, templates={}, unlocks={}, keepCompleted={})",
			existingDiaryGoalId, areaDisplayName, tier,
			resolved == null ? 0 : resolved.templates.size(),
			resolved == null ? 0 : resolved.unlocks.size(), keepCompleted);
		java.util.List<String> gestureGoalIds = new java.util.ArrayList<>();
		if (areaDisplayName == null || tier == null || resolved == null || resolved.isEmpty())
		{
			return gestureGoalIds;
		}
		java.util.List<Goal> prereqTemplates = resolved.templates;

		AchievementDiaryData.Tier internalTier = mapDiaryTier(tier);
		String tierStr = internalTier.getDisplayName();
		String compoundDesc = "Add diary with requirements: " + areaDisplayName + " " + tierStr;

		api.beginCompound(compoundDesc);
		boolean prevKeepCompleted = this.seedKeepCompleted;
		this.seedKeepCompleted = keepCompleted;
		try
		{
			String diaryGoalId = existingDiaryGoalId != null
				? existingDiaryGoalId : insertDiaryGoal(areaDisplayName, tier);
			if (diaryGoalId == null)
			{
				log.warn("addDiaryGoalWithPrereqsCore: insertDiaryGoal returned null");
				return gestureGoalIds;
			}

			Goal diaryGoal = api.findGoal(diaryGoalId);
			String sectionId = diaryGoal != null ? diaryGoal.getSectionId() : null;

			String tagLabel = areaDisplayName + " " + tierStr;
			if (tagLabel.length() > 30)
			{
				tagLabel = areaDisplayName;
			}

			gestureGoalIds.add(diaryGoalId);

			// Use the same BFS priority queue as quest prereq seeding.
			java.util.Set<Quest> visited = new java.util.HashSet<>();
			java.util.Set<String> preExisting = new java.util.HashSet<>();
			for (Goal g : api.goalStore.getGoals()) preExisting.add(g.getId());
			seedPrereqsInto(diaryGoalId, null, tagLabel,
				prereqTemplates, visited, preExisting, gestureGoalIds);

			// Goals with their own prereq trees (boss goals with skill reqs,
			// account goals with quest prereqs). Boss prereqs are now auto-
			// seeded by addBossGoal via BossKillData.getPrereqs().
			for (com.goalplanner.data.DiaryRequirementResolver.ResolvedBossReq entry : resolved.bossReqs)
			{
				final Goal bt = entry.bossTemplate;
				final String sid = sectionId;
				String goalId;
				if (bt.getType() == GoalType.BOSS && bt.getBossName() != null)
				{
					// addBossGoal auto-seeds skill/unlock prereqs from BossKillData
					goalId = reuseOrCreateInSection(sid, bt, preExisting,
						() -> addBossGoal(bt.getBossName(), bt.getTargetValue()));
				}
				else if (bt.getType() == GoalType.ACCOUNT && bt.getAccountMetric() != null)
				{
					goalId = reuseOrCreateInSection(sid, bt, preExisting,
						() -> addAccountGoal(bt.getAccountMetric(), bt.getTargetValue()));
				}
				else
				{
					continue;
				}
				if (goalId == null) continue;
				gestureGoalIds.add(goalId);
				api.addRequirement(diaryGoalId, goalId);
				try
				{
					api.addTagWithCategory(goalId, tagLabel, TagCategory.QUEST.name());
				}
				catch (Exception e)
				{
					log.warn("addDiaryGoalWithPrereqs: failed to tag: {}", e.getMessage());
				}

				// Seed any quest prereqs on the goal (e.g. Throne of Miscellania
				// for MISC_APPROVAL account metric)
				// Use boss name or the created goal's display name for tagging
				// (not the raw template name which may be an enum like "TOG_MAX_TEARS")
				Goal createdGoal = api.findGoal(goalId);
				String entryTagLabel = bt.getBossName() != null
					? bt.getBossName()
					: (createdGoal != null ? createdGoal.getName() : bt.getName());
				if (entryTagLabel != null && entryTagLabel.length() > 30)
					entryTagLabel = entryTagLabel.substring(0, 30);
				for (Goal prereqTemplate : entry.skillTemplates)
				{
					if (prereqTemplate.getType() == GoalType.QUEST && prereqTemplate.getQuestName() != null)
					{
						try
						{
							final Quest quest = Quest.valueOf(prereqTemplate.getQuestName());
							String questGoalId = reuseOrCreateInSection(sid, prereqTemplate, preExisting,
								() -> insertQuestGoal(quest));
							if (questGoalId == null) continue;
							gestureGoalIds.add(questGoalId);
							api.addRequirement(goalId, questGoalId);
							try
							{
								api.addTagWithCategory(questGoalId, entryTagLabel, TagCategory.QUEST.name());
							}
							catch (Exception ex)
							{
								log.warn("addDiaryGoalWithPrereqs: failed to tag quest prereq: {}", ex.getMessage());
							}
							// Recursively seed quest's own prereqs
							com.goalplanner.data.QuestRequirementResolver.Resolved qr =
								api.resolveQuestRequirements(quest);
							if (qr != null && !qr.isEmpty())
							{
								java.util.Set<Quest> qVisited = new java.util.HashSet<>();
								qVisited.add(quest);
								seedPrereqsInto(questGoalId, quest, qr.templates,
									qVisited, preExisting, gestureGoalIds);
							}
						}
						catch (IllegalArgumentException e)
						{
							log.warn("addDiaryGoalWithPrereqs: unknown quest {}", prereqTemplate.getQuestName());
						}
					}
				}
			}

			// Unlocks: create CUSTOM goal → link quest prereqs to it → link to diary.
			for (com.goalplanner.data.DiaryRequirementResolver.ResolvedUnlock unlock : resolved.unlocks)
			{
				String unlockGoalId = reuseOrCreateInSection(sectionId,
					Goal.builder().type(GoalType.CUSTOM).name(unlock.name).build(), preExisting,
					() -> addCustomGoal(unlock.name, "Requirement"));
				if (unlockGoalId == null) continue;
				// Set item icon on the unlock goal.
				if (unlock.itemId > 0)
				{
					Goal unlockGoal = api.findGoal(unlockGoalId);
					if (unlockGoal != null)
					{
						unlockGoal.setItemId(unlock.itemId);
						api.goalStore.save();
					}
				}
				gestureGoalIds.add(unlockGoalId);
				api.addRequirement(diaryGoalId, unlockGoalId);
				try
				{
					api.addTagWithCategory(unlockGoalId, tagLabel, TagCategory.QUEST.name());
				}
				catch (Exception e)
				{
					log.warn("addDiaryGoalWithPrereqs: failed to tag unlock: {}", e.getMessage());
				}

				// Link the unlock's quest prereqs to the unlock goal.
				for (Goal questTemplate : unlock.questTemplates)
				{
					if (questTemplate.getType() != GoalType.QUEST || questTemplate.getQuestName() == null)
						continue;
					try
					{
						final Quest quest = Quest.valueOf(questTemplate.getQuestName());
						final Goal qProbe = questTemplate;
						String questGoalId = reuseOrCreateInSection(sectionId, qProbe, preExisting,
							() -> insertQuestGoal(quest));
						if (questGoalId == null) continue;
						gestureGoalIds.add(questGoalId);
						api.addRequirement(unlockGoalId, questGoalId);
						try
						{
							api.addTagWithCategory(questGoalId, tagLabel, TagCategory.QUEST.name());
						}
						catch (Exception e2)
						{
							log.warn("addDiaryGoalWithPrereqs: failed to tag quest: {}", e2.getMessage());
						}
						// Seed this quest's own requirements recursively.
						com.goalplanner.data.QuestRequirementResolver.Resolved qr =
							api.resolveQuestRequirements(quest);
						if (qr != null && !qr.isEmpty())
						{
							java.util.Set<Quest> vis = new java.util.HashSet<>();
							vis.add(quest);
							java.util.Set<String> pre = new java.util.HashSet<>();
							for (Goal g2 : api.goalStore.getGoals())
								pre.add(g2.getId());
							seedPrereqsInto(questGoalId, quest, qr.templates,
								vis, pre, gestureGoalIds);
						}
					}
					catch (IllegalArgumentException e)
					{
						log.warn("addDiaryGoalWithPrereqs: unknown quest {}", questTemplate.getQuestName());
					}
				}

				// Link the unlock's skill prereqs to the unlock goal.
				// These are optional (suggested, not strictly required).
				for (Goal skillTemplate : unlock.skillTemplates)
				{
					if (skillTemplate.getType() != GoalType.SKILL || skillTemplate.getSkillName() == null)
						continue;
					final Goal st = skillTemplate;
					String skillGoalId = reuseOrCreateInSection(sectionId, st, preExisting,
						() -> addSkillGoal(Skill.valueOf(st.getSkillName()), st.getTargetValue()));
					if (skillGoalId == null) continue;
					Goal skillGoal = api.findGoal(skillGoalId);
					if (skillGoal != null) skillGoal.setOptional(true);
					gestureGoalIds.add(skillGoalId);
					api.addRequirement(unlockGoalId, skillGoalId);
					try
					{
						api.addTagWithCategory(skillGoalId, tagLabel, TagCategory.QUEST.name());
					}
					catch (Exception e)
					{
						log.warn("addDiaryGoalWithPrereqs: failed to tag unlock skill: {}", e.getMessage());
					}
				}

				// Link the unlock's account metric prereqs to the unlock goal.
				for (Goal accountTemplate : unlock.accountTemplates)
				{
					if (accountTemplate.getType() != GoalType.ACCOUNT || accountTemplate.getAccountMetric() == null)
						continue;
					final Goal at = accountTemplate;
					String accountGoalId = reuseOrCreateInSection(sectionId, at, preExisting,
						() -> addAccountGoal(at.getAccountMetric(), at.getTargetValue()));
					if (accountGoalId == null) continue;
					gestureGoalIds.add(accountGoalId);
					api.addRequirement(unlockGoalId, accountGoalId);
					try
					{
						api.addTagWithCategory(accountGoalId, tagLabel, TagCategory.QUEST.name());
					}
					catch (Exception e)
					{
						log.warn("addDiaryGoalWithPrereqs: failed to tag unlock account: {}", e.getMessage());
					}
				}

				// Seed alternatives as OR-prereqs on the unlock goal.
				// Tag each with the unlock name (e.g. "Warriors Guild Entry").
				String unlockTagLabel = unlock.name;
				if (unlockTagLabel != null && unlockTagLabel.length() > 30)
				{
					unlockTagLabel = unlockTagLabel.substring(0, 30);
				}
				for (com.goalplanner.data.DiaryRequirementResolver.ResolvedAlternative alt : unlock.alternatives)
				{
					for (Goal skillTemplate : alt.skillTemplates)
					{
						if (skillTemplate.getType() != GoalType.SKILL || skillTemplate.getSkillName() == null)
							continue;
						final Goal altSt = skillTemplate;
						String skillGoalId = reuseOrCreateInSection(sectionId, altSt, preExisting,
							() -> addSkillGoal(Skill.valueOf(altSt.getSkillName()), altSt.getTargetValue()));
						if (skillGoalId == null) continue;
						gestureGoalIds.add(skillGoalId);
						api.addOrRequirement(unlockGoalId, skillGoalId);
						try
						{
							api.addTagWithCategory(skillGoalId, unlockTagLabel, TagCategory.QUEST.name());
						}
						catch (Exception e)
						{
							log.warn("addDiaryGoalWithPrereqs: failed to tag alt skill: {}", e.getMessage());
						}
					}
					for (Goal accountTemplate : alt.accountTemplates)
					{
						if (accountTemplate.getType() != GoalType.ACCOUNT || accountTemplate.getAccountMetric() == null)
							continue;
						final Goal altAt = accountTemplate;
						String accountGoalId = reuseOrCreateInSection(sectionId, altAt, preExisting,
							() -> addAccountGoal(altAt.getAccountMetric(), altAt.getTargetValue()));
						if (accountGoalId == null) continue;
						gestureGoalIds.add(accountGoalId);
						api.addOrRequirement(unlockGoalId, accountGoalId);
						try
						{
							api.addTagWithCategory(accountGoalId, unlockTagLabel, TagCategory.QUEST.name());
						}
						catch (Exception e)
						{
							log.warn("addDiaryGoalWithPrereqs: failed to tag alt account: {}", e.getMessage());
						}
					}
					for (Goal bossTemplate : alt.bossTemplates)
					{
						if (bossTemplate.getType() != GoalType.BOSS || bossTemplate.getBossName() == null)
							continue;
						final Goal altBt = bossTemplate;
						String bossGoalId = reuseOrCreateInSection(sectionId, altBt, preExisting,
							() -> addBossGoal(altBt.getBossName(), altBt.getTargetValue()));
						if (bossGoalId == null) continue;
						gestureGoalIds.add(bossGoalId);
						api.addOrRequirement(unlockGoalId, bossGoalId);
						try
						{
							api.addTagWithCategory(bossGoalId, unlockTagLabel, TagCategory.QUEST.name());
						}
						catch (Exception e)
						{
							log.warn("addDiaryGoalWithPrereqs: failed to tag alt boss: {}", e.getMessage());
						}
					}
				}
			}

			// Promote leaf quests (zero active requirements) to the top.
			api.reorderingService.promoteLeafGoalsToTop();

			// Select all goals created in this gesture.
			api.replaceGoalSelection(gestureGoalIds);
		}
		finally
		{
			this.seedKeepCompleted = prevKeepCompleted;
			api.endCompound();
		}
		return gestureGoalIds;
	}

	/**
	 * Panel "Add requirements to this section" for an existing DIARY goal - the
	 * diary parallel of {@link #seedRequirementsForGoal}. Derives the area from the
	 * goal's name and the tier from its description prefix ("Easy/Medium/Hard/Elite
	 * Achievement Diary"), resolves the requirement tree, and seeds it INTO the
	 * diary goal's own section under one undo (reusing equivalents already there).
	 *
	 * <p>{@code includeMet = false} seeds only unmet requirements from live player
	 * state (needs a Client); {@code true} ("All") uses floor lookups (treat nothing
	 * as met) so the whole tree seeds deterministically and met requirements are kept
	 * inline as cards. No-op (returns 0) for non-diary goals or diaries without
	 * requirement data.
	 *
	 * @return number of prerequisite goals seeded (created or reused), excluding the root
	 */
	int seedDiaryRequirementsForGoal(String diaryGoalId, boolean includeMet)
	{
		log.debug("API.internal seedDiaryRequirementsForGoal(goalId={}, includeMet={})", diaryGoalId, includeMet);
		Goal g = api.findGoal(diaryGoalId);
		if (g == null || g.getType() != GoalType.DIARY || g.getName() == null)
		{
			return 0;
		}
		AchievementDiaryData.Tier internalTier = parseTierFromDescription(g.getDescription());
		if (internalTier == null)
		{
			return 0;
		}
		String area = g.getName();
		if (!com.goalplanner.data.DiaryRequirements.hasRequirements(area, internalTier))
		{
			return 0;
		}
		GoalPlannerApi.DiaryTier apiTier = toApiTier(internalTier);

		// "All" resolves the full tree with floor lookups (nothing met) - no Client
		// needed, deterministic. "Incomplete only" uses live player state.
		com.goalplanner.data.DiaryRequirementResolver.Resolved resolved = includeMet
			? com.goalplanner.data.DiaryRequirementResolver.resolve(
				area, internalTier,
				s -> 1,
				q -> net.runelite.api.QuestState.NOT_STARTED)
			: com.goalplanner.data.DiaryRequirementResolver.resolve(area, internalTier, api.client);
		if (resolved == null || resolved.isEmpty())
		{
			return 0;
		}

		api.clearGoalSelection();
		api.beginCompound((includeMet ? "Add all requirements: " : "Add requirements: ")
			+ area + " " + internalTier.getDisplayName());
		try
		{
			// "All" keeps the section's completed prereqs inline as cards (matches the
			// quest "All" behaviour) instead of letting them auto-archive to ghosts.
			if (includeMet)
			{
				api.setSectionAutoArchiveOverride(g.getSectionId(), Boolean.FALSE);
			}
			java.util.List<String> gesture =
				addDiaryGoalWithPrereqsCore(diaryGoalId, area, apiTier, resolved, /*keepCompleted=*/includeMet);
			return Math.max(0, gesture.size() - 1);
		}
		finally
		{
			api.endCompound();
		}
	}

	/** Parse the internal diary tier from a diary goal's "&lt;Tier&gt; Achievement Diary" description. */
	private static AchievementDiaryData.Tier parseTierFromDescription(String description)
	{
		if (description == null)
		{
			return null;
		}
		for (AchievementDiaryData.Tier t : AchievementDiaryData.Tier.values())
		{
			if (description.startsWith(t.getDisplayName()))
			{
				return t;
			}
		}
		return null;
	}

	private static GoalPlannerApi.DiaryTier toApiTier(AchievementDiaryData.Tier tier)
	{
		switch (tier)
		{
			case EASY:   return GoalPlannerApi.DiaryTier.EASY;
			case MEDIUM: return GoalPlannerApi.DiaryTier.MEDIUM;
			case HARD:   return GoalPlannerApi.DiaryTier.HARD;
			case ELITE:  return GoalPlannerApi.DiaryTier.ELITE;
			default:     throw new IllegalArgumentException("Unknown Tier " + tier);
		}
	}

	String addCombatAchievementGoal(int caTaskId)
	{
		log.debug("API.public addCombatAchievementGoal(caTaskId={})", caTaskId);
		if (caTaskId < 0 || caTaskId > 639)
		{
			log.warn("addCombatAchievementGoal: caTaskId {} out of range [0, 639]", caTaskId);
			return null;
		}

		WikiCaRepository.CaInfo info = api.wikiCaRepository.getById(caTaskId);
		if (info == null)
		{
			log.warn("addCombatAchievementGoal: no wiki entry for task id {}", caTaskId);
			return null;
		}

		// Duplicate guard: same caTaskId or name in the default namespace.
		Goal caDup = existingInDefault(Goal.builder()
			.type(GoalType.COMBAT_ACHIEVEMENT).caTaskId(caTaskId).name(info.name).build());
		if (caDup != null)
		{
			log.info("addCombatAchievementGoal: duplicate of existing goal {} ({})",
				caDup.getId(), info.name);
			return caDup.getId();
		}

		// Tier sprite: SpriteID.CaTierSwordsSmall._0..5 = 3399..3404
		CombatAchievementData.Tier ctier = parseCaTier(info.tier);
		int tierSpriteId = ctier != null ? 3399 + ctier.ordinal() : 0;
		String description = ctier != null
			? ctier.getDisplayName() + " Combat Achievement"
			: "Combat Achievement";
		String tooltip = info.task != null && !info.task.isEmpty()
			? info.name + " - " + info.task
			: null;

		List<String> tagIds = new ArrayList<>();
		if (info.monster != null && !info.monster.isEmpty())
		{
			boolean isRaid = CombatAchievementData.isRaidBoss(info.monster);
			String tagLabel = isRaid ? CombatAchievementData.abbreviateRaid(info.monster) : info.monster;
			Tag bossTag = api.goalStore.findOrCreateSystemTag(tagLabel,
				isRaid ? TagCategory.RAID : TagCategory.BOSS);
			if (bossTag != null) tagIds.add(bossTag.getId());
			// Inherit Slayer skill tag if the monster is a known slayer task target
			if (com.goalplanner.data.SourceAttributes.isSlayerTask(info.monster))
			{
				Tag slayerTag = api.goalStore.findOrCreateSystemTag("Slayer", TagCategory.SKILLING);
				if (slayerTag != null) tagIds.add(slayerTag.getId());
			}
		}

		Goal goal = Goal.builder()
			.type(GoalType.COMBAT_ACHIEVEMENT)
			.name(info.name)
			.description(description)
			.tooltip(tooltip)
			.targetValue(1)
			.currentValue(0)
			.spriteId(tierSpriteId)
			.caTaskId(caTaskId)
			.tagIds(new ArrayList<>(tagIds))
			.defaultTagIds(new ArrayList<>(tagIds))
			.build();

		final String goalId = goal.getId();
		final String displayName = info.name;
		executeAddGoal(goal, goalId, "Add CA: " + displayName);
		log.info("addCombatAchievementGoal created: {} ({})", goalId, info.name);
		return goalId;
	}

	private static CombatAchievementData.Tier parseCaTier(String wikiTier)
	{
		if (wikiTier == null) return null;
		switch (wikiTier.toUpperCase())
		{
			case "EASY":        return CombatAchievementData.Tier.EASY;
			case "MEDIUM":      return CombatAchievementData.Tier.MEDIUM;
			case "HARD":        return CombatAchievementData.Tier.HARD;
			case "ELITE":       return CombatAchievementData.Tier.ELITE;
			case "MASTER":      return CombatAchievementData.Tier.MASTER;
			case "GRANDMASTER": return CombatAchievementData.Tier.GRANDMASTER;
			default:            return null;
		}
	}

	String addAccountGoal(String metricName, int target)
	{
		log.debug("API.public addAccountGoal(metric={}, target={})", metricName, target);
		if (metricName == null || target <= 0) return null;
		AccountMetric metric;
		try
		{
			metric = AccountMetric.valueOf(metricName);
		}
		catch (IllegalArgumentException e)
		{
			log.warn("addAccountGoal: unknown metric {}", metricName);
			return null;
		}
		// Clamp the floor only. Targets ABOVE the metric's max are allowed -
		// ceilings grow with game updates (new quests, new log slots), so an
		// over-max goal is a legitimate aspiration that simply tracks until
		// the game catches up. The UI still SUGGESTS the current ceiling
		// (AccountMetric.effectiveMaxTarget drives the Max button and the
		// "max N" prompt hint); it just doesn't enforce it.
		int clampedTarget = Math.max(metric.getMinTarget(), target);
		api.clearGoalSelection();

		// Duplicate guard: same metric + target in the default namespace.
		Goal acctDup = existingInDefault(Goal.builder()
			.type(GoalType.ACCOUNT).accountMetric(metricName).targetValue(clampedTarget).build());
		if (acctDup != null)
		{
			log.info("addAccountGoal: duplicate of existing goal {} ({} {})",
				acctDup.getId(), metricName, clampedTarget);
			return acctDup.getId();
		}

		String goalName;
		if (metric == AccountMetric.CA_POINTS)
		{
			goalName = clampedTarget + " CA (" + AccountMetric.caTierLabel(clampedTarget) + ")";
		}
		else if (metric == AccountMetric.MISC_APPROVAL)
		{
			// 127 = 100% approval. Display as percentage.
			int pct = Math.round(clampedTarget * 100f / 127f);
			goalName = pct + "% " + metric.getDisplayName();
		}
		else
		{
			goalName = clampedTarget + " " + metric.getDisplayName();
		}

		Goal goal = Goal.builder()
			.type(GoalType.ACCOUNT)
			.name(goalName)
			.description(metric.getDisplayName())
			.accountMetric(metricName)
			.targetValue(clampedTarget)
			.currentValue(0)
			.spriteId(metric.resolveSpriteForTarget(clampedTarget))
			.customColorRgb(metric.getColorRgb())
			.build();

		final String goalId = goal.getId();
		final String displayName = goalName;
		api.beginCompound("Add goal: " + displayName);
		try
		{
			executeAddGoal(goal, goalId, "Add account goal: " + displayName);
			autoLinkSkillOrItemChain(goal);
		}
		finally
		{
			api.endCompound();
		}
		log.info("addAccountGoal created: {} ({})", goalId, goalName);
		return goalId;
	}

	String addBossGoal(String bossName, int targetKills)
	{
		log.debug("API.public addBossGoal(boss={}, target={})", bossName, targetKills);
		if (bossName == null || !com.goalplanner.data.BossKillData.isKnownBoss(bossName))
		{
			log.warn("addBossGoal: unknown boss {}", bossName);
			return null;
		}
		if (targetKills < 1) return null;

		// Duplicate guard: same boss name in the default namespace.
		Goal bossDup = existingInDefault(Goal.builder().type(GoalType.BOSS).bossName(bossName).build());
		if (bossDup != null)
		{
			log.info("addBossGoal: duplicate of existing goal {} ({})", bossDup.getId(), bossName);
			return bossDup.getId();
		}

		int petItemId = com.goalplanner.data.BossKillData.getPetItemId(bossName);
		Goal goal = Goal.builder()
			.type(GoalType.BOSS)
			.name(bossName)
			.description(targetKills + " kills")
			.bossName(bossName)
			.targetValue(targetKills)
			.currentValue(0)
			.itemId(petItemId)
			.build();

		final String goalId = goal.getId();
		final String displayName = bossName;
		api.beginCompound("Add boss goal: " + displayName);
		try
		{
			executeAddGoal(goal, goalId, "Add boss goal: " + displayName);
			// Auto-tag with BOSS category.
			api.addTagWithCategory(goalId, bossName, TagCategory.BOSS.name());

			seedBossPrereqs(goalId, bossName, true);
		}
		finally
		{
			api.endCompound();
		}
		log.info("addBossGoal created: {} ({} x {})", goalId, bossName, targetKills);
		return goalId;
	}

	/**
	 * Seed a boss's prerequisite tree (skills, quests, items, account metrics,
	 * boss-kill prereqs, unlock subtrees, and OR-group alternatives) into the
	 * goal's section. Shared by {@link #addBossGoal} (includeMet=true, the full
	 * tree) and {@link #seedBossRequirementsForGoal} (includeMet=false filters
	 * requirements the player already meets). Existing goals are reused.
	 */
	private void seedBossPrereqs(String goalId, String bossName, boolean includeMet)
	{
			// Auto-seed boss prereqs (e.g. 70 Ranged + Mith Grapple for Kree'arra).
			com.goalplanner.data.BossKillData.BossPrereqs prereqs =
				com.goalplanner.data.BossKillData.getPrereqs(bossName);
			if (prereqs != null)
			{
				// Gesture-level accumulators for the priority-queue BFS.
				// Mirrors addDiaryGoalWithPrereqs / addQuestGoalWithPrereqs so
				// boss prereqs render with the same ordering (optional →
				// skills/item/account → quests) and quests chain their own
				// prereqs transitively.
				java.util.Set<net.runelite.api.Quest> bossVisited = new java.util.HashSet<>();
				java.util.Set<String> bossPreExistingGoalIds = new java.util.HashSet<>();
				for (Goal g : api.goalStore.getGoals()) bossPreExistingGoalIds.add(g.getId());
				java.util.List<String> bossGestureGoalIds = new java.util.ArrayList<>();
				bossGestureGoalIds.add(goalId);

				// Build templates for every direct prereq type EXCEPT
				// UnlockRef (which has a unique CUSTOM + optional-skill
				// subtree shape and stays in its own loop below).
				java.util.List<Goal> templates = new java.util.ArrayList<>();

				for (com.goalplanner.data.BossKillData.SkillReq sr : prereqs.skills)
				{
					// "Incomplete only" (includeMet=false): skip a level the
					// player already has, so it doesn't seed as a completed goal.
					if (!includeMet && api.client != null
						&& api.client.getRealSkillLevel(sr.skill) >= sr.level)
					{
						continue;
					}
					templates.add(Goal.builder()
						.type(GoalType.SKILL)
						.name(sr.skill.getName() + " - Level " + sr.level)
						.skillName(sr.skill.name())
						.targetValue(net.runelite.api.Experience.getXpForLevel(sr.level))
						.build());
				}
				for (net.runelite.api.Quest q : prereqs.quests)
				{
					if (!includeMet && isQuestFinished(q))
					{
						continue;
					}
					templates.add(Goal.builder()
						.type(GoalType.QUEST)
						.name(q.getName())
						.description("Quest")
						.questName(q.name())
						.targetValue(1)
						.spriteId(GoalPlannerApiImpl.QUEST_SPRITE_ID)
						.build());
				}
				for (com.goalplanner.data.BossKillData.ItemReq ir : prereqs.itemReqs)
				{
					templates.add(Goal.builder()
						.type(GoalType.ITEM_GRIND)
						.name(ir.displayName)
						.itemId(ir.itemId)
						.targetValue(ir.quantity)
						.build());
				}
				for (com.goalplanner.data.BossKillData.AccountReq ar : prereqs.accountReqs)
				{
					if (!includeMet && isAccountMet(ar.metricName, ar.target))
					{
						continue;
					}
					templates.add(Goal.builder()
						.type(GoalType.ACCOUNT)
						.name(ar.metricName)
						.accountMetric(ar.metricName)
						.targetValue(ar.target)
						.build());
				}
				for (com.goalplanner.data.BossKillData.BossReq br : prereqs.bossKills)
				{
					templates.add(Goal.builder()
						.type(GoalType.BOSS)
						.name(br.bossName)
						.description(br.killCount + " kills")
						.bossName(br.bossName)
						.targetValue(br.killCount)
						.itemId(com.goalplanner.data.BossKillData.getPetItemId(br.bossName))
						.build());
				}

				// Priority-queue BFS: orders optional → skill/item/account →
				// quest, and transitively chains each quest's own prereqs.
				seedPrereqsInto(goalId, null, bossName, templates,
					bossVisited, bossPreExistingGoalIds, bossGestureGoalIds);

				// Unlock prereqs (e.g. Mith Grapple) - CUSTOM goals with an
				// optional skill subtree; unique shape, not templated.
				for (com.goalplanner.data.BossKillData.UnlockRef unlock : prereqs.unlocks)
				{
					String unlockId = addCustomGoal(unlock.name, "Requirement");
					if (unlockId == null) continue;
					Goal unlockGoal = api.findGoal(unlockId);
					if (unlockGoal != null && unlock.itemId > 0)
					{
						unlockGoal.setItemId(unlock.itemId);
					}
					api.addRequirement(goalId, unlockId);
					try
					{
						api.addTagWithCategory(unlockId, bossName, TagCategory.QUEST.name());
					}
					catch (Exception e)
					{
						log.warn("addBossGoal: failed to tag unlock prereq: {}", e.getMessage());
					}
					// Unlock's optional skill prereqs
					for (com.goalplanner.data.BossKillData.SkillReq usr : unlock.optionalSkills)
					{
						String usId = addSkillGoal(usr.skill,
							net.runelite.api.Experience.getXpForLevel(usr.level));
						if (usId == null) continue;
						Goal sg = api.findGoal(usId);
						if (sg != null) sg.setOptional(true);
						api.addRequirement(unlockId, usId);
						try
						{
							api.addTagWithCategory(usId, unlock.name, TagCategory.QUEST.name());
						}
						catch (Exception e)
						{
							log.warn("addBossGoal: failed to tag unlock skill: {}", e.getMessage());
						}
					}
				}
				// Seed alternatives (OR-groups) as OR-prereqs on the boss
				// goal. Each Alternative contributes skill / quest /
				// account / boss-kill goals, all linked via
				// addOrRequirement so the boss unlocks when ANY alt is
				// satisfied (in addition to AND-prereqs above). Mirrors
				// the diary-unlock alternative path.
				for (com.goalplanner.data.BossKillData.Alternative alt : prereqs.alternatives)
				{
					String altLabel = alt.label;
					if (altLabel != null && altLabel.length() > 30)
					{
						altLabel = altLabel.substring(0, 30);
					}
					// Skill alternatives
					for (com.goalplanner.data.BossKillData.SkillReq sr : alt.skills)
					{
						String skillGoalId = addSkillGoal(sr.skill,
							net.runelite.api.Experience.getXpForLevel(sr.level));
						if (skillGoalId == null) continue;
						bossGestureGoalIds.add(skillGoalId);
						api.addOrRequirement(goalId, skillGoalId);
						try
						{
							api.addTagWithCategory(skillGoalId,
								altLabel != null ? altLabel : bossName,
								TagCategory.QUEST.name());
						}
						catch (Exception e)
						{
							log.warn("addBossGoal: failed to tag alt skill: {}", e.getMessage());
						}
					}
					// Account metric alternatives
					for (com.goalplanner.data.BossKillData.AccountReq ar : alt.accounts)
					{
						String accountGoalId = addAccountGoal(ar.metricName, ar.target);
						if (accountGoalId == null) continue;
						bossGestureGoalIds.add(accountGoalId);
						api.addOrRequirement(goalId, accountGoalId);
						try
						{
							api.addTagWithCategory(accountGoalId,
								altLabel != null ? altLabel : bossName,
								TagCategory.QUEST.name());
						}
						catch (Exception e)
						{
							log.warn("addBossGoal: failed to tag alt account: {}", e.getMessage());
						}
					}
					// Boss-kill alternatives
					for (com.goalplanner.data.BossKillData.BossReq br : alt.bosses)
					{
						String altBossId = addBossGoal(br.bossName, br.killCount);
						if (altBossId == null) continue;
						bossGestureGoalIds.add(altBossId);
						api.addOrRequirement(goalId, altBossId);
						try
						{
							api.addTagWithCategory(altBossId,
								altLabel != null ? altLabel : bossName,
								TagCategory.BOSS.name());
						}
						catch (Exception e)
						{
							log.warn("addBossGoal: failed to tag alt boss: {}", e.getMessage());
						}
					}
					// Quest alternatives - chain transitively like AND-quests
					for (net.runelite.api.Quest q : alt.quests)
					{
						if (!bossVisited.add(q)) continue;
						String questGoalId = insertQuestGoal(q);
						if (questGoalId == null) continue;
						bossGestureGoalIds.add(questGoalId);
						api.addOrRequirement(goalId, questGoalId);
						try
						{
							api.addTagWithCategory(questGoalId,
								altLabel != null ? altLabel : bossName,
								TagCategory.QUEST.name());
						}
						catch (Exception e)
						{
							log.warn("addBossGoal: failed to tag alt quest: {}", e.getMessage());
						}
						// Recursively seed this quest's own requirements
						// via the AND-edge path (prereq chain inside an
						// OR-branch is still AND-gated - you need the
						// quest's own skills to do the quest).
						com.goalplanner.data.QuestRequirementResolver.Resolved qr =
							api.resolveQuestRequirements(q);
						if (qr != null && !qr.isEmpty())
						{
							seedPrereqsInto(questGoalId, q, qr.templates,
								bossVisited, bossPreExistingGoalIds, bossGestureGoalIds);
						}
					}
				}
			}
	}

	/**
	 * Create a bite-sized repeatable goal from a long-term one: "300k Woodcutting
	 * XP a day" off a 99 Woodcutting goal, or "20 General Graardor kills a week"
	 * off a Bandos chestplate goal.
	 *
	 * <p>The derived goal is an ordinary auto-tracked SKILL or BOSS goal with a
	 * {@code repeatChunk}, so every existing tracker handles it unchanged. Its
	 * target starts at {@code live + chunk} and re-bases there again at each
	 * rollover - see {@link com.goalplanner.service.RepeatResetService}.
	 *
	 * <p>Reads live client state (current XP / kill count), matching how
	 * {@code GoalDialogFactory} resolves relative skill targets. If that read
	 * fails, this returns null rather than creating a goal with a bogus target:
	 * a BOSS goal targeting 20 when the player is already at 1,847 lifetime kills
	 * would complete itself on the next tick.
	 *
	 * @param activityName boss name for an item-derived goal; ignored for SKILL parents
	 * @return the new goal's id, or null if it could not be built
	 */
	String createDerivedRepeatGoal(String parentGoalId, com.goalplanner.model.RepeatPeriod period,
		int chunk, String activityName)
	{
		log.debug("API.public createDerivedRepeatGoal(parent={}, period={}, chunk={}, activity={})",
			parentGoalId, period, chunk, activityName);
		if (parentGoalId == null || period == null || !period.isRepeating() || chunk <= 0)
		{
			return null;
		}
		Goal parent = api.findGoal(parentGoalId);
		if (parent == null)
		{
			return null;
		}

		Goal derived = parent.getType() == GoalType.SKILL && parent.getSkillName() != null
			? buildSkillChunk(parent, period, chunk)
			: buildActivityChunk(parent, period, chunk, activityName);
		if (derived == null)
		{
			return null;
		}

		final String goalId = derived.getId();
		executeAddGoal(derived, goalId, "Add " + period.getLabel().toLowerCase(java.util.Locale.ROOT)
			+ " goal: " + derived.getName());
		// Pull it straight into the Repeatable section rather than leaving it in
		// the default bucket until something else triggers a reconcile.
		api.goalStore.reconcileDerivedSections();
		log.info("createDerivedRepeatGoal created: {} ({})", goalId, derived.getName());
		return goalId;
	}

	/** "Woodcutting +300,000 XP", tracked as an ordinary SKILL goal. */
	private Goal buildSkillChunk(Goal parent, com.goalplanner.model.RepeatPeriod period, int chunk)
	{
		final net.runelite.api.Skill skill;
		try
		{
			skill = net.runelite.api.Skill.valueOf(parent.getSkillName());
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
		int currentXp;
		try
		{
			if (api.client == null) return null;
			currentXp = api.client.getSkillExperience(skill);
		}
		catch (Throwable t)
		{
			log.warn("createDerivedRepeatGoal: could not read {} XP: {}", skill, t.toString());
			return null;
		}
		int target = com.goalplanner.ui.RelativeTargetResolver.resolveSkillXp(currentXp, chunk);
		if (target < 0)
		{
			return null;
		}
		return Goal.builder()
			.type(GoalType.SKILL)
			.skillName(parent.getSkillName())
			.name(skill.getName() + " +" + com.goalplanner.util.FormatUtil.formatNumber(chunk) + " XP")
			.description(period.getLabel())
			.targetValue(target)
			.currentValue(currentXp)
			.repeatEvery(period)
			.repeatChunk(chunk)
			.derivedFromGoalId(parent.getId())
			.build();
	}

	/** "General Graardor x20", tracked as an ordinary BOSS goal. */
	private Goal buildActivityChunk(Goal parent, com.goalplanner.model.RepeatPeriod period,
		int chunk, String activityName)
	{
		if (activityName == null
			|| !com.goalplanner.data.BossKillData.isKnownBoss(activityName))
		{
			return null;
		}
		int varpId = com.goalplanner.data.BossKillData.getVarpId(activityName);
		if (varpId < 0)
		{
			return null;
		}
		int currentKc;
		try
		{
			if (api.client == null) return null;
			currentKc = api.client.getVarpValue(varpId);
		}
		catch (Throwable t)
		{
			log.warn("createDerivedRepeatGoal: could not read {} kill count: {}",
				activityName, t.toString());
			return null;
		}
		if (currentKc < 0)
		{
			return null;
		}
		return Goal.builder()
			.type(GoalType.BOSS)
			.bossName(activityName)
			.name(activityName + " x" + chunk)
			.description(period.getLabel() + " - " + chunk + " kills")
			.targetValue(currentKc + chunk)
			.currentValue(currentKc)
			.repeatEvery(period)
			.repeatChunk(chunk)
			.derivedFromGoalId(parent.getId())
			.build();
	}

	String addCustomGoal(String name, String description)
	{
		log.debug("API.public addCustomGoal(name={}, description={})", name, description);
		if (name == null || name.trim().isEmpty()) return null;
		String trimmedName = name.trim();
		// Duplicate guard: same name in the default namespace.
		Goal existing = existingInDefault(Goal.builder().type(GoalType.CUSTOM).name(trimmedName).build());
		if (existing != null)
		{
			log.info("addCustomGoal: duplicate of existing goal {} ({})", existing.getId(), trimmedName);
			return existing.getId();
		}
		Goal goal = Goal.builder()
			.type(GoalType.CUSTOM)
			.name(trimmedName)
			.description(description != null ? description.trim() : "")
			.targetValue(1)
			.currentValue(0)
			.build();
		// Undoable. Wrap in a Command that adds/removes the same
		// Goal entity (preserving id) so redo restores the exact same goal
		// and any later commands referencing it still resolve.
		final String goalId = goal.getId();
		executeAddGoal(goal, goalId, "Add goal: " + trimmedName);
		log.info("addCustomGoal created: {} ({})", goalId, trimmedName);
		return goalId;
	}

	/**
	 * Duplicate goals into a target section as independent copies (new ids, fresh
	 * progress). Relations AMONG the duplicated set are preserved (remapped to the
	 * new ids); edges to goals outside the set are dropped. A source is skipped if
	 * the target section's namespace already contains an equivalent (per-section
	 * no-duplicates - each section is its own bucket). One undo reverses the whole
	 * gesture.
	 *
	 * @return the new goal ids (empty if nothing was duplicated)
	 */
	java.util.List<String> duplicateGoalsToSection(java.util.Collection<String> goalIds, String targetSectionId)
	{
		log.debug("API.internal duplicateGoalsToSection(n={}, target={})",
			goalIds == null ? 0 : goalIds.size(), targetSectionId);
		if (goalIds == null || goalIds.isEmpty()) return java.util.Collections.emptyList();
		if (api.goalStore.findSection(targetSectionId) == null) return java.util.Collections.emptyList();

		final java.util.List<Goal> sources = new java.util.ArrayList<>();
		for (String id : goalIds)
		{
			Goal g = api.findGoal(id);
			if (g != null) sources.add(g);
		}
		if (sources.isEmpty()) return java.util.Collections.emptyList();

		final java.util.List<String> createdGoalIds = new java.util.ArrayList<>();
		api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				createdGoalIds.clear();
				java.util.Map<String, String> oldToNew = new java.util.HashMap<>();
				// Pass 1: create copies, honoring per-section dedup against the target.
				for (Goal src : sources)
				{
					if (api.goalStore.findEquivalentInNamespace(targetSectionId, src) != null) continue;
					Goal copy = copyGoalInto(src, targetSectionId);
					api.goalStore.addGoal(copy);
					createdGoalIds.add(copy.getId());
					oldToNew.put(src.getId(), copy.getId());
				}
				// Pass 2: rewire relations that fall WITHIN the duplicated set.
				for (Goal src : sources)
				{
					String newFrom = oldToNew.get(src.getId());
					if (newFrom == null) continue;
					if (src.getRequiredGoalIds() != null)
					{
						for (String req : src.getRequiredGoalIds())
						{
							if (oldToNew.containsKey(req)) api.goalStore.addRequirement(newFrom, oldToNew.get(req));
						}
					}
					if (src.getOrRequiredGoalIds() != null)
					{
						for (String req : src.getOrRequiredGoalIds())
						{
							if (oldToNew.containsKey(req)) api.goalStore.addOrRequirement(newFrom, oldToNew.get(req));
						}
					}
				}
				return !createdGoalIds.isEmpty();
			}
			@Override public boolean revert()
			{
				for (String id : createdGoalIds) api.goalStore.removeGoal(id);
				return true;
			}
			@Override public String getDescription()
			{
				return "Duplicate " + sources.size() + " goal(s) to section";
			}
		});
		return new java.util.ArrayList<>(createdGoalIds);
	}

	/**
	 * Build an independent copy of a goal placed in {@code sectionId}: same
	 * definition fields + tags, but a fresh id and zeroed progress (the trackers
	 * re-evaluate it against the account). Relations are NOT copied here - the
	 * caller rewires them.
	 */
	private Goal copyGoalInto(Goal src, String sectionId)
	{
		Goal copy = Goal.builder()
			.type(src.getType())
			.name(src.getName())
			.description(src.getDescription())
			.targetValue(src.getTargetValue())
			.skillName(src.getSkillName())
			.questName(src.getQuestName())
			.accountMetric(src.getAccountMetric())
			.bossName(src.getBossName())
			.varbitId(src.getVarbitId())
			.itemId(src.getItemId())
			.spriteId(src.getSpriteId())
			.tooltip(src.getTooltip())
			.caTaskId(src.getCaTaskId())
			.customColorRgb(src.getCustomColorRgb())
			.optional(src.isOptional())
			.autoSeeded(src.isAutoSeeded())
			.wikiUrl(src.getWikiUrl())
			.inventorySetup(src.getInventorySetup())
			.sectionId(sectionId)
			.build();
		if (src.getTagIds() != null)
		{
			copy.setTagIds(new java.util.ArrayList<>(src.getTagIds()));
		}
		return copy;
	}

	/**
	 * Execute the standard "add this goal" command: insert {@code goal} (a no-op
	 * if a goal with its id already exists), reverting by removing it and clearing
	 * it from the selection. {@code description} is the undo-stack label.
	 */
	private boolean executeAddGoal(Goal goal, String goalId, String description)
	{
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override
			public boolean apply()
			{
				if (api.findGoal(goalId) != null) return false;
				api.goalStore.addGoal(goal);
				return true;
			}

			@Override
			public boolean revert()
			{
				api.goalStore.removeGoal(goalId);
				api.selectedGoalIds.remove(goalId);
				return true;
			}

			@Override
			public String getDescription() { return description; }
		});
	}
}
