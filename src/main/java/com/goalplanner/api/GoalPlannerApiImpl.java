package com.goalplanner.api;

import com.goalplanner.data.WikiCaRepository;
import com.goalplanner.model.Goal;
import com.goalplanner.persistence.GoalStore;
import com.goalplanner.service.GoalReorderingService;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;

/**
 * Default implementation of {@link GoalPlannerApi}. Bound to the public
 * interface in {@link com.goalplanner.GoalPlannerPlugin#configure}.
 *
 * <p>This is a thin facade: every method delegates to a focused service class.
 * Only undo/redo, selection, and infrastructure (constructors, findGoal,
 * executeCommand) live here.
 */
@Slf4j
@Singleton
public class GoalPlannerApiImpl implements GoalPlannerApi, GoalPlannerInternalApi
{
	static final int QUEST_SPRITE_ID = 899;
	static final int F2P_TAG_COLOR_RGB = (160 << 16) | (160 << 8) | 160;
	static final String LAMP_ICON_KEY = "lamp";

	final GoalStore goalStore;
	final GoalReorderingService reorderingService;
	final ItemManager itemManager;
	final WikiCaRepository wikiCaRepository;
	/** Used by {@link RelationService#resolveQuestRequirements} to filter requirements
	 *  against live player state. May be null in tests that don't touch
	 *  that method. */
	final net.runelite.api.Client client;

	/** Optional UI-refresh hook the plugin sets after the panel is constructed. */
	Runnable onGoalsChanged = () -> {};
	/** Lightweight selection-only refresh - avoids full rebuild for selection changes. */
	Runnable onSelectionChanged = () -> {};

	/** Ephemeral selection set - not persisted, lost on plugin restart. */
	final java.util.Set<String> selectedGoalIds = new java.util.LinkedHashSet<>();

	/** Undo/redo history. Session-only. Tracker-driven mutations
	 *  bypass this - only user actions routed through {@link #executeCommand}
	 *  appear in history. */
	private final com.goalplanner.command.CommandHistory commandHistory =
		new com.goalplanner.command.CommandHistory();

	private final GoalCreationService creationService;
	// Blocked-prereqs indicator: goalId -> labels of unmet, not-in-plan DIRECT
	// prerequisites. Recomputed on the client thread (reads live state) and read
	// by the panel when building views. Replaced atomically; never mutated in place.
	private volatile java.util.Map<String, java.util.List<String>> blockedByGoalId =
		java.util.Collections.emptyMap();
	final TagService tagService;
	private final GoalMutationService mutationService;
	private final GoalQueryService queryService;
	final SectionService sectionService;
	private final RelationService relationService;
	private final ShareImportService shareImportService;
	private final ShareExportService shareExportService;

	@Inject
	public GoalPlannerApiImpl(
		GoalStore goalStore,
		GoalReorderingService reorderingService,
		ItemManager itemManager,
		WikiCaRepository wikiCaRepository,
		net.runelite.api.Client client)
	{
		this.goalStore = goalStore;
		this.reorderingService = reorderingService;
		this.itemManager = itemManager;
		this.wikiCaRepository = wikiCaRepository;
		this.client = client;
		this.creationService = new GoalCreationService(this);
		this.tagService = new TagService(this);
		this.mutationService = new GoalMutationService(this);
		this.queryService = new GoalQueryService(this);
		this.sectionService = new SectionService(this);
		this.relationService = new RelationService(this);
		this.shareImportService = new ShareImportService(this);
		this.shareExportService = new ShareExportService(this);
	}

	/**
	 * Test-friendly constructor that omits the {@link net.runelite.api.Client}.
	 */
	public GoalPlannerApiImpl(
		GoalStore goalStore,
		GoalReorderingService reorderingService,
		ItemManager itemManager,
		WikiCaRepository wikiCaRepository)
	{
		this(goalStore, reorderingService, itemManager, wikiCaRepository, null);
	}

	/**
	 * Plugin-internal hook for the UI to register a refresh callback. Not part
	 * of the public API; called by GoalPlannerPlugin during startup.
	 */
	public void setOnGoalsChanged(Runnable callback)
	{
		this.onGoalsChanged = callback != null ? callback : () -> {};
	}

