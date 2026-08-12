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
import com.goalplanner.data.WikiCaRepository;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Changing an EXISTING goal's mode in place - the "Edit goal" form's
 * Total/Relative/Repeatable (boss) and One-time/Repeatable (skill) toggles.
 *
 * <p>The conversion pair exists because neither original setter could do it:
 * {@code setGoalRepeat} refuses a non-CUSTOM type that carries no chunk, and
 * {@code setGoalRepeatChunk} refuses a goal whose chunk is still zero - so a plain
 * SKILL/BOSS goal was permanently stuck in the mode it was created in. Those
 * refusals are asserted here too, so the reason for the new API stays documented.
 *
 * <p>What matters in every case: the goal actually changes mode, it lands in the
 * right section, and ONE undo puts it back exactly as it was.
 */
@ExtendWith(DataResourcesInitExtension.class)
class ConvertGoalModeTest
{
	private static final String BOSS = "Zulrah";

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

	private Goal addPlainSkillGoal()
	{
		Goal g = Goal.builder()
			.type(GoalType.SKILL).name("Woodcutting - Level 99")
			.skillName("WOODCUTTING").targetValue(13_034_431).build();
		store.addGoal(g);
		return g;
	}

	private Goal addPlainBossGoal(int targetKills)
	{
		Goal g = Goal.builder()
			.type(GoalType.BOSS).name(BOSS).description(targetKills + " kills")
			.bossName(BOSS).targetValue(targetKills).build();
		store.addGoal(g);
		return g;
	}

	@Nested
	@DisplayName("the old setters cannot change a plain goal's mode (why this API exists)")
	class WhyItExists
	{
		@Test
		@DisplayName("setGoalRepeat refuses a non-CUSTOM goal that carries no chunk")
		void setGoalRepeatRefusesPlainAutoTracked()
		{
			GoalPlannerApiImpl api = apiWith(MockClientFactory.createClient(new MockGameState()));
			Goal g = addPlainSkillGoal();

			assertFalse(api.setGoalRepeat(g.getId(), RepeatPeriod.DAILY));
			assertFalse(g.isRepeating());
		}

		@Test
		@DisplayName("setGoalRepeatChunk refuses a goal whose chunk is still zero")
		void setGoalRepeatChunkRefusesNonDerived()
		{
			GoalPlannerApiImpl api = apiWith(MockClientFactory.createClient(new MockGameState()));
			Goal g = addPlainBossGoal(500);

			assertFalse(api.setGoalRepeatChunk(g.getId(), 20));
			assertEquals(0, g.getRepeatChunk());
		}
	}

	@Nested
	@DisplayName("plain -> repeatable (convertGoalToRepeatable)")
	class ToRepeatable
	{
		@Test
		@DisplayName("a skill goal re-bases to live XP + chunk and lands in Repeatable")
		void skillConverts()
		{
			Client client = MockClientFactory.createClient(
				new MockGameState().skillXp(Skill.WOODCUTTING, 5_000_000));
			GoalPlannerApiImpl api = apiWith(client);
			Goal g = addPlainSkillGoal();

			assertTrue(api.convertGoalToRepeatable(g.getId(), RepeatPeriod.DAILY, 300_000));

			assertTrue(g.isRepeating());
			assertEquals(RepeatPeriod.DAILY, g.getRepeatEvery());
			assertEquals(300_000, g.getRepeatChunk());
			assertEquals(5_300_000, g.getTargetValue(), "live XP + chunk, never the chunk alone");
			assertEquals(5_000_000, g.getCurrentValue());
			assertEquals(0L, g.getLastPeriodKey());
			assertEquals("Woodcutting +300K XP", g.getName());
			assertEquals(store.getRepeatableSection().getId(), g.getSectionId(),
				"reconcileDerivedSections pulls a repeating goal into Repeatable");
		}

