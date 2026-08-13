package com.goalplanner.tracker;

import com.goalplanner.data.AchievementDiaryData;
import com.goalplanner.model.AccountMetric;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalStatus;
import com.goalplanner.model.GoalType;
import com.goalplanner.testsupport.MockClientFactory;
import com.goalplanner.testsupport.MockGameState;
import com.goalplanner.testsupport.TrackerTestHarness;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AccountTracker} using the MockGameState framework.
 * Covers all account metrics: quest points, combat level, total level,
 * CA points, slayer points, and museum kudos.
 */
class AccountTrackerTest
{
	private Goal makeAccountGoal(AccountMetric metric, int target)
	{
		return Goal.builder()
			.type(GoalType.ACCOUNT)
			.name(metric.getDisplayName() + " goal")
			.accountMetric(metric.name())
			.targetValue(target)
			.currentValue(0)
			.build();
	}

	@Nested
	@DisplayName("Quest Points")
	class QuestPointTests
	{
		@Test
		@DisplayName("reads quest points from VarPlayer")
		void readsQuestPoints()
		{
			MockGameState state = new MockGameState().questPoints(150);

			var h = TrackerTestHarness.forAccount(state);
			Goal goal = makeAccountGoal(AccountMetric.QUEST_POINTS, 200);
			h.store().addGoal(goal);

			assertTrue(h.tracker().checkGoals(h.store().getGoals()));
			assertEquals(150, goal.getCurrentValue());
		}

		@Test
		@DisplayName("detects QP increase between snapshots")
		void detectsQpIncrease()
		{
			MockGameState before = new MockGameState().questPoints(199);
			MockGameState after = before.copy().questPoints(200);

			var h = TrackerTestHarness.forAccount(before);
			Goal goal = makeAccountGoal(AccountMetric.QUEST_POINTS, 200);
			h.store().addGoal(goal);

			h.tracker().checkGoals(h.store().getGoals());
			assertFalse(goal.isComplete());

			h = h.withNewState(after);
			h.tracker().checkGoals(h.store().getGoals());
			assertTrue(goal.isComplete());
		}
	}

	@Nested
	@DisplayName("Combat Level")
	class CombatLevelTests
	{
		@Test
		@DisplayName("reads combat level from player")
		void readsCombatLevel()
		{
			MockGameState state = new MockGameState().combatLevel(85);

			var h = TrackerTestHarness.forAccount(state);
			Goal goal = makeAccountGoal(AccountMetric.COMBAT_LEVEL, 100);
			h.store().addGoal(goal);

			assertTrue(h.tracker().checkGoals(h.store().getGoals()));
			assertEquals(85, goal.getCurrentValue());
		}
	}

	@Nested
	@DisplayName("Total Level")
	class TotalLevelTests
	{
		@Test
		@DisplayName("reads total level from client")
		void readsTotalLevel()
		{
			MockGameState state = new MockGameState().totalLevel(1500);

			var h = TrackerTestHarness.forAccount(state);
			Goal goal = makeAccountGoal(AccountMetric.TOTAL_LEVEL, 2000);
			h.store().addGoal(goal);

			assertTrue(h.tracker().checkGoals(h.store().getGoals()));
			assertEquals(1500, goal.getCurrentValue());
		}
	}

	@Nested
	@DisplayName("CA Points")
	class CaPointTests
	{
		@Test
		@DisplayName("reads CA points from varbit")
		void readsCaPoints()
		{
			MockGameState state = new MockGameState()
				.varbit(VarbitID.CA_POINTS, 500);

			var h = TrackerTestHarness.forAccount(state);
			Goal goal = makeAccountGoal(AccountMetric.CA_POINTS, 1000);
			h.store().addGoal(goal);

			assertTrue(h.tracker().checkGoals(h.store().getGoals()));
			assertEquals(500, goal.getCurrentValue());
		}
	}

