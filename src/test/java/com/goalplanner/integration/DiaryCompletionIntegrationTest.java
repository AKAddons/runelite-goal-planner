package com.goalplanner.integration;

import com.goalplanner.api.GoalPlannerApi;
import com.goalplanner.api.GoalPlannerApiImpl;
import com.goalplanner.data.AchievementDiaryData;
import com.goalplanner.data.DiaryRequirementResolver;
import com.goalplanner.data.WikiCaRepository;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.persistence.GoalStore;
import com.goalplanner.service.GoalReorderingService;
import com.goalplanner.testsupport.InMemoryConfigManager;
import com.goalplanner.testsupport.MockClientFactory;
import com.goalplanner.testsupport.MockGameState;
import com.goalplanner.tracker.DiaryTracker;
import com.goalplanner.tracker.QuestTracker;
import com.goalplanner.tracker.SkillTracker;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemID;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test: simulate a fresh account completing the Ardougne Easy
 * diary by progressing through all prerequisites (skills + quests), then
 * completing the diary itself. Verifies that all goals move through
 * ACTIVE → COMPLETE and into the Completed section.
 *
 * <p>Ardougne Easy requires: Thieving 5, Quest Rune Mysteries, Quest Biohazard.
 * Biohazard itself requires Plague City, which requires no additional skills.
 */
class DiaryCompletionIntegrationTest
{
	private GoalStore store;
	private GoalPlannerApiImpl api;

	@BeforeEach
	void setUp()
	{
		ConfigManager configManager = InMemoryConfigManager.create();
		store = new GoalStore(configManager, new com.google.gson.Gson());
		store.load();

		// Use a Client mock for the API so resolveQuestRequirements works
		// (it needs client != null to resolve quest prereq trees).
		// Fresh account: all quests NOT_STARTED.
		MockGameState freshState = new MockGameState();
		Client client = MockClientFactory.createClient(freshState);

		ItemManager itemManager = mock(ItemManager.class);
		// Stub item compositions for item goals used in diary prereqs
		ItemComposition kqHeadComp = mock(ItemComposition.class);
		when(kqHeadComp.getName()).thenReturn("KQ head");
		when(itemManager.getItemComposition(ItemID.KQ_HEAD)).thenReturn(kqHeadComp);

		ItemComposition crystalKeyComp = mock(ItemComposition.class);
		when(crystalKeyComp.getName()).thenReturn("Crystal key");
		when(itemManager.getItemComposition(ItemID.CRYSTAL_KEY)).thenReturn(crystalKeyComp);

		WikiCaRepository wikiCaRepository = mock(WikiCaRepository.class);
		GoalReorderingService reorderingService = new GoalReorderingService(store);
		api = new GoalPlannerApiImpl(store, reorderingService, itemManager, wikiCaRepository, client);
	}

	/**
	 * Run all relevant trackers against the current goal list using the
	 * given game state. Returns true if any tracker detected a change.
	 */
	private boolean runAllTrackers(MockGameState state)
	{
		Client client = MockClientFactory.createClient(state);
		boolean changed = false;
		changed |= new SkillTracker(client, api).checkGoals(store.getGoals());
		changed |= new QuestTracker(client, api).checkGoals(store.getGoals());
		changed |= new DiaryTracker(client, api).checkGoals(store.getGoals());
		return changed;
	}

	private List<Goal> goalsOfType(GoalType type)
	{
		return store.getGoals().stream()
			.filter(g -> g.getType() == type)
			.collect(Collectors.toList());
	}

	private List<Goal> completedGoals()
	{
		return store.getGoals().stream()
			.filter(Goal::isComplete)
			.collect(Collectors.toList());
	}

	private List<Goal> activeGoals()
	{
		return store.getGoals().stream()
			.filter(g -> !g.isComplete())
			.collect(Collectors.toList());
	}

	private String completedSectionId()
	{
		return store.getCompletedSection().getId();
	}

	// ================================================================
	// Full diary lifecycle: fresh account → all prereqs → diary done
	// ================================================================

