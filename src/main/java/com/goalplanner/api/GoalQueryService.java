package com.goalplanner.api;

import com.goalplanner.model.AccountMetric;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.model.Section;
import com.goalplanner.model.Tag;
import com.goalplanner.model.TagCategory;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Experience;

/**
 * Encapsulates goal/section query methods extracted from {@link GoalPlannerApiImpl}.
 * Package-private - only {@link GoalPlannerApiImpl} instantiates and delegates to this.
 */
@Slf4j
class GoalQueryService
{
	/** Neutral default section header color (matches SectionHeaderRow BORDER_COLOR). */
	static final int SECTION_DEFAULT_COLOR_RGB = (60 << 16) | (60 << 8) | 60;

	private final GoalPlannerApiImpl api;

	GoalQueryService(GoalPlannerApiImpl api)
	{
		this.api = api;
	}

	/**
	 * Get a fresh GoalView for a single goal by ID. O(1) goal lookup
	 * + O(relations) view construction. Used for incremental card updates.
	 */
	GoalView queryGoalView(String goalId)
	{
		if (goalId == null) return null;
		Goal g = api.goalStore.findGoalById(goalId);
		if (g == null) return null;
		return toGoalView(g);
	}

	List<GoalView> queryAllGoals()
	{
		log.debug("API.public queryAllGoals()");
		// Ensure the flat list is in canonical (section.order, priority) order before snapshot.
		api.goalStore.normalizeOrder();
		List<Goal> source = api.goalStore.getGoals();
		List<GoalView> out = new ArrayList<>(source.size());
		for (Goal g : source)
		{
			out.add(toGoalView(g));
		}
		return out;
	}

	List<GoalView> searchGoals(String query)
	{
		log.debug("API.internal searchGoals(query={})", query);
		List<GoalView> all = queryAllGoals();
		if (query == null) return all;
		String needle = query.trim().toLowerCase();
		if (needle.isEmpty()) return all;

		// Phase 1: find direct matches.
		java.util.Set<String> matchedIds = new java.util.LinkedHashSet<>();
		java.util.Map<String, GoalView> viewById = new java.util.LinkedHashMap<>();
		for (GoalView gv : all)
		{
			viewById.put(gv.id, gv);
			if (matchesSearch(gv, needle)) matchedIds.add(gv.id);
		}

		// Phase 2: expand to include the full relation tree (both directions)
		// for every direct match. BFS from each matched goal.
		java.util.Set<String> expanded = new java.util.LinkedHashSet<>(matchedIds);
		java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(matchedIds);
		while (!queue.isEmpty())
		{
			String id = queue.poll();
			Goal g = api.goalStore.findGoalById(id);
			if (g == null) continue;
			// Walk outgoing edges (requirements)
			if (g.getRequiredGoalIds() != null)
			{
				for (String reqId : g.getRequiredGoalIds())
				{
					if (expanded.add(reqId)) queue.add(reqId);
				}
			}
			// Walk incoming edges (dependents)
			for (String depId : api.goalStore.getDependents(id))
			{
				if (expanded.add(depId)) queue.add(depId);
			}
		}

		// Phase 3: return all expanded goals in canonical order.
		List<GoalView> out = new ArrayList<>();
		for (GoalView gv : all)
		{
			if (expanded.contains(gv.id)) out.add(gv);
		}
		return out;
	}

	/**
	 * Pure match check used by {@link #searchGoals(String)}. Pulled out for
	 * unit-testability and to keep the loop body trivial. The needle is
	 * pre-lowercased and pre-trimmed by the caller.
	 */
	private boolean matchesSearch(GoalView gv, String needle)
	{
		if (gv.name != null && gv.name.toLowerCase().contains(needle)) return true;
		if (gv.description != null && gv.description.toLowerCase().contains(needle)) return true;
		// GoalType display name (e.g. "Combat Achievement", "Skill")
		try
		{
			String typeDisplay = com.goalplanner.model.GoalType.valueOf(gv.type).getDisplayName();
			if (typeDisplay.toLowerCase().contains(needle)) return true;
		}
		catch (IllegalArgumentException ignored) {}
		// Section title
		if (gv.sectionId != null)
		{
			com.goalplanner.model.Section sec = api.goalStore.findSection(gv.sectionId);
			if (sec != null && sec.getName() != null
				&& sec.getName().toLowerCase().contains(needle)) return true;
		}
		// Tags: labels + category display names. defaultTags + customTags cover all.
		if (matchesAnyTag(gv.defaultTags, needle)) return true;
		if (matchesAnyTag(gv.customTags, needle)) return true;
		return false;
	}

