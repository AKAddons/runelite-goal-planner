package com.goalplanner.ui.dock;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The dock's state decision (ADR-0007). Small on purpose - everything else
 * about the dock is render-path and verified by screenshot.
 */
class DockContextTest
{
	@Test
	@DisplayName("no selection is the panel-level state")
	void emptySelection()
	{
		assertEquals(DockContext.State.EMPTY, DockContext.of(Set.of()).getState());
		assertEquals(DockContext.State.EMPTY, DockContext.of(null).getState());
		assertNull(DockContext.of(Set.of()).getSoleGoalId());
	}

	@Test
	@DisplayName("one goal selected carries its id")
	void singleSelection()
	{
		DockContext ctx = DockContext.of(Set.of("g1"));
		assertEquals(DockContext.State.GOAL, ctx.getState());
		assertEquals("g1", ctx.getSoleGoalId());
		assertEquals(1, ctx.getCount());
	}

	@Test
	@DisplayName("several goals selected is the bulk state with a count")
	void multiSelection()
	{
		DockContext ctx = DockContext.of(Set.of("g1", "g2", "g3"));
		assertEquals(DockContext.State.MULTI, ctx.getState());
		assertNull(ctx.getSoleGoalId(), "no single target in bulk state");
		assertEquals(3, ctx.getCount());
	}

	@Test
	@DisplayName("a section selected with no goals is the section state carrying its id")
	void sectionSelection()
	{
		DockContext ctx = DockContext.of(Set.of(), "s1");
		assertEquals(DockContext.State.SECTION, ctx.getState());
		assertEquals("s1", ctx.getSectionId());
		assertNull(ctx.getSoleGoalId(), "no goal target in section state");
	}

	@Test
	@DisplayName("selected goals win over a section id")
	void goalsWinOverSection()
	{
		DockContext one = DockContext.of(Set.of("g1"), "s1");
		assertEquals(DockContext.State.GOAL, one.getState());
		assertEquals("g1", one.getSoleGoalId());
		assertNull(one.getSectionId(), "goal selection suppresses the section");

		DockContext many = DockContext.of(Set.of("g1", "g2"), "s1");
		assertEquals(DockContext.State.MULTI, many.getState());
		assertNull(many.getSectionId(), "bulk selection suppresses the section");
	}

	@Test
	@DisplayName("nothing selected and no section is the empty state")
	void nothingSelected()
	{
		assertEquals(DockContext.State.EMPTY, DockContext.of(Set.of(), null).getState());
		assertEquals(DockContext.State.EMPTY, DockContext.of(null, null).getState());
		assertNull(DockContext.of(Set.of(), null).getSectionId());
	}
}
