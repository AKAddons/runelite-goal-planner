package com.goalplanner.ui;

/**
 * Pure helpers for resolving "gain X more" relative goal inputs into the
 * absolute target values stored on Goal entities.
 *
 * <p>Relative goals are an input convenience only - once resolved, the goal
 * is indistinguishable from one created with the same absolute target. The
 * math lives here so it can be unit-tested without spinning up Swing or the
 * RuneLite client.
 */
public final class RelativeTargetResolver
{
	private RelativeTargetResolver() {}

	/** OSRS XP cap. Skill XP saturates here regardless of what the user enters. */
	public static final int MAX_SKILL_XP = 200_000_000;

	/**
	 * Resolve a "gain N XP" delta against the player's current XP for a skill.
	 * Negative or zero deltas are returned as -1 to signal "invalid input" so
	 * the caller can show an error message.
	 *
	 * @param currentXp the player's current XP for the skill (>= 0)
	 * @param deltaXp the XP the user wants to gain (must be > 0)
	 * @return resolved absolute XP target, clamped at {@link #MAX_SKILL_XP},
	 *         or -1 if delta is non-positive
	 */
	public static int resolveSkillXp(int currentXp, int deltaXp)
	{
		if (deltaXp <= 0) return -1;
		long sum = (long) Math.max(0, currentXp) + (long) deltaXp;
		return (int) Math.min((long) MAX_SKILL_XP, sum);
	}

	/**
	 * Resolve a "get N more kills" delta against the player's current kill count for
	 * a boss/activity. An unknown or unreadable current count is treated as 0, so the
	 * target falls back to the raw delta rather than failing - the user still gets a
	 * goal (used by the dock's boss Relative mode, Task 2).
	 *
	 * @param currentKc the player's current kill count (an unknown/negative read is floored to 0)
	 * @param delta the extra kills the user wants (must be > 0)
	 * @return resolved absolute kill target, or -1 if delta is non-positive
	 */
	public static int resolveKillCount(int currentKc, int delta)
	{
		if (delta <= 0) return -1;
		long sum = (long) Math.max(0, currentKc) + (long) delta;
		return (int) Math.min((long) Integer.MAX_VALUE, sum);
	}

}
