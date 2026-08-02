package com.goalplanner.service;

import com.goalplanner.ResetBoundary;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.model.RepeatPeriod;
import com.goalplanner.model.Section;
import com.goalplanner.persistence.GoalStore;
import com.goalplanner.testsupport.InMemoryConfigManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rollover behaviour against the real {@link GoalStore}. The clock is passed
 * in, so "it is now tomorrow" is a parameter rather than a wait.
 */
class RepeatResetServiceTest
{
	private GoalStore store;
	private RepeatResetService service;

	private static final Instant MONDAY_NOON =
		LocalDate.of(2026, 7, 27).atTime(12, 0).atZone(ZoneOffset.UTC).toInstant();

	@BeforeEach
	void setUp()
	{
		ConfigManager configManager = InMemoryConfigManager.create();
		store = new GoalStore(configManager, new com.google.gson.Gson());
		store.load();
		service = new RepeatResetService(store);
	}

	private Goal dailyGoal(String name)
	{
		Goal g = Goal.builder()
			.type(GoalType.CUSTOM)
			.name(name)
			.repeatEvery(RepeatPeriod.DAILY)
			.build();
		store.addGoal(g);
		return g;
	}

	private int run(Instant now)
	{
		return service.applyResets(now, ResetBoundary.GAME_RESET, 0);
	}

	@Test
	@DisplayName("the first check stamps the period without reopening a done goal")
	void firstCheckStampsWithoutResetting()
	{
		Goal g = dailyGoal("5 Mahogany Homes contracts");
		g.setCompletedAt(MONDAY_NOON.toEpochMilli());

		assertEquals(0, run(MONDAY_NOON), "an unstamped goal must not reset on sight");
		assertTrue(g.isComplete(), "marking something repeatable while it is done leaves it done");
		assertTrue(g.getLastPeriodKey() != 0, "it should now be stamped");
	}

	@Test
	@DisplayName("crossing midnight reopens a completed daily")
	void rollsOverAtMidnight()
	{
		Goal g = dailyGoal("15 Pest Control games");
		run(MONDAY_NOON);
		g.setCompletedAt(MONDAY_NOON.toEpochMilli());

		assertEquals(1, run(MONDAY_NOON.plusSeconds(86_400)));
		assertFalse(g.isComplete(), "the next day it should be ready again");
	}

	@Test
	@DisplayName("running repeatedly inside one period does nothing")
	void idempotentWithinPeriod()
	{
		Goal g = dailyGoal("10 CG runs");
		run(MONDAY_NOON);
		g.setCompletedAt(MONDAY_NOON.toEpochMilli());

		assertEquals(0, run(MONDAY_NOON.plusSeconds(60)));
		assertEquals(0, run(MONDAY_NOON.plusSeconds(3600)));
		assertTrue(g.isComplete(), "a mid-period check must not disturb a completed goal");
	}

	@Test
	@DisplayName("five days offline reopens the goal once, not five times")
	void fiveDaysOfflineResetsOnce()
	{
		Goal g = dailyGoal("300 chinchompas");
		run(MONDAY_NOON);
		g.setCompletedAt(MONDAY_NOON.toEpochMilli());

		assertEquals(1, run(MONDAY_NOON.plusSeconds(5 * 86_400)));
		assertFalse(g.isComplete());
		// And the catch-up leaves it correctly stamped, not lagging four days.
		assertEquals(0, run(MONDAY_NOON.plusSeconds(5 * 86_400 + 60)));
	}

	@Test
	@DisplayName("an incomplete repeatable goal is left alone but still re-stamped")
	void incompleteGoalJustRestamps()
	{
		Goal g = dailyGoal("100 MTA points");
		run(MONDAY_NOON);
		long stamped = g.getLastPeriodKey();

		assertEquals(0, run(MONDAY_NOON.plusSeconds(86_400)),
			"nothing was completed, so nothing was reset");
		assertTrue(g.getLastPeriodKey() > stamped, "but the period did advance");
	}

	@Test
	@DisplayName("non-repeating goals are never touched")
	void oneShotGoalsUntouched()
	{
		Goal once = Goal.builder().type(GoalType.CUSTOM).name("Quest cape").build();
		store.addGoal(once);
		once.setCompletedAt(MONDAY_NOON.toEpochMilli());

		run(MONDAY_NOON);
		assertEquals(0, run(MONDAY_NOON.plusSeconds(30 * 86_400)));
		assertTrue(once.isComplete(), "a one-shot goal must stay completed forever");
	}

