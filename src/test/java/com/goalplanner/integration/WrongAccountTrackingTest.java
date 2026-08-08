package com.goalplanner.integration;

import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.testsupport.MockGameState;
import com.goalplanner.testsupport.TrackerTestHarness;
import com.goalplanner.tracker.BossKillTracker;
import com.goalplanner.tracker.SkillTracker;
import com.goalplanner.util.AccountBindingGate;
import com.goalplanner.util.AccountBindingGate.Decision;
import net.runelite.api.Skill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end cover for the reported bug: one account's goals completing
 * themselves against another account's stats.
 *
 * <p>These tests run the REAL store, API and trackers. They reconstruct the
 * plugin's drain guard rather than calling {@code GoalPlannerPlugin} directly,
 * because instantiating the plugin needs RuneLite's injector; the guard
 * expression here is the same one the plugin applies before
 * {@code drainTrackerUpdates()}. What that leaves unproven is only that the
 * plugin calls it - the guard's effect on real trackers is exercised for real.
 *
 * <p>The first test in each pair deliberately reproduces the corruption with
 * the gate absent. If those ever stop failing-open, the gate is being tested
 * against a bug that no longer exists and these need revisiting.
 */
class WrongAccountTrackingTest
{
	private static final long MAIN = 111_111L;
	private static final long ALT = 222_222L;

	/** Exactly what the plugin evaluates before letting the trackers run. */
	private static boolean drainAllowed(AccountBindingGate gate, long live, long bound)
	{
		return AccountBindingGate.allowsTracking(gate.evaluate(live, bound));
	}

	private static Goal prayerGoal()
	{
		return Goal.builder().type(GoalType.SKILL).name("Prayer - Level 99")
			.skillName("PRAYER").targetValue(13_034_431).build();
	}

	private static Goal graardorGoal()
	{
		return Goal.builder().type(GoalType.BOSS).name("General Graardor")
			.bossName("General Graardor").targetValue(50).build();
	}

	@Test
	@DisplayName("REPRODUCES THE BUG: ungated, another account's stats complete these goals")
	void ungatedTrackingCorrupts()
	{
		// The alt's plan is loaded, but the MAIN is logged in with 99 Prayer.
		TrackerTestHarness<SkillTracker> h = TrackerTestHarness.forSkills(
			new MockGameState().skillXp(Skill.PRAYER, 13_034_431));
		Goal altGoal = prayerGoal();
		h.store().addGoal(altGoal);

		h.tracker().checkGoals(h.store().getGoals());

		assertTrue(altGoal.isComplete(),
			"this is the reported bug reproduced - the alt's goal completed off the main's XP");
	}

	@Test
	@DisplayName("gated, the same pairing never reaches the tracker and nothing completes")
	void gatedTrackingIsRefused()
	{
		TrackerTestHarness<SkillTracker> h = TrackerTestHarness.forSkills(
			new MockGameState().skillXp(Skill.PRAYER, 13_034_431));
		Goal altGoal = prayerGoal();
		h.store().addGoal(altGoal);
		h.store().setBoundAccountHash(ALT);

		AccountBindingGate gate = new AccountBindingGate();
		if (drainAllowed(gate, MAIN, h.store().getBoundAccountHash()))
		{
			h.tracker().checkGoals(h.store().getGoals());
		}

		assertFalse(altGoal.isComplete(),
			"the alt's goal must survive the main being logged in");
		assertEquals(0, altGoal.getCurrentValue(),
			"and must not even record the wrong account's progress");
	}

	@Test
	@DisplayName("boss kill counts are protected the same way")
	void bossKillCountsGated()
	{
		TrackerTestHarness<BossKillTracker> h = TrackerTestHarness.forBossKills(
			new MockGameState().bossKills("General Graardor", 1847));
		Goal altGoal = graardorGoal();
		h.store().addGoal(altGoal);
		h.store().setBoundAccountHash(ALT);

		AccountBindingGate gate = new AccountBindingGate();
		if (drainAllowed(gate, MAIN, h.store().getBoundAccountHash()))
		{
			h.tracker().checkGoals(h.store().getGoals());
		}

		assertFalse(altGoal.isComplete(), "1,847 lifetime kills must not complete the alt's 50-kill goal");
	}

	@Test
	@DisplayName("the right account still tracks normally - the gate must not break the common case")
	void matchingAccountStillTracks()
	{
		TrackerTestHarness<SkillTracker> h = TrackerTestHarness.forSkills(
			new MockGameState().skillXp(Skill.PRAYER, 13_034_431));
		Goal ownGoal = prayerGoal();
		h.store().addGoal(ownGoal);
		h.store().setBoundAccountHash(MAIN);

		AccountBindingGate gate = new AccountBindingGate();
		if (drainAllowed(gate, MAIN, h.store().getBoundAccountHash()))
		{
			h.tracker().checkGoals(h.store().getGoals());
		}

		assertTrue(ownGoal.isComplete(), "a matching account must track exactly as before");
	}

	@Test
	@DisplayName("an unbound goal set adopts the logged-in account and tracks it")
	void unboundSetAdoptsAndTracks()
	{
		TrackerTestHarness<SkillTracker> h = TrackerTestHarness.forSkills(
			new MockGameState().skillXp(Skill.PRAYER, 13_034_431));
		Goal goal = prayerGoal();
		h.store().addGoal(goal);
		assertEquals(0L, h.store().getBoundAccountHash(), "starts unbound, as an upgrading user would");

		AccountBindingGate gate = new AccountBindingGate();
		Decision decision = gate.evaluate(MAIN, h.store().getBoundAccountHash());
		if (decision == Decision.ADOPT)
		{
			h.store().setBoundAccountHash(MAIN);
		}
		if (AccountBindingGate.allowsTracking(decision))
		{
			h.tracker().checkGoals(h.store().getGoals());
		}

		assertEquals(MAIN, h.store().getBoundAccountHash(), "existing users migrate with no action");
		assertTrue(goal.isComplete(), "and tracking is not interrupted by the migration");
	}

	@Test
	@DisplayName("once adopted, the OTHER account is locked out - the full astrasin sequence")
	void adoptThenSwitchAccountsIsRefused()
	{
		// Day 1: play the main, goals bind to it and track.
		TrackerTestHarness<SkillTracker> h = TrackerTestHarness.forSkills(
			new MockGameState().skillXp(Skill.PRAYER, 13_034_431));
		Goal goal = prayerGoal();
		h.store().addGoal(goal);

		AccountBindingGate gate = new AccountBindingGate();
		if (gate.evaluate(MAIN, h.store().getBoundAccountHash()) == Decision.ADOPT)
		{
			h.store().setBoundAccountHash(MAIN);
		}
		h.tracker().checkGoals(h.store().getGoals());
		assertTrue(goal.isComplete());

		// Day 2: same profile, the ALT logs in. Reset the goal as the recovery
		// flow would, then confirm the alt cannot re-complete it.
		goal.setCompletedAt(0);
		goal.setStatus(com.goalplanner.model.GoalStatus.ACTIVE);

		assertFalse(drainAllowed(gate, ALT, h.store().getBoundAccountHash()),
			"the alt must be refused on a profile bound to the main");
		assertTrue(gate.shouldAnnounceMismatch(), "and the user must be told why tracking stopped");
	}
}
