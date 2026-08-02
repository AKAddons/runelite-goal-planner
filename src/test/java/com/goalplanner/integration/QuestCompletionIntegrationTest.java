package com.goalplanner.integration;

import com.goalplanner.api.GoalPlannerApiImpl;
import com.goalplanner.data.QuestRequirementResolver;
import com.goalplanner.data.WikiCaRepository;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.persistence.GoalStore;
import com.goalplanner.service.GoalReorderingService;
import com.goalplanner.testsupport.InMemoryConfigManager;
import com.goalplanner.testsupport.MockClientFactory;
import com.goalplanner.testsupport.MockGameState;
import com.goalplanner.tracker.QuestTracker;
import com.goalplanner.tracker.SkillTracker;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Integration tests: simulate quest completion flows from fresh account
 * through skill training, prerequisite quest completion, and final quest
 * turn-in. Tests range from simple (no prereqs) to deep transitive chains.
 */
class QuestCompletionIntegrationTest
{
	private GoalStore store;
	private GoalPlannerApiImpl api;

	@BeforeEach
	void setUp()
	{
		ConfigManager configManager = InMemoryConfigManager.create();
		store = new GoalStore(configManager, new com.google.gson.Gson());
		store.load();

		// Fresh account Client for quest prereq resolution
		MockGameState freshState = new MockGameState();
		Client client = MockClientFactory.createClient(freshState);

		ItemManager itemManager = mock(ItemManager.class);
		WikiCaRepository wikiCaRepository = mock(WikiCaRepository.class);
		GoalReorderingService reorderingService = new GoalReorderingService(store);
		api = new GoalPlannerApiImpl(store, reorderingService, itemManager, wikiCaRepository, client);
	}

	private boolean runTrackers(MockGameState state)
	{
		Client client = MockClientFactory.createClient(state);
		boolean changed = false;
		changed |= new SkillTracker(client, api).checkGoals(store.getGoals());
		changed |= new QuestTracker(client, api).checkGoals(store.getGoals());
		return changed;
	}

	private List<Goal> goalsOfType(GoalType type)
	{
		return store.getGoals().stream()
			.filter(g -> g.getType() == type)
			.collect(Collectors.toList());
	}

	private Set<String> questNames()
	{
		return goalsOfType(GoalType.QUEST).stream()
			.map(Goal::getQuestName)
			.collect(Collectors.toSet());
	}

