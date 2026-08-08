package com.goalplanner.api;

import com.goalplanner.model.Goal;
import com.goalplanner.model.Section;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Encapsulates section-management methods extracted from {@link GoalPlannerApiImpl}.
 * Package-private - only {@link GoalPlannerApiImpl} instantiates and delegates to this.
 */
@Slf4j
class SectionService
{
	private final GoalPlannerApiImpl api;

	SectionService(GoalPlannerApiImpl api)
	{
		this.api = api;
	}

	String createSection(String name)
	{
		log.debug("API.internal createSection(name={})", name);
		Section created = api.goalStore.createUserSection(name);
		if (created == null) return null;
		final String sectionId = created.getId();
		final String sectionName = created.getName();
		// Already created. Wrap in a Command for the undo path.
		api.executeCommand(new com.goalplanner.command.Command()
		{
			private boolean firstApply = true;
			@Override public boolean apply()
			{
				if (firstApply) { firstApply = false; return true; }
				if (api.goalStore.findSection(sectionId) != null) return false;
				api.goalStore.recreateUserSection(sectionId, sectionName);
				return true;
			}
			@Override public boolean revert()
			{
				// Undoing "create" must not destroy goals the user has since
				// put in the section - relocate them, then drop the (empty)
				// section. deleteUserSection itself deletes a section's goals.
				api.goalStore.evacuateSectionToIncomplete(sectionId);
				return api.goalStore.deleteUserSection(sectionId);
			}
			@Override public String getDescription() { return "Add section: " + sectionName; }
		});
		return sectionId;
	}