	public void setOnSelectionChanged(Runnable callback)
	{
		this.onSelectionChanged = callback != null ? callback : () -> {};
	}

	/**
	 * Drop per-session selection and undo/redo state. Called from the plugin's
	 * shutDown() so this {@code @Singleton} doesn't carry a previous session's
	 * selected ids or command history into the next enable.
	 */
	public void clearSelectionState()
	{
		selectedGoalIds.clear();
		commandHistory.clear();
	}

	// =====================================================================
	// Goal creation delegations
	// =====================================================================

	@Override public String addSkillGoal(Skill skill, int targetXp) { String id = creationService.addSkillGoal(skill, targetXp); selectAfterCreate(id); return id; }
	@Override public String addSkillGoalForLevel(Skill skill, int level) { String id = creationService.addSkillGoalForLevel(skill, level); selectAfterCreate(id); return id; }
	@Override public String addItemGoal(int itemId, int targetQuantity) { String id = creationService.addItemGoal(itemId, targetQuantity); selectAfterCreate(id); return id; }
	@Override public String addQuestGoal(Quest quest) { String id = creationService.addQuestGoal(quest); selectAfterCreate(id); return id; }
	// Not part of the published GoalPlannerApi - the public addQuestGoal / addDiaryGoal
	// now auto-resolve prereqs internally. Kept public on the impl for tests and
	// internal callers that want to supply pre-computed templates directly.
	public String addQuestGoalWithPrereqs(Quest quest, java.util.List<Goal> prereqTemplates) { return creationService.addQuestGoalWithPrereqs(quest, prereqTemplates); }
	@Override public String addDiaryGoal(String areaDisplayName, DiaryTier tier) { String id = creationService.addDiaryGoal(areaDisplayName, tier); selectAfterCreate(id); return id; }
	// Bare diary add: insert the diary goal ONLY, no prereq auto-seeding. Used by the
	// in-game diary menu (organising/seeding is left to the panel's "Add requirements
	// to this section"). Mirrors the bare-add intent of addQuestGoalWithPrereqs(..., []).
	public String addDiaryGoalNoPrereqs(String areaDisplayName, DiaryTier tier) { String id = creationService.insertDiaryGoal(areaDisplayName, tier); selectAfterCreate(id); return id; }
	public String addDiaryGoalWithPrereqs(String areaDisplayName, DiaryTier tier, com.goalplanner.data.DiaryRequirementResolver.Resolved resolved) { return creationService.addDiaryGoalWithPrereqs(areaDisplayName, tier, resolved); }
	// Panel "Add requirements to this section" for an existing DIARY goal (parallel
	// to seedRequirementsForGoal for quests).
	public int seedDiaryRequirementsForGoal(String diaryGoalId, boolean includeMet) { return creationService.seedDiaryRequirementsForGoal(diaryGoalId, includeMet); }
	// Panel "Add requirements to this section" for an existing BOSS goal.
	public int seedBossRequirementsForGoal(String bossGoalId, boolean includeMet) { return creationService.seedBossRequirementsForGoal(bossGoalId, includeMet); }

	/** Cached missing-direct-prereq labels for a goal (empty if none / not yet computed). */
	public java.util.List<String> blockedRequirements(String goalId)
	{
		return blockedByGoalId.getOrDefault(goalId, java.util.List.of());
	}

