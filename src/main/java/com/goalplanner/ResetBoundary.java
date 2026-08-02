package com.goalplanner;

/**
 * Where the day boundary falls for repeatable goals.
 *
 * <p>{@link #GAME_RESET} matches the in-game daily reset (00:00 UTC) that
 * herb boxes and Tears of Guthix already run on, so a shared daily routine
 * rolls over at the same moment for everyone who imports it. The local options
 * trade that for matching the player's own sense of "a day".
 */
public enum ResetBoundary
{
	GAME_RESET("Game reset (00:00 UTC)"),
	LOCAL_MIDNIGHT("Local midnight"),
	CUSTOM_HOUR("Custom hour (local)");

	private final String label;

	ResetBoundary(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
