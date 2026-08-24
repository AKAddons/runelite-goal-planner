package com.goalplanner.api;

import com.goalplanner.model.Goal;
import com.goalplanner.model.Section;
import com.goalplanner.share.SectionShareDto;
import com.goalplanner.share.ShareBundle;
import com.goalplanner.share.ShareMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a {@link ShareBundle} from goals currently in the store (export side).
 * Thin wrapper over {@code ShareMapper} that gathers the goals for a section (or
 * an explicit id set) and supplies the tag lookup. The encode-to-string,
 * clipboard steps live in the UI/plugin layer.
 */
class ShareExportService
{
	private final GoalPlannerApiImpl api;

	ShareExportService(GoalPlannerApiImpl api)
	{
		this.api = api;
	}

	/** Bundle of every goal in the given section, or null if no such section. */
	ShareBundle exportSection(String sectionId, String sharedBy)
	{
		Section section = api.goalStore.findSection(sectionId);
		if (section == null)
		{
			return null;
		}
		List<Goal> goals = new ArrayList<>();
		for (Goal g : api.goalStore.getGoals())
		{
			if (g != null && sectionId.equals(g.getSectionId()))
			{
				goals.add(g);
			}
		}
		return ShareMapper.toBundle(
			ShareBundle.Kind.SECTION, section.getName(), section.getColorRgb(),
			goals, api.goalStore::findTag, sharedBy);
	}

	/**
	 * Multi-section (v2) bundle of EVERY user section that has goals, in
	 * display order. Built-ins (the default plan) are not included. Returns
	 * null when there is no user section with goals.
	 */
	ShareBundle exportAllUserSections(String sharedBy)
	{
		List<SectionShareDto> sections = new ArrayList<>();
		List<List<Goal>> sectionGoals = new ArrayList<>();
		for (Section section : api.goalStore.getSections())
		{
			if (section == null || section.isBuiltIn())
			{
				continue;
			}
			List<Goal> goals = new ArrayList<>();
			for (Goal g : api.goalStore.getGoals())
			{
				if (g != null && section.getId().equals(g.getSectionId()))
				{
					goals.add(g);
				}
			}
			if (goals.isEmpty())
			{
				continue;
			}
			sections.add(ShareMapper.toSectionDto(
				section.getName(), section.getColorRgb(), false, section.getNestedOverride(),
				goals, api.goalStore::findTag));
			sectionGoals.add(goals);
		}
		if (sections.isEmpty())
		{
			return null;
		}
		ShareBundle bundle = ShareMapper.toMultiBundle(sections, sharedBy);
		bundle.setCrossEdges(ShareMapper.crossEdges(sectionGoals));
		return bundle;
	}

	/**
	 * Bundle of the given goals (in store order), or null if none resolve.
	 * The selection's SOURCE SECTIONS are preserved: goals from each user
	 * section export as that section (name + colour), goals from the default
	 * plan as a {@code targetDefault} entry. A selection entirely within the
	 * default plan stays a legacy loose-goals bundle, and one entirely within
	 * a single user section stays a legacy section bundle - both on the v1
	 * wire so older plugin builds keep importing them; anything spanning
	 * sections needs the v2 wire.
	 */
	ShareBundle exportGoals(List<String> goalIds, String sharedBy)
	{
		if (goalIds == null || goalIds.isEmpty())
		{
			return null;
		}
		Set<String> wanted = new HashSet<>(goalIds);
		// Group the selection by source section, in store (display) order;
		// the two built-ins collapse into one "default plan" group.
		List<Goal> defaultGroup = new ArrayList<>();
		Map<String, List<Goal>> bySection = new LinkedHashMap<>();
		for (Goal g : api.goalStore.getGoals())
		{
			if (g == null || !wanted.contains(g.getId()))
			{
				continue;
			}
			Section sec = api.goalStore.findSection(g.getSectionId());
			if (sec == null || sec.isBuiltIn())
			{
				defaultGroup.add(g);
			}
			else
			{
				bySection.computeIfAbsent(sec.getId(), k -> new ArrayList<>()).add(g);
			}
		}
		if (defaultGroup.isEmpty() && bySection.isEmpty())
		{
			return null;
		}
		if (bySection.isEmpty())
		{
			// Entirely from the default plan → legacy loose goals (v1 wire).
			return ShareMapper.toBundle(
				ShareBundle.Kind.GOALS, null, -1, defaultGroup, api.goalStore::findTag, sharedBy);
		}
		if (defaultGroup.isEmpty() && bySection.size() == 1)
		{
			// Entirely from one user section → legacy section bundle (v1 wire).
			String sid = bySection.keySet().iterator().next();
			Section sec = api.goalStore.findSection(sid);
			return ShareMapper.toBundle(
				ShareBundle.Kind.SECTION, sec.getName(), sec.getColorRgb(),
				bySection.get(sid), api.goalStore::findTag, sharedBy);
		}
		// Spans sections → multi-section (v2) bundle preserving each source.
		List<SectionShareDto> sections = new ArrayList<>();
		List<List<Goal>> groups = new ArrayList<>();
		for (Map.Entry<String, List<Goal>> e : bySection.entrySet())
		{
			Section sec = api.goalStore.findSection(e.getKey());
			sections.add(ShareMapper.toSectionDto(
				sec.getName(), sec.getColorRgb(), false, sec.getNestedOverride(),
				e.getValue(), api.goalStore::findTag));
			groups.add(e.getValue());
		}
		if (!defaultGroup.isEmpty())
		{
			sections.add(ShareMapper.toSectionDto(
				null, -1, true, null, defaultGroup, api.goalStore::findTag));
			groups.add(defaultGroup);
		}
		ShareBundle bundle = ShareMapper.toMultiBundle(sections, sharedBy);
		bundle.setCrossEdges(ShareMapper.crossEdges(groups));
		return bundle;
	}
}
