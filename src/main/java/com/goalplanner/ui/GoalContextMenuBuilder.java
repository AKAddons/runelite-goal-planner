package com.goalplanner.ui;

import com.goalplanner.api.GoalPlannerApiImpl;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.persistence.GoalStore;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds right-click context menus for goal cards and section headers.
 * Extracted from GoalPanel to keep menu construction separate from panel layout.
 */
@Slf4j
class GoalContextMenuBuilder
{
	private final GoalPlannerApiImpl api;
	private final GoalStore goalStore;
	private final GoalPanel panel;
	private final GoalDialogFactory dialogFactory;
	private final GoalReorderController reorderController;
	private final com.goalplanner.GoalPlannerConfig config;

	GoalContextMenuBuilder(GoalPlannerApiImpl api,
						   GoalStore goalStore,
						   GoalPanel panel,
						   GoalDialogFactory dialogFactory,
						   GoalReorderController reorderController,
						   com.goalplanner.GoalPlannerConfig config)
	{
		this.api = api;
		this.goalStore = goalStore;
		this.panel = panel;
		this.dialogFactory = dialogFactory;
		this.reorderController = reorderController;
		this.config = config;
	}

	/**
	 * Build the tag-management menu entry. When both Add and Remove apply,
	 * collapse them into a single "Tag" submenu so the parent menu doesn't
	 * spend two lines on tagging. When only one applies, return that as a
	 * flat item ("Add Tag" / "Remove Tag") since a single-child submenu
	 * would be a hover step for nothing. Returns null when neither applies.
	 */
	private static JMenuItem buildTagMenuEntry(
		boolean canAdd, Runnable addAction,
		boolean canRemove, Runnable removeAction)
	{
		if (canAdd && canRemove)
		{
			JMenu submenu = new JMenu("Tag");
			JMenuItem add = new JMenuItem("Add");
			add.addActionListener(e -> addAction.run());
			submenu.add(add);
			// "Remove..." - ellipsis because the action opens a multi-pick
			// dialog rather than removing immediately on click.
			JMenuItem remove = new JMenuItem("Remove...");
			remove.addActionListener(e -> removeAction.run());
			submenu.add(remove);
			return submenu;
		}
		if (canAdd)
		{
			JMenuItem add = new JMenuItem("Add Tag");
			add.addActionListener(e -> addAction.run());
			return add;
		}
		if (canRemove)
		{
			JMenuItem remove = new JMenuItem("Remove Tags...");
			remove.addActionListener(e -> removeAction.run());
			return remove;
		}
		return null;
	}

	/**
	 * Attach a right-click popup trigger to a goal card. Delegates to
	 * {@link #buildSingleItemMenu} or {@link #buildBulkMenu} depending on
	 * whether the card is part of an active multi-selection.
	 */
	void addContextMenu(GoalCard card, Goal goal, int index, int sectionStart, int sectionEnd)
	{
		addContextMenu(card, goal, index, sectionStart, sectionEnd, null);
	}

	/** @param onToggleNestCollapse non-null only for goals that are a nest parent
	 *  in the current nested view - adds a hide/show-nested-prerequisites item. */
	void addContextMenu(GoalCard card, Goal goal, int index, int sectionStart, int sectionEnd,
		Runnable onToggleNestCollapse)
	{
		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e) { maybeShowPopup(e); }