	/**
	 * Recompute the blocked-prereqs map for active quest/diary/boss goals.
	 * CLIENT-THREAD ONLY (reads live skill/quest/account state). Returns true if
	 * the map changed, so the caller can refresh the panel; a no-op for plans
	 * with no incomplete requirement-bearing goals.
	 */
	public boolean recomputeBlockedRequirements()
	{
		java.util.Map<String, java.util.List<String>> next = new java.util.HashMap<>();
		for (Goal g : goalStore.getGoals())
		{
			if (g.isComplete()) continue;
			com.goalplanner.model.GoalType t = g.getType();
			if (t != com.goalplanner.model.GoalType.QUEST
				&& t != com.goalplanner.model.GoalType.DIARY
				&& t != com.goalplanner.model.GoalType.BOSS)
			{
				continue;
			}
			java.util.List<String> missing = creationService.missingDirectPrereqLabels(g);
			if (!missing.isEmpty())
			{
				next.put(g.getId(), missing);
			}
		}
		if (next.equals(blockedByGoalId))
		{
			return false;
		}
		blockedByGoalId = next;
		return true;
	}
	@Override public String addCombatAchievementGoal(int caTaskId) { String id = creationService.addCombatAchievementGoal(caTaskId); selectAfterCreate(id); return id; }
	@Override public String addBossGoal(String bossName, int targetKills) { String id = creationService.addBossGoal(bossName, targetKills); selectAfterCreate(id); return id; }
	@Override public String addAccountGoal(String metricName, int target) { String id = creationService.addAccountGoal(metricName, target); selectAfterCreate(id); return id; }
	public String addCustomGoal(String name, String description) { return creationService.addCustomGoal(name, description); }

	/**
	 * Import a shared bundle (from a pasted share code or the cross-plugin API)
	 * into a new section. Not part of the published API - plugin-internal.
	 * Returns the new section id, or null if nothing was importable.
	 */
	public String importShareBundle(com.goalplanner.share.ShareBundle bundle) { return shareImportService.importBundle(bundle); }

	/** Build a share bundle for a whole section (plugin-internal). */
	public com.goalplanner.share.ShareBundle exportSectionBundle(String sectionId, String sharedBy) { return shareExportService.exportSection(sectionId, sharedBy); }
	public com.goalplanner.share.ShareBundle exportAllSectionsBundle(String sharedBy) { return shareExportService.exportAllUserSections(sharedBy); }

	/** Build a share bundle for the given goal ids (plugin-internal). */
	public com.goalplanner.share.ShareBundle exportGoalsBundle(java.util.List<String> goalIds, String sharedBy) { return shareExportService.exportGoals(goalIds, sharedBy); }

	// =====================================================================
	// Query delegations
	// =====================================================================

	@Override public List<GoalView> queryAllGoals() { return queryService.queryAllGoals(); }
	public GoalView queryGoalView(String goalId) { return queryService.queryGoalView(goalId); }
	@Override public List<GoalView> searchGoals(String query) { return queryService.searchGoals(query); }
	@Override public List<SectionView> queryAllSections() { return queryService.queryAllSections(); }
	@Override public java.util.List<GoalView> queryGoalsTopologicallySorted(String sectionId) { return queryService.queryGoalsTopologicallySorted(sectionId); }
	@Override public java.util.Map<String, java.util.List<GoalView>> queryAllGoalsTopologicallySorted() { return queryService.queryAllGoalsTopologicallySorted(); }

	// =====================================================================
	// Mutation delegations
	// =====================================================================