	private Goal findQuestGoal(Quest quest)
	{
		return store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.QUEST
				&& quest.name().equals(g.getQuestName()))
			.findFirst().orElse(null);
	}

	private Goal findSkillGoal(Skill skill)
	{
		return store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.SKILL
				&& skill.name().equals(g.getSkillName()))
			.findFirst().orElse(null);
	}

	// ================================================================
	// Simple: Lost City (skills only, no quest prereqs)
	// Requires: Crafting 31, Woodcutting 36
	// ================================================================

	@Nested
	@DisplayName("Lost City (skill-only prerequisites)")
	class LostCityTests
	{
		@Test
		@DisplayName("seeds skill goals for Crafting 31 and Woodcutting 36")
		void seedsSkillGoals()
		{
			QuestRequirementResolver.Resolved resolved = QuestRequirementResolver.resolve(
				Quest.LOST_CITY,
				skill -> 1,
				quest -> QuestState.NOT_STARTED);

			api.addQuestGoalWithPrereqs(Quest.LOST_CITY, resolved.templates);

			Goal questGoal = findQuestGoal(Quest.LOST_CITY);
			assertNotNull(questGoal, "Lost City quest goal should exist");

			// Should have skill prereqs
			Goal crafting = findSkillGoal(Skill.CRAFTING);
			Goal woodcutting = findSkillGoal(Skill.WOODCUTTING);
			assertNotNull(crafting, "Crafting skill goal should be seeded");
			assertNotNull(woodcutting, "Woodcutting skill goal should be seeded");

			// No quest prereqs expected
			List<Goal> questGoals = goalsOfType(GoalType.QUEST);
			assertEquals(1, questGoals.size(),
				"only Lost City itself should be a quest goal (no quest prereqs)");
		}

		@Test
		@DisplayName("complete skill training then finish quest")
		void completeSkillsThenQuest()
		{
			QuestRequirementResolver.Resolved resolved = QuestRequirementResolver.resolve(
				Quest.LOST_CITY, skill -> 1, quest -> QuestState.NOT_STARTED);
			api.addQuestGoalWithPrereqs(Quest.LOST_CITY, resolved.templates);

			// Step 1: Train skills
			MockGameState trained = new MockGameState()
				.skillXp(Skill.CRAFTING, Experience.getXpForLevel(31))
				.skillXp(Skill.WOODCUTTING, Experience.getXpForLevel(36));
			runTrackers(trained);

			Goal crafting = findSkillGoal(Skill.CRAFTING);
			Goal woodcutting = findSkillGoal(Skill.WOODCUTTING);
			assertTrue(crafting.isComplete(), "Crafting 31 should be met");
			assertTrue(woodcutting.isComplete(), "Woodcutting 36 should be met");

			// Quest still incomplete (haven't done it in-game yet)
			Goal questGoal = findQuestGoal(Quest.LOST_CITY);
			assertFalse(questGoal.isComplete());

			// Step 2: Complete the quest
			MockGameState questDone = trained.copy()
				.questFinished(Quest.LOST_CITY);
			runTrackers(questDone);

			assertTrue(questGoal.isComplete(), "Lost City should be complete");

			// Reconcile sections
			store.reconcileDerivedSections();
			String completedId = store.getCompletedSection().getId();
			assertEquals(completedId, questGoal.getSectionId());
			assertEquals(completedId, crafting.getSectionId());
			assertEquals(completedId, woodcutting.getSectionId());
		}

		@Test
		@DisplayName("mid-game account with Crafting 50 only seeds Woodcutting")
		void midGameSkipsMetSkills()
		{
			QuestRequirementResolver.Resolved resolved = QuestRequirementResolver.resolve(
				Quest.LOST_CITY,
				skill -> skill == Skill.CRAFTING ? 50 : 1,
				quest -> QuestState.NOT_STARTED);

			api.addQuestGoalWithPrereqs(Quest.LOST_CITY, resolved.templates);

			assertNull(findSkillGoal(Skill.CRAFTING),
				"Crafting should be skipped (already at 50)");
			assertNotNull(findSkillGoal(Skill.WOODCUTTING),
				"Woodcutting should still be seeded");
			assertTrue(resolved.skippedSkills > 0);
		}
	}

	// ================================================================
	// Medium: Animal Magnetism (mixed skills + leaf quest prereqs)
	// Requires: Slayer 18, Crafting 19, Ranged 30, Woodcutting 35
	// Quest prereqs: The Restless Ghost, Ernest the Chicken, Priest in Peril
	// ================================================================

	@Nested
	@DisplayName("Animal Magnetism (mixed skills + quest prerequisites)")
	class AnimalMagnetismTests
	{
		@Test
		@DisplayName("seeds both skill and quest prerequisites")
		void seedsMixedPrereqs()
		{
			QuestRequirementResolver.Resolved resolved = QuestRequirementResolver.resolve(
				Quest.ANIMAL_MAGNETISM, skill -> 1, quest -> QuestState.NOT_STARTED);
			api.addQuestGoalWithPrereqs(Quest.ANIMAL_MAGNETISM, resolved.templates);

			// Should have skill goals
			List<Goal> skills = goalsOfType(GoalType.SKILL);
			assertTrue(skills.size() >= 4,
				"should have at least 4 skill prereqs");

			// Should have quest prereq goals
			Set<String> quests = questNames();
			assertTrue(quests.contains("ANIMAL_MAGNETISM"));
			assertTrue(quests.contains("THE_RESTLESS_GHOST"),
				"The Restless Ghost should be seeded");
			assertTrue(quests.contains("ERNEST_THE_CHICKEN"),
				"Ernest the Chicken should be seeded");
			assertTrue(quests.contains("PRIEST_IN_PERIL"),
				"Priest in Peril should be seeded");
		}

		@Test
		@DisplayName("incremental completion: skills first, then quests, then main quest")
		void incrementalCompletion()
		{
			QuestRequirementResolver.Resolved resolved = QuestRequirementResolver.resolve(
				Quest.ANIMAL_MAGNETISM, skill -> 1, quest -> QuestState.NOT_STARTED);
			api.addQuestGoalWithPrereqs(Quest.ANIMAL_MAGNETISM, resolved.templates);

			Goal mainQuest = findQuestGoal(Quest.ANIMAL_MAGNETISM);

			// Phase 1: Train all skills
			MockGameState afterSkills = new MockGameState()
				.skillXp(Skill.SLAYER, Experience.getXpForLevel(18))
				.skillXp(Skill.CRAFTING, Experience.getXpForLevel(19))
				.skillXp(Skill.RANGED, Experience.getXpForLevel(30))
				.skillXp(Skill.WOODCUTTING, Experience.getXpForLevel(35));
			runTrackers(afterSkills);

			for (Goal s : goalsOfType(GoalType.SKILL))
			{
				if (!s.isOptional())
				{
					assertTrue(s.isComplete(),
						"required skill should be complete: " + s.getName());
				}
			}
			assertFalse(mainQuest.isComplete());

			// Phase 2: Complete prerequisite quests
			MockGameState afterPrereqQuests = afterSkills.copy()
				.questFinished(Quest.THE_RESTLESS_GHOST)
				.questFinished(Quest.ERNEST_THE_CHICKEN)
				.questFinished(Quest.PRIEST_IN_PERIL);
			runTrackers(afterPrereqQuests);

			assertTrue(findQuestGoal(Quest.THE_RESTLESS_GHOST).isComplete());
			assertTrue(findQuestGoal(Quest.ERNEST_THE_CHICKEN).isComplete());
			assertTrue(findQuestGoal(Quest.PRIEST_IN_PERIL).isComplete());
			assertFalse(mainQuest.isComplete(), "main quest not yet done");

			// Phase 3: Complete Animal Magnetism itself
			MockGameState afterMain = afterPrereqQuests.copy()
				.questFinished(Quest.ANIMAL_MAGNETISM);
			runTrackers(afterMain);

			assertTrue(mainQuest.isComplete());

			// Verify section reconciliation
			store.reconcileDerivedSections();
			String completedId = store.getCompletedSection().getId();
			assertEquals(completedId, mainQuest.getSectionId());
		}

		@Test
		@DisplayName("account with some quests done seeds only unfinished prereqs")
		void skipsCompletedPrereqs()
		{
			QuestRequirementResolver.Resolved resolved = QuestRequirementResolver.resolve(
				Quest.ANIMAL_MAGNETISM,
				skill -> 99,  // all skills maxed
				quest -> quest == Quest.PRIEST_IN_PERIL
					? QuestState.FINISHED : QuestState.NOT_STARTED);

			api.addQuestGoalWithPrereqs(Quest.ANIMAL_MAGNETISM, resolved.templates);

			assertEquals(0, goalsOfType(GoalType.SKILL).size(),
				"no skill goals needed (all at 99)");

			Set<String> quests = questNames();
			assertFalse(quests.contains("PRIEST_IN_PERIL"),
				"Priest in Peril should be skipped (already finished)");
			assertTrue(quests.contains("THE_RESTLESS_GHOST"));
			assertTrue(quests.contains("ERNEST_THE_CHICKEN"));
		}
	}

	// ================================================================
	// Deep: Desert Treasure I (transitive quest chain)
	// Direct: Thieving 53, Firemaking 50, Magic 50, Slayer 10
	// Quest prereqs: The Dig Site, Temple of Ikov, The Tourist Trap,
	//   Troll Stronghold, Priest in Peril, Waterfall Quest
	// Each of those quests may have their own prereqs (transitive)
	// ================================================================

	@Nested
	@DisplayName("Desert Treasure I (deep transitive quest chain)")
	class DesertTreasureTests
	{
		@Test
		@DisplayName("seeds deep prerequisite tree with transitive quests")
		void seedsDeepPrereqTree()
		{
			QuestRequirementResolver.Resolved resolved = QuestRequirementResolver.resolve(
				Quest.DESERT_TREASURE_I, skill -> 1, quest -> QuestState.NOT_STARTED);
			api.addQuestGoalWithPrereqs(Quest.DESERT_TREASURE_I, resolved.templates);

			int totalGoals = store.getGoals().size();
			assertTrue(totalGoals > 10,
				"deep quest tree should produce many goals, got " + totalGoals);

			// Direct quest prereqs should all be present
			Set<String> quests = questNames();
			assertTrue(quests.contains("DESERT_TREASURE_I"));
			assertTrue(quests.contains("THE_DIG_SITE"),
				"The Dig Site should be seeded");
			assertTrue(quests.contains("PRIEST_IN_PERIL"),
				"Priest in Peril should be seeded");
			assertTrue(quests.contains("WATERFALL_QUEST"),
				"Waterfall Quest should be seeded");

			// Direct skill prereqs
			List<Goal> skills = goalsOfType(GoalType.SKILL);
			assertTrue(skills.size() >= 4,
				"should have at least 4 direct skill prereqs");

			// The main quest should have requirement edges
			Goal mainQuest = findQuestGoal(Quest.DESERT_TREASURE_I);
			assertNotNull(mainQuest);
			assertTrue(mainQuest.getRequiredGoalIds().size() > 0,
				"DT1 should have requirement links");
		}

		@Test
		@DisplayName("full completion: train all skills, complete all quests in order")
		void fullCompletion()
		{
			QuestRequirementResolver.Resolved resolved = QuestRequirementResolver.resolve(
				Quest.DESERT_TREASURE_I, skill -> 1, quest -> QuestState.NOT_STARTED);
			api.addQuestGoalWithPrereqs(Quest.DESERT_TREASURE_I, resolved.templates);

			Goal mainQuest = findQuestGoal(Quest.DESERT_TREASURE_I);

			// Phase 1: Max out all required skills at once
			MockGameState afterSkills = new MockGameState()
				.skillXp(Skill.THIEVING, Experience.getXpForLevel(53))
				.skillXp(Skill.FIREMAKING, Experience.getXpForLevel(50))
				.skillXp(Skill.MAGIC, Experience.getXpForLevel(50))
				.skillXp(Skill.SLAYER, Experience.getXpForLevel(10))
				// Cover transitive skill reqs too
				.skillXp(Skill.AGILITY, Experience.getXpForLevel(40))
				.skillXp(Skill.CRAFTING, Experience.getXpForLevel(30))
				.skillXp(Skill.FLETCHING, Experience.getXpForLevel(30))
				.skillXp(Skill.HERBLORE, Experience.getXpForLevel(30))
				.skillXp(Skill.MINING, Experience.getXpForLevel(20))
				.skillXp(Skill.SMITHING, Experience.getXpForLevel(20))
				.skillXp(Skill.ATTACK, Experience.getXpForLevel(30))
				.skillXp(Skill.PRAYER, Experience.getXpForLevel(30))
				.skillXp(Skill.RANGED, Experience.getXpForLevel(30))
				.skillXp(Skill.STRENGTH, Experience.getXpForLevel(30))
				.skillXp(Skill.HITPOINTS, Experience.getXpForLevel(30))
				.skillXp(Skill.DEFENCE, Experience.getXpForLevel(30))
				.skillXp(Skill.WOODCUTTING, Experience.getXpForLevel(30));
			runTrackers(afterSkills);

			// Phase 2: Complete ALL quest prereqs at once
			MockGameState afterQuests = afterSkills.copy();
			for (Goal g : goalsOfType(GoalType.QUEST))
			{
				if (!g.getQuestName().equals("DESERT_TREASURE_I"))
				{
					try
					{
						Quest q = Quest.valueOf(g.getQuestName());
						afterQuests = afterQuests.questFinished(q);
					}
					catch (IllegalArgumentException ignored) {}
				}
			}
			runTrackers(afterQuests);

			// All prereq quests should be done
			for (Goal g : goalsOfType(GoalType.QUEST))
			{
				if (!g.getQuestName().equals("DESERT_TREASURE_I"))
				{
					assertTrue(g.isComplete(),
						"prereq quest should be complete: " + g.getName());
				}
			}
			assertFalse(mainQuest.isComplete(), "DT1 not yet done");

			// Phase 3: Complete Desert Treasure I
			MockGameState afterDT1 = afterQuests.copy()
				.questFinished(Quest.DESERT_TREASURE_I);
			runTrackers(afterDT1);

			assertTrue(mainQuest.isComplete(), "DT1 should be complete");

			// Phase 4: Reconcile
			store.reconcileDerivedSections();
			String completedId = store.getCompletedSection().getId();
			assertEquals(completedId, mainQuest.getSectionId(),
				"DT1 should be in Completed section");

			// Count how many goals ended up completed
			long completedCount = store.getGoals().stream()
				.filter(Goal::isComplete).count();
			assertTrue(completedCount >= 10,
				"at least 10 goals should be complete in the DT1 tree");
		}

		@Test
		@DisplayName("shared prerequisites are not duplicated")
		void sharedPrereqsNotDuplicated()
		{
			QuestRequirementResolver.Resolved resolved = QuestRequirementResolver.resolve(
				Quest.DESERT_TREASURE_I, skill -> 1, quest -> QuestState.NOT_STARTED);
			api.addQuestGoalWithPrereqs(Quest.DESERT_TREASURE_I, resolved.templates);

			// Check that no quest appears twice
			List<String> allQuestNames = goalsOfType(GoalType.QUEST).stream()
				.map(Goal::getQuestName)
				.collect(Collectors.toList());
			Set<String> uniqueNames = Set.copyOf(allQuestNames);
			assertEquals(uniqueNames.size(), allQuestNames.size(),
				"each quest should appear exactly once (no duplicates)");

			// Same for skills: no duplicate skill+level combos
			List<String> allSkillKeys = goalsOfType(GoalType.SKILL).stream()
				.map(g -> g.getSkillName() + ":" + g.getTargetValue())
				.collect(Collectors.toList());
			Set<String> uniqueSkills = Set.copyOf(allSkillKeys);
			assertEquals(uniqueSkills.size(), allSkillKeys.size(),
				"each skill level should appear exactly once");
		}
	}
}