	private boolean matchesAnyTag(List<TagView> tags, String needle)
	{
		if (tags == null) return false;
		for (TagView t : tags)
		{
			if (t.label != null && t.label.toLowerCase().contains(needle)) return true;
			if (t.category != null)
			{
				try
				{
					String catDisplay = TagCategory.valueOf(t.category).getDisplayName();
					if (catDisplay.toLowerCase().contains(needle)) return true;
				}
				catch (IllegalArgumentException ignored) {}
			}
		}
		return false;
	}

	List<SectionView> queryAllSections()
	{
		log.debug("API.public queryAllSections()");
		List<Section> source = new ArrayList<>(api.goalStore.getSections());
		source.sort(java.util.Comparator.comparingInt(Section::getOrder));
		List<SectionView> out = new ArrayList<>(source.size());
		for (Section s : source)
		{
			out.add(toSectionView(s));
		}
		return out;
	}

	GoalView toGoalView(Goal g)
	{
		GoalView v = new GoalView();
		v.id = g.getId();
		v.type = g.getType().name();
		v.name = g.getName();
		v.description = g.getDescription();
		// Period-relative for bite-sized goals, identical to the raw values for
		// everything else. Mapped here rather than in the renderers so the card
		// text, the progress bar and the tooltip all agree without each one
		// having to know about repeatChunk.
		v.currentValue = g.getDisplayCurrent();
		v.targetValue = g.getDisplayTarget();
		v.completedAt = g.getCompletedAt();
		v.sectionId = g.getSectionId();
		v.spriteId = g.getSpriteId();
		v.selected = api.selectedGoalIds.contains(g.getId());
		v.optional = g.isOptional();
		v.nestCollapsed = g.isNestCollapsed();
		v.blockedRequirements = api.blockedRequirements(g.getId());

		// Background color: type default + optional user override. DTO carries both
		// so consumers can show "reset to default" affordances with the right preview.
		java.awt.Color typeC = g.getType().getColor();
		int typeRgb = (typeC.getRed() << 16) | (typeC.getGreen() << 8) | typeC.getBlue();
		v.defaultBackgroundColorRgb = typeRgb;
		if (g.getCustomColorRgb() >= 0)
		{
			v.backgroundColorRgb = g.getCustomColorRgb();
			v.backgroundColorOverridden = true;
		}
		else
		{
			v.backgroundColorRgb = typeRgb;
			v.backgroundColorOverridden = false;
		}

		// Tag splitting: defaultTagIds is the snapshot from creation; the rest of
		// tagIds are user-added. Each id is dereferenced via the tag store.
		List<String> defaultIds = g.getDefaultTagIds() != null ? g.getDefaultTagIds() : java.util.Collections.emptyList();
		List<String> allIds = g.getTagIds() != null ? g.getTagIds() : java.util.Collections.emptyList();

		v.defaultTags = new ArrayList<>();
		for (String id : defaultIds)
		{
			Tag tag = api.goalStore.findTag(id);
			if (tag != null) v.defaultTags.add(api.tagService.toTagView(tag));
		}

		v.customTags = new ArrayList<>();
		for (String id : allIds)
		{
			if (defaultIds.contains(id)) continue;
			Tag tag = api.goalStore.findTag(id);
			if (tag != null) v.customTags.add(api.tagService.toTagView(tag));
		}

		// Resolve relations for the card hover tooltip.
		// Skill-chain edges (same skill, different level) are internal
		// bookkeeping and excluded from the display lists.
		v.requiresNames = new ArrayList<>();
		if (g.getRequiredGoalIds() != null)
		{
			for (String reqId : g.getRequiredGoalIds())
			{
				Goal req = api.findGoal(reqId);
				if (req == null || req.getName() == null) continue;
				if (isSkillChainEdge(g, req)) continue;
				v.requiresNames.add(toRelationView(req));
			}
		}
		// OR-prereqs: displayed with "(or)" suffix so the user sees the
		// difference between AND and OR edges.
		v.orRequiresNames = new ArrayList<>();
		if (g.getOrRequiredGoalIds() != null)
		{
			for (String reqId : g.getOrRequiredGoalIds())
			{
				Goal req = api.findGoal(reqId);
				if (req == null || req.getName() == null) continue;
				v.orRequiresNames.add(toRelationView(req));
			}
		}
		v.requiredByNames = new ArrayList<>();
		v.orRequiredByNames = new ArrayList<>();
		for (Goal other : api.goalStore.getGoals())
		{
			if (other.getId().equals(g.getId())) continue;
			if (other.getRequiredGoalIds() != null
				&& other.getRequiredGoalIds().contains(g.getId())
				&& other.getName() != null)
			{
				if (isSkillChainEdge(other, g)) continue;
				v.requiredByNames.add(toRelationView(other));
			}
			// OR-edges: the parent shows as "Required by" (it requires
			// this goal), and the sibling OR-prereqs show as "Also
			// Completed By" (completing any one of them satisfies the
			// parent).
			if (other.getOrRequiredGoalIds() != null
				&& other.getOrRequiredGoalIds().contains(g.getId())
				&& other.getName() != null)
			{
				// Parent goes into requiredByNames
				v.requiredByNames.add(toRelationView(other));
				// Sibling OR-prereqs go into orRequiredByNames
				for (String siblingId : other.getOrRequiredGoalIds())
				{
					if (siblingId.equals(g.getId())) continue;
					Goal sibling = api.findGoal(siblingId);
					if (sibling != null && sibling.getName() != null)
					{
						v.orRequiredByNames.add(toRelationView(sibling));
					}
				}
			}
		}

		// Type-specific attributes
		v.attributes = new java.util.HashMap<>();
		switch (g.getType())
		{
			case SKILL:
				if (g.getSkillName() != null) v.attributes.put("skillName", g.getSkillName());
				break;
			case QUEST:
				if (g.getQuestName() != null) v.attributes.put("questName", g.getQuestName());
				if (g.getTooltip() != null) v.attributes.put("tooltip", g.getTooltip());
				break;
			case DIARY:
				v.attributes.put("area", g.getName());
				if (g.getDescription() != null && g.getDescription().endsWith(" Achievement Diary"))
				{
					String tier = g.getDescription().substring(
						0, g.getDescription().length() - " Achievement Diary".length()).toUpperCase();
					v.attributes.put("tier", tier);
				}
				if (g.getVarbitId() > 0) v.attributes.put("varbitId", g.getVarbitId());
				if (g.getTooltip() != null) v.attributes.put("tooltip", g.getTooltip());
				break;
			case ITEM_GRIND:
				if (g.getItemId() > 0) v.attributes.put("itemId", g.getItemId());
				break;
			case COMBAT_ACHIEVEMENT:
				if (g.getCaTaskId() >= 0) v.attributes.put("caTaskId", g.getCaTaskId());
				if (g.getDescription() != null && g.getDescription().endsWith(" Combat Achievement"))
				{
					String tier = g.getDescription().substring(
						0, g.getDescription().length() - " Combat Achievement".length()).toUpperCase();
					v.attributes.put("tier", tier);
				}
				// Monster name lives in the BOSS/RAID tag, not its own field
				for (String id : allIds)
				{
					Tag tag = api.goalStore.findTag(id);
					if (tag != null && (tag.getCategory() == TagCategory.BOSS || tag.getCategory() == TagCategory.RAID))
					{
						v.attributes.put("monster", tag.getLabel());
						break;
					}
				}
				if (g.getTooltip() != null) v.attributes.put("tooltip", g.getTooltip());
				break;
			case ACCOUNT:
				if (g.getAccountMetric() != null)
				{
					v.attributes.put("accountMetric", g.getAccountMetric());
					try
					{
						AccountMetric m = AccountMetric.valueOf(g.getAccountMetric());
						String resolvedIconKey = m.resolveIconKeyForTarget(g.getTargetValue());
						if (resolvedIconKey != null)
						{
							v.attributes.put("iconKey", resolvedIconKey);
						}
						// Icons derive from the METRIC, not the creation path:
						// goals that arrived without a persisted sprite (share
						// imports, API callers) render the same icon a
						// plugin-created goal would.
						if (v.spriteId == 0)
						{
							v.spriteId = m.resolveSpriteForTarget(g.getTargetValue());
						}
					}
					catch (IllegalArgumentException ignored) {}
				}
				break;
			case BOSS:
				if (g.getBossName() != null) v.attributes.put("bossName", g.getBossName());
				if (g.getItemId() > 0) v.attributes.put("itemId", g.getItemId());
				break;
			case CUSTOM:
				if (g.getItemId() > 0) v.attributes.put("itemId", g.getItemId());
				break;
			default:
				break;
		}

		return v;
	}