			@Override
			public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }

			private void maybeShowPopup(MouseEvent e)
			{
				if (!e.isPopupTrigger()) return;
				// Right-click exits whichever pick mode is active. The
				// user is clearly navigating away from the action they
				// started; show the normal context menu of the clicked
				// card instead of stranding them in mode.
				if (!panel.pendingRelationSourceIds.isEmpty()) panel.exitRelationMode();
				if (panel.pendingMoveSourceId != null) panel.exitMoveMode();
				// Both single-item and bulk menus render through
				// ColumnMenu now - click-driven, hover-stable single
				// column with drill-down + back. The build code still
				// produces JPopupMenu trees; MenuTreeAdapter walks them
				// into MenuNode trees the ColumnMenu can render. The
				// auto-deselect rule lives at the API layer, so menu
				// re-rendering is "build and show" with no per-item
				// wrapping required.
				Set<String> sel = api.getSelectedGoalIds();
				JPopupMenu source = sel.contains(goal.getId()) && sel.size() >= 2
					? buildBulkMenu(goal.getId())
					: buildSingleItemMenu(goal, index, sectionStart, sectionEnd, onToggleNestCollapse);
				com.goalplanner.ui.columnmenu.ColumnMenu.show(card, e.getX(), e.getY(),
					com.goalplanner.ui.columnmenu.MenuTreeAdapter.fromPopup(source));
			}
		});
	}

	/**
	 * Compute the intersection of an edge-list reader's results across a
	 * set of goals. Used by the bulk Relations submenu: a common
	 * requirement (or dependent) is one every selected goal carries.
	 * Returns an empty list when {@code goals} is empty so callers can
	 * gate "show submenu" on emptiness.
	 *
	 * <p>Package-private + static so a test can verify the intersection
	 * rule without standing up a full popup-menu rendering pipeline.
	 */
	static List<String> commonEdges(
		java.util.function.Function<String, List<String>> edgeReader,
		Iterable<Goal> goals)
	{
		List<String> common = null;
		for (Goal g : goals)
		{
			List<String> edges = edgeReader.apply(g.getId());
			if (common == null)
			{
				common = new ArrayList<>(edges);
			}
			else
			{
				common.retainAll(edges);
			}
			if (common.isEmpty()) break;
		}
		return common != null ? common : Collections.emptyList();
	}

	/**
	 * Builds the normal per-card right-click menu. Called lazily on each
	 * popup show so the contents reflect current selection / completion / tag
	 * state without needing to be rebuilt at panel.rebuild() time.
	 */
	JPopupMenu buildSingleItemMenu(Goal goal, int index, int sectionStart, int sectionEnd)
	{
		return buildSingleItemMenu(goal, index, sectionStart, sectionEnd, null);
	}

	JPopupMenu buildSingleItemMenu(Goal goal, int index, int sectionStart, int sectionEnd,
		Runnable onToggleNestCollapse)
	{
		JPopupMenu menu = new JPopupMenu();

		// Selection toggle - first item so it's predictable. Label flips based on
		// the goal's current selection state. Routes through the same internal API
		// the click handler uses, so multi-select state stays consistent.
		Set<String> selectedIds = api.getSelectedGoalIds();
		boolean currentlySelected = selectedIds.contains(goal.getId());
		JMenuItem selectToggle = new JMenuItem(currentlySelected ? "Deselect" : "Select");
		selectToggle.addActionListener(e -> {
			if (currentlySelected) api.removeFromGoalSelection(goal.getId());
			else api.addToGoalSelection(goal.getId());
		});
		menu.add(selectToggle);

		// Nested view: hide/show this goal's prerequisite subtree. Menu is built
		// lazily on each show, so the label reflects current state.
		if (onToggleNestCollapse != null)
		{
			JMenuItem nestToggle = new JMenuItem(goal.isNestCollapsed()
				? "Show nested prerequisites" : "Hide nested prerequisites");
			nestToggle.addActionListener(e -> onToggleNestCollapse.run());
			menu.add(nestToggle);
		}

		// "Deselect All" appears on every card whenever ANY card is selected,
		// so the user has a quick escape from a multi-selection.
		if (!selectedIds.isEmpty())
		{
			JMenuItem deselectAll = new JMenuItem("Deselect All");
			deselectAll.addActionListener(e -> api.clearGoalSelection());
			menu.add(deselectAll);
		}

		menu.addSeparator();

		// Share this goal - copy a paste-anywhere share code.
		if (panel.isShareAvailable())
		{
			final java.util.List<String> shareIds = java.util.Collections.singletonList(goal.getId());
			JMenu shareMenu = new JMenu("Share/Save");
			JMenuItem copyCode = new JMenuItem("Copy share code");
			copyCode.addActionListener(e -> panel.copyGoalsShareCode(shareIds));
			shareMenu.add(copyCode);
			if (panel.isSavedPlansAvailable())
			{
				JMenuItem savePlan = new JMenuItem("Save share code...");
				savePlan.addActionListener(e -> panel.saveGoalsPlan(shareIds));
				shareMenu.add(savePlan);
			}
			menu.add(shareMenu);
			menu.addSeparator();
		}

		// Move-to-Top / Move-to-Bottom are accessed via right-clicking the
		// up/down arrow buttons on the card itself (see GoalCard.createArrowButton).

		// Add Goal submenu - Top/Bottom of section, Above/Below
		// the right-clicked card. Available on completed goals too (completed
		// goals are first-class); only the Completed BUILT-IN is excluded -
		// new goals are incomplete, so reconcile would immediately bounce
		// them back out of it.
		com.goalplanner.model.Section goalSection = goal.getSectionId() != null
			? goalStore.findSection(goal.getSectionId()) : null;
		boolean inCompletedBuiltIn = goalSection != null
			&& goalSection.getBuiltInKind() == com.goalplanner.model.Section.BuiltInKind.COMPLETED;
		if (goal.getSectionId() != null && !inCompletedBuiltIn)
		{
			final String secId = goal.getSectionId();
			final int posInSection = index - sectionStart;
			JMenu addGoalMenu = new JMenu("Add Goal");

			JMenuItem addTop = new JMenuItem("At Top of Section");
			addTop.addActionListener(e -> {
				dialogFactory.pendingAddPositionInSection = 0;
				dialogFactory.showAddGoalDialog(secId);
			});
			addGoalMenu.add(addTop);

			JMenuItem addBottom = new JMenuItem("At Bottom of Section");
			addBottom.addActionListener(e -> {
				dialogFactory.pendingAddPositionInSection = Integer.MAX_VALUE;
				dialogFactory.showAddGoalDialog(secId);
			});
			addGoalMenu.add(addBottom);

			// Above/Below are always valid: "above the first" lands at the
			// top, "below the last" lands at the bottom - both equivalent to
			// the dedicated Top/Bottom items, but enabling them avoids the
			// surprise of a greyed-out option on edge-of-section goals.
			JMenuItem addAbove = new JMenuItem("Above This Goal");
			addAbove.addActionListener(e -> {
				dialogFactory.pendingAddPositionInSection = posInSection;
				dialogFactory.showAddGoalDialog(secId);
			});
			addGoalMenu.add(addAbove);

			JMenuItem addBelow = new JMenuItem("Below This Goal");
			addBelow.addActionListener(e -> {
				dialogFactory.pendingAddPositionInSection = posInSection + 1;
				dialogFactory.showAddGoalDialog(secId);
			});
			addGoalMenu.add(addBelow);

			// Leagues shortcut - direct-create both goal types at the tier/area
			// milestones without opening the dialog. Lands just below the
			// right-clicked card so the user can rapidly stack related leagues
			// goals next to each other. Leagues profile only - on a main
			// account these goals could never track.
			if (com.goalplanner.persistence.GoalStore.PROFILE_LEAGUES
				.equals(goalStore.getActiveProfile()))
			{
				addGoalMenu.addSeparator();
				JMenu leaguesMenu = new JMenu("Leagues Goal");

				JMenu pointsMenu = new JMenu("League Points");
				for (int i = 0; i < com.goalplanner.model.AccountMetric.LEAGUE_TIER_NAMES.length; i++)
				{
					final int target = com.goalplanner.model.AccountMetric.LEAGUE_TIER_VALUES[i];
					JMenuItem item = new JMenuItem(com.goalplanner.model.AccountMetric.LEAGUE_TIER_NAMES[i]);
					item.addActionListener(e -> createLeaguesShortcut(
						com.goalplanner.model.AccountMetric.LEAGUE_POINTS, target, secId, posInSection + 1));
					pointsMenu.add(item);
				}
				leaguesMenu.add(pointsMenu);

				JMenu tasksMenu = new JMenu("Leagues Tasks");
				for (int i = 0; i < com.goalplanner.model.AccountMetric.LEAGUE_AREA_NAMES.length; i++)
				{
					final int target = com.goalplanner.model.AccountMetric.LEAGUE_AREA_VALUES[i];
					JMenuItem item = new JMenuItem(com.goalplanner.model.AccountMetric.LEAGUE_AREA_NAMES[i]);
					item.addActionListener(e -> createLeaguesShortcut(
						com.goalplanner.model.AccountMetric.LEAGUE_TASKS, target, secId, posInSection + 1));
					tasksMenu.add(item);
				}
				leaguesMenu.add(tasksMenu);

				addGoalMenu.add(leaguesMenu);
			}

			menu.add(addGoalMenu);
		}

		if (menu.getComponentCount() > 0)
		{
			menu.addSeparator();
		}

		// Manual completion: CUSTOM and ITEM_GRIND. Skill/quest/diary/CA are
		// purely game-driven. ITEM_GRIND is terminal once complete:
		// dropping below the target does NOT auto-revert. The user must
		// manually mark the goal incomplete to let the tracker re-evaluate.
		boolean manuallyToggleable = goal.getType() == GoalType.CUSTOM
			|| goal.getType() == GoalType.ITEM_GRIND;
		// Reopening is offered for EVERY completed goal, not just manually-toggled
		// types: it is the recovery path when something completed itself against
		// the wrong account. Trackers re-derive on the next drain, so a genuinely
		// finished goal comes straight back.
		if (goal.isComplete())
		{
			JMenuItem reopen = new JMenuItem("Mark Incomplete");
			reopen.addActionListener(e -> api.markGoalIncomplete(goal.getId()));
			menu.add(reopen);
		}
		if (manuallyToggleable)
		{
			if (goal.isComplete())
			{
				// handled above
			}
			else
			{
				JMenuItem complete = new JMenuItem("Mark Complete");
				complete.addActionListener(e -> api.markGoalComplete(goal.getId()));
				menu.add(complete);
			}
		}

		// Repeat submenu - CUSTOM only for now. Auto-tracked types read an
		// absolute counter, so repeating them needs a per-period baseline that
		// does not exist yet; offering it here would show the lifetime total
		// every morning instead of today's progress.
		if (goal.getType() == GoalType.CUSTOM)
		{
			JMenu repeatMenu = new JMenu("Repeat");
			for (com.goalplanner.model.RepeatPeriod period
				: com.goalplanner.model.RepeatPeriod.values())
			{
				JMenuItem item = new JMenuItem(
					goal.getRepeatEvery() == period ? period.getLabel() + " *" : period.getLabel());
				item.setEnabled(goal.getRepeatEvery() != period);
				item.addActionListener(e -> api.setGoalRepeat(goal.getId(), period));
				repeatMenu.add(item);
			}
			menu.add(repeatMenu);
		}

		addRepeatableGoalMenu(menu, goal);

		// Customize submenu - groups general property edits (optional flag,
		// name/description for CUSTOM, color, tags, relations, restore defaults)
		// under a single hover so the top-level menu stays scannable. Mark
		// Complete/Incomplete and Change Amount stay top-level: completion is
		// the primary lifecycle action, and Change Amount is the most-edited
		// field for skill/item goals.
		JMenu customizeMenu = new JMenu("Customize");

		// Optional toggle - hidden on completed goals; the optional/required
		// distinction only affects how active goals are weighted/displayed,
		// so it's noise on completed cards.
		if (!goal.isComplete())
		{
			boolean isOptional = goal.isOptional();
			JMenuItem optionalItem = new JMenuItem(isOptional ? "Mark Required" : "Mark Optional");
			optionalItem.addActionListener(e -> api.setGoalOptional(goal.getId(), !isOptional));
			customizeMenu.add(optionalItem);
		}

		if (goal.getType() == GoalType.CUSTOM && !goal.isComplete())
		{
			JMenuItem editName = new JMenuItem("Change Name");
			editName.addActionListener(e -> {
				String input = JOptionPane.showInputDialog(panel, "New name:", goal.getName());
				if (input != null && !input.trim().isEmpty())
				{
					api.editCustomGoal(goal.getId(), input.trim(), null);
				}
			});
			customizeMenu.add(editName);

			JMenuItem editDesc = new JMenuItem("Change Description");
			editDesc.addActionListener(e -> {
				String input = JOptionPane.showInputDialog(panel, "New description:",
					goal.getDescription() != null ? goal.getDescription() : "");
				if (input != null)
				{
					api.editCustomGoal(goal.getId(), null, input.trim());
				}
			});
			customizeMenu.add(editDesc);
		}

		// Change Color is available on all active goal types - override persists
		// on the goal model so rebuilds don't clobber it. Hidden on completed
		// goals included - completed goals are first-class, so recolouring is fine.
		JMenuItem changeGoalColor = new JMenuItem("Change Color");
		changeGoalColor.addActionListener(e -> dialogFactory.showGoalColorDialog(goal));
		customizeMenu.add(changeGoalColor);

		// Skill-specific options. Available on completed goals too - completed
		// and active goals are equivalent under retargeting: the new target
		// re-evaluates completion (raising it past the progress reopens the
		// goal, trackers resume).
		if (goal.getType() == GoalType.SKILL)
		{
			JMenuItem editLevel = new JMenuItem("Change Amount");
			editLevel.addActionListener(e -> dialogFactory.showChangeSkillTargetDialog(goal));
			menu.add(editLevel);
		}

		// Item-specific options (completed goals included - same re-evaluation).
		if (goal.getType() == GoalType.ITEM_GRIND)
		{
			JMenuItem editQty = new JMenuItem("Change Amount");
			editQty.addActionListener(e -> {
				String input = JOptionPane.showInputDialog(
					panel,
					"New target quantity for " + goal.getName() + ":",
					String.valueOf(goal.getTargetValue())
				);
				if (input != null)
				{
					try
					{
						int newQty = Integer.parseInt(input.trim().replace(",", ""));
						if (newQty > 0)
						{
							// changeTarget regenerates the description from the new
							// quantity; no follow-up mutation needed.
							api.changeTarget(goal.getId(), newQty);
						}
					}
					catch (NumberFormatException ignored) {}
				}
			});
			menu.add(editQty);
		}

		// Boss kill-count goals: edit the target KC, same as an item quantity.
		if (goal.getType() == GoalType.BOSS)
		{
			JMenuItem editKc = new JMenuItem("Change Amount");
			editKc.addActionListener(e -> {
				String input = JOptionPane.showInputDialog(
					panel,
					"New target kill count for " + goal.getName() + ":",
					String.valueOf(goal.getTargetValue())
				);
				if (input != null)
				{
					try
					{
						int newKc = Integer.parseInt(input.trim().replace(",", ""));
						if (newKc > 0)
						{
							api.changeTarget(goal.getId(), newKc);
						}
					}
					catch (NumberFormatException ignored) {}
				}
			});
			menu.add(editKc);
		}

		// Loadout Lab link-in, install-aware:
		//   ENABLED            - "Search in Loadout Lab" posts the cross-plugin
		//                        message (the receiver's lookup is punctuation-
		//                        and level-suffix tolerant, so the BossKillData
		//                        display name goes as-is).
		//   INSTALLED_DISABLED - grayed nudge; the user has it, remind them.
		//   NOT_INSTALLED      - no entry at all: users without Loadout Lab
		//                        get no upsell in their context menu.
		// Also hidden when the support isn't wired (panels built in tests).
		if (goal.getType() == GoalType.BOSS && goal.getBossName() != null
			&& !goal.getBossName().isEmpty())
		{
			GoalPanel.LoadoutLabState labState = panel.loadoutLabState();
			if (labState == GoalPanel.LoadoutLabState.ENABLED)
			{
				JMenuItem searchLab = new JMenuItem("Search in Loadout Lab", loadoutLabIcon());
				final String monster = goal.getBossName();
				searchLab.addActionListener(e -> panel.searchLoadoutLab(monster));
				menu.add(searchLab);
			}
			else if (labState == GoalPanel.LoadoutLabState.INSTALLED_DISABLED)
			{
				JMenuItem labDisabled = new JMenuItem("Loadout Lab is installed but disabled", loadoutLabIcon());
				labDisabled.setEnabled(false);
				menu.add(labDisabled);
			}
			// NOT_INSTALLED or null (support not wired): no entry.
		}

		// Tag management - routes through the shared TagPickerDialog so the
		// single-item and bulk Add Tag flows stay in lockstep (category list,
		// SKILLING lock, freeform/dropdown switch).
		// Removable tags: for CUSTOM goals, anything. For everything else, only
		// user-added tags (not in defaultTagIds). Dereference tag ids
		// through the store and operate on Tag entities.
		List<com.goalplanner.model.Tag> removableTags = new ArrayList<>();
		List<com.goalplanner.model.Tag> allGoalTags = new ArrayList<>();
		if (goal.getTagIds() != null && !goal.getTagIds().isEmpty())
		{
			List<String> defaults = goal.getDefaultTagIds() != null
				? goal.getDefaultTagIds() : Collections.emptyList();
			for (String tagId : goal.getTagIds())
			{
				com.goalplanner.model.Tag t = goalStore.findTag(tagId);
				if (t == null) continue;
				allGoalTags.add(t);
				if (goal.getType() == GoalType.CUSTOM || !defaults.contains(tagId))
				{
					removableTags.add(t);
				}
			}
		}

		// Completed goals are tag-frozen, so no Add. Remove still allowed
		// (cleanup of stale tags). When both Add and Remove apply, collapse
		// them under a single "Tag" submenu to save a menu line.
		final List<com.goalplanner.model.Tag> finalRemovable = removableTags;
		Runnable addTagAction = () -> {
			TagPickerDialog.Result picked = TagPickerDialog.show(panel, "Add Tag", api);
			if (picked != null)
			{
				api.addTagWithCategory(goal.getId(), picked.label, picked.category.name());
			}
		};
		Runnable removeTagAction = () -> {
			List<MultiSelectDialog.Item> items = new ArrayList<>();
			for (com.goalplanner.model.Tag t : finalRemovable)
			{
				items.add(new MultiSelectDialog.Item(
					t.getLabel(),
					t.getLabel() + " (" + t.getCategory().getDisplayName() + ")"));
			}
			List<String> chosen = MultiSelectDialog.show(
				panel, "Remove Tags", "Remove", items);
			if (chosen.isEmpty()) return;
			api.beginCompound("Remove " + chosen.size() + " tag(s)");
			try
			{
				for (String label : chosen) api.removeTag(goal.getId(), label);
			}
			finally { api.endCompound(); }
		};
		// Completed goals are first-class - allow tagging them too (their tags
		// render on the card, same as active goals).
		JMenuItem tagEntry = buildTagMenuEntry(
			true, addTagAction,
			!removableTags.isEmpty(), removeTagAction);
		if (tagEntry != null) customizeMenu.add(tagEntry);

		// Relations. All requirement-graph editing collapsed under a single
		// "Relations" parent so the top-level menu stays short. "Requires..."
		// and "Required by..." enter a click-mode where the user clicks
		// another goal to link. The Remove children are direct pick-to-remove
		// lists of the current edges. Hidden for completed goals: completed
		// items are reference history, not active tracking - editing their
		// prerequisite graph adds noise with no behavioral payoff.
		if (!goal.isComplete())
		{
			JMenu relationsMenu = new JMenu("Relations");

			JMenuItem addRequirement = new JMenuItem("Requires...");
			addRequirement.setToolTipText(
				"Click, then click another goal to mark it as a requirement of this one.");
			addRequirement.addActionListener(e ->
				panel.enterRelationMode(goal.getId(), /*sourceRequiresTarget=*/true));
			relationsMenu.add(addRequirement);

			JMenuItem addDependent = new JMenuItem("Required by...");
			addDependent.setToolTipText(
				"Click, then click another goal that should require this one.");
			addDependent.addActionListener(e ->
				panel.enterRelationMode(goal.getId(), /*sourceRequiresTarget=*/false));
			relationsMenu.add(addDependent);

			List<String> currentRequirements = api.getRequirements(goal.getId());
			List<String> currentDependents = api.getDependents(goal.getId());
			if (!currentRequirements.isEmpty() || !currentDependents.isEmpty())
			{
				relationsMenu.addSeparator();
			}

			// Remove Requirements... - opens a checkbox dialog so the user
			// can drop several edges in one gesture instead of clicking a
			// submenu item per edge. Submit removes the chosen subset
			// inside one compound (one undo).
			if (!currentRequirements.isEmpty())
			{
				final List<String> reqsSnapshot = new ArrayList<>(currentRequirements);
				JMenuItem removeReqs = new JMenuItem("Remove Requirements...");
				removeReqs.addActionListener(e -> {
					List<MultiSelectDialog.Item> items = new ArrayList<>();
					for (String reqId : reqsSnapshot)
					{
						items.add(new MultiSelectDialog.Item(
							reqId, reorderController.goalNameById(reqId)));
					}
					List<String> chosen = MultiSelectDialog.show(
						panel, "Remove Requirements", "Remove", items);
					if (chosen.isEmpty()) return;
					api.beginCompound("Remove " + chosen.size() + " requirement(s)");
					try
					{
						for (String reqId : chosen) api.removeRequirement(goal.getId(), reqId);
					}
					finally { api.endCompound(); }
				});
				relationsMenu.add(removeReqs);
			}

			// Remove Dependents... - same multi-pick pattern.
			if (!currentDependents.isEmpty())
			{
				final List<String> depsSnapshot = new ArrayList<>(currentDependents);
				JMenuItem removeDeps = new JMenuItem("Remove Dependents...");
				removeDeps.addActionListener(e -> {
					List<MultiSelectDialog.Item> items = new ArrayList<>();
					for (String depId : depsSnapshot)
					{
						items.add(new MultiSelectDialog.Item(
							depId, reorderController.goalNameById(depId)));
					}
					List<String> chosen = MultiSelectDialog.show(
						panel, "Remove Dependents", "Remove", items);
					if (chosen.isEmpty()) return;
					api.beginCompound("Remove " + chosen.size() + " dependent(s)");
					try
					{
						for (String depId : chosen) api.removeRequirement(depId, goal.getId());
					}
					finally { api.endCompound(); }
				});
				relationsMenu.add(removeDeps);
			}

			customizeMenu.add(relationsMenu);
		}

		// Restore Defaults - gated on isGoalOverridden (tag drift
		// OR color override). Routes through the bulk API so the single-item
		// path resets BOTH tags and color in one shot.
		if (api.isGoalOverridden(goal.getId()))
		{
			JMenuItem restore = new JMenuItem("Restore Defaults");
			restore.addActionListener(e ->
				api.bulkRestoreDefaults(Collections.singleton(goal.getId())));
			customizeMenu.add(restore);
		}

		// Add the Customize submenu only if it actually has content. On a
		// completed goal with no removable tags and no overrides, every item
		// inside is gated off and the submenu would be a dead hover.
		if (customizeMenu.getMenuComponentCount() > 0)
		{
			menu.add(customizeMenu);
		}

		// Add requirements to this section - for quest/diary goals with known
		// game-data requirements, seed the requirement tree (prior quests, skill
		// levels, unlocks, ...) into this goal's section so the nested guide is whole.
		// "Incomplete only" = unmet reqs (live state); "All" = the entire tree
		// (floor lookups, deterministic). Existing goals in the section are reused.
		boolean questWithReqs = false;
		if (goal.getType() == GoalType.QUEST && goal.getQuestName() != null)
		{
			try
			{
				net.runelite.api.Quest q = net.runelite.api.Quest.valueOf(goal.getQuestName());
				questWithReqs = com.goalplanner.data.QuestRequirements.hasRequirements(q);
			}
			catch (IllegalArgumentException ignored) {}
		}
		boolean diaryWithReqs = false;
		if (goal.getType() == GoalType.DIARY && goal.getName() != null)
		{
			com.goalplanner.data.AchievementDiaryData.Tier tier =
				parseDiaryTier(goal.getDescription());
			diaryWithReqs = tier != null
				&& com.goalplanner.data.DiaryRequirements.hasRequirements(goal.getName(), tier);
		}
		boolean bossWithReqs = goal.getType() == GoalType.BOSS && goal.getBossName() != null
			&& com.goalplanner.data.BossKillData.getPrereqs(goal.getBossName()) != null;
		if (questWithReqs || diaryWithReqs || bossWithReqs)
		{
			final ReqKind reqKind = diaryWithReqs ? ReqKind.DIARY : bossWithReqs ? ReqKind.BOSS : ReqKind.QUEST;
			JMenu addReqs = new JMenu("Add requirements to this section");
			JMenuItem reqsIncomplete = new JMenuItem("Incomplete only");
			reqsIncomplete.setToolTipText("Add requirements you haven't met yet.");
			reqsIncomplete.addActionListener(e -> seedReqs(goal.getId(), false, reqKind));
			JMenuItem reqsAll = new JMenuItem("All");
			reqsAll.setToolTipText("Add the whole requirement tree, including ones already met.");
			reqsAll.addActionListener(e -> seedReqs(goal.getId(), true, reqKind));
			addReqs.add(reqsIncomplete);
			addReqs.add(reqsAll);
			menu.add(addReqs);
		}

		// Move submenu - sibling of Customize. Collects all relocation actions
		// under one hover: Move to Top/Bottom reorder within the current
		// section; Move to... enters click-mode; Move to Section ▶ lists every
		// other valid section plus a "New Section" option, so almost every
		// incomplete goal has at least one valid destination. Completed goals
		// are included now (first-class section members) - but the in-section
		// REORDER items (Top/Bottom, Move to...) stay hidden on completed goals,
		// which are force-sorted to the bottom, so reorder is a no-op there.
		// Move to Section / Duplicate to Section still work on them.
		{
			JMenu moveMenu = new JMenu("Move");

			if (!goal.isComplete())
			{
				int sectionSize = sectionEnd - sectionStart + 1;
				if (sectionSize > 1)
				{
					JMenuItem moveToTop = new JMenuItem("Move to Top");
					moveToTop.addActionListener(e ->
						reorderController.moveGoalTo(goal.getId(), sectionStart));
					moveMenu.add(moveToTop);

					JMenuItem moveToBottom = new JMenuItem("Move to Bottom");
					moveToBottom.addActionListener(e ->
						reorderController.moveGoalTo(goal.getId(), sectionEnd));
					moveMenu.add(moveToBottom);
				}

				JMenuItem moveToPicker = new JMenuItem("Move to...");
				moveToPicker.setToolTipText(
					"Click, then click another goal to place this one above it, or click + New Section.");
				moveToPicker.addActionListener(e -> panel.enterMoveMode(goal.getId()));
				moveMenu.add(moveToPicker);
			}

			JMenu moveToSection = new JMenu("Move to Section");

			List<com.goalplanner.api.SectionView> allSections = api.queryAllSections();
			List<com.goalplanner.api.SectionView> destinations = new ArrayList<>();
			// The two built-ins are one logical "Default" bucket (auto-split by
			// completion) - offered as a single "Default" entry below, not as
			// individual Incomplete/Completed destinations.
			String defaultIncompleteId = null;
			boolean goalInDefault = false;
			for (com.goalplanner.api.SectionView sv : allSections)
			{
				if ("INCOMPLETE".equals(sv.kind)) defaultIncompleteId = sv.id;
				if (sv.builtIn)
				{
					if (sv.id.equals(goal.getSectionId())) goalInDefault = true;
					continue;
				}
				if (sv.id.equals(goal.getSectionId())) continue;
				destinations.add(sv);
			}
			if (!goalInDefault)
			{
				// "Default" → the Incomplete/Completed list; lands by completion.
				JMenuItem moveDefault = new JMenuItem("Default");
				moveDefault.setToolTipText("Move to the default Incomplete / Completed list.");
				moveDefault.addActionListener(e -> api.moveGoalsToDefault(
					java.util.Collections.singletonList(goal.getId())));
				moveToSection.add(moveDefault);
			}
			for (com.goalplanner.api.SectionView dest : destinations)
			{
				JMenuItem item = new JMenuItem(dest.name);
				item.addActionListener(e -> api.moveGoalToSection(goal.getId(), dest.id));
				moveToSection.add(item);
			}
			if (!destinations.isEmpty())
			{
				moveToSection.addSeparator();
			}
			JMenuItem newSectionItem = new JMenuItem("Move to New Section...");
			newSectionItem.addActionListener(e ->
				promptNewSection(newId -> api.moveGoalToSection(goal.getId(), newId)));
			moveToSection.add(newSectionItem);

			moveMenu.add(moveToSection);

			// Duplicate to Section - an independent copy in another section.
			// Per-section identity: the same goal can live once per section.
			JMenu dupToSection = new JMenu("Duplicate to Section");
			if (!goalInDefault && defaultIncompleteId != null)
			{
				// A fresh copy is incomplete, so it enters the default via Incomplete.
				final String defId = defaultIncompleteId;
				JMenuItem dupDefault = new JMenuItem("Default");
				dupDefault.setToolTipText("Duplicate into the default Incomplete / Completed list.");
				dupDefault.addActionListener(e -> api.duplicateGoalsToSection(
					java.util.Collections.singletonList(goal.getId()), defId));
				dupToSection.add(dupDefault);
			}
			for (com.goalplanner.api.SectionView dest : destinations)
			{
				JMenuItem dupItem = new JMenuItem(dest.name);
				dupItem.addActionListener(e -> api.duplicateGoalsToSection(
					java.util.Collections.singletonList(goal.getId()), dest.id));
				dupToSection.add(dupItem);
			}
			if (!destinations.isEmpty())
			{
				dupToSection.addSeparator();
			}
			JMenuItem dupNewSection = new JMenuItem("Duplicate to New Section...");
			dupNewSection.addActionListener(e -> promptNewSection(newId ->
				api.duplicateGoalsToSection(java.util.Collections.singletonList(goal.getId()), newId)));
			dupToSection.add(dupNewSection);
			moveMenu.add(dupToSection);

			menu.add(moveMenu);
		}

		JMenuItem remove = new JMenuItem("Remove Goal");
		remove.addActionListener(e -> api.removeGoal(goal.getId()));
		menu.add(remove);

		return menu;
	}

	/**
	 * Direct-create an account goal from the Leagues shortcut submenu and
	 * drop it into the given section at the given slot. Wrapped in a
	 * compound so undo treats it as one step.
	 */
	/**
	 * Prompt for a new section name; if the user confirms a non-blank name and
	 * the section is created, run {@code action} with the new section id. Shared
	 * by the Move/Duplicate "...to New Section..." menu items.
	 */
	private void promptNewSection(java.util.function.Consumer<String> action)
	{
		String input = JOptionPane.showInputDialog(panel, "New section name:", "");
		if (input != null && !input.trim().isEmpty())
		{
			String newId = api.createSection(input.trim());
			if (newId != null)
			{
				action.accept(newId);
			}
		}
	}

	private void createLeaguesShortcut(com.goalplanner.model.AccountMetric metric,
									   int target, String sectionId, int positionInSection)
	{
		api.beginCompound("Add " + metric.getDisplayName() + " (" + target + ")");
		try
		{
			String createdId = api.addAccountGoal(metric.name(), target);
			if (createdId != null && sectionId != null)
			{
				api.positionGoalInSection(createdId, sectionId, positionInSection);
			}
		}
		finally
		{
			api.endCompound();
		}
	}

	/**
	 * Builds the streamlined bulk-action menu shown when the right-clicked
	 * card is part of a multi-selection (size >= 2). Five items only:
	 * Move to Section, Add Tag, Change Color, Remove, Mark as Complete.
	 */
	JPopupMenu buildBulkMenu(String rightClickedGoalId)
	{
		JPopupMenu menu = new JPopupMenu();
		Set<String> selectedIds = api.getSelectedGoalIds();
		int selectionSize = selectedIds.size();

		// Snapshot the selected Goal objects up front so all handlers operate
		// on a consistent set even if the underlying selection mutates between
		// menu open and item pick.
		List<Goal> selectedGoals = new ArrayList<>();
		for (Goal g : goalStore.getGoals())
		{
			if (selectedIds.contains(g.getId())) selectedGoals.add(g);
		}

		// Header label so the user knows the menu applies to N cards
		JMenuItem header = new JMenuItem(selectionSize + " selected");
		header.setEnabled(false);
		menu.add(header);

		// Share the selected goals as one copy-paste code.
		if (panel.isShareAvailable())
		{
			final List<String> shareIds = new ArrayList<>(selectedIds);
			menu.addSeparator();
			JMenu shareMenu = new JMenu("Share/Save " + selectionSize + " selected");
			JMenuItem copyCode = new JMenuItem("Copy share code");
			copyCode.addActionListener(e -> panel.copyGoalsShareCode(shareIds));
			shareMenu.add(copyCode);
			if (panel.isSavedPlansAvailable())
			{
				JMenuItem savePlan = new JMenuItem("Save share code...");
				savePlan.addActionListener(e -> panel.saveGoalsPlan(shareIds));
				shareMenu.add(savePlan);
			}
			menu.add(shareMenu);
		}

		// Selection toggle + deselect all on the bulk menu so the
		// user can drop one card or escape the whole multi-selection without
		// having to find a single-card popup.
		menu.addSeparator();
		JMenuItem deselectThis = new JMenuItem("Deselect this");
		deselectThis.addActionListener(e -> api.removeFromGoalSelection(rightClickedGoalId));
		menu.add(deselectThis);
		JMenuItem deselectOthers = new JMenuItem("Deselect all but this");
		deselectOthers.addActionListener(e ->
			api.replaceGoalSelection(Collections.singleton(rightClickedGoalId)));
		menu.add(deselectOthers);
		JMenuItem deselectAll = new JMenuItem("Deselect All");
		deselectAll.addActionListener(e -> api.clearGoalSelection());
		menu.add(deselectAll);
		menu.addSeparator();

		// Mark as Complete - top-level (mirrors single-item menu where
		// Mark Complete/Incomplete is a primary lifecycle action). Only
		// when ALL selected are CUSTOM (per locked design).
		boolean allCustom = !selectedGoals.isEmpty();
		for (Goal g : selectedGoals)
		{
			if (g.getType() != GoalType.CUSTOM) { allCustom = false; break; }
		}
		if (allCustom)
		{
			JMenuItem markComplete = new JMenuItem("Mark as Complete");
			markComplete.addActionListener(e -> {
				api.beginCompound("Mark " + selectedGoals.size() + " complete");
				try
				{
					for (Goal g : selectedGoals) api.markGoalComplete(g.getId());
				}
				finally { api.endCompound(); }
			});
			menu.add(markComplete);
		}

		// Customize submenu - collects property-edit actions that apply to
		// the whole selection (optional flag, color, tags, restore defaults).
		// Mirrors the single-item Customize submenu so the menu shape is the
		// same whether the user has one or many cards selected.
		JMenu customizeMenu = new JMenu("Customize");

		// Mark Optional/Required - only applies to non-completed goals
		// (matches single-item gating). When the selection is uniform
		// (all required or all optional), show a single flip action;
		// when mixed, show a submenu so the user can force either state.
		List<Goal> optionalTargets = new ArrayList<>();
		for (Goal g : selectedGoals)
		{
			if (!g.isComplete()) optionalTargets.add(g);
		}
		if (!optionalTargets.isEmpty())
		{
			boolean anyOptional = false;
			boolean anyRequired = false;
			for (Goal g : optionalTargets)
			{
				if (g.isOptional()) anyOptional = true;
				else anyRequired = true;
				if (anyOptional && anyRequired) break;
			}
			final List<Goal> finalOptionalTargets = optionalTargets;
			Runnable markAllOptional = () -> {
				api.beginCompound("Mark " + finalOptionalTargets.size() + " optional");
				try
				{
					for (Goal g : finalOptionalTargets) api.setGoalOptional(g.getId(), true);
				}
				finally { api.endCompound(); }
			};
			Runnable markAllRequired = () -> {
				api.beginCompound("Mark " + finalOptionalTargets.size() + " required");
				try
				{
					for (Goal g : finalOptionalTargets) api.setGoalOptional(g.getId(), false);
				}
				finally { api.endCompound(); }
			};
			if (anyRequired && !anyOptional)
			{
				JMenuItem item = new JMenuItem("Mark as Optional");
				item.addActionListener(e -> markAllOptional.run());
				customizeMenu.add(item);
			}
			else if (anyOptional && !anyRequired)
			{
				JMenuItem item = new JMenuItem("Mark as Required");
				item.addActionListener(e -> markAllRequired.run());
				customizeMenu.add(item);
			}
			else
			{
				JMenu optionalSubmenu = new JMenu("Optional");
				JMenuItem makeOptional = new JMenuItem("Optional");
				makeOptional.addActionListener(e -> markAllOptional.run());
				optionalSubmenu.add(makeOptional);
				JMenuItem makeRequired = new JMenuItem("Required");
				makeRequired.addActionListener(e -> markAllRequired.run());
				optionalSubmenu.add(makeRequired);
				customizeMenu.add(optionalSubmenu);
			}
		}

		// Change Color - applies to every selected goal (completed included;
		// matches the single-item menu, where completed goals are recolourable).
		List<Goal> recolorTargets = new ArrayList<>(selectedGoals);
		if (!recolorTargets.isEmpty())
		{
			JMenuItem changeColor = new JMenuItem("Change Color");
			changeColor.addActionListener(e -> dialogFactory.showBulkChangeColorDialog(recolorTargets));
			customizeMenu.add(changeColor);
		}

		// Tag - Add applies to every selected goal (completed included, matching
		// the single-item menu); Remove still applies to any tagged goal.
		List<Goal> tagAddTargets = new ArrayList<>(selectedGoals);
		List<com.goalplanner.api.GoalPlannerInternalApi.TagRemovalOption> removableOpts =
			api.getRemovableTagsForSelection(selectedIds);
		JMenuItem bulkTagEntry = buildTagMenuEntry(
			!tagAddTargets.isEmpty(),
			() -> dialogFactory.showBulkAddTagDialog(tagAddTargets),
			!removableOpts.isEmpty(),
			() -> dialogFactory.showBulkRemoveTagDialog(selectedIds, removableOpts));
		if (bulkTagEntry != null) customizeMenu.add(bulkTagEntry);

		// Relations - bulk add only. Each selected non-completed goal
		// gets the same edge to/from the click-mode target. Cycle and
		// duplicate rejections fail open per source so a partial success
		// still applies. Bulk relation removal is not yet exposed -
		// per-goal edges remove via the single-item Relations submenu.
		List<Goal> relationSources = new ArrayList<>();
		for (Goal g : selectedGoals)
		{
			if (!g.isComplete()) relationSources.add(g);
		}
		if (!relationSources.isEmpty())
		{
			JMenu relationsMenu = new JMenu("Relations");

			LinkedHashSet<String> relationSourceIds = new LinkedHashSet<>();
			for (Goal g : relationSources) relationSourceIds.add(g.getId());

			JMenuItem requires = new JMenuItem("Requires...");
			requires.setToolTipText(
				"Click, then click another goal to mark as a requirement of every selected goal.");
			requires.addActionListener(e ->
				panel.enterRelationMode(relationSourceIds, /*sourceRequiresTarget=*/true));
			relationsMenu.add(requires);

			JMenuItem requiredBy = new JMenuItem("Required by...");
			requiredBy.setToolTipText(
				"Click, then click another goal that should require every selected goal.");
			requiredBy.addActionListener(e ->
				panel.enterRelationMode(relationSourceIds, /*sourceRequiresTarget=*/false));
			relationsMenu.add(requiredBy);

			// Compute intersections - an edge is "common" only if every
			// selected non-completed goal has it. Anything less would be
			// ambiguous: removing a requirement that only some selected
			// goals carry is better expressed via the single-item menu
			// on those specific goals.
			List<String> commonRequirements = commonEdges(api::getRequirements, relationSources);
			List<String> commonDependents = commonEdges(api::getDependents, relationSources);

			if (!commonRequirements.isEmpty() || !commonDependents.isEmpty())
			{
				relationsMenu.addSeparator();
			}

			if (!commonRequirements.isEmpty())
			{
				final List<Goal> finalSources = relationSources;
				final List<String> commonReqsSnapshot = new ArrayList<>(commonRequirements);
				JMenuItem removeReqs = new JMenuItem("Remove Requirements...");
				removeReqs.addActionListener(e -> {
					List<MultiSelectDialog.Item> items = new ArrayList<>();
					for (String reqId : commonReqsSnapshot)
					{
						items.add(new MultiSelectDialog.Item(
							reqId, reorderController.goalNameById(reqId)));
					}
					List<String> chosen = MultiSelectDialog.show(
						panel, "Remove Common Requirements", "Remove", items);
					if (chosen.isEmpty()) return;
					api.beginCompound("Remove " + chosen.size() + " requirement(s) from "
						+ finalSources.size() + " goals");
					try
					{
						for (String reqId : chosen)
						{
							for (Goal g : finalSources) api.removeRequirement(g.getId(), reqId);
						}
					}
					finally { api.endCompound(); }
				});
				relationsMenu.add(removeReqs);
			}

			if (!commonDependents.isEmpty())
			{
				final List<Goal> finalSources = relationSources;
				final List<String> commonDepsSnapshot = new ArrayList<>(commonDependents);
				JMenuItem removeDeps = new JMenuItem("Remove Dependents...");
				removeDeps.addActionListener(e -> {
					List<MultiSelectDialog.Item> items = new ArrayList<>();
					for (String depId : commonDepsSnapshot)
					{
						items.add(new MultiSelectDialog.Item(
							depId, reorderController.goalNameById(depId)));
					}
					List<String> chosen = MultiSelectDialog.show(
						panel, "Remove Common Dependents", "Remove", items);
					if (chosen.isEmpty()) return;
					api.beginCompound("Remove " + chosen.size() + " dependent(s) from "
						+ finalSources.size() + " goals");
					try
					{
						for (String depId : chosen)
						{
							for (Goal g : finalSources) api.removeRequirement(depId, g.getId());
						}
					}
					finally { api.endCompound(); }
				});
				relationsMenu.add(removeDeps);
			}

			customizeMenu.add(relationsMenu);
		}

		// Restore Defaults - show only if at least one selected goal is
		// overridden (tag drift OR color override).
		boolean anyOverridden = false;
		for (String id : selectedIds)
		{
			if (api.isGoalOverridden(id)) { anyOverridden = true; break; }
		}
		if (anyOverridden)
		{
			JMenuItem restoreDefaults = new JMenuItem("Restore Defaults");
			restoreDefaults.addActionListener(e -> {
				int changed = api.bulkRestoreDefaults(selectedIds);
				log.debug("bulkRestoreDefaults changed {} of {} selected goals",
					changed, selectionSize);
			});
			customizeMenu.add(restoreDefaults);
		}

		if (customizeMenu.getMenuComponentCount() > 0)
		{
			menu.add(customizeMenu);
		}

		// Move submenu - sibling of Customize (mirrors single-item menu).
		// Available whenever there's a selection + a destination section
		// (completed goals are movable/duplicable now).
		boolean anyMovable = !selectedGoals.isEmpty();
		List<com.goalplanner.api.SectionView> allSections = api.queryAllSections();
		String defaultIncompleteId = null, defaultCompletedId = null;
		for (com.goalplanner.api.SectionView sv : allSections)
		{
			if ("INCOMPLETE".equals(sv.kind)) defaultIncompleteId = sv.id;
			if ("COMPLETED".equals(sv.kind)) defaultCompletedId = sv.id;
		}
		List<com.goalplanner.api.SectionView> destinations = new ArrayList<>();
		for (com.goalplanner.api.SectionView sv : allSections)
		{
			// Built-ins are offered as a single "Default" entry, not individually.
			if (sv.builtIn) continue;
			// Skip sections where every selected goal already lives.
			boolean allAlreadyHere = true;
			for (Goal g : selectedGoals)
			{
				if (!sv.id.equals(g.getSectionId())) { allAlreadyHere = false; break; }
			}
			if (allAlreadyHere) continue;
			destinations.add(sv);
		}
		// "Default" shows unless every selected goal already lives in the default.
		boolean allInDefault = !selectedGoals.isEmpty();
		for (Goal g : selectedGoals)
		{
			String sid = g.getSectionId();
			if (sid == null || (!sid.equals(defaultIncompleteId) && !sid.equals(defaultCompletedId)))
			{
				allInDefault = false;
				break;
			}
		}

		// In-section bulk reordering - only when every selected goal is in
		// the same section and non-completed, and there's room to move
		// (section has more goals than the selection). All four operations
		// preserve the relative priority order of the selected block.
		boolean allInOneSection = !selectedGoals.isEmpty();
		String commonSectionId = selectedGoals.isEmpty() ? null : selectedGoals.get(0).getSectionId();
		boolean allActive = true;
		for (Goal g : selectedGoals)
		{
			if (!commonSectionId.equals(g.getSectionId())) allInOneSection = false;
			if (g.isComplete()) allActive = false;
		}
		boolean canInSectionMove = allInOneSection && allActive && commonSectionId != null;
		int sectionStart = -1;
		int sectionEnd = -1;
		if (canInSectionMove)
		{
			List<Goal> allGoals = goalStore.getGoals();
			for (int i = 0; i < allGoals.size(); i++)
			{
				if (commonSectionId.equals(allGoals.get(i).getSectionId()))
				{
					if (sectionStart == -1) sectionStart = i;
					sectionEnd = i;
				}
			}
			int sectionSize = (sectionStart == -1) ? 0 : (sectionEnd - sectionStart + 1);
			// "Same goals as the section" → every move is a no-op; hide
			// the in-section items entirely.
			if (sectionSize <= selectedGoals.size()) canInSectionMove = false;
		}

		if (anyMovable && (canInSectionMove || !destinations.isEmpty()))
		{
			JMenu moveMenu = new JMenu("Move");

			if (canInSectionMove)
			{
				final String moveSectionId = commonSectionId;
				final int moveSectionStart = sectionStart;
				final int moveSectionEnd = sectionEnd;

				// Repeat-friendly actions - keep the column-menu popup open
				// after click so the user can nudge a selection up/down
				// several rows without reopening the menu each time.
				JMenuItem moveToTop = new JMenuItem("Move to Top");
				moveToTop.addActionListener(e ->
					bulkMoveToTop(selectedGoals, moveSectionId, moveSectionStart));
				moveToTop.putClientProperty(
					com.goalplanner.ui.columnmenu.MenuTreeAdapter.KEEP_OPEN_PROPERTY, Boolean.TRUE);
				moveMenu.add(moveToTop);

				JMenuItem moveToBottom = new JMenuItem("Move to Bottom");
				moveToBottom.addActionListener(e ->
					bulkMoveToBottom(selectedGoals, moveSectionId,
						moveSectionStart, moveSectionEnd));
				moveToBottom.putClientProperty(
					com.goalplanner.ui.columnmenu.MenuTreeAdapter.KEEP_OPEN_PROPERTY, Boolean.TRUE);
				moveMenu.add(moveToBottom);

				JMenuItem moveUp = new JMenuItem("Move Up");
				moveUp.addActionListener(e ->
					bulkMoveUp(selectedGoals, moveSectionId, moveSectionStart));
				moveUp.putClientProperty(
					com.goalplanner.ui.columnmenu.MenuTreeAdapter.KEEP_OPEN_PROPERTY, Boolean.TRUE);
				moveMenu.add(moveUp);

				JMenuItem moveDown = new JMenuItem("Move Down");
				moveDown.addActionListener(e ->
					bulkMoveDown(selectedGoals, moveSectionId,
						moveSectionStart, moveSectionEnd));
				moveDown.putClientProperty(
					com.goalplanner.ui.columnmenu.MenuTreeAdapter.KEEP_OPEN_PROPERTY, Boolean.TRUE);
				moveMenu.add(moveDown);
			}

			// Always offered: even with no user sections and an all-default
			// selection, "New Section..." is a valid target.
			{
				JMenu moveToSection = new JMenu("Move to Section");
				if (!allInDefault)
				{
					JMenuItem moveDefault = new JMenuItem("Default");
					moveDefault.setToolTipText("Move to the default Incomplete / Completed list.");
					moveDefault.addActionListener(e -> {
						LinkedHashSet<String> ids = new LinkedHashSet<>();
						for (Goal g : selectedGoals) ids.add(g.getId());
						api.moveGoalsToDefault(ids);
					});
					moveToSection.add(moveDefault);
				}
				for (com.goalplanner.api.SectionView dest : destinations)
				{
					JMenuItem item = new JMenuItem(dest.name);
					item.addActionListener(e -> {
						LinkedHashSet<String> ids = new LinkedHashSet<>();
						for (Goal g : selectedGoals) ids.add(g.getId());
						api.bulkMoveGoalsToSection(ids, dest.id);
					});
					moveToSection.add(item);
				}
				if (moveToSection.getMenuComponentCount() > 0)
				{
					moveToSection.addSeparator();
				}
				JMenuItem moveNewSection = new JMenuItem("Move to New Section...");
				moveNewSection.addActionListener(e -> promptNewSection(newId -> {
					LinkedHashSet<String> ids = new LinkedHashSet<>();
					for (Goal g : selectedGoals) ids.add(g.getId());
					api.bulkMoveGoalsToSection(ids, newId);
				}));
				moveToSection.add(moveNewSection);
				moveMenu.add(moveToSection);

				// Bulk Duplicate to Section - independent copies, in-set
				// relations remapped, per-section dedup skips ones already there.
				JMenu dupToSection = new JMenu("Duplicate to Section");
				if (!allInDefault && defaultIncompleteId != null)
				{
					final String defId = defaultIncompleteId;
					JMenuItem dupDefault = new JMenuItem("Default");
					dupDefault.setToolTipText("Duplicate into the default Incomplete / Completed list.");
					dupDefault.addActionListener(e -> {
						LinkedHashSet<String> ids = new LinkedHashSet<>();
						for (Goal g : selectedGoals) ids.add(g.getId());
						api.duplicateGoalsToSection(ids, defId);
					});
					dupToSection.add(dupDefault);
				}
				for (com.goalplanner.api.SectionView dest : destinations)
				{
					JMenuItem item = new JMenuItem(dest.name);
					item.addActionListener(e -> {
						LinkedHashSet<String> ids = new LinkedHashSet<>();
						for (Goal g : selectedGoals) ids.add(g.getId());
						api.duplicateGoalsToSection(ids, dest.id);
					});
					dupToSection.add(item);
				}
				if (dupToSection.getMenuComponentCount() > 0)
				{
					dupToSection.addSeparator();
				}
				JMenuItem dupNewSection = new JMenuItem("Duplicate to New Section...");
				dupNewSection.addActionListener(e -> promptNewSection(newId -> {
					LinkedHashSet<String> ids = new LinkedHashSet<>();
					for (Goal g : selectedGoals) ids.add(g.getId());
					api.duplicateGoalsToSection(ids, newId);
				}));
				dupToSection.add(dupNewSection);
				moveMenu.add(dupToSection);
			}

			menu.add(moveMenu);
		}

		menu.addSeparator();

		// Remove
		JMenuItem removeItem = new JMenuItem("Remove Goals");
		removeItem.addActionListener(e -> {
			LinkedHashSet<String> ids = new LinkedHashSet<>();
			for (Goal g : selectedGoals) ids.add(g.getId());
			api.bulkRemoveGoals(ids);
		});
		menu.add(removeItem);

		return menu;
	}

	/**
	 * Attach a right-click context menu to a section header row.
	 * User sections get the full rename/move/delete/color menu;
	 * built-ins get Add Goal, Add Section, Select All, and Change Color.
	 */
	void attachSectionContextMenu(SectionHeaderRow row,
								  com.goalplanner.api.SectionView section,
								  List<com.goalplanner.api.SectionView> allSections)
	{
		// Compute this section's index within the user-section band so we can
		// gate the move-up / move-down items correctly.
		List<com.goalplanner.api.SectionView> userSections = new ArrayList<>();
		for (com.goalplanner.api.SectionView sv : allSections)
		{
			if (!sv.builtIn) userSections.add(sv);
		}
		int userIndex = -1;
		for (int i = 0; i < userSections.size(); i++)
		{
			if (userSections.get(i).id.equals(section.id)) { userIndex = i; break; }
		}
		final int currentUserIndex = userIndex;

		JPopupMenu menu = new JPopupMenu();

		// Add Goal submenu - Top of Section / Bottom of Section.
		// Hidden on Completed (auto-managed).
		if (!"COMPLETED".equals(section.kind))
		{
			JMenu addGoalMenu = new JMenu("Add Goal");
			JMenuItem addTop = new JMenuItem("At Top of Section");
			addTop.addActionListener(e -> {
				dialogFactory.pendingAddPositionInSection = 0;
				dialogFactory.showAddGoalDialog(section.id);
			});
			addGoalMenu.add(addTop);
			JMenuItem addBottom = new JMenuItem("At Bottom of Section");
			addBottom.addActionListener(e -> {
				dialogFactory.pendingAddPositionInSection = Integer.MAX_VALUE;
				dialogFactory.showAddGoalDialog(section.id);
			});
			addGoalMenu.add(addBottom);
			menu.add(addGoalMenu);
		}

		// Add Section submenu. User sections get Above/Below;
		// built-ins (Incomplete, Completed) get a single entry that creates
		// the new section at the end of the user-band.
		if (!section.builtIn)
		{
			JMenu addSectionMenu = new JMenu("Add Section");
			JMenuItem addSectionAbove = new JMenuItem("Above");
			addSectionAbove.addActionListener(e -> dialogFactory.showCreateSectionDialog(currentUserIndex));
			addSectionMenu.add(addSectionAbove);
			JMenuItem addSectionBelow = new JMenuItem("Below");
			addSectionBelow.addActionListener(e -> dialogFactory.showCreateSectionDialog(currentUserIndex + 1));
			addSectionMenu.add(addSectionBelow);
			menu.add(addSectionMenu);
		}
		else
		{
			JMenuItem addSection = new JMenuItem("Add Section");
			addSection.addActionListener(e -> dialogFactory.showCreateSectionDialog(-1));
			menu.add(addSection);
		}

		menu.addSeparator();

		// Select / Deselect All in Section - label flips when every goal in the
		// section is already selected. Computed against the current selection
		// snapshot at menu-build time.
		Set<String> currentSel = api.getSelectedGoalIds();
		List<String> sectionGoalIds = new ArrayList<>();
		for (Goal g : goalStore.getGoals())
		{
			if (section.id.equals(g.getSectionId())) sectionGoalIds.add(g.getId());
		}
		boolean allSelected = !sectionGoalIds.isEmpty() && currentSel.containsAll(sectionGoalIds);
		JMenuItem selectAll = new JMenuItem(allSelected
			? "Deselect All in Section"
			: "Select All in Section");
		selectAll.addActionListener(e -> {
			if (allSelected) api.deselectAllInSection(section.id);
			else api.selectAllInSection(section.id);
		});
		// Disable on empty sections - nothing to (de)select.
		if (sectionGoalIds.isEmpty()) selectAll.setEnabled(false);
		menu.add(selectAll);

		menu.addSeparator();

		// Share this whole section as a copy-paste code.
		// Mirrors the goal-card Share submenu so sections and goals are
		// consistent. Only shown when the section actually has goals.
		if (panel.isShareAvailable() && !sectionGoalIds.isEmpty())
		{
			final String shareSectionId = section.id;
			JMenu shareMenu = new JMenu("Share/Save");
			JMenuItem copyCode = new JMenuItem("Copy share code");
			copyCode.addActionListener(e -> panel.copySectionShareCode(shareSectionId));
			shareMenu.add(copyCode);
			JMenuItem copyAll = new JMenuItem("Copy share code (all sections)");
			copyAll.addActionListener(e -> panel.copyAllSectionsShareCode());
			shareMenu.add(copyAll);
			if (panel.isSavedPlansAvailable())
			{
				JMenuItem savePlan = new JMenuItem("Save share code...");
				savePlan.addActionListener(e -> panel.saveSectionPlan(shareSectionId));
				shareMenu.add(savePlan);
				JMenuItem saveAll = new JMenuItem("Save share code (all sections)...");
				saveAll.addActionListener(e -> panel.saveAllSectionsPlan());
				shareMenu.add(saveAll);
			}
			menu.add(shareMenu);
			menu.addSeparator();
		}

		// View toggle: flat list vs subtle nested view. Nested view left-indents
		// each goal by its in-section dependency depth with a faint file-tree
		// guide. Available on every section, built-in or user. Rendered as a
		// checkable row (left dot when active) via the ColumnMenu adapter.
		// (The underlying flag/API is still named railView - internal rename to
		// nestedView is a tracked follow-up.)
		// Dependency nesting - per-section override of the global "Indent
		// dependencies by default" setting: inherit (Use default), nested,
		// or not nested. Mirrors the auto-archive override above.
		boolean indentDefault = config.showDependenciesIndented();
		Boolean nestedOverride = section.nestedOverride;
		JMenu nestMenu = new JMenu("Dependency nesting");
		javax.swing.JRadioButtonMenuItem nestDefault = new javax.swing.JRadioButtonMenuItem(
			"Use default (" + (indentDefault ? "nested" : "not nested") + ")", nestedOverride == null);
		nestDefault.addActionListener(e -> api.setSectionNestedOverride(section.id, null));
		nestMenu.add(nestDefault);

		javax.swing.JRadioButtonMenuItem nestOn = new javax.swing.JRadioButtonMenuItem(
			"Nested", Boolean.TRUE.equals(nestedOverride));
		nestOn.addActionListener(e -> api.setSectionNestedOverride(section.id, Boolean.TRUE));
		nestMenu.add(nestOn);

		javax.swing.JRadioButtonMenuItem nestOff = new javax.swing.JRadioButtonMenuItem(
			"Not nested", Boolean.FALSE.equals(nestedOverride));
		nestOff.addActionListener(e -> api.setSectionNestedOverride(section.id, Boolean.FALSE));
		nestMenu.add(nestOff);

		menu.add(nestMenu);

		// Change Color is available on every section, built-in or user.
		JMenuItem changeColor = new JMenuItem("Change Color");
		changeColor.addActionListener(e -> dialogFactory.showSectionColorDialog(section));
		menu.add(changeColor);

		// User-section-only items: rename, move up/down, delete.
		if (!section.builtIn)
		{
			JMenuItem rename = new JMenuItem("Rename");
			rename.addActionListener(e -> dialogFactory.showRenameSectionDialog(section));
			menu.add(rename);

			// Completed goals - per-section override of the global auto-archive
			// default: inherit (Use default), always archive out to Completed, or
			// always keep inline as a checklist.
			boolean archiveDefault = api.isAutoArchiveDefault();
			Boolean archiveOverride = section.autoArchiveOverride;
			JMenu completedMenu = new JMenu("Completed goals");

			javax.swing.JRadioButtonMenuItem useDefault = new javax.swing.JRadioButtonMenuItem(
				"Use default (" + (archiveDefault ? "auto-archive" : "keep inline") + ")",
				archiveOverride == null);
			useDefault.addActionListener(e -> api.setSectionAutoArchiveOverride(section.id, null));
			completedMenu.add(useDefault);

			javax.swing.JRadioButtonMenuItem archiveItem = new javax.swing.JRadioButtonMenuItem(
				"Auto-archive (move to Completed)", Boolean.TRUE.equals(archiveOverride));
			archiveItem.addActionListener(e -> api.setSectionAutoArchiveOverride(section.id, Boolean.TRUE));
			completedMenu.add(archiveItem);

			javax.swing.JRadioButtonMenuItem keepItem = new javax.swing.JRadioButtonMenuItem(
				"Keep inline (checklist)", Boolean.FALSE.equals(archiveOverride));
			keepItem.addActionListener(e -> api.setSectionAutoArchiveOverride(section.id, Boolean.FALSE));
			completedMenu.add(keepItem);

			menu.add(completedMenu);

			if (currentUserIndex > 0)
			{
				JMenuItem moveUp = new JMenuItem("Move Up");
				moveUp.addActionListener(e -> api.reorderSection(section.id, currentUserIndex - 1));
				menu.add(moveUp);
			}
			if (currentUserIndex >= 0 && currentUserIndex < userSections.size() - 1)
			{
				JMenuItem moveDown = new JMenuItem("Move Down");
				moveDown.addActionListener(e -> api.reorderSection(section.id, currentUserIndex + 1));
				menu.add(moveDown);
			}

			menu.addSeparator();

			JMenuItem delete = new JMenuItem("Delete Section");
			delete.addActionListener(e -> {
				int goalCount = 0;
				for (Goal g : goalStore.getGoals())
				{
					if (section.id.equals(g.getSectionId())) goalCount++;
				}
				String plural = goalCount == 1 ? "goal" : "goals";
				javax.swing.JCheckBox moveInstead = new javax.swing.JCheckBox(
					"Move " + (goalCount == 1 ? "it" : "them")
						+ " to Default (Incomplete/Completed) instead");
				Object[] message = goalCount > 0
					? new Object[]{
						"Delete section \"" + section.name + "\"?\n"
							+ "This also deletes its " + goalCount + " " + plural
							+ ". (Undo restores everything.)",
						moveInstead}
					: new Object[]{"Delete section \"" + section.name + "\"?"};
				int confirm = JOptionPane.showConfirmDialog(
					panel,
					message,
					"Delete Section",
					JOptionPane.YES_NO_OPTION,
					JOptionPane.WARNING_MESSAGE
				);
				if (confirm == JOptionPane.YES_OPTION)
				{
					api.deleteSection(section.id, moveInstead.isSelected());
				}
			});
			menu.add(delete);
		}

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger()) showSectionMenu(row, e.getX(), e.getY());
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger()) showSectionMenu(row, e.getX(), e.getY());
			}

			// Render through the ColumnMenu so section menus match the goal
			// menus' click-driven, hover-stable column style. The menu is built
			// as a JPopupMenu above; MenuTreeAdapter walks it into the new model.
			private void showSectionMenu(java.awt.Component invoker, int x, int y)
			{
				com.goalplanner.ui.columnmenu.ColumnMenu.show(invoker, x, y,
					com.goalplanner.ui.columnmenu.MenuTreeAdapter.fromPopup(menu));
			}
		});
	}

	// --------------------------------------------------------------
	// Bulk in-section move helpers
	// --------------------------------------------------------------
	//
	// All four operations preserve the relative priority order of the
	// selected block. The "iteration index as min/max bound" trick
	// (target = max(currentRel - 1, i) for up; target = min(currentRel
	// + 1, sectionSize - 1 - i) for down) prevents one move from
	// displacing another selected goal - when the goal directly above
	// (or below) is also selected, the bound clamps the move out and
	// the relative order is preserved.

	/**
	 * Compute target positions UP-FRONT in section-relative space, then
	 * apply via positionGoalInSection. We use planned positions rather
	 * than re-reading goal.getPriority() between calls because earlier
	 * moves shift other goals' priorities and the math for "what should
	 * this goal's new position be" is much simpler before any moves.
	 */
	static List<Integer> planMoveUp(List<Integer> sectionRelativeAsc, int sectionSize)
	{
		List<Integer> targets = new ArrayList<>(sectionRelativeAsc.size());
		for (int i = 0; i < sectionRelativeAsc.size(); i++)
		{
			int currentRel = sectionRelativeAsc.get(i);
			targets.add(Math.max(currentRel - 1, i));
		}
		return targets;
	}

	static List<Integer> planMoveDown(List<Integer> sectionRelativeAsc, int sectionSize)
	{
		// Process desc by reversing the input. Iteration index i maps to
		// "this many already-processed selecteds are below us in the
		// final order", so the floor for our target is sectionSize-1-i.
		List<Integer> reversed = new ArrayList<>(sectionRelativeAsc);
		Collections.reverse(reversed);
		List<Integer> targetsDesc = new ArrayList<>(reversed.size());
		for (int i = 0; i < reversed.size(); i++)
		{
			int currentRel = reversed.get(i);
			targetsDesc.add(Math.min(currentRel + 1, sectionSize - 1 - i));
		}
		// Reverse back to ASC alignment.
		Collections.reverse(targetsDesc);
		return targetsDesc;
	}

	private void bulkMoveToTop(List<Goal> selected, String sectionId, int sectionStart)
	{
		List<Goal> sorted = new ArrayList<>(selected);
		sorted.sort(java.util.Comparator.comparingInt(Goal::getPriority));
		api.beginCompound("Move " + sorted.size() + " to top");
		try
		{
			for (int i = 0; i < sorted.size(); i++)
			{
				api.positionGoalInSection(sorted.get(i).getId(), sectionId, i);
			}
		}
		finally { api.endCompound(); }
	}

	private void bulkMoveToBottom(List<Goal> selected, String sectionId,
								  int sectionStart, int sectionEnd)
	{
		int sectionSize = sectionEnd - sectionStart + 1;
		List<Goal> sortedDesc = new ArrayList<>(selected);
		sortedDesc.sort(java.util.Comparator.comparingInt(Goal::getPriority).reversed());
		api.beginCompound("Move " + sortedDesc.size() + " to bottom");
		try
		{
			for (int i = 0; i < sortedDesc.size(); i++)
			{
				api.positionGoalInSection(sortedDesc.get(i).getId(), sectionId,
					sectionSize - 1 - i);
			}
		}
		finally { api.endCompound(); }
	}

	private void bulkMoveUp(List<Goal> selected, String sectionId, int sectionStart)
	{
		List<Goal> sorted = new ArrayList<>(selected);
		sorted.sort(java.util.Comparator.comparingInt(Goal::getPriority));
		List<Integer> currentRels = new ArrayList<>();
		for (Goal g : sorted) currentRels.add(g.getPriority() - sectionStart);
		List<Integer> targets = planMoveUp(currentRels, /*sectionSize=*/0);
		api.beginCompound("Move " + sorted.size() + " up");
		try
		{
			for (int i = 0; i < sorted.size(); i++)
			{
				int target = targets.get(i);
				if (target != currentRels.get(i))
				{
					api.positionGoalInSection(sorted.get(i).getId(), sectionId, target);
				}
			}
		}
		finally { api.endCompound(); }
	}

	private void bulkMoveDown(List<Goal> selected, String sectionId,
							  int sectionStart, int sectionEnd)
	{
		int sectionSize = sectionEnd - sectionStart + 1;
		List<Goal> sorted = new ArrayList<>(selected);
		sorted.sort(java.util.Comparator.comparingInt(Goal::getPriority));
		List<Integer> currentRels = new ArrayList<>();
		for (Goal g : sorted) currentRels.add(g.getPriority() - sectionStart);
		List<Integer> targets = planMoveDown(currentRels, sectionSize);
		// Process desc so the bottommost goal moves first - it can't be
		// blocked by a goal-being-moved sitting where it wants to land.
		api.beginCompound("Move " + sorted.size() + " down");
		try
		{
			for (int i = sorted.size() - 1; i >= 0; i--)
			{
				int target = targets.get(i);
				if (target != currentRels.get(i))
				{
					api.positionGoalInSection(sorted.get(i).getId(), sectionId, target);
				}
			}
		}
		finally { api.endCompound(); }
	}

	/** Route "Add requirements" to the quest or diary seeder. */
	/** Which requirement source a goal's "Add requirements" menu seeds from. */
	private enum ReqKind { QUEST, DIARY, BOSS }

	private void seedReqs(String goalId, boolean includeMet, ReqKind kind)
	{
		// The "Incomplete only" variant resolves against LIVE player state
		// (getRealSkillLevel / Quest.getState / account metrics), which asserts
		// the client thread - run on it. ("All" uses floor lookups and is
		// thread-agnostic, but routing both keeps one path.) Store mutation on
		// the client thread is fine (trackers already mutate there); the
		// resulting onGoalsChanged marshals the rebuild back to the EDT.
		panel.runOnClientThread(() -> {
			switch (kind)
			{
				case DIARY:
					api.seedDiaryRequirementsForGoal(goalId, includeMet);
					break;
				case BOSS:
					api.seedBossRequirementsForGoal(goalId, includeMet);
					break;
				default:
					api.seedRequirementsForGoal(goalId, includeMet);
			}
		});
	}

	/** Internal diary tier from a diary goal's "&lt;Tier&gt; Achievement Diary" description, or null. */
	private static com.goalplanner.data.AchievementDiaryData.Tier parseDiaryTier(String description)
	{
		if (description == null)
		{
			return null;
		}
		for (com.goalplanner.data.AchievementDiaryData.Tier t
			: com.goalplanner.data.AchievementDiaryData.Tier.values())
		{
			if (description.startsWith(t.getDisplayName()))
			{
				return t;
			}
		}
		return null;
	}

	/**
	 * Lazily-loaded 16px Loadout Lab menu icon - a vendored copy of that
	 * plugin's sidebar art (the user's own art in both repos).
	 */
	private static javax.swing.ImageIcon loadoutLabIcon;

	private static javax.swing.ImageIcon loadoutLabIcon()
	{
		if (loadoutLabIcon == null)
		{
			loadoutLabIcon = new javax.swing.ImageIcon(
				net.runelite.client.util.ImageUtil.resizeImage(
					net.runelite.client.util.ImageUtil.loadImageResource(
						GoalContextMenuBuilder.class, "/icons/loadout_lab.png"),
					16, 16));
		}
		return loadoutLabIcon;
	}

	/** Chunk sizes offered per skill goal, in XP. */
	private static final int[] XP_CHUNKS = {10_000, 50_000, 100_000, 300_000, 1_000_000};

	/** Chunk sizes offered per activity goal, in kills. */
	private static final int[] KILL_CHUNKS = {5, 10, 20, 50};

	/**
	 * "Repeatable goal" submenu: turn a long grind into a repeating slice of
	 * itself. A skill goal offers XP chunks; an item goal offers kill counts at
	 * whatever activity drops it.
	 *
	 * <p>Absent, not disabled, when nothing can be derived - roughly half of all
	 * items come from shops or skilling and have no activity to farm, and a
	 * greyed-out entry would imply the feature is broken rather than
	 * inapplicable.
	 */
	private void addRepeatableGoalMenu(JPopupMenu menu, com.goalplanner.model.Goal goal)
	{
		java.util.List<com.goalplanner.model.RepeatPeriod> periods = java.util.List.of(
			com.goalplanner.model.RepeatPeriod.DAILY,
			com.goalplanner.model.RepeatPeriod.WEEKLY,
			com.goalplanner.model.RepeatPeriod.MONTHLY);

		// Already a repeatable goal? Offer to change what it asks for, not to
		// spawn a second one off it - deriving a chunk from a chunk is not a
		// thing, and it is the wrong affordance on a card the player is looking
		// at because they want to adjust it.
		if (goal.getRepeatChunk() > 0)
		{
			addEditRepeatMenu(menu, goal, periods);
			return;
		}

		if (goal.getType() == GoalType.SKILL && goal.getSkillName() != null)
		{
			JMenu root = new JMenu("Repeatable goal");
			for (com.goalplanner.model.RepeatPeriod period : periods)
			{
				JMenu perPeriod = new JMenu(period.getLabel());
				for (int chunk : XP_CHUNKS)
				{
					JMenuItem item = new JMenuItem(
						com.goalplanner.util.FormatUtil.formatNumber(chunk) + " XP");
					// Reads live XP, which is a client-thread op - the dev
					// harness runs with -ea, so an EDT read asserts and the
					// creation would silently return null.
					item.addActionListener(e -> panel.runOnClientThread(() ->
						api.createDerivedRepeatGoal(goal.getId(), period, chunk, null)));
					perPeriod.add(item);
				}
				perPeriod.add(customItem(goal, period, null, "XP"));
				root.add(perPeriod);
			}
			menu.add(root);
			return;
		}

		if (goal.getItemId() <= 0)
		{
			return;
		}
		java.util.List<com.goalplanner.data.ItemActivityResolver.Activity> activities =
			com.goalplanner.data.ItemActivityResolver.resolve(goal.getItemId());
		if (activities.isEmpty())
		{
			return;
		}

		JMenu root = new JMenu("Repeatable goal");
		for (com.goalplanner.data.ItemActivityResolver.Activity activity : activities)
		{
			// One entry per activity: a shared collection-log slot can mean
			// several bosses (Dagannoth Kings, the Moons), and the player has to
			// pick which one they are actually going to farm.
			JMenu perActivity = activities.size() == 1
				? root
				: new JMenu(activity.getName());
			for (com.goalplanner.model.RepeatPeriod period : periods)
			{
				JMenu perPeriod = new JMenu(period.getLabel());
				for (int chunk : KILL_CHUNKS)
				{
					JMenuItem item = new JMenuItem(chunk + " kills");
					// Reads the boss kill-count varp: client-thread only.
					item.addActionListener(e -> panel.runOnClientThread(() ->
						api.createDerivedRepeatGoal(
							goal.getId(), period, chunk, activity.getName())));
					perPeriod.add(item);
				}
				perPeriod.add(customItem(goal, period, activity.getName(), "kills"));
				perActivity.add(perPeriod);
			}
			if (perActivity != root)
			{
				root.add(perActivity);
			}
		}
		menu.add(root);
	}

	/**
	 * "Custom..." entry: the presets cover the common sizes, but the right chunk
	 * depends on how long the player actually plays, which no preset can know.
	 * Rejects non-numeric and non-positive input rather than silently doing
	 * nothing - a chunk of 0 would target the current value exactly.
	 */
	private JMenuItem customItem(com.goalplanner.model.Goal goal,
		com.goalplanner.model.RepeatPeriod period, String activityName, String unit)
	{
		JMenuItem custom = new JMenuItem("Custom...");
		custom.addActionListener(e -> {
			String input = javax.swing.JOptionPane.showInputDialog(panel,
				"How much " + unit + " per " + period.getLabel().toLowerCase(java.util.Locale.ROOT)
					+ " period?",
				"Repeatable goal", javax.swing.JOptionPane.PLAIN_MESSAGE);
			if (input == null)
			{
				return; // cancelled
			}
			// Tolerate the separators people naturally type: "300,000", "300 000".
			String cleaned = input.trim().replace(",", "").replace(" ", "").replace("_", "");
			final int chunk;
			try
			{
				chunk = Integer.parseInt(cleaned);
			}
			catch (NumberFormatException ex)
			{
				javax.swing.JOptionPane.showMessageDialog(panel,
					"Enter a whole number, for example 300000.",
					"Repeatable goal", javax.swing.JOptionPane.WARNING_MESSAGE);
				return;
			}
			if (chunk <= 0)
			{
				javax.swing.JOptionPane.showMessageDialog(panel,
					"Enter an amount greater than zero.",
					"Repeatable goal", javax.swing.JOptionPane.WARNING_MESSAGE);
				return;
			}
			panel.runOnClientThread(() ->
				api.createDerivedRepeatGoal(goal.getId(), period, chunk, activityName));
		});
		return custom;
	}

	/**
	 * Edit menu for an existing repeatable goal: how often, and how much.
	 * Replaces the "create one" menu, which would otherwise offer to derive a
	 * chunk from a chunk.
	 */
	private void addEditRepeatMenu(JPopupMenu menu, com.goalplanner.model.Goal goal,
		java.util.List<com.goalplanner.model.RepeatPeriod> periods)
	{
		boolean isSkill = goal.getType() == GoalType.SKILL;
		int[] sizes = isSkill ? XP_CHUNKS : KILL_CHUNKS;
		String unit = isSkill ? "XP" : "kills";

		JMenu every = new JMenu("Repeats");
		for (com.goalplanner.model.RepeatPeriod period : periods)
		{
			JMenuItem item = new JMenuItem(
				goal.getRepeatEvery() == period ? period.getLabel() + " *" : period.getLabel());
			item.setEnabled(goal.getRepeatEvery() != period);
			item.addActionListener(e -> api.setGoalRepeat(goal.getId(), period));
			every.add(item);
		}
		menu.add(every);

		JMenu amount = new JMenu("Amount");
		for (int size : sizes)
		{
			String label = isSkill
				? com.goalplanner.util.FormatUtil.formatNumber(size) + " " + unit
				: size + " " + unit;
			JMenuItem item = new JMenuItem(goal.getRepeatChunk() == size ? label + " *" : label);
			item.setEnabled(goal.getRepeatChunk() != size);
			item.addActionListener(e -> api.setGoalRepeatChunk(goal.getId(), size));
			amount.add(item);
		}
		amount.add(customAmountItem(goal, unit));
		menu.add(amount);

		// No "Stop repeating" entry. A derived goal cannot meaningfully become a
		// one-shot - its target is a re-based arbitrary number - so the only
		// sensible outcome was deletion, and hiding a delete behind that label
		// is worse than the unconditional "Remove Goal" already at the bottom of
		// this menu.
	}

	/** "Custom..." for changing an existing repeatable goal's per-period amount. */
	private JMenuItem customAmountItem(com.goalplanner.model.Goal goal, String unit)
	{
		JMenuItem custom = new JMenuItem("Custom...");
		custom.addActionListener(e -> {
			String input = javax.swing.JOptionPane.showInputDialog(panel,
				"How much " + unit + " per period?", "Repeatable goal",
				javax.swing.JOptionPane.PLAIN_MESSAGE);
			if (input == null)
			{
				return;
			}
			String cleaned = input.trim().replace(",", "").replace(" ", "").replace("_", "");
			final int chunk;
			try
			{
				chunk = Integer.parseInt(cleaned);
			}
			catch (NumberFormatException ex)
			{
				javax.swing.JOptionPane.showMessageDialog(panel,
					"Enter a whole number, for example 300000.",
					"Repeatable goal", javax.swing.JOptionPane.WARNING_MESSAGE);
				return;
			}
			if (chunk <= 0)
			{
				javax.swing.JOptionPane.showMessageDialog(panel,
					"Enter an amount greater than zero.",
					"Repeatable goal", javax.swing.JOptionPane.WARNING_MESSAGE);
				return;
			}
			api.setGoalRepeatChunk(goal.getId(), chunk);
		});
		return custom;
	}
}