	@Override public boolean removeGoal(String goalId) { autoDeselectIfNotMember(goalId); return mutationService.removeGoal(goalId); }
	@Override public boolean editCustomGoal(String goalId, String newName, String newDescription) { autoDeselectIfNotMember(goalId); return mutationService.editCustomGoal(goalId, newName, newDescription); }
	@Override public boolean markGoalComplete(String goalId) { autoDeselectIfNotMember(goalId); return mutationService.markGoalComplete(goalId); }
	@Override public boolean markGoalIncomplete(String goalId) { autoDeselectIfNotMember(goalId); return mutationService.markGoalIncomplete(goalId); }
	@Override public boolean setGoalRepeat(String goalId, com.goalplanner.model.RepeatPeriod period) { return mutationService.setGoalRepeat(goalId, period); }
	@Override public String createDerivedRepeatGoal(String parentGoalId, com.goalplanner.model.RepeatPeriod period, int chunk, String activityName) { return creationService.createDerivedRepeatGoal(parentGoalId, period, chunk, activityName); }
	@Override public boolean setGoalRepeatChunk(String goalId, int newChunk) { return mutationService.setGoalRepeatChunk(goalId, newChunk); }
	@Override public boolean changeTarget(String goalId, int newTarget) { autoDeselectIfNotMember(goalId); return mutationService.changeTarget(goalId, newTarget); }
	@Override public boolean recordGoalProgress(String goalId, int newValue) { return mutationService.recordGoalProgress(goalId, newValue); }
	@Override public boolean isGoalOverridden(String goalId) { return mutationService.isGoalOverridden(goalId); }
	@Override public int bulkRestoreDefaults(java.util.Set<String> goalIds) { return mutationService.bulkRestoreDefaults(goalIds); }
	@Override public int bulkRemoveGoals(java.util.Set<String> goalIds) { return mutationService.bulkRemoveGoals(goalIds); }
	@Override public int bulkMoveGoalsToSection(java.util.Set<String> goalIds, String targetSectionId) { return mutationService.bulkMoveGoalsToSection(goalIds, targetSectionId); }
	@Override public void removeAllGoals() { mutationService.removeAllGoals(); }
	// Not part of the published GoalPlannerApi - UI "Remove duplicate goals" cleanup.
	public int removeDuplicateGoals() { return mutationService.removeDuplicateGoals(); }
	@Override public boolean moveGoal(String goalId, int newGlobalIndex) { autoDeselectIfNotMember(goalId); return mutationService.moveGoal(goalId, newGlobalIndex); }
	@Override public boolean positionGoalInSection(String goalId, String sectionId, int positionInSection) { autoDeselectIfNotMember(goalId); return mutationService.positionGoalInSection(goalId, sectionId, positionInSection); }
	@Override public boolean setGoalOptional(String goalId, boolean optional) { autoDeselectIfNotMember(goalId); return mutationService.setGoalOptional(goalId, optional); }

	// =====================================================================
	// Tag delegations
	// =====================================================================

	@Override public boolean addTag(String goalId, String label) { autoDeselectIfNotMember(goalId); return tagService.addTag(goalId, label); }
	@Override public boolean removeTag(String goalId, String label) { autoDeselectIfNotMember(goalId); return tagService.removeTag(goalId, label); }
	@Override public boolean restoreDefaultTags(String goalId) { autoDeselectIfNotMember(goalId); return tagService.restoreDefaultTags(goalId); }
	@Override public boolean addTagWithCategory(String goalId, String label, String categoryName) { autoDeselectIfNotMember(goalId); return tagService.addTagWithCategory(goalId, label, categoryName); }
	@Override public List<TagView> queryAllTags() { return tagService.queryAllTags(); }
	@Override public String createUserTag(String label, String categoryName) { return tagService.createUserTag(label, categoryName); }
	@Override public boolean renameTag(String tagId, String newLabel) { return tagService.renameTag(tagId, newLabel); }
	@Override public boolean recolorTag(String tagId, int colorRgb) { return tagService.recolorTag(tagId, colorRgb); }
	@Override public boolean deleteTag(String tagId) { return tagService.deleteTag(tagId); }
	@Override public boolean setCategoryColor(String categoryName, int colorRgb) { return tagService.setCategoryColor(categoryName, colorRgb); }
	@Override public boolean resetCategoryColor(String categoryName) { return tagService.resetCategoryColor(categoryName); }
	@Override public int getCategoryColor(String categoryName) { return tagService.getCategoryColor(categoryName); }
	@Override public int getCategoryDefaultColor(String categoryName) { return tagService.getCategoryDefaultColor(categoryName); }
	@Override public boolean isCategoryColorOverridden(String categoryName) { return tagService.isCategoryColorOverridden(categoryName); }
	@Override public boolean setTagIcon(String tagId, String iconKey) { return tagService.setTagIcon(tagId, iconKey); }
	@Override public boolean clearTagIcon(String tagId) { return tagService.clearTagIcon(tagId); }
	@Override public boolean setTagColor(String goalId, String tagLabel, int colorRgb) { autoDeselectIfNotMember(goalId); return tagService.setTagColor(goalId, tagLabel, colorRgb); }
	@Override public int bulkRemoveTagFromGoals(java.util.Set<String> goalIds, String tagId) { return tagService.bulkRemoveTagFromGoals(goalIds, tagId); }
	@Override public List<TagRemovalOption> getRemovableTagsForSelection(java.util.Set<String> goalIds) { return tagService.getRemovableTagsForSelection(goalIds); }

