package com.goalplanner.api;

import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Encapsulates goal-mutation methods extracted from {@link GoalPlannerApiImpl}.
 * Package-private - only {@link GoalPlannerApiImpl} instantiates and delegates to this.
 */
@Slf4j
class GoalMutationService
{
	/** Max level supported (1-99 normal, 100-126 virtual). */
	private static final int MAX_LEVEL = 126;

	/** Max experience per skill in OSRS. */
	private static final int MAX_XP = 200_000_000;

	private final GoalPlannerApiImpl api;

	GoalMutationService(GoalPlannerApiImpl api)
	{
		this.api = api;
	}

	boolean removeGoal(String goalId)
	{
		log.debug("API.public removeGoal(goalId={})", goalId);
		if (goalId == null) return false;
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		final com.goalplanner.persistence.GoalStore.RemoveGoalBypassSnapshot[] snapHolder =
			new com.goalplanner.persistence.GoalStore.RemoveGoalBypassSnapshot[1];
		final String name = g.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				com.goalplanner.persistence.GoalStore.RemoveGoalBypassSnapshot snap =
					api.goalStore.removeGoalWithBypass(goalId);
				if (snap == null) return false;
				snapHolder[0] = snap;
				api.selectedGoalIds.remove(goalId);
				return true;
			}
			@Override public boolean revert()
			{
				com.goalplanner.persistence.GoalStore.RemoveGoalBypassSnapshot snap = snapHolder[0];
				if (snap == null) return false;
				if (api.findGoal(goalId) != null) return false;
				// 1. Remove the bypass edges we added
				for (String[] edge : snap.addedBypassEdges)
				{
					api.goalStore.removeRequirement(edge[0], edge[1]);
				}
				// 2. Re-insert the deleted goal at its original index. The
				//    snapshotted Goal carries its original requiredGoalIds so
				//    outgoing edges come back automatically.
				api.goalStore.insertGoalAt(snap.goal, snap.originalIndex);
				// 3. Restore incoming edges - re-add the deleted goal's id to
				//    each predecessor's requiredGoalIds.
				for (String predId : snap.predecessors)
				{
					api.goalStore.addRequirement(predId, goalId);
				}
				return true;
			}
			@Override public String getDescription() { return "Remove: " + name; }
		});
	}

	boolean editCustomGoal(String goalId, String newName, String newDescription)
	{
		log.debug("API.public editCustomGoal(goalId={}, newName={}, newDescription={})",
			goalId, newName, newDescription);
		if (goalId == null) return false;
		Goal g = api.findGoal(goalId);
		if (g == null || g.getType() != GoalType.CUSTOM) return false;
		final String prevName = g.getName();
		final String prevDesc = g.getDescription();
		final String resolvedName = newName != null && !newName.trim().isEmpty()
			? newName.trim() : prevName;
		final String resolvedDesc = newDescription != null ? newDescription.trim() : prevDesc;
		if (newName != null && newName.trim().isEmpty()) return false;
		if (resolvedName.equals(prevName) && resolvedDesc.equals(prevDesc)) return false;
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				Goal cg = api.findGoal(goalId);
				if (cg == null) return false;
				cg.setName(resolvedName);
				cg.setDescription(resolvedDesc);
				api.goalStore.updateGoal(cg);
				return true;
			}
			@Override public boolean revert()
			{
				Goal cg = api.findGoal(goalId);
				if (cg == null) return false;
				cg.setName(prevName);
				cg.setDescription(prevDesc);
				api.goalStore.updateGoal(cg);
				return true;
			}
			@Override public String getDescription() { return "Edit: " + prevName; }
		});
	}

	boolean markGoalComplete(String goalId)
	{
		log.debug("API.public markGoalComplete(goalId={})", goalId);
		if (goalId == null) return false;
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		if (g.getType() != GoalType.CUSTOM && g.getType() != GoalType.ITEM_GRIND) return false;
		if (g.getStatus() == com.goalplanner.model.GoalStatus.COMPLETE) return false;
		final long prevCompletedAt = g.getCompletedAt();
		final String name = g.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				return markCompleteInternal(goalId);
			}
			@Override public boolean revert()
			{
				return markIncompleteInternal(goalId, prevCompletedAt);
			}
			@Override public String getDescription() { return "Mark complete: " + name; }
		});
	}

	boolean markGoalIncomplete(String goalId)
	{
		log.debug("API.public markGoalIncomplete(goalId={})", goalId);
		if (goalId == null) return false;
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		// Deliberately allowed for EVERY type, not just manually-toggled ones.
		// This is the recovery path when a goal was completed against the wrong
		// account: clearing completedAt lets the trackers re-derive the truth on
		// the next drain, so a genuinely-finished goal re-completes within a tick
		// and a falsely-completed one simply stays open. Refusing here would
		// leave users with no way out but deleting and rebuilding their plan.
		//
		// The cost: re-completion stamps a fresh completedAt, so a legitimately
		// completed goal loses its original date. Acceptable for an action the
		// user takes explicitly on a goal they believe is wrong.
		if (g.getStatus() != com.goalplanner.model.GoalStatus.COMPLETE) return false;
		final long prevCompletedAt = g.getCompletedAt();
		final String name = g.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				return markIncompleteInternal(goalId, 0L);
			}
			@Override public boolean revert()
			{
				return markCompleteInternalAt(goalId, prevCompletedAt);
			}
			@Override public String getDescription() { return "Mark incomplete: " + name; }
		});
	}

	/**
	 * Clear completion on every goal in the selection, as one undoable step.
	 *
	 * <p>The bulk form of the recovery above: after goals were completed against
	 * the wrong account, select them and reset. Trackers re-derive from the
	 * correct account on the next drain, so the plan is rebuilt from live state
	 * rather than by hand.
	 *
	 * @return how many goals were actually reopened
	 */
	int bulkMarkIncomplete(java.util.Set<String> goalIds)
	{
		log.debug("API.internal bulkMarkIncomplete(goalIds={})", goalIds == null ? 0 : goalIds.size());
		if (goalIds == null || goalIds.isEmpty()) return 0;

		java.util.List<String> targets = new java.util.ArrayList<>();
		for (String id : goalIds)
		{
			Goal g = api.findGoal(id);
			if (g != null && g.getStatus() == com.goalplanner.model.GoalStatus.COMPLETE)
			{
				targets.add(id);
			}
		}
		if (targets.isEmpty()) return 0;

		api.beginCompound("Reset completion on " + targets.size() + " goal(s)");
		try
		{
			for (String id : targets)
			{
				markGoalIncomplete(id);
			}
		}
		finally
		{
			api.endCompound();
		}
		return targets.size();
	}

	boolean markCompleteInternal(String goalId)
	{
		return markCompleteInternalAt(goalId, System.currentTimeMillis());
	}

	boolean markCompleteInternalAt(String goalId, long completedAt)
	{
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		g.setCompletedAt(completedAt);
		g.setStatus(com.goalplanner.model.GoalStatus.COMPLETE);
		api.goalStore.updateGoal(g);
		api.goalStore.reconcileDerivedSections();
		return true;
	}

	boolean markIncompleteInternal(String goalId, long restoredCompletedAt)
	{
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		g.setCompletedAt(restoredCompletedAt);
		g.setStatus(com.goalplanner.model.GoalStatus.ACTIVE);
		api.goalStore.updateGoal(g);
		api.goalStore.reconcileDerivedSections();
		return true;
	}

	boolean setGoalRepeat(String goalId, com.goalplanner.model.RepeatPeriod period)
	{
		log.debug("API.public setGoalRepeat(goalId={}, period={})", goalId, period);
		if (goalId == null || period == null) return false;
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		// Manual repeat is CUSTOM-only: an auto-tracked type reads an absolute
		// counter, so flipping one to repeat in place would show the lifetime
		// total every morning instead of the period's progress.
		//
		// A goal with a repeatChunk is exempt - it is a derived repeatable goal
		// whose target already re-bases each period, so changing its term is
		// well-defined for any type.
		if (g.getType() != GoalType.CUSTOM && g.getRepeatChunk() <= 0) return false;
		if (g.getRepeatEvery() == period) return false; // no-op

		final com.goalplanner.model.RepeatPeriod prevPeriod = g.getRepeatEvery();
		final long prevKey = g.getLastPeriodKey();
		final String name = g.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply() { return applyRepeat(goalId, period, 0L); }
			@Override public boolean revert() { return applyRepeat(goalId, prevPeriod, prevKey); }
			@Override public String getDescription()
			{
				return (period.isRepeating() ? "Repeat " + period.getLabel() + ": " : "Stop repeating: ")
					+ name;
			}
		});
	}

	/**
	 * Set the repeat period and stamp state, then let reconcile move the goal
	 * into or out of the Repeatable section. A fresh enable stamps
	 * {@code lastPeriodKey = 0} so the next reset check adopts the current
	 * period without reopening a goal that is already done for today.
	 */
	/**
	 * Change how much a derived repeatable goal asks for each period.
	 *
	 * <p>Keeps the CURRENT period's start where it is and only moves its end:
	 * the period began at {@code targetValue - oldChunk}, so the new target is
	 * that start plus the new chunk. Re-basing off {@code currentValue} instead
	 * would forgive whatever the player already did this period, and shrinking
	 * the chunk below what they have already earned should complete the goal,
	 * not silently reset it.
	 */
	boolean setGoalRepeatChunk(String goalId, int newChunk)
	{
		log.debug("API.public setGoalRepeatChunk(goalId={}, newChunk={})", goalId, newChunk);
		if (goalId == null || newChunk <= 0) return false;
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		if (g.getRepeatChunk() <= 0) return false;      // not a derived goal
		if (g.getRepeatChunk() == newChunk) return false; // no-op

		final int prevChunk = g.getRepeatChunk();
		final int prevTarget = g.getTargetValue();
		final long prevCompletedAt = g.getCompletedAt();
		final com.goalplanner.model.GoalStatus prevStatus = g.getStatus();
		final int periodStart = prevTarget - prevChunk;
		final String name = g.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				Goal goal = api.findGoal(goalId);
				if (goal == null) return false;
				goal.setRepeatChunk(newChunk);
				goal.setTargetValue(periodStart + newChunk);
				renameChunkGoal(goal, newChunk);
				reevaluateCompletion(goal);
				api.goalStore.updateGoal(goal);
				api.goalStore.reconcileDerivedSections();
				return true;
			}
			@Override public boolean revert()
			{
				Goal goal = api.findGoal(goalId);
				if (goal == null) return false;
				goal.setRepeatChunk(prevChunk);
				goal.setTargetValue(prevTarget);
				renameChunkGoal(goal, prevChunk);
				goal.setCompletedAt(prevCompletedAt);
				goal.setStatus(prevStatus);
				api.goalStore.updateGoal(goal);
				api.goalStore.reconcileDerivedSections();
				return true;
			}
			@Override public String getDescription() { return "Change amount: " + name; }
		});
	}

	/** Keep the card title honest when the amount changes ("Prayer +50K XP"). */
	private void renameChunkGoal(Goal g, int chunk)
	{
		if (g.getType() == GoalType.SKILL && g.getSkillName() != null)
		{
			try
			{
				net.runelite.api.Skill skill = net.runelite.api.Skill.valueOf(g.getSkillName());
				g.setName(skill.getName() + " +"
					+ com.goalplanner.util.FormatUtil.formatNumber(chunk) + " XP");
			}
			catch (IllegalArgumentException ignored) {}
		}
		else if (g.getType() == GoalType.BOSS && g.getBossName() != null)
		{
			g.setName(g.getBossName() + " x" + chunk);
			g.setDescription(g.getRepeatEvery().getLabel() + " - " + chunk + " kills");
		}
	}

	/** Shrinking a chunk below what is already earned completes it; growing reopens. */
	private void reevaluateCompletion(Goal g)
	{
		boolean meets = g.meetsTarget();
		if (g.isComplete() && !meets)
		{
			g.setCompletedAt(0);
			g.setStatus(com.goalplanner.model.GoalStatus.ACTIVE);
		}
		else if (!g.isComplete() && meets)
		{
			g.setCompletedAt(System.currentTimeMillis());
			g.setStatus(com.goalplanner.model.GoalStatus.COMPLETE);
		}
	}

	private boolean applyRepeat(String goalId, com.goalplanner.model.RepeatPeriod period, long periodKey)
	{
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		g.setRepeatEvery(period);
		g.setLastPeriodKey(periodKey);
		api.goalStore.updateGoal(g);
		api.goalStore.reconcileDerivedSections();
		return true;
	}

	boolean setGoalOptional(String goalId, boolean optional)
	{
		log.debug("API.internal setGoalOptional(goalId={}, optional={})", goalId, optional);
		if (goalId == null) return false;
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		if (g.isOptional() == optional) return false; // no-op
		final boolean oldValue = g.isOptional();
		api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				Goal goal = api.findGoal(goalId);
				if (goal == null) return false;
				goal.setOptional(optional);
				api.goalStore.save();
				return true;
			}
			@Override public boolean revert()
			{
				Goal goal = api.findGoal(goalId);
				if (goal == null) return false;
				goal.setOptional(oldValue);
				api.goalStore.save();
				return true;
			}
			@Override public String getDescription()
			{
				return optional ? "Mark optional" : "Mark required";
			}
		});
		return true;
	}

	boolean changeTarget(String goalId, int newTarget)
	{
		log.debug("API.public changeTarget(goalId={}, newTarget={})", goalId, newTarget);
		if (goalId == null || newTarget < 1) return false;
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		if (g.getType() == GoalType.SKILL)
		{
			if (newTarget > MAX_XP) return false;
		}
		else if (g.getType() != GoalType.ITEM_GRIND && g.getType() != GoalType.BOSS)
		{
			return false; // CA/quest/diary targets are immutable
		}
		if (newTarget == g.getTargetValue())
		{
			return false; // no-op - also keeps a re-typed value from re-evaluating a manual completion
		}
		final int prevTarget = g.getTargetValue();
		final String prevName = g.getName();
		final String prevDescription = g.getDescription();
		final com.goalplanner.model.GoalStatus prevStatus = g.getStatus();
		final long prevCompletedAt = g.getCompletedAt();
		final String label = g.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply() { return changeTargetInternal(goalId, newTarget); }
			@Override public boolean revert()
			{
				Goal cg = api.findGoal(goalId);
				if (cg == null) return false;
				cg.setTargetValue(prevTarget);
				cg.setName(prevName);
				cg.setDescription(prevDescription);
				boolean statusChanged = cg.getStatus() != prevStatus;
				cg.setStatus(prevStatus);
				cg.setCompletedAt(prevCompletedAt);
				api.goalStore.updateGoal(cg);
				if (statusChanged)
				{
					api.goalStore.reconcileDerivedSections();
				}
				return true;
			}
			@Override public String getDescription() { return "Change target: " + label; }
		});
	}

	private boolean changeTargetInternal(String goalId, int newTarget)
	{
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		g.setTargetValue(newTarget);
		if (g.getType() == GoalType.SKILL && g.getSkillName() != null)
		{
			try
			{
				net.runelite.api.Skill skill = net.runelite.api.Skill.valueOf(g.getSkillName());
				int level = net.runelite.api.Experience.getLevelForXp(newTarget);
				g.setName(skill.getName() + " - Level " + level);
			}
			catch (IllegalArgumentException ignored) {}
		}
		else if (g.getType() == GoalType.ITEM_GRIND)
		{
			g.setDescription(com.goalplanner.util.FormatUtil.formatNumber(newTarget) + " total");
		}
		else if (g.getType() == GoalType.BOSS)
		{
			g.setDescription(newTarget + " kills");
		}

		// Re-evaluate completion against the new target so completed and
		// active goals stay equivalent under retargeting: raising the target
		// past the recorded progress reopens the goal (trackers resume -
		// COMPLETE is only terminal while it stands); lowering it to or below
		// the progress completes it, with the same OR-prereq cascade a
		// tracker-driven completion runs. Note the recorded progress can lag
		// the game for a reopened goal (trackers skip COMPLETE goals); the
		// post-command refresh resyncs and re-completes it if warranted.
		boolean meets = g.meetsTarget();
		boolean statusChanged = false;
		if (g.isComplete() && !meets)
		{
			g.setCompletedAt(0);
			g.setStatus(com.goalplanner.model.GoalStatus.ACTIVE);
			statusChanged = true;
			log.info("API.internal changeTarget: goal reopened {} ({})", g.getId(), g.getName());
		}
		else if (!g.isComplete() && meets)
		{
			g.setCompletedAt(System.currentTimeMillis());
			g.setStatus(com.goalplanner.model.GoalStatus.COMPLETE);
			statusChanged = true;
			log.info("API.internal changeTarget: goal complete {} ({})", g.getId(), g.getName());
			checkOrPrereqCompletion(goalId);
		}

		api.goalStore.updateGoal(g);
		if (statusChanged)
		{
			api.goalStore.reconcileDerivedSections();
		}
		return true;
	}

	boolean recordGoalProgress(String goalId, int newValue)
	{
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		// An imported repeatable goal arrives unsized (targetValue 0) because the
		// sender's target was computed against THEIR progress. This is the first
		// moment the recipient's own value is known, so size it here: the next
		// `chunk` from wherever they actually are. meetsTarget() is false while
		// targetValue is 0, so an unsized goal can never complete by accident.
		//
		// Deliberately BEFORE the no-op guard below. A player who has never
		// killed the boss reports 0, which equals the stored 0, so the guard
		// would return early and leave the goal unsized forever.
		boolean justSized = false;
		if (g.getRepeatChunk() > 0 && g.getTargetValue() <= 0)
		{
			g.setTargetValue(newValue + g.getRepeatChunk());
			justSized = true;
			log.info("API.internal recordGoalProgress: sized imported repeatable {} to {}",
				g.getId(), g.getTargetValue());
		}

		// Also proceed when the completion state is STALE - complete but no
		// longer meeting target, or (the reported bug) reopened yet still maxed.
		// Otherwise reopening a 99/maxed auto-tracked goal never re-completes:
		// its XP/KC never changes, so the tracker reads the same value, this
		// guard no-ops, and completion is never re-evaluated. With the stale
		// check, a genuinely-met reopen self-heals to complete next tick; a
		// falsely-completed one (wrong-account recovery) stays open.
		boolean stale = g.isComplete() != g.meetsTarget();
		if (g.getCurrentValue() == newValue && !justSized && !stale) return false;

		g.setCurrentValue(newValue);

		boolean meetsTarget = g.meetsTarget();
		boolean wasComplete = g.isComplete();

		if (meetsTarget && !wasComplete)
		{
			g.setCompletedAt(System.currentTimeMillis());
			g.setStatus(com.goalplanner.model.GoalStatus.COMPLETE);
			log.info("API.internal recordGoalProgress: goal complete {} ({})",
				g.getId(), g.getName());
			// Check if this goal is an OR-prereq of any parent unlock
			checkOrPrereqCompletion(goalId);
		}
		else if (!meetsTarget && wasComplete)
		{
			g.setCompletedAt(0);
			g.setStatus(com.goalplanner.model.GoalStatus.ACTIVE);
			log.info("API.internal recordGoalProgress: goal un-completed {} ({})",
				g.getId(), g.getName());
		}
		// Mark this goal as dirty for the next saveDirtyGoals() call.
		api.goalStore.markGoalDirty(goalId);
		return true;
	}

	/**
	 * When an OR-prereq completes, check if any parent goal should
	 * auto-complete. A parent with OR-prereqs completes when ANY
	 * OR-prereq is done AND ALL AND-prereqs are done.
	 */
	private void checkOrPrereqCompletion(String completedGoalId)
	{
		List<String> parentIds = api.goalStore.getDependents(completedGoalId);
		for (String parentId : parentIds)
		{
			Goal parent = api.findGoal(parentId);
			if (parent == null || parent.isComplete()) continue;
			// Any OR-unlock parent gets re-evaluated, whichever edge just
			// completed. The old skip ("this parent uses AND-edge, not OR")
			// made the cascade order-dependent: if the LAST prerequisite to
			// finish was an AND-edge (quest tracker runs after skill tracker,
			// so a quest AND-prereq often is), the parent was never rechecked
			// and sat incomplete with every requirement met.
			if (parent.getOrRequiredGoalIds() == null
				|| parent.getOrRequiredGoalIds().isEmpty())
			{
				continue; // not an OR-unlock - completing prereqs never auto-completes it
			}

			// Check: any OR-prereq complete?
			boolean anyOrMet = false;
			for (String orId : parent.getOrRequiredGoalIds())
			{
				Goal orGoal = api.findGoal(orId);
				if (orGoal != null && orGoal.isComplete())
				{
					anyOrMet = true;
					break;
				}
			}
			if (!anyOrMet) continue;

			// Check: all AND-prereqs complete?
			boolean allAndMet = true;
			if (parent.getRequiredGoalIds() != null)
			{
				for (String andId : parent.getRequiredGoalIds())
				{
					Goal andGoal = api.findGoal(andId);
					if (andGoal != null && !andGoal.isComplete())
					{
						allAndMet = false;
						break;
					}
				}
			}
			if (!allAndMet) continue;

			// Auto-complete the parent
			parent.setCurrentValue(parent.getTargetValue() > 0 ? parent.getTargetValue() : 1);
			parent.setCompletedAt(System.currentTimeMillis());
			parent.setStatus(com.goalplanner.model.GoalStatus.COMPLETE);
			api.goalStore.markGoalDirty(parentId);
			log.info("OR-prereq auto-completed parent: {} ({})", parentId, parent.getName());
		}
	}

	boolean isGoalOverridden(String goalId)
	{
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		if (g.getCustomColorRgb() >= 0) return true;
		List<String> defaults = g.getDefaultTagIds() != null ? g.getDefaultTagIds() : java.util.Collections.emptyList();
		List<String> current = g.getTagIds() != null ? g.getTagIds() : java.util.Collections.emptyList();
		return !new java.util.HashSet<>(current).equals(new java.util.HashSet<>(defaults));
	}

	int bulkRestoreDefaults(java.util.Set<String> goalIds)
	{
		log.debug("API.internal bulkRestoreDefaults({} goals)", goalIds == null ? 0 : goalIds.size());
		if (goalIds == null || goalIds.isEmpty()) return 0;
		final java.util.List<String[]> snapshots = new java.util.ArrayList<>();
		for (String goalId : goalIds)
		{
			Goal g = api.findGoal(goalId);
			if (g == null) continue;
			if (!isGoalOverridden(goalId)) continue;
			String prevTagsCsv = g.getTagIds() != null ? String.join(",", g.getTagIds()) : "";
			snapshots.add(new String[]{ goalId,
				String.valueOf(g.getCustomColorRgb()),
				prevTagsCsv });
		}
		if (snapshots.isEmpty()) return 0;
		boolean ok = api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				for (String[] snap : snapshots)
				{
					Goal g = api.findGoal(snap[0]);
					if (g == null) continue;
					List<String> defaults = g.getDefaultTagIds() != null ? g.getDefaultTagIds() : java.util.Collections.emptyList();
					g.setTagIds(new ArrayList<>(defaults));
					g.setCustomColorRgb(-1);
					api.goalStore.updateGoal(g);
				}
				return true;
			}
			@Override public boolean revert()
			{
				for (String[] snap : snapshots)
				{
					Goal g = api.findGoal(snap[0]);
					if (g == null) continue;
					g.setCustomColorRgb(Integer.parseInt(snap[1]));
					java.util.List<String> tags = snap[2].isEmpty()
						? new ArrayList<>() : new ArrayList<>(java.util.Arrays.asList(snap[2].split(",")));
					g.setTagIds(tags);
					api.goalStore.updateGoal(g);
				}
				return true;
			}
			@Override public String getDescription()
			{
				return "Restore defaults (" + snapshots.size() + " goals)";
			}
		});
		return ok ? snapshots.size() : 0;
	}

	int bulkRemoveGoals(java.util.Set<String> goalIds)
	{
		log.debug("API.internal bulkRemoveGoals({} goals)", goalIds == null ? 0 : goalIds.size());
		if (goalIds == null || goalIds.isEmpty()) return 0;
		final java.util.List<Goal> goalSnapshots = new ArrayList<>();
		final java.util.List<Integer> prioritySnapshots = new ArrayList<>();
		for (Goal g : api.goalStore.getGoals())
		{
			if (goalIds.contains(g.getId()))
			{
				goalSnapshots.add(g);
				prioritySnapshots.add(g.getPriority());
			}
		}
		if (goalSnapshots.isEmpty()) return 0;
		final java.util.Set<String> selectionSnapshot = new java.util.LinkedHashSet<>(api.selectedGoalIds);
		boolean ok = api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				for (Goal g : goalSnapshots)
				{
					api.goalStore.removeGoal(g.getId());
					api.selectedGoalIds.remove(g.getId());
				}
				return true;
			}
			@Override public boolean revert()
			{
				java.util.List<Integer> order = new ArrayList<>();
				for (int i = 0; i < goalSnapshots.size(); i++) order.add(i);
				order.sort((a, b) -> Integer.compare(prioritySnapshots.get(a), prioritySnapshots.get(b)));
				for (int idx : order)
				{
					api.goalStore.insertGoalAt(goalSnapshots.get(idx), prioritySnapshots.get(idx));
				}
				api.selectedGoalIds.addAll(selectionSnapshot);
				return true;
			}
			@Override public String getDescription()
			{
				return "Remove " + goalSnapshots.size() + " goals";
			}
		});
		return ok ? goalSnapshots.size() : 0;
	}

	int bulkMoveGoalsToSection(java.util.Set<String> goalIds, String targetSectionId)
	{
		log.debug("API.internal bulkMoveGoalsToSection({} goals → {})",
			goalIds == null ? 0 : goalIds.size(), targetSectionId);
		if (goalIds == null || goalIds.isEmpty() || targetSectionId == null) return 0;
		final java.util.List<String> affectedIds = new ArrayList<>();
		final java.util.List<String> prevSections = new ArrayList<>();
		final java.util.List<Integer> prevPriorities = new ArrayList<>();
		for (Goal g : api.goalStore.getGoals())
		{
			if (!goalIds.contains(g.getId())) continue;
			if (targetSectionId.equals(g.getSectionId())) continue;
			affectedIds.add(g.getId());
			prevSections.add(g.getSectionId());
			prevPriorities.add(g.getPriority());
		}
		if (affectedIds.isEmpty()) return 0;
		boolean ok = api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				for (String gid : affectedIds) api.sectionService.moveGoalToSectionInternal(gid, targetSectionId);
				return true;
			}
			@Override public boolean revert()
			{
				for (int i = 0; i < affectedIds.size(); i++)
				{
					String gid = affectedIds.get(i);
					api.goalStore.moveGoalToSection(gid, prevSections.get(i));
					Goal g = api.findGoal(gid);
					if (g != null) g.setPriority(prevPriorities.get(i));
				}
				api.goalStore.normalizeOrder();
				return true;
			}
			@Override public String getDescription()
			{
				return "Move " + affectedIds.size() + " goals";
			}
		});
		return ok ? affectedIds.size() : 0;
	}

	/**
	 * Move goals to the DEFAULT namespace - each lands in the built-in that
	 * matches its completion (incomplete → Incomplete, complete → Completed),
	 * as one undoable gesture. This is the "Move to Default" action: the default
	 * is one logical bucket that auto-splits by completion, so the caller doesn't
	 * pick a half. Goals already in the correct default half are skipped.
	 *
	 * @return the number of goals actually moved
	 */
	int moveGoalsToDefault(java.util.Collection<String> goalIds)
	{
		log.debug("API.internal moveGoalsToDefault({} goals)", goalIds == null ? 0 : goalIds.size());
		if (goalIds == null || goalIds.isEmpty()) return 0;
		final String incId = api.goalStore.getIncompleteSection().getId();
		final String compId = api.goalStore.getCompletedSection().getId();
		final java.util.List<String> affectedIds = new ArrayList<>();
		final java.util.List<String> targets = new ArrayList<>();
		final java.util.List<String> prevSections = new ArrayList<>();
		final java.util.List<Integer> prevPriorities = new ArrayList<>();
		for (Goal g : api.goalStore.getGoals())
		{
			if (!goalIds.contains(g.getId())) continue;
			String target = g.isComplete() ? compId : incId;
			if (target.equals(g.getSectionId())) continue; // already in the right default half
			affectedIds.add(g.getId());
			targets.add(target);
			prevSections.add(g.getSectionId());
			prevPriorities.add(g.getPriority());
		}
		if (affectedIds.isEmpty()) return 0;
		boolean ok = api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				for (int i = 0; i < affectedIds.size(); i++)
				{
					api.sectionService.moveGoalToSectionInternal(affectedIds.get(i), targets.get(i));
				}
				return true;
			}
			@Override public boolean revert()
			{
				for (int i = 0; i < affectedIds.size(); i++)
				{
					String gid = affectedIds.get(i);
					api.goalStore.moveGoalToSection(gid, prevSections.get(i));
					Goal g = api.findGoal(gid);
					if (g != null) g.setPriority(prevPriorities.get(i));
				}
				api.goalStore.normalizeOrder();
				return true;
			}
			@Override public String getDescription() { return "Move " + affectedIds.size() + " goal(s) to default"; }
		});
		return ok ? affectedIds.size() : 0;
	}

	void removeAllGoals()
	{
		log.debug("API.internal removeAllGoals()");
		// Only remove incomplete goals - completed goals are preserved.
		final java.util.List<Goal> toRemove = new ArrayList<>();
		for (Goal g : api.goalStore.getGoals())
		{
			if (g.getStatus() != com.goalplanner.model.GoalStatus.COMPLETE)
			{
				toRemove.add(g);
			}
		}
		if (toRemove.isEmpty()) return;
		final java.util.Set<String> removedIds = new java.util.LinkedHashSet<>();
		for (Goal g : toRemove) removedIds.add(g.getId());
		final java.util.Set<String> selectionSnapshot = new java.util.LinkedHashSet<>(api.selectedGoalIds);
		// Suspend saves so the bulk loop writes one goal_order at the end,
		// not N times (one per removal).
		api.goalStore.suspendSave();
		try
		{
			api.executeCommand(new com.goalplanner.command.Command()
			{
				@Override public boolean apply()
				{
					for (Goal g : toRemove)
					{
						api.goalStore.removeGoal(g.getId());
					}
					api.selectedGoalIds.removeAll(removedIds);
					return true;
				}
				@Override public boolean revert()
				{
					for (Goal g : toRemove)
					{
						if (api.findGoal(g.getId()) == null) api.goalStore.addGoal(g);
					}
					api.goalStore.normalizeOrder();
					api.selectedGoalIds.addAll(selectionSnapshot);
					return true;
				}
				@Override public String getDescription() { return "Remove all incomplete goals (" + toRemove.size() + ")"; }
			});
		}
		finally
		{
			api.goalStore.resumeSave();
		}
	}

	/**
	 * Remove duplicate goals - within each namespace (the default Incomplete+
	 * Completed, or a user section), goals that share an identity
	 * ({@link com.goalplanner.model.GoalIdentity}) are collapsed to one, keeping
	 * the most advanced (complete &gt; more progress &gt; first). Lets the user
	 * clean up the duplicates that can accumulate in the default bucket from
	 * auto-archiving several same-identity goals. One undoable gesture.
	 *
	 * @return the number of goals removed
	 */
	int removeDuplicateGoals()
	{
		log.debug("API.internal removeDuplicateGoals()");
		final java.util.List<Goal> all = new ArrayList<>(api.goalStore.getGoals());
		final java.util.Set<String> removeIds = new java.util.LinkedHashSet<>();
		for (int i = 0; i < all.size(); i++)
		{
			Goal a = all.get(i);
			if (removeIds.contains(a.getId())) continue;
			String nsA = api.goalStore.namespaceKey(a.getSectionId());
			java.util.List<Goal> group = new ArrayList<>();
			group.add(a);
			for (int j = i + 1; j < all.size(); j++)
			{
				Goal b = all.get(j);
				if (removeIds.contains(b.getId())) continue;
				if (java.util.Objects.equals(nsA, api.goalStore.namespaceKey(b.getSectionId()))
					&& com.goalplanner.model.GoalIdentity.sameIdentity(a, b))
				{
					group.add(b);
				}
			}
			if (group.size() <= 1) continue;
			Goal keep = group.get(0);
			for (Goal g : group) keep = preferGoal(keep, g);
			for (Goal g : group) if (g != keep) removeIds.add(g.getId());
		}
		if (removeIds.isEmpty()) return 0;

		final java.util.List<Goal> toRemove = new ArrayList<>();
		for (Goal g : all) if (removeIds.contains(g.getId())) toRemove.add(g);
		final java.util.Set<String> selectionSnapshot = new java.util.LinkedHashSet<>(api.selectedGoalIds);

		api.goalStore.suspendSave();
		try
		{
			api.executeCommand(new com.goalplanner.command.Command()
			{
				@Override public boolean apply()
				{
					for (Goal g : toRemove) api.goalStore.removeGoal(g.getId());
					api.selectedGoalIds.removeAll(removeIds);
					return true;
				}
				@Override public boolean revert()
				{
					for (Goal g : toRemove) if (api.findGoal(g.getId()) == null) api.goalStore.addGoal(g);
					api.goalStore.normalizeOrder();
					api.selectedGoalIds.addAll(selectionSnapshot);
					return true;
				}
				@Override public String getDescription() { return "Remove " + toRemove.size() + " duplicate goal(s)"; }
			});
		}
		finally
		{
			api.goalStore.resumeSave();
		}
		return toRemove.size();
	}

	/** Pick which of two same-identity goals to keep: complete &gt; more progress &gt; the first. */
	private Goal preferGoal(Goal keep, Goal candidate)
	{
		if (candidate == keep) return keep;
		if (candidate.isComplete() != keep.isComplete())
		{
			return candidate.isComplete() ? candidate : keep;
		}
		return candidate.getCurrentValue() > keep.getCurrentValue() ? candidate : keep;
	}

	boolean moveGoal(String goalId, int newGlobalIndex)
	{
		log.debug("API.internal moveGoal(goalId={}, newGlobalIndex={})", goalId, newGlobalIndex);
		if (goalId == null) return false;
		List<Goal> goals = api.goalStore.getGoals();
		int currentIndex = -1;
		for (int i = 0; i < goals.size(); i++)
		{
			if (goals.get(i).getId().equals(goalId))
			{
				currentIndex = i;
				break;
			}
		}
		if (currentIndex < 0) return false;
		if (newGlobalIndex < 0 || newGlobalIndex >= goals.size()) return false;
		if (currentIndex == newGlobalIndex) return false;
		String sourceSectionId = goals.get(currentIndex).getSectionId();
		String targetSectionId = goals.get(newGlobalIndex).getSectionId();
		if (sourceSectionId == null || !sourceSectionId.equals(targetSectionId)) return false;

		final int snapshotFromIndex = currentIndex;
		final String name = goals.get(currentIndex).getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				List<Goal> gs = api.goalStore.getGoals();
				int from = -1;
				for (int i = 0; i < gs.size(); i++)
				{
					if (gs.get(i).getId().equals(goalId)) { from = i; break; }
				}
				if (from < 0 || newGlobalIndex >= gs.size()) return false;
				int delta = Math.abs(newGlobalIndex - from);
				if (delta == 1) api.reorderingService.moveGoal(from, newGlobalIndex);
				else api.reorderingService.moveGoalTo(from, newGlobalIndex);
				return true;
			}
			@Override public boolean revert()
			{
				List<Goal> gs = api.goalStore.getGoals();
				int currentPos = -1;
				for (int i = 0; i < gs.size(); i++)
				{
					if (gs.get(i).getId().equals(goalId)) { currentPos = i; break; }
				}
				if (currentPos < 0) return false;
				if (currentPos == snapshotFromIndex) return true;
				int delta = Math.abs(snapshotFromIndex - currentPos);
				if (delta == 1) api.reorderingService.moveGoal(currentPos, snapshotFromIndex);
				else api.reorderingService.moveGoalTo(currentPos, snapshotFromIndex);
				return true;
			}
			@Override public String getDescription() { return "Reorder: " + name; }
		});
	}

	boolean positionGoalInSection(String goalId, String sectionId, int positionInSection)
	{
		log.debug("API.internal positionGoalInSection(goalId={}, sectionId={}, pos={})",
			goalId, sectionId, positionInSection);
		if (goalId == null || sectionId == null) return false;
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		final String prevSectionId = g.getSectionId();
		final int prevPriority = g.getPriority();
		final String name = g.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				return positionGoalInSectionInternal(goalId, sectionId, positionInSection);
			}
			@Override public boolean revert()
			{
				Goal cg = api.findGoal(goalId);
				if (cg == null) return false;
				if (!prevSectionId.equals(cg.getSectionId()))
				{
					api.goalStore.moveGoalToSection(goalId, prevSectionId);
				}
				cg.setPriority(prevPriority);
				api.goalStore.normalizeOrder();
				return true;
			}
			@Override public String getDescription() { return "Reposition: " + name; }
		});
	}

	private boolean positionGoalInSectionInternal(String goalId, String sectionId, int positionInSection)
	{
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		boolean changed = false;
		if (!sectionId.equals(g.getSectionId()))
		{
			if (api.goalStore.moveGoalToSection(goalId, sectionId)) changed = true;
		}
		api.goalStore.normalizeOrder();
		List<Goal> goals = api.goalStore.getGoals();
		List<Integer> sectionIndices = new ArrayList<>();
		int sourceIdx = -1;
		for (int i = 0; i < goals.size(); i++)
		{
			if (sectionId.equals(goals.get(i).getSectionId()))
			{
				sectionIndices.add(i);
				if (goalId.equals(goals.get(i).getId())) sourceIdx = i;
			}
		}
		if (sourceIdx < 0) return changed;
		int sectionSize = sectionIndices.size();
		int clampedPos = Math.max(0, Math.min(positionInSection, sectionSize - 1));
		int targetGlobal = sectionIndices.get(clampedPos);
		if (sourceIdx != targetGlobal)
		{
			api.reorderingService.moveGoalTo(sourceIdx, targetGlobal);
			changed = true;
		}
		return changed;
	}
}
