package com.goalplanner.api;

import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.model.RepeatPeriod;
import com.goalplanner.persistence.GoalStore;
import com.goalplanner.service.GoalReorderingService;
import com.goalplanner.testsupport.DataResourcesInitExtension;
import com.goalplanner.testsupport.InMemoryConfigManager;
import com.goalplanner.testsupport.MockClientFactory;
import com.goalplanner.testsupport.MockGameState;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import com.goalplanner.data.WikiCaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Deriving a repeatable goal from a long-term one.
 *
 * <p>The assertion that matters throughout: the derived goal's target is
 * {@code live + chunk}, never just {@code chunk}. Auto-tracked goals read a
 * cumulative counter, so a target of "20 kills" against a player already at
 * 1,847 lifetime kills completes itself on the next tick.
 */
@ExtendWith(DataResourcesInitExtension.class)
class CreateDerivedRepeatGoalTest
{
	private GoalStore store;

	@BeforeEach
	void setUp()
	{
		store = new GoalStore(InMemoryConfigManager.create(), new com.google.gson.Gson());
		store.load();
	}

	private GoalPlannerApiImpl apiWith(Client client)
	{
		return new GoalPlannerApiImpl(store, new GoalReorderingService(store),
			mock(ItemManager.class), mock(WikiCaRepository.class), client);
	}

	private Goal addSkillGoal(String skillName)
	{
		Goal g = Goal.builder()
			.type(GoalType.SKILL).name(skillName + " - Level 99")
			.skillName(skillName).targetValue(13_034_431).build();
		store.addGoal(g);
		return g;
	}

	private Goal addItemGoal(int itemId, String name)
	{
		Goal g = Goal.builder()
			.type(GoalType.ITEM_GRIND).name(name).itemId(itemId).targetValue(1).build();
		store.addGoal(g);
		return g;
	}

	@Nested
	@DisplayName("from a skill goal")
	class FromSkill
	{
		@Test
		@DisplayName("targets current XP plus the chunk, not the chunk alone")
		void targetsLivePlusChunk()
		{
			Client client = MockClientFactory.createClient(
				new MockGameState().skillXp(Skill.WOODCUTTING, 5_000_000));
			GoalPlannerApiImpl api = apiWith(client);
			Goal parent = addSkillGoal("WOODCUTTING");

			String id = api.createDerivedRepeatGoal(parent.getId(), RepeatPeriod.DAILY, 300_000, null);

			assertNotNull(id);
			Goal derived = store.findGoalById(id);
			assertEquals(GoalType.SKILL, derived.getType());
			assertEquals("WOODCUTTING", derived.getSkillName());
			assertEquals(5_300_000, derived.getTargetValue());
			assertEquals(5_000_000, derived.getCurrentValue());
			assertEquals(300_000, derived.getRepeatChunk());
			assertEquals(RepeatPeriod.DAILY, derived.getRepeatEvery());
			assertEquals(parent.getId(), derived.getDerivedFromGoalId());
		}

		@Test
		@DisplayName("lands in the Repeatable section immediately")
		void landsInRepeatable()
		{
			Client client = MockClientFactory.createClient(
				new MockGameState().skillXp(Skill.WOODCUTTING, 1_000));
			GoalPlannerApiImpl api = apiWith(client);
			Goal parent = addSkillGoal("WOODCUTTING");

			String id = api.createDerivedRepeatGoal(parent.getId(), RepeatPeriod.WEEKLY, 50_000, null);

			assertEquals(store.getRepeatableSection().getId(),
				store.findGoalById(id).getSectionId());
		}

		@Test
		@DisplayName("the parent is untouched - a chunk is a sibling, not a mutation")
		void parentUnchanged()
		{
			Client client = MockClientFactory.createClient(
				new MockGameState().skillXp(Skill.WOODCUTTING, 1_000));
			GoalPlannerApiImpl api = apiWith(client);
			Goal parent = addSkillGoal("WOODCUTTING");

			api.createDerivedRepeatGoal(parent.getId(), RepeatPeriod.DAILY, 10_000, null);

			assertEquals(13_034_431, parent.getTargetValue());
			assertEquals(RepeatPeriod.NONE, parent.getRepeatEvery());
		}
	}

	@Nested
	@DisplayName("from an item goal")
	class FromItem
	{
		@Test
		@DisplayName("resolves the dropping boss and targets current kills plus the chunk")
		void targetsLiveKcPlusChunk()
		{
			Client client = MockClientFactory.createClient(
				new MockGameState().bossKills("General Graardor", 1847));
			GoalPlannerApiImpl api = apiWith(client);
			Goal parent = addItemGoal(11832, "Bandos chestplate");

			String id = api.createDerivedRepeatGoal(
				parent.getId(), RepeatPeriod.DAILY, 20, "General Graardor");

			assertNotNull(id);
			Goal derived = store.findGoalById(id);
			assertEquals(GoalType.BOSS, derived.getType());
			assertEquals("General Graardor", derived.getBossName());
			assertEquals(1867, derived.getTargetValue(), "must be live KC + chunk");
			assertEquals(1847, derived.getCurrentValue());
			assertTrue(derived.isRepeating());
		}

