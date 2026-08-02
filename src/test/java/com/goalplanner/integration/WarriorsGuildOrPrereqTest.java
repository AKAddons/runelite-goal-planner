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
import com.goalplanner.tracker.AccountTracker;
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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Focused tests for Warriors Guild Entry OR-prereq behavior.
 * Validates that all 4 goals (unlock + 3 alternatives) are seeded
 * correctly and that each of the 3 completion paths clears all 4.
 */
class WarriorsGuildOrPrereqTest
{
	private GoalStore store;
	private GoalPlannerApiImpl api;

	@BeforeEach
	void setUp()
	{
		ConfigManager configManager = InMemoryConfigManager.create();
		store = new GoalStore(configManager, new com.google.gson.Gson());
		store.load();

		MockGameState freshState = new MockGameState();
		Client client = MockClientFactory.createClient(freshState);

		ItemManager itemManager = mock(ItemManager.class);
		WikiCaRepository wikiCaRepository = mock(WikiCaRepository.class);
		GoalReorderingService reorderingService = new GoalReorderingService(store);
		api = new GoalPlannerApiImpl(store, reorderingService, itemManager, wikiCaRepository, client);
	}

	/** Seed the Falador Hard diary and return only the 4 Warriors Guild goals. */
	private List<Goal> seedAndGetWarriorsGuildGoals()
	{
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Falador", AchievementDiaryData.Tier.HARD,
			skill -> 1,
			quest -> QuestState.NOT_STARTED);

		api.addDiaryGoalWithPrereqs(
			"Falador", GoalPlannerApi.DiaryTier.HARD, resolved);

