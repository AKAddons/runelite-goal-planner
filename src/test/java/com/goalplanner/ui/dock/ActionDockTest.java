package com.goalplanner.ui.dock;

import javax.swing.JButton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared full-width "lead" button factory. The single-goal edit surface
 * reuses this so its top-pinned Deselect matches the MULTI "Deselect (N)" bar.
 * Everything else about the dock is render-path and verified by screenshot.
 */
class ActionDockTest
{
	@Test
	@DisplayName("lead button carries its label and tooltip")
	void labelAndTooltip()
	{
		JButton b = ActionDock.leadButton("Deselect", "Clear the selection", () -> { });
		assertEquals("Deselect", b.getText());
		assertEquals("Clear the selection", b.getToolTipText());
	}

	@Test
	@DisplayName("clicking a lead button runs its action")
	void actionFires()
	{
		boolean[] ran = {false};
		JButton b = ActionDock.leadButton("Deselect", "tip", () -> ran[0] = true);
		b.doClick();
		assertTrue(ran[0], "the lead button's action should run on click");
	}

	@Test
	@DisplayName("a null action is tolerated and clicking is a no-op")
	void nullActionIsSafe()
	{
		JButton b = ActionDock.leadButton("Deselect", "tip", null);
		assertDoesNotThrow(() -> b.doClick());
	}
}