	@Nested
	@DisplayName("Slayer Points")
	class SlayerPointTests
	{
		@Test
		@DisplayName("reads slayer points from varbit")
		void readsSlayerPoints()
		{
			MockGameState state = new MockGameState()
				.varbit(VarbitID.SLAYER_POINTS, 2000);

			var h = TrackerTestHarness.forAccount(state);
			Goal goal = makeAccountGoal(AccountMetric.SLAYER_POINTS, 5000);
			h.store().addGoal(goal);

			assertTrue(h.tracker().checkGoals(h.store().getGoals()));
			assertEquals(2000, goal.getCurrentValue());
		}
	}

	@Nested
	@DisplayName("Museum Kudos")
	class KudosTests
	{
		@Test
		@DisplayName("reads kudos from varbit")
		void readsKudos()
		{
			MockGameState state = new MockGameState()
				.varbit(VarbitID.VM_KUDOS, 153);

			var h = TrackerTestHarness.forAccount(state);
			Goal goal = makeAccountGoal(AccountMetric.KUDOS, 200);
			h.store().addGoal(goal);

			assertTrue(h.tracker().checkGoals(h.store().getGoals()));
			assertEquals(153, goal.getCurrentValue());
		}
	}

	@Nested
	@DisplayName("Collection Log Slots")
	class CollectionLogSlotTests
	{
		@Test
		@DisplayName("reads obtained slot count from VarPlayer")
		void readsSlotCount()
		{
			MockGameState state = new MockGameState()
				.varp(VarPlayerID.COLLECTION_COUNT, 432);

			var h = TrackerTestHarness.forAccount(state);
			Goal goal = makeAccountGoal(AccountMetric.COLLECTION_LOG_SLOTS, 600);
			h.store().addGoal(goal);

			assertTrue(h.tracker().checkGoals(h.store().getGoals()));
			assertEquals(432, goal.getCurrentValue());
		}

		@Test
		@DisplayName("skips a zero read (unsynced log) instead of clobbering stored progress")
		void skipsUnsyncedZeroRead()
		{
			MockGameState synced = new MockGameState()
				.varp(VarPlayerID.COLLECTION_COUNT, 432);
			MockGameState unsynced = new MockGameState(); // varp reads 0

			var h = TrackerTestHarness.forAccount(synced);
			Goal goal = makeAccountGoal(AccountMetric.COLLECTION_LOG_SLOTS, 600);
			h.store().addGoal(goal);
			h.tracker().checkGoals(h.store().getGoals());
			assertEquals(432, goal.getCurrentValue());

			h = h.withNewState(unsynced);
			assertFalse(h.tracker().checkGoals(h.store().getGoals()));
			assertEquals(432, goal.getCurrentValue());
		}

		@Test
		@DisplayName("completes the goal when the obtained count reaches the target")
		void completesAtTarget()
		{
			MockGameState state = new MockGameState()
				.varp(VarPlayerID.COLLECTION_COUNT, 600);

			var h = TrackerTestHarness.forAccount(state);
			Goal goal = makeAccountGoal(AccountMetric.COLLECTION_LOG_SLOTS, 600);
			h.store().addGoal(goal);

			h.tracker().checkGoals(h.store().getGoals());
			assertTrue(goal.isComplete());
		}
	}

	@Nested
	@DisplayName("Diary Tiers")
	class DiaryTierTests
	{
		@Test
		@DisplayName("counts completed tiers across boolean and Karamja count varbits")
		void countsMixedTrackingModes()
		{
			MockGameState state = new MockGameState()
				.diaryComplete("Ardougne", AchievementDiaryData.Tier.EASY)
				.diaryComplete("Lumbridge", AchievementDiaryData.Tier.ELITE)
				.diaryComplete("Karamja", AchievementDiaryData.Tier.MEDIUM)
				.diaryComplete("Karamja", AchievementDiaryData.Tier.ELITE);

			var h = TrackerTestHarness.forAccount(state);
			Goal goal = makeAccountGoal(AccountMetric.DIARY_TIERS_COMPLETED, 48);
			h.store().addGoal(goal);

			assertTrue(h.tracker().checkGoals(h.store().getGoals()));
			assertEquals(4, goal.getCurrentValue());
		}

