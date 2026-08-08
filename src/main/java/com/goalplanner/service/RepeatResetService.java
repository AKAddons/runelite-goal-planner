package com.goalplanner.service;

import com.goalplanner.ResetBoundary;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalStatus;
import com.goalplanner.persistence.GoalStore;
import com.goalplanner.util.RepeatSchedule;
import java.time.Instant;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;

/**
 * Rolls repeatable goals over when their period ends.
 *
 * <p>Driven by a clock timer rather than the game tick, deliberately: stage-1
 * repeatable goals are CUSTOM goals, which need no client data at all, and the
 * day boundary is usually crossed while the player is logged out - when no
 * game tick fires. The panel's countdown and this check therefore share one
 * source of truth in {@link RepeatSchedule}.
 *
 * <p>Resets are system-driven, so they deliberately do NOT go through the
 * command history. Undoing the user's last edit must not also un-roll a
 * midnight reset.
 *
 * <p>{@code now} is a parameter rather than a {@code System.currentTimeMillis()}
 * call so every rollover case is testable without waiting for midnight.
 */
@Slf4j
public class RepeatResetService
{
	private final GoalStore store;

	public RepeatResetService(GoalStore store)
	{
		this.store = store;
	}

	/**
	 * Roll over every repeatable goal whose period has changed since it was
	 * last stamped.
	 *
	 * <p>Idempotent: calling it repeatedly within one period does nothing, so
	 * running it every minute costs nothing and a missed run is self-healing.
	 * A goal that has never been stamped ({@code lastPeriodKey == 0}) is
	 * stamped without resetting - marking something repeatable while it is
	 * already done should leave it done for the current period, not
	 * immediately reopen it.
	 *
	 * @return how many goals were actually un-completed
	 */
	public int applyResets(Instant now, ResetBoundary boundary, int configuredHour)
	{
		if (now == null)
		{
			return 0;
		}
		ZoneId zone = RepeatSchedule.zoneFor(boundary);
		int hour = RepeatSchedule.hourFor(boundary, configuredHour);

		int resetCount = 0;
		boolean anyStamped = false;

		for (Goal goal : store.getGoals())
		{
			if (!goal.isRepeating())
			{
				continue;
			}
			long liveKey = RepeatSchedule.periodKey(goal.getRepeatEvery(),
				RepeatSchedule.boundaryDate(now, zone, hour));
			long lastKey = goal.getLastPeriodKey();
			if (lastKey == liveKey)
			{
				continue;
			}

			// A real rollover, not a first stamp. Note this fires ONCE however
			// many periods were missed: the keys are compared, never differenced.
			if (lastKey != 0)
			{
				boolean wasComplete = goal.isComplete();
				if (goal.getRepeatChunk() > 0)
				{
					// Derived "gain N more" goal over a cumulative counter. Do
					// NOT clear the progress - the tracker reports a lifetime
					// total and would immediately write it back. Move the goal
					// posts instead: the next N from wherever the player is now.
					//
					// This reads only stored state, never the client, which is
					// what keeps rollovers working while logged out. No progress
					// can happen offline, so the stored value is exact.
					goal.setTargetValue(goal.getCurrentValue() + goal.getRepeatChunk());
				}
				else
				{
					// Plain manual repeat (CUSTOM): nothing cumulative to
					// preserve, so clear the progress outright.
					goal.setCurrentValue(0);
				}

				if (wasComplete)
				{
					goal.setCompletedAt(0);
					goal.setStatus(GoalStatus.ACTIVE);
					resetCount++;
				}
			}

			goal.setLastPeriodKey(liveKey);
			store.markGoalDirty(goal.getId());
			anyStamped = true;
		}

		if (anyStamped)
		{
			store.saveDirtyGoals();
			// Placement can change: a goal that just reopened is still
			// repeating, so it stays in Repeatable - but reconciling keeps the
			// section membership honest if anything else moved underneath us.
			store.reconcileDerivedSections();
		}
		if (resetCount > 0)
		{
			log.info("Repeatable goals rolled over: {} reset", resetCount);
		}
		return resetCount;
	}
}