	/**
	 * True when both goals are implicit chain links that should not
	 * clutter the tooltip: same-skill edges (40 Prayer → 60 Prayer)
	 * or same-metric account edges (96 Combat → 126 Combat).
	 */
	static boolean isSkillChainEdge(Goal a, Goal b)
	{
		if (a.getType() == GoalType.SKILL && b.getType() == GoalType.SKILL)
		{
			return a.getSkillName() != null && a.getSkillName().equals(b.getSkillName());
		}
		if (a.getType() == GoalType.ACCOUNT && b.getType() == GoalType.ACCOUNT)
		{
			return a.getAccountMetric() != null && a.getAccountMetric().equals(b.getAccountMetric());
		}
		return false;
	}

	static GoalView.RelationView toRelationView(Goal g)
	{
		String skillName = null;
		int targetLevel = 0;
		if (g.getType() == GoalType.SKILL && g.getSkillName() != null)
		{
			skillName = g.getSkillName();
			targetLevel = Experience.getLevelForXp(g.getTargetValue());
		}
		return new GoalView.RelationView(g.getName(), skillName, targetLevel, g.isOptional());
	}

	static SectionView toSectionView(Section s)
	{
		SectionView v = new SectionView();
		v.id = s.getId();
		v.name = s.getName();
		v.order = s.getOrder();
		v.collapsed = s.isCollapsed();
		v.builtIn = s.isBuiltIn();
		v.kind = s.getBuiltInKind() != null ? s.getBuiltInKind().name() : null;
		v.autoArchiveOverride = s.getAutoArchiveOverride();
		v.nestedOverride = s.getNestedOverride();
		v.defaultColorRgb = SECTION_DEFAULT_COLOR_RGB;
		if (s.getColorRgb() >= 0)
		{
			v.colorRgb = s.getColorRgb();
			v.colorOverridden = true;
		}
		else
		{
			v.colorRgb = SECTION_DEFAULT_COLOR_RGB;
			v.colorOverridden = false;
		}
		return v;
	}