		return store.getGoals().stream()
			.filter(g ->
				("Warriors Guild Entry".equals(g.getName()) && g.getType() == GoalType.CUSTOM)
				|| ("ATT_STR_COMBINED".equals(g.getAccountMetric()) && g.getType() == GoalType.ACCOUNT)
				|| (g.getType() == GoalType.SKILL && "ATTACK".equals(g.getSkillName())
					&& g.getTargetValue() == Experience.getXpForLevel(99))
				|| (g.getType() == GoalType.SKILL && "STRENGTH".equals(g.getSkillName())
					&& g.getTargetValue() == Experience.getXpForLevel(99)))
			.collect(Collectors.toList());
	}

	private Goal findByName(List<Goal> goals, String name)
	{
		return goals.stream()
			.filter(g -> name.equals(g.getName()))
			.findFirst().orElse(null);
	}

	private Goal findByMetric(List<Goal> goals, String metric)
	{
		return goals.stream()
			.filter(g -> metric.equals(g.getAccountMetric()))
			.findFirst().orElse(null);
	}

	private Goal findSkill(List<Goal> goals, String skillName)
	{
		return goals.stream()
			.filter(g -> g.getType() == GoalType.SKILL && skillName.equals(g.getSkillName()))
			.findFirst().orElse(null);
	}

	// ================================================================
	// Setup verification
	// ================================================================

	@Test
	@DisplayName("an already-satisfied OR-alternative (99 Attack) skips the whole unlock")
	void satisfiedAlternativeSkipsUnlock()
	{
		// "Incomplete only" against a player with 99 Attack: the Warriors Guild
		// unlock's OR-block is satisfied, so it must NOT be seeded at all (the
		// bug — it was always seeded and auto-completed). Other Falador Hard
		// requirements may still resolve; we only assert the WG unlock is gone.
		java.util.function.ToIntFunction<Skill> has99Attack =
			s -> s == Skill.ATTACK ? 99 : 1;

		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Falador", AchievementDiaryData.Tier.HARD,
			has99Attack,
			quest -> QuestState.NOT_STARTED,
			metric -> 0);

		assertTrue(resolved.unlocks.stream().noneMatch(u -> "Warriors Guild Entry".equals(u.name)),
			"unlock should be skipped when an alternative is already met");
	}

	@Test
	@DisplayName("with no alternative met (all stats low) the unlock is still seeded")
	void unmetAlternativesStillSeedUnlock()
	{
		DiaryRequirementResolver.Resolved resolved = DiaryRequirementResolver.resolve(
			"Falador", AchievementDiaryData.Tier.HARD,
			skill -> 1,
			quest -> QuestState.NOT_STARTED,
			metric -> 0);

		assertTrue(resolved.unlocks.stream().anyMatch(u -> "Warriors Guild Entry".equals(u.name)),
			"unlock should seed when no alternative is met");
	}

	@Test
	@DisplayName("seeding creates exactly 4 Warriors Guild goals: unlock + 3 OR-alternatives")
	void seedsAll4Goals()
	{
		List<Goal> wgGoals = seedAndGetWarriorsGuildGoals();

		assertEquals(4, wgGoals.size(),
			"should have 4 WG goals, got: " + wgGoals.stream()
				.map(g -> g.getName() + " (" + g.getType() + ")")
				.collect(Collectors.joining(", ")));

		Goal unlock = findByName(wgGoals, "Warriors Guild Entry");
		Goal combined = findByMetric(wgGoals, "ATT_STR_COMBINED");
		Goal attack99 = findSkill(wgGoals, "ATTACK");
		Goal strength99 = findSkill(wgGoals, "STRENGTH");

		assertNotNull(unlock, "Warriors Guild Entry unlock should exist");
		assertNotNull(combined, "Att + Str Combined goal should exist");
		assertNotNull(attack99, "Attack 99 goal should exist");
		assertNotNull(strength99, "Strength 99 goal should exist");

		// Verify OR-edges on the unlock
		assertEquals(3, unlock.getOrRequiredGoalIds().size(),
			"unlock should have 3 OR-prereqs");
		assertTrue(unlock.getOrRequiredGoalIds().contains(combined.getId()));
		assertTrue(unlock.getOrRequiredGoalIds().contains(attack99.getId()));
		assertTrue(unlock.getOrRequiredGoalIds().contains(strength99.getId()));

		// All 4 should start incomplete
		for (Goal g : wgGoals)
		{
			assertFalse(g.isComplete(), "should start incomplete: " + g.getName());
		}
	}

	// ================================================================
	// Path 1: 65 Attack + 65 Strength = 130 combined clears all 4
	// ================================================================

	@Test
	@DisplayName("65 Attack + 65 Strength (130 combined) completes all 4 Warriors Guild goals")
	void combined130ClearsAll4()
	{
		List<Goal> wgGoals = seedAndGetWarriorsGuildGoals();

		MockGameState state = new MockGameState()
			.skillXp(Skill.ATTACK, Experience.getXpForLevel(65))
			.skillXp(Skill.STRENGTH, Experience.getXpForLevel(65));

		Client client = MockClientFactory.createClient(state);

		// Run both trackers: SkillTracker for Attack/Strength, AccountTracker for combined
		new SkillTracker(client, api).checkGoals(store.getGoals());
		new AccountTracker(client, api).checkGoals(store.getGoals());

		// Combined goal should be complete (130 >= 130)
		Goal combined = findByMetric(wgGoals, "ATT_STR_COMBINED");
		assertEquals(130, combined.getCurrentValue());
		assertTrue(combined.isComplete(), "Att + Str Combined should be complete at 130");

		// Unlock should auto-complete via OR-prereq
		Goal unlock = findByName(wgGoals, "Warriors Guild Entry");
		assertTrue(unlock.isComplete(),
			"Warriors Guild Entry should auto-complete when combined reaches 130");

		// Attack 99 and Strength 99 goals get progress but don't complete
		Goal attack99 = findSkill(wgGoals, "ATTACK");
		Goal strength99 = findSkill(wgGoals, "STRENGTH");
		assertFalse(attack99.isComplete(), "Attack 99 should not be complete at 65");
		assertFalse(strength99.isComplete(), "Strength 99 should not be complete at 65");

		// Reconcile: completed goals move to Completed section
		store.reconcileDerivedSections();
		String completedId = store.getCompletedSection().getId();
		assertEquals(completedId, combined.getSectionId());
		assertEquals(completedId, unlock.getSectionId());
	}

	// ================================================================
	// Path 2: 99 Strength clears all 4
	// ================================================================

	@Test
	@DisplayName("99 Strength alone completes Strength 99 + unlock (combined and Attack 99 stay incomplete)")
	void strength99ClearsUnlock()
	{
		List<Goal> wgGoals = seedAndGetWarriorsGuildGoals();

		MockGameState state = new MockGameState()
			.skillXp(Skill.STRENGTH, Experience.getXpForLevel(99));

		Client client = MockClientFactory.createClient(state);
		new SkillTracker(client, api).checkGoals(store.getGoals());

		Goal strength99 = findSkill(wgGoals, "STRENGTH");
		assertTrue(strength99.isComplete(), "Strength 99 should be complete");

		Goal unlock = findByName(wgGoals, "Warriors Guild Entry");
		assertTrue(unlock.isComplete(),
			"Warriors Guild Entry should auto-complete when 99 Strength OR-prereq completes");

		// The other alternatives don't complete
		Goal attack99 = findSkill(wgGoals, "ATTACK");
		Goal combined = findByMetric(wgGoals, "ATT_STR_COMBINED");
		assertFalse(attack99.isComplete(), "Attack 99 should not be complete");
		// Combined gets partial credit (1 + 99 = 100) but doesn't meet 130
		assertFalse(combined.isComplete(), "Combined should not be complete at 100");

		// Reconcile
		store.reconcileDerivedSections();
		String completedId = store.getCompletedSection().getId();
		assertEquals(completedId, strength99.getSectionId());
		assertEquals(completedId, unlock.getSectionId());
	}

	// ================================================================
	// Path 3: 99 Attack clears all 4
	// ================================================================

	@Test
	@DisplayName("99 Attack alone completes Attack 99 + unlock (combined and Strength 99 stay incomplete)")
	void attack99ClearsUnlock()
	{
		List<Goal> wgGoals = seedAndGetWarriorsGuildGoals();

		MockGameState state = new MockGameState()
			.skillXp(Skill.ATTACK, Experience.getXpForLevel(99));

		Client client = MockClientFactory.createClient(state);
		new SkillTracker(client, api).checkGoals(store.getGoals());

		Goal attack99 = findSkill(wgGoals, "ATTACK");
		assertTrue(attack99.isComplete(), "Attack 99 should be complete");

		Goal unlock = findByName(wgGoals, "Warriors Guild Entry");
		assertTrue(unlock.isComplete(),
			"Warriors Guild Entry should auto-complete when 99 Attack OR-prereq completes");

		// The other alternatives don't complete
		Goal strength99 = findSkill(wgGoals, "STRENGTH");
		Goal combined = findByMetric(wgGoals, "ATT_STR_COMBINED");
		assertFalse(strength99.isComplete(), "Strength 99 should not be complete");
		assertFalse(combined.isComplete(), "Combined should not be complete at 100");

		// Reconcile
		store.reconcileDerivedSections();
		String completedId = store.getCompletedSection().getId();
		assertEquals(completedId, attack99.getSectionId());
		assertEquals(completedId, unlock.getSectionId());
	}

	// ================================================================
	// Undo removes all 4
	// ================================================================

	@Test
	@DisplayName("undo removes all 4 Warriors Guild goals")
	void undoRemovesAll4()
	{
		List<Goal> wgGoals = seedAndGetWarriorsGuildGoals();
		assertEquals(4, wgGoals.size());

		int totalBefore = store.getGoals().size();
		assertTrue(totalBefore > 4, "should have diary + other prereqs too");

		assertTrue(api.undo());

		// ALL goals removed (diary + all prereqs including WG)
		assertEquals(0, store.getGoals().size(),
			"all goals should be removed after undo");
	}
}