	@Test
	@DisplayName("fresh account completes Ardougne Easy diary through full prerequisite chain")
	void freshAccountCompletesArdougneEasy()
	{
		// ---- Phase 1: Resolve requirements for a brand-new account ----
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Ardougne", AchievementDiaryData.Tier.EASY,
			skill -> 1,  // all skills at level 1
			quest -> QuestState.NOT_STARTED);

		// Seed the diary goal with all prerequisites
		api.addDiaryGoalWithPrereqs(
			"Ardougne", GoalPlannerApi.DiaryTier.EASY, resolved);

		// Find the diary goal by type (ID lookup is unreliable across compounds)
		Goal diaryGoal = store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.DIARY)
			.findFirst().orElse(null);
		assertNotNull(diaryGoal, "diary goal should exist in store");
		assertTrue(diaryGoal.getRequiredGoalIds().size() > 0,
			"diary should have prerequisite links");

		List<Goal> allGoals = store.getGoals();
		assertTrue(allGoals.size() > 1,
			"should have diary + at least skill/quest prereqs");

		// Identify the prerequisite goals
		List<Goal> skillGoals = goalsOfType(GoalType.SKILL);
		List<Goal> questGoals = goalsOfType(GoalType.QUEST);

		// Ardougne Easy requires: Thieving 5, Rune Mysteries, Biohazard
		// Biohazard requires Plague City (seeded via quest prereq resolution)
		assertTrue(skillGoals.size() >= 1, "should have at least 1 skill prereq (Thieving)");
		assertTrue(questGoals.size() >= 2, "should have at least 2 quest prereqs");

		// Verify all goals start incomplete
		for (Goal g : allGoals)
		{
			assertFalse(g.isComplete(), "all goals should start incomplete: " + g.getName());
		}

		// ---- Phase 2: Fresh account, no progress ----
		MockGameState freshAccount = new MockGameState()
			.combatLevel(3)
			.totalLevel(32);

		runAllTrackers(freshAccount);

		// Nothing should be complete yet
		assertEquals(0, completedGoals().size(), "no goals complete on fresh account");

		// ---- Phase 3: Train Thieving to 5 ----
		int thievingXpTarget = Experience.getXpForLevel(5);
		MockGameState afterThieving = freshAccount.copy()
			.skillXp(Skill.THIEVING, thievingXpTarget);

		runAllTrackers(afterThieving);

		// The Thieving skill goal should now be complete
		Goal thievingGoal = skillGoals.stream()
			.filter(g -> g.getSkillName() != null && g.getSkillName().equals("THIEVING"))
			.findFirst().orElse(null);
		assertNotNull(thievingGoal, "should have a Thieving goal");
		assertTrue(thievingGoal.isComplete(),
			"Thieving goal should be complete at level 5 XP");

		// ---- Phase 4: Complete quest prerequisites ----
		// Plague City is a prereq of Biohazard, Rune Mysteries is standalone
		MockGameState afterQuests = afterThieving.copy()
			.questFinished(Quest.PLAGUE_CITY)
			.questFinished(Quest.RUNE_MYSTERIES)
			.questFinished(Quest.BIOHAZARD);

		runAllTrackers(afterQuests);

		// All quest goals should now be complete
		for (Goal q : questGoals)
		{
			assertTrue(q.isComplete(), "quest goal should be complete: " + q.getName());
		}

		// All prereqs done, but diary itself is NOT complete yet
		// (diary completion comes from the varbit, not from prereqs)
		assertFalse(diaryGoal.isComplete(),
			"diary goal should NOT be complete until varbit fires");

		// ---- Phase 5: Complete the diary in-game (varbit fires) ----
		MockGameState afterDiary = afterQuests.copy()
			.diaryComplete("Ardougne", AchievementDiaryData.Tier.EASY);

		runAllTrackers(afterDiary);

		// NOW the diary goal should be complete
		assertTrue(diaryGoal.isComplete(),
			"diary goal should be complete after varbit fires");

		// ---- Phase 6: Reconcile sections ----
		store.reconcileDerivedSections();

		// All completed goals should be in the Completed section
		String completedId = completedSectionId();
		for (Goal g : completedGoals())
		{
			assertEquals(completedId, g.getSectionId(),
				"completed goal should be in Completed section: " + g.getName());
		}

		// No active goals should remain in the Completed section
		for (Goal g : activeGoals())
		{
			assertNotEquals(completedId, g.getSectionId(),
				"active goal should NOT be in Completed section: " + g.getName());
		}
	}

	// ================================================================
	// Partial progress: skills done, quests not yet
	// ================================================================

	@Test
	@DisplayName("partial progress: skills complete but quests pending keeps diary incomplete")
	void partialProgressSkillsOnlyKeepsDiaryIncomplete()
	{
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Ardougne", AchievementDiaryData.Tier.EASY,
			skill -> 1,
			quest -> QuestState.NOT_STARTED);

		api.addDiaryGoalWithPrereqs(
			"Ardougne", GoalPlannerApi.DiaryTier.EASY, resolved);

		Goal diaryGoal = store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.DIARY)
			.findFirst().orElse(null);
		assertNotNull(diaryGoal, "diary goal should exist in store");

		// Complete all skills but no quests
		MockGameState state = new MockGameState()
			.skillXp(Skill.THIEVING, Experience.getXpForLevel(99));

		runAllTrackers(state);

		assertFalse(diaryGoal.isComplete());

		// Skill goals should be complete
		List<Goal> skills = goalsOfType(GoalType.SKILL);
		for (Goal s : skills)
		{
			assertTrue(s.isComplete(), "skill goal should be complete: " + s.getName());
		}

		// Quest goals should still be incomplete
		List<Goal> quests = goalsOfType(GoalType.QUEST);
		for (Goal q : quests)
		{
			assertFalse(q.isComplete(), "quest goal should still be incomplete: " + q.getName());
		}
	}

	// ================================================================
	// Account already meets some requirements
	// ================================================================

	@Test
	@DisplayName("mid-game account with existing skills seeds fewer prerequisites")
	void midGameAccountSeedsFewerPrereqs()
	{
		// Account already has Thieving 50 and completed Rune Mysteries
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Ardougne", AchievementDiaryData.Tier.EASY,
			skill -> skill == Skill.THIEVING ? 50 : 1,
			quest -> quest == Quest.RUNE_MYSTERIES ? QuestState.FINISHED : QuestState.NOT_STARTED);

		assertTrue(resolved.skippedSkills > 0,
			"Thieving should be skipped (already met)");
		assertTrue(resolved.skippedQuests > 0,
			"Rune Mysteries should be skipped (already finished)");

		String diaryGoalId = api.addDiaryGoalWithPrereqs(
			"Ardougne", GoalPlannerApi.DiaryTier.EASY, resolved);

		// The return value is the created DIARY goal's id (not the area name).
		Goal diaryGoal = store.getGoals().stream()
			.filter(g -> g.getId().equals(diaryGoalId)).findFirst().orElse(null);
		assertNotNull(diaryGoal, "returned id must resolve to a stored goal");
		assertEquals(GoalType.DIARY, diaryGoal.getType());

		// Should have fewer seeded goals since some were already met
		List<Goal> skillGoals = goalsOfType(GoalType.SKILL);
		assertEquals(0, skillGoals.size(),
			"no skill goals needed — Thieving already at 50");

		// Should still have Biohazard (+ Plague City prereq) but not Rune Mysteries
		List<Goal> questGoals = goalsOfType(GoalType.QUEST);
		boolean hasRuneMysteries = questGoals.stream()
			.anyMatch(g -> g.getQuestName() != null
				&& g.getQuestName().equals(Quest.RUNE_MYSTERIES.name()));
		assertFalse(hasRuneMysteries, "Rune Mysteries should be skipped");
	}

	// ================================================================
	// Incremental state transitions
	// ================================================================

	@Test
	@DisplayName("incremental state changes update goals one at a time")
	void incrementalStateChanges()
	{
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Ardougne", AchievementDiaryData.Tier.EASY,
			skill -> 1,
			quest -> QuestState.NOT_STARTED);

		api.addDiaryGoalWithPrereqs(
			"Ardougne", GoalPlannerApi.DiaryTier.EASY, resolved);

		int totalGoals = store.getGoals().size();

		// Step 1: Train Thieving partially (level 3, not 5 yet)
		MockGameState step1 = new MockGameState()
			.skillXp(Skill.THIEVING, Experience.getXpForLevel(3));
		runAllTrackers(step1);

		// Thieving goal should have progress but NOT be complete
		Goal thievingGoal = goalsOfType(GoalType.SKILL).stream()
			.filter(g -> "THIEVING".equals(g.getSkillName()))
			.findFirst().orElse(null);
		assertNotNull(thievingGoal);
		assertTrue(thievingGoal.getCurrentValue() > 0,
			"should have some XP progress");
		assertFalse(thievingGoal.isComplete(),
			"level 3 should not meet level 5 target");

		// Step 2: Finish Thieving training
		MockGameState step2 = step1.copy()
			.skillXp(Skill.THIEVING, Experience.getXpForLevel(5));
		runAllTrackers(step2);
		assertTrue(thievingGoal.isComplete(), "level 5 should meet target");

		// Step 3: Complete one quest
		MockGameState step3 = step2.copy()
			.questFinished(Quest.RUNE_MYSTERIES);
		runAllTrackers(step3);

		long completedCount = completedGoals().size();
		assertTrue(completedCount >= 2,
			"at least Thieving + Rune Mysteries should be complete");
		assertTrue(completedCount < totalGoals,
			"not everything should be complete yet");

		// Step 4: Complete remaining quests
		MockGameState step4 = step3.copy()
			.questFinished(Quest.PLAGUE_CITY)
			.questFinished(Quest.BIOHAZARD);
		runAllTrackers(step4);

		// Step 5: Diary varbit fires
		MockGameState step5 = step4.copy()
			.diaryComplete("Ardougne", AchievementDiaryData.Tier.EASY);
		runAllTrackers(step5);

		// The diary goal and its direct prereqs (skills + quests) should
		// all be complete. Recommended combat levels from quest prereq trees
		// may remain incomplete — they are optional/recommended goals.
		Goal diaryGoal = store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.DIARY)
			.findFirst().orElse(null);
		assertNotNull(diaryGoal);
		assertTrue(diaryGoal.isComplete(), "diary goal should be complete");

		// All skill and quest goals should be complete
		for (Goal g : goalsOfType(GoalType.SKILL))
		{
			// Only assert required skills (Thieving) — recommended combat
			// levels from quest prereq trees are auto-seeded but optional.
			if ("THIEVING".equals(g.getSkillName()))
			{
				assertTrue(g.isComplete(),
					"required skill should be complete: " + g.getName());
			}
		}
		for (Goal g : goalsOfType(GoalType.QUEST))
		{
			assertTrue(g.isComplete(),
				"quest should be complete: " + g.getName());
		}

		// Reconcile and verify completed goals move to Completed section
		store.reconcileDerivedSections();
		for (Goal g : completedGoals())
		{
			assertEquals(completedSectionId(), g.getSectionId(),
				"completed goal should be in Completed section: " + g.getName());
		}
	}

	// ================================================================
	// Undo: adding a diary with requirements then undoing removes all
	// ================================================================

	@Test
	@DisplayName("undo removes all seeded goals including boss prereqs")
	void undoRemovesAllSeededGoals()
	{
		// Desert Hard has boss prereq (KQ 1kc)
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Desert", AchievementDiaryData.Tier.HARD,
			skill -> 1,
			quest -> QuestState.NOT_STARTED);

		api.addDiaryGoalWithPrereqs(
			"Desert", GoalPlannerApi.DiaryTier.HARD, resolved);

		int goalsAfterAdd = store.getGoals().size();
		assertTrue(goalsAfterAdd > 1,
			"should have diary + prereqs, got " + goalsAfterAdd);

		// Verify boss goal was seeded
		boolean hasBossGoal = store.getGoals().stream()
			.anyMatch(g -> g.getType() == GoalType.BOSS
				&& "Kalphite Queen".equals(g.getBossName()));
		assertTrue(hasBossGoal, "KQ boss goal should be seeded for Desert Hard");

		// Undo the entire diary creation
		assertTrue(api.undo(), "undo should succeed");

		assertEquals(0, store.getGoals().size(),
			"all goals should be removed after undo, but " + store.getGoals().size()
			+ " remain: " + store.getGoals().stream()
				.map(g -> g.getName() + " (" + g.getType() + ")")
				.collect(Collectors.joining(", ")));
	}

	@Test
	@DisplayName("undo removes item prereqs from diary with requirements")
	void undoRemovesItemPrereqs()
	{
		// Desert Elite has item prereq (KQ Head 1x)
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Desert", AchievementDiaryData.Tier.ELITE,
			skill -> 1,
			quest -> QuestState.NOT_STARTED);

		api.addDiaryGoalWithPrereqs(
			"Desert", GoalPlannerApi.DiaryTier.ELITE, resolved);

		// Verify item goal was seeded
		boolean hasItemGoal = store.getGoals().stream()
			.anyMatch(g -> g.getType() == GoalType.ITEM_GRIND
				&& g.getItemId() == ItemID.KQ_HEAD);
		assertTrue(hasItemGoal, "KQ Head item goal should be seeded for Desert Elite");

		// Undo
		assertTrue(api.undo());

		assertEquals(0, store.getGoals().size(),
			"all goals including item prereqs should be removed after undo, but "
			+ store.getGoals().size() + " remain: " + store.getGoals().stream()
				.map(g -> g.getName() + " (" + g.getType() + ")")
				.collect(Collectors.joining(", ")));
	}

	// ================================================================
	// Fremennik Hard: GWD bosses with skill/unlock prereqs
	// ================================================================

	@Test
	@DisplayName("Fremennik Elite seeds GWD bosses with skill prereqs via addBossGoal")
	void fremennikEliteSeedsGwdBosses()
	{
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Fremennik", AchievementDiaryData.Tier.ELITE,
			skill -> 1,
			quest -> QuestState.NOT_STARTED);

		api.addDiaryGoalWithPrereqs(
			"Fremennik", GoalPlannerApi.DiaryTier.ELITE, resolved);

		// All 4 GWD bosses should be seeded as BOSS goals
		List<Goal> bossGoals = goalsOfType(GoalType.BOSS);
		java.util.Set<String> bossNames = bossGoals.stream()
			.map(Goal::getBossName).collect(java.util.stream.Collectors.toSet());
		assertTrue(bossNames.contains("Kree'arra"), "Kree'arra should be seeded");
		assertTrue(bossNames.contains("General Graardor"), "Graardor should be seeded");
		assertTrue(bossNames.contains("Commander Zilyana"), "Zilyana should be seeded");
		assertTrue(bossNames.contains("K'ril Tsutsaroth"), "K'ril should be seeded");

		// Each boss should be a requirement of the diary goal
		Goal diaryGoal = store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.DIARY)
			.findFirst().orElse(null);
		assertNotNull(diaryGoal);
		for (Goal boss : bossGoals)
		{
			assertTrue(diaryGoal.getRequiredGoalIds().contains(boss.getId()),
				boss.getBossName() + " should be a requirement of the diary");
		}

		// Kree'arra should have Mith Grapple as a requirement
		Goal kreearra = bossGoals.stream()
			.filter(g -> "Kree'arra".equals(g.getBossName()))
			.findFirst().orElse(null);
		assertNotNull(kreearra);
		boolean hasMithGrapple = store.getGoals().stream()
			.anyMatch(g -> g.getType() == GoalType.CUSTOM
				&& "Mith Grapple".equals(g.getName())
				&& kreearra.getRequiredGoalIds().contains(g.getId()));
		assertTrue(hasMithGrapple, "Kree'arra should require Mith Grapple");
	}

	// ================================================================
	// Falador Medium: Crystal Key item + Mith Grapple unlock with skills
	// ================================================================

	@Test
	@DisplayName("Falador Medium seeds Crystal Key item goal")
	void faladorMediumSeedsCrystalKey()
	{
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Falador", AchievementDiaryData.Tier.MEDIUM,
			skill -> 1,
			quest -> QuestState.NOT_STARTED);

		api.addDiaryGoalWithPrereqs(
			"Falador", GoalPlannerApi.DiaryTier.MEDIUM, resolved);

		boolean hasCrystalKey = store.getGoals().stream()
			.anyMatch(g -> g.getType() == GoalType.ITEM_GRIND
				&& g.getItemId() == ItemID.CRYSTAL_KEY);
		assertTrue(hasCrystalKey, "Crystal Key item goal should be seeded");
	}

	@Test
	@DisplayName("Falador Medium seeds Mith Grapple unlock with Fletching 59 + Smithing 59 skill prereqs")
	void faladorMediumSeedsMithGrappleWithSkills()
	{
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Falador", AchievementDiaryData.Tier.MEDIUM,
			skill -> 1,
			quest -> QuestState.NOT_STARTED);

		api.addDiaryGoalWithPrereqs(
			"Falador", GoalPlannerApi.DiaryTier.MEDIUM, resolved);

		// Mith Grapple custom goal should exist
		boolean hasMithGrapple = store.getGoals().stream()
			.anyMatch(g -> g.getType() == GoalType.CUSTOM
				&& "Mith Grapple".equals(g.getName()));
		assertTrue(hasMithGrapple, "Mith Grapple unlock goal should be seeded");

		// Fletching 59 and Smithing 59 should be seeded as optional skill prereqs
		Goal fletching59 = store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.SKILL
				&& "FLETCHING".equals(g.getSkillName())
				&& g.getTargetValue() == Experience.getXpForLevel(59))
			.findFirst().orElse(null);
		Goal smithing59 = store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.SKILL
				&& "SMITHING".equals(g.getSkillName())
				&& g.getTargetValue() == Experience.getXpForLevel(59))
			.findFirst().orElse(null);
		assertNotNull(fletching59, "Fletching 59 should be seeded for Mith Grapple");
		assertNotNull(smithing59, "Smithing 59 should be seeded for Mith Grapple");
		assertTrue(fletching59.isOptional(), "Fletching 59 should be optional");
		assertTrue(smithing59.isOptional(), "Smithing 59 should be optional");
	}

	@Test
	@DisplayName("Falador Medium undo removes Crystal Key and Mith Grapple with skill prereqs")
	void faladorMediumUndoRemovesAll()
	{
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Falador", AchievementDiaryData.Tier.MEDIUM,
			skill -> 1,
			quest -> QuestState.NOT_STARTED);

		api.addDiaryGoalWithPrereqs(
			"Falador", GoalPlannerApi.DiaryTier.MEDIUM, resolved);

		int goalsAfterAdd = store.getGoals().size();
		assertTrue(goalsAfterAdd > 5, "should have many goals, got " + goalsAfterAdd);

		assertTrue(api.undo());

		assertEquals(0, store.getGoals().size(),
			"all goals should be removed after undo, but " + store.getGoals().size()
			+ " remain: " + store.getGoals().stream()
				.map(g -> g.getName() + " (" + g.getType() + ")")
				.collect(Collectors.joining(", ")));
	}

	// ================================================================
	// Falador Hard: Warriors Guild OR-prereqs
	// ================================================================

	@Test
	@DisplayName("Falador Hard seeds Warriors Guild with 3 OR-prereqs")
	void faladorHardSeedsWarriorsGuildOrPrereqs()
	{
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Falador", AchievementDiaryData.Tier.HARD,
			skill -> 1,
			quest -> QuestState.NOT_STARTED);

		api.addDiaryGoalWithPrereqs(
			"Falador", GoalPlannerApi.DiaryTier.HARD, resolved);

		// Warriors Guild Entry unlock should exist
		Goal wgUnlock = store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.CUSTOM
				&& "Warriors Guild Entry".equals(g.getName()))
			.findFirst().orElse(null);
		assertNotNull(wgUnlock, "Warriors Guild Entry unlock should be seeded");

		// It should have OR-prereqs, not AND-prereqs for the alternatives
		assertTrue(wgUnlock.getOrRequiredGoalIds().size() >= 3,
			"should have 3 OR-prereqs (combined, 99att, 99str), got "
			+ wgUnlock.getOrRequiredGoalIds().size());
	}

	@Test
	@DisplayName("Warriors Guild auto-completes when combined Att+Str reaches 130")
	void warriorsGuildCompletesOnCombined130()
	{
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Falador", AchievementDiaryData.Tier.HARD,
			skill -> 1,
			quest -> QuestState.NOT_STARTED);

		api.addDiaryGoalWithPrereqs(
			"Falador", GoalPlannerApi.DiaryTier.HARD, resolved);

		Goal wgUnlock = store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.CUSTOM
				&& "Warriors Guild Entry".equals(g.getName()))
			.findFirst().orElse(null);
		assertNotNull(wgUnlock);
		assertFalse(wgUnlock.isComplete());

		// Simulate: Attack 65 + Strength 65 = 130
		MockGameState state = new MockGameState()
			.skillXp(Skill.ATTACK, Experience.getXpForLevel(65))
			.skillXp(Skill.STRENGTH, Experience.getXpForLevel(65));

		Client client = MockClientFactory.createClient(state);
		new com.goalplanner.tracker.AccountTracker(client, api).checkGoals(store.getGoals());

		// The ATT_STR_COMBINED account goal should now be at 130
		Goal combinedGoal = store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.ACCOUNT
				&& "ATT_STR_COMBINED".equals(g.getAccountMetric()))
			.findFirst().orElse(null);
		assertNotNull(combinedGoal, "ATT_STR_COMBINED goal should exist");
		assertEquals(130, combinedGoal.getCurrentValue());
		assertTrue(combinedGoal.isComplete());

		// The Warriors Guild unlock should auto-complete via OR-prereq
		assertTrue(wgUnlock.isComplete(),
			"Warriors Guild should auto-complete when any OR-prereq completes");
	}

	@Test
	@DisplayName("Warriors Guild auto-completes when 99 Attack alone is reached")
	void warriorsGuildCompletesOn99Attack()
	{
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Falador", AchievementDiaryData.Tier.HARD,
			skill -> 1,
			quest -> QuestState.NOT_STARTED);

		api.addDiaryGoalWithPrereqs(
			"Falador", GoalPlannerApi.DiaryTier.HARD, resolved);

		Goal wgUnlock = store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.CUSTOM
				&& "Warriors Guild Entry".equals(g.getName()))
			.findFirst().orElse(null);
		assertNotNull(wgUnlock);

		// Simulate: 99 Attack (Strength stays at 1)
		MockGameState state = new MockGameState()
			.skillXp(Skill.ATTACK, Experience.getXpForLevel(99));
		new com.goalplanner.tracker.SkillTracker(
			MockClientFactory.createClient(state), api).checkGoals(store.getGoals());

		// The Attack 99 skill goal should complete
		Goal attack99 = store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.SKILL
				&& "ATTACK".equals(g.getSkillName())
				&& g.getTargetValue() == Experience.getXpForLevel(99))
			.findFirst().orElse(null);
		assertNotNull(attack99, "Attack 99 goal should exist");
		assertTrue(attack99.isComplete());

		// Warriors Guild unlock should auto-complete via OR-prereq
		assertTrue(wgUnlock.isComplete(),
			"Warriors Guild should auto-complete when 99 Attack OR-prereq completes");
	}

	@Test
	@DisplayName("Falador Hard undo removes Warriors Guild OR-prereqs")
	void faladorHardUndoRemovesOrPrereqs()
	{
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Falador", AchievementDiaryData.Tier.HARD,
			skill -> 1,
			quest -> QuestState.NOT_STARTED);

		api.addDiaryGoalWithPrereqs(
			"Falador", GoalPlannerApi.DiaryTier.HARD, resolved);

		int goalsAfterAdd = store.getGoals().size();
		assertTrue(goalsAfterAdd > 5);

		assertTrue(api.undo());

		assertEquals(0, store.getGoals().size(),
			"all goals including OR-prereqs should be removed, but "
			+ store.getGoals().size() + " remain");
	}

	@Test
	@DisplayName("Falador Medium skips Mith Grapple when player already has 59 Fletching and Smithing")
	void faladorMediumSkipsMithGrappleWhenMet()
	{
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Falador", AchievementDiaryData.Tier.MEDIUM,
			skill -> (skill == Skill.FLETCHING || skill == Skill.SMITHING) ? 59 : 1,
			quest -> QuestState.NOT_STARTED);

		// Mith Grapple unlock should NOT appear in resolved (all skill prereqs met)
		boolean hasMithGrappleUnlock = resolved.unlocks.stream()
			.anyMatch(u -> "Mith Grapple".equals(u.name));
		assertFalse(hasMithGrappleUnlock,
			"Mith Grapple should be skipped when Fletching and Smithing are already 59+");
	}
}