	// =====================================================================
	// Section delegations
	// =====================================================================

	@Override public String createSection(String name) { return sectionService.createSection(name); }
	@Override public boolean renameSection(String sectionId, String newName) { return sectionService.renameSection(sectionId, newName); }
	@Override public boolean deleteSection(String sectionId) { return sectionService.deleteSection(sectionId, false); }
	@Override public boolean deleteSection(String sectionId, boolean moveGoalsToDefault) { return sectionService.deleteSection(sectionId, moveGoalsToDefault); }
	@Override public boolean reorderSection(String sectionId, int newUserIndex) { return sectionService.reorderSection(sectionId, newUserIndex); }
	@Override public boolean moveGoalToSection(String goalId, String sectionId) { autoDeselectIfNotMember(goalId); return sectionService.moveGoalToSection(goalId, sectionId); }
	// Not part of the published GoalPlannerApi - UI duplicate-to-section action.
	public java.util.List<String> duplicateGoalsToSection(java.util.Collection<String> goalIds, String targetSectionId) { return creationService.duplicateGoalsToSection(goalIds, targetSectionId); }
	// Not part of the published GoalPlannerApi - UI "Move to Default" action.
	public int moveGoalsToDefault(java.util.Collection<String> goalIds) { return mutationService.moveGoalsToDefault(goalIds); }
	@Override public int removeAllUserSections() { return sectionService.removeAllUserSections(); }
	@Override public int removeEmptyUserSections() { return sectionService.removeEmptyUserSections(); }

	@Override public void removeAllGoalsAndSections()
	{
		// Complete wipe: every user section AND every goal regardless of status
		// (completed included). Built-in Incomplete/Completed sections remain
		// (empty). One compound so the whole reset is a single undo.
		//
		// Order matters for undo fidelity: removeAllUserSections runs FIRST, while
		// goals still carry their user-section ids, so it captures the original
		// section->goals mapping for its revert. It re-homes those goals to
		// Incomplete; we then wipe every remaining goal. On undo (reverse order)
		// the goals are restored first, then removeAllUserSections' revert
		// recreates the sections and moves each goal back to where it started.
		beginCompound("Delete all goals and sections");
		try
		{
			removeAllUserSections();
			for (Goal g : goalStore.getGoals())
			{
				removeGoal(g.getId());
			}
		}
		finally
		{
			endCompound();
		}
	}
	@Override public boolean setSectionCollapsed(String sectionId, boolean collapsed) { return sectionService.setSectionCollapsed(sectionId, collapsed); }
	@Override public boolean toggleSectionCollapsed(String sectionId) { return sectionService.toggleSectionCollapsed(sectionId); }
	@Override public boolean toggleGoalNestCollapsed(String goalId) { return goalStore.toggleNestCollapsed(goalId) != null; }
	@Override public boolean setSectionNestedOverride(String sectionId, Boolean value) { return sectionService.setSectionNestedOverride(sectionId, value); }
	@Override public int seedRequirementsForGoal(String goalId, boolean includeMet) { return creationService.seedRequirementsForGoal(goalId, includeMet); }
	@Override public boolean setSectionColor(String sectionId, int colorRgb) { return sectionService.setSectionColor(sectionId, colorRgb); }
	@Override public boolean setSectionAutoArchiveOverride(String sectionId, Boolean value) { return sectionService.setSectionAutoArchiveOverride(sectionId, value); }
	@Override public boolean isAutoArchiveDefault() { return goalStore.isAutoArchiveDefault(); }
	@Override public void setAutoArchiveDefault(boolean value) { goalStore.setAutoArchiveDefault(value); }
	@Override public boolean setGoalColor(String goalId, int colorRgb) { autoDeselectIfNotMember(goalId); return sectionService.setGoalColor(goalId, colorRgb); }

	// =====================================================================
	// Relation delegations
	// =====================================================================

