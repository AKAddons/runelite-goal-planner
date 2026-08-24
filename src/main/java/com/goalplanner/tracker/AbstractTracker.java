package com.goalplanner.tracker;

import com.goalplanner.api.GoalPlannerApiImpl;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalStatus;
import com.goalplanner.model.GoalType;
import java.util.List;
import net.runelite.api.Client;
import java.util.ArrayList;

/**
 * Base class for goal trackers. Provides the shared loop that filters
 * goals by type and status, reads the current value from the game
 * client, and records progress via the API. Subclasses implement only
 * the value-extraction logic.
 *
 * <p>Leagues/main goal isolation is handled at the persistence layer
 * via profile-scoped storage, not here - when on a leagues world, only
 * leagues-profile goals are loaded into memory, so this tracker only
 * ever sees the goals relevant to the current world.
 */
public abstract class AbstractTracker
{
	protected final Client client;
	protected final GoalPlannerApiImpl api;

	protected AbstractTracker(Client client, GoalPlannerApiImpl api)
	{
		this.client = client;
		this.api = api;
	}

	/** The goal type this tracker handles. */
	protected abstract GoalType targetType();

	/**
	 * Read the current value for a goal from the game client.
	 * Return -1 to skip this goal (e.g. unreadable state).
	 */
	protected abstract int readCurrentValue(Goal goal);

	/**
	 * Extra skip logic beyond type and status filtering.
	 * Override to add guards (e.g. null field checks).
	 * Default: track everything of the right type.
	 */
	protected boolean shouldTrack(Goal goal)
	{
		return true;
	}

	/**
	 * Whether a COMPLETE goal should still be re-read from the client.
	 *
	 * <p>Default false: completion is sticky, because the backing value for
	 * almost everything tracked here (XP, quest state, kill counts, diary
	 * varbits) is monotonic in-game. Override and return true only for goals
	 * whose backing value can genuinely FALL - then completion is derived
	 * absolutely from the live value and the goal re-opens when it drops below
	 * target (see {@link AccountTracker} / {@code AccountMetric.decays()}).
	 */
	protected boolean tracksAfterCompletion(Goal goal)
	{
		return false;
	}

	/**
	 * Check all goals of this tracker's type and update progress.
	 * Returns true if any goal was updated.
	 */
	public boolean checkGoals(List<Goal> goals)
	{
		boolean anyUpdated = false;

		// Snapshot to avoid ConcurrentModificationException if the list
		// is modified by a compound transaction on another thread.
		//
		// Skip goals that are already COMPLETE. For the types handled by
		// AbstractTracker subclasses (QUEST, DIARY, COMBAT_ACHIEVEMENT,
		// BOSS, ACCOUNT, SKILL) the backing varbit/varp/kc/xp is monotonic
		// in-game - completion never regresses. A tracker read returning a
		// below-target value for a previously-complete goal therefore
		// almost always means the backing value hasn't synced yet (pre-
		// login window, profile switch, brief zero reads). Keeping COMPLETE
		// goals terminal prevents the original completedAt from being
		// clobbered by a transient zero read and subsequently overwritten
		// with System.currentTimeMillis() when the real value arrives.
		//
		// Manual un-completion is still supported via the user-facing
		// markGoalIncomplete path (CUSTOM goals only); for auto-tracked
		// types the user can re-run tracking by editing the backing goal
		// state explicitly.
		//
		// The one exception is a goal whose backing value can actually FALL
		// (Miscellania favour decays, slayer points get spent). Those opt in
		// via tracksAfterCompletion(goal) and keep being read while COMPLETE,
		// so recordGoalProgress can clear completedAt the moment the live
		// value drops below target. BLOCKED/PAUSED goals are never read.
		//
		// ItemTracker overrides this method entirely for its own semantics.
		List<Goal> snapshot = new ArrayList<>(goals);
		for (Goal goal : snapshot)
		{
			if (goal.getType() != targetType()) continue;
			if (goal.getStatus() != GoalStatus.ACTIVE
				&& !(goal.getStatus() == GoalStatus.COMPLETE && tracksAfterCompletion(goal)))
			{
				continue;
			}

			if (!shouldTrack(goal))
			{
				continue;
			}

			int currentValue = readCurrentValue(goal);
			if (currentValue >= 0 && api.recordGoalProgress(goal.getId(), currentValue))
			{
				anyUpdated = true;
			}
		}

		return anyUpdated;
	}
}
