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
	static final int WIDTH = 225;

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

	@Test
	@DisplayName("no styling helper calls itself - a refactor once made three recurse")
	void helpersDoNotRecurse() throws Exception
	{
		String src = new String(java.nio.file.Files.readAllBytes(
			java.nio.file.Paths.get("src/main/java/com/goalplanner/ui/GoalPanel.java")),
			java.nio.charset.StandardCharsets.UTF_8);
		java.util.regex.Matcher m = java.util.regex.Pattern
			.compile("\\tprivate static void (\\w+)\\(JComponent c\\)\\n\\t\\{\\n(.*?)\\n\\t\\}",
				java.util.regex.Pattern.DOTALL)
			.matcher(src);
		java.util.List<String> bad = new java.util.ArrayList<>();
		while (m.find())
		{
			if (m.group(2).matches("(?s).*\\b" + m.group(1) + "\\s*\\(c\\).*"))
			{
				bad.add(m.group(1));
			}
		}
		assertTrue(bad.isEmpty(), "self-recursive styling helpers: " + bad);
	}

	/**
	 * The DESIGN review artifact (REQ-003): paint each dock surface and lay them
	 * out as one contact sheet. The dock has never been seen running - the
	 * progress doc says every layout choice is provisional - so this is the
	 * cheapest way to get eyes on it without a client.
	 *
	 * <p>Driven through real entry points (replaceGoalSelection,
	 * openSeededCreate), not reflection, so what is painted is what the plugin
	 * actually builds.
	 */
	@Test
	@DisplayName("dock surfaces render into a contact sheet for design review")
	void dockContactSheet() throws Exception
	{
		java.util.List<BufferedImage> shots = new java.util.ArrayList<>();
		java.util.List<String> names = new java.util.ArrayList<>();

		// 1. idle, a few goals
		shots.add(shoot(seeded(), 520)); names.add("idle");

		// 2. one goal selected - the dock's main surface
		Object[] one = seeded();
		GoalPanel p1 = (GoalPanel) one[0];
		((com.goalplanner.api.GoalPlannerApiImpl) one[1])
			.replaceGoalSelection(java.util.Collections.singletonList("skill1"));
		p1.refreshSelection();
		shots.add(shoot(new Object[]{p1}, 520)); names.add("one selected");

		// 3. multi-selection - the bulk surface
		Object[] two = seeded();
		GoalPanel p2 = (GoalPanel) two[0];
		((com.goalplanner.api.GoalPlannerApiImpl) two[1])
			.replaceGoalSelection(java.util.Arrays.asList("skill1", "boss1"));
		p2.refreshSelection();
		shots.add(shoot(new Object[]{p2}, 520)); names.add("multi selected");

		// 4-6. the create surface, seeded per type
		for (GoalType t : new GoalType[]{GoalType.SKILL, GoalType.BOSS, GoalType.CUSTOM})
		{
			Object[] c = seeded();
			GoalPanel pc = (GoalPanel) c[0];
			pc.openSeededCreate(t, null, t == GoalType.BOSS ? "Zulrah" : null,
				null, null, null);
			shots.add(shoot(new Object[]{pc}, 520));
			names.add("create: " + t);
		}

		// 7. the explicit choice surface (replaces tap-to-cycle chips)
		Object[] ch = seeded();
		GoalPanel pch = (GoalPanel) ch[0];
		pch.openChoiceSurface("Nesting",
			new String[]{"Default (flat)", "Nested", "Flat"}, 0, i -> { });
		shots.add(shoot(new Object[]{pch}, 520)); names.add("choice: Nesting");

		contactSheet(shots, names, "dock-contact-sheet.png");
		assertEquals(7, shots.size());
	}

	/** A panel plus its api, seeded with one goal of each common type. */
	static Object[] seeded()
	{
		TrackerTestHarness<?> h = TrackerTestHarness.forSkills(new MockGameState());
		h.store().addGoal(goal("skill1", GoalType.SKILL, "99 Slayer"));
		h.store().addGoal(goal("boss1", GoalType.BOSS, "500 Zulrah"));
		h.store().addGoal(goal("cust1", GoalType.CUSTOM, "Finish the dock"));
		GoalPanel p = new GoalPanel(h.store(),
			mock(SkillIconManager.class, RETURNS_DEEP_STUBS),
			mock(ItemManager.class, RETURNS_DEEP_STUBS),
			mock(SpriteManager.class, RETURNS_DEEP_STUBS),
			h.api(),
			mock(GoalReorderingService.class, RETURNS_DEEP_STUBS),
			mock(GoalPanel.ItemSearchRequest.class, RETURNS_DEEP_STUBS),
			mock(GoalPlannerConfig.class, RETURNS_DEEP_STUBS));
		return new Object[]{p, h.api()};
	}

	private static BufferedImage shoot(Object[] panelHolder, int height)
	{
		GoalPanel p = (GoalPanel) panelHolder[0];
		p.setSize(WIDTH, height);
		layoutDeep(p);
		BufferedImage img = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = img.createGraphics();
		p.paint(g);
		g.dispose();
		return img;
	}

	private static void contactSheet(java.util.List<BufferedImage> shots,
		java.util.List<String> names, String file) throws Exception
	{
		int gap = 8, tall = 0, wide = gap;
		for (BufferedImage s : shots) { tall = Math.max(tall, s.getHeight()); wide += s.getWidth() + gap; }
		BufferedImage sheet = new BufferedImage(wide, tall + 24, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = sheet.createGraphics();
		g.setColor(new java.awt.Color(30, 30, 30));
		g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
		g.setColor(java.awt.Color.LIGHT_GRAY);
		int x = gap;
		for (int i = 0; i < shots.size(); i++)
		{
			g.drawImage(shots.get(i), x, 20, null);
			g.drawString(names.get(i), x, 14);
			x += shots.get(i).getWidth() + gap;
		}
		g.dispose();
		java.io.File out = new java.io.File("build/render-shots/" + file);
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(sheet, "png", out);
	}

	/** Swing only lays out REALIZED containers; walk it ourselves. */
	static void layoutDeep(java.awt.Container container)
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