		@Test
		@DisplayName("a boss goal re-bases to live kill count + chunk and lands in Repeatable")
		void bossConverts()
		{
			Client client = MockClientFactory.createClient(
				new MockGameState().bossKills(BOSS, 1_847));
			GoalPlannerApiImpl api = apiWith(client);
			Goal g = addPlainBossGoal(2_000);

			assertTrue(api.convertGoalToRepeatable(g.getId(), RepeatPeriod.WEEKLY, 20));

			assertTrue(g.isRepeating());
			assertEquals(RepeatPeriod.WEEKLY, g.getRepeatEvery());
			assertEquals(20, g.getRepeatChunk());
			assertEquals(1_867, g.getTargetValue(), "a bare 20 would complete on the next tick");
			assertEquals(BOSS + " x20", g.getName());
			assertEquals(store.getRepeatableSection().getId(), g.getSectionId());
		}

		@Test
		@DisplayName("one undo restores the plain goal exactly - mode, target, name and section")
		void undoRestoresPlainGoal()
		{
			Client client = MockClientFactory.createClient(
				new MockGameState().bossKills(BOSS, 1_847));
			GoalPlannerApiImpl api = apiWith(client);
			Goal g = addPlainBossGoal(2_000);
			String homeSection = g.getSectionId();

			assertTrue(api.convertGoalToRepeatable(g.getId(), RepeatPeriod.DAILY, 20));
			api.undo();

			assertFalse(g.isRepeating());
			assertEquals(0, g.getRepeatChunk());
			assertEquals(2_000, g.getTargetValue());
			assertEquals(BOSS, g.getName());
			assertEquals(homeSection, g.getSectionId());
		}

		@Test
		@DisplayName("a bad period, chunk, type or unreadable counter is refused, not faked")
		void refusals()
		{
			Client client = MockClientFactory.createClient(
				new MockGameState().skillXp(Skill.WOODCUTTING, 1_000));
			GoalPlannerApiImpl api = apiWith(client);
			Goal skill = addPlainSkillGoal();

			assertFalse(api.convertGoalToRepeatable(skill.getId(), RepeatPeriod.NONE, 100));
			assertFalse(api.convertGoalToRepeatable(skill.getId(), null, 100));
			assertFalse(api.convertGoalToRepeatable(skill.getId(), RepeatPeriod.DAILY, 0));
			assertFalse(api.convertGoalToRepeatable(null, RepeatPeriod.DAILY, 100));

			Goal custom = Goal.builder().type(GoalType.CUSTOM).name("Manual").targetValue(1).build();
			store.addGoal(custom);
			assertFalse(api.convertGoalToRepeatable(custom.getId(), RepeatPeriod.DAILY, 5),
				"CUSTOM has no live counter to re-base against - setGoalRepeat handles it");

			// No client = no live counter: refuse rather than target the chunk alone.
			assertFalse(apiWith(null).convertGoalToRepeatable(
				skill.getId(), RepeatPeriod.DAILY, 100));
			assertFalse(skill.isRepeating());
		}
	}

	@Nested
	@DisplayName("repeatable -> one-time (convertGoalToOneTime)")
	class ToOneTime
	{
		private Goal repeatingSkillGoal(GoalPlannerApiImpl api)
		{
			Goal g = addPlainSkillGoal();
			assertTrue(api.convertGoalToRepeatable(g.getId(), RepeatPeriod.DAILY, 300_000));
			return g;
		}

		@Test
		@DisplayName("clears BOTH the period and the chunk, takes the absolute target, leaves Repeatable")
		void skillConvertsBack()
		{
			Client client = MockClientFactory.createClient(
				new MockGameState().skillXp(Skill.WOODCUTTING, 5_000_000));
			GoalPlannerApiImpl api = apiWith(client);
			Goal g = repeatingSkillGoal(api);

			assertTrue(api.convertGoalToOneTime(g.getId(), 13_034_431));

			assertFalse(g.isRepeating());
			assertEquals(RepeatPeriod.NONE, g.getRepeatEvery());
			assertEquals(0, g.getRepeatChunk(), "the chunk must go too, or it stays a derived slice");
			assertEquals(13_034_431, g.getTargetValue());
			assertEquals("Woodcutting - Level 99", g.getName());
			assertFalse(store.getRepeatableSection().getId().equals(g.getSectionId()),
				"a non-repeating goal is reconciled out of the Repeatable section");
		}

