package com.goalplanner.api;

import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.model.Tag;
import com.goalplanner.model.TagCategory;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import java.awt.Color;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Encapsulates all tag-management methods extracted from {@link GoalPlannerApiImpl}.
 * Package-private - only {@link GoalPlannerApiImpl} instantiates and delegates to this.
 */
@Slf4j
class TagService
{
	private final GoalPlannerApiImpl api;

	TagService(GoalPlannerApiImpl api)
	{
		this.api = api;
	}

	boolean addTag(String goalId, String label)
	{
		log.debug("API.public addTag(goalId={}, label={})", goalId, label);
		if (goalId == null || label == null || label.trim().isEmpty()) return false;
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		Tag tag = api.goalStore.createUserTag(label.trim(), TagCategory.OTHER);
		if (tag == null) return false;
		if (g.getTagIds() != null && g.getTagIds().contains(tag.getId())) return false; // already has it
		final String tagId = tag.getId();
		final String label2 = label.trim();
		final String name = g.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				Goal cg = api.findGoal(goalId);
				if (cg == null) return false;
				if (cg.getTagIds() == null) cg.setTagIds(new ArrayList<>());
				if (cg.getTagIds().contains(tagId)) return false;
				cg.getTagIds().add(tagId);
				api.goalStore.updateGoal(cg);
				return true;
			}
			@Override public boolean revert()
			{
				Goal cg = api.findGoal(goalId);
				if (cg == null || cg.getTagIds() == null) return false;
				boolean removed = cg.getTagIds().remove(tagId);
				if (removed) api.goalStore.updateGoal(cg);
				return removed;
			}
			@Override public String getDescription() { return "Add tag '" + label2 + "' to " + name; }
		});
	}

	boolean removeTag(String goalId, String label)
	{
		log.debug("API.public removeTag(goalId={}, label={})", goalId, label);
		if (goalId == null || label == null) return false;
		Goal g = api.findGoal(goalId);
		if (g == null || g.getTagIds() == null) return false;
		List<String> defaults = g.getDefaultTagIds() != null ? g.getDefaultTagIds() : Collections.emptyList();
		String toRemove = null;
		int idx = -1;
		for (int i = 0; i < g.getTagIds().size(); i++)
		{
			String id = g.getTagIds().get(i);
			if (defaults.contains(id)) continue;
			Tag tag = api.goalStore.findTag(id);
			if (tag != null && label.equals(tag.getLabel()))
			{
				toRemove = id;
				idx = i;
				break;
			}
		}
		if (toRemove == null) return false;
		final String tagId = toRemove;
		final int restoreIdx = idx;
		final String name = g.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				Goal cg = api.findGoal(goalId);
				if (cg == null || cg.getTagIds() == null) return false;
				boolean removed = cg.getTagIds().remove(tagId);
				if (removed) api.goalStore.updateGoal(cg);
				return removed;
			}
			@Override public boolean revert()
			{
				Goal cg = api.findGoal(goalId);
				if (cg == null) return false;
				if (cg.getTagIds() == null) cg.setTagIds(new ArrayList<>());
				int safeIdx = Math.min(restoreIdx, cg.getTagIds().size());
				cg.getTagIds().add(safeIdx, tagId);
				api.goalStore.updateGoal(cg);
				return true;
			}
			@Override public String getDescription() { return "Remove tag '" + label + "' from " + name; }
		});
	}

	boolean addTagWithCategory(String goalId, String label, String categoryName)
	{
		log.debug("API.internal addTagWithCategory(goalId={}, label={}, category={})",
			goalId, label, categoryName);
		if (goalId == null || label == null || label.trim().isEmpty()) return false;
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		TagCategory category;
		try
		{
			category = TagCategory.valueOf(categoryName);
		}
		catch (IllegalArgumentException ex)
		{
			log.warn("addTagWithCategory: unknown category {}", categoryName);
			return false;
		}
		// SKILLING is system-only: only allow attaching an existing skill tag,
		// never creating a new one. Look up via findTag-by-label; null = reject.
		Tag tag;
		if (category == TagCategory.SKILLING)
		{
			tag = api.goalStore.findTagByLabel(label.trim(), category);
			if (tag == null)
			{
				log.warn("addTagWithCategory: SKILLING tag '{}' does not exist", label);
				return false;
			}
		}
		else
		{
			tag = api.goalStore.createUserTag(label.trim(), category);
		}
		if (tag == null) return false;
		if (g.getTagIds() != null && g.getTagIds().contains(tag.getId())) return false;
		final String tagId = tag.getId();
		final String tagLabel = tag.getLabel();
		final String name = g.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				Goal cg = api.findGoal(goalId);
				if (cg == null) return false;
				if (cg.getTagIds() == null) cg.setTagIds(new ArrayList<>());
				if (cg.getTagIds().contains(tagId)) return false;
				cg.getTagIds().add(tagId);
				api.goalStore.updateGoal(cg);
				return true;
			}
			@Override public boolean revert()
			{
				Goal cg = api.findGoal(goalId);
				if (cg == null || cg.getTagIds() == null) return false;
				boolean removed = cg.getTagIds().remove(tagId);
				if (removed) api.goalStore.updateGoal(cg);
				return removed;
			}
			@Override public String getDescription() { return "Add tag '" + tagLabel + "' to " + name; }
		});
	}

	boolean restoreDefaultTags(String goalId)
	{
		log.debug("API.public restoreDefaultTags(goalId={})", goalId);
		if (goalId == null) return false;
		Goal g = api.findGoal(goalId);
		if (g == null) return false;
		List<String> defaults = g.getDefaultTagIds();
		if (defaults == null || defaults.isEmpty()) return false;
		final List<String> snapshotTagIds = new ArrayList<>(g.getTagIds() != null ? g.getTagIds() : Collections.emptyList());
		final String name = g.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				Goal cg = api.findGoal(goalId);
				if (cg == null) return false;
				cg.setTagIds(new ArrayList<>(cg.getDefaultTagIds()));
				api.goalStore.updateGoal(cg);
				return true;
			}
			@Override public boolean revert()
			{
				Goal cg = api.findGoal(goalId);
				if (cg == null) return false;
				cg.setTagIds(new ArrayList<>(snapshotTagIds));
				api.goalStore.updateGoal(cg);
				return true;
			}
			@Override public String getDescription() { return "Restore defaults: " + name; }
		});
	}

	List<TagView> queryAllTags()
	{
		log.debug("API.internal queryAllTags()");
		List<TagView> out = new ArrayList<>();
		for (Tag t : api.goalStore.getTags())
		{
			out.add(toTagView(t));
		}
		return out;
	}

	String createUserTag(String label, String categoryName)
	{
		log.debug("API.internal createUserTag(label={}, category={})", label, categoryName);
		TagCategory category;
		try
		{
			category = TagCategory.valueOf(categoryName);
		}
		catch (IllegalArgumentException ex)
		{
			log.warn("createUserTag: unknown category {}", categoryName);
			return null;
		}
		if (category == TagCategory.SKILLING)
		{
			log.warn("createUserTag: SKILLING category is reserved for system tags");
			return null;
		}
		// Use the same find-or-create semantics. If it already existed, no
		// command - return existing id with no history entry.
		Tag existing = api.goalStore.findTagByLabel(label != null ? label.trim() : "", category);
		if (existing != null) return existing.getId();
		Tag tag = api.goalStore.createUserTag(label, category);
		if (tag == null) return null;
		final Tag captured = tag;
		final String tagLabel = tag.getLabel();
		api.executeCommand(new com.goalplanner.command.Command()
		{
			private boolean firstApply = true;
			@Override public boolean apply()
			{
				if (firstApply) { firstApply = false; return true; }
				api.goalStore.recreateTag(captured);
				return true;
			}
			@Override public boolean revert() { return api.goalStore.deleteTag(captured.getId()); }
			@Override public String getDescription() { return "Create tag: " + tagLabel; }
		});
		return tag.getId();
	}

	boolean renameTag(String tagId, String newLabel)
	{
		log.debug("API.internal renameTag(tagId={}, newLabel={})", tagId, newLabel);
		Tag t = api.goalStore.findTag(tagId);
		if (t == null) return false;
		final String prevLabel = t.getLabel();
		final String resolved = newLabel != null ? newLabel.trim() : "";
		if (resolved.isEmpty() || resolved.equals(prevLabel)) return false;
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply() { return api.goalStore.renameTag(tagId, resolved); }
			@Override public boolean revert() { return api.goalStore.renameTag(tagId, prevLabel); }
			@Override public String getDescription() { return "Rename tag: " + prevLabel + " -> " + resolved; }
		});
	}

	boolean recolorTag(String tagId, int colorRgb)
	{
		log.debug("API.internal recolorTag(tagId={}, colorRgb={})", tagId, colorRgb);
		Tag t = api.goalStore.findTag(tagId);
		if (t == null) return false;
		final int prevColor = t.getColorRgb();
		final TagCategory cat = t.getCategory();
		final int prevCategoryColor = api.goalStore.isCategoryColorOverridden(cat)
			? api.goalStore.getCategoryColor(cat) : -1;
		final String label = t.getLabel();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				if (cat == TagCategory.OTHER) return api.goalStore.recolorTag(tagId, colorRgb);
				return api.goalStore.setCategoryColor(cat, colorRgb);
			}
			@Override public boolean revert()
			{
				if (cat == TagCategory.OTHER) return api.goalStore.recolorTag(tagId, prevColor);
				return api.goalStore.setCategoryColor(cat, prevCategoryColor);
			}
			@Override public String getDescription() { return "Recolor tag: " + label; }
		});
	}

	boolean deleteTag(String tagId)
	{
		log.debug("API.internal deleteTag(tagId={})", tagId);
		Tag t = api.goalStore.findTag(tagId);
		if (t == null || t.isSystem()) return false;
		// Snapshot the tag entity AND the per-goal references so revert can
		// restore both. We also snapshot defaultTagIds membership for goals
		// that had this tag in their defaults.
		final Tag captured = t;
		final List<String> tagOnGoals = new ArrayList<>();
		final List<String> tagOnDefaults = new ArrayList<>();
		for (Goal g : api.goalStore.getGoals())
		{
			if (g.getTagIds() != null && g.getTagIds().contains(tagId)) tagOnGoals.add(g.getId());
			if (g.getDefaultTagIds() != null && g.getDefaultTagIds().contains(tagId)) tagOnDefaults.add(g.getId());
		}
		final String label = t.getLabel();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply() { return api.goalStore.deleteTag(tagId); }
			@Override public boolean revert()
			{
				api.goalStore.recreateTag(captured);
				for (String gid : tagOnGoals)
				{
					Goal g = api.findGoal(gid);
					if (g == null) continue;
					if (g.getTagIds() == null) g.setTagIds(new ArrayList<>());
					if (!g.getTagIds().contains(tagId)) g.getTagIds().add(tagId);
				}
				for (String gid : tagOnDefaults)
				{
					Goal g = api.findGoal(gid);
					if (g == null) continue;
					if (g.getDefaultTagIds() == null) g.setDefaultTagIds(new ArrayList<>());
					if (!g.getDefaultTagIds().contains(tagId)) g.getDefaultTagIds().add(tagId);
				}
				return true;
			}
			@Override public String getDescription() { return "Delete tag: " + label; }
		});
	}

	boolean setCategoryColor(String categoryName, int colorRgb)
	{
		log.debug("API.internal setCategoryColor(category={}, colorRgb={})", categoryName, colorRgb);
		TagCategory category;
		try { category = TagCategory.valueOf(categoryName); }
		catch (IllegalArgumentException ex) { return false; }
		final int prevColor = api.goalStore.isCategoryColorOverridden(category)
			? api.goalStore.getCategoryColor(category) : -1;
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply() { return api.goalStore.setCategoryColor(category, colorRgb); }
			@Override public boolean revert() { return api.goalStore.setCategoryColor(category, prevColor); }
			@Override public String getDescription() { return "Recolor category: " + category.name(); }
		});
	}

	boolean resetCategoryColor(String categoryName)
	{
		log.debug("API.internal resetCategoryColor(category={})", categoryName);
		return setCategoryColor(categoryName, -1);
	}

	int getCategoryColor(String categoryName)
	{
		try { return api.goalStore.getCategoryColor(TagCategory.valueOf(categoryName)); }
		catch (IllegalArgumentException ex) { return 0; }
	}

	int getCategoryDefaultColor(String categoryName)
	{
		try
		{
			Color c = TagCategory.valueOf(categoryName).getColor();
			return (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
		}
		catch (IllegalArgumentException ex) { return 0; }
	}

	boolean isCategoryColorOverridden(String categoryName)
	{
		try { return api.goalStore.isCategoryColorOverridden(TagCategory.valueOf(categoryName)); }
		catch (IllegalArgumentException ex) { return false; }
	}

	boolean setTagIcon(String tagId, String iconKey)
	{
		log.debug("API.internal setTagIcon(tagId={}, iconKey={})", tagId, iconKey);
		Tag t = api.goalStore.findTag(tagId);
		if (t == null) return false;
		final String prevIcon = t.getIconKey();
		final String label = t.getLabel();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply() { return api.goalStore.setTagIcon(tagId, iconKey); }
			@Override public boolean revert() { api.goalStore.setTagIcon(tagId, prevIcon); return true; }
			@Override public String getDescription() { return "Set icon: " + label; }
		});
	}

	boolean clearTagIcon(String tagId)
	{
		log.debug("API.internal clearTagIcon(tagId={})", tagId);
		return setTagIcon(tagId, null);
	}

	boolean setTagColor(String goalId, String tagLabel, int colorRgb)
	{
		// Per-tag colors are OTHER-only. For OTHER tags this
		// stores the color on the tag entity; for other categories it
		// delegates to setCategoryColor which affects every tag in the
		// category. SKILLING is rejected.
		log.debug("API.internal setTagColor(goalId={}, tagLabel={}, colorRgb={})",
			goalId, tagLabel, colorRgb);
		Goal g = api.findGoal(goalId);
		if (g == null || tagLabel == null || g.getTagIds() == null) return false;
		for (String id : g.getTagIds())
		{
			Tag tag = api.goalStore.findTag(id);
			if (tag != null && tagLabel.equals(tag.getLabel()))
			{
				boolean changed;
				if (tag.getCategory() == TagCategory.OTHER)
				{
					changed = api.goalStore.recolorTag(id, colorRgb);
				}
				else
				{
					changed = api.goalStore.setCategoryColor(tag.getCategory(), colorRgb);
				}
				if (changed) api.fireIfNotInCompound();
				return changed;
			}
		}
		return false;
	}

	List<GoalPlannerInternalApi.TagRemovalOption> getRemovableTagsForSelection(Set<String> goalIds)
	{
		if (goalIds == null || goalIds.isEmpty()) return Collections.emptyList();
		// tagId → count of selected goals where it's both present and removable
		Map<String, Integer> counts = new HashMap<>();
		for (String goalId : goalIds)
		{
			Goal g = api.findGoal(goalId);
			if (g == null || g.getTagIds() == null) continue;
			boolean isCustom = g.getType() == GoalType.CUSTOM;
			List<String> defaults = g.getDefaultTagIds() != null ? g.getDefaultTagIds() : Collections.emptyList();
			for (String tid : g.getTagIds())
			{
				if (!isCustom && defaults.contains(tid)) continue;
				counts.merge(tid, 1, Integer::sum);
			}
		}
		List<GoalPlannerInternalApi.TagRemovalOption> out = new ArrayList<>(counts.size());
		for (Map.Entry<String, Integer> e : counts.entrySet())
		{
			Tag tag = api.goalStore.findTag(e.getKey());
			if (tag == null) continue;
			out.add(new GoalPlannerInternalApi.TagRemovalOption(tag.getId(), tag.getLabel(), e.getValue()));
		}
		// Sort: count desc, then label asc (case-insensitive)
		out.sort((a, b) -> {
			if (a.count != b.count) return Integer.compare(b.count, a.count);
			return a.label.compareToIgnoreCase(b.label);
		});
		return out;
	}

	int bulkRemoveTagFromGoals(Set<String> goalIds, String tagId)
	{
		log.debug("API.internal bulkRemoveTagFromGoals({} goals, tagId={})",
			goalIds == null ? 0 : goalIds.size(), tagId);
		if (goalIds == null || goalIds.isEmpty() || tagId == null) return 0;
		// Snapshot which goals will lose this tag and at what
		// index, so revert can re-insert at the same position.
		final String fTagId = tagId;
		final List<int[]> snapshots = new ArrayList<>(); // unused
		final List<String> goalIdsAffected = new ArrayList<>();
		final List<Integer> indices = new ArrayList<>();
		for (String goalId : goalIds)
		{
			Goal g = api.findGoal(goalId);
			if (g == null || g.getTagIds() == null) continue;
			if (!g.getTagIds().contains(tagId)) continue;
			boolean isCustom = g.getType() == GoalType.CUSTOM;
			List<String> defaults = g.getDefaultTagIds() != null ? g.getDefaultTagIds() : Collections.emptyList();
			if (!isCustom && defaults.contains(tagId)) continue;
			goalIdsAffected.add(goalId);
			indices.add(g.getTagIds().indexOf(tagId));
		}
		if (goalIdsAffected.isEmpty()) return 0;
		final Tag tagSnapshot = api.goalStore.findTag(tagId);
		final String tagLabel = tagSnapshot != null ? tagSnapshot.getLabel() : tagId;
		boolean ok = api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				for (String goalId : goalIdsAffected)
				{
					Goal g = api.findGoal(goalId);
					if (g == null || g.getTagIds() == null) continue;
					g.getTagIds().remove(fTagId);
					api.goalStore.updateGoal(g);
				}
				return true;
			}
			@Override public boolean revert()
			{
				for (int i = 0; i < goalIdsAffected.size(); i++)
				{
					Goal g = api.findGoal(goalIdsAffected.get(i));
					if (g == null) continue;
					if (g.getTagIds() == null) g.setTagIds(new ArrayList<>());
					int idx = Math.min(indices.get(i), g.getTagIds().size());
					g.getTagIds().add(idx, fTagId);
					api.goalStore.updateGoal(g);
				}
				return true;
			}
			@Override public String getDescription()
			{
				return "Remove tag '" + tagLabel + "' (" + goalIdsAffected.size() + " goals)";
			}
		});
		return ok ? goalIdsAffected.size() : 0;
	}

	/**
	 * Converts a {@link Tag} entity to its API view representation. This helper
	 * is also used by {@link GoalPlannerApiImpl#toGoalView} for tag splitting.
	 */
	TagView toTagView(Tag t)
	{
		// Defensive: null category fallback to OTHER (handles enum removal migration).
		TagCategory cat = t.getCategory() != null ? t.getCategory() : TagCategory.OTHER;
		int defaultRgb = api.goalStore.getCategoryDefaultColor(cat);

		int currentRgb;
		boolean overridden;
		if (cat == TagCategory.OTHER)
		{
			// OTHER is special: per-tag colors. Each Other tag carries its own.
			if (t.getColorRgb() >= 0)
			{
				currentRgb = t.getColorRgb();
				overridden = true;
			}
			else
			{
				currentRgb = defaultRgb;
				overridden = false;
			}
		}
		else
		{
			// BOSS/RAID/CLUE/MINIGAME/SKILLING: shared category color. The
			// per-tag colorRgb field is ignored for these categories.
			currentRgb = api.goalStore.getCategoryColor(cat);
			overridden = api.goalStore.isCategoryColorOverridden(cat);
		}

		TagView v = new TagView(t.getLabel(), cat.name(),
			currentRgb, defaultRgb, overridden);
		v.id = t.getId();
		v.system = t.isSystem();
		v.iconKey = t.getIconKey();
		return v;
	}
}