	boolean renameSection(String sectionId, String newName)
	{
		log.debug("API.internal renameSection(sectionId={}, newName={})", sectionId, newName);
		Section sec = api.goalStore.findSection(sectionId);
		if (sec == null) return false;
		final String prevName = sec.getName();
		final String resolved = newName != null ? newName.trim() : "";
		if (resolved.isEmpty() || resolved.equals(prevName)) return false;
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply() { return api.goalStore.renameUserSection(sectionId, resolved); }
			@Override public boolean revert() { return api.goalStore.renameUserSection(sectionId, prevName); }
			@Override public String getDescription() { return "Rename section: " + prevName + " -> " + resolved; }
		});
	}

	boolean deleteSection(String sectionId, boolean moveGoalsToDefault)
	{
		log.debug("API.internal deleteSection(sectionId={}, moveGoalsToDefault={})",
			sectionId, moveGoalsToDefault);
		Section sec = api.goalStore.findSection(sectionId);
		if (sec == null || sec.isBuiltIn()) return false;
		final String name = sec.getName();
		final int order = sec.getOrder();
		final int colorRgb = sec.getColorRgb();
		final Boolean nestedOverride = sec.getNestedOverride();
		final Boolean autoArchiveOverride = sec.getAutoArchiveOverride();
		// Completed goals archived OUT of this section live in Completed and
		// survive either path, but the delete clears their home memory -
		// snapshot the ids so undo can restore the link.
		final java.util.List<String> archivedOutIds = new ArrayList<>();
		for (Goal g : api.goalStore.getGoals())
		{
			if (sectionId.equals(g.getArchivedFromSectionId())) archivedOutIds.add(g.getId());
		}

		if (moveGoalsToDefault)
		{
			// Opt-in sparing path: relocate the goals to the default buckets,
			// then drop the (now empty) section.
			final java.util.List<String> displacedGoalIds = new ArrayList<>();
			for (Goal g : api.goalStore.getGoals())
			{
				if (sectionId.equals(g.getSectionId())) displacedGoalIds.add(g.getId());
			}
			return api.executeCommand(new com.goalplanner.command.Command()
			{
				@Override public boolean apply()
				{
					api.goalStore.evacuateSectionToIncomplete(sectionId);
					return api.goalStore.deleteUserSection(sectionId);
				}
				@Override public boolean revert()
				{
					recreateSection(sectionId, name, order, colorRgb, nestedOverride, autoArchiveOverride);
					// moveGoalToSection pins completed goals inline in the
					// destination - matching their pre-delete placement.
					for (String gid : displacedGoalIds)
					{
						api.goalStore.moveGoalToSection(gid, sectionId);
					}
					restoreArchivedHomes(archivedOutIds, sectionId);
					api.goalStore.normalizeOrder();
					return true;
				}
				@Override public String getDescription() { return "Delete section (keep goals): " + name; }
			});
		}

		// Deleting a section deletes its goals, so revert must RESTORE them:
		// the goal objects (ascending flat index, so positions come back), a
		// copy of each one's outgoing edge lists (deletion scrubs doomed ids
		// out of doomed peers' lists, mutating the very objects we keep), and
		// the incoming edges from surviving goals (scrubbed by removeGoal).
		final java.util.List<Goal> doomedGoals = new ArrayList<>();
		final java.util.Map<String, Integer> originalIndex = new java.util.HashMap<>();
		final java.util.Map<String, java.util.List<String>> savedRequires = new java.util.HashMap<>();
		final java.util.Map<String, java.util.List<String>> savedOrRequires = new java.util.HashMap<>();
		java.util.List<Goal> all = api.goalStore.getGoals();
		for (int i = 0; i < all.size(); i++)
		{
			Goal g = all.get(i);
			if (sectionId.equals(g.getSectionId()))
			{
				doomedGoals.add(g);
				originalIndex.put(g.getId(), i);
				savedRequires.put(g.getId(), new ArrayList<>(g.getRequiredGoalIds()));
				savedOrRequires.put(g.getId(), new ArrayList<>(g.getOrRequiredGoalIds()));
			}
		}
		final java.util.Set<String> doomedIds = originalIndex.keySet();
		final java.util.List<String[]> survivorAndEdges = new ArrayList<>();
		final java.util.List<String[]> survivorOrEdges = new ArrayList<>();
		for (Goal g : all)
		{
			if (doomedIds.contains(g.getId())) continue;
			for (String rid : g.getRequiredGoalIds())
			{
				if (doomedIds.contains(rid)) survivorAndEdges.add(new String[]{g.getId(), rid});
			}
			for (String rid : g.getOrRequiredGoalIds())
			{
				if (doomedIds.contains(rid)) survivorOrEdges.add(new String[]{g.getId(), rid});
			}
		}
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				if (!api.goalStore.deleteUserSection(sectionId)) return false;
				api.selectedGoalIds.removeAll(doomedIds);
				return true;
			}
			@Override public boolean revert()
			{
				recreateSection(sectionId, name, order, colorRgb, nestedOverride, autoArchiveOverride);
				// Ascending original index so each goal lands back in place.
				for (Goal g : doomedGoals)
				{
					g.setSectionId(sectionId);
					api.goalStore.insertGoalAt(g, originalIndex.get(g.getId()));
				}
				// Re-add edges the delete scrubbed: doomed→doomed outgoing
				// (from the pre-delete copies) and survivor→doomed incoming.
				for (Goal g : doomedGoals)
				{
					for (String rid : savedRequires.get(g.getId()))
					{
						if (!g.getRequiredGoalIds().contains(rid)) api.goalStore.addRequirement(g.getId(), rid);
					}
					for (String rid : savedOrRequires.get(g.getId()))
					{
						if (!g.getOrRequiredGoalIds().contains(rid)) api.goalStore.addOrRequirement(g.getId(), rid);
					}
				}
				for (String[] e : survivorAndEdges) api.goalStore.addRequirement(e[0], e[1]);
				for (String[] e : survivorOrEdges) api.goalStore.addOrRequirement(e[0], e[1]);
				restoreArchivedHomes(archivedOutIds, sectionId);
				api.goalStore.normalizeOrder();
				return true;
			}
			@Override public String getDescription() { return "Delete section: " + name; }
		});
	}

	/** Recreate a deleted user section with its remembered display properties. */
	private void recreateSection(String sectionId, String name, int order, int colorRgb,
		Boolean nestedOverride, Boolean autoArchiveOverride)
	{
		api.goalStore.recreateUserSection(sectionId, name);
		Section restored = api.goalStore.findSection(sectionId);
		if (restored != null)
		{
			restored.setOrder(order);
			restored.setColorRgb(colorRgb);
			restored.setNestedOverride(nestedOverride);
			restored.setAutoArchiveOverride(autoArchiveOverride);
		}
	}

	/** Re-link completed goals in Completed back to their deleted-then-restored home section. */
	private void restoreArchivedHomes(java.util.List<String> goalIds, String sectionId)
	{
		for (String gid : goalIds)
		{
			Goal g = api.findGoal(gid);
			if (g != null && g.getArchivedFromSectionId() == null)
			{
				g.setArchivedFromSectionId(sectionId);
				api.goalStore.updateGoal(g);
			}
		}
	}

	boolean reorderSection(String sectionId, int newUserIndex)
	{
		log.debug("API.internal reorderSection(sectionId={}, newUserIndex={})", sectionId, newUserIndex);
		Section sec = api.goalStore.findSection(sectionId);
		if (sec == null) return false;
		// Compute current user index
		int prevUserIndex = -1;
		int idx = 0;
		for (Section s : api.goalStore.getSections())
		{
			// Test the flag, not the order: built-ins are no longer all
			// high-ordered (Repeatable is pinned to the top with MIN_VALUE), so
			// an order comparison would count it as a user section here.
			if (!s.isBuiltIn())
			{
				if (s.getId().equals(sectionId)) { prevUserIndex = idx; break; }
				idx++;
			}
		}
		final int snapshotPrev = prevUserIndex;
		final String name = sec.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply() { return api.goalStore.reorderUserSection(sectionId, newUserIndex); }
			@Override public boolean revert()
			{
				if (snapshotPrev < 0) return false;
				return api.goalStore.reorderUserSection(sectionId, snapshotPrev);
			}
			@Override public String getDescription() { return "Reorder section: " + name; }
		});
	}

	boolean moveGoalToSection(String goalId, String sectionId)
	{
		log.debug("API.internal moveGoalToSection(goalId={}, sectionId={})", goalId, sectionId);
		Goal current = api.findGoal(goalId);
		if (current == null) return false;
		if (sectionId == null) return false;
		if (sectionId.equals(current.getSectionId())) return false;
		final String prevSectionId = current.getSectionId();
		final int prevPriority = current.getPriority();
		final String name = current.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply() { return moveGoalToSectionInternal(goalId, sectionId); }
			@Override public boolean revert()
			{
				Goal g = api.findGoal(goalId);
				if (g == null) return false;
				api.goalStore.moveGoalToSection(goalId, prevSectionId);
				g.setPriority(prevPriority);
				api.goalStore.normalizeOrder();
				return true;
			}
			@Override public String getDescription() { return "Move: " + name; }
		});
	}

	boolean moveGoalToSectionInternal(String goalId, String sectionId)
	{
		boolean moved = api.goalStore.moveGoalToSection(goalId, sectionId);
		if (moved)
		{
			Goal g = api.findGoal(goalId);
			if (g != null && g.getType() == com.goalplanner.model.GoalType.SKILL)
			{
				api.reorderingService.enforceSkillOrderingInSection(sectionId);
			}
		}
		return moved;
	}

	/**
	 * Delete every user section that currently holds no goals. Built-in sections
	 * are never touched. Empty sections have nothing to re-home, so each is a
	 * plain {@link #deleteSection} - wrapped in one compound so the whole sweep
	 * is a single undo. Returns the number deleted.
	 */
	int removeEmptyUserSections()
	{
		log.debug("API.internal removeEmptyUserSections()");
		final java.util.Set<String> nonEmpty = new java.util.HashSet<>();
		for (Goal g : api.goalStore.getGoals())
		{
			if (g.getSectionId() != null) nonEmpty.add(g.getSectionId());
		}
		final java.util.List<String> empties = new ArrayList<>();
		for (Section s : api.goalStore.getSections())
		{
			if (s.getBuiltInKind() == null && !nonEmpty.contains(s.getId()))
			{
				empties.add(s.getId());
			}
		}
		if (empties.isEmpty()) return 0;
		api.beginCompound("Delete empty sections");
		try
		{
			for (String id : empties) deleteSection(id, false);
		}
		finally
		{
			api.endCompound();
		}
		return empties.size();
	}

	int removeAllUserSections()
	{
		log.debug("API.internal removeAllUserSections()");
		final java.util.List<Section> sectionSnapshots = new ArrayList<>();
		final java.util.Set<String> userSectionIds = new java.util.HashSet<>();
		for (Section s : api.goalStore.getSections())
		{
			if (s.getBuiltInKind() == null)
			{
				sectionSnapshots.add(s);
				userSectionIds.add(s.getId());
			}
		}
		// Single pass over goals to group by section.
		final java.util.Map<String, java.util.List<String>> goalsBySection = new java.util.HashMap<>();
		for (Goal g : api.goalStore.getGoals())
		{
			if (userSectionIds.contains(g.getSectionId()))
			{
				goalsBySection.computeIfAbsent(g.getSectionId(), k -> new ArrayList<>())
					.add(g.getId());
			}
		}
		if (sectionSnapshots.isEmpty()) return 0;
		boolean ok = api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply() { return api.goalStore.removeAllUserSections() > 0; }
			@Override public boolean revert()
			{
				for (Section s : sectionSnapshots)
				{
					api.goalStore.recreateUserSection(s.getId(), s.getName());
					Section restored = api.goalStore.findSection(s.getId());
					if (restored != null)
					{
						restored.setOrder(s.getOrder());
						restored.setColorRgb(s.getColorRgb());
					}
					for (String gid : goalsBySection.get(s.getId()))
					{
						api.goalStore.moveGoalToSection(gid, s.getId());
					}
				}
				api.goalStore.normalizeOrder();
				return true;
			}
			@Override public String getDescription() { return "Remove all sections (" + sectionSnapshots.size() + ")"; }
		});
		return ok ? sectionSnapshots.size() : 0;
	}

	boolean setSectionCollapsed(String sectionId, boolean collapsed)
	{
		log.debug("API.internal setSectionCollapsed(sectionId={}, collapsed={})", sectionId, collapsed);
		if (sectionId == null) return false;
		for (Section s : api.goalStore.getSections())
		{
			if (sectionId.equals(s.getId()))
			{
				if (s.isCollapsed() == collapsed) return false;
				s.setCollapsed(collapsed);
				api.goalStore.save();
				api.fireIfNotInCompound();
				return true;
			}
		}
		return false;
	}

	boolean toggleSectionCollapsed(String sectionId)
	{
		log.debug("API.internal toggleSectionCollapsed(sectionId={})", sectionId);
		if (sectionId == null) return false;
		for (Section s : api.goalStore.getSections())
		{
			if (sectionId.equals(s.getId()))
			{
				boolean next = !s.isCollapsed();
				s.setCollapsed(next);
				api.goalStore.save();
				api.fireIfNotInCompound();
				return next;
			}
		}
		return false;
	}

	boolean setSectionNestedOverride(String sectionId, Boolean value)
	{
		log.debug("API.internal setSectionNestedOverride(sectionId={}, value={})", sectionId, value);
		if (sectionId == null) return false;
		Section s = api.goalStore.findSection(sectionId);
		if (s == null || java.util.Objects.equals(s.getNestedOverride(), value)) return false;
		s.setNestedOverride(value);
		api.goalStore.save();
		api.fireIfNotInCompound();
		return true;
	}

	boolean setSectionColor(String sectionId, int colorRgb)
	{
		log.debug("API.internal setSectionColor(sectionId={}, colorRgb={})", sectionId, colorRgb);
		Section section = api.goalStore.findSection(sectionId);
		if (section == null) return false;
		int normalized = colorRgb < 0 ? -1 : (colorRgb & 0xFFFFFF);
		if (section.getColorRgb() == normalized) return false;
		final int prevColor = section.getColorRgb();
		final String name = section.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				Section s = api.goalStore.findSection(sectionId);
				if (s == null) return false;
				s.setColorRgb(normalized);
				api.goalStore.save();
				return true;
			}
			@Override public boolean revert()
			{
				Section s = api.goalStore.findSection(sectionId);
				if (s == null) return false;
				s.setColorRgb(prevColor);
				api.goalStore.save();
				return true;
			}
			@Override public String getDescription() { return "Recolor section: " + name; }
		});
	}

	boolean setSectionAutoArchiveOverride(String sectionId, Boolean value)
	{
		log.debug("API.internal setSectionAutoArchiveOverride(sectionId={}, value={})", sectionId, value);
		Section section = api.goalStore.findSection(sectionId);
		if (section == null || section.isBuiltIn()) return false;
		if (java.util.Objects.equals(section.getAutoArchiveOverride(), value)) return false;
		final Boolean prev = section.getAutoArchiveOverride();
		final String name = section.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				Section s = api.goalStore.findSection(sectionId);
				if (s == null) return false;
				s.setAutoArchiveOverride(value);
				// Reconcile both directions: archive existing completed goals out
				// if this is now auto-archive, OR pull previously-archived goals
				// back inline if it's now keep-inline. (Like auto-completion, the
				// relocation itself isn't separately undoable - revert restores
				// just the flag.)
				api.goalStore.reconcileDerivedSections();
				api.goalStore.save();
				return true;
			}
			@Override public boolean revert()
			{
				Section s = api.goalStore.findSection(sectionId);
				if (s == null) return false;
				s.setAutoArchiveOverride(prev);
				api.goalStore.save();
				return true;
			}
			@Override public String getDescription()
			{
				String state = value == null ? "default" : (value ? "auto-archive" : "keep inline");
				return "Completed goals (" + state + "): " + name;
			}
		});
	}

	boolean setGoalColor(String goalId, int colorRgb)
	{
		log.debug("API.internal setGoalColor(goalId={}, colorRgb={})", goalId, colorRgb);
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		int normalized = colorRgb < 0 ? -1 : (colorRgb & 0xFFFFFF);
		if (g.getCustomColorRgb() == normalized) return false;
		final int previousColor = g.getCustomColorRgb();
		final String name = g.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply() { return setGoalColorInternal(goalId, normalized); }
			@Override public boolean revert() { return setGoalColorInternal(goalId, previousColor); }
			@Override public String getDescription() { return "Recolor: " + name; }
		});
	}

	boolean setGoalColorInternal(String goalId, int normalized)
	{
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		g.setCustomColorRgb(normalized);
		api.goalStore.save();
		return true;
	}
}
