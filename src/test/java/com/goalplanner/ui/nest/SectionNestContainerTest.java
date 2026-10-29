package com.goalplanner.ui.nest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SectionNestContainer — gutter and collapse chevrons")
class SectionNestContainerTest
{
	private static JPanel card()
	{
		JPanel p = new JPanel();
		p.setPreferredSize(new Dimension(200, 48));
		return p;
	}

	private static JLabel chevron(SectionNestContainer c)
	{
		for (Component child : c.getComponents())
		{
			if (child instanceof JLabel) return (JLabel) child;
		}
		return null;
	}

	@Test
	@DisplayName("a collapsed root keeps its gutter, so the expand chevron stays clear of the card and clickable")
	void collapsedRootChevronStaysReachable()
	{
		// Collapsing a root hides every indented row, leaving only level 0 visible.
		// A 0 gutter then put the card on top of its own chevron - stuck collapsed.
		JPanel card = card();
		List<String> toggled = new ArrayList<>();
		SectionNestContainer c = new SectionNestContainer(
			Arrays.asList(new SectionNestContainer.Row("root", 0, card, true, true)), toggled::add);
		c.setSize(225, 100);
		c.doLayout();

		JLabel chev = chevron(c);
		assertNotNull(chev, "a parent row must get a chevron");
		assertTrue(chev.getX() + chev.getWidth() <= card.getX(),
			"chevron " + chev.getBounds() + " is under the card at x=" + card.getX());

		chev.dispatchEvent(new MouseEvent(chev, MouseEvent.MOUSE_PRESSED, 0, 0, 2, 2, 1, false, MouseEvent.BUTTON1));
		assertEquals(Arrays.asList("root"), toggled, "clicking the chevron must toggle the goal");
	}

	@Test
	@DisplayName("a flat section with no chevrons still renders without a gutter")
	void flatSectionHasNoGutter()
	{
		JPanel card = card();
		SectionNestContainer c = new SectionNestContainer(
			Arrays.asList(new SectionNestContainer.Row("a", 0, card)), id -> {});
		c.setSize(225, 100);
		c.doLayout();

		assertEquals(0, card.getX());
	}
}