	@Override public boolean addRequirement(String fromGoalId, String toGoalId) { return relationService.addRequirement(fromGoalId, toGoalId); }
	@Override public boolean removeRequirement(String fromGoalId, String toGoalId) { return relationService.removeRequirement(fromGoalId, toGoalId); }
	public boolean addOrRequirement(String fromGoalId, String toGoalId) { return relationService.addOrRequirement(fromGoalId, toGoalId); }
	@Override public java.util.List<String> getRequirements(String goalId) { return relationService.getRequirements(goalId); }
	@Override public java.util.List<String> getDependents(String goalId) { return relationService.getDependents(goalId); }
	@Override public FindOrCreateResult findOrCreateRequirement(Goal template, String preferredSectionId) { return relationService.findOrCreateRequirement(template, preferredSectionId); }
	@Override public com.goalplanner.data.QuestRequirementResolver.Resolved resolveQuestRequirements(Quest quest) { return relationService.resolveQuestRequirements(quest); }

	// =====================================================================
	// Undo / redo
	// =====================================================================

	/**
	 * Internal entry point for user-mutation API methods. Runs the command
	 * via {@link com.goalplanner.command.CommandHistory#execute} so it lands on the undo stack.
	 */
	public boolean executeCommand(com.goalplanner.command.Command cmd)
	{
		boolean ok = commandHistory.execute(cmd);
		if (ok) fireIfNotInCompound();
		return ok;
	}

	/**
	 * Fire the UI refresh callback unless we're inside a compound
	 * transaction. All mutation paths should use this instead of calling
	 * onGoalsChanged.run() directly to avoid per-sub-command rebuilds.
	 */
	void fireIfNotInCompound()
	{
		if (!commandHistory.isInCompound()) pruneSelectionThenFireGoalsChanged();
	}

	/** Fire the UI refresh callback (goes through the debounce timer). */
	public void fireGoalsChanged()
	{
		pruneSelectionThenFireGoalsChanged();
	}

	/**
	 * Drop selected ids whose goal no longer exists, then fire the goals-changed
	 * refresh (and a selection-changed refresh if the prune removed anything).
	 * The seed/import gestures select the goals they create directly on
	 * {@link #selectedGoalIds}; undoing one removes those goals from the store
	 * but not the selection, leaving a stale "N selected" count of ids that no
	 * longer exist. Reconciling here keeps the selection honest after any
	 * goal-set change (undo, redo, removal).
	 */
	private void pruneSelectionThenFireGoalsChanged()
	{
		boolean selectionChanged = selectedGoalIds.removeIf(id -> goalStore.findGoalById(id) == null);
		onGoalsChanged.run();
		if (selectionChanged) onSelectionChanged.run();
	}

	@Override public boolean canUndo() { return commandHistory.canUndo(); }
	@Override public boolean canRedo() { return commandHistory.canRedo(); }
	@Override public String peekUndoDescription() { return commandHistory.peekUndoDescription(); }
	@Override public String peekRedoDescription() { return commandHistory.peekRedoDescription(); }

	@Override
	public boolean undo()
	{
		goalStore.suspendSave();
		boolean ok = commandHistory.undo();
		goalStore.resumeSave();
		if (ok) pruneSelectionThenFireGoalsChanged();
		return ok;
	}

	@Override
	public boolean redo()
	{
		goalStore.suspendSave();
		boolean ok = commandHistory.redo();
		goalStore.resumeSave();
		if (ok) pruneSelectionThenFireGoalsChanged();
		return ok;
	}

	@Override public void beginCompound(String description)
	{
		commandHistory.beginCompound(description);
		goalStore.suspendSave();
	}
	@Override public void endCompound()
	{
		commandHistory.endCompound();
		if (!commandHistory.isInCompound())
		{
			goalStore.resumeSave();
			pruneSelectionThenFireGoalsChanged();
		}
	}

	// =====================================================================
	// Selection (Phase 5) - ephemeral, not persisted
	// =====================================================================

