package com.goalplanner.ui.dock;

import java.util.Set;

/**
 * What the action dock should show, derived purely from the current
 * selection. See ADR-0007: the dock is selection-driven - it answers "what
 * does an action operate on?" with whatever the user has selected, replacing
 * the positional answer the right-click menus gave.
 *
 * <p>Pure by design (no Swing, no store): the state decision is the part a
 * test can hold still, mirroring {@code AccountBindingGate} and
 * {@code RepeatSchedule}.
 */
public final class DockContext
{
	/** The dock's contextual states. SECTION is reserved for the
	 *  section-selection pass and never produced yet. */
	public enum State
	{
		/** Nothing selected: panel-level actions (add, import, share-all). */
		EMPTY,
		/** Exactly one goal selected: that goal's actions. */
		GOAL,
		/** Several goals selected: bulk actions. */
		MULTI,
		/** One section selected (future pass). */
		SECTION
	}

	private final State state;
	private final String soleGoalId;
	private final int count;

	private DockContext(State state, String soleGoalId, int count)
	{
		this.state = state;
		this.soleGoalId = soleGoalId;
		this.count = count;
	}

	public static DockContext of(Set<String> selectedGoalIds)
	{
		if (selectedGoalIds == null || selectedGoalIds.isEmpty())
		{
			return new DockContext(State.EMPTY, null, 0);
		}
		if (selectedGoalIds.size() == 1)
		{
			return new DockContext(State.GOAL,
				selectedGoalIds.iterator().next(), 1);
		}
		return new DockContext(State.MULTI, null, selectedGoalIds.size());
	}

	public State getState() { return state; }

	/** The selected goal's id in {@link State#GOAL}; null otherwise. */
	public String getSoleGoalId() { return soleGoalId; }

	public int getCount() { return count; }
}
