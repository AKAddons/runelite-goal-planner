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
	/** The dock's contextual states. */
	public enum State
	{
		/** Nothing selected: panel-level actions (add, import, share-all). */
		EMPTY,
		/** Exactly one goal selected: that goal's actions. */
		GOAL,
		/** Several goals selected: bulk actions. */
		MULTI,
		/** One section selected: that section's actions. */
		SECTION
	}

	private final State state;
	private final String soleGoalId;
	private final String sectionId;
	private final int count;

	private DockContext(State state, String soleGoalId, String sectionId, int count)
	{
		this.state = state;
		this.soleGoalId = soleGoalId;
		this.sectionId = sectionId;
		this.count = count;
	}

	public static DockContext of(Set<String> selectedGoalIds)
	{
		return of(selectedGoalIds, null);
	}

	/**
	 * Resolve the dock state from the selection. Goals and a section are
	 * mutually exclusive, and a goal selection always wins: any selected goals
	 * yield GOAL/MULTI (the section id is ignored); otherwise a non-null section
	 * id yields SECTION; otherwise EMPTY. Pure - the caller enforces the mutual
	 * exclusion in its own selection model; this just answers "what wins".
	 */
	public static DockContext of(Set<String> selectedGoalIds, String selectedSectionId)
	{
		if (selectedGoalIds != null && !selectedGoalIds.isEmpty())
		{
			if (selectedGoalIds.size() == 1)
			{
				return new DockContext(State.GOAL,
					selectedGoalIds.iterator().next(), null, 1);
			}
			return new DockContext(State.MULTI, null, null, selectedGoalIds.size());
		}
		if (selectedSectionId != null)
		{
			return new DockContext(State.SECTION, null, selectedSectionId, 0);
		}
		return new DockContext(State.EMPTY, null, null, 0);
	}

	public State getState() { return state; }

	/** The selected goal's id in {@link State#GOAL}; null otherwise. */
	public String getSoleGoalId() { return soleGoalId; }

	/** The selected section's id in {@link State#SECTION}; null otherwise. */
	public String getSectionId() { return sectionId; }

	public int getCount() { return count; }
}