		@Test
		@DisplayName("reads 0 tiers on a fresh account")
		void zeroOnFreshAccount()
		{
			MockGameState state = new MockGameState();

			var h = TrackerTestHarness.forAccount(state);
			Goal goal = makeAccountGoal(AccountMetric.DIARY_TIERS_COMPLETED, 48);
			h.store().addGoal(goal);

			h.tracker().checkGoals(h.store().getGoals());
			assertEquals(0, goal.getCurrentValue());
			assertFalse(goal.isComplete());
		}

		@Test
		@DisplayName("completes a 48-tier goal when every diary tier is done")
		void completesDiaryCape()
		{
			MockGameState state = new MockGameState();
			for (String area : AchievementDiaryData.AREA_KEYS)
			{
				for (AchievementDiaryData.Tier tier : AchievementDiaryData.Tier.values())
				{
					state.diaryComplete(area, tier);
				}
			}

			var h = TrackerTestHarness.forAccount(state);
			Goal goal = makeAccountGoal(AccountMetric.DIARY_TIERS_COMPLETED, 48);
			h.store().addGoal(goal);

			h.tracker().checkGoals(h.store().getGoals());
			assertEquals(48, goal.getCurrentValue());
			assertTrue(goal.isComplete());
		}

		@Test
		@DisplayName("detects tier completion between snapshots")
		void detectsTierIncrease()
		{
			MockGameState before = new MockGameState()
				.diaryComplete("Varrock", AchievementDiaryData.Tier.EASY);
			MockGameState after = before.copy()
				.diaryComplete("Varrock", AchievementDiaryData.Tier.MEDIUM);

			var h = TrackerTestHarness.forAccount(before);
			Goal goal = makeAccountGoal(AccountMetric.DIARY_TIERS_COMPLETED, 2);
			h.store().addGoal(goal);

			h.tracker().checkGoals(h.store().getGoals());
			assertEquals(1, goal.getCurrentValue());
			assertFalse(goal.isComplete());

			h = h.withNewState(after);
			h.tracker().checkGoals(h.store().getGoals());
			assertEquals(2, goal.getCurrentValue());
			assertTrue(goal.isComplete());
		}
	}

	@Nested
	@DisplayName("League Points")
	class LeaguePointTests
	{
		@Test
		@DisplayName("reads lifetime league points from VarPlayer (on seasonal world)")
		void readsLeaguePoints()
		{
			MockGameState state = new MockGameState()
				.seasonal(true)
				.varp(VarPlayerID.LEAGUE_POINTS_COMPLETED, 42000);

			var h = TrackerTestHarness.forAccount(state);
			Goal goal = makeAccountGoal(AccountMetric.LEAGUE_POINTS, 50000);
			h.store().addGoal(goal);

			assertTrue(h.tracker().checkGoals(h.store().getGoals()));
			assertEquals(42000, goal.getCurrentValue());
		}
	}

	@Nested
	@DisplayName("Leagues Tasks")
	class LeagueTaskTests
	{
		@Test
		@DisplayName("reads total tasks completed from varbit (on seasonal world)")
		void readsLeagueTasks()
		{
			MockGameState state = new MockGameState()
				.seasonal(true)
				.varbit(VarbitID.LEAGUE_TOTAL_TASKS_COMPLETED, 250);

			var h = TrackerTestHarness.forAccount(state);
			Goal goal = makeAccountGoal(AccountMetric.LEAGUE_TASKS, 500);
			h.store().addGoal(goal);

			assertTrue(h.tracker().checkGoals(h.store().getGoals()));
			assertEquals(250, goal.getCurrentValue());
		}
	}

	@Nested
	@DisplayName("Decaying metrics (completion follows the live value)")
	class DecayingMetricTests
	{
		private MockGameState favour(int approval)
		{
			return new MockGameState().varbit(VarbitID.MISC_APPROVAL, approval);
		}

		@Test
		@DisplayName("Miscellania favour goal completes at 100% approval")
		void completesAtFullApproval()
		{
			var h = TrackerTestHarness.forAccount(favour(127));
			Goal goal = makeAccountGoal(AccountMetric.MISC_APPROVAL, 127);
			h.store().addGoal(goal);

			h.tracker().checkGoals(h.store().getGoals());
			assertEquals(127, goal.getCurrentValue());
			assertTrue(goal.isComplete());
		}