	List<GoalView> queryGoalsTopologicallySorted(String sectionId)
	{
		log.debug("API.internal queryGoalsTopologicallySorted(sectionId={})", sectionId);
		if (sectionId == null) return new ArrayList<>();
		List<Goal> sectionGoals = new ArrayList<>();
		for (Goal g : api.goalStore.getGoals())
		{
			if (sectionId.equals(g.getSectionId())) sectionGoals.add(g);
		}
		return topoSortSection(sectionGoals, sectionId);
	}

	/**
	 * Topo-sort ALL sections in one pass. Returns a map of sectionId →
	 * sorted GoalView list. Avoids repeated scans of the goals list.
	 */
	java.util.Map<String, List<GoalView>> queryAllGoalsTopologicallySorted()
	{
		// Group goals by section in one scan.
		java.util.Map<String, List<Goal>> goalsBySection = new java.util.LinkedHashMap<>();
		for (Goal g : api.goalStore.getGoals())
		{
			if (g.getSectionId() != null)
			{
				goalsBySection.computeIfAbsent(g.getSectionId(), k -> new ArrayList<>())
					.add(g);
			}
		}
		java.util.Map<String, List<GoalView>> result = new java.util.LinkedHashMap<>();
		for (java.util.Map.Entry<String, List<Goal>> entry : goalsBySection.entrySet())
		{
			result.put(entry.getKey(), topoSortSection(entry.getValue(), entry.getKey()));
		}
		return result;
	}

