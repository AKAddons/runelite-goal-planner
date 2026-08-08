package com.goalplanner.api;

import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.model.Section;
import com.goalplanner.model.Tag;
import com.goalplanner.model.TagCategory;
import com.goalplanner.persistence.GoalStore;
import com.goalplanner.share.GoalShareDto;
import com.goalplanner.share.SectionShareDto;
import com.goalplanner.share.ShareBundle;
import com.goalplanner.share.TagShareDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies a decoded {@link ShareBundle} (from a pasted share code, or
 * pasted as a share code) into the store: a new user section, fresh goals with
 * find-or-created tags and remapped relations, all in one undo-compound so a
 * single {@code undo()} reverses the whole import.
 *
 * <p>Imports land in their own user section: because user sections keep their
 * completed goals inline, the recipient sees the whole shared set as a checklist
 * against their own account - requirements they already meet show ticked off,
 * the rest show progress.
 *
 * <p>Inverse of {@code com.goalplanner.share.ShareMapper}. Because the payload
 * originates on another player's client it is treated as <b>untrusted</b>:
 * unknown {@link GoalType}/{@link TagCategory} values are skipped and string
 * fields are length-clamped, so a malformed or hostile bundle can't corrupt the
 * store.
 */
@Slf4j
class ShareImportService
{
	/** Defensive caps on attacker-controllable strings. */
	private static final int MAX_NAME = 120;
	private static final int MAX_TEXT = 600;

	private final GoalPlannerApiImpl api;

	ShareImportService(GoalPlannerApiImpl api)
	{
		this.api = api;
	}