		@Test
		@DisplayName("re-opens the goal when favour decays below the target")
		void reopensWhenFavourDecays()
		{
			var h = TrackerTestHarness.forAccount(favour(127));
			Goal goal = makeAccountGoal(AccountMetric.MISC_APPROVAL, 127);
			h.store().addGoal(goal);
			h.tracker().checkGoals(h.store().getGoals());
			assertTrue(goal.isComplete());

			h = h.withNewState(favour(126));
			assertTrue(h.tracker().checkGoals(h.store().getGoals()));
			assertFalse(goal.isComplete());
			assertEquals(0, goal.getCompletedAt());
			assertEquals(126, goal.getCurrentValue());
			assertEquals(GoalStatus.ACTIVE, goal.getStatus());
		}

		@Test
		@DisplayName("re-completes once favour is topped back up")
		void reCompletesWhenFavourRecovers()
		{
			var h = TrackerTestHarness.forAccount(favour(127));
			Goal goal = makeAccountGoal(AccountMetric.MISC_APPROVAL, 127);
			h.store().addGoal(goal);
			h.tracker().checkGoals(h.store().getGoals());

			h = h.withNewState(favour(120));
			h.tracker().checkGoals(h.store().getGoals());
			assertFalse(goal.isComplete());

			h = h.withNewState(favour(127));
			h.tracker().checkGoals(h.store().getGoals());
			assertTrue(goal.isComplete());
			assertEquals(127, goal.getCurrentValue());
		}

		@Test
		@DisplayName("holds steady once re-opened - no flip-flop on an unchanged value")
		void noFlipFlopWhileBelowTarget()
		{
			var h = TrackerTestHarness.forAccount(favour(127));
			Goal goal = makeAccountGoal(AccountMetric.MISC_APPROVAL, 127);
			h.store().addGoal(goal);
			h.tracker().checkGoals(h.store().getGoals());

			h = h.withNewState(favour(126));
			assertTrue(h.tracker().checkGoals(h.store().getGoals()));
			// A second pass over the same state changes nothing at all.
			assertFalse(h.tracker().checkGoals(h.store().getGoals()));
			assertFalse(goal.isComplete());
			assertEquals(126, goal.getCurrentValue());
		}

		@Test
		@DisplayName("a completed goal at full favour is left untouched")
		void stableWhileStillMet()
		{
			var h = TrackerTestHarness.forAccount(favour(127));
			Goal goal = makeAccountGoal(AccountMetric.MISC_APPROVAL, 127);
			h.store().addGoal(goal);
			h.tracker().checkGoals(h.store().getGoals());
			long completedAt = goal.getCompletedAt();

			assertFalse(h.tracker().checkGoals(h.store().getGoals()));
			assertEquals(completedAt, goal.getCompletedAt());
		}

		@Test
		@DisplayName("spent slayer points re-open a completed points goal")
		void spendingSlayerPointsReopens()
		{
			var h = TrackerTestHarness.forAccount(
				new MockGameState().varbit(VarbitID.SLAYER_POINTS, 1000));
			Goal goal = makeAccountGoal(AccountMetric.SLAYER_POINTS, 1000);
			h.store().addGoal(goal);
			h.tracker().checkGoals(h.store().getGoals());
			assertTrue(goal.isComplete());

			h = h.withNewState(new MockGameState().varbit(VarbitID.SLAYER_POINTS, 100));
			h.tracker().checkGoals(h.store().getGoals());
			assertFalse(goal.isComplete());
			assertEquals(100, goal.getCurrentValue());
		}

