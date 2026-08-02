package com.goalplanner.model;

/**
 * How often a goal resets and becomes available to complete again.
 *
 * <p>{@link #NONE} is the ordinary one-shot goal: completion is terminal.
 * Every other value makes the goal repeatable - it lives in the built-in
 * Repeatable section, stays there when checked off, and un-completes when its
 * period rolls over.
 *
 * <p>The periods are NOT a uniform ladder: months are not a fixed number of
 * days, so each one carries its own bucketing rule in
 * {@link com.goalplanner.util.RepeatSchedule}. Do not assume ordinal order
 * matches deadline order either - a monthly goal on the 31st resets sooner
 * than a weekly one that waits for Wednesday.
 */
public enum RepeatPeriod
{
	NONE("Off"),
	DAILY("Daily"),
	WEEKLY("Weekly"),
	MONTHLY("Monthly");

	private final String label;

	RepeatPeriod(String label)
	{
		this.label = label;
	}

	/** True for every value except {@link #NONE}. */
	public boolean isRepeating()
	{
		return this != NONE;
	}

	public String getLabel()
	{
		return label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