	/** Core topo-sort for a single section's goals. */
	private List<GoalView> topoSortSection(List<Goal> sectionGoals, String sectionId)
	{
		List<GoalView> out = new ArrayList<>();
		if (sectionGoals.isEmpty()) return out;

		// Completed section: most-recently-completed at the top. Skip the topo
		// machinery - every goal's prereqs are by definition already done, so
		// DAG ordering carries no information; chronological order is what users
		// actually want here. Tie-break by priority for stable rendering when
		// two goals were completed in the same millisecond (e.g. cascading
		// auto-completion in one VarbitChanged handler).
		Section section = api.goalStore.findSection(sectionId);
		if (section != null && section.getBuiltInKind() == Section.BuiltInKind.COMPLETED)
		{
			List<Goal> byDate = new ArrayList<>(sectionGoals);
			byDate.sort(java.util.Comparator
				.comparingLong(Goal::getCompletedAt).reversed()
				.thenComparingInt(Goal::getPriority));
			for (Goal g : byDate) out.add(toGoalView(g));
			return out;
		}

		sectionGoals.sort(java.util.Comparator.comparingInt(Goal::getPriority));

		java.util.Set<String> goalIds = new java.util.HashSet<>();
		for (Goal g : sectionGoals) goalIds.add(g.getId());

		// Stable local-repair sort.
		List<Goal> ordered = new ArrayList<>(sectionGoals);
		int maxIter = sectionGoals.size() * sectionGoals.size() + 1;
		int iter = 0;
		boolean converged = false;
		while (!converged && iter++ < maxIter)
		{
			converged = true;
			java.util.Map<String, Integer> pos = new java.util.HashMap<>();
			for (int i = 0; i < ordered.size(); i++) pos.put(ordered.get(i).getId(), i);

			for (int i = 0; i < ordered.size(); i++)
			{
				Goal g = ordered.get(i);
				// Collect both AND and OR edges for topo ordering
				List<String> allReqs = new ArrayList<>();
				if (g.getRequiredGoalIds() != null) allReqs.addAll(g.getRequiredGoalIds());
				if (g.getOrRequiredGoalIds() != null) allReqs.addAll(g.getOrRequiredGoalIds());
				if (allReqs.isEmpty()) continue;
				int maxReqPos = -1;
				for (String reqId : allReqs)
				{
					if (!goalIds.contains(reqId)) continue;
					Integer p = pos.get(reqId);
					if (p != null && p > maxReqPos) maxReqPos = p;
				}
				if (maxReqPos > i)
				{
					ordered.remove(i);
					ordered.add(maxReqPos, g);
					converged = false;
					break;
				}
			}
		}
		if (!converged)
		{
			log.warn("topoSortSection: local-repair hit iteration bound "
				+ "in section {} - graph may contain a cycle", sectionId);
		}

		// Post-sort: group OR-prereqs immediately before their parent.
		// For each goal with orRequiredGoalIds, extract those prereqs
		// from wherever they are in the list and re-insert them as a
		// contiguous block right before the parent.
		List<Goal> grouped = new ArrayList<>(ordered);
		for (int i = 0; i < grouped.size(); i++)
		{
			Goal parent = grouped.get(i);
			if (parent.getOrRequiredGoalIds() == null || parent.getOrRequiredGoalIds().isEmpty())
			{
				continue;
			}
			java.util.Set<String> orIds = new java.util.HashSet<>(parent.getOrRequiredGoalIds());
			// Collect OR-prereqs that are in this section
			List<Goal> orGroup = new ArrayList<>();
			for (int j = grouped.size() - 1; j >= 0; j--)
			{
				if (j == i) continue;
				if (orIds.contains(grouped.get(j).getId()))
				{
					orGroup.add(0, grouped.remove(j));
					if (j < i) i--; // adjust parent index
				}
			}
			if (!orGroup.isEmpty())
			{
				// Insert OR-prereqs right before the parent
				grouped.addAll(i, orGroup);
				i += orGroup.size(); // skip past the inserted group
			}
		}

		// User sections keep their completed goals inline (only the built-in
		// Incomplete/Completed default relocates on completion), so sink the
		// completed ones to the bottom - incomplete requirements first,
		// ticked-off below, like a checklist. Stable partition preserves the
		// topo/OR order within each group. (Built-in Completed is handled above.)
		if (section != null && !section.isBuiltIn())
		{
			List<Goal> incomplete = new ArrayList<>();
			List<Goal> done = new ArrayList<>();
			for (Goal g : grouped)
			{
				(g.isComplete() ? done : incomplete).add(g);
			}
			incomplete.addAll(done);
			grouped = incomplete;
		}

		for (Goal g : grouped)
		{
			out.add(toGoalView(g));
		}
		return out;
	}
}