		@Test
		@DisplayName("the pre-sync zero read right after login does not un-complete it")
		void loginSyncZeroDoesNotReopen()
		{
			var h = TrackerTestHarness.forAccount(favour(127));
			Goal goal = makeAccountGoal(AccountMetric.MISC_APPROVAL, 127);
			h.store().addGoal(goal);
			h.tracker().checkGoals(h.store().getGoals());
			long completedAt = goal.getCompletedAt();

			// Relog: the varbit block has not landed yet, so MISC_APPROVAL reads 0.
			h = h.withNewState(favour(0));
			h.tracker().onLogin();
			assertFalse(h.tracker().checkGoals(h.store().getGoals()));
			assertTrue(goal.isComplete());
			assertEquals(completedAt, goal.getCompletedAt());
			assertEquals(127, goal.getCurrentValue());
		}

		@Test
		@DisplayName("a genuine zero re-opens the goal once the settle window passes")
		void settledZeroDoesReopen()
		{
			// Fake clock: onLogin() arms the window, then time jumps past it.
			long[] now = { 1_000_000L };
			var h = TrackerTestHarness.forAccount(favour(127));
			Goal goal = makeAccountGoal(AccountMetric.MISC_APPROVAL, 127);
			h.store().addGoal(goal);
			h.tracker().checkGoals(h.store().getGoals());

			AccountTracker settling = new AccountTracker(
				MockClientFactory.createClient(favour(0)), h.api(), () -> now[0]);
			settling.onLogin();
			assertFalse(settling.checkGoals(h.store().getGoals()));
			assertTrue(goal.isComplete());

			now[0] += AccountTracker.LOGIN_SETTLE_MS;
			assertTrue(settling.checkGoals(h.store().getGoals()));
			assertFalse(goal.isComplete());
			assertEquals(0, goal.getCurrentValue());
		}
	}

	@Nested
	@DisplayName("Monotonic metrics (completion stays sticky)")
	class MonotonicMetricTests
	{
		@Test
		@DisplayName("a completed quest-point goal survives a lower read")
		void completedQuestPointGoalStaysComplete()
		{
			var h = TrackerTestHarness.forAccount(new MockGameState().questPoints(200));
			Goal goal = makeAccountGoal(AccountMetric.QUEST_POINTS, 200);
			h.store().addGoal(goal);
			h.tracker().checkGoals(h.store().getGoals());
			assertTrue(goal.isComplete());
			long completedAt = goal.getCompletedAt();

			// Unsynced/wrong read: the goal is never re-read once complete.
			h = h.withNewState(new MockGameState().questPoints(0));
			assertFalse(h.tracker().checkGoals(h.store().getGoals()));
			assertTrue(goal.isComplete());
			assertEquals(completedAt, goal.getCompletedAt());
			assertEquals(200, goal.getCurrentValue());
		}
	}

	@Test
	@DisplayName("tracks multiple metrics simultaneously")
	void tracksMultipleMetrics()
	{
		MockGameState state = new MockGameState()
			.questPoints(100)
			.combatLevel(75)
			.totalLevel(1200)
			.varbit(VarbitID.CA_POINTS, 300);

		var h = TrackerTestHarness.forAccount(state);
		Goal qp = makeAccountGoal(AccountMetric.QUEST_POINTS, 200);
		Goal combat = makeAccountGoal(AccountMetric.COMBAT_LEVEL, 126);
		Goal total = makeAccountGoal(AccountMetric.TOTAL_LEVEL, 2000);
		Goal ca = makeAccountGoal(AccountMetric.CA_POINTS, 500);
		h.store().addGoal(qp);
		h.store().addGoal(combat);
		h.store().addGoal(total);
		h.store().addGoal(ca);

		h.tracker().checkGoals(h.store().getGoals());
		assertEquals(100, qp.getCurrentValue());
		assertEquals(75, combat.getCurrentValue());
		assertEquals(1200, total.getCurrentValue());
		assertEquals(300, ca.getCurrentValue());
	}

	@Test
	@DisplayName("skips goals with null accountMetric")
	void skipsNullMetric()
	{
		MockGameState state = new MockGameState();
		var h = TrackerTestHarness.forAccount(state);

		Goal goal = Goal.builder()
			.type(GoalType.ACCOUNT)
			.name("Bad account goal")
			.accountMetric(null)
			.targetValue(100)
			.build();
		h.store().addGoal(goal);

		assertFalse(h.tracker().checkGoals(h.store().getGoals()));
	}
}
