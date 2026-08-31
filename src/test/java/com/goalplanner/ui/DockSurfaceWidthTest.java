package com.goalplanner.ui;

import java.awt.Component;
import java.awt.Container;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Field report 2026-08-27 (screenshot): the Options surface showed "Join our
 * Discord | Import shared goals... | Sav" - every chip past the dock edge was
 * simply CLIPPED. The expanded surface rode a HORIZONTAL_SCROLLBAR_NEVER
 * viewport as a plain panel, which does not track viewport width, so
 * WrapLayout measured against MAX_VALUE and laid one endless row.
 *
 * <p>The tree harness cannot see this - the chips exist and carry the right
 * text - so this net asserts GEOMETRY: after a real layout at panel width,
 * every visible button in the dock must fit inside the panel's bounds.
 */
class DockSurfaceWidthTest extends HeadlessRenderTest
{
	@Test
	@DisplayName("every chip on a wide choice surface stays inside the panel")
	void wideChoiceSurfaceWraps() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			Object[] holder = seeded();
			GoalPanel panel = (GoalPanel) holder[0];
			// The Options list: six chips, the widest choice surface shipped.
			panel.openChoiceSurface("Options", new String[]{
				"Join our Discord", "Import shared goals...", "Saved plans...",
				"Remove duplicate goals", "Delete empty sections",
				"Delete all goals and sections"}, -1, i -> { });
			panel.setSize(WIDTH, 520);
			layoutDeep(panel);

			int overflow = 0;
			StringBuilder out = new StringBuilder();
			for (AbstractButton b : buttonsOf(panel))
			{
				if (!b.isShowing() && b.getWidth() == 0)
				{
					continue;
				}
				java.awt.Point p = SwingUtilities.convertPoint(
					b.getParent(), b.getLocation(), panel);
				// Only the RIGHT edge: a wrap failure clips rightward, while a
				// negative x is viewport-scrolled content, not clipping.
				if (p.x + b.getWidth() > WIDTH)
				{
					overflow++;
					out.append("\n  ").append(b.getText())
						.append(" @x=").append(p.x)
						.append(" w=").append(b.getWidth());
				}
			}
			assertEquals(0, overflow,
				"chips clipped past the dock edge (panel width " + WIDTH + "):" + out);
		});
	}

	private static java.util.List<AbstractButton> buttonsOf(Container root)
	{
		java.util.List<AbstractButton> out = new java.util.ArrayList<>();
		for (Component c : root.getComponents())
		{
			if (c instanceof AbstractButton)
			{
				out.add((AbstractButton) c);
			}
			if (c instanceof Container)
			{
				out.addAll(buttonsOf((Container) c));
			}
		}
		return out;
	}
}
