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
}
