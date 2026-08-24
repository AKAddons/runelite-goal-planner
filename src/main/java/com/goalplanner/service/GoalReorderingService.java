package com.goalplanner.service;

import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalStatus;
import com.goalplanner.model.GoalType;
import com.goalplanner.persistence.GoalStore;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Handles goal reordering logic including recursive skill chain movement.
 *
 * Rules:
 * - Goals move independently through unrelated goals
 * - If a move would violate same-skill ordering (lower target must be above higher),
 *   partners recursively move first from end of chain inward
 */
@Singleton
public class GoalReorderingService
{
	private final GoalStore goalStore;

	@Inject
	public GoalReorderingService(GoalStore goalStore)
	{
		this.goalStore = goalStore;
	}

	/**
	 * Move a goal one position up or down, respecting skill chain ordering.
	 */
	public void moveGoal(int fromIndex, int toIndex)
	{
		List<Goal> goals = goalStore.getGoals();
		if (toIndex < 0 || toIndex >= goals.size())
		{
			return;
		}

		boolean movingUp = toIndex < fromIndex;
		Goal moving = goals.get(fromIndex);

		makeRoom(moving, fromIndex, movingUp);

		goals = goalStore.getGoals();
		int currentIndex = goals.indexOf(moving);
		int newTarget = movingUp ? currentIndex - 1 : currentIndex + 1;

		if (newTarget >= 0 && newTarget < goals.size())
		{
			goalStore.reorder(currentIndex, newTarget);
		}
		// goalStore.reorder() already persists; no extra save needed.
	}

	/**
	 * Move a goal directly to a specific position (Move to Top/Bottom).
	 * Enforces skill ordering after placement.
	 */
	public void moveGoalTo(int fromIndex, int toIndex)
	{
		List<Goal> goals = goalStore.getGoals();
		if (fromIndex < 0 || fromIndex >= goals.size()
			|| toIndex < 0 || toIndex >= goals.size()
			|| fromIndex == toIndex)
		{
			return;
		}

		goalStore.reorder(fromIndex, toIndex);
		enforceSkillOrdering();
		// goalStore.reorder() (called above and inside enforceSkillOrdering) persists.
	}

	/**
	 * Enforce same-skill ordering across every section. Each section is
	 * processed independently so we never swap goals across section boundaries.
	 */
	public void enforceSkillOrdering()
	{
		Set<String> sectionIds = new HashSet<>();
		for (Goal g : goalStore.getGoals())
		{
			if (g.getSectionId() != null) sectionIds.add(g.getSectionId());
		}
		for (String sectionId : sectionIds)
		{
			enforceSkillOrderingInSection(sectionId);
		}
	}

	/**
	 * Enforce same-skill ordering within a single section: for any pair of
	 * same-skill goals, the one with the lower target must come first.
	 * Goals in other sections are not touched.
	 *
	 * <p>Collects same-skill groups, sorts each by target, then reorders
	 * in a single pass. O(n log n) instead of the previous O(n^3) bubble sort.
	 */
	public void enforceSkillOrderingInSection(String sectionId)
	{
		if (sectionId == null) return;
		List<Goal> goals = goalStore.getGoals();

		// Group same-skill active goals in this section by skillName.
		// Each entry: skillName → list of (flat index, goal) pairs.
		Map<String, List<int[]>> groups = new LinkedHashMap<>();
		for (int i = 0; i < goals.size(); i++)
		{
			Goal g = goals.get(i);
			if (!isActiveSkillGoal(g) || !sectionId.equals(g.getSectionId())) continue;
			String key = g.getSkillName();
			if (key == null) continue;
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{i, g.getTargetValue()});
		}

