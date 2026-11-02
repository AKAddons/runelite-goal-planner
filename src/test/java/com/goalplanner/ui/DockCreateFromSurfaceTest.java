package com.goalplanner.ui;

import com.goalplanner.api.GoalPlannerApiImpl;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Field report 2026-11-01 (video): open the "..." Options surface, press Create
 * Goal, and nothing in the create grid responds. The footer mounted the grid over
 * a choice overlay that was still armed, so the next refresh saw that overlay
 * "already mounted" and returned before reaching the create surface.
 */
class DockCreateFromSurfaceTest
{
	private static final String OPTIONS_CHIP = "Join our Discord";

	private static GoalPanel panel()
	{
		GoalPanel p = (GoalPanel) HeadlessRenderTest.seeded()[0];
		p.setSize(HeadlessRenderTest.WIDTH, 900);
		return p;
	}

	private static AbstractButton find(Container root, String text, String tooltip)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof AbstractButton)
			{
				AbstractButton b = (AbstractButton) c;
				if ((text != null && text.equals(b.getText()))
					|| (tooltip != null && tooltip.equals(b.getToolTipText())))
				{
					return b;
				}
			}
			if (c instanceof Container)
			{
				AbstractButton found = find((Container) c, text, tooltip);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static void click(GoalPanel p, String text)
	{
		AbstractButton b = find(p, text, null);
		assertNotNull(b, "no button '" + text + "' in:\n" + HeadlessRenderTest.structure(p));
		b.doClick();
	}

	private static void openOptions(GoalPanel p)
	{
		AbstractButton options = find(p, null, "Options...");
		assertNotNull(options, "no Options button");
		options.doClick();
		assertNotNull(find(p, OPTIONS_CHIP, null), "Options surface did not open");
	}

	private static String tree(GoalPanel p)
	{
		return HeadlessRenderTest.structure(p);
	}

	/** The form for a tile replaces the grid, so its sibling tile is gone. */
	private static void assertTileOpensItsForm(GoalPanel p)
	{
		click(p, "Skill");
		assertNull(find(p, "Quest", null), "tile click did nothing:\n" + tree(p));
	}

	@Test
	@DisplayName("Create Goal after Options leaves a create grid that responds to clicks")
	void createGoalAfterOptions() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			GoalPanel p = panel();
			openOptions(p);
			click(p, "Create Goal");
			assertNull(find(p, OPTIONS_CHIP, null), "Options still mounted");
			assertTileOpensItsForm(p);
		});
	}

	@Test
	@DisplayName("Create Goal over Options opened from the grid switches back rather than collapsing")
	void createGoalAfterGridThenOptions() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			GoalPanel p = panel();
			click(p, "Create Goal");
			openOptions(p);
			click(p, "Create Goal");
			assertNull(find(p, OPTIONS_CHIP, null), "Options still mounted");
			assertTileOpensItsForm(p);
		});
	}

	@Test
	@DisplayName("Create Section after Options stays on the new-section form")
	void createSectionAfterOptions() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			GoalPanel p = panel();
			openOptions(p);
			click(p, "Create Section");
			assertNull(find(p, OPTIONS_CHIP, null), "Options still mounted");
			click(p, "Create Goal");
			assertTileOpensItsForm(p);
		});
	}

	@Test
	@DisplayName("Create Goal with a section selected leaves a grid that responds to clicks")
	void createGoalWithSectionSelected() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Object[] seeded = HeadlessRenderTest.seeded();
				GoalPanel p = (GoalPanel) seeded[0];
				p.setSize(HeadlessRenderTest.WIDTH, 900);
				String sectionId = ((GoalPlannerApiImpl) seeded[1]).createSection("Raids");
				Field selected = GoalPanel.class.getDeclaredField("selectedSectionId");
				selected.setAccessible(true);
				selected.set(p, sectionId);
				Method refresh = GoalPanel.class.getDeclaredMethod("refreshDock");
				refresh.setAccessible(true);
				refresh.invoke(p);

				click(p, "Create Goal");
				assertTileOpensItsForm(p);
			}
			catch (ReflectiveOperationException e)
			{
				throw new AssertionError(e);
			}
		});
	}

	@Test
	@DisplayName("the grab handle's dismiss closes Options back to the footer")
	void dismissClosesOptions() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				GoalPanel p = panel();
				openOptions(p);
				Method dismiss = GoalPanel.class.getDeclaredMethod("dismissDock");
				dismiss.setAccessible(true);
				dismiss.invoke(p);
				// A collapse hides the surface rather than unmounting it.
				for (Component c = find(p, OPTIONS_CHIP, null); c != null && c != p; c = c.getParent())
				{
					if (!c.isVisible())
					{
						return;
					}
				}
				fail("Options still visible after dismiss");
			}
			catch (ReflectiveOperationException e)
			{
				throw new AssertionError(e);
			}
		});
	}
}
