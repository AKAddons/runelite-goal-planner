package com.goalplanner.share;

import com.goalplanner.model.Goal;
import com.goalplanner.model.Tag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Export-side mapping: live {@link Goal}/{@link Tag} model objects →
 * {@link ShareBundle}. Pure - takes a tag-lookup {@link Function} rather than a
 * store, so it's unit-testable without the client.
 *
 * <p>Three responsibilities:
 * <ul>
 *   <li><b>Strip recipient-specific state.</b> Only definition fields are
 *       copied; {@code id}, {@code status}, {@code currentValue},
 *       {@code completedAt}, {@code createdAt}, {@code priority} and
 *       {@code sectionId} are left behind (the {@link GoalShareDto} has no
 *       slots for them) - imported goals track against the recipient's own
 *       account.</li>
 *   <li><b>Resolve tags.</b> A goal's {@code tagIds} are looked up and emitted
 *       as {label, category, colour} so the importer can find-or-create them;
 *       dangling tag refs are dropped.</li>
 *   <li><b>Rewrite relations.</b> {@code requiredGoalIds} /
 *       {@code orRequiredGoalIds} become bundle-local {@code ref} indices;
 *       edges pointing outside the shared set are dropped.</li>
 * </ul>
 */
public final class ShareMapper
{
	private ShareMapper()
	{
	}

	/**
	 * Build a shareable bundle from the goals being shared.
	 *
	 * @param kind            SECTION (carries a section name) or GOALS
	 * @param sectionName     section name for SECTION shares; null otherwise
	 * @param sectionColorRgb section colour override (0xRRGGBB; -1 = default)
	 * @param goals           the goals to share, in display order
	 * @param tagLookup       resolves a tag id to its {@link Tag} (or null)
	 * @param sharedBy        sharer's display name, for the import prompt
	 */
	public static ShareBundle toBundle(
		ShareBundle.Kind kind,
		String sectionName,
		int sectionColorRgb,
		List<Goal> goals,
		Function<String, Tag> tagLookup,
		String sharedBy)
	{
		ShareBundle bundle = new ShareBundle();
		bundle.setKind(kind);
		bundle.setSharedBy(sharedBy);
		bundle.setSectionName(sectionName);
		bundle.setSectionColorRgb(sectionColorRgb);
		bundle.setGoals(toDtos(goals, tagLookup));
		return bundle;
	}

	/**
	 * Build one v2 section entry. Relation refs are scoped to this section's
	 * goal list ({@code remap} skips out-of-section edges - the bundle-level
	 * {@link #crossEdges} pass carries those).
	 */
	public static SectionShareDto toSectionDto(
		String name,
		int colorRgb,
		boolean targetDefault,
		Boolean nestedOverride,
		List<Goal> goals,
		Function<String, Tag> tagLookup)
	{
		SectionShareDto dto = new SectionShareDto();
		dto.setName(name);
		dto.setColorRgb(colorRgb);
		dto.setTargetDefault(targetDefault);
		dto.setNestedOverride(nestedOverride);
		dto.setGoals(toDtos(goals, tagLookup));
		return dto;
	}

	/** Build a multi-section (v2) bundle from prepared section entries. */
	public static ShareBundle toMultiBundle(List<SectionShareDto> sections, String sharedBy)
	{
		ShareBundle bundle = new ShareBundle();
		bundle.setSharedBy(sharedBy);
		bundle.setSections(sections != null ? sections : new ArrayList<>());
		return bundle;
	}

	/**
	 * Build the cross-section dependency edges for a multi-section bundle:
	 * every requires/orRequires edge whose endpoints live in two DIFFERENT
	 * groups. {@code groups} must be the same goal lists, in the same order,
	 * that produced the bundle's section entries - refs are the per-section
	 * list indices {@link #toDtos} assigned. Edges to goals outside the
	 * bundle entirely are not representable and remain dropped.
	 */
	public static List<CrossEdgeDto> crossEdges(List<List<Goal>> groups)
	{
		// goal id → (section index, ref) across the whole bundle.
		Map<String, int[]> at = new HashMap<>();
		for (int s = 0; s < groups.size(); s++)
		{
			List<Goal> goals = groups.get(s);
			for (int r = 0; r < goals.size(); r++)
			{
				Goal g = goals.get(r);
				if (g != null && g.getId() != null)
				{
					at.put(g.getId(), new int[]{s, r});
				}
			}
		}
		List<CrossEdgeDto> out = new ArrayList<>();
		for (int s = 0; s < groups.size(); s++)
		{
			List<Goal> goals = groups.get(s);
			for (int r = 0; r < goals.size(); r++)
			{
				Goal g = goals.get(r);
				if (g == null)
				{
					continue;
				}
				addCrossEdges(out, at, s, r, g.getRequiredGoalIds(), false);
				addCrossEdges(out, at, s, r, g.getOrRequiredGoalIds(), true);
			}
		}
		return out;
	}