	/**
	 * Import a bundle into a new user section.
	 *
	 * @return the id of the section the goals were imported into, or
	 *         {@code null} if there was nothing importable.
	 */
	String importBundle(ShareBundle bundle)
	{
		if (bundle == null)
		{
			return null;
		}
		// One canonical section list for the whole import - effectiveSections()
		// builds fresh wrapper instances per call for legacy bundles, and the
		// cross-edge pass needs stable bundle-position indices.
		final List<SectionShareDto> bundleSections = bundle.effectiveSections();
		final List<SectionShareDto> sections = new ArrayList<>();
		for (SectionShareDto s : bundleSections)
		{
			if (s != null && s.getGoals() != null && !s.getGoals().isEmpty())
			{
				sections.add(s);
			}
		}
		if (sections.isEmpty())
		{
			return null;
		}
		final String description = sections.size() == 1
			? "Import shared goals: " + displayName(sections.get(0))
			: "Import shared goals: " + sections.size() + " sections";

		// Wrap the whole import as ONE undoable command across every section.
		// Imported goals always start INCOMPLETE - buildGoal copies only
		// definition fields, never the sharer's progress/completion.
		final List<String> createdGoalIds = new ArrayList<>();
		final List<String> createdSectionIds = new ArrayList<>();
		// Relations added during import, tracked explicitly: default-target
		// sections REUSE existing goals, and a relation added between two reused
		// goals would otherwise survive an undo.
		final List<String[]> addedRequires = new ArrayList<>();
		final List<String[]> addedOrRequires = new ArrayList<>();
		final String[] landedSectionId = {null};

		// Batch persistence across the whole import: apply/revert/redo each flush
		// once instead of writing per goal+section. A large import's undo/redo
		// was issuing one config write per entity, the source of the lag.
		api.executeCommand(new com.goalplanner.command.SaveBatchingCommand(
			api.goalStore::suspendSave, api.goalStore::resumeSave,
			new com.goalplanner.command.Command()
		{
			@Override
			public boolean apply()
			{
				createdGoalIds.clear();
				createdSectionIds.clear();
				addedRequires.clear();
				addedOrRequires.clear();
				landedSectionId[0] = null;
				boolean importedAnything = false;
				// Per-section ref → created/reused goal id, keyed by the
				// section's position in the BUNDLE's section list - cross-edge
				// entries reference sections by that index.
				final Map<Integer, Map<Integer, String>> refMaps = new HashMap<>();

				for (SectionShareDto shared : sections)
				{
					final boolean reuseEquivalents = shared.isTargetDefault();
					final String targetSectionId;
					if (reuseEquivalents)
					{
						// Default plan: goals land in the Incomplete built-in and
						// existing equivalents are reused - the same dedup the
						// in-game Add Goal flow applies.
						targetSectionId = api.goalStore.getIncompleteSection().getId();
					}
					else
					{
						// A named import lands in its own NEW user section, forced
						// to KEEP COMPLETED INLINE so the recipient sees the shared
						// set as a checklist against their account.
						Section section = api.goalStore.createUserSection(
							importSectionName(shared));
						if (section == null)
						{
							log.warn("importBundle: createUserSection failed for '{}' - skipping section",
								shared.getName());
							continue;
						}
						if (shared.getColorRgb() >= 0)
						{
							section.setColorRgb(shared.getColorRgb());
						}
						// Carry the sharer's nested-view preference (TRUE/FALSE);
						// null leaves the recipient on their global default. Set
						// directly on the section (like colorRgb above) - the import
						// flow persists the section afterward.
						if (shared.getNestedOverride() != null)
						{
							section.setNestedOverride(shared.getNestedOverride());
						}
						api.goalStore.setSectionAutoArchiveOverride(section.getId(), false);
						createdSectionIds.add(section.getId());
						targetSectionId = section.getId();
					}
					if (landedSectionId[0] == null)
					{
						landedSectionId[0] = targetSectionId;
					}

					// First pass: create (or, in default mode, reuse) goals.
					// Relation refs are SECTION-scoped in v2.
					Map<Integer, String> refToId = new HashMap<>();
					for (GoalShareDto dto : shared.getGoals())
					{
						GoalType type = parseType(dto.getType());
						if (type == null)
						{
							log.warn("importBundle: skipping goal with unknown type '{}'", dto.getType());
							continue;
						}
						Goal goal = buildGoal(dto, type, reuseEquivalents ? null : targetSectionId);
						if (reuseEquivalents)
						{
							Goal existing = api.goalStore.findEquivalentInNamespace(targetSectionId, goal);
							if (existing != null)
							{
								refToId.put(dto.getRef(), existing.getId());
								importedAnything = true;
								continue;
							}
						}
						api.goalStore.addGoal(goal);   // null sectionId → Incomplete built-in
						createdGoalIds.add(goal.getId());
						refToId.put(dto.getRef(), goal.getId());
						importedAnything = true;
					}

					// Second pass: rewire relations now that every ref has an id.
					for (GoalShareDto dto : shared.getGoals())
					{
						String fromId = refToId.get(dto.getRef());
						if (fromId == null)
						{
							continue;
						}
						for (Integer ref : dto.getRequires())
						{
							String toId = refToId.get(ref);
							if (toId != null && api.goalStore.addRequirement(fromId, toId))
							{
								addedRequires.add(new String[]{fromId, toId});
							}
						}
						for (Integer ref : dto.getOrRequires())
						{
							String toId = refToId.get(ref);
							if (toId != null && api.goalStore.addOrRequirement(fromId, toId))
							{
								addedOrRequires.add(new String[]{fromId, toId});
							}
						}
					}
					refMaps.put(bundleSections.indexOf(shared), refToId);
				}

				// Third pass: cross-section edges - (section index, ref) pairs
				// resolved through the per-section maps. Unresolvable entries
				// (skipped goal/section, malformed indices) are dropped.
				if (bundle.getCrossEdges() != null)
				{
					for (com.goalplanner.share.CrossEdgeDto edge : bundle.getCrossEdges())
					{
						if (edge == null)
						{
							continue;
						}
						Map<Integer, String> fromMap = refMaps.get(edge.getFromSection());
						Map<Integer, String> toMap = refMaps.get(edge.getToSection());
						String fromId = fromMap != null ? fromMap.get(edge.getFromRef()) : null;
						String toId = toMap != null ? toMap.get(edge.getToRef()) : null;
						if (fromId == null || toId == null)
						{
							continue;
						}
						if (edge.isOr())
						{
							if (api.goalStore.addOrRequirement(fromId, toId))
							{
								addedOrRequires.add(new String[]{fromId, toId});
							}
						}
						else if (api.goalStore.addRequirement(fromId, toId))
						{
							addedRequires.add(new String[]{fromId, toId});
						}
					}
				}
				return importedAnything;
			}

			@Override
			public boolean revert()
			{
				// Relations first - created goals take theirs with them, but a
				// relation between two REUSED goals must be removed explicitly.
				for (String[] rel : addedRequires)
				{
					api.goalStore.removeRequirement(rel[0], rel[1]);
				}
				for (String[] rel : addedOrRequires)
				{
					api.goalStore.removeOrRequirement(rel[0], rel[1]);
				}
				for (String goalId : createdGoalIds)
				{
					api.goalStore.removeGoal(goalId);
				}
				for (String sectionId : createdSectionIds)
				{
					api.goalStore.deleteUserSection(sectionId);
				}
				return true;
			}

			@Override
			public String getDescription()
			{
				return description;
			}
		}));

		return landedSectionId[0];
	}

	private static String displayName(SectionShareDto shared)
	{
		if (shared.isTargetDefault())
		{
			return "Default";
		}
		return notBlank(shared.getName()) ? shared.getName().trim() : "Shared goals";
	}

