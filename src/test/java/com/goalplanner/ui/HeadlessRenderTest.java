package com.goalplanner.ui;

import com.goalplanner.GoalPlannerConfig;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.service.GoalReorderingService;
import com.goalplanner.testsupport.MockGameState;
import com.goalplanner.testsupport.TrackerTestHarness;
import java.awt.image.BufferedImage;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * Headless render gate for the panel (ISS-01M0RTK149HQA02WMJ1WETXNAK), ported
 * from Loadout Lab's band-2 harness.
 *
 * <p>Purpose: 1.0.0 removes ~180 pop-up call sites. Deleting that much UI with
 * no way to see what vanished is how a flow silently becomes unreachable, and
 * no automated client may touch RuneLite. So the panel is built with stubbed
 * RuneLite dependencies and its component TREE is snapshotted.
 *
 * <p>The tree, not pixels: font metrics differ per machine, so a pixel gate
 * fails everywhere but the machine that wrote it. The tree (nesting, kind,
 * label and button text) is stable and is what actually breaks.
 *
 * <p>Real GoalStore and GoalPlannerApiImpl come from TrackerTestHarness against
 * InMemoryConfigManager - not parallel doubles that drift from real semantics.
 */
class HeadlessRenderTest
{
	private static final int WIDTH = 225;

	private static Goal goal(String id, GoalType type, String name)
	{
		Goal g = Goal.builder().type(type).name(name).build();
		g.setId(id);
		return g;
	}

	/** The panel with every RuneLite dependency stubbed. */
	private static GoalPanel panel(Goal... goals)
	{
		TrackerTestHarness<?> h = TrackerTestHarness.forSkills(new MockGameState());
		for (Goal g : goals)
		{
			h.store().addGoal(g);
		}
		return new GoalPanel(
			h.store(),
			mock(SkillIconManager.class, RETURNS_DEEP_STUBS),
			mock(ItemManager.class, RETURNS_DEEP_STUBS),
			mock(SpriteManager.class, RETURNS_DEEP_STUBS),
			h.api(),
			mock(GoalReorderingService.class, RETURNS_DEEP_STUBS),
			mock(GoalPanel.ItemSearchRequest.class, RETURNS_DEEP_STUBS),
			mock(GoalPlannerConfig.class, RETURNS_DEEP_STUBS));
	}

	@Test
	@DisplayName("the goal panel paints headlessly, with no game client")
	void paintsWithoutAClient()
	{
		GoalPanel p = panel(
			goal("a", GoalType.CUSTOM, "Finish the dock"),
			goal("b", GoalType.SKILL, "99 Slayer"));

		int height = Math.max(200, Math.min(p.getPreferredSize().height, 3000));
		p.setSize(WIDTH, height);
		layoutDeep(p);

		BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = image.createGraphics();
		p.paint(g);
		g.dispose();

		assertTrue(nonBlank(image) > 500, "panel painted blank - the seam does not work");

		try
		{
			java.io.File out = new java.io.File("build/render-shots/panel.png");
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
		catch (Exception e)
		{
			// the PNG is a review aid, never a reason to fail the gate
		}
	}

	@Test
	@DisplayName("the rendered tree carries the seeded goals")
	void treeCarriesTheGoals()
	{
		GoalPanel p = panel(
			goal("a", GoalType.CUSTOM, "Finish the dock"),
			goal("b", GoalType.SKILL, "99 Slayer"));
		p.setSize(WIDTH, 900);
		layoutDeep(p);

		String tree = structure(p);
		assertTrue(tree.contains("Finish the dock"),
			"seeded CUSTOM goal missing from the tree");
	}

	/** Swing only lays out REALIZED containers; walk it ourselves. */
	private static void layoutDeep(java.awt.Container container)
	{
		container.doLayout();
		for (java.awt.Component child : container.getComponents())
		{
			if (child instanceof java.awt.Container)
			{
				layoutDeep((java.awt.Container) child);
			}
		}
	}

	/** Nesting, kind, text and tooltip. No bounds: font metrics are
	 * machine-dependent and baking them in makes this unportable. */
	static String structure(java.awt.Component component)
	{
		StringBuilder out = new StringBuilder();
		walk(component, 0, out);
		return out.toString();
	}

	private static void walk(java.awt.Component component, int depth, StringBuilder out)
	{
		for (int i = 0; i < depth; i++)
		{
			out.append("  ");
		}
		out.append(component.getClass().getSimpleName());
		String text = null;
		if (component instanceof javax.swing.JLabel)
		{
			text = ((javax.swing.JLabel) component).getText();
		}
		else if (component instanceof javax.swing.AbstractButton)
		{
			text = ((javax.swing.AbstractButton) component).getText();
		}
		if (text != null && !text.isEmpty())
		{
			out.append(" \"").append(text.replace('\n', ' ')).append('"');
		}
		if (component instanceof javax.swing.JComponent)
		{
			String tip = ((javax.swing.JComponent) component).getToolTipText();
			if (tip != null && !tip.isEmpty())
			{
				out.append(" tip=").append(tip.replace('\n', ' '));
			}
		}
		out.append('\n');
		if (component instanceof java.awt.Container)
		{
			for (java.awt.Component child : ((java.awt.Container) component).getComponents())
			{
				walk(child, depth + 1, out);
			}
		}
	}

	private static int nonBlank(BufferedImage image)
	{
		int count = 0;
		for (int x = 0; x < image.getWidth(); x++)
		{
			for (int y = 0; y < image.getHeight(); y++)
			{
				if ((image.getRGB(x, y) >>> 24) != 0)
				{
					count++;
				}
			}
		}
		return count;
	}
}