	private static void addCrossEdges(List<CrossEdgeDto> out, Map<String, int[]> at,
		int fromSection, int fromRef, List<String> targetIds, boolean or)
	{
		if (targetIds == null)
		{
			return;
		}
		for (String id : targetIds)
		{
			int[] target = at.get(id);
			if (target == null || target[0] == fromSection)
			{
				continue;   // outside the bundle, or section-local (carried on the dto)
			}
			CrossEdgeDto edge = new CrossEdgeDto();
			edge.setFromSection(fromSection);
			edge.setFromRef(fromRef);
			edge.setToSection(target[0]);
			edge.setToRef(target[1]);
			edge.setOr(or);
			out.add(edge);
		}
	}

	private static List<GoalShareDto> toDtos(List<Goal> goals, Function<String, Tag> tagLookup)
	{
		List<Goal> source = goals != null ? goals : List.of();

		// goal id → bundle-local ref index, for rewiring relations.
		Map<String, Integer> idToRef = new HashMap<>();
		for (int i = 0; i < source.size(); i++)
		{
			Goal g = source.get(i);
			if (g != null && g.getId() != null)
			{
				idToRef.put(g.getId(), i);
			}
		}

		List<GoalShareDto> dtos = new ArrayList<>();
		for (int i = 0; i < source.size(); i++)
		{
			Goal g = source.get(i);
			if (g == null)
			{
				continue;
			}
			GoalShareDto dto = new GoalShareDto();
			dto.setRef(i);
			dto.setType(g.getType() != null ? g.getType().name() : null);
			dto.setName(g.getName());
			dto.setDescription(g.getDescription());
			dto.setTargetValue(g.getTargetValue());
			dto.setSkillName(g.getSkillName());
			dto.setQuestName(g.getQuestName());
			dto.setAccountMetric(g.getAccountMetric());
			dto.setBossName(g.getBossName());
			dto.setVarbitId(g.getVarbitId());
			dto.setItemId(g.getItemId());
			dto.setSpriteId(g.getSpriteId());
			dto.setTooltip(g.getTooltip());
			dto.setCaTaskId(g.getCaTaskId());
			dto.setRepeatEvery(g.getRepeatEvery().name());
			dto.setRepeatChunk(g.getRepeatChunk());
			dto.setCustomColorRgb(g.getCustomColorRgb());
			dto.setOptional(g.isOptional());
			dto.setAutoSeeded(g.isAutoSeeded());
			dto.setWikiUrl(g.getWikiUrl());
			dto.setInventorySetup(g.getInventorySetup());
			dto.setTags(resolveTags(g.getTagIds(), tagLookup));
			dto.setRequires(remap(g.getRequiredGoalIds(), idToRef));
			dto.setOrRequires(remap(g.getOrRequiredGoalIds(), idToRef));
			dtos.add(dto);
		}

		return dtos;
	}

	private static List<TagShareDto> resolveTags(List<String> tagIds, Function<String, Tag> lookup)
	{
		List<TagShareDto> out = new ArrayList<>();
		if (tagIds == null || lookup == null)
		{
			return out;
		}
		for (String id : tagIds)
		{
			Tag tag = lookup.apply(id);
			if (tag == null)
			{
				continue;   // dangling tag ref - drop
			}
			TagShareDto t = new TagShareDto();
			t.setLabel(tag.getLabel());
			t.setCategory(tag.getCategory() != null ? tag.getCategory().name() : null);
			t.setColorRgb(tag.getColorRgb());
			t.setSystem(tag.isSystem());
			out.add(t);
		}
		return out;
	}

	private static List<Integer> remap(List<String> goalIds, Map<String, Integer> idToRef)
	{
		List<Integer> out = new ArrayList<>();
		if (goalIds == null)
		{
			return out;
		}
		for (String id : goalIds)
		{
			Integer ref = idToRef.get(id);
			if (ref != null)   // drop edges pointing outside the bundle
			{
				out.add(ref);
			}
		}
		return out;
	}
}
