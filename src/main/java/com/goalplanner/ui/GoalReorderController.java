package com.goalplanner.ui;

import com.goalplanner.api.GoalPlannerApiImpl;
import com.goalplanner.api.GoalView;
import com.goalplanner.model.Goal;
import com.goalplanner.persistence.GoalStore;

import java.util.List;

/**
 * Handles goal reordering within sections, including topo-aware chain
 * moves that respect requirement edges. Extracted from GoalPanel.
 */
class GoalReorderController
{
	private final GoalPlannerApiImpl api;
	private final GoalStore goalStore;

	GoalReorderController(GoalPlannerApiImpl api, GoalStore goalStore)
	{
		this.api = api;
		this.goalStore = goalStore;
	}


	/** Move a goal directly to a target index within its section. */
	void moveGoalTo(String goalId, int toIndex)
	{
		api.moveGoal(goalId, toIndex);
	}

	/**
	 * Topo-aware chain move via recursive descent. Wrapped in a compound
	 * so one Ctrl+Z reverses the whole gesture.
	 */
	void moveChainInTopo(String goalId, String sectionId, boolean up)
	{
		api.beginCompound(up ? "Move up" : "Move down");
		try
		{
			moveRecursive(goalId, sectionId, up, 0);
		}
		finally
		{
			api.endCompound();
		}
	}

	/**
	 * One step of the recursive descent. Returns true if the goal
	 * successfully moved by one position in the requested direction.
	 */
	private boolean moveRecursive(String goalId, String sectionId, boolean up, int depth)
	{
		if (depth > 256) return false;

		List<GoalView> topo = api.queryGoalsTopologicallySorted(sectionId);
		int pos = -1;
		for (int i = 0; i < topo.size(); i++)
		{
			if (goalId.equals(topo.get(i).id)) { pos = i; break; }
		}
		if (pos < 0) return false;
		int targetPos = up ? pos - 1 : pos + 1;
		if (targetPos < 0 || targetPos >= topo.size()) return false;

		GoalView adjacent = topo.get(targetPos);

		boolean blocked;
		if (up)
		{
			blocked = goalDirectlyRequires(goalId, adjacent.id);
		}
		else
		{
			blocked = goalDirectlyRequires(adjacent.id, goalId);
		}

		if (blocked)
		{
			boolean blockerMoved = moveRecursive(adjacent.id, sectionId, up, depth + 1);
			if (!blockerMoved) return false;
			return moveRecursive(goalId, sectionId, up, depth + 1);
		}

		int adjacentFlatIdx = globalIndexOf(adjacent.id);
		if (adjacentFlatIdx < 0) return false;
		return api.moveGoal(goalId, adjacentFlatIdx);
	}

	/**
	 * Does fromId's goal have a direct requirement edge pointing at toId?
	 */
	boolean goalDirectlyRequires(String fromId, String toId)
	{
		if (fromId == null || toId == null) return false;
		Goal g = goalStore.findGoalById(fromId);
		if (g == null) return false;
		return g.getRequiredGoalIds() != null
			&& g.getRequiredGoalIds().contains(toId);
	}

	/** Global (flat-priority) index of the given goal, or -1 if missing. */
	int globalIndexOf(String goalId)
	{
		List<Goal> goals = goalStore.getGoals();
		for (int i = 0; i < goals.size(); i++)
		{
			if (goals.get(i).getId().equals(goalId)) return i;
		}
		return -1;
	}

	/**
	 * Resolve a goal id to a display-friendly name. Returns the id itself
	 * if no match (defensive fallback).
	 */
	String goalNameById(String goalId)
	{
		if (goalId == null) return "(unknown)";
		Goal g = goalStore.findGoalById(goalId);
		if (g == null) return goalId;
		return g.getName() != null ? g.getName() : goalId;
	}
}
