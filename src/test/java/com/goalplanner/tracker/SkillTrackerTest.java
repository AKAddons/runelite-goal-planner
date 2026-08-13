package com.goalplanner.tracker;

import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.testsupport.MockGameState;
import com.goalplanner.testsupport.TrackerTestHarness;
import net.runelite.api.Skill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SkillTracker} using the MockGameState framework.
 * Validates that the tracker correctly reads skill XP from the client
 * and records goal progress.
 */
class SkillTrackerTest
{
	private Goal makeSkillGoal(Skill skill, int targetXp)
	{
		return Goal.builder()
			.type(GoalType.SKILL)
			.name(skill.getName() + " goal")
			.skillName(skill.name())
			.targetValue(targetXp)
			.currentValue(0)
			.build();
	}

	@Test
	@DisplayName("reads skill XP from client and records progress")
	void readsSkillXp()
	{
		MockGameState state = new MockGameState()
			.skillXp(Skill.WOODCUTTING, 500_000);

		var h = TrackerTestHarness.forSkills(state);
		Goal goal = makeSkillGoal(Skill.WOODCUTTING, 1_000_000);
		h.store().addGoal(goal);

		assertTrue(h.tracker().checkGoals(h.store().getGoals()));
		assertEquals(500_000, goal.getCurrentValue());
		assertFalse(goal.isComplete());
	}

	@Test
	@DisplayName("detects XP gain between snapshots")
	void detectsXpGain()
	{
		MockGameState before = new MockGameState().skillXp(Skill.MINING, 100_000);
		MockGameState after = before.copy().skillXp(Skill.MINING, 150_000);

		var h = TrackerTestHarness.forSkills(before);
		Goal goal = makeSkillGoal(Skill.MINING, 150_000);
		h.store().addGoal(goal);

		h.tracker().checkGoals(h.store().getGoals());
		assertEquals(100_000, goal.getCurrentValue());
		assertFalse(goal.isComplete());

		h = h.withNewState(after);
		h.tracker().checkGoals(h.store().getGoals());
		assertEquals(150_000, goal.getCurrentValue());
		assertTrue(goal.isComplete());
	}

	@Test
	@DisplayName("a completed 99 goal stays complete when a lower XP value is reported")
	void completedSkillGoalIsSticky()
	{
		MockGameState maxed = new MockGameState().skillXp(Skill.ATTACK, 13_034_431);
		MockGameState unsynced = new MockGameState().skillXp(Skill.ATTACK, 0);

		var h = TrackerTestHarness.forSkills(maxed);
		Goal goal = makeSkillGoal(Skill.ATTACK, 13_034_431);
		h.store().addGoal(goal);
		h.tracker().checkGoals(h.store().getGoals());
		assertTrue(goal.isComplete());
		long completedAt = goal.getCompletedAt();

		// XP is monotonic in game, so a lower read is a stale client, never a
		// loss: the goal is not re-read and its completion date is preserved.
		h = h.withNewState(unsynced);
		assertFalse(h.tracker().checkGoals(h.store().getGoals()));
		assertTrue(goal.isComplete());
		assertEquals(completedAt, goal.getCompletedAt());
		assertEquals(13_034_431, goal.getCurrentValue());
	}

	@Test
	@DisplayName("tracks multiple skills independently")
	void tracksMultipleSkills()
	{
		MockGameState state = new MockGameState()
			.skillXp(Skill.ATTACK, 50_000)
			.skillXp(Skill.STRENGTH, 75_000)
			.skillXp(Skill.FISHING, 200_000);

		var h = TrackerTestHarness.forSkills(state);
		Goal attack = makeSkillGoal(Skill.ATTACK, 100_000);
		Goal strength = makeSkillGoal(Skill.STRENGTH, 100_000);
		Goal fishing = makeSkillGoal(Skill.FISHING, 200_000);
		h.store().addGoal(attack);
		h.store().addGoal(strength);
		h.store().addGoal(fishing);

		h.tracker().checkGoals(h.store().getGoals());
		assertEquals(50_000, attack.getCurrentValue());
		assertEquals(75_000, strength.getCurrentValue());
		assertEquals(200_000, fishing.getCurrentValue());
		assertTrue(fishing.isComplete());
	}

	@Test
	@DisplayName("skips goals with null skillName")
	void skipsNullSkillName()
	{
		MockGameState state = new MockGameState();
		var h = TrackerTestHarness.forSkills(state);

		Goal goal = Goal.builder()
			.type(GoalType.SKILL)
			.name("Bad skill goal")
			.skillName(null)
			.targetValue(100)
			.build();
		h.store().addGoal(goal);

		assertFalse(h.tracker().checkGoals(h.store().getGoals()));
	}

	@Test
	@DisplayName("no-op when XP unchanged between checks")
	void noOpWhenUnchanged()
	{
		MockGameState state = new MockGameState().skillXp(Skill.AGILITY, 300_000);
		var h = TrackerTestHarness.forSkills(state);
		Goal goal = makeSkillGoal(Skill.AGILITY, 1_000_000);
		h.store().addGoal(goal);

		assertTrue(h.tracker().checkGoals(h.store().getGoals()));
		assertFalse(h.tracker().checkGoals(h.store().getGoals()),
			"second check with same XP should be no-op");
	}
}