		@Test
		@DisplayName("a boss goal converts back with its plain title and kill-count description")
		void bossConvertsBack()
		{
			Client client = MockClientFactory.createClient(
				new MockGameState().bossKills(BOSS, 1_847));
			GoalPlannerApiImpl api = apiWith(client);
			Goal g = addPlainBossGoal(2_000);
			assertTrue(api.convertGoalToRepeatable(g.getId(), RepeatPeriod.DAILY, 20));

			assertTrue(api.convertGoalToOneTime(g.getId(), 2_500));

			assertFalse(g.isRepeating());
			assertEquals(0, g.getRepeatChunk());
			assertEquals(2_500, g.getTargetValue());
			assertEquals(BOSS, g.getName());
			assertEquals("2500 kills", g.getDescription());
		}

		@Test
		@DisplayName("one undo restores the repeatable goal exactly - chunk, period, target and section")
		void undoRestoresRepeatable()
		{
			Client client = MockClientFactory.createClient(
				new MockGameState().skillXp(Skill.WOODCUTTING, 5_000_000));
			GoalPlannerApiImpl api = apiWith(client);
			Goal g = repeatingSkillGoal(api);

			assertTrue(api.convertGoalToOneTime(g.getId(), 13_034_431));
			api.undo();

			assertTrue(g.isRepeating());
			assertEquals(RepeatPeriod.DAILY, g.getRepeatEvery());
			assertEquals(300_000, g.getRepeatChunk());
			assertEquals(5_300_000, g.getTargetValue());
			assertEquals("Woodcutting +300K XP", g.getName());
			assertEquals(store.getRepeatableSection().getId(), g.getSectionId());
		}

		@Test
		@DisplayName("a goal that already is one-time, and a non-positive target, are refused")
		void refusals()
		{
			GoalPlannerApiImpl api = apiWith(MockClientFactory.createClient(new MockGameState()));
			Goal plain = addPlainSkillGoal();

			assertFalse(api.convertGoalToOneTime(plain.getId(), 1_000),
				"changing only the target is changeTarget's job");
			assertEquals(13_034_431, plain.getTargetValue(), "the target must not be touched");
			assertFalse(api.convertGoalToOneTime(null, 1_000));
			assertFalse(api.convertGoalToOneTime(plain.getId(), 0));
		}
	}

	@Nested
	@DisplayName("round trip")
	class RoundTrip
	{
		@Test
		@DisplayName("repeatable -> one-time -> repeatable leaves a well-formed repeatable goal")
		void thereAndBackAgain()
		{
			Client client = MockClientFactory.createClient(
				new MockGameState().bossKills(BOSS, 1_847));
			GoalPlannerApiImpl api = apiWith(client);
			Goal g = addPlainBossGoal(2_000);

			assertTrue(api.convertGoalToRepeatable(g.getId(), RepeatPeriod.DAILY, 20));
			assertTrue(api.convertGoalToOneTime(g.getId(), 2_000));
			assertTrue(api.convertGoalToRepeatable(g.getId(), RepeatPeriod.MONTHLY, 30));

			assertTrue(g.isRepeating());
			assertEquals(RepeatPeriod.MONTHLY, g.getRepeatEvery());
			assertEquals(30, g.getRepeatChunk());
			assertEquals(1_877, g.getTargetValue());
			assertEquals(store.getRepeatableSection().getId(), g.getSectionId());
		}

		@Test
		@DisplayName("re-tuning an already repeatable goal to a new period + chunk works in one step")
		void retune()
		{
			Client client = MockClientFactory.createClient(
				new MockGameState().skillXp(Skill.WOODCUTTING, 5_000_000));
			GoalPlannerApiImpl api = apiWith(client);
			Goal g = addPlainSkillGoal();
			assertTrue(api.convertGoalToRepeatable(g.getId(), RepeatPeriod.DAILY, 300_000));

			assertTrue(api.convertGoalToRepeatable(g.getId(), RepeatPeriod.WEEKLY, 1_000_000));

			assertEquals(RepeatPeriod.WEEKLY, g.getRepeatEvery());
			assertEquals(1_000_000, g.getRepeatChunk());
			assertEquals(6_000_000, g.getTargetValue());
		}
	}
}