	@Test
	@DisplayName("a weekly goal survives a day rollover and resets on Wednesday")
	void weeklyIgnoresDailyBoundary()
	{
		Goal g = Goal.builder().type(GoalType.CUSTOM).name("Weekly raid")
			.repeatEvery(RepeatPeriod.WEEKLY).build();
		store.addGoal(g);
		run(MONDAY_NOON);
		g.setCompletedAt(MONDAY_NOON.toEpochMilli());

		// Tuesday: same week, still done.
		assertEquals(0, run(MONDAY_NOON.plusSeconds(86_400)));
		assertTrue(g.isComplete());

		// Wednesday: new week.
		assertEquals(1, run(MONDAY_NOON.plusSeconds(2 * 86_400)));
		assertFalse(g.isComplete());
	}

	@Test
	@DisplayName("a completed repeatable goal stays in Repeatable, never Completed")
	void completedRepeatableStaysPut()
	{
		Goal g = dailyGoal("5 Mahogany Homes contracts");
		store.reconcileDerivedSections();
		String repeatableId = store.getRepeatableSection().getId();
		assertEquals(repeatableId, g.getSectionId());

		g.setCompletedAt(MONDAY_NOON.toEpochMilli());
		store.reconcileDerivedSections();

		assertEquals(repeatableId, g.getSectionId(),
			"a checked-off daily must not graduate to Completed");
	}

	@Test
	@DisplayName("a repeatable goal never lands in Completed, which is what lets both mechanisms share archivedFromSectionId")
	void archivedFromInvariant()
	{
		Section home = store.createUserSection("Bossing");
		Goal g = Goal.builder().type(GoalType.CUSTOM).name("10 CG runs")
			.sectionId(home.getId()).repeatEvery(RepeatPeriod.DAILY).build();
		store.addGoal(g);

		store.reconcileDerivedSections();
		assertEquals(store.getRepeatableSection().getId(), g.getSectionId());
		assertEquals(home.getId(), g.getArchivedFromSectionId(),
			"the home section must be remembered so turning repeat off can restore it");

		// Complete it, then confirm it still is not in Completed - if it ever
		// were, the shared home-memory field would be serving two masters.
		g.setCompletedAt(MONDAY_NOON.toEpochMilli());
		store.reconcileDerivedSections();
		assertEquals(store.getRepeatableSection().getId(), g.getSectionId());
		assertEquals(home.getId(), g.getArchivedFromSectionId());
	}

	@Test
	@DisplayName("turning repeat off returns the goal to the section it came from")
	void repeatOffRestoresHome()
	{
		Section home = store.createUserSection("Bossing");
		Goal g = Goal.builder().type(GoalType.CUSTOM).name("10 CG runs")
			.sectionId(home.getId()).repeatEvery(RepeatPeriod.DAILY).build();
		store.addGoal(g);
		store.reconcileDerivedSections();

		g.setRepeatEvery(RepeatPeriod.NONE);
		store.reconcileDerivedSections();

		assertEquals(home.getId(), g.getSectionId());
	}

	@Test
	@DisplayName("a goal persisted before repetition existed deserializes as non-repeating, not null")
	void legacyGoalHasNoNullPeriod()
	{
		// Gson bypasses Lombok's @Builder.Default and leaves absent fields null.
		Goal legacy = Goal.builder().type(GoalType.CUSTOM).name("Old goal").build();
		legacy.setRepeatEvery(null);
		store.addGoal(legacy);

		assertEquals(RepeatPeriod.NONE, legacy.getRepeatEvery());
		assertFalse(legacy.isRepeating());
		assertEquals(0, run(MONDAY_NOON), "a legacy goal must not be swept into rollover");
	}

	// ====================================================================
	// Derived "gain N per period" goals over a cumulative counter
	// ====================================================================

	/** A daily "gain N more" goal sitting on a cumulative tracker value. */
	private Goal chunkGoal(int alreadyAt, int chunk)
	{
		Goal g = Goal.builder()
			.type(GoalType.BOSS)
			.name("Kill General Graardor")
			.bossName("General Graardor")
			.repeatEvery(RepeatPeriod.DAILY)
			.repeatChunk(chunk)
			.currentValue(alreadyAt)
			.targetValue(alreadyAt + chunk)
			.build();
		store.addGoal(g);
		return g;
	}

