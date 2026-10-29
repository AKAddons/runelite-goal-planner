package com.goalplanner.tracker;

import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.testsupport.MockGameState;
import com.goalplanner.testsupport.TrackerTestHarness;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CombatAchievementTracker} using the MockGameState framework.
 * Validates the bit-packed CA_TASK_COMPLETED varplayer reading logic.
 */
class CombatAchievementTrackerTest
{
	private Goal makeCaGoal(int taskId)
	{
		return Goal.builder()
			.type(GoalType.COMBAT_ACHIEVEMENT)
			.name("CA Task " + taskId)
			.caTaskId(taskId)
			.targetValue(1)
			.currentValue(0)
			.build();
	}

	@Test
	@DisplayName("detects completed CA task from bit-packed varp")
	void detectsCompletedTask()
	{
		MockGameState state = new MockGameState()
			.caTaskComplete(5); // bit 5 in CA_TASK_COMPLETED_0

		var h = TrackerTestHarness.forCombatAchievements(state);
		Goal goal = makeCaGoal(5);
		h.store().addGoal(goal);

		assertTrue(h.tracker().checkGoals(h.store().getGoals()));
		assertEquals(1, goal.getCurrentValue());
		assertTrue(goal.isComplete());
	}

	@Test
	@DisplayName("tasks 640-671 read from CA_TASK_COMPLETED_20 (Cordoned Off, id 644 = bit 4)")
	void readsTheTwentyFirstVarplayer()
	{
		MockGameState state = new MockGameState()
			.caTaskComplete(644)
			.caTaskComplete(671);

		var h = TrackerTestHarness.forCombatAchievements(state);
		Goal cordonedOff = makeCaGoal(644);
		Goal lastSlot = makeCaGoal(671);
		Goal notDone = makeCaGoal(645);
		h.store().addGoal(cordonedOff);
		h.store().addGoal(lastSlot);
		h.store().addGoal(notDone);

		assertTrue(h.tracker().checkGoals(h.store().getGoals()));
		assertEquals(1, cordonedOff.getCurrentValue());
		assertEquals(1, lastSlot.getCurrentValue());
		assertEquals(0, notDone.getCurrentValue());
		assertEquals(VarPlayerID.CA_TASK_COMPLETED_20, 5673, "gameval id the game added in 1.13.4");
	}

	@Test
	@DisplayName("incomplete CA task reads as 0")
	void incompleteTaskReadsZero()
	{
		MockGameState state = new MockGameState()
			.caTaskComplete(5); // task 5 is done, but task 6 is not

		var h = TrackerTestHarness.forCombatAchievements(state);
		Goal goal = makeCaGoal(6);
		h.store().addGoal(goal);

		assertFalse(h.tracker().checkGoals(h.store().getGoals()),
			"task 6 should not be complete");
		assertEquals(0, goal.getCurrentValue());
	}

	@Test
	@DisplayName("handles tasks across different varp indices")
	void handlesTasksAcrossVarpIndices()
	{
		// Task 0 is in varp index 0 (bit 0)
		// Task 33 is in varp index 1 (bit 1)
		// Task 64 is in varp index 2 (bit 0)
		MockGameState state = new MockGameState()
			.caTaskComplete(0)
			.caTaskComplete(33)
			.caTaskComplete(64);

		var h = TrackerTestHarness.forCombatAchievements(state);
		Goal task0 = makeCaGoal(0);
		Goal task33 = makeCaGoal(33);
		Goal task64 = makeCaGoal(64);
		Goal task1 = makeCaGoal(1); // not completed
		h.store().addGoal(task0);
		h.store().addGoal(task33);
		h.store().addGoal(task64);
		h.store().addGoal(task1);

		h.tracker().checkGoals(h.store().getGoals());
		assertTrue(task0.isComplete(), "task 0 should be complete");
		assertTrue(task33.isComplete(), "task 33 should be complete");
		assertTrue(task64.isComplete(), "task 64 should be complete");
		assertFalse(task1.isComplete(), "task 1 should not be complete");
	}

	@Test
	@DisplayName("detects task completion between snapshots")
	void detectsCompletionBetweenSnapshots()
	{
		MockGameState before = new MockGameState();
		MockGameState after = before.copy().caTaskComplete(42);

		var h = TrackerTestHarness.forCombatAchievements(before);
		Goal goal = makeCaGoal(42);
		h.store().addGoal(goal);

		h.tracker().checkGoals(h.store().getGoals());
		assertFalse(goal.isComplete());

		h = h.withNewState(after);
		h.tracker().checkGoals(h.store().getGoals());
		assertTrue(goal.isComplete());
	}

	@Test
	@DisplayName("skips goals with negative caTaskId")
	void skipsNegativeTaskId()
	{
		MockGameState state = new MockGameState();
		var h = TrackerTestHarness.forCombatAchievements(state);

		Goal goal = Goal.builder()
			.type(GoalType.COMBAT_ACHIEVEMENT)
			.name("Bad CA goal")
			.caTaskId(-1)
			.targetValue(1)
			.build();
		h.store().addGoal(goal);

		assertFalse(h.tracker().checkGoals(h.store().getGoals()));
	}

	@Test
	@DisplayName("multiple tasks in same varp word don't interfere")
	void multipleTasksSameVarp()
	{
		// Tasks 0, 1, 31 are all in varp index 0
		MockGameState state = new MockGameState()
			.caTaskComplete(0)
			.caTaskComplete(31);

		var h = TrackerTestHarness.forCombatAchievements(state);
		Goal task0 = makeCaGoal(0);
		Goal task1 = makeCaGoal(1);
		Goal task31 = makeCaGoal(31);
		h.store().addGoal(task0);
		h.store().addGoal(task1);
		h.store().addGoal(task31);

		h.tracker().checkGoals(h.store().getGoals());
		assertTrue(task0.isComplete(), "task 0 should be complete");
		assertFalse(task1.isComplete(), "task 1 should not be complete");
		assertTrue(task31.isComplete(), "task 31 should be complete");
	}
}