		boolean anyReordered = false;
		for (List<int[]> group : groups.values())
		{
			if (group.size() < 2) continue;
			// Check if already sorted by target value.
			boolean sorted = true;
			for (int i = 1; i < group.size(); i++)
			{
				if (group.get(i - 1)[1] > group.get(i)[1]) { sorted = false; break; }
			}
			if (sorted) continue;

			// Sort by target value, then place each goal at the position
			// occupied by the group member that should be there.
			List<int[]> sortedByTarget = new ArrayList<>(group);
			sortedByTarget.sort(Comparator.comparingInt(a -> a[1]));

			// Collect the flat indices the group currently occupies (in order).
			int[] slots = new int[group.size()];
			for (int i = 0; i < group.size(); i++) slots[i] = group.get(i)[0];

			// Place each sorted goal into its slot.
			for (int i = 0; i < sortedByTarget.size(); i++)
			{
				int targetIdx = slots[i];
				int currentGoalTarget = sortedByTarget.get(i)[1];
				Goal atSlot = goalStore.getGoals().get(targetIdx);
				if (atSlot.getTargetValue() != currentGoalTarget)
				{
					// Find where this goal actually is and swap it in.
					for (int j = targetIdx + 1; j < goalStore.getGoals().size(); j++)
					{
						Goal candidate = goalStore.getGoals().get(j);
						if (isActiveSkillGoal(candidate)
							&& sectionId.equals(candidate.getSectionId())
							&& candidate.getSkillName() != null
							&& candidate.getSkillName().equals(atSlot.getSkillName())
							&& candidate.getTargetValue() == currentGoalTarget)
						{
							goalStore.reorder(j, targetIdx);
							anyReordered = true;
							break;
						}
					}
				}
			}
		}
	}

	/**
	 * Find the correct insertion index for a new skill goal within the given
	 * section: just above the first same-skill same-section goal with a higher
	 * target. Returns -1 if no specific position needed (or sectionId is null).
	 * Section-scoped so the returned index never crosses section boundaries.
	 */
	public int findInsertionIndex(String skillName, int targetXp, String sectionId)
	{
		if (sectionId == null) return -1;
		List<Goal> goals = goalStore.getGoals();
		for (int i = 0; i < goals.size(); i++)
		{
			Goal g = goals.get(i);
			if (g.getType() == GoalType.SKILL
				&& skillName.equals(g.getSkillName())
				&& sectionId.equals(g.getSectionId())
				&& g.getStatus() != GoalStatus.COMPLETE
				&& g.getTargetValue() > targetXp)
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * Recursively make room for a goal to move in a direction.
	 */
	private void makeRoom(Goal moving, int movingIndex, boolean movingUp)
	{
		if (!isActiveSkillGoal(moving))
		{
			return;
		}

		List<Goal> goals = goalStore.getGoals();
		int targetIndex = movingUp ? movingIndex - 1 : movingIndex + 1;

		if (targetIndex < 0 || targetIndex >= goals.size())
		{
			return;
		}

		Goal neighbor = goals.get(targetIndex);

		if (!isSameSkillChain(moving, neighbor))
		{
			return;
		}

		boolean wouldViolate;
		if (movingUp)
		{
			wouldViolate = moving.getTargetValue() > neighbor.getTargetValue();
		}
		else
		{
			wouldViolate = moving.getTargetValue() < neighbor.getTargetValue();
		}

		if (!wouldViolate)
		{
			return;
		}

		makeRoom(neighbor, targetIndex, movingUp);

		goals = goalStore.getGoals();
		int partnerIndex = goals.indexOf(neighbor);
		int partnerTarget = movingUp ? partnerIndex - 1 : partnerIndex + 1;

		if (partnerTarget >= 0 && partnerTarget < goals.size())
		{
			goalStore.reorder(partnerIndex, partnerTarget);
		}
	}

	private static boolean isActiveSkillGoal(Goal goal)
	{
		return goal.getType() == GoalType.SKILL
			&& goal.getSkillName() != null
			&& goal.getStatus() != GoalStatus.COMPLETE;
	}

	private static boolean isSameSkillChain(Goal a, Goal b)
	{
		return isActiveSkillGoal(a) && isActiveSkillGoal(b)
			&& a.getSkillName().equals(b.getSkillName());
	}

	/**
	 * Re-prioritize incomplete goals: quest goals with zero active
	 * (non-completed) requirement edges get moved to the top of the
	 * Incomplete section. Everything else keeps its relative order.
	 *
	 * <p>Called after any operation that adds requirement edges
	 * (addQuestGoalWithPrereqs, addDiaryGoalWithPrereqs, bulk adds)
	 * so the user sees "do first" goals at the top.
	 */
	public void promoteLeafGoalsToTop()
	{
		List<Goal> allGoals = goalStore.getGoals();
		String incompleteSectionId = goalStore.getIncompleteSection().getId();

		List<Goal> leaves = new ArrayList<>();
		List<Goal> rest = new ArrayList<>();

		for (Goal g : allGoals)
		{
			if (!incompleteSectionId.equals(g.getSectionId()))
			{
				rest.add(g);
				continue;
			}
			boolean isLeaf = false;
			if (g.getType() == GoalType.QUEST && g.getQuestName() != null)
			{
				// A quest is a leaf if it has no active (non-completed)
				// requirement edges.
				boolean hasActiveReqs = false;
				if (g.getRequiredGoalIds() != null)
				{
					for (String reqId : g.getRequiredGoalIds())
					{
						Goal req = goalStore.findGoalById(reqId);
						if (req != null && !req.isComplete())
						{
							hasActiveReqs = true;
							break;
						}
					}
				}
				if (!hasActiveReqs) isLeaf = true;
			}
			if (isLeaf) leaves.add(g);
			else rest.add(g);
		}

		if (leaves.isEmpty()) return;

		int p = 0;
		for (Goal g : leaves) { g.setPriority(p++); }
		for (Goal g : rest) { g.setPriority(p++); }
		goalStore.normalizeOrder();
	}
}