		@Test
		@DisplayName("a boss the player has never killed still works")
		void zeroKillCount()
		{
			Client client = MockClientFactory.createClient(
				new MockGameState().bossKills("General Graardor", 0));
			GoalPlannerApiImpl api = apiWith(client);
			Goal parent = addItemGoal(11832, "Bandos chestplate");

			String id = api.createDerivedRepeatGoal(
				parent.getId(), RepeatPeriod.DAILY, 5, "General Graardor");

			assertEquals(5, store.findGoalById(id).getTargetValue());
		}

		@Test
		@DisplayName("an unknown activity name is refused rather than guessed at")
		void unknownActivity()
		{
			GoalPlannerApiImpl api = apiWith(MockClientFactory.createClient(new MockGameState()));
			Goal parent = addItemGoal(11832, "Bandos chestplate");

			assertNull(api.createDerivedRepeatGoal(
				parent.getId(), RepeatPeriod.DAILY, 20, "Not A Real Boss"));
		}
	}

	@Nested
	@DisplayName("rejections")
	class Rejections
	{
		private GoalPlannerApiImpl api()
		{
			return apiWith(MockClientFactory.createClient(
				new MockGameState().skillXp(Skill.WOODCUTTING, 1000)));
		}

		@Test
		@DisplayName("null id, null period, and NONE are all refused")
		void nullsAndNone()
		{
			Goal parent = addSkillGoal("WOODCUTTING");
			assertNull(api().createDerivedRepeatGoal(null, RepeatPeriod.DAILY, 1000, null));
			assertNull(api().createDerivedRepeatGoal(parent.getId(), null, 1000, null));
			assertNull(api().createDerivedRepeatGoal(parent.getId(), RepeatPeriod.NONE, 1000, null));
		}

		@Test
		@DisplayName("a non-positive chunk is refused - it would target the current value exactly")
		void nonPositiveChunk()
		{
			Goal parent = addSkillGoal("WOODCUTTING");
			assertNull(api().createDerivedRepeatGoal(parent.getId(), RepeatPeriod.DAILY, 0, null));
			assertNull(api().createDerivedRepeatGoal(parent.getId(), RepeatPeriod.DAILY, -5, null));
		}

		@Test
		@DisplayName("an unknown parent is refused")
		void unknownParent()
		{
			assertNull(api().createDerivedRepeatGoal("no-such-goal", RepeatPeriod.DAILY, 1000, null));
		}

		@Test
		@DisplayName("with no client there is no live value to base a target on, so nothing is created")
		void noClient()
		{
			Goal parent = addSkillGoal("WOODCUTTING");
			assertNull(apiWith(null).createDerivedRepeatGoal(
				parent.getId(), RepeatPeriod.DAILY, 1000, null));
		}
	}

	@Nested
	@DisplayName("what the panel is handed")
	class ViewMapping
	{
		@Test
		@DisplayName("the view exposes period-relative values and flags them as such")
		void viewIsPeriodRelative()
		{
			Client client = MockClientFactory.createClient(
				new MockGameState().skillXp(Skill.PRAYER, 9_800_000));
			GoalPlannerApiImpl api = apiWith(client);
			Goal parent = addSkillGoal("PRAYER");

			String id = api.createDerivedRepeatGoal(parent.getId(), RepeatPeriod.DAILY, 100_000, null);
			GoalView v = api.queryAllGoals().stream()
				.filter(gv -> gv.id.equals(id)).findFirst().orElseThrow();

			assertEquals(0, v.currentValue, "a fresh period reads 0, not 9.8M");
			assertEquals(100_000, v.targetValue, "the denominator is the chunk");
			// The flag is what stops the card running getLevelForXp over these:
			// 100,000 XP is level 49, which would render "49 Prayer / Lv 1 / 49"
			// on a level-96 account.
			assertEquals(100_000, v.repeatChunk,
				"renderers need this to know the values are not absolute XP");
		}

		@Test
		@DisplayName("an ordinary skill goal is not flagged, so level math still applies")
		void plainSkillViewUnflagged()
		{
			GoalPlannerApiImpl api = apiWith(
				MockClientFactory.createClient(new MockGameState().skillXp(Skill.PRAYER, 9_800_000)));
			Goal parent = addSkillGoal("PRAYER");
			parent.setCurrentValue(9_800_000);

			GoalView v = api.queryAllGoals().stream()
				.filter(gv -> gv.id.equals(parent.getId())).findFirst().orElseThrow();

			assertEquals(0, v.repeatChunk);
			assertEquals(13_034_431, v.targetValue, "absolute target passes through untouched");
			assertEquals(9_800_000, v.currentValue);
		}
	}