	@Test
	@DisplayName("rollover moves the target forward instead of clearing cumulative progress")
	void chunkGoalRebasesTarget()
	{
		Goal g = chunkGoal(1847, 20);
		run(MONDAY_NOON);                    // first stamp, no rollover
		g.setCurrentValue(1867);             // player got their 20 kills
		g.setCompletedAt(System.currentTimeMillis());
		g.setStatus(com.goalplanner.model.GoalStatus.COMPLETE);

		assertEquals(1, run(MONDAY_NOON.plus(java.time.Duration.ofDays(1))));

		assertEquals(1867, g.getCurrentValue(),
			"lifetime kill count must NOT be cleared - the tracker would just write it back");
		assertEquals(1887, g.getTargetValue(), "target must re-base to current + chunk");
		assertFalse(g.isComplete(), "re-basing past the progress must reopen the goal");
	}

	@Test
	@DisplayName("an untouched chunk goal still re-bases, so a skipped day is not owed back")
	void chunkGoalRebasesEvenWhenIgnored()
	{
		Goal g = chunkGoal(1000, 50);
		run(MONDAY_NOON);
		// Player never logs in; progress stays at 1000.
		run(MONDAY_NOON.plus(java.time.Duration.ofDays(1)));

		assertEquals(1050, g.getTargetValue(),
			"a missed day re-bases to today's 50, it does not accumulate to 100");
	}

	@Test
	@DisplayName("a plain manual repeat still clears its progress")
	void manualRepeatStillClears()
	{
		Goal g = dailyGoal("5 Mahogany Homes contracts");
		run(MONDAY_NOON);
		g.setCurrentValue(5);
		g.setCompletedAt(System.currentTimeMillis());
		g.setStatus(com.goalplanner.model.GoalStatus.COMPLETE);

		run(MONDAY_NOON.plus(java.time.Duration.ofDays(1)));

		assertEquals(0, g.getCurrentValue(), "a chunk-less repeat has nothing cumulative to keep");
		assertFalse(g.isComplete());
	}

	@Test
	@DisplayName("a repeatable goal displays progress within the period, not the lifetime total")
	void chunkGoalDisplaysPeriodProgress()
	{
		// 9.8M XP account, daily 10k chunk: the day's work must be visible.
		Goal g = Goal.builder()
			.type(GoalType.SKILL).name("Woodcutting +10,000 XP").skillName("WOODCUTTING")
			.repeatEvery(RepeatPeriod.DAILY).repeatChunk(10_000)
			.currentValue(9_800_000).targetValue(9_810_000)
			.build();
		store.addGoal(g);

		assertEquals(0, g.getDisplayCurrent(), "fresh period reads 0, not 9.8M");
		assertEquals(10_000, g.getDisplayTarget());

		g.setCurrentValue(9_803_500);
		assertEquals(3_500, g.getDisplayCurrent());

		g.setCurrentValue(9_810_000);
		assertEquals(10_000, g.getDisplayCurrent(), "a finished period reads full");
	}

	@Test
	@DisplayName("display progress clamps rather than going negative or overflowing")
	void chunkDisplayClamps()
	{
		Goal g = Goal.builder()
			.type(GoalType.BOSS).name("General Graardor x20").bossName("General Graardor")
			.repeatEvery(RepeatPeriod.DAILY).repeatChunk(20)
			.currentValue(1847).targetValue(1867)
			.build();
		store.addGoal(g);

		// Overshoot: the tracker can report past the target between ticks.
		g.setCurrentValue(1875);
		assertEquals(20, g.getDisplayCurrent(), "must not read above the chunk");

		// Below the period start, which a stale read can produce.
		g.setCurrentValue(1800);
		assertEquals(0, g.getDisplayCurrent(), "must not read negative");
	}

	@Test
	@DisplayName("a non-chunk goal displays its raw values unchanged")
	void plainGoalDisplayUnchanged()
	{
		Goal g = Goal.builder()
			.type(GoalType.SKILL).name("Woodcutting - Level 99").skillName("WOODCUTTING")
			.currentValue(9_800_000).targetValue(13_034_431)
			.build();
		store.addGoal(g);

		assertEquals(9_800_000, g.getDisplayCurrent());
		assertEquals(13_034_431, g.getDisplayTarget());
	}

	@Test
	@DisplayName("after a rollover the display resets to zero of the new chunk")
	void displayResetsAfterRollover()
	{
		Goal g = chunkGoal(1847, 20);
		run(MONDAY_NOON);
		g.setCurrentValue(1867);
		assertEquals(20, g.getDisplayCurrent(), "period complete");

		run(MONDAY_NOON.plus(java.time.Duration.ofDays(1)));

		assertEquals(0, g.getDisplayCurrent(), "new period starts empty");
		assertEquals(20, g.getDisplayTarget());
	}
}
