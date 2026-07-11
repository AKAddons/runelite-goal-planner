package com.goalplanner.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-function tests for the Loadout Lab link-in menu state. The Swing
 * wiring (menu items per state) is verified manually against the receiver;
 * this covers the decision logic the plugin feeds it from PluginManager
 * facts, without touching Swing or the RuneLite client.
 */
class GoalPanelLoadoutLabSupportTest
{
	@Test
	@DisplayName("an enabled copy resolves to ENABLED")
	void enabledWins()
	{
		assertEquals(GoalPanel.LoadoutLabState.ENABLED,
			GoalPanel.LoadoutLabState.resolve(true, true));
	}

	@Test
	@DisplayName("enabled wins even if the installed fact is stale-false (enabled implies installed)")
	void enabledWinsOverStaleInstalledFact()
	{
		assertEquals(GoalPanel.LoadoutLabState.ENABLED,
			GoalPanel.LoadoutLabState.resolve(true, false));
	}

	@Test
	@DisplayName("installed but no enabled copy resolves to INSTALLED_DISABLED")
	void installedButDisabled()
	{
		assertEquals(GoalPanel.LoadoutLabState.INSTALLED_DISABLED,
			GoalPanel.LoadoutLabState.resolve(false, true));
	}

	@Test
	@DisplayName("no copy at all resolves to NOT_INSTALLED")
	void notInstalled()
	{
		assertEquals(GoalPanel.LoadoutLabState.NOT_INSTALLED,
			GoalPanel.LoadoutLabState.resolve(false, false));
	}
}