	@Nested
	@DisplayName("editing an existing repeatable goal")
	class Editing
	{
		private GoalPlannerApiImpl api;
		private Goal derived;

		private void makeChunkGoal(int chunk)
		{
			api = apiWith(MockClientFactory.createClient(
				new MockGameState().skillXp(Skill.PRAYER, 9_800_000)));
			Goal parent = addSkillGoal("PRAYER");
			derived = store.findGoalById(
				api.createDerivedRepeatGoal(parent.getId(), RepeatPeriod.DAILY, chunk, null));
		}

		@Test
		@DisplayName("changing the amount keeps the period's start, so work already done still counts")
		void amountKeepsPeriodStart()
		{
			makeChunkGoal(100_000);
			// Period ran 9.80M -> 9.90M; the player has done 30k of it.
			derived.setCurrentValue(9_830_000);

			assertTrue(api.setGoalRepeatChunk(derived.getId(), 50_000));

			assertEquals(9_850_000, derived.getTargetValue(),
				"the period still starts at 9.80M - only its end moves");
			assertEquals(30_000, derived.getDisplayCurrent(),
				"progress already made this period must survive the edit");
			assertEquals(50_000, derived.getDisplayTarget());
		}

		@Test
		@DisplayName("shrinking the amount below what is already done completes the goal")
		void shrinkingCompletes()
		{
			makeChunkGoal(100_000);
			derived.setCurrentValue(9_830_000); // 30k done

			api.setGoalRepeatChunk(derived.getId(), 20_000);

			assertTrue(derived.isComplete(),
				"30k done against a 20k ask is finished, not silently reset");
		}

		@Test
		@DisplayName("growing the amount past current progress reopens a finished goal")
		void growingReopens()
		{
			makeChunkGoal(10_000);
			derived.setCurrentValue(9_810_000);
			derived.setCompletedAt(System.currentTimeMillis());
			derived.setStatus(com.goalplanner.model.GoalStatus.COMPLETE);

			api.setGoalRepeatChunk(derived.getId(), 50_000);

			assertFalse(derived.isComplete());
			assertEquals(10_000, derived.getDisplayCurrent(), "the 10k already earned still counts");
		}

		@Test
		@DisplayName("the title follows the amount so the card does not lie")
		void titleFollowsAmount()
		{
			makeChunkGoal(100_000);
			api.setGoalRepeatChunk(derived.getId(), 50_000);
			assertEquals("Prayer +50K XP", derived.getName());
		}

		@Test
		@DisplayName("the period can be changed on a derived goal even though it is not CUSTOM")
		void periodChangeAllowedOnDerived()
		{
			makeChunkGoal(100_000);
			assertEquals(GoalType.SKILL, derived.getType());

			assertTrue(api.setGoalRepeat(derived.getId(), RepeatPeriod.WEEKLY),
				"a derived goal re-bases each period, so changing its term is well-defined");
			assertEquals(RepeatPeriod.WEEKLY, derived.getRepeatEvery());
		}

		@Test
		@DisplayName("a plain auto-tracked goal still cannot be made to repeat in place")
		void plainSkillGoalStillRefused()
		{
			api = apiWith(MockClientFactory.createClient(new MockGameState()));
			Goal plain = addSkillGoal("PRAYER");

			assertFalse(api.setGoalRepeat(plain.getId(), RepeatPeriod.DAILY),
				"no repeatChunk means no per-period baseline - still refused");
		}

		@Test
		@DisplayName("removing a derived goal leaves nothing behind in Incomplete")
		void stopRepeatingRemoves()
		{
			makeChunkGoal(100_000);
			String id = derived.getId();

			assertTrue(api.removeGoal(id));

			assertNull(store.findGoalById(id),
				"a derived goal's target is period-relative, so demoting it to a one-shot "
					+ "would drop a meaningless goal into Incomplete");
		}

		@Test
		@DisplayName("rejections: non-derived goals, no-ops and bad amounts")
		void rejections()
		{
			makeChunkGoal(100_000);
			assertFalse(api.setGoalRepeatChunk(derived.getId(), 100_000), "no-op");
			assertFalse(api.setGoalRepeatChunk(derived.getId(), 0));
			assertFalse(api.setGoalRepeatChunk(derived.getId(), -1));
			assertFalse(api.setGoalRepeatChunk("no-such-goal", 500));

			Goal plain = addSkillGoal("MAGIC");
			assertFalse(api.setGoalRepeatChunk(plain.getId(), 500),
				"a goal with no chunk has no per-period amount to change");
		}
	}
}