	private Goal buildGoal(GoalShareDto dto, GoalType type, String sectionId)
	{
		// Enrich the icon fields the share wire doesn't carry, mirroring in-game
		// creation: QUEST/DIARY goals get the journal sprite, BOSS goals get their
		// pet item id - otherwise the card falls back to a plain colour dot.
		int spriteId = dto.getSpriteId();
		int itemId = dto.getItemId();
		if (spriteId <= 0)
		{
			if (type == GoalType.QUEST) spriteId = GoalPlannerApiImpl.QUEST_SPRITE_ID;
			else if (type == GoalType.DIARY) spriteId = com.goalplanner.data.AchievementDiaryData.DIARY_SPRITE_ID;
		}
		if (itemId <= 0 && type == GoalType.BOSS && notBlank(dto.getBossName()))
		{
			itemId = com.goalplanner.data.BossKillData.getPetItemId(dto.getBossName().trim());
		}
		Goal goal = Goal.builder()
			.type(type)
			.name(clamp(dto.getName(), MAX_NAME))
			.description(clamp(dto.getDescription(), MAX_TEXT))
			// A repeatable chunk goal's target is the SENDER's progress plus the
			// chunk - meaningless here. Import it as 0 ("not yet sized") and let
			// the first tracker read size it against the recipient's own progress
			// (see GoalMutationService.recordGoalProgress). Sending 9,810,000 to
			// someone with 2M Prayer XP would ask them for 7.8M in a day while the
			// card displayed a tidy "0 / 10K".
			.targetValue(dto.getRepeatChunk() > 0 ? 0 : dto.getTargetValue())
			.skillName(dto.getSkillName())
			.questName(dto.getQuestName())
			.accountMetric(dto.getAccountMetric())
			.bossName(clamp(dto.getBossName(), MAX_NAME))
			.varbitId(dto.getVarbitId())
			.itemId(itemId)
			.spriteId(spriteId)
			.tooltip(clamp(dto.getTooltip(), MAX_TEXT))
			.caTaskId(dto.getCaTaskId())
			.repeatEvery(parseRepeatPeriod(dto.getRepeatEvery()))
			.repeatChunk(Math.max(0, dto.getRepeatChunk()))
			.customColorRgb(dto.getCustomColorRgb())
			.optional(dto.isOptional())
			.autoSeeded(dto.isAutoSeeded())
			.wikiUrl(clamp(dto.getWikiUrl(), MAX_TEXT))
			.inventorySetup(clamp(dto.getInventorySetup(), MAX_NAME))
			.sectionId(sectionId)
			.build();
		goal.setTagIds(resolveTags(dto.getTags()));
		return goal;
	}

	private List<String> resolveTags(List<TagShareDto> tags)
	{
		List<String> ids = new ArrayList<>();
		if (tags == null)
		{
			return ids;
		}
		for (TagShareDto spec : tags)
		{
			String label = clamp(spec.getLabel(), MAX_NAME);
			TagCategory category = parseCategory(spec.getCategory());
			if (label == null || label.isEmpty() || category == null)
			{
				continue;   // unusable tag - skip
			}
			Tag tag = spec.isSystem()
				? api.goalStore.findOrCreateSystemTag(label, category)
				: findOrCreateUserTag(label, category);
			if (tag == null)
			{
				continue;
			}
			// Only recolour user tags - system-tag colours are shared across the
			// recipient's goals and shouldn't be mutated by an import.
			if (!spec.isSystem() && spec.getColorRgb() != -1)
			{
				api.goalStore.recolorTag(tag.getId(), spec.getColorRgb());
			}
			ids.add(tag.getId());
		}
		return ids;
	}

	private Tag findOrCreateUserTag(String label, TagCategory category)
	{
		Tag existing = api.goalStore.findTagByLabel(label, category);
		return existing != null ? existing : api.goalStore.createUserTag(label, category);
	}

	private String importSectionName(SectionShareDto shared)
	{
		// Use the shared section's own name verbatim - no "(from <sharer>)" suffix.
		// That suffix ate horizontal space in the panel; sharer attribution isn't
		// worth a section's limited width. Clamp as a backstop against the cap
		// (createUserSection THROWS on over-length names, which CommandHistory
		// swallows - silently failing the whole import).
		String base = notBlank(shared.getName()) ? shared.getName().trim() : "Shared goals";
		return clamp(base, GoalStore.MAX_SECTION_NAME_LENGTH);
	}

	private static GoalType parseType(String name)
	{
		if (name == null)
		{
			return null;
		}
		try
		{
			return GoalType.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	private static TagCategory parseCategory(String name)
	{
		if (name == null)
		{
			return null;
		}
		try
		{
			return TagCategory.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	private static boolean notBlank(String s)
	{
		return s != null && !s.trim().isEmpty();
	}

	private static String clamp(String s, int max)
	{
		if (s == null)
		{
			return null;
		}
		String t = s.trim();
		return t.length() <= max ? t : t.substring(0, max);
	}

	/**
	 * Read a shared repeat period. Anything unrecognised - absent (an older
	 * sender), misspelled, or a period a future version adds - imports as a
	 * plain one-shot rather than failing the whole bundle. A share code is
	 * untrusted input from another client.
	 */
	private static com.goalplanner.model.RepeatPeriod parseRepeatPeriod(String raw)
	{
		if (raw == null || raw.trim().isEmpty())
		{
			return com.goalplanner.model.RepeatPeriod.NONE;
		}
		try
		{
			return com.goalplanner.model.RepeatPeriod.valueOf(raw.trim());
		}
		catch (IllegalArgumentException e)
		{
			log.warn("share import: unknown repeat period {} - importing as one-shot", raw);
			return com.goalplanner.model.RepeatPeriod.NONE;
		}
	}
}