	@Override
	public boolean replaceGoalSelection(java.util.Collection<String> goalIds)
	{
		log.debug("API.internal replaceGoalSelection(size={})", goalIds == null ? 0 : goalIds.size());
		java.util.Set<String> next = new java.util.LinkedHashSet<>();
		if (goalIds != null) next.addAll(goalIds);
		if (next.equals(selectedGoalIds)) return false;
		selectedGoalIds.clear();
		selectedGoalIds.addAll(next);
		if (!commandHistory.isInCompound()) onSelectionChanged.run();
		return true;
	}

	@Override
	public boolean addToGoalSelection(String goalId)
	{
		log.debug("API.internal addToGoalSelection(goalId={})", goalId);
		if (goalId == null) return false;
		if (!selectedGoalIds.add(goalId)) return false;
		if (!commandHistory.isInCompound()) onSelectionChanged.run();
		return true;
	}

	@Override
	public boolean removeFromGoalSelection(String goalId)
	{
		log.debug("API.internal removeFromGoalSelection(goalId={})", goalId);
		if (goalId == null) return false;
		if (!selectedGoalIds.remove(goalId)) return false;
		if (!commandHistory.isInCompound()) onSelectionChanged.run();
		return true;
	}

	@Override
	public boolean clearGoalSelection()
	{
		log.debug("API.internal clearGoalSelection()");
		if (selectedGoalIds.isEmpty()) return false;
		selectedGoalIds.clear();
		if (!commandHistory.isInCompound()) onSelectionChanged.run();
		return true;
	}

	/**
	 * Auto-deselect rule: any single-target mutation on a goal that isn't
	 * a member of the active multi-selection clears the selection first.
	 * Keeps the user's "active set" coherent - acting on something outside
	 * the current selection invalidates the prior selection. Called at the
	 * top of every public single-target mutation. Skipped for: bulk
	 * methods (Set<id>), two-id relation methods, recordGoalProgress
	 * (auto-tracked from game events, not user-initiated), and selection
	 * methods themselves.
	 */
	private void autoDeselectIfNotMember(String goalId)
	{
		if (goalId == null) return;
		if (selectedGoalIds.isEmpty()) return;
		if (selectedGoalIds.contains(goalId)) return;
		clearGoalSelection();
	}

	/**
	 * Post-creation selection rule: after an addXxxGoal call succeeds,
	 * the new goal becomes the active selection unless the creation flow
	 * already arranged for it (e.g. quest gesture-select includes the
	 * parent goal alongside seeded prerequisite goals). The contains()
	 * guard preserves those richer selections. Combined with the
	 * auto-deselect rule, every "add a goal" gesture ends with the new
	 * goal as part of the active selection - no stale prior selection
	 * lingers, no need for callers to manage selection by hand.
	 */
	private void selectAfterCreate(String newGoalId)
	{
		if (newGoalId == null) return;
		if (selectedGoalIds.contains(newGoalId)) return;
		replaceGoalSelection(java.util.Collections.singleton(newGoalId));
	}

	@Override
	public java.util.Set<String> getSelectedGoalIds()
	{
		return java.util.Collections.unmodifiableSet(selectedGoalIds);
	}

	@Override
	public int selectAllInSection(String sectionId)
	{
		log.debug("API.internal selectAllInSection(sectionId={})", sectionId);
		if (sectionId == null) return 0;
		int added = 0;
		for (Goal g : goalStore.getGoals())
		{
			if (sectionId.equals(g.getSectionId()))
			{
				if (selectedGoalIds.add(g.getId())) added++;
			}
		}
		if (added > 0 && !commandHistory.isInCompound()) onSelectionChanged.run();
		return added;
	}

	@Override
	public int deselectAllInSection(String sectionId)
	{
		log.debug("API.internal deselectAllInSection(sectionId={})", sectionId);
		if (sectionId == null) return 0;
		int removed = 0;
		for (Goal g : goalStore.getGoals())
		{
			if (sectionId.equals(g.getSectionId()))
			{
				if (selectedGoalIds.remove(g.getId())) removed++;
			}
		}
		if (removed > 0 && !commandHistory.isInCompound()) onSelectionChanged.run();
		return removed;
	}

	// =====================================================================
	// Internal helpers
	// =====================================================================

	Goal findGoal(String goalId)
	{
		return goalStore.findGoalById(goalId);
	}
}
