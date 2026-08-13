package com.goalplanner.ui;

import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.model.RepeatPeriod;
import com.goalplanner.model.Tag;
import com.goalplanner.ui.dock.ActionDock;
import com.goalplanner.persistence.GoalStore;
import com.goalplanner.service.GoalReorderingService;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sidebar panel - priority list of goals with gradient cards and arrow reordering.
 */
@Slf4j
public class GoalPanel extends PluginPanel
{
	/**
	 * Discord invite for the plugin's community. Exposed via the header
	 * Options menu.
	 */
	private static final String DISCORD_URL = "https://discord.gg/CFQsA3fmh7";

	private final GoalStore goalStore;
	private final GoalReorderingService reorderingService;
	private final com.goalplanner.api.GoalPlannerApiImpl api;
	/** Permanent bottom control panel (ADR-0007). Selection-driven; the
	 *  right-click menus remain alive alongside it until parity is verified. */
	private final com.goalplanner.ui.dock.ActionDock actionDock =
		new com.goalplanner.ui.dock.ActionDock();
	/** The create sub-views the dock navigates between (ADR-0008, notes 2 + 3). */
	private enum CreateNav { GRID, FORM, SECTION_NEW, SECTION_PICK }
	/** Sub-step inside a tall type's FORM (SKILL / BOSS / ITEM_GRIND): PICKER =
	 *  choose the skill / boss / item, DETAILS = fill in the target + options for
	 *  the chosen entry. Non-tall types render DETAILS directly (no picker). */
	private enum CreateStep { PICKER, DETAILS }
	/** Create-surface navigation (ADR-0008), read by {@link #refreshDock()}.
	 *  GRID = the 8-tile type grid; FORM = {@link #dockCreateType}'s create form;
	 *  SECTION_NEW = the in-dock new-section form (note 2); SECTION_PICK = the
	 *  landing-section chooser for a pending goal create (note 3). Orthogonal to
	 *  the selection-driven {@code DockContext}, so it lives here rather than in
	 *  the pure state resolver. Reset whenever a selection exists (the dock leaves
	 *  the create surface for the action strips). */
	private CreateNav dockCreateNav = CreateNav.GRID;
	/** Whether the user has opened the create surface above the permanent footer
	 *  (ADR-0008 refinement). The footer's Create Goal / Create Section buttons are
	 *  always visible; this tracks whether the surface ABOVE them is expanded while
	 *  nothing is selected. A selection auto-expands regardless; returning to EMPTY
	 *  (deselect) rests back to false, so {@link #refreshDock()} shows just the
	 *  footer. Preserved across create navigation so a tile tap does not collapse. */
	private boolean dockCreateOpen = false;
	/** Which type's form to show when {@link #dockCreateNav} is FORM. */
	private com.goalplanner.model.GoalType dockCreateType = null;
	/** A validated goal-create awaiting its landing section (note 3): the consumer
	 *  performs the actual create + move once the user picks a sectionId in the
	 *  SECTION_PICK sub-view. Cleared once run or when navigation leaves the flow. */
	private java.util.function.Consumer<String> dockPendingCreate = null;
	/** Prefill for a freshly-opened create form (note 5): "Make repeatable" on a
	 *  plain grind hands off to the create flow pre-seeded for a repeatable goal.
	 *  Consumed once when the matching form builds, then cleared. */
	private static final class CreateSeed
	{
		net.runelite.api.Skill skill;
		String bossName;
		boolean repeatable;
		Integer targetXp;
		Integer targetCount;
	}
	private CreateSeed dockCreateSeed = null;
	/** Whether the create surface is currently mounted in the dock, and for which
	 *  nav/type - lets {@link #refreshDock()} skip rebuilding it on unrelated
	 *  refreshes so in-progress form input is not wiped. */
	private boolean dockCreateMounted = false;
	private CreateNav dockCreateMountedNav = null;
	private com.goalplanner.model.GoalType dockCreateMountedType = null;
	/** The current sub-step of a tall type's FORM (default PICKER) and the pick
	 *  stashed by the picker screen, read by the DETAILS screen. Reset whenever the
	 *  create flow leaves FORM or the type changes (see {@link #navigateCreate}). */
	private CreateStep dockCreateStep = CreateStep.PICKER;
	private CreateStep dockCreateMountedStep = null;
	private net.runelite.api.Skill dockPickedSkill = null;
	private String dockPickedBoss = null;
	private int dockPickedItemId = -1;
	private String dockPickedItemName = null;
	/** Whether the unified EDIT form (ADR-0008) is currently mounted in the dock,
	 *  and for which goal - lets {@link #refreshDock()} skip rebuilding it while
	 *  the same goal stays selected, so an in-progress field edit is not wiped
	 *  out from under the cursor. Cleared by lifecycle actions that change the
	 *  goal's structure so the form re-renders to reflect them. */
	private boolean dockEditMounted = false;
	private String dockEditMountedGoalId = null;
	/** Which edit-chip drill-in group is open (note 6), or null for the top-level
	 *  Data/Relations/Actions group chips. Reset to null whenever a different goal
	 *  mounts, so a new selection always starts at the top level. */
	private EditGroup dockEditGroup = null;
	/** Transient "edit goal" overlay (read-only-selected pass): the id of the goal
	 *  whose CREATE-style form is mounted in UPDATE mode, or null when the Selected
	 *  view shows its normal read-only summary. Mirrors the color / tag / share
	 *  overlays - the goal stays selected underneath, and closing the overlay
	 *  remounts the Selected view. The create builders read it through
	 *  {@link #editingGoal()}: when non-null they pre-fill from the goal and their
	 *  primary button UPDATES it instead of creating a second goal. */
	private String dockEditFormGoalId = null;
	private boolean dockEditFormMounted = false;
	/** The selection a transient overlay was opened against, so a selection change
	 *  can drop it (see {@link #refreshDock()}). */
	private String dockOverlaySelectionKey = "";
	/** The currently selected SECTION's id (dock SECTION state), or null. Mutually
	 *  exclusive with the goal selection: selecting a section clears the goal
	 *  selection and vice-versa (enforced in {@link #refreshDock()}). */
	private String selectedSectionId = null;
	/** When a section's "Add goal" opened the in-dock create flow (Task 1), the id
	 *  of that landing section: the create flow SKIPS the section-pick step and
	 *  lands the created goal directly here. Consumed by {@link #goToSectionPick}
	 *  and cleared whenever the create surface leaves (a selection or a fresh
	 *  footer-create). Null = the normal flow that prompts for a section. */
	private String dockCreateTargetSection = null;
	/** Which section drill-in group is open in the SECTION dock (null = top level).
	 *  Reset when a different section mounts, so a new selection starts at top. */
	private SectionGroup dockSectionGroup = null;
	/** Whether the SECTION surface is mounted in the dock and for which section -
	 *  lets {@link #refreshDock()} skip rebuilding it while the same section stays
	 *  selected. */
	private boolean dockSectionMounted = false;
	private String dockSectionMountedId = null;
	private SectionGroup dockSectionMountedGroup = null;
	/** Which surface the in-dock color picker returns to when a swatch is tapped
	 *  (or Back is pressed): the GOAL edit form, the MULTI action strips, or the
	 *  SECTION dock. Null = the color overlay is not active. */
	private enum ColorReturn { GOAL, MULTI, SECTION }
	/** Marker {@link #dockColorTarget} value for the MULTI bulk-recolor overlay
	 *  (the selection is read live from {@code api.getSelectedGoalIds()}); a
	 *  control char so it can never collide with a real goal/section id. */
	private static final String COLOR_TARGET_MULTI = "__GP_MULTI__";
	/** Transient in-dock color-picker overlay (inline-color pass). When
	 *  {@link #dockColorReturn} is non-null the dock mounts {@link #buildColorSurface}
	 *  above the permanent footer instead of the normal edit/multi/section surface;
	 *  tapping a swatch applies immediately and returns to the surface named by
	 *  {@link #dockColorReturn}. {@link #dockColorTarget} holds the goal id (GOAL),
	 *  the section id (SECTION), or {@link #COLOR_TARGET_MULTI} (MULTI). */
	private ColorReturn dockColorReturn = null;
	private String dockColorTarget = null;
	private boolean dockColorMounted = false;
	/** Which inline tag surface is mounted: {@link TagMode#ADD} (existing-tag
	 *  chips + a new-tag field) or {@link TagMode#REMOVE} (removable-tag chips).
	 *  Null = no tag overlay is active (mirrors {@link #dockColorReturn}). */
	private enum TagMode { ADD, REMOVE }
	/** Which surface the inline tag overlay returns to when Back is pressed (or a
	 *  chip applies): the GOAL edit form or the MULTI action strips. */
	private enum TagReturn { GOAL, MULTI }
	/** Marker {@link #dockTagTarget} value for the MULTI bulk-tag overlay (the
	 *  selection is read live from {@code api.getSelectedGoalIds()}); a control
	 *  char so it can never collide with a real goal id. */
	private static final String TAG_TARGET_MULTI = "__GP_MULTI_TAG__";
	/** Transient in-dock tag overlay (inline-tag pass), mirroring the color
	 *  picker. When {@link #dockTagMode} is non-null the dock mounts
	 *  {@link #buildTagSurface} above the permanent footer instead of the normal
	 *  edit/multi surface; the underlying selection is left intact.
	 *  {@link #dockTagTarget} holds the goal id (GOAL) or {@link #TAG_TARGET_MULTI}
	 *  (MULTI). */
	private TagMode dockTagMode = null;
	private TagReturn dockTagReturn = null;
	private String dockTagTarget = null;
	private boolean dockTagMounted = false;
	/** Which target the inline Share surface (share-inline pass) encodes: GOALS =
	 *  a captured goal-id list (the single-goal edit share OR the multi selection),
	 *  SECTION = one section, ALL = every user section. Null = the Share overlay is
	 *  not active (mirrors {@link #dockColorReturn}). The ids/section are captured at
	 *  open time so the surface has no live-selection dependency; closing remounts
	 *  whichever surface it was opened from. */
	private enum ShareScope { GOALS, SECTION, ALL }
	private ShareScope dockShareScope = null;
	private java.util.List<String> dockShareGoalIds = null;
	private String dockShareSectionId = null;
	private boolean dockShareMounted = false;
	/** Inline Import overlay (import-inline pass): a paste-a-code surface mounted
	 *  above the footer, reached from the create grid (also still on the header
	 *  Options popup). No target to go stale. */
	private boolean dockImportActive = false;
	private boolean dockImportMounted = false;
	/** Inline Saved Plans overlay (saved-goals-inline pass): the saved-plans list
	 *  (Load / Delete per row) mounted above the footer, reached from the create
	 *  grid (also still on the header Options popup). */
	private boolean dockSavedActive = false;
	private boolean dockSavedMounted = false;
	/** Which flavour of the inline "pick a section" overlay (inline-move pass) is
	 *  mounted: MOVE / COPY act on one goal (the GOAL edit surface), BULK_MOVE /
	 *  BULK_COPY on the captured multi-selection. Null = the overlay is not active
	 *  (mirrors {@link #dockColorReturn}). */
	private enum MoveMode { MOVE, COPY, BULK_MOVE, BULK_COPY }
	/** Transient inline move/copy-to-section overlay (inline-move pass). It replaces
	 *  the old {@code dockChooser} JOptionPane popups: when {@link #dockMoveMode} is
	 *  non-null the dock mounts {@link #buildMoveSurface} above the permanent footer,
	 *  leaving the underlying selection intact so picking (or Back) returns to it.
	 *  {@link #dockMoveGoalIds} is captured at open time. */
	private MoveMode dockMoveMode = null;
	private java.util.LinkedHashSet<String> dockMoveGoalIds = null;
	private boolean dockMoveMounted = false;
	private final com.goalplanner.GoalPlannerConfig config;
	private final SkillIconManager skillIconManager;
	private final ItemManager itemManager;
	private final net.runelite.client.game.SpriteManager spriteManager;
	private final ItemSearchRequest itemSearchCallback;
	private final GoalReorderController reorderController;
	private final GoalDialogFactory dialogFactory;
	private final GoalContextMenuBuilder contextMenuBuilder;

	/**
	 * Callback the panel uses to ask the plugin to open the in-game chatbox
	 * item search and create an item goal. The plugin owns the chatbox + the
	 * client thread; the panel owns the section/position context. Passing
	 * both pieces of state through the callback lets the plugin place the
	 * created goal in the right slot - without this, item goals always
	 * landed in the default Incomplete section regardless of which section
	 * the user right-clicked from.
	 */
	@FunctionalInterface
	public interface ItemSearchRequest
	{
		/**
		 * @param qty               target quantity for the new item goal
		 * @param preferredSectionId section the goal should land in, or null
		 *                           for the default Incomplete section
		 * @param positionInSection in-section index to place the goal at,
		 *                           or -1 for "append to bottom"
		 */
		void accept(int qty, String preferredSectionId, int positionInSection);
	}
	private Client client;
	private final JPanel goalListPanel;
	private final Map<String, GoalCard> cardMap = new HashMap<>();
	/** Per-card render signature from the LAST rebuild (goal id → signature). A
	 *  card whose signature is unchanged is reused as-is instead of reconstructed,
	 *  cutting the dominant per-card Swing-construction cost on large rebuilds. */
	private final Map<String, String> cardSig = new HashMap<>();
	/** Section header rows by section id - selection changes refresh their
	 *  select-all toggle without a full rebuild (mirrors cardMap). */
	private final java.util.List<SectionHeaderRow> headerRows = new java.util.ArrayList<>();
	/** Free-text filter applied to the goal list. Empty = show all. */
	private String searchFilter = "";
	/** Most recent simple-click goal id, used as the anchor for shift-click range
	 *  selection. Cleared on rebuilds when the goal no longer exists. */
	private String selectionAnchorId = null;
	/** Source goal ids the user initiated a relation-pick from. Empty when
	 *  not in relation-pick mode. The single-goal "Requires..."/"Required
	 *  by..." path adds one id; the bulk Customize > Relations path adds
	 *  every selected goal. Cleared on target click, cancel, or ESC. */
	java.util.Set<String> pendingRelationSourceIds = new java.util.LinkedHashSet<>();
	/** Direction flag for relation-pick mode. When true, each clicked
	 *  target becomes a REQUIREMENT of every source (edge source → target).
	 *  When false, each source becomes a DEPENDENT of the target (edge
	 *  target → source). */
	private boolean pendingRelationSourceRequiresTarget = true;
	/** Instruction banner shown at the top of the panel while
	 *  relation-pick mode is active. Hidden otherwise. */
	private JPanel relationModeBanner;
	private JLabel relationModeLabel;
	/** Id of the goal the user is repositioning via the "Move to..."
	 *  click-mode picker, or null when not in move mode. The next click on
	 *  another goal card inserts the source above that target; clicking the
	 *  in-mode "New Section" row creates a new section and moves there. */
	String pendingMoveSourceId = null;
	/** Instruction banner for move-pick mode. Distinct blue background so
	 *  it can't be confused with the orange relation banner. */
	private JPanel moveModeBanner;
	private JLabel moveModeLabel;
	/** Transient, non-modal info banner (green). Used to surface a just-created
	 *  goal that was already complete on add - it lands in the built-in Completed
	 *  section, which the user may have collapsed, so it reads as "didn't show up".
	 *  Auto-dismisses via {@link #infoNoticeTimer}. */
	private JPanel infoNoticeBanner;
	private JLabel infoNoticeLabel;
	private javax.swing.Timer infoNoticeTimer;
	/** A goal just created via the dock create flow, to reveal once it settles
	 *  into its section (armed by {@link #armCreateReveal()}). A complete-on-add
	 *  goal (diary/quest/CA/boss/item/skill already at target) reconciles into the
	 *  Completed section a moment later; when it does, {@link #maybeRevealPendingCreate()}
	 *  expands that section, scrolls the card into view, and shows the info banner.
	 *  Cleared once revealed or when the arming window lapses. */
	private String pendingRevealGoalId;
	private long pendingRevealArmedAt;
	/** How long a pending create-reveal stays armed. Long enough to outlast the
	 *  tracker drain + reconcile + rebuild that completes a complete-on-add goal,
	 *  short enough that an ordinary incomplete goal never lingers to fire later. */
	private static final long REVEAL_WINDOW_MS = 8_000L;
	/** Toolbar undo/redo buttons. Refreshed on every rebuild. */
	private JButton undoButton;
	private JButton redoButton;

	// Share support - injected via setShareSupport() after construction (like
	// setClient). Null until then; the Options menu omits share entries if unset.
	private com.goalplanner.share.ShareCodec shareCodec;
	private com.goalplanner.persistence.SavedPlanStore savedPlanStore;
	private java.util.function.Supplier<String> playerNameSupplier;

	public GoalPanel(GoalStore goalStore, SkillIconManager skillIconManager, ItemManager itemManager,
					 net.runelite.client.game.SpriteManager spriteManager,
					 com.goalplanner.api.GoalPlannerApiImpl api,
					 GoalReorderingService reorderingService,
					 ItemSearchRequest itemSearchCallback,
					 com.goalplanner.GoalPlannerConfig config)
	{
		super(false);
		this.goalStore = goalStore;
		this.reorderingService = reorderingService;
		this.api = api;
		this.config = config;
		this.skillIconManager = skillIconManager;
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		this.itemSearchCallback = itemSearchCallback;
		this.reorderController = new GoalReorderController(api, goalStore);
		this.dialogFactory = new GoalDialogFactory(api, goalStore, skillIconManager,
			itemManager, spriteManager, itemSearchCallback, this);
		this.contextMenuBuilder = new GoalContextMenuBuilder(api, goalStore, this,
			dialogFactory, reorderController, config);

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Header with add button
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(new EmptyBorder(8, 8, 8, 8));

		JLabel title = new JLabel("Goal Planner");
		title.setForeground(Color.WHITE);
		title.setFont(PanelFonts.derive(Font.BOLD, 14f));

		JPanel headerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
		headerButtons.setOpaque(false);

		// + goal and + section buttons removed. Adding is now
		// contextual via section header / goal card right-click menus.

		// Options menu - opens a small popup with plugin-wide actions
		// (Discord link, and future general options). Single-goal removal
		// is still available via right-click context menu on each card;
		// bulk / "remove all" entry points were dropped in v0.1.0 in
		// favor of relying on right-click + undo/redo for reversibility.
		JButton optionsButton = new JButton(ShapeIcons.moreDots(10, new Color(180, 180, 220)));
		optionsButton.setToolTipText("Options...");
		optionsButton.setMargin(new Insets(3, 6, 3, 6));
		optionsButton.addActionListener(e -> {
			JPopupMenu popup = new JPopupMenu();
			JMenuItem joinDiscord = new JMenuItem("Join our Discord");
			joinDiscord.addActionListener(ev -> openDiscordInvite());
			popup.add(joinDiscord);

			// Share / import (only once share support is wired by the plugin).
			if (shareCodec != null)
			{
				popup.addSeparator();

				JMenuItem importShare = new JMenuItem("Import shared goals...");
				importShare.addActionListener(ev ->
					ShareDialogs.promptImport(GoalPanel.this, api, shareCodec, savedPlanStore, this::rebuild));
				popup.add(importShare);

				if (isSavedPlansAvailable())
				{
					JMenuItem savedPlans = new JMenuItem("Saved plans...");
					savedPlans.addActionListener(ev -> openSavedPlans());
					popup.add(savedPlans);
				}
				// Section sharing lives on the section-header right-click menu
				// (consistent with goal sharing); goal sharing on goal cards.
			}

			popup.addSeparator();
			JMenuItem removeDupes = new JMenuItem("Remove duplicate goals");
			removeDupes.setToolTipText(
				"Collapse same-goal duplicates within each section (keeps the most-complete one).");
			removeDupes.addActionListener(ev -> {
				int n = api.removeDuplicateGoals();
				rebuild();
				javax.swing.JOptionPane.showMessageDialog(GoalPanel.this,
					n == 0 ? "No duplicate goals found." : "Removed " + n + " duplicate goal(s).",
					"Remove Duplicates", javax.swing.JOptionPane.INFORMATION_MESSAGE);
			});
			popup.add(removeDupes);

			// Bulk delete entry points. Both are undoable (single step), so the
			// confirm dialog notes that; "delete empty sections" is low-risk and
			// skips the prompt. (Bulk "remove all" was dropped in v0.1.0 for
			// reversibility - it's back now that each is a single undo.)
			popup.addSeparator();

			JMenuItem deleteEmptySections = new JMenuItem("Delete empty sections");
			deleteEmptySections.setToolTipText("Remove sections that contain no goals. Undoable.");
			deleteEmptySections.addActionListener(ev -> {
				int n = api.removeEmptyUserSections();
				rebuild();
				javax.swing.JOptionPane.showMessageDialog(GoalPanel.this,
					n == 0 ? "No empty sections found." : "Deleted " + n + " empty section(s).",
					"Delete Empty Sections", javax.swing.JOptionPane.INFORMATION_MESSAGE);
			});
			popup.add(deleteEmptySections);

			JMenuItem deleteEverything = new JMenuItem("Delete all goals and sections");
			deleteEverything.setToolTipText("Wipe every goal and section (completed goals included). Undoable.");
			deleteEverything.addActionListener(ev -> {
				int choice = javax.swing.JOptionPane.showConfirmDialog(GoalPanel.this,
					"Delete all goals and sections?\nThis wipes every goal (completed included) and every section. This can be undone.",
					"Delete All Goals and Sections", javax.swing.JOptionPane.YES_NO_OPTION,
					javax.swing.JOptionPane.WARNING_MESSAGE);
				if (choice == javax.swing.JOptionPane.YES_OPTION)
				{
					api.removeAllGoalsAndSections();
					rebuild();
				}
			});
			popup.add(deleteEverything);

			popup.show(optionsButton, 0, optionsButton.getHeight());
		});

		JButton manageTagsButton = new JButton(ShapeIcons.tag(12, new Color(220, 180, 140)));
		manageTagsButton.setToolTipText("Manage tags");
		manageTagsButton.setMargin(new Insets(3, 6, 3, 6));
		manageTagsButton.addActionListener(e -> {
			java.awt.Window window = SwingUtilities.getWindowAncestor(GoalPanel.this);
			java.awt.Frame owner = window instanceof java.awt.Frame ? (java.awt.Frame) window : null;
			TagManagementDialog dialog = new TagManagementDialog(owner, api, skillIconManager, itemManager);
			dialog.setVisible(true);
		});

		// Undo/redo buttons. Tooltip + enable state refreshed on
		// each panel rebuild via refreshUndoRedoButtons() (called from rebuild()).
		undoButton = new JButton(ShapeIcons.undoArrow(12, new Color(180, 180, 220)));
		undoButton.setMargin(new Insets(3, 6, 3, 6));
		// refreshEditForm() drops the same-goal edit-surface remount guard so an
		// undo that restores a relation/tag/etc. re-renders the mounted Selected
		// view immediately (otherwise it stayed stale until reselect).
		undoButton.addActionListener(e -> { api.undo(); refreshEditForm(); });
		redoButton = new JButton(ShapeIcons.redoArrow(12, new Color(180, 180, 220)));
		redoButton.setMargin(new Insets(3, 6, 3, 6));
		redoButton.addActionListener(e -> { api.redo(); refreshEditForm(); });

		headerButtons.add(optionsButton);
		headerButtons.add(Box.createHorizontalStrut(6));
		headerButtons.add(undoButton);
		headerButtons.add(redoButton);
		headerButtons.add(Box.createHorizontalStrut(6));
		headerButtons.add(manageTagsButton);

		// Title in CENTER (not WEST) so it yields space to the EAST buttons and
		// clips instead of overlapping them when a large font widens it.
		header.add(title, BorderLayout.CENTER);
		header.add(headerButtons, BorderLayout.EAST);

		// Free-text search row beneath the toolbar. Filters
		// goals by name/description/tags/category/type/section title.
		JPanel searchRow = new JPanel(new BorderLayout(4, 0));
		searchRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		searchRow.setBorder(new EmptyBorder(0, 8, 8, 8));
		final javax.swing.JTextField searchField = new javax.swing.JTextField();
		searchField.setToolTipText("Search goals by name, description, tag, category, type, or section");
		// Debounce: rebuild() is a full teardown; rebuilding on every keystroke
		// makes typing janky. Coalesce rapid edits into one rebuild.
		final javax.swing.Timer searchDebounce = new javax.swing.Timer(150, e -> rebuild());
		searchDebounce.setRepeats(false);
		searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			private void update()
			{
				searchFilter = searchField.getText();
				searchDebounce.restart();
			}
			@Override public void insertUpdate(javax.swing.event.DocumentEvent e) { update(); }
			@Override public void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
			@Override public void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }
		});
		JButton clearSearchBtn = new JButton(ShapeIcons.closeX(10, new Color(200, 200, 200)));
		clearSearchBtn.setToolTipText("Clear search");
		clearSearchBtn.setMargin(new Insets(3, 6, 3, 6));
		clearSearchBtn.addActionListener(e -> searchField.setText(""));
		searchRow.add(searchField, BorderLayout.CENTER);
		searchRow.add(clearSearchBtn, BorderLayout.EAST);

		// Relation-pick mode banner. Hidden by default, shown
		// when the user initiates "Requires..." / "Required by..." from the
		// context menu. Tells the user what to do next and how to cancel.
		relationModeBanner = new JPanel(new BorderLayout());
		relationModeBanner.setBackground(new Color(0xB8, 0x60, 0x20));
		relationModeBanner.setBorder(new EmptyBorder(4, 8, 4, 8));
		relationModeLabel = new JLabel();
		relationModeLabel.setForeground(Color.WHITE);
		relationModeLabel.setFont(PanelFonts.derive(11f));
		relationModeBanner.add(relationModeLabel, BorderLayout.CENTER);
		// Use ShapeIcons.closeX for the cancel glyph rather than the
		// literal "\u2715" Unicode character - the latter renders inconsistently
		// across platforms (macOS in particular shows it as a colored
		// emoji on some default fonts).
		JButton relationCancelBtn = new JButton(ShapeIcons.closeX(10, Color.WHITE));
		relationCancelBtn.setContentAreaFilled(false);
		relationCancelBtn.setBorderPainted(false);
		relationCancelBtn.setFocusPainted(false);
		relationCancelBtn.setMargin(new Insets(0, 4, 0, 4));
		relationCancelBtn.setToolTipText("Cancel (ESC)");
		relationCancelBtn.addActionListener(e -> exitRelationMode());
		relationModeBanner.add(relationCancelBtn, BorderLayout.EAST);
		relationModeBanner.setVisible(false);

		// Move-pick mode banner. Distinct blue background so it can't be
		// confused with the relation banner. Shown when the user picks
		// "Move to..." from a goal's Customize > Move submenu.
		moveModeBanner = new JPanel(new BorderLayout());
		moveModeBanner.setBackground(new Color(0x20, 0x60, 0xB8));
		moveModeBanner.setBorder(new EmptyBorder(4, 8, 4, 8));
		moveModeLabel = new JLabel();
		moveModeLabel.setForeground(Color.WHITE);
		moveModeLabel.setFont(PanelFonts.derive(11f));
		moveModeBanner.add(moveModeLabel, BorderLayout.CENTER);
		JButton moveCancelBtn = new JButton(ShapeIcons.closeX(10, Color.WHITE));
		moveCancelBtn.setContentAreaFilled(false);
		moveCancelBtn.setBorderPainted(false);
		moveCancelBtn.setFocusPainted(false);
		moveCancelBtn.setMargin(new Insets(0, 4, 0, 4));
		moveCancelBtn.setToolTipText("Cancel (ESC)");
		moveCancelBtn.addActionListener(e -> exitMoveMode());
		moveModeBanner.add(moveCancelBtn, BorderLayout.EAST);
		moveModeBanner.setVisible(false);

		// Transient info banner (green): a non-modal, auto-dismissing notice.
		// Currently surfaces a complete-on-add goal that landed in the Completed
		// section. Hidden by default; a close X dismisses it early.
		infoNoticeBanner = new JPanel(new BorderLayout());
		infoNoticeBanner.setBackground(new Color(0x2E, 0x6B, 0x3A));
		infoNoticeBanner.setBorder(new EmptyBorder(4, 8, 4, 8));
		infoNoticeLabel = new JLabel();
		infoNoticeLabel.setForeground(Color.WHITE);
		infoNoticeLabel.setFont(PanelFonts.derive(11f));
		infoNoticeBanner.add(infoNoticeLabel, BorderLayout.CENTER);
		JButton infoNoticeCloseBtn = new JButton(ShapeIcons.closeX(10, Color.WHITE));
		infoNoticeCloseBtn.setContentAreaFilled(false);
		infoNoticeCloseBtn.setBorderPainted(false);
		infoNoticeCloseBtn.setFocusPainted(false);
		infoNoticeCloseBtn.setMargin(new Insets(0, 4, 0, 4));
		infoNoticeCloseBtn.setToolTipText("Dismiss");
		infoNoticeCloseBtn.addActionListener(e -> hideInfoNotice());
		infoNoticeBanner.add(infoNoticeCloseBtn, BorderLayout.EAST);
		infoNoticeBanner.setVisible(false);

		// Both mode banners share a vertical stack below the toolbar/search
		// row. They are mutually exclusive in practice (entering one mode
		// exits the other), but the layout supports either being shown.
		JPanel modeBanners = new JPanel();
		modeBanners.setLayout(new BoxLayout(modeBanners, BoxLayout.Y_AXIS));
		modeBanners.setOpaque(false);
		modeBanners.add(relationModeBanner);
		modeBanners.add(moveModeBanner);
		modeBanners.add(infoNoticeBanner);

		JPanel headerStack = new JPanel(new BorderLayout());
		headerStack.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel headerTop = new JPanel(new BorderLayout());
		headerTop.setBackground(ColorScheme.DARK_GRAY_COLOR);
		headerTop.add(header, BorderLayout.NORTH);
		headerTop.add(searchRow, BorderLayout.CENTER);
		headerStack.add(headerTop, BorderLayout.NORTH);
		headerStack.add(modeBanners, BorderLayout.SOUTH);

		// Scrollable goal list
		goalListPanel = new JPanel();
		goalListPanel.setLayout(new BoxLayout(goalListPanel, BoxLayout.Y_AXIS));
		goalListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		goalListPanel.setBorder(new EmptyBorder(4, 8, 8, 8));

		JScrollPane scrollPane = new JScrollPane(goalListPanel);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		add(headerStack, BorderLayout.NORTH);
		add(scrollPane, BorderLayout.CENTER);
		add(actionDock, BorderLayout.SOUTH);
		refreshDock();

		// ESC cancels whichever pick mode is active. Registered on the
		// whole panel so the key fires regardless of focus within the
		// scrollable goal list.
		javax.swing.KeyStroke escStroke =
			javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0);
		getInputMap(WHEN_IN_FOCUSED_WINDOW).put(escStroke, "cancelPickMode");
		getActionMap().put("cancelPickMode", new javax.swing.AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				if (!pendingRelationSourceIds.isEmpty()) exitRelationMode();
				else if (pendingMoveSourceId != null) exitMoveMode();
			}
		});

		rebuild();
	}

	public void setClient(Client client)
	{
		this.client = client;
		dialogFactory.setClient(client);
	}

	/**
	 * Inject the share/import support used by the Options menu. Called once at
	 * plugin start-up. {@code playerName} supplies the local RSN for the
	 * "shared by" label.
	 */
	public void setShareSupport(
		com.goalplanner.share.ShareCodec shareCodec,
		java.util.function.Supplier<String> playerName,
		com.goalplanner.persistence.SavedPlanStore savedPlanStore)
	{
		this.shareCodec = shareCodec;
		this.playerNameSupplier = playerName;
		this.savedPlanStore = savedPlanStore;
	}

	/** Loadout Lab's install/enable state, resolved fresh at menu-open time. */
	public enum LoadoutLabState
	{
		/** Some copy of the plugin is enabled - the link-in works. */
		ENABLED,
		/** Installed but no copy enabled - nudge; a hub link would be wrong advice. */
		INSTALLED_DISABLED,
		/** No plugin named Loadout Lab at all - offer its Plugin Hub page. */
		NOT_INSTALLED;

		/**
		 * Maps the two plugin-manager facts to the menu state. Enabled wins
		 * over installed (an enabled copy is necessarily installed).
		 */
		public static LoadoutLabState resolve(boolean anyCopyEnabled, boolean anyCopyInstalled)
		{
			if (anyCopyEnabled)
			{
				return ENABLED;
			}
			return anyCopyInstalled ? INSTALLED_DISABLED : NOT_INSTALLED;
		}
	}

	// Loadout Lab link-in - state supplier + search callback; null until
	// the plugin wires it (dialog-only tests leave it unset, hiding the item).
	private java.util.function.Supplier<LoadoutLabState> loadoutLabStateSupplier;
	private java.util.function.Consumer<String> searchLoadoutLabCallback;

	public void setLoadoutLabSupport(
		java.util.function.Supplier<LoadoutLabState> loadoutLabState,
		java.util.function.Consumer<String> searchLoadoutLab)
	{
		this.loadoutLabStateSupplier = loadoutLabState;
		this.searchLoadoutLabCallback = searchLoadoutLab;
	}

	/**
	 * Loadout Lab's current state, or null when the link-in isn't wired
	 * (panels built in tests) - null hides the menu entry entirely.
	 */
	LoadoutLabState loadoutLabState()
	{
		return loadoutLabStateSupplier != null && searchLoadoutLabCallback != null
			? loadoutLabStateSupplier.get() : null;
	}

	/** Ask Loadout Lab to search its optimizer for a monster (display name). */
	void searchLoadoutLab(String monsterName)
	{
		if (searchLoadoutLabCallback != null) searchLoadoutLabCallback.accept(monsterName);
	}

	/** Runs an action on the RuneLite client thread. Defaults to synchronous
	 *  (direct run) until the plugin wires the real client-thread executor, so
	 *  panels constructed in tests still function. Used by context-menu /
	 *  dialog actions that read live Client state (skill levels, quest states,
	 *  the quest DB table) - those reads ASSERT the client thread and silently
	 *  die on the EDT (see CONTRIBUTING "EDT vs client thread"). */
	private java.util.function.Consumer<Runnable> clientThreadExec = Runnable::run;

	/** Wire the client-thread executor (plugin: {@code clientThread::invokeLater}). */
	public void setClientThreadExecutor(java.util.function.Consumer<Runnable> exec)
	{
		this.clientThreadExec = exec != null ? exec : Runnable::run;
		dialogFactory.setClientThreadExecutor(this.clientThreadExec);
	}

	/** Run {@code r} on the client thread (for live Client-state reads). */
	public void runOnClientThread(Runnable r)
	{
		clientThreadExec.accept(r);
	}

	/** Blocked-badge click: seed the goal's unmet, not-in-plan requirements
	 *  ("Incomplete only"). Routes by type; runs on the client thread (live
	 *  resolution), the resulting onGoalsChanged rebuilds the panel. */
	void addMissingRequirements(com.goalplanner.api.GoalView view)
	{
		runOnClientThread(() -> {
			switch (view.type)
			{
				case "DIARY":
					api.seedDiaryRequirementsForGoal(view.id, false);
					break;
				case "BOSS":
					api.seedBossRequirementsForGoal(view.id, false);
					break;
				case "QUEST":
					api.seedRequirementsForGoal(view.id, false);
					break;
				default:
					// no requirement source
			}
		});
	}

	/** Whether share/import support is wired (used to gate share menu entries). */
	public boolean isShareAvailable()
	{
		return shareCodec != null;
	}

	/** Copy a share code for the given goals to the clipboard. */
	public void copyGoalsShareCode(java.util.List<String> goalIds)
	{
		if (shareCodec == null)
		{
			return;
		}
		ShareDialogs.copyGoals(this, api, shareCodec, playerNameSupplier, goalIds);
	}

	/** Copy a share code for a whole section to the clipboard. */
	public void copySectionShareCode(String sectionId)
	{
		if (shareCodec == null)
		{
			return;
		}
		ShareDialogs.copySection(this, api, shareCodec, playerNameSupplier, sectionId);
	}

	/** Copy one share code carrying every user section (v2 multi-section). */
	public void copyAllSectionsShareCode()
	{
		if (!isShareAvailable())
		{
			return;
		}
		ShareDialogs.copyAllSections(this, api, shareCodec, playerNameSupplier);
	}

	/** Whether the Saved Plans library is wired (gates save/library menu entries). */
	public boolean isSavedPlansAvailable()
	{
		return shareCodec != null && savedPlanStore != null;
	}

	/** Bookmark a section's share code into the Saved Plans library. */
	public void saveSectionPlan(String sectionId)
	{
		if (!isSavedPlansAvailable())
		{
			return;
		}
		ShareDialogs.savePlanForSection(this, api, shareCodec, playerNameSupplier, savedPlanStore, sectionId);
	}

	/** Bookmark a selection's share code into the Saved Plans library. */
	public void saveGoalsPlan(java.util.List<String> goalIds)
	{
		if (!isSavedPlansAvailable())
		{
			return;
		}
		ShareDialogs.savePlanForGoals(this, api, shareCodec, playerNameSupplier, savedPlanStore, goalIds);
	}

	/** Bookmark an all-sections share code into the Saved Plans library. */
	public void saveAllSectionsPlan()
	{
		if (!isSavedPlansAvailable())
		{
			return;
		}
		ShareDialogs.savePlanForAllSections(this, api, shareCodec, playerNameSupplier, savedPlanStore);
	}

	/** Open the Saved Plans library manager. */
	public void openSavedPlans()
	{
		if (!isSavedPlansAvailable())
		{
			return;
		}
		SavedPlansDialog.open(this, api, shareCodec, savedPlanStore, this::rebuild);
	}

	/**
	 * Lightweight selection refresh - updates card borders without
	 * rebuilding the entire panel. O(cards) repaint vs O(goals * sections)
	 * full rebuild.
	 */
	/**
	 * Incrementally update progress on specific cards without a full rebuild.
	 * O(dirtyIds) - looks up each card in the map and refreshes its view.
	 * Falls back to full rebuild if a card isn't found (goal was added/removed).
	 */
	public void refreshProgress(java.util.Set<String> dirtyGoalIds)
	{
		if (dirtyGoalIds == null || dirtyGoalIds.isEmpty()) return;
		for (String goalId : dirtyGoalIds)
		{
			GoalCard card = cardMap.get(goalId);
			if (card == null)
			{
				// Card not in map - goal was added/removed, need full rebuild
				rebuild();
				return;
			}
			com.goalplanner.api.GoalView view = api.queryGoalView(goalId);
			if (view == null)
			{
				// Goal was removed - need full rebuild
				rebuild();
				return;
			}
			card.update(view);
		}
	}

	public void refreshSelection()
	{
		java.util.Set<String> selected = api.getSelectedGoalIds();
		for (java.util.Map.Entry<String, GoalCard> entry : cardMap.entrySet())
		{
			entry.getValue().setSelected(selected.contains(entry.getKey()));
		}
		for (SectionHeaderRow row : headerRows)
		{
			row.refreshSelectToggle();
		}
		refreshUndoRedoButtons();
		refreshDock();
	}

	/** Show the transient green info banner with {@code msg} and (re)start its
	 *  auto-dismiss timer. Non-modal - never blocks the panel. */
	private void showInfoNotice(String msg)
	{
		infoNoticeLabel.setText(msg);
		infoNoticeBanner.setVisible(true);
		infoNoticeBanner.revalidate();
		infoNoticeBanner.repaint();
		if (infoNoticeTimer != null)
		{
			infoNoticeTimer.stop();
		}
		infoNoticeTimer = new javax.swing.Timer(6_000, e -> hideInfoNotice());
		infoNoticeTimer.setRepeats(false);
		infoNoticeTimer.start();
	}

	private void hideInfoNotice()
	{
		if (infoNoticeTimer != null)
		{
			infoNoticeTimer.stop();
		}
		infoNoticeBanner.setVisible(false);
		infoNoticeBanner.revalidate();
		infoNoticeBanner.repaint();
	}

	/** Arm a reveal for the goal a dock create just made. {@code selectAfterCreate}
	 *  has already left it as the sole selection, so that is the goal to watch. A
	 *  complete-on-add goal reconciles into the Completed section a moment later
	 *  (after the tracker drain); {@link #maybeRevealPendingCreate()} surfaces it
	 *  when it lands. An ordinary incomplete create just lapses out of the window,
	 *  silently. */
	private void armCreateReveal()
	{
		java.util.Set<String> selected = api.getSelectedGoalIds();
		if (selected.size() == 1)
		{
			pendingRevealGoalId = selected.iterator().next();
			pendingRevealArmedAt = System.currentTimeMillis();
		}
	}

	/** If a just-created goal has settled into the built-in Completed section,
	 *  make it discoverable: expand that section if the user had it collapsed,
	 *  scroll the card into view, and show a brief non-modal notice. Runs after a
	 *  rebuild (cards laid out). No-op until the goal is complete AND reconciled
	 *  into Completed; clears itself once revealed or once the arming window lapses
	 *  so a plain incomplete goal never triggers it. */
	private void maybeRevealPendingCreate()
	{
		final String id = pendingRevealGoalId;
		if (id == null)
		{
			return;
		}
		if (System.currentTimeMillis() - pendingRevealArmedAt > REVEAL_WINDOW_MS)
		{
			pendingRevealGoalId = null;
			return;
		}
		com.goalplanner.api.GoalView view = api.queryGoalView(id);
		if (view == null)
		{
			pendingRevealGoalId = null;
			return;
		}
		if (view.completedAt <= 0)
		{
			// Not complete (yet). Keep armed: if it is a complete-on-add goal the
			// tracker will finish it and trigger another rebuild; if not, the
			// window lapses and this clears silently.
			return;
		}
		// Complete. Only surface the built-in Completed landing - a complete goal
		// kept inline in its own section is already visible where the user put it.
		String completedSectionId = null;
		boolean completedCollapsed = false;
		for (com.goalplanner.api.SectionView s : api.queryAllSections())
		{
			if ("COMPLETED".equals(s.kind))
			{
				completedSectionId = s.id;
				completedCollapsed = s.collapsed;
				break;
			}
		}
		if (completedSectionId == null || !completedSectionId.equals(view.sectionId))
		{
			// Not (yet) in the Completed section - either reconcile has not run or
			// it archives inline. Keep armed within the window.
			return;
		}
		if (completedCollapsed)
		{
			// Expand so the card renders, then rebuild; the reveal re-runs from the
			// rebuild's tail and finds the card this time.
			api.setSectionCollapsed(completedSectionId, false);
			rebuild();
			return;
		}
		GoalCard card = cardMap.get(id);
		if (card == null)
		{
			// Section expanded but no card (e.g. hidden under a collapsed nest).
			// Nothing safe to scroll to; drop the notice rather than guess.
			pendingRevealGoalId = null;
			return;
		}
		card.scrollRectToVisible(new Rectangle(0, 0, card.getWidth(), card.getHeight()));
		showInfoNotice("Already complete - added to the Completed section.");
		pendingRevealGoalId = null;
	}

	/** True when the section has goals and every one of them is selected. */
	private boolean isAllSelectedInSection(String sectionId)
	{
		java.util.Set<String> selected = api.getSelectedGoalIds();
		boolean any = false;
		for (com.goalplanner.api.GoalView v : api.queryAllGoals())
		{
			if (sectionId.equals(v.sectionId))
			{
				any = true;
				if (!selected.contains(v.id))
				{
					return false;
				}
			}
		}
		return any;
	}

	/** True when the section has goals and every one of them is complete -
	 *  drives the all-complete badge in the section header. */
	private boolean isAllCompleteInSection(String sectionId)
	{
		boolean any = false;
		for (com.goalplanner.api.GoalView v : api.queryAllGoals())
		{
			if (sectionId.equals(v.sectionId))
			{
				any = true;
				if (v.completedAt <= 0)
				{
					return false;
				}
			}
		}
		return any;
	}

	/**
	 * Render signature for a goal card: every GoalView field the card draws PLUS
	 * the position-derived wiring (reorder/context-menu bounds, first/last, source
	 * highlights). Equal signatures ⇒ the card renders and behaves identically, so
	 * the prior card can be reused untouched. MAINTENANCE: keep in sync with
	 * everything GoalCard reads from the view.
	 */
	private static String cardSignature(com.goalplanner.api.GoalView v,
		int secStart, int secEnd, int index, boolean completedSection,
		boolean first, boolean last, boolean relSource, boolean moveSource)
	{
		StringBuilder sb = new StringBuilder(160);
		// Font generation: a family/size change must bust every signature so cards
		// are reconstructed with the new font instead of reused with the old one.
		sb.append('f').append(PanelFonts.generation()).append('|');
		sb.append(secStart).append(';').append(secEnd).append(';').append(index).append(';')
			.append(completedSection ? 1 : 0).append(first ? 1 : 0).append(last ? 1 : 0)
			.append(relSource ? 1 : 0).append(moveSource ? 1 : 0).append('|');
		sb.append(v.type).append(';').append(v.name).append(';').append(v.optional ? 1 : 0).append(';')
			.append(v.currentValue).append(';').append(v.targetValue).append(';').append(v.completedAt).append(';')
			.append(v.backgroundColorRgb).append(';').append(v.spriteId).append('|');
		// Type-specific icon/label inputs the card reads out of the attribute map.
		for (String k : new String[]{"itemId", "skillName", "caTaskId", "tier", "monster", "area", "varbitId", "tooltip"})
		{
			sb.append(v.attributes.get(k)).append(';');
		}
		sb.append('|').append(v.blockedRequirements).append('|');
		appendTagSig(sb, v.defaultTags);
		appendTagSig(sb, v.customTags);
		// Relations drive the (eagerly-built) hover tooltip.
		appendRelSig(sb, v.requiresNames);
		appendRelSig(sb, v.orRequiresNames);
		appendRelSig(sb, v.requiredByNames);
		appendRelSig(sb, v.orRequiredByNames);
		return sb.toString();
	}

	private static void appendTagSig(StringBuilder sb, java.util.List<com.goalplanner.api.TagView> tags)
	{
		sb.append("t[");
		if (tags != null)
		{
			for (com.goalplanner.api.TagView t : tags)
			{
				sb.append(t.label).append('/').append(t.category).append('/')
					.append(t.colorRgb).append('/').append(t.iconKey).append(',');
			}
		}
		sb.append(']');
	}

	private static void appendRelSig(StringBuilder sb, java.util.List<com.goalplanner.api.GoalView.RelationView> rels)
	{
		sb.append("r[");
		if (rels != null)
		{
			for (com.goalplanner.api.GoalView.RelationView r : rels)
			{
				sb.append(r.name).append('/').append(r.skillName).append('/')
					.append(r.targetLevel).append('/').append(r.optional).append(',');
			}
		}
		sb.append(']');
	}

	public void rebuild()
	{
		long start = System.currentTimeMillis();
		goalListPanel.removeAll();
		// Snapshot last rebuild's cards + signatures so unchanged cards can be
		// reused (re-parented) instead of reconstructed. cardMap/cardSig are then
		// cleared and repopulated with the reused-or-new cards for this rebuild.
		final Map<String, GoalCard> prevCards = new HashMap<>(cardMap);
		final Map<String, String> prevSig = new HashMap<>(cardSig);
		cardMap.clear();
		cardSig.clear();
		headerRows.clear();
		refreshUndoRedoButtons();

		// Read path goes through the public API - the panel is now a consumer of
		// GoalPlannerApi just like external plugins would be.
		//
		// Search filter. When active, goalViews is the filtered flat
		// list. Within each section we re-order via topological
		// sort of the relation DAG (leaves first, priority tiebreaks within a
		// tier). The flat goalViews is kept for search-filter matching and for
		// the flat-priority `index` values the arrow buttons use.
		//
		// Arrow-button limitation: in sections that contain relation edges,
		// the flat-priority index the arrows act on may not correspond to the
		// visually-adjacent card in the topo-sorted view, so clicking up/down
		// on a related goal can be a visual no-op (topo sort re-applies after
		// the priority change). This is a known limitation of the session 2
		// checkpoint; topo-aware reordering is a follow-up.
		boolean filterActive = searchFilter != null && !searchFilter.trim().isEmpty();
		java.util.List<com.goalplanner.api.GoalView> goalViews = filterActive
			? api.searchGoals(searchFilter) : api.queryAllGoals();
		java.util.List<com.goalplanner.api.SectionView> sectionViews = api.queryAllSections();

		// Flat-priority index lookup for arrow-button bounds.
		java.util.Map<String, Integer> flatIndexById = new java.util.HashMap<>();
		for (int i = 0; i < goalViews.size(); i++)
		{
			flatIndexById.put(goalViews.get(i).id, i);
		}
		java.util.Set<String> visibleIds = flatIndexById.keySet();

		// Batch topo-sort all sections in one pass.
		java.util.Map<String, java.util.List<com.goalplanner.api.GoalView>> allTopoOrders =
			api.queryAllGoalsTopologicallySorted();

		// "New Section" drop target - only rendered while move-pick mode is
		// active. Sits above the first section header so the user always sees
		// it without scrolling. Click prompts for a name, creates the
		// section, and routes the move through handleMovePickToNewSection.
		if (pendingMoveSourceId != null)
		{
			JLabel newSectionRow = new JLabel("+ New Section");
			newSectionRow.setForeground(new Color(0x33, 0x99, 0xFF));
			newSectionRow.setFont(PanelFonts.derive(Font.BOLD, 12f));
			newSectionRow.setBorder(javax.swing.BorderFactory.createCompoundBorder(
				javax.swing.BorderFactory.createDashedBorder(new Color(0x33, 0x99, 0xFF), 1.5f, 4f, 2f, true),
				new EmptyBorder(8, 10, 8, 10)));
			newSectionRow.setAlignmentX(Component.CENTER_ALIGNMENT);
			newSectionRow.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			newSectionRow.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (e.getButton() != MouseEvent.BUTTON1) return;
					handleMovePickToNewSection();
				}
			});
			goalListPanel.add(newSectionRow);
			goalListPanel.add(Box.createVerticalStrut(6));
		}

		for (com.goalplanner.api.SectionView section : sectionViews)
		{
			int sectionStart = -1;
			int sectionEnd = -1;
			for (int i = 0; i < goalViews.size(); i++)
			{
				if (section.id.equals(goalViews.get(i).sectionId))
				{
					if (sectionStart == -1) sectionStart = i;
					sectionEnd = i;
				}
			}
			int sectionCount = (sectionStart == -1) ? 0 : (sectionEnd - sectionStart + 1);

			// Use pre-computed topo order for this section.
			java.util.List<com.goalplanner.api.GoalView> topoOrder =
				allTopoOrders.getOrDefault(section.id, java.util.Collections.emptyList());
			if (filterActive)
			{
				java.util.List<com.goalplanner.api.GoalView> filtered = new java.util.ArrayList<>();
				for (com.goalplanner.api.GoalView v : topoOrder)
				{
					if (visibleIds.contains(v.id)) filtered.add(v);
				}
				topoOrder = filtered;
			}

			// Built-in section headers (Incomplete, Completed) are
			// always visible now so the user can right-click them for the Add
			// Section action even when they're empty. User sections were
			// always visible. When filtering, still hide empty sections so
			// the result list stays focused.
			//
			// Repeatable is the exception: most players never make a repeatable
			// goal, so an always-visible empty header is pure clutter for them.
			// Hiding it orphans no entry point - repeat is set from the GOAL
			// right-click menu, not from this header.
			if (sectionCount == 0 && (filterActive || "REPEATABLE".equals(section.kind))) continue;
			final String sectionIdRef = section.id;
			SectionHeaderRow headerRow = new SectionHeaderRow(section, sectionCount, () -> {
				// The chevron toggles collapse. In move-pick mode it doubles as a
				// drop target for empty sections, matching the pre-select behavior.
				if (pendingMoveSourceId != null)
				{
					handleMovePickToSection(sectionIdRef);
					return;
				}
				api.toggleSectionCollapsed(sectionIdRef);
				// API callback rebuilds the panel.
			},
				// Clicking the row body SELECTS the section (dock SECTION state).
				// In move-pick mode it stays a drop target - the row body is the
				// large hit area, particularly useful for empty sections.
				() -> {
					if (pendingMoveSourceId != null)
					{
						handleMovePickToSection(sectionIdRef);
						return;
					}
					selectSection(sectionIdRef);
				},
				// Select-all/unselect-all toggle on the right edge of the header.
				() -> isAllSelectedInSection(sectionIdRef),
				() -> {
					if (isAllSelectedInSection(sectionIdRef))
					{
						api.deselectAllInSection(sectionIdRef);
					}
					else
					{
						api.selectAllInSection(sectionIdRef);
					}
				},
				isAllCompleteInSection(sectionIdRef));
			headerRow.setSelected(sectionIdRef.equals(selectedSectionId));
			headerRows.add(headerRow);
			// Section right-click menu retired (1.0.0): right-clicking the header
			// now selects the section (like goal cards), and section actions live in
			// the dock's SECTION state. Menu builder stays until the diet removes it.
			goalListPanel.add(headerRow);
			goalListPanel.add(Box.createVerticalStrut(2));

			// Empty user-section placeholder: a single italic hint row directly under
			// the header, so a freshly created section doesn't look broken.
			if (sectionCount == 0 && !section.builtIn && !section.collapsed)
			{
				JLabel placeholder = new JLabel("Empty - right-click goals to move them here");
				placeholder.setForeground(new Color(120, 120, 120));
				placeholder.setFont(PanelFonts.derive(Font.ITALIC, 10f));
				placeholder.setAlignmentX(Component.CENTER_ALIGNMENT);
				placeholder.setBorder(new EmptyBorder(2, 4, 6, 4));
				goalListPanel.add(placeholder);
				continue;
			}

			// Skip rendering goal cards while the section is collapsed, or when
			// the section is empty (sectionStart == -1 → guard against the
			// goalViews.get(i) loop below running with i = -1).
			if (section.collapsed || sectionCount == 0)
			{
				continue;
			}

			boolean isCompletedSection = "COMPLETED".equals(section.kind);

			// Nested view: when enabled (the section's railView flag - kept for
			// now; the renderer is the subtle indent+guide nesting, not the
			// rejected connector rail), cards are collected into a nesting
			// container that left-indents each card by its in-section dependency
			// depth. Only in-section edges count, so pre-compute the id set and
			// each goal's indent level up front.
			// Nested when the global "Indent dependencies" option is on, OR the
			// section's own override flag is set (per-section force-on).
			// Per-section override wins when set; otherwise the global default.
			boolean nestedView = section.nestedOverride != null
				? section.nestedOverride
				: config.showDependenciesIndented();
			java.util.Set<String> sectionGoalIdSet = null;
			com.goalplanner.ui.nest.NestIndentAssigner.Result nestResult = null;
			if (nestedView)
			{
				sectionGoalIdSet = new java.util.HashSet<>();
				for (com.goalplanner.api.GoalView v : topoOrder) sectionGoalIdSet.add(v.id);

				// In-section direct prereq edges for any goal id.
				final java.util.Set<String> inSection = sectionGoalIdSet;
				java.util.function.Function<String, java.util.List<String>> inSectionEdges = gid ->
				{
					Goal gg = goalStore.findGoalById(gid);
					java.util.List<String> edges = new java.util.ArrayList<>();
					if (gg != null)
					{
						for (String rid : gg.getRequiredGoalIds())
							if (inSection.contains(rid)) edges.add(rid);
						for (String rid : gg.getOrRequiredGoalIds())
							if (inSection.contains(rid)) edges.add(rid);
					}
					return edges;
				};
				// Completed goals sink to the bottom, not nested - but chains must
				// survive THROUGH them: A → B(done) → C still nests A under C.
				java.util.function.Predicate<String> sunk = gid ->
				{
					Goal gg = goalStore.findGoalById(gid);
					return gg == null || gg.isComplete();
				};
				java.util.List<com.goalplanner.ui.nest.NestIndentAssigner.Node> nestNodes =
					new java.util.ArrayList<>();
				for (com.goalplanner.api.GoalView v : topoOrder)
				{
					if (sunk.test(v.id)) continue;
					nestNodes.add(new com.goalplanner.ui.nest.NestIndentAssigner.Node(
						v.id,
						com.goalplanner.ui.nest.NestIndentAssigner.resolveVisiblePrereqs(
							inSectionEdges.apply(v.id), inSectionEdges, sunk)));
				}
				nestResult = com.goalplanner.ui.nest.NestIndentAssigner.assign(nestNodes);
			}

			// Goals that are a nest PARENT (some visible goal nests under them) -
			// these get a collapse chevron + right-click toggle.
			final java.util.Set<String> nestParents = new java.util.HashSet<>();
			if (nestResult != null)
			{
				for (String gid : nestResult.ordered)
				{
					String pp = nestResult.primaryParent.get(gid);
					if (pp != null) nestParents.add(pp);
				}
			}

			// Iterate topo-order for rendering, but resolve each
			// goal's flat-priority index for the arrow buttons. Arrows target
			// the VISUALLY adjacent card in the topo view, but only when that
			// card is in the SAME topo tier - otherwise the move would fight
			// with the DAG constraint (topo sort would put the cards back).
			// When the adjacent card is in a different tier, the arrow is
			// hidden (via firstInList/lastInList).
			for (int topoPos = 0; topoPos < topoOrder.size(); topoPos++)
			{
				com.goalplanner.api.GoalView view = topoOrder.get(topoPos);
				Goal goal = goalStore.findGoalById(view.id);
				if (goal == null) continue; // shouldn't happen but defensive
				Integer flatIdx = flatIndexById.get(view.id);
				if (flatIdx == null) continue; // search-filtered out
				final int index = flatIdx;

				final int secStart = sectionStart;
				final int secEnd = sectionEnd;

				// Arrows always fire. The handler walks the
				// topo list from the clicked card, collects any direct
				// prereq/dependent chain that needs to move with it, and
				// shifts the whole block by one position. No-ops if the
				// chain is already at the edge of the section.
				final String goalIdRef = view.id;
				final String arrowSectionId = section.id;
				// Arrow actions go through api.moveGoal, which enforces the
				// auto-deselect-if-not-member rule at the API layer - no
				// UI-side wrapping needed.
				final boolean firstInList = isCompletedSection || topoPos == 0;
				final boolean lastInList = isCompletedSection || topoPos == topoOrder.size() - 1;
				final boolean relSource = pendingRelationSourceIds.contains(goalIdRef);
				final boolean moveSource = goalIdRef.equals(pendingMoveSourceId);
				// Reuse the prior card untouched when its full signature is
				// unchanged - the signature covers every rendered view field AND the
				// position-derived wiring (reorder targets, context-menu indices,
				// first/last, source borders), so a match means the card is valid
				// as-is. Re-parenting it skips the dominant Swing construction cost
				// with no re-wiring (no duplicate listeners). MAINTENANCE: any new
				// GoalView field the card renders MUST be added to cardSignature().
				final String sig = cardSignature(view, sectionStart, sectionEnd, index,
					isCompletedSection, firstInList, lastInList, relSource, moveSource);
				GoalCard card = sig.equals(prevSig.get(goalIdRef)) ? prevCards.get(goalIdRef) : null;
				if (card == null)
				{
				card = new GoalCard(
					view,
					e -> reorderController.moveChainInTopo(goalIdRef, arrowSectionId, /*up=*/true),
					e -> reorderController.moveChainInTopo(goalIdRef, arrowSectionId, /*up=*/false),
					() -> reorderController.moveGoalTo(goalIdRef, secStart),
					() -> reorderController.moveGoalTo(goalIdRef, secEnd),
					skillIconManager,
					itemManager,
					spriteManager,
					view.blockedRequirements.isEmpty() ? null : () -> addMissingRequirements(view)
				);

				// Completed section is read-only ordering - no reorder arrows.
				// Otherwise arrows show at non-edge positions; the handler decides
				// whether a move is actually possible.
				card.setFirstInList(firstInList);
				card.setLastInList(lastInList);
				// Panel right-click menu retired (1.0.0): right-click now selects
				// (see attachSelectionClick), every action lives in the dock, and
				// nest-collapse rides the SectionNestContainer chevron. The menu
				// builder stays until the diet removes it. In-game menus unaffected.
				attachSelectionClick(card, view);

				// Relation-pick (orange) / move-pick (blue) source highlight.
				if (relSource)
				{
					card.setBorder(javax.swing.BorderFactory.createLineBorder(
						new Color(0xFF, 0x99, 0x33), 2));
				}
				else if (moveSource)
				{
					card.setBorder(javax.swing.BorderFactory.createLineBorder(
						new Color(0x33, 0x99, 0xFF), 2));
				}
				}
				// Selection is toggled live (refreshSelection), not part of the
				// signature; sync the (reused or new) card's highlight cheaply.
				card.setSelected(view.selected);
				// Nested cards are narrower the deeper they sit; use the compact
				// status form there so the progress column doesn't clip. Applied
				// live (like selection) so reused cards update when their level shifts.
				int nestLvl = nestResult != null ? nestResult.level.getOrDefault(goalIdRef, 0) : 0;
				card.setCompactStatus(nestedView && nestLvl > 0);
				cardMap.put(goalIdRef, card);
				cardSig.put(goalIdRef, sig);

				// In nested view the cards are laid out by the nest container in
				// tree pre-order (built after this loop, from cardMap); don't add
				// them to the flat list here.
				if (!nestedView)
				{
					goalListPanel.add(card);
					goalListPanel.add(Box.createVerticalStrut(4));
				}
			}

			// Nested view: incomplete goals form an outline tree (indented by
			// dependency, extra prereqs in a tooltip); completed goals sink to a
			// flat group at the bottom as cards. Un-completing a goal returns it to
			// its nested position on the next rebuild.
			if (nestedView)
			{
				java.util.List<com.goalplanner.ui.nest.SectionNestContainer.Row> nestRows =
					new java.util.ArrayList<>();
				if (nestResult != null)
				{
					int hideBelowLevel = Integer.MAX_VALUE;
					for (String gid : nestResult.ordered)
					{
						int lvl = nestResult.level.getOrDefault(gid, 0);
						// Skip everything beneath a collapsed parent (preorder).
						if (lvl > hideBelowLevel) continue;
						hideBelowLevel = Integer.MAX_VALUE;
						GoalCard card = cardMap.get(gid);
						if (card == null) continue; // search-filtered out
						int extra = nestResult.extraPrereqs.getOrDefault(gid, 0);
						if (extra > 0)
						{
							String primaryId = nestResult.primaryParent.get(gid);
							java.util.List<String> extraNames = new java.util.ArrayList<>();
							Goal cg = goalStore.findGoalById(gid);
							if (cg != null)
							{
								java.util.List<String> all = new java.util.ArrayList<>(cg.getRequiredGoalIds());
								all.addAll(cg.getOrRequiredGoalIds());
								for (String rid : all)
								{
									if (rid.equals(primaryId) || !sectionGoalIdSet.contains(rid)) continue;
									Goal rg = goalStore.findGoalById(rid);
									if (rg != null && !extraNames.contains(rg.getName())) extraNames.add(rg.getName());
								}
							}
							card.setToolTipText(extraNames.isEmpty() ? null
								: "Also needs: " + String.join(", ", extraNames));
						}
						else
						{
							card.setToolTipText(null);
						}
						boolean isParent = nestParents.contains(gid);
						Goal pg = goalStore.findGoalById(gid);
						boolean isCollapsed = isParent && pg != null && pg.isNestCollapsed();
						if (isCollapsed) hideBelowLevel = lvl;
						nestRows.add(new com.goalplanner.ui.nest.SectionNestContainer.Row(
							gid, lvl, card, isParent, isCollapsed));
					}
				}
				// Completed in-section goals: flat (level 0), sunk to the bottom.
				for (com.goalplanner.api.GoalView v : topoOrder)
				{
					Goal vg = goalStore.findGoalById(v.id);
					if (vg == null || !vg.isComplete()) continue;
					GoalCard card = cardMap.get(v.id);
					if (card == null) continue;
					card.setToolTipText(null);
					nestRows.add(new com.goalplanner.ui.nest.SectionNestContainer.Row(v.id, 0, card));
				}
				if (!nestRows.isEmpty())
				{
					com.goalplanner.ui.nest.SectionNestContainer nestContainer =
						new com.goalplanner.ui.nest.SectionNestContainer(nestRows,
							gid -> { api.toggleGoalNestCollapsed(gid); rebuild(); });
					nestContainer.setAlignmentX(Component.CENTER_ALIGNMENT);
					goalListPanel.add(nestContainer);
					goalListPanel.add(Box.createVerticalStrut(4));
				}
			}
		}

		if (goalViews.isEmpty())
		{
			JPanel emptyPanel = new JPanel();
			emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
			emptyPanel.setOpaque(false);
			emptyPanel.setBorder(new EmptyBorder(32, 8, 8, 8));
			emptyPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

			JLabel headline = new JLabel("No goals yet");
			headline.setForeground(new Color(180, 180, 180));
			headline.setFont(PanelFonts.derive(Font.BOLD, 13f));
			headline.setAlignmentX(Component.CENTER_ALIGNMENT);

			emptyPanel.add(headline);
			addHintLines(emptyPanel, 8, new String[] {
				"Right click on the Incomplete",
				"section to add your first goal",
			});
			addHintLines(emptyPanel, 6, new String[] {
				"Or right-click in-game on",
				"skills, quests, diary tasks,",
				"items, or collection log",
			});
			addHintLines(emptyPanel, 6, new String[] {
				"Right-click any section header",
				"to add a custom section",
			});
			goalListPanel.add(emptyPanel);
		}

		goalListPanel.revalidate();
		goalListPanel.repaint();
		// A just-created goal may have completed-on-add and reconciled into the
		// Completed section. Check after layout settles (bounds valid) so the
		// reveal can scroll its card into view.
		if (pendingRevealGoalId != null)
		{
			javax.swing.SwingUtilities.invokeLater(this::maybeRevealPendingCreate);
		}
		long elapsed = System.currentTimeMillis() - start;
		if (elapsed > 50)
		{
			log.warn("rebuild() took {}ms ({} cards)", elapsed, cardMap.size());
		}
	}

	private void addHintLines(JPanel parent, int topGap, String[] lines)
	{
		for (int i = 0; i < lines.length; i++)
		{
			JLabel line = new JLabel(lines[i]);
			line.setForeground(new Color(130, 130, 130));
			line.setFont(PanelFonts.derive(11f));
			line.setAlignmentX(Component.CENTER_ALIGNMENT);
			line.setBorder(new EmptyBorder(i == 0 ? topGap : 0, 0, 0, 0));
			parent.add(line);
		}
	}

	// ------------------------------------------------------------------
	// Relation-pick mode
	// ------------------------------------------------------------------

	/**
	 * Enter relation-pick mode. The next left-click on any goal card will
	 * complete a new relation edge between {@code sourceGoalId} and the
	 * clicked goal. Shows an instruction banner at the top of the panel.
	 *
	 * @param sourceGoalId            the goal the user right-clicked
	 * @param sourceRequiresTarget    true to make the clicked goal a
	 *                                requirement of the source (edge
	 *                                source → target); false to make the
	 *                                source a dependent of the target
	 *                                (edge target → source)
	 */
	void enterRelationMode(String sourceGoalId, boolean sourceRequiresTarget)
	{
		enterRelationMode(java.util.Collections.singleton(sourceGoalId), sourceRequiresTarget);
	}

	/**
	 * Bulk variant: every source goal gets the same edge to/from the
	 * clicked target on completion. Cycle / duplicate rejections fail
	 * open per source - others still succeed.
	 */
	void enterRelationMode(java.util.Set<String> sourceGoalIds, boolean sourceRequiresTarget)
	{
		if (sourceGoalIds == null || sourceGoalIds.isEmpty()) return;
		// Pick modes are about a single source set. Any pre-existing multi-
		// select highlight would compete visually with the orange source
		// border, so clear the selection on entry.
		api.clearGoalSelection();
		pendingRelationSourceIds.clear();
		pendingRelationSourceIds.addAll(sourceGoalIds);
		pendingRelationSourceRequiresTarget = sourceRequiresTarget;
		String banner;
		if (pendingRelationSourceIds.size() == 1)
		{
			String sourceName = reorderController.goalNameById(
				pendingRelationSourceIds.iterator().next());
			banner = sourceRequiresTarget
				? "Click a goal to add as a requirement of \"" + sourceName + "\""
				: "Click a goal that should require \"" + sourceName + "\"";
		}
		else
		{
			int n = pendingRelationSourceIds.size();
			banner = sourceRequiresTarget
				? "Click a goal to add as a requirement of " + n + " selected goals"
				: "Click a goal that should require " + n + " selected goals";
		}
		relationModeLabel.setText("<html>" + banner + " - ESC to cancel</html>");
		relationModeBanner.setVisible(true);
		// Rebuild so the orange source-card borders get applied.
		rebuild();
	}

	/** Exit relation-pick mode without adding an edge. */
	void exitRelationMode()
	{
		if (pendingRelationSourceIds.isEmpty()) return;
		pendingRelationSourceIds.clear();
		relationModeBanner.setVisible(false);
		// Rebuild so the orange source-card borders go away.
		rebuild();
	}

	/**
	 * Handle a left-click on a card while relation-pick mode is active.
	 * Clicking any source card cancels (the user is signaling stop).
	 * Otherwise attempt to add the edge for every source in the set;
	 * cycle / duplicate rejections per-source fail open (skip that one,
	 * others still succeed). Always exits the mode so the user isn't
	 * stranded.
	 */
	private void handleRelationPickTarget(String clickedGoalId)
	{
		if (pendingRelationSourceIds.isEmpty()) return;
		if (pendingRelationSourceIds.contains(clickedGoalId))
		{
			exitRelationMode();
			return;
		}
		java.util.List<String> sources = new java.util.ArrayList<>(pendingRelationSourceIds);
		boolean requires = pendingRelationSourceRequiresTarget;
		int attempted = sources.size();
		int succeeded = 0;
		api.beginCompound(attempted == 1
			? "Add relation"
			: "Add " + attempted + " relations");
		try
		{
			for (String sourceId : sources)
			{
				String fromId = requires ? sourceId : clickedGoalId;
				String toId = requires ? clickedGoalId : sourceId;
				if (api.addRequirement(fromId, toId)) succeeded++;
			}
		}
		finally { api.endCompound(); }
		exitRelationMode();
		if (succeeded == 0)
		{
			JOptionPane.showMessageDialog(this,
				attempted == 1
					? "Could not add relation - it may already exist or would create a cycle."
					: "Could not add any of the " + attempted + " relations - each may already exist or would create a cycle.",
				"Add Relation", JOptionPane.WARNING_MESSAGE);
		}
	}

	// ------------------------------------------------------------------
	// Move-pick mode
	// ------------------------------------------------------------------

	/**
	 * Enter move-pick mode. The next left-click on any goal card inserts
	 * {@code sourceGoalId} above the clicked target (in the target's
	 * section, at the target's position). Clicking the in-mode "+ New
	 * Section" row instead creates a new section and moves there.
	 * Exiting another pick mode first keeps the two states mutually
	 * exclusive.
	 */
	void enterMoveMode(String sourceGoalId)
	{
		if (!pendingRelationSourceIds.isEmpty()) exitRelationMode();
		// Same rule as enterRelationMode - pick mode is single-source, so
		// any existing multi-select highlight is visual noise.
		api.clearGoalSelection();
		pendingMoveSourceId = sourceGoalId;
		String sourceName = reorderController.goalNameById(sourceGoalId);
		moveModeLabel.setText("<html>Click a goal to place \"" + sourceName
			+ "\" above it, or click + New Section - ESC to cancel</html>");
		moveModeBanner.setVisible(true);
		// Rebuild so the blue source-card border + New Section row appear.
		rebuild();
	}

	/** Exit move-pick mode without moving. */
	void exitMoveMode()
	{
		if (pendingMoveSourceId == null) return;
		pendingMoveSourceId = null;
		moveModeBanner.setVisible(false);
		// Rebuild so the highlight + New Section row go away.
		rebuild();
	}

	/**
	 * Handle a left-click on a card while move-pick mode is active.
	 * Clicking the source cancels. Otherwise the source is positioned in
	 * the target's section at the target's position-within-section, which
	 * places it directly above the target. Always exits the mode so the
	 * user isn't stranded.
	 */
	private void handleMovePickTarget(String clickedGoalId)
	{
		if (pendingMoveSourceId == null) return;
		if (clickedGoalId.equals(pendingMoveSourceId))
		{
			exitMoveMode();
			return;
		}
		com.goalplanner.api.GoalView target = api.queryGoalView(clickedGoalId);
		if (target == null)
		{
			exitMoveMode();
			return;
		}
		// Compute target's flat-priority position within its section. The
		// source IS counted (not skipped) because positionGoalInSection's
		// sectionIndices list also includes the source. Skipping it would
		// off-by-one when source is above target, making the move a no-op.
		// With source counted, the move is symmetric: source always ends
		// up at target's row, and target shifts to make room.
		java.util.List<com.goalplanner.api.GoalView> all = api.queryAllGoals();
		int positionInSection = 0;
		for (com.goalplanner.api.GoalView v : all)
		{
			if (!target.sectionId.equals(v.sectionId)) continue;
			if (v.id.equals(target.id)) break;
			positionInSection++;
		}
		String sourceId = pendingMoveSourceId;
		api.positionGoalInSection(sourceId, target.sectionId, positionInSection);
		exitMoveMode();
	}

	/**
	 * Handle a click on a section header row while move-pick mode is
	 * active. Useful for empty sections (no goal cards to click as a
	 * target). Routes through moveGoalToSection so the placement matches
	 * the Move-to-Section submenu (appended to end). No-ops on the
	 * source's current section and on the Completed section.
	 */
	private void handleMovePickToSection(String sectionId)
	{
		if (pendingMoveSourceId == null) return;
		com.goalplanner.api.GoalView source = api.queryGoalView(pendingMoveSourceId);
		if (source == null || sectionId.equals(source.sectionId))
		{
			exitMoveMode();
			return;
		}
		// Skip COMPLETED - the menu's "Move to Section" filter excludes it
		// and we keep the click-mode behavior consistent.
		for (com.goalplanner.api.SectionView sv : api.queryAllSections())
		{
			if (sv.id.equals(sectionId) && "COMPLETED".equals(sv.kind))
			{
				exitMoveMode();
				return;
			}
		}
		api.moveGoalToSection(pendingMoveSourceId, sectionId);
		exitMoveMode();
	}

	/**
	 * Handle a click on the in-mode "+ New Section" row. Prompts for a
	 * name, creates the section, and moves the source goal there. Cancels
	 * the mode regardless of outcome.
	 */
	private void handleMovePickToNewSection()
	{
		if (pendingMoveSourceId == null) return;
		String input = JOptionPane.showInputDialog(this, "New section name:", "");
		if (input != null && !input.trim().isEmpty())
		{
			String newId = api.createSection(input.trim());
			if (newId != null)
			{
				api.moveGoalToSection(pendingMoveSourceId, newId);
			}
		}
		exitMoveMode();
	}

	/**
	 * Attach a left-click MouseListener that routes selection clicks through
	 * the API. Coexists with the existing right-click context menu - only
	 * BUTTON1 events are handled here, BUTTON3 falls through to the popup.
	 *
	 * <p>Click semantics:
	 * <ul>
	 *   <li>Plain click on UNSELECTED → replace selection with just this card</li>
	 *   <li>Plain click on SELECTED → clear selection entirely</li>
	 *   <li>Cmd/Ctrl+click on UNSELECTED → add this card to selection</li>
	 *   <li>Cmd/Ctrl+click on SELECTED → remove this card from selection</li>
	 * </ul>
	 */
	private void attachSelectionClick(GoalCard card, com.goalplanner.api.GoalView view)
	{
		final String goalId = view.id;
		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// Right-click (BUTTON3) now behaves exactly like Cmd/Ctrl-click -
				// toggle this card in the selection - instead of opening a context
				// menu (the panel menu is retired in 1.0.0; in-game right-clicks are
				// unaffected). Left-click keeps its plain/modifier semantics.
				boolean rightClick = e.getButton() == MouseEvent.BUTTON3;
				if (e.getButton() != MouseEvent.BUTTON1 && !rightClick) return;
				if (!pendingRelationSourceIds.isEmpty())
				{
					handleRelationPickTarget(goalId);
					return;
				}
				if (pendingMoveSourceId != null)
				{
					handleMovePickTarget(goalId);
					return;
				}
				// Check LIVE selection state, not the stale build-time value.
				boolean isSelected = api.getSelectedGoalIds().contains(goalId);
				boolean cmdCtrl = e.isMetaDown() || e.isControlDown() || rightClick;
				boolean shift = e.isShiftDown();
				if (shift && selectionAnchorId != null && !cmdCtrl)
				{
					java.util.Set<String> range = computeRangeSelection(selectionAnchorId, goalId);
					if (!range.isEmpty())
					{
						java.util.Set<String> current = new java.util.LinkedHashSet<>(api.getSelectedGoalIds());
						if (isSelected)
						{
							// Shift-click on selected goal: deselect the range.
							current.removeAll(range);
						}
						else
						{
							// Shift-click on unselected goal: add the range.
							current.addAll(range);
						}
						api.replaceGoalSelection(current);
					}
					selectionAnchorId = goalId;
					return;
				}
				if (cmdCtrl)
				{
					if (isSelected) api.removeFromGoalSelection(goalId);
					else api.addToGoalSelection(goalId);
					selectionAnchorId = goalId;
				}
				else
				{
					if (isSelected) api.clearGoalSelection();
					else api.replaceGoalSelection(java.util.Collections.singleton(goalId));
					selectionAnchorId = goalId;
				}
			}
		});
	}

	/**
	 * Refresh the enabled state + tooltip on the undo/redo buttons
	 * to reflect the current command history. Called from {@link #rebuild()}.
	 */
	private static final Color UNDO_REDO_ENABLED = new Color(180, 180, 220);
	private static final Color UNDO_REDO_DISABLED = new Color(80, 80, 90);

	/**
	 * Open the Discord invite in the user's default browser. Falls back to
	 * a no-op (with a log warning) if Desktop browse isn't supported - on
	 * a headless system there's nothing useful we can do.
	 */
	private void openDiscordInvite()
	{
		try
		{
			if (java.awt.Desktop.isDesktopSupported()
				&& java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE))
			{
				java.awt.Desktop.getDesktop().browse(java.net.URI.create(DISCORD_URL));
			}
			else
			{
				log.warn("Desktop browse not supported; cannot open Discord invite");
			}
		}
		catch (Exception ex)
		{
			log.warn("Failed to open Discord invite: {}", ex.getMessage());
		}
	}

	private void refreshUndoRedoButtons()
	{
		if (undoButton == null || redoButton == null) return;
		boolean canUndo = api.canUndo();
		boolean canRedo = api.canRedo();
		undoButton.setEnabled(canUndo);
		redoButton.setEnabled(canRedo);
		// ShapeIcons don't react to component enabled state, so
		// swap the icon color to make the disabled state visible.
		undoButton.setIcon(ShapeIcons.undoArrow(12,
			canUndo ? UNDO_REDO_ENABLED : UNDO_REDO_DISABLED));
		redoButton.setIcon(ShapeIcons.redoArrow(12,
			canRedo ? UNDO_REDO_ENABLED : UNDO_REDO_DISABLED));
		undoButton.setToolTipText(canUndo
			? "Undo: " + api.peekUndoDescription() : "Nothing to undo");
		redoButton.setToolTipText(canRedo
			? "Redo: " + api.peekRedoDescription() : "Nothing to redo");
	}

	/**
	 * Two-step yes/no confirmation guard for destructive actions.
	 * The action only runs if the user clicks Yes on BOTH dialogs in sequence.
	 */
	/**
	 * Walk the canonical goal order from the API and return the slice of ids
	 * between (and including) anchorId and clickedId. The order is the same
	 * one used to render the panel - sections in section.order, goals within
	 * each section in priority order. Returns an empty set if either id is
	 * missing from the canonical list (e.g. just deleted).
	 */
	/**
	 * Compute the range of goals between anchor and clicked in the
	 * RENDERED card order (topo-sorted per section), not flat priority.
	 * Uses the cardMap insertion order which matches the visual layout.
	 */
	private java.util.Set<String> computeRangeSelection(String anchorId, String clickedId)
	{
		// Walk the rendered card order (cardMap is LinkedHashMap-like via
		// insertion order during rebuild). Use goalListPanel's components
		// to get the actual visual order.
		java.util.List<String> renderedOrder = new java.util.ArrayList<>();
		for (java.awt.Component comp : goalListPanel.getComponents())
		{
			if (comp instanceof GoalCard)
			{
				GoalCard card = (GoalCard) comp;
				renderedOrder.add(card.getGoalId());
			}
		}
		int aIdx = -1, bIdx = -1;
		for (int i = 0; i < renderedOrder.size(); i++)
		{
			String id = renderedOrder.get(i);
			if (id.equals(anchorId)) aIdx = i;
			if (id.equals(clickedId)) bIdx = i;
		}
		if (aIdx < 0 || bIdx < 0) return java.util.Collections.emptySet();
		int lo = Math.min(aIdx, bIdx), hi = Math.max(aIdx, bIdx);
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
		for (int i = lo; i <= hi; i++) out.add(renderedOrder.get(i));
		return out;
	}

	/**
	 * Rebuild the action dock for the current selection. ALL dock action
	 * assembly lives here on purpose - the parity pass that migrates the 125
	 * context-menu actions edits this one method (ADR-0007).
	 *
	 * <p>Initial slice: enough of each state to feel the interaction. The
	 * context menus remain the complete surface until parity is verified.
	 */
	void refreshDock()
	{
		// A section that was deleted/renamed away while selected must not linger as
		// a stale SECTION state - drop the id before it can drive the dock.
		if (selectedSectionId != null && findSectionView(selectedSectionId) == null)
		{
			selectedSectionId = null;
		}
		// Selecting something ALWAYS resets the panel, even mid-flow (user): if the
		// selection moved since a transient overlay (color / tag / share / move /
		// edit form) was opened, that overlay belongs to the old selection - drop it
		// so the dock renders the NEW selection instead of stranding the user in a
		// half-finished flow against the wrong goal.
		String selKey = dockSelectionKey();
		if (dockOverlayActive() && !selKey.equals(dockOverlaySelectionKey))
		{
			clearTransientOverlays();
		}
		dockOverlaySelectionKey = selKey;
		com.goalplanner.ui.dock.DockContext ctx =
			com.goalplanner.ui.dock.DockContext.of(api.getSelectedGoalIds(), selectedSectionId);

		// Goals and a section are mutually exclusive; a goal selection always wins.
		// Clearing the id here keeps a stale section highlight from surviving a goal
		// selection made through any path (card click, select-all, share import).
		if (ctx.getState() == com.goalplanner.ui.dock.DockContext.State.GOAL
			|| ctx.getState() == com.goalplanner.ui.dock.DockContext.State.MULTI)
		{
			selectedSectionId = null;
		}
		// Repaint every header's selection highlight against the resolved id. The
		// header rows persist across a dock-only refresh, so this updates them
		// without a full list rebuild (mirrors refreshSelectToggle).
		for (SectionHeaderRow row : headerRows)
		{
			row.setSelected(row.getSectionId() != null
				&& row.getSectionId().equals(selectedSectionId));
		}
		// Anything but a single section selected drops the mounted SECTION surface.
		if (ctx.getState() != com.goalplanner.ui.dock.DockContext.State.SECTION)
		{
			dockSectionMounted = false;
			dockSectionMountedId = null;
			dockSectionMountedGroup = null;
			dockSectionGroup = null;
		}

		// The two create buttons are a PERMANENT footer (ADR-0008 refinement),
		// present in every state. The contextual surface renders ABOVE them. Wire
		// the footer callbacks every refresh (cheap); the handlers own the
		// open/switch/collapse semantics because they read the create nav state.
		actionDock.setFooterActions(this::onFooterCreateGoal, this::onFooterCreateSection);
		// The grab handle's drag-down / click dismiss (Task 8) clears the surface
		// drivers and rests the dock at the footer from any state.
		actionDock.setOnDismiss(this::dismissDock);

		// Inline color picker (inline-color pass): a transient overlay that mounts
		// its swatch grid above the footer instead of the normal edit/multi/section
		// surface. The underlying selection is left intact - closing the overlay
		// remounts whichever surface it belongs to. If the target vanished (goal /
		// section deleted, or the MULTI selection cleared), drop the overlay and
		// fall through to normal routing.
		if (dockColorReturn != null)
		{
			if (!colorTargetValid())
			{
				dockColorReturn = null;
				dockColorTarget = null;
				dockColorMounted = false;
			}
			else
			{
				actionDock.setExpanded(true);
				if (!dockColorMounted)
				{
					actionDock.setExpandedComponent(buildColorSurface());
					dockColorMounted = true;
				}
				return;
			}
		}

		// Inline tag add/remove surface (inline-tag pass): the same overlay pattern
		// as the color picker. When active it mounts the Add/Remove tag surface above
		// the footer, leaving the underlying selection intact. If the target vanished
		// (goal deleted, or the MULTI selection cleared) drop the overlay and fall
		// through to normal routing.
		if (dockTagMode != null)
		{
			if (!tagTargetValid())
			{
				dockTagMode = null;
				dockTagReturn = null;
				dockTagTarget = null;
				dockTagMounted = false;
			}
			else
			{
				actionDock.setExpanded(true);
				if (!dockTagMounted)
				{
					actionDock.setExpandedComponent(buildTagSurface());
					dockTagMounted = true;
				}
				return;
			}
		}

		// Inline Share surface (share-inline pass): the same overlay pattern as the
		// color / tag surfaces. When active it mounts the share-code view (read-only
		// code + Copy + optional Save) above the footer. The target was captured at
		// open time; if it went stale (its goals / section vanished) drop the overlay
		// and fall through to normal routing.
		if (dockShareScope != null)
		{
			if (!shareTargetValid())
			{
				dockShareScope = null;
				dockShareGoalIds = null;
				dockShareSectionId = null;
				dockShareMounted = false;
			}
			else
			{
				actionDock.setExpanded(true);
				if (!dockShareMounted)
				{
					actionDock.setExpandedComponent(buildShareSurface());
					dockShareMounted = true;
				}
				return;
			}
		}

		// Inline Import overlay (import-inline pass): a paste surface above the
		// footer, reached from the create grid. Same overlay pattern as the color /
		// tag / share surfaces; no target to go stale.
		if (dockImportActive)
		{
			actionDock.setExpanded(true);
			if (!dockImportMounted)
			{
				actionDock.setExpandedComponent(buildImportSurface());
				dockImportMounted = true;
			}
			return;
		}

		// "Edit goal" overlay (read-only-selected pass): the Selected view is
		// read-only, and its Edit goal button mounts the goal's own CREATE form here
		// in UPDATE mode - same builders, pre-filled, primary button saves. Same
		// overlay pattern as color / tag / share: the goal stays selected underneath,
		// so closing remounts the Selected view. A goal that vanished (deleted, or
		// undone away) drops the overlay and falls through to normal routing.
		if (dockEditFormGoalId != null)
		{
			// Valid only while its goal still exists AND is still the sole selection -
			// selecting another card (or deselecting) leaves update mode rather than
			// stranding a form over a goal the user has moved on from.
			Goal editing =
				ctx.getState() == com.goalplanner.ui.dock.DockContext.State.GOAL
					&& dockEditFormGoalId.equals(ctx.getSoleGoalId())
					? goalStore.findGoalById(dockEditFormGoalId) : null;
			if (editing == null)
			{
				dockEditFormGoalId = null;
				dockEditFormMounted = false;
				dockCreateStep = CreateStep.PICKER;
				resetCreatePicks();
			}
			else
			{
				actionDock.setExpanded(true);
				if (!dockEditFormMounted)
				{
					actionDock.setExpandedComponent(buildCreateForm(editing.getType()));
					dockEditFormMounted = true;
				}
				return;
			}
		}

		// Inline Saved Plans overlay (saved-goals-inline pass): the saved-plans list
		// above the footer, reached from the create grid.
		if (dockSavedActive)
		{
			actionDock.setExpanded(true);
			if (!dockSavedMounted)
			{
				actionDock.setExpandedComponent(buildSavedPlansSurface());
				dockSavedMounted = true;
			}
			return;
		}

		// Inline move / copy-to-section overlay (inline-move pass): the section
		// picker that replaced the JOptionPane chooser. Same overlay pattern as the
		// color / tag / share surfaces - the selection stays put underneath, and
		// picking (or Back) remounts it. A target that vanished (its goals deleted,
		// or the multi-selection cleared) drops the overlay.
		if (dockMoveMode != null)
		{
			if (!moveTargetValid())
			{
				dockMoveMode = null;
				dockMoveGoalIds = null;
				dockMoveMounted = false;
			}
			else
			{
				actionDock.setExpanded(true);
				if (!dockMoveMounted)
				{
					actionDock.setExpandedComponent(buildMoveSurface());
					dockMoveMounted = true;
				}
				return;
			}
		}

		// A selection means the dock leaves the create surface for the edit/multi
		// surface; reset the create navigation so returning to EMPTY starts at the
		// type grid, forget the mounted create view, and rest the create surface
		// (so a later deselect shows just the footer, not a stale expanded grid).
		if (ctx.getState() != com.goalplanner.ui.dock.DockContext.State.EMPTY)
		{
			dockCreateNav = CreateNav.GRID;
			dockCreateType = null;
			dockPendingCreate = null;
			dockCreateMounted = false;
			dockCreateMountedNav = null;
			dockCreateMountedType = null;
			dockCreateStep = CreateStep.PICKER;
			dockCreateMountedStep = null;
			dockCreateOpen = false;
			dockCreateTargetSection = null;
			resetCreatePicks();
			// A pending seed survives only the transition INTO the create surface
			// (selection just cleared -> EMPTY), so it must not be cleared here.
			// makeRepeatableFrom* sets it, then clears the selection; by the time
			// this runs the state is already EMPTY, so this branch is skipped.
		}
		// Anything but a single-goal selection drops the mounted EDIT form, so a
		// fresh selection remounts it (and MULTI/EMPTY do not keep it around).
		if (ctx.getState() != com.goalplanner.ui.dock.DockContext.State.GOAL)
		{
			dockEditMounted = false;
			dockEditMountedGoalId = null;
			dockEditGroup = null;
		}

		java.util.List<com.goalplanner.ui.dock.ActionDock.Item> top = new java.util.ArrayList<>();
		java.util.List<com.goalplanner.ui.dock.ActionDock.Item> bottom = new java.util.ArrayList<>();
		String hint = null;
		// Optional full-width lead button above the strips (MULTI "Deselect (N)").
		com.goalplanner.ui.dock.ActionDock.Item lead = null;

		switch (ctx.getState())
		{
			case GOAL:
			{
				String gid = ctx.getSoleGoalId();
				Goal g = goalStore.findGoalById(gid);
				if (g == null) break;
				// Unified create/edit form (ADR-0008): a selected goal shows the
				// SAME per-type form as create, pre-filled, with its parameters as
				// inline commit-on-blur fields plus the lifecycle action chips. The
				// surface auto-expands above the permanent footer. It is a custom
				// component, so mount it via setExpandedComponent and return early.
				// Guard the remount so a same-goal refresh does not wipe an
				// in-progress field edit.
				if (usesUnifiedEditForm(g.getType()))
				{
					actionDock.setExpanded(true);
					if (!dockEditMounted || !gid.equals(dockEditMountedGoalId))
					{
						// A genuinely different goal resets the chip drill-in to the
						// top level; a same-goal re-render (refreshEditForm) keeps it.
						if (!gid.equals(dockEditMountedGoalId))
						{
							dockEditGroup = null;
						}
						actionDock.setExpandedComponent(buildEditSurface(g));
						dockEditMounted = true;
						dockEditMountedGoalId = gid;
					}
					return;
				}
				// Legacy button-strip fallback (e.g. COLLECTION_LOG): the strips
				// carry a "1 selected" hint since there is no SELECTED bar here.
				hint = "1 selected";
				buildGoalDock(g, top, bottom);
				break;
			}
			case MULTI:
			{
				int n = ctx.getCount();
				hint = n + " selected";
				java.util.Set<String> ids =
					new java.util.LinkedHashSet<>(api.getSelectedGoalIds());
				buildMultiDock(ids, top, bottom);
				// Full-width "Deselect (N)" pinned at the top of the multi surface.
				lead = new com.goalplanner.ui.dock.ActionDock.Item("Deselect (" + n + ")",
					"Clear the selection", () -> api.clearGoalSelection());
				break;
			}
			case SECTION:
			{
				// A selected section renders its actions as a custom surface above
				// the permanent footer, exactly like the goal EDIT view. Mount it
				// via setExpandedComponent and return early. Guard the remount so a
				// same-section refresh (e.g. an in-group action) does not thrash it.
				com.goalplanner.api.SectionView sv = findSectionView(ctx.getSectionId());
				if (sv == null)
				{
					// Raced away between the stale-guard and here; treat as EMPTY.
					selectedSectionId = null;
					actionDock.setExpanded(dockCreateOpen);
					if (!dockCreateMounted
						|| dockCreateMountedNav != dockCreateNav
						|| dockCreateMountedType != dockCreateType
						|| dockCreateMountedStep != dockCreateStep)
					{
						mountCreateSurface();
					}
					return;
				}
				actionDock.setExpanded(true);
				if (!dockSectionMounted
					|| !sv.id.equals(dockSectionMountedId)
					|| dockSectionMountedGroup != dockSectionGroup)
				{
					// A genuinely different section resets the drill-in to the top.
					if (!sv.id.equals(dockSectionMountedId))
					{
						dockSectionGroup = null;
					}
					actionDock.setExpandedComponent(buildSectionDock(sv));
					dockSectionMounted = true;
					dockSectionMountedId = sv.id;
					dockSectionMountedGroup = dockSectionGroup;
				}
				return;
			}
			case EMPTY:
			default:
			{
				// Nothing selected: the surface above the footer is the create
				// surface (type grid -> per-type forms, all in the dock). At rest
				// it stays collapsed (just the footer shows, list keeps its
				// height); the footer's Create buttons open it (dockCreateOpen).
				// Rebuild the mounted view only when it no longer matches the
				// requested navigation, so a half-filled form survives unrelated
				// refreshes. Expansion tracks dockCreateOpen, which is preserved
				// across create navigation but reset to false on any selection, so
				// a deselect rests back to the footer.
				actionDock.setExpanded(dockCreateOpen);
				if (!dockCreateMounted
					|| dockCreateMountedNav != dockCreateNav
					|| dockCreateMountedType != dockCreateType
					|| dockCreateMountedStep != dockCreateStep)
				{
					mountCreateSurface();
				}
				return;
			}
		}
		// GOAL (legacy strip) / MULTI: the action strips auto-expand above the
		// permanent footer.
		actionDock.setExpanded(true);
		actionDock.setRows(new com.goalplanner.ui.dock.ActionDock.Rows(hint, top, bottom, lead));
	}

	/**
	 * The permanent footer's Create Goal button. Always switches the surface to
	 * the create type grid and expands it - unless that grid is already showing at
	 * rest (nothing selected), in which case tapping again collapses (toggle). A
	 * selection is cleared first so the dock reads unambiguously as create mode;
	 * dockCreateOpen keeps the surface expanded across the async reselect refresh.
	 */
	private void onFooterCreateGoal()
	{
		boolean hasSelection = !api.getSelectedGoalIds().isEmpty();
		// Toggle closed only when the create-goal surface is what is already open.
		if (!hasSelection && dockCreateOpen && actionDock.isExpanded()
			&& (dockCreateNav == CreateNav.GRID || dockCreateNav == CreateNav.FORM
				|| dockCreateNav == CreateNav.SECTION_PICK))
		{
			dockCreateOpen = false;
			actionDock.setExpanded(false);
			return;
		}
		if (hasSelection)
		{
			// Leave the edit/multi surface for create; the list stops highlighting.
			api.clearGoalSelection();
		}
		dockCreateOpen = true;
		dockCreateNav = CreateNav.GRID;
		dockCreateType = null;
		dockPendingCreate = null;
		dockCreateStep = CreateStep.PICKER;
		// A plain footer create is not section-targeted (Task 1): drop any armed
		// landing section so it prompts normally.
		dockCreateTargetSection = null;
		resetCreatePicks();
		mountCreateSurface();
		actionDock.setExpanded(true);
	}

	/**
	 * The permanent footer's Create Section button. Switches the surface to the
	 * in-dock new-section form and expands it; tapping again while that form is the
	 * one showing at rest collapses (toggle). Mirrors {@link #onFooterCreateGoal}.
	 */
	private void onFooterCreateSection()
	{
		boolean hasSelection = !api.getSelectedGoalIds().isEmpty();
		if (!hasSelection && dockCreateOpen && actionDock.isExpanded()
			&& dockCreateNav == CreateNav.SECTION_NEW)
		{
			dockCreateOpen = false;
			actionDock.setExpanded(false);
			return;
		}
		if (hasSelection)
		{
			api.clearGoalSelection();
		}
		dockCreateOpen = true;
		dockCreateNav = CreateNav.SECTION_NEW;
		dockCreateType = null;
		dockPendingCreate = null;
		dockCreateTargetSection = null;
		mountCreateSurface();
		actionDock.setExpanded(true);
	}

	// ============================================================
	// Select-surface (ADR-0007): the GOAL and MULTI action strips.
	//
	// Every goal-selection and multi-selection action from the right-click
	// menus (GoalContextMenuBuilder) is migrated here, so the dock replaces
	// right-click on goals. Assembly lives ONLY in these helpers, reached from
	// refreshDock() (the single-place rule). The right-click menus + the dialogs
	// they open stay alive alongside until in-client parity is verified; every
	// dock button here REUSES an existing dialog/flow rather than rebuilding one.
	// Positional menu-isms (move up/down/top/bottom, add above/below,
	// deselect-this / all-but-this) are intentionally dropped per the parity
	// table - drag-reorder and the dock's Deselect cover them.
	// ============================================================

	/** XP chunk presets offered when deriving a repeatable skill slice. */
	private static final int[] DOCK_XP_CHUNKS = {10_000, 50_000, 100_000, 300_000, 1_000_000};
	/** Kill-count chunk presets offered when deriving a repeatable activity slice. */
	private static final int[] DOCK_KILL_CHUNKS = {5, 10, 20, 50};

	/** A dock action button. */
	private static ActionDock.Item item(String label, String tooltip, Runnable action)
	{
		return new ActionDock.Item(label, tooltip, action);
	}

	/** A group separator in a dock strip (small-caps label, no action). */
	private static ActionDock.Item sep(String label)
	{
		return new ActionDock.Item(label, null, null);
	}

	/**
	 * Assemble the one-goal action strips. Top row carries the lifecycle +
	 * primary edits (complete, optional, amount, repeat, color); the bottom row
	 * carries the organize cluster (tags, requirements, move, share, remove),
	 * grouped by separators and reached by horizontal scroll when it overflows.
	 */
	private void buildGoalDock(Goal g,
		java.util.List<ActionDock.Item> top,
		java.util.List<ActionDock.Item> bottom)
	{
		final String gid = g.getId();
		final GoalType type = g.getType();
		final boolean complete = g.isComplete();
		final boolean manual = type == GoalType.CUSTOM || type == GoalType.ITEM_GRIND;

		// --- TOP: lifecycle + primary edits ---
		if (complete)
		{
			top.add(item("Reopen", "Mark incomplete and let tracking re-derive it",
				() -> api.markGoalIncomplete(gid)));
		}
		else if (manual)
		{
			top.add(item("Complete", "Mark this goal complete",
				() -> api.markGoalComplete(gid)));
		}
		if (!complete)
		{
			top.add(item(g.isOptional() ? "Required" : "Optional",
				g.isOptional() ? "Mark this goal required" : "Mark this goal optional",
				() -> api.setGoalOptional(gid, !g.isOptional())));
		}

		// Change Amount - SKILL / ITEM_GRIND / BOSS carry a numeric target.
		if (type == GoalType.SKILL || type == GoalType.ITEM_GRIND || type == GoalType.BOSS)
		{
			top.add(item("Amount", "Change this goal's target", () -> dockChangeAmount(g)));
		}

		// Repeat - CUSTOM (set a period), a derived slice (edit period/amount),
		// or a SKILL/derivable-item grind (derive a per-period slice).
		ActionDock.Item repeat = buildRepeatItem(g);
		if (repeat != null)
		{
			top.add(repeat);
		}

		// Change Name / Description - CUSTOM goals only, and not once complete.
		if (type == GoalType.CUSTOM && !complete)
		{
			top.add(item("Name", "Change this goal's name", () -> dockChangeName(g)));
			top.add(item("Desc", "Change this goal's description", () -> dockChangeDescription(g)));
		}

		top.add(item("Color", "Change this goal's color",
			() -> openColorSurfaceForGoal(g.getId())));

		// --- BOTTOM: organize ---
		// Tags.
		bottom.add(sep("tag"));
		bottom.add(item("Add tag", "Add a tag to this goal", () -> openTagAddSurfaceForGoal(gid)));
		java.util.List<Tag> removable = removableTagsFor(g);
		if (!removable.isEmpty())
		{
			bottom.add(item("Drop tags", "Remove tags from this goal",
				() -> openTagRemoveSurfaceForGoal(gid)));
		}

		// Requirements graph - hidden on completed goals (reference history).
		if (!complete)
		{
			bottom.add(sep("requires"));
			bottom.add(item("Requires", "Then click another goal to require it",
				() -> enterRelationMode(gid, /*sourceRequiresTarget=*/true)));
			bottom.add(item("Required by", "Then click another goal that should require this",
				() -> enterRelationMode(gid, /*sourceRequiresTarget=*/false)));
			if (!api.getRequirements(gid).isEmpty())
			{
				bottom.add(item("Drop reqs", "Remove requirements of this goal",
					() -> dockRemoveRequirements(g)));
			}
			if (!api.getDependents(gid).isEmpty())
			{
				bottom.add(item("Drop dependents", "Remove dependents of this goal",
					() -> dockRemoveDependents(g)));
			}
		}

		// Seed a quest/diary/boss goal's game-data requirements into its section.
		if (goalHasSeedableReqs(g))
		{
			bottom.add(item("Add reqs to section",
				"Add this goal's requirements into its section",
				() -> dockSeedReqs(g)));
		}

		// Move / duplicate / restore.
		bottom.add(sep("organize"));
		bottom.add(item("Move to section", "Move this goal to another section",
			() -> openMoveSurface(MoveMode.MOVE, java.util.Collections.singletonList(gid))));
		bottom.add(item("Copy to section", "Duplicate this goal into another section",
			() -> openMoveSurface(MoveMode.COPY, java.util.Collections.singletonList(gid))));
		if (api.isGoalOverridden(gid))
		{
			bottom.add(item("Restore defaults", "Reset tags and color to their defaults",
				() -> api.bulkRestoreDefaults(java.util.Collections.singleton(gid))));
		}

		// Loadout Lab link-in - BOSS goals only, install-aware (mirrors the menu).
		if (type == GoalType.BOSS && g.getBossName() != null && !g.getBossName().isEmpty())
		{
			LoadoutLabState labState = loadoutLabState();
			if (labState == LoadoutLabState.ENABLED)
			{
				final String monster = g.getBossName();
				bottom.add(sep("lab"));
				bottom.add(item("Loadout Lab", "Search this boss in Loadout Lab",
					() -> searchLoadoutLab(monster)));
			}
			else if (labState == LoadoutLabState.INSTALLED_DISABLED)
			{
				bottom.add(sep("lab"));
				bottom.add(new ActionDock.Item("Lab is off",
					"Loadout Lab is installed but disabled", () -> {}, false));
			}
		}

		// Share this single goal as a paste-anywhere code (inline Share surface).
		if (isShareAvailable())
		{
			final java.util.List<String> shareIds = java.util.Collections.singletonList(gid);
			bottom.add(sep("share"));
			bottom.add(item("Share", "Copy or save a share code for this goal",
				() -> openShareForGoals(shareIds)));
		}

		// Deselect / Remove close out the strip.
		bottom.add(sep("goal"));
		bottom.add(item("Deselect", "Clear the selection", () -> api.clearGoalSelection()));
		bottom.add(item("Remove", "Remove this goal (undoable)", () -> api.removeGoal(gid)));
	}

	/**
	 * Assemble the multi-selection action strips. Mirrors the bulk right-click
	 * menu: bulk lifecycle in the top row, bulk organize (move/duplicate, tags,
	 * restore, share) in the bottom row.
	 */
	private void buildMultiDock(java.util.Set<String> ids,
		java.util.List<ActionDock.Item> top,
		java.util.List<ActionDock.Item> bottom)
	{
		final java.util.LinkedHashSet<String> sel = new java.util.LinkedHashSet<>(ids);
		java.util.List<Goal> goals = new ArrayList<>();
		for (Goal g : goalStore.getGoals())
		{
			if (sel.contains(g.getId()))
			{
				goals.add(g);
			}
		}

		// --- TOP: bulk lifecycle ---
		top.add(item("Reset done",
			"Reopen every completed goal in the selection (one undo)",
			() -> api.bulkMarkIncomplete(sel)));
		// Mark complete - only when every selected goal is manually completable
		// (all CUSTOM), matching the bulk menu.
		boolean allCustom = !goals.isEmpty();
		for (Goal g : goals)
		{
			if (g.getType() != GoalType.CUSTOM)
			{
				allCustom = false;
				break;
			}
		}
		if (allCustom)
		{
			final java.util.List<Goal> completeTargets = new ArrayList<>(goals);
			top.add(item("Complete", "Mark every selected goal complete (one undo)",
				() -> {
					api.beginCompound("Mark " + completeTargets.size() + " complete");
					try
					{
						for (Goal g : completeTargets)
						{
							api.markGoalComplete(g.getId());
						}
					}
					finally
					{
						api.endCompound();
					}
				}));
		}
		// Mark optional / required - applies to the non-completed goals.
		final java.util.List<Goal> optionalTargets = new ArrayList<>();
		for (Goal g : goals)
		{
			if (!g.isComplete())
			{
				optionalTargets.add(g);
			}
		}
		if (!optionalTargets.isEmpty())
		{
			top.add(item("Optional", "Mark the selected goals optional (one undo)",
				() -> bulkSetOptional(optionalTargets, true)));
			top.add(item("Required", "Mark the selected goals required (one undo)",
				() -> bulkSetOptional(optionalTargets, false)));
		}
		top.add(item("Remove", "Remove every selected goal (one undo)",
			() -> api.bulkRemoveGoals(sel)));

		// --- BOTTOM: bulk organize ---
		final java.util.List<Goal> recolor = new ArrayList<>(goals);
		if (!recolor.isEmpty())
		{
			bottom.add(sep("edit"));
			bottom.add(item("Color", "Change the color of every selected goal",
				() -> openColorSurfaceForMulti()));
		}

		// Tags.
		final java.util.List<Goal> tagAdd = new ArrayList<>(goals);
		java.util.List<com.goalplanner.api.GoalPlannerInternalApi.TagRemovalOption> removableOpts =
			api.getRemovableTagsForSelection(sel);
		if (!tagAdd.isEmpty() || !removableOpts.isEmpty())
		{
			bottom.add(sep("tag"));
			if (!tagAdd.isEmpty())
			{
				bottom.add(item("Add tag", "Add a tag to every selected goal",
					() -> openTagAddSurfaceForMulti()));
			}
			if (!removableOpts.isEmpty())
			{
				bottom.add(item("Drop tags", "Remove tags from the selected goals",
					() -> openTagRemoveSurfaceForMulti()));
			}
		}

		// Move / duplicate / restore.
		bottom.add(sep("organize"));
		bottom.add(item("Move to section", "Move the selected goals to another section",
			() -> openMoveSurface(MoveMode.BULK_MOVE, sel)));
		bottom.add(item("Copy to section", "Duplicate the selected goals into another section",
			() -> openMoveSurface(MoveMode.BULK_COPY, sel)));
		boolean anyOverridden = false;
		for (String id : sel)
		{
			if (api.isGoalOverridden(id))
			{
				anyOverridden = true;
				break;
			}
		}
		if (anyOverridden)
		{
			bottom.add(item("Restore defaults",
				"Reset tags and color to defaults for the selected goals",
				() -> api.bulkRestoreDefaults(sel)));
		}

		// Share the selection as one code (inline Share surface).
		if (isShareAvailable())
		{
			final java.util.List<String> shareIds = new ArrayList<>(sel);
			bottom.add(sep("share"));
			bottom.add(item("Share", "Copy or save one share code for the selected goals",
				() -> openShareForGoals(shareIds)));
		}

		// Deselect is no longer a trailing chip: it is a full-width "Deselect (N)"
		// button pinned at the TOP of the multi surface (Task 2), built as the Rows
		// lead in refreshDock's MULTI case.
	}

	private void bulkSetOptional(java.util.List<Goal> targets, boolean optional)
	{
		api.beginCompound("Mark " + targets.size() + (optional ? " optional" : " required"));
		try
		{
			for (Goal g : targets)
			{
				api.setGoalOptional(g.getId(), optional);
			}
		}
		finally
		{
			api.endCompound();
		}
	}

	// ------------------------------------------------------------
	// Select-surface action helpers (all reuse an existing dialog/flow).
	// ------------------------------------------------------------

	/** A single-choice picker rendered as a combo prompt - the dock's stand-in
	 *  for a menu submenu (Move to Section, Repeat period, etc.). Runs the action
	 *  parallel to the chosen label; a cancel is a no-op. */
	private void dockChooser(String title, java.util.List<String> labels,
		java.util.List<Runnable> actions)
	{
		if (labels.isEmpty())
		{
			return;
		}
		Object sel = javax.swing.JOptionPane.showInputDialog(this, title, title,
			javax.swing.JOptionPane.PLAIN_MESSAGE, null,
			labels.toArray(), labels.get(0));
		if (sel == null)
		{
			return;
		}
		int i = labels.indexOf(sel.toString());
		if (i >= 0)
		{
			actions.get(i).run();
		}
	}

	/** Route Change Amount to the right editor: the shared skill-target dialog,
	 *  or a quantity/kill-count prompt for item/boss goals. */
	private void dockChangeAmount(Goal g)
	{
		if (g.getType() == GoalType.SKILL)
		{
			dialogFactory.showChangeSkillTargetDialog(g);
			return;
		}
		String noun = g.getType() == GoalType.BOSS ? "kill count" : "quantity";
		String input = javax.swing.JOptionPane.showInputDialog(this,
			"New target " + noun + " for " + g.getName() + ":",
			String.valueOf(g.getTargetValue()));
		if (input == null)
		{
			return;
		}
		try
		{
			int newTarget = Integer.parseInt(input.trim().replace(",", ""));
			if (newTarget > 0)
			{
				api.changeTarget(g.getId(), newTarget);
			}
		}
		catch (NumberFormatException ignored)
		{
		}
	}

	private void dockChangeName(Goal g)
	{
		String input = javax.swing.JOptionPane.showInputDialog(this, "New name:", g.getName());
		if (input != null && !input.trim().isEmpty())
		{
			api.editCustomGoal(g.getId(), input.trim(), null);
		}
	}

	private void dockChangeDescription(Goal g)
	{
		String input = javax.swing.JOptionPane.showInputDialog(this, "New description:",
			g.getDescription() != null ? g.getDescription() : "");
		if (input != null)
		{
			api.editCustomGoal(g.getId(), null, input.trim());
		}
	}

	/** Tags removable from a goal: any tag for CUSTOM, else only user-added
	 *  (non-default) tags. Mirrors the single-item menu's rule. */
	private java.util.List<Tag> removableTagsFor(Goal g)
	{
		java.util.List<Tag> removable = new ArrayList<>();
		if (g.getTagIds() == null || g.getTagIds().isEmpty())
		{
			return removable;
		}
		java.util.List<String> defaults = g.getDefaultTagIds() != null
			? g.getDefaultTagIds() : java.util.Collections.emptyList();
		for (String tagId : g.getTagIds())
		{
			Tag t = goalStore.findTag(tagId);
			if (t == null)
			{
				continue;
			}
			if (g.getType() == GoalType.CUSTOM || !defaults.contains(tagId))
			{
				removable.add(t);
			}
		}
		return removable;
	}

	private void dockAddTag(Goal g)
	{
		TagPickerDialog.Result picked = TagPickerDialog.show(this, "Add Tag", api);
		if (picked != null)
		{
			api.addTagWithCategory(g.getId(), picked.label, picked.category.name());
		}
	}

	private void dockRemoveTags(Goal g, java.util.List<Tag> removable)
	{
		java.util.List<MultiSelectDialog.Item> items = new ArrayList<>();
		for (Tag t : removable)
		{
			items.add(new MultiSelectDialog.Item(
				t.getLabel(),
				t.getLabel() + " (" + t.getCategory().getDisplayName() + ")"));
		}
		java.util.List<String> chosen = MultiSelectDialog.show(this, "Remove Tags", "Remove", items);
		if (chosen.isEmpty())
		{
			return;
		}
		api.beginCompound("Remove " + chosen.size() + " tag(s)");
		try
		{
			for (String label : chosen)
			{
				api.removeTag(g.getId(), label);
			}
		}
		finally
		{
			api.endCompound();
		}
	}

	private void dockRemoveRequirements(Goal g)
	{
		java.util.List<String> reqs = new ArrayList<>(api.getRequirements(g.getId()));
		java.util.List<MultiSelectDialog.Item> items = new ArrayList<>();
		for (String reqId : reqs)
		{
			items.add(new MultiSelectDialog.Item(reqId, reorderController.goalNameById(reqId)));
		}
		java.util.List<String> chosen = MultiSelectDialog.show(this,
			"Remove Requirements", "Remove", items);
		if (chosen.isEmpty())
		{
			return;
		}
		api.beginCompound("Remove " + chosen.size() + " requirement(s)");
		try
		{
			for (String reqId : chosen)
			{
				api.removeRequirement(g.getId(), reqId);
			}
		}
		finally
		{
			api.endCompound();
		}
	}

	private void dockRemoveDependents(Goal g)
	{
		java.util.List<String> deps = new ArrayList<>(api.getDependents(g.getId()));
		java.util.List<MultiSelectDialog.Item> items = new ArrayList<>();
		for (String depId : deps)
		{
			items.add(new MultiSelectDialog.Item(depId, reorderController.goalNameById(depId)));
		}
		java.util.List<String> chosen = MultiSelectDialog.show(this,
			"Remove Dependents", "Remove", items);
		if (chosen.isEmpty())
		{
			return;
		}
		api.beginCompound("Remove " + chosen.size() + " dependent(s)");
		try
		{
			for (String depId : chosen)
			{
				api.removeRequirement(depId, g.getId());
			}
		}
		finally
		{
			api.endCompound();
		}
	}

	/** True when a quest/diary/boss goal carries game-data requirements that can
	 *  be seeded into its section. Mirrors the menu's gate. */
	private boolean goalHasSeedableReqs(Goal g)
	{
		if (g.getType() == GoalType.QUEST && g.getQuestName() != null)
		{
			try
			{
				net.runelite.api.Quest q = net.runelite.api.Quest.valueOf(g.getQuestName());
				return com.goalplanner.data.QuestRequirements.hasRequirements(q);
			}
			catch (IllegalArgumentException ignored)
			{
				return false;
			}
		}
		if (g.getType() == GoalType.DIARY && g.getName() != null)
		{
			com.goalplanner.data.AchievementDiaryData.Tier tier = parseDiaryTier(g.getDescription());
			return tier != null
				&& com.goalplanner.data.DiaryRequirements.hasRequirements(g.getName(), tier);
		}
		return g.getType() == GoalType.BOSS && g.getBossName() != null
			&& com.goalplanner.data.BossKillData.getPrereqs(g.getBossName()) != null;
	}

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

	/** Offer the "incomplete only" vs "all" requirement seed, then run it on the
	 *  client thread (the incomplete variant reads live player state). */
	private void dockSeedReqs(Goal g)
	{
		final String gid = g.getId();
		final boolean diary = g.getType() == GoalType.DIARY;
		final boolean boss = g.getType() == GoalType.BOSS;
		java.util.List<String> labels = java.util.Arrays.asList(
			"Incomplete only", "All (whole tree)");
		java.util.List<Runnable> actions = java.util.Arrays.asList(
			() -> seedReqsOnClientThread(gid, false, diary, boss),
			() -> seedReqsOnClientThread(gid, true, diary, boss));
		dockChooser("Add requirements to this section", labels, actions);
	}

	private void seedReqsOnClientThread(String goalId, boolean includeMet, boolean diary, boolean boss)
	{
		runOnClientThread(() -> {
			if (diary)
			{
				api.seedDiaryRequirementsForGoal(goalId, includeMet);
			}
			else if (boss)
			{
				api.seedBossRequirementsForGoal(goalId, includeMet);
			}
			else
			{
				api.seedRequirementsForGoal(goalId, includeMet);
			}
		});
	}

	// ----- Repeat / repeatable -----

	/** The Repeat dock button for this goal, or null when repeating does not
	 *  apply (auto-tracked non-skill types with no derivable activity). */
	private ActionDock.Item buildRepeatItem(Goal g)
	{
		if (g.getRepeatChunk() > 0)
		{
			return item("Repeat", "Change how often / how much this repeats",
				() -> dockEditRepeat(g));
		}
		if (g.getType() == GoalType.CUSTOM)
		{
			return item("Repeat", "Repeat this goal every day / week / month",
				() -> dockSetCustomRepeat(g));
		}
		if (g.getType() == GoalType.SKILL && g.getSkillName() != null)
		{
			return item("Repeat", "Turn this into a per-period XP slice",
				() -> dockDeriveRepeat(g, null));
		}
		if (g.getItemId() > 0
			&& !com.goalplanner.data.ItemActivityResolver.resolve(g.getItemId()).isEmpty())
		{
			return item("Repeat", "Turn this into a per-period kill slice",
				() -> dockDeriveItemRepeat(g));
		}
		return null;
	}

	private java.util.List<RepeatPeriod> derivePeriods()
	{
		return java.util.Arrays.asList(
			RepeatPeriod.DAILY, RepeatPeriod.WEEKLY, RepeatPeriod.MONTHLY);
	}

	private void dockSetCustomRepeat(Goal g)
	{
		java.util.List<String> labels = new ArrayList<>();
		java.util.List<Runnable> actions = new ArrayList<>();
		for (RepeatPeriod period : RepeatPeriod.values())
		{
			final RepeatPeriod p = period;
			labels.add(g.getRepeatEvery() == period ? period.getLabel() + " (current)" : period.getLabel());
			actions.add(() -> api.setGoalRepeat(g.getId(), p));
		}
		dockChooser("Repeat every", labels, actions);
	}

	/** Edit an existing derived/repeatable goal: change the period, or the
	 *  per-period amount. Mirrors the menu's Repeats / Amount submenus. */
	private void dockEditRepeat(Goal g)
	{
		java.util.List<String> labels = java.util.Arrays.asList("Change how often", "Change how much");
		java.util.List<Runnable> actions = java.util.Arrays.asList(
			() -> dockEditRepeatPeriod(g),
			() -> dockEditRepeatAmount(g));
		dockChooser("Repeat", labels, actions);
	}

	private void dockEditRepeatPeriod(Goal g)
	{
		java.util.List<String> labels = new ArrayList<>();
		java.util.List<Runnable> actions = new ArrayList<>();
		for (RepeatPeriod period : derivePeriods())
		{
			final RepeatPeriod p = period;
			labels.add(g.getRepeatEvery() == period ? period.getLabel() + " (current)" : period.getLabel());
			actions.add(() -> api.setGoalRepeat(g.getId(), p));
		}
		dockChooser("Repeats every", labels, actions);
	}

	private void dockEditRepeatAmount(Goal g)
	{
		boolean skill = g.getType() == GoalType.SKILL;
		int[] sizes = skill ? DOCK_XP_CHUNKS : DOCK_KILL_CHUNKS;
		String unit = skill ? "XP" : "kills";
		java.util.List<String> labels = new ArrayList<>();
		java.util.List<Runnable> actions = new ArrayList<>();
		for (int size : sizes)
		{
			final int s = size;
			String label = (skill ? com.goalplanner.util.FormatUtil.formatNumber(size) : String.valueOf(size))
				+ " " + unit;
			labels.add(g.getRepeatChunk() == size ? label + " (current)" : label);
			actions.add(() -> api.setGoalRepeatChunk(g.getId(), s));
		}
		labels.add("Custom...");
		actions.add(() -> {
			Integer chunk = promptChunk(unit);
			if (chunk != null)
			{
				api.setGoalRepeatChunk(g.getId(), chunk);
			}
		});
		dockChooser("Amount per period", labels, actions);
	}

	/** Derive a repeatable slice from a skill goal (activityName null) or a
	 *  resolved item activity. Picks a period, then an amount, then creates on
	 *  the client thread (the derive reads live XP / kill-count). */
	/** "Make repeatable" on a plain SKILL grind (note 5): hand off to the create
	 *  flow pre-seeded for a repeatable skill goal (skill preselected, repeat
	 *  disclosure ON, target prefilled). Clears the selection first so the dock
	 *  leaves the edit surface for the create surface. May create a fresh parent +
	 *  slice - that is the create flow and matches the ask. */
	private void makeRepeatableFromSkill(Goal g)
	{
		CreateSeed seed = new CreateSeed();
		seed.skill = skillOf(g);
		seed.repeatable = true;
		seed.targetXp = g.getTargetValue();
		dockCreateSeed = seed;
		api.clearGoalSelection();
		navigateCreate(GoalType.SKILL);
	}

	/** "Make repeatable" on a plain BOSS grind (note 5): hand off to the create
	 *  flow pre-seeded for a repeatable boss goal (boss preselected, repeat ON,
	 *  target kill count prefilled). */
	private void makeRepeatableFromBoss(Goal g)
	{
		CreateSeed seed = new CreateSeed();
		seed.bossName = g.getBossName();
		seed.repeatable = true;
		seed.targetCount = g.getTargetValue();
		dockCreateSeed = seed;
		api.clearGoalSelection();
		navigateCreate(GoalType.BOSS);
	}

	private void dockDeriveRepeat(Goal g, String activityName)
	{
		boolean skill = g.getType() == GoalType.SKILL;
		final String unit = skill ? "XP" : "kills";
		java.util.List<String> periodLabels = new ArrayList<>();
		java.util.List<Runnable> periodActions = new ArrayList<>();
		for (RepeatPeriod period : derivePeriods())
		{
			final RepeatPeriod p = period;
			periodLabels.add(period.getLabel());
			periodActions.add(() -> {
				int[] sizes = skill ? DOCK_XP_CHUNKS : DOCK_KILL_CHUNKS;
				java.util.List<String> labels = new ArrayList<>();
				java.util.List<Runnable> actions = new ArrayList<>();
				for (int size : sizes)
				{
					final int s = size;
					String label = (skill ? com.goalplanner.util.FormatUtil.formatNumber(size)
						: String.valueOf(size)) + " " + unit;
					labels.add(label);
					actions.add(() -> runOnClientThread(() ->
						api.createDerivedRepeatGoal(g.getId(), p, s, activityName)));
				}
				labels.add("Custom...");
				actions.add(() -> {
					Integer chunk = promptChunk(unit);
					if (chunk != null)
					{
						runOnClientThread(() ->
							api.createDerivedRepeatGoal(g.getId(), p, chunk, activityName));
					}
				});
				dockChooser("How much " + unit + " per period", labels, actions);
			});
		}
		dockChooser("Repeat every", periodLabels, periodActions);
	}

	/** An item grind can drop from several activities (shared collection-log
	 *  slots); pick which one to farm, then derive as usual. */
	private void dockDeriveItemRepeat(Goal g)
	{
		java.util.List<com.goalplanner.data.ItemActivityResolver.Activity> activities =
			com.goalplanner.data.ItemActivityResolver.resolve(g.getItemId());
		if (activities.isEmpty())
		{
			return;
		}
		if (activities.size() == 1)
		{
			dockDeriveRepeat(g, activities.get(0).getName());
			return;
		}
		java.util.List<String> labels = new ArrayList<>();
		java.util.List<Runnable> actions = new ArrayList<>();
		for (com.goalplanner.data.ItemActivityResolver.Activity a : activities)
		{
			final String name = a.getName();
			labels.add(name);
			actions.add(() -> dockDeriveRepeat(g, name));
		}
		dockChooser("Which activity?", labels, actions);
	}

	/** Prompt for a positive whole-number chunk, tolerating typed separators.
	 *  Returns null on cancel or invalid input (with a nudge on invalid). */
	private Integer promptChunk(String unit)
	{
		String input = javax.swing.JOptionPane.showInputDialog(this,
			"How much " + unit + " per period?", "Repeatable goal",
			javax.swing.JOptionPane.PLAIN_MESSAGE);
		if (input == null)
		{
			return null;
		}
		String cleaned = input.trim().replace(",", "").replace(" ", "").replace("_", "");
		int chunk;
		try
		{
			chunk = Integer.parseInt(cleaned);
		}
		catch (NumberFormatException ex)
		{
			javax.swing.JOptionPane.showMessageDialog(this,
				"Enter a whole number, for example 300000.",
				"Repeatable goal", javax.swing.JOptionPane.WARNING_MESSAGE);
			return null;
		}
		if (chunk <= 0)
		{
			javax.swing.JOptionPane.showMessageDialog(this,
				"Enter an amount greater than zero.",
				"Repeatable goal", javax.swing.JOptionPane.WARNING_MESSAGE);
			return null;
		}
		return chunk;
	}

	// ----- Move / duplicate to section -----

	/** Prompt for a new section name; on a non-blank name that creates cleanly,
	 *  run the action with the new section id. Shared by move/duplicate. */
	private void promptNewSectionThen(java.util.function.Consumer<String> action)
	{
		String input = javax.swing.JOptionPane.showInputDialog(this, "New section name:", "");
		if (input != null && !input.trim().isEmpty())
		{
			String newId = api.createSection(input.trim());
			if (newId != null)
			{
				action.accept(newId);
			}
		}
	}

	private void dockMoveToSection(Goal g)
	{
		java.util.List<String> labels = new ArrayList<>();
		java.util.List<Runnable> actions = new ArrayList<>();
		boolean goalInDefault = false;
		java.util.List<com.goalplanner.api.SectionView> destinations = new ArrayList<>();
		for (com.goalplanner.api.SectionView sv : api.queryAllSections())
		{
			if (sv.builtIn)
			{
				if (sv.id.equals(g.getSectionId()))
				{
					goalInDefault = true;
				}
				continue;
			}
			if (sv.id.equals(g.getSectionId()))
			{
				continue;
			}
			destinations.add(sv);
		}
		if (!goalInDefault)
		{
			labels.add("Default (Incomplete / Completed)");
			actions.add(() -> api.moveGoalsToDefault(java.util.Collections.singletonList(g.getId())));
		}
		for (com.goalplanner.api.SectionView dest : destinations)
		{
			final String destId = dest.id;
			labels.add(dest.name);
			actions.add(() -> api.moveGoalToSection(g.getId(), destId));
		}
		labels.add("New section...");
		actions.add(() -> promptNewSectionThen(newId -> api.moveGoalToSection(g.getId(), newId)));
		dockChooser("Move to section", labels, actions);
	}

	private void dockDuplicateToSection(Goal g)
	{
		java.util.List<String> labels = new ArrayList<>();
		java.util.List<Runnable> actions = new ArrayList<>();
		String defaultIncompleteId = null;
		boolean goalInDefault = false;
		java.util.List<com.goalplanner.api.SectionView> destinations = new ArrayList<>();
		for (com.goalplanner.api.SectionView sv : api.queryAllSections())
		{
			if ("INCOMPLETE".equals(sv.kind))
			{
				defaultIncompleteId = sv.id;
			}
			if (sv.builtIn)
			{
				if (sv.id.equals(g.getSectionId()))
				{
					goalInDefault = true;
				}
				continue;
			}
			if (sv.id.equals(g.getSectionId()))
			{
				continue;
			}
			destinations.add(sv);
		}
		if (!goalInDefault && defaultIncompleteId != null)
		{
			final String defId = defaultIncompleteId;
			labels.add("Default (Incomplete / Completed)");
			actions.add(() -> api.duplicateGoalsToSection(
				java.util.Collections.singletonList(g.getId()), defId));
		}
		for (com.goalplanner.api.SectionView dest : destinations)
		{
			final String destId = dest.id;
			labels.add(dest.name);
			actions.add(() -> api.duplicateGoalsToSection(
				java.util.Collections.singletonList(g.getId()), destId));
		}
		labels.add("New section...");
		actions.add(() -> promptNewSectionThen(newId -> api.duplicateGoalsToSection(
			java.util.Collections.singletonList(g.getId()), newId)));
		dockChooser("Duplicate to section", labels, actions);
	}

	private void dockBulkMoveToSection(java.util.List<Goal> goals, java.util.Set<String> ids)
	{
		final java.util.LinkedHashSet<String> sel = new java.util.LinkedHashSet<>(ids);
		java.util.List<String> labels = new ArrayList<>();
		java.util.List<Runnable> actions = new ArrayList<>();
		String defaultIncompleteId = null;
		String defaultCompletedId = null;
		java.util.List<com.goalplanner.api.SectionView> allSections = api.queryAllSections();
		for (com.goalplanner.api.SectionView sv : allSections)
		{
			if ("INCOMPLETE".equals(sv.kind))
			{
				defaultIncompleteId = sv.id;
			}
			if ("COMPLETED".equals(sv.kind))
			{
				defaultCompletedId = sv.id;
			}
		}
		java.util.List<com.goalplanner.api.SectionView> destinations = new ArrayList<>();
		for (com.goalplanner.api.SectionView sv : allSections)
		{
			if (sv.builtIn)
			{
				continue;
			}
			boolean allAlreadyHere = true;
			for (Goal g : goals)
			{
				if (!sv.id.equals(g.getSectionId()))
				{
					allAlreadyHere = false;
					break;
				}
			}
			if (allAlreadyHere)
			{
				continue;
			}
			destinations.add(sv);
		}
		boolean allInDefault = !goals.isEmpty();
		for (Goal g : goals)
		{
			String sid = g.getSectionId();
			if (sid == null || (!sid.equals(defaultIncompleteId) && !sid.equals(defaultCompletedId)))
			{
				allInDefault = false;
				break;
			}
		}
		if (!allInDefault)
		{
			labels.add("Default (Incomplete / Completed)");
			actions.add(() -> api.moveGoalsToDefault(sel));
		}
		for (com.goalplanner.api.SectionView dest : destinations)
		{
			final String destId = dest.id;
			labels.add(dest.name);
			actions.add(() -> api.bulkMoveGoalsToSection(sel, destId));
		}
		labels.add("New section...");
		actions.add(() -> promptNewSectionThen(newId -> api.bulkMoveGoalsToSection(sel, newId)));
		dockChooser("Move " + goals.size() + " to section", labels, actions);
	}

	private void dockBulkDuplicateToSection(java.util.List<Goal> goals, java.util.Set<String> ids)
	{
		final java.util.LinkedHashSet<String> sel = new java.util.LinkedHashSet<>(ids);
		java.util.List<String> labels = new ArrayList<>();
		java.util.List<Runnable> actions = new ArrayList<>();
		String defaultIncompleteId = null;
		String defaultCompletedId = null;
		java.util.List<com.goalplanner.api.SectionView> allSections = api.queryAllSections();
		for (com.goalplanner.api.SectionView sv : allSections)
		{
			if ("INCOMPLETE".equals(sv.kind))
			{
				defaultIncompleteId = sv.id;
			}
			if ("COMPLETED".equals(sv.kind))
			{
				defaultCompletedId = sv.id;
			}
		}
		java.util.List<com.goalplanner.api.SectionView> destinations = new ArrayList<>();
		for (com.goalplanner.api.SectionView sv : allSections)
		{
			if (sv.builtIn)
			{
				continue;
			}
			boolean allAlreadyHere = true;
			for (Goal g : goals)
			{
				if (!sv.id.equals(g.getSectionId()))
				{
					allAlreadyHere = false;
					break;
				}
			}
			if (allAlreadyHere)
			{
				continue;
			}
			destinations.add(sv);
		}
		boolean allInDefault = !goals.isEmpty();
		for (Goal g : goals)
		{
			String sid = g.getSectionId();
			if (sid == null || (!sid.equals(defaultIncompleteId) && !sid.equals(defaultCompletedId)))
			{
				allInDefault = false;
				break;
			}
		}
		if (!allInDefault && defaultIncompleteId != null)
		{
			final String defId = defaultIncompleteId;
			labels.add("Default (Incomplete / Completed)");
			actions.add(() -> api.duplicateGoalsToSection(sel, defId));
		}
		for (com.goalplanner.api.SectionView dest : destinations)
		{
			final String destId = dest.id;
			labels.add(dest.name);
			actions.add(() -> api.duplicateGoalsToSection(sel, destId));
		}
		labels.add("New section...");
		actions.add(() -> promptNewSectionThen(newId -> api.duplicateGoalsToSection(sel, newId)));
		dockChooser("Duplicate " + goals.size() + " to section", labels, actions);
	}

	// --- Inline move / copy-to-section overlay (inline-move pass) --------------
	// Move / Copy to section used to open the dockChooser JOptionPane above (kept
	// intact but dead, like the menus). They now open an IN-DOCK section picker -
	// the SAME surface the create flow's landing step uses (sectionPickSurface) -
	// mounted above the permanent footer. The underlying selection stays put, so
	// picking a section (or Back) returns to the goal / multi surface.

	/** Open the inline section picker for the given mode over the captured ids. */
	private void openMoveSurface(MoveMode mode, java.util.Collection<String> goalIds)
	{
		dockMoveMode = mode;
		dockMoveGoalIds = new java.util.LinkedHashSet<>(goalIds);
		dockMoveMounted = false;
		refreshDock();
	}

	/** Close the move overlay and return to the surface it belongs to (also the
	 *  path after a section is picked). Mirrors {@link #closeColorSurface}: the
	 *  overlay replaced the expanded component, so force the edit surface to
	 *  remount; MULTI rebuilds its strips every refresh and needs no guard drop. */
	private void closeMoveSurface()
	{
		dockMoveMode = null;
		dockMoveGoalIds = null;
		dockMoveMounted = false;
		dockEditMounted = false;
		refreshDock();
	}

	/** Whether the move overlay still has at least one live goal to act on, so
	 *  refreshDock can drop a stale overlay instead of mounting an empty surface. */
	/** A stable signature of what is selected right now (goal ids + section), used
	 *  to notice that the selection moved under an open overlay. */
	private String dockSelectionKey()
	{
		java.util.List<String> ids = new ArrayList<>(api.getSelectedGoalIds());
		java.util.Collections.sort(ids);
		return String.join(",", ids) + "|" + (selectedSectionId == null ? "" : selectedSectionId);
	}

	/** True while any transient overlay (color / tag / share / move / edit form /
	 *  import / saved plans) is driving the dock. */
	private boolean dockOverlayActive()
	{
		return dockColorReturn != null
			|| dockTagMode != null
			|| dockShareScope != null
			|| dockMoveMode != null
			|| dockEditFormGoalId != null
			|| dockImportActive
			|| dockSavedActive;
	}

	/** Drop every transient overlay so the dock falls back to the plain state for
	 *  the current selection. Used when the selection changes mid-flow. */
	private void clearTransientOverlays()
	{
		dockColorReturn = null;
		dockColorTarget = null;
		dockColorMounted = false;
		dockTagMode = null;
		dockTagReturn = null;
		dockTagTarget = null;
		dockTagMounted = false;
		dockShareScope = null;
		dockShareGoalIds = null;
		dockShareSectionId = null;
		dockShareMounted = false;
		dockMoveMode = null;
		dockMoveGoalIds = null;
		dockMoveMounted = false;
		dockEditFormGoalId = null;
		dockEditFormMounted = false;
		dockImportActive = false;
		dockImportMounted = false;
		dockSavedActive = false;
		dockSavedMounted = false;
	}

	private boolean moveTargetValid()
	{
		if (dockMoveMode == null || dockMoveGoalIds == null)
		{
			return false;
		}
		for (String id : dockMoveGoalIds)
		{
			if (goalStore.findGoalById(id) != null)
			{
				return true;
			}
		}
		return false;
	}

	/** Build the inline move/copy section picker for {@link #dockMoveMode}. The
	 *  destination list mirrors the old dialogs exactly: user sections where EVERY
	 *  target already lives are skipped, and the "Default (Incomplete / Completed)"
	 *  row is offered only when at least one target lives outside the built-ins. */
	private JComponent buildMoveSurface()
	{
		final MoveMode mode = dockMoveMode;
		final boolean copy = mode == MoveMode.COPY || mode == MoveMode.BULK_COPY;
		final boolean bulk = mode == MoveMode.BULK_MOVE || mode == MoveMode.BULK_COPY;
		final java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
		final java.util.List<Goal> goals = new ArrayList<>();
		for (String id : dockMoveGoalIds)
		{
			Goal g = goalStore.findGoalById(id);
			if (g != null)
			{
				ids.add(id);
				goals.add(g);
			}
		}

		String incompleteId = null;
		String completedId = null;
		java.util.List<com.goalplanner.api.SectionView> allSections = api.queryAllSections();
		for (com.goalplanner.api.SectionView sv : allSections)
		{
			if ("INCOMPLETE".equals(sv.kind))
			{
				incompleteId = sv.id;
			}
			if ("COMPLETED".equals(sv.kind))
			{
				completedId = sv.id;
			}
		}

		// Skip any user section that ALL targets already live in.
		java.util.Set<String> exclude = new java.util.HashSet<>();
		for (com.goalplanner.api.SectionView sv : allSections)
		{
			if (sv.builtIn)
			{
				continue;
			}
			boolean allAlreadyHere = !goals.isEmpty();
			for (Goal g : goals)
			{
				if (!sv.id.equals(g.getSectionId()))
				{
					allAlreadyHere = false;
					break;
				}
			}
			if (allAlreadyHere)
			{
				exclude.add(sv.id);
			}
		}

		boolean allInDefault = !goals.isEmpty();
		for (Goal g : goals)
		{
			String sid = g.getSectionId();
			if (sid == null || (!sid.equals(incompleteId) && !sid.equals(completedId)))
			{
				allInDefault = false;
				break;
			}
		}

		final java.util.function.Consumer<String> onPick = destId ->
		{
			if (copy)
			{
				api.duplicateGoalsToSection(ids, destId);
			}
			else if (bulk)
			{
				api.bulkMoveGoalsToSection(ids, destId);
			}
			else
			{
				api.moveGoalToSection(ids.iterator().next(), destId);
			}
			closeMoveSurface();
		};
		final java.util.function.Consumer<String> onDefault = allInDefault ? null : incId ->
		{
			if (copy)
			{
				api.duplicateGoalsToSection(ids, incId);
			}
			else
			{
				api.moveGoalsToDefault(ids);
			}
			closeMoveSurface();
		};

		final String verb = copy ? "Copy" : "Move";
		final String title = bulk
			? verb + " " + goals.size() + " to section"
			: verb + " to section";
		final String prompt = bulk
			? (copy ? "Copy these " + goals.size() + " goals into:"
				: "Move these " + goals.size() + " goals to:")
			: (copy ? "Copy this goal into:" : "Move this goal to:");
		return sectionPickSurface(title, prompt,
			copy ? "Back without copying" : "Back without moving",
			"Default (Incomplete / Completed)", onDefault, exclude, onPick,
			verb + " to new section", this::closeMoveSurface);
	}

	private void promptAddSectionFromDock()
	{
		String input = javax.swing.JOptionPane.showInputDialog(this,
			"Section name:", "Add Section", javax.swing.JOptionPane.PLAIN_MESSAGE);
		if (input != null && !input.trim().isEmpty())
		{
			try
			{
				api.createSection(input.trim());
			}
			catch (IllegalArgumentException e)
			{
				javax.swing.JOptionPane.showMessageDialog(this, e.getMessage(),
					"Add Section", javax.swing.JOptionPane.WARNING_MESSAGE);
			}
		}
	}

	// ============================================================
	// Create surface (ADR-0008): the dock's EMPTY-state content.
	//
	// A type GRID navigates into per-type forms, all rendered inside the dock -
	// no dialog, the goal list never leaves. Navigation state is
	// {@link #dockCreateType}; every builder below is called only from
	// {@link #refreshDock()} via {@link #buildCreateSurface()}, keeping all dock
	// content assembly in one place. The right-click create dialogs remain alive
	// alongside this until in-client parity is verified (ADR-0007).
	// ============================================================

	/** The eight type tiles, in mockup order (ADR-0008). COLLECTION_LOG has no
	 *  tile - it is not a user-created goal type here. */
	private static final com.goalplanner.model.GoalType[] CREATE_TILES = {
		com.goalplanner.model.GoalType.SKILL,
		com.goalplanner.model.GoalType.QUEST,
		com.goalplanner.model.GoalType.DIARY,
		com.goalplanner.model.GoalType.COMBAT_ACHIEVEMENT,
		com.goalplanner.model.GoalType.BOSS,
		com.goalplanner.model.GoalType.ITEM_GRIND,
		com.goalplanner.model.GoalType.ACCOUNT,
		com.goalplanner.model.GoalType.CUSTOM,
	};

	private static final Color CREATE_FG = new Color(0xCF, 0xCF, 0xCF);
	private static final Color CREATE_FG_DIM = new Color(0x9A, 0x9A, 0x9A);
	private static final Color CREATE_TILE_BG = new Color(0x33, 0x33, 0x36);
	private static final Color CREATE_TILE_HOVER = new Color(0x45, 0x45, 0x4C);
	private static final Color CREATE_PRIMARY_BG = new Color(0x2E, 0x4D, 0x32);
	private static final Color CREATE_PRIMARY_HOVER = new Color(0x3A, 0x60, 0x40);
	private static final Color CREATE_PRIMARY_FG = new Color(0xD4, 0xE9, 0xD4);
	// Discard (abandon an edit): muted red, tuned to sit beside the green primary
	// without shouting - same weight, opposite intent.
	private static final Color DISCARD_BG = new Color(0x4D, 0x2E, 0x2E);
	private static final Color DISCARD_HOVER = new Color(0x64, 0x3A, 0x3A);
	private static final Color DISCARD_FG = new Color(0xE9, 0xC4, 0xC4);
	private static final Color CREATE_FIELD_BG = new Color(0x2A, 0x2A, 0x2C);
	/** Subtle rounded outline drawn around text fields (glam pass). */
	private static final Color CREATE_FIELD_STROKE = new Color(0x4A, 0x4A, 0x50);
	/** Highlight for the selected icon-button in a picker grid (skill/boss/etc). */
	private static final Color CREATE_SEL_BG = new Color(0x2E, 0x4D, 0x32);
	private static final Color CREATE_SEL_BORDER = new Color(0x5A, 0x9A, 0x5A);
	/** Full-width context-indicator bar (ADR-0008): green "CREATE" tone for the
	 *  create surface, neutral "SELECTED" tone for the edit surface. */
	private static final Color IND_CREATE_BG = new Color(0x1D, 0x2A, 0x1F);
	private static final Color IND_CREATE_FG = new Color(0xBF, 0xE0, 0xBF);
	private static final Color IND_EDIT_BG = new Color(0x24, 0x24, 0x28);
	private static final Color IND_EDIT_FG = new Color(0xBC, 0xBC, 0xBC);
	/** Subtle rounded-card fill behind a create / edit / section surface so it
	 *  reads as a card rather than a hard rectangle (glam pass). */
	private static final Color SURFACE_CARD_BG = new Color(0x23, 0x23, 0x26);

	/** Non-interactive completion indicator on the absolute-goal edit surface: a
	 *  clear green tick when complete, a muted empty box when not. Painted via
	 *  ShapeIcons so they render regardless of the checkbox's disabled state. */
	private static final Color ABSOLUTE_CHECK_DONE = new Color(0x5C, 0xB8, 0x5C);
	private static final Color ABSOLUTE_CHECK_EMPTY = new Color(0x7A, 0x7A, 0x7A);

	/** Every trainable skill (Skill.values() minus OVERALL) for the skill picker
	 *  grid - OVERALL is the account "Total Level" metric, not a skill goal. */
	private static final net.runelite.api.Skill[] GOAL_SKILLS =
		java.util.Arrays.stream(net.runelite.api.Skill.values())
			.filter(s -> s != net.runelite.api.Skill.OVERALL)
			.toArray(net.runelite.api.Skill[]::new);

	/** Build the surface for the current create navigation (note 2 + 3). */
	private JComponent buildCreateSurface()
	{
		switch (dockCreateNav)
		{
			case FORM:         return buildCreateForm(dockCreateType);
			case SECTION_NEW:  return buildSectionNewForm();
			case SECTION_PICK: return buildSectionPickForm();
			case GRID:
			default:           return buildCreateGrid();
		}
	}

	/** Mount the current create surface into the dock and record what was mounted,
	 *  so the guard in {@link #refreshDock()} can skip needless rebuilds. */
	private void mountCreateSurface()
	{
		actionDock.setExpandedComponent(buildCreateSurface());
		dockCreateMounted = true;
		dockCreateMountedNav = dockCreateNav;
		dockCreateMountedType = dockCreateType;
		dockCreateMountedStep = dockCreateStep;
	}

	/** The types whose FORM splits into a PICKER step then a DETAILS step. All
	 *  other types render DETAILS directly. */
	private static boolean isTallType(com.goalplanner.model.GoalType t)
	{
		return t == com.goalplanner.model.GoalType.SKILL
			|| t == com.goalplanner.model.GoalType.BOSS
			|| t == com.goalplanner.model.GoalType.ITEM_GRIND;
	}

	/** Forget any stashed picker selection - called when the create flow leaves a
	 *  form or changes type, so a stale pick never bleeds into the next form. */
	private void resetCreatePicks()
	{
		dockPickedSkill = null;
		dockPickedBoss = null;
		dockPickedItemId = -1;
		dockPickedItemName = null;
	}

	/** Set the create navigation to a type form ({@code null} returns to the type
	 *  grid - used by Back and after a successful create) and re-render the dock.
	 *  Entering a form resets the sub-step to PICKER and clears any stale pick; a
	 *  "Make repeatable" seed (note 5) jumps a tall type straight to DETAILS with
	 *  its pick preselected (the seed's target/repeat is consumed by the DETAILS
	 *  builder). */
	private void navigateCreate(com.goalplanner.model.GoalType type)
	{
		// Navigating the create surface keeps it expanded above the footer (a
		// deselect/selection is the only thing that rests it); mark it open so
		// refreshDock's EMPTY branch does not collapse mid-navigation.
		dockCreateOpen = true;
		dockCreateType = type;
		dockCreateNav = type == null ? CreateNav.GRID : CreateNav.FORM;
		if (type == null)
		{
			dockPendingCreate = null;
			dockCreateSeed = null;
			dockCreateStep = CreateStep.PICKER;
			resetCreatePicks();
		}
		else
		{
			dockCreateStep = CreateStep.PICKER;
			resetCreatePicks();
			// A make-repeatable seed carries the pick: preselect it and skip the
			// picker step. The DETAILS builder consumes the rest of the seed.
			if (dockCreateSeed != null)
			{
				if (type == com.goalplanner.model.GoalType.SKILL && dockCreateSeed.skill != null)
				{
					dockPickedSkill = dockCreateSeed.skill;
					dockCreateStep = CreateStep.DETAILS;
				}
				else if (type == com.goalplanner.model.GoalType.BOSS && dockCreateSeed.bossName != null)
				{
					dockPickedBoss = dockCreateSeed.bossName;
					dockCreateStep = CreateStep.DETAILS;
				}
			}
		}
		refreshDock();
	}

	/** Advance/return between a tall form's PICKER and DETAILS sub-steps and
	 *  re-render the dock (the mount guard sees the step change and remounts). */
	private void navigateCreateStep(CreateStep step)
	{
		dockCreateOpen = true;
		dockCreateStep = step;
		refreshDock();
	}

	/** Set the create navigation to a non-form sub-view (grid / section new /
	 *  section pick) and re-render the dock. */
	private void navigateCreateNav(CreateNav nav)
	{
		dockCreateOpen = true;
		dockCreateNav = nav;
		refreshDock();
	}

	private JComponent buildCreateGrid()
	{
		JPanel inner = new JPanel(new BorderLayout(0, 6));
		inner.setOpaque(false);
		inner.setBorder(new EmptyBorder(6, 8, 8, 8));

		// No "Add a goal" title and no "CREATE" indicator bar here: the Create Goal
		// header button already names the mode, and a third label read as redundant
		// (user feedback). The type tiles speak for themselves.
		JPanel grid = new JPanel(new GridLayout(2, 4, 5, 5));
		grid.setOpaque(false);
		for (com.goalplanner.model.GoalType t : CREATE_TILES)
		{
			grid.add(buildTypeTile(t));
		}
		inner.add(grid, BorderLayout.CENTER);
		// "New Section" is no longer nested here: section creation is now its own
		// Create Section button in the dock header, beside Create Goal, which
		// mounts the in-dock new-section form (buildSectionNewForm). Adding a goal
		// is about goals; the grid stays clean.

		// Import + Saved goals below the tiles (previously only on the header Options
		// popup). Gated exactly like that popup: Import needs share support; Saved
		// goals needs the Saved Plans library too. Each opens its inline surface.
		if (isShareAvailable())
		{
			boolean saved = isSavedPlansAvailable();
			JPanel extra = new JPanel(new GridLayout(1, saved ? 2 : 1, 5, 0));
			extra.setOpaque(false);
			extra.setBorder(new EmptyBorder(6, 0, 0, 0));
			JButton importBtn = flatButton("Import", false);
			importBtn.setToolTipText("Paste and import a share code");
			importBtn.addActionListener(e -> openImportSurface());
			extra.add(importBtn);
			if (saved)
			{
				JButton savedBtn = flatButton("Saved goals", false);
				savedBtn.setToolTipText("Load or delete your saved goals");
				savedBtn.addActionListener(e -> openSavedPlansSurface());
				extra.add(savedBtn);
			}
			inner.add(extra, BorderLayout.SOUTH);
		}
		return plainSurface(inner);
	}

	/** The in-dock new-section form (note 2): a name field (autofocus) + a primary
	 *  "Create section" button, wrapped in the same CREATE shell as the goal
	 *  forms. Enter or the button commits {@link com.goalplanner.api.GoalPlannerApiImpl#createSection}
	 *  (blank ignored), then returns to the type grid. */
	private JComponent buildSectionNewForm()
	{
		JPanel body = formBody();

		JTextField nameField = new JTextField(16);
		styleField(nameField);
		addFormRow(body, "New section name", nameField);
		autofocus(nameField);

		Runnable commit = () ->
		{
			String name = nameField.getText().trim();
			if (name.isEmpty())
			{
				return;
			}
			api.createSection(name);
			navigateCreate(null);
		};
		nameField.addActionListener(e -> commit.run());

		JPanel inner = new JPanel(new BorderLayout(0, 6));
		inner.setOpaque(false);
		inner.setBorder(new EmptyBorder(6, 8, 8, 8));

		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setOpaque(false);
		JButton back = flatButton("Back", false);
		back.addActionListener(e -> navigateCreate(null));
		JLabel title = new JLabel("New section");
		title.setForeground(CREATE_FG);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
		header.add(back, BorderLayout.WEST);
		header.add(title, BorderLayout.CENTER);
		inner.add(header, BorderLayout.NORTH);

		inner.add(body, BorderLayout.CENTER);

		// A prominent FULL-WIDTH primary button that commits on a single click
		// (Task 3): a right-aligned pill read as secondary and invited a stray
		// double-click. BorderLayout.CENTER stretches it edge to edge; Enter in the
		// name field still commits via the field's action listener above.
		JButton create = flatButton("Create section", true);
		create.addActionListener(e -> commit.run());
		JPanel footer = new JPanel(new BorderLayout());
		footer.setOpaque(false);
		footer.add(create, BorderLayout.CENTER);
		inner.add(footer, BorderLayout.SOUTH);

		return plainSurface(inner);
	}

	/** Request focus for a field once its window is realized (a field mounted into
	 *  the dock is not focusable until it is shown). */
	private static void autofocus(JComponent field)
	{
		javax.swing.SwingUtilities.invokeLater(field::requestFocusInWindow);
	}

	/** Stash a validated goal-create and navigate to the landing-section chooser
	 *  (note 3). The consumer runs once the user picks a section.
	 *
	 *  <p>Task 1: when a fixed landing section is armed (a section's "Add goal"
	 *  opened this flow), the chooser is SKIPPED - the create runs straight against
	 *  that section (the same create logic {@link #chooseSection} runs), then the
	 *  flow returns to the type grid. The target is one-shot: it is consumed here. */
	private void goToSectionPick(java.util.function.Consumer<String> pending)
	{
		if (dockCreateTargetSection != null)
		{
			String target = dockCreateTargetSection;
			dockCreateTargetSection = null;
			pending.accept(target);
			// A complete-on-add goal reconciles into Completed; arm a reveal so it
			// does not read as "didn't show up" (mirrors chooseSection).
			armCreateReveal();
			// Task 1: collapse to the resting footer rather than re-open the new
			// goal's edit view. Sync creates settle here; async creates also post
			// finishDockCreate from their client-thread runnable to beat the debounce.
			finishDockCreate();
			return;
		}
		dockPendingCreate = pending;
		navigateCreateNav(CreateNav.SECTION_PICK);
	}

	/**
	 * Run a section-pick create whose add path resolves requirements against live
	 * Client state (DIARY/QUEST/BOSS - {@code DiaryRequirementResolver.resolve},
	 * {@code resolveQuestRequirements}, {@code q.getState}). Those reads MUST run on
	 * the client thread: the section-pick consumer fires on the EDT, and a client
	 * read off the client thread trips RuneLite's {@code -ea} client-thread assert,
	 * silently failing the create (the diary-add bug). Wrapping create + move on the
	 * client thread in one compound fixes it AND gives a clean single undo, mirroring
	 * the standalone-repeatable client-thread wrap (buildSkillDetails/buildBossDetails).
	 * Safe types (item/account/custom/CA/skill one-time) read no Client state and skip
	 * this - they create directly on the EDT.
	 */
	private void clientThreadCreateInSection(String compoundDesc, String sectionId,
		java.util.function.Supplier<String> create)
	{
		runOnClientThread(() ->
		{
			api.beginCompound(compoundDesc);
			try
			{
				String id = create.get();
				if (id != null)
				{
					api.moveGoalToSection(id, sectionId);
				}
			}
			finally
			{
				api.endCompound();
			}
			// Task 1: collapse the dock AFTER the compound settles. endCompound's
			// onGoalsChanged rebuild (debounced) re-selects the new goal via
			// selectAfterCreate and would re-open the edit view; this invokeLater is
			// queued strictly after that post and before the debounce fires, so by
			// the time the rebuild runs the selection is cleared and it stays closed.
			javax.swing.SwingUtilities.invokeLater(this::finishDockCreate);
		});
	}

	/** Run the pending goal-create against the chosen section, then return to the
	 *  type grid (note 3). */
	private void chooseSection(String sectionId)
	{
		java.util.function.Consumer<String> pending = dockPendingCreate;
		dockPendingCreate = null;
		if (pending != null)
		{
			pending.accept(sectionId);
			// The create may have been complete-on-add (e.g. a diary tier already
			// 100% on this account): it silently reconciles into Completed. Arm a
			// reveal so it does not read as "didn't show up".
			armCreateReveal();
		}
		// Task 1: collapse to the resting footer rather than re-open the new goal's
		// edit view. Sync creates (skill one-time / item / account / custom / CA)
		// settle synchronously here; async creates (diary / quest / boss) ALSO post
		// finishDockCreate from clientThreadCreateInSection after their compound so
		// the later debounced rebuild finds no selection and stays collapsed.
		finishDockCreate();
	}

	/** The landing-section chooser (note 3): the pending goal-create's target. The
	 *  default Incomplete section is offered first (preselected/highlighted), then
	 *  every user section, then a "+ New section" option that reveals an inline
	 *  name field (note 2's field UI) creating-and-selecting in one go. A "< Back"
	 *  abandons the pending create and returns to the type grid (form field values
	 *  are NOT preserved across Back - forward flow is the norm; known limitation). */
	private JComponent buildSectionPickForm()
	{
		return sectionPickSurface("Choose section", "Choose a section for this goal",
			"Back to the goal types (this goal is not created yet)",
			"Incomplete (default)", this::chooseSection,
			java.util.Collections.emptySet(), this::chooseSection,
			null, () -> navigateCreate(null));
	}

	/**
	 * The shared "pick a section" surface: a prompt line, a highlighted default row
	 * (the built-in Incomplete section), a tappable row per user section, and a
	 * "+ New section" row that reveals an inline name field creating-and-picking in
	 * one go. Headed by a title and a "< Back".
	 *
	 * <p>Backs both the create flow's landing-section step
	 * ({@link #buildSectionPickForm}) and the inline move/copy overlay
	 * ({@link #buildMoveSurface}), so the two look and behave identically.
	 *
	 * @param title        header title
	 * @param prompt       dim one-liner above the rows
	 * @param backTip      tooltip on "< Back"
	 * @param defaultLabel label for the built-in Incomplete row
	 * @param onDefault    handed the built-in Incomplete section's id; null omits
	 *                     the default row entirely (e.g. everything is already there)
	 * @param exclude      section ids to leave out of the list (e.g. the goal's own)
	 * @param onPick       handed a user section's id, or a freshly created section's
	 * @param newSectionCompound undo description wrapping "create section + onPick"
	 *                     so the pair is one undo; null leaves them separate
	 * @param onBack       run when "< Back" is pressed
	 */
	private JComponent sectionPickSurface(String title, String prompt, String backTip,
		String defaultLabel, java.util.function.Consumer<String> onDefault,
		java.util.Set<String> exclude, java.util.function.Consumer<String> onPick,
		String newSectionCompound, Runnable onBack)
	{
		JPanel body = formBody();

		JLabel promptLabel = new JLabel(prompt);
		promptLabel.setForeground(CREATE_FG_DIM);
		promptLabel.setFont(promptLabel.getFont().deriveFont(10f));
		promptLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(promptLabel);
		body.add(Box.createVerticalStrut(4));

		String incompleteId = null;
		java.util.List<com.goalplanner.api.SectionView> userSections = new ArrayList<>();
		for (com.goalplanner.api.SectionView sv : api.queryAllSections())
		{
			if ("INCOMPLETE".equals(sv.kind))
			{
				incompleteId = sv.id;
			}
			else if (!sv.builtIn && !exclude.contains(sv.id))
			{
				userSections.add(sv);
			}
		}

		// Default: the built-in Incomplete section, offered first + highlighted.
		if (incompleteId != null && onDefault != null)
		{
			final String defId = incompleteId;
			body.add(sectionPickRow(defaultLabel, true, () -> onDefault.accept(defId)));
			body.add(Box.createVerticalStrut(3));
		}
		for (com.goalplanner.api.SectionView sv : userSections)
		{
			final String destId = sv.id;
			body.add(sectionPickRow(sv.name, false, () -> onPick.accept(destId)));
			body.add(Box.createVerticalStrut(3));
		}

		// "+ New section": reveals an inline name field creating-and-selecting.
		final JTextField newName = new JTextField(16);
		styleField(newName);
		final JPanel newRow = new JPanel(new BorderLayout(4, 0));
		newRow.setOpaque(false);
		newRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		newRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, newName.getPreferredSize().height));
		newRow.setVisible(false);
		Runnable createUse = () ->
		{
			String name = newName.getText().trim();
			if (name.isEmpty())
			{
				return;
			}
			// The move/copy overlay folds "create the section" and "move into it"
			// into ONE undo entry; the create flow passes null and keeps its own
			// (the goal-create path compounds itself downstream).
			if (newSectionCompound != null)
			{
				api.beginCompound(newSectionCompound);
			}
			try
			{
				String newId = api.createSection(name);
				if (newId != null)
				{
					onPick.accept(newId);
				}
			}
			finally
			{
				if (newSectionCompound != null)
				{
					api.endCompound();
				}
			}
		};
		newName.addActionListener(e -> createUse.run());
		JButton createUseBtn = flatButton("Create & use", true);
		createUseBtn.addActionListener(e -> createUse.run());
		newRow.add(newName, BorderLayout.CENTER);
		newRow.add(createUseBtn, BorderLayout.EAST);

		JButton newSection = flatButton("+ New section", false);
		newSection.setAlignmentX(Component.LEFT_ALIGNMENT);
		newSection.addActionListener(e ->
		{
			newRow.setVisible(!newRow.isVisible());
			if (newRow.isVisible())
			{
				autofocus(newName);
			}
			remeasureDock();
		});
		body.add(Box.createVerticalStrut(2));
		body.add(newSection);
		body.add(Box.createVerticalStrut(3));
		body.add(newRow);

		JPanel inner = new JPanel(new BorderLayout(0, 6));
		inner.setOpaque(false);
		inner.setBorder(new EmptyBorder(6, 8, 8, 8));

		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setOpaque(false);
		JButton back = flatButton("< Back", false);
		back.setToolTipText(backTip);
		back.addActionListener(e -> onBack.run());
		JLabel titleLabel = new JLabel(title);
		titleLabel.setForeground(CREATE_FG);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 12f));
		header.add(back, BorderLayout.WEST);
		header.add(titleLabel, BorderLayout.CENTER);
		inner.add(header, BorderLayout.NORTH);
		inner.add(body, BorderLayout.CENTER);

		return plainSurface(inner);
	}

	/** A tappable section row for the landing-section chooser; the default row is
	 *  highlighted (green border) to mark the preselected choice. */
	private JComponent sectionPickRow(String label, boolean isDefault, Runnable onPick)
	{
		JPanel row = new RoundedPaint.RoundedPanel(new BorderLayout(), RoundedPaint.RADIUS);
		row.setBackground(isDefault ? CREATE_SEL_BG : CREATE_TILE_BG);
		row.setBorder(RoundedPaint.border(isDefault ? CREATE_SEL_BORDER : CREATE_TILE_BG, 1,
			RoundedPaint.RADIUS, new java.awt.Insets(4, 6, 4, 6)));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JLabel nm = new JLabel(label);
		nm.setForeground(CREATE_FG);
		nm.setFont(nm.getFont().deriveFont(11f));
		row.add(nm, BorderLayout.CENTER);

		row.addMouseListener(new MouseAdapter()
		{
			@Override public void mouseClicked(MouseEvent e) { onPick.run(); }
		});
		return row;
	}

	/** Wrap a create/edit surface body in a shell headed by a FULL-WIDTH context
	 *  indicator bar (ADR-0008): a green "CREATE" bar for create, a neutral
	 *  "SELECTED" bar for edit. The bar bleeds edge-to-edge (no side inset); the
	 *  body carries its own padding. */
	private JComponent surfaceShell(String indicator, boolean createTone, JComponent inner)
	{
		ScrollablePanel root = new ScrollablePanel(new BorderLayout()).asCard(SURFACE_CARD_BG);
		root.setOpaque(false);
		root.add(indicatorBar(indicator, createTone), BorderLayout.NORTH);
		root.add(inner, BorderLayout.CENTER);
		return root;
	}

	/** Like {@link #surfaceShell} but WITHOUT the context indicator bar. The create
	 *  surfaces use this: the Create Goal / Create Section header buttons already
	 *  signal the mode, so a "CREATE" bar was redundant (user feedback). Keeps the
	 *  width-tracking ScrollablePanel so rows still lay out full-width. */
	private JComponent plainSurface(JComponent inner)
	{
		ScrollablePanel root = new ScrollablePanel(new BorderLayout()).asCard(SURFACE_CARD_BG);
		root.setOpaque(false);
		root.add(inner, BorderLayout.CENTER);
		return root;
	}

	/** The full-width small-caps context bar for {@link #surfaceShell}. Its top
	 *  corners round to match the surface card cap (glam); the bottom stays square
	 *  so the bar meets the body flush. */
	private JComponent indicatorBar(String text, boolean createTone)
	{
		final Color barBg = createTone ? IND_CREATE_BG : IND_EDIT_BG;
		JLabel bar = new JLabel(text.toUpperCase(java.util.Locale.ROOT))
		{
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				try
				{
					RoundedPaint.fillTop(g2, getWidth(), getHeight(),
						RoundedPaint.SURFACE_RADIUS, barBg);
				}
				finally
				{
					g2.dispose();
				}
				super.paintComponent(g);
			}
		};
		bar.setOpaque(false);
		bar.setForeground(createTone ? IND_CREATE_FG : IND_EDIT_FG);
		bar.setFont(bar.getFont().deriveFont(Font.BOLD, 10f));
		bar.setBorder(new EmptyBorder(4, 10, 4, 10));
		// Span the dock's width whatever the form's preferred width.
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, bar.getPreferredSize().height));
		return bar;
	}

	private JButton buildTypeTile(com.goalplanner.model.GoalType type)
	{
		// Task 2: a create tile carries the SAME color as the goal cards it creates.
		// The accent is the type's own color; near-black types (e.g. Boss) fall back
		// to a visible swatch so they don't vanish on the dark surface.
		Color accent = type.getColor();
		if (accent.getRed() + accent.getGreen() + accent.getBlue() < 120)
		{
			accent = new Color(0x55, 0x55, 0x58);
		}
		// Tint the tile background toward the type color - muted (blended into the
		// dark tile surface at ~1/3) so the ASCII label stays legible in light grey
		// at both font 1.0 and 1.3, while the tile still reads as "the <type> color".
		final Color base = tintTile(CREATE_TILE_BG, accent);
		final Color hovered = tintTile(CREATE_TILE_HOVER, accent);
		// A FULL rounded border in the type's own color frames the whole tile (user
		// feedback: full borders over the top-only tab accent), over the tinted body,
		// so each tile clearly reads as "the <type> color".
		RoundedPaint.RoundedButton b = new RoundedPaint.RoundedButton(tileLabel(type));
		b.setForeground(CREATE_FG);
		b.setBackground(base);
		b.setFont(b.getFont().deriveFont(11f));
		b.setBorder(RoundedPaint.border(accent, 2, RoundedPaint.RADIUS, new Insets(6, 4, 6, 4)));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		hover(b, base, hovered);
		b.addActionListener(e -> navigateCreate(type));
		return b;
	}

	/** Blend a create-tile base color ~1/3 of the way toward the type accent: a
	 *  muted tint that keeps the light-grey label readable while making the tile
	 *  recognizably the type's color. */
	private static Color tintTile(Color base, Color accent)
	{
		final double t = 0.32;
		return new Color(
			(int) Math.round(base.getRed()   + (accent.getRed()   - base.getRed())   * t),
			(int) Math.round(base.getGreen() + (accent.getGreen() - base.getGreen()) * t),
			(int) Math.round(base.getBlue()  + (accent.getBlue()  - base.getBlue())  * t));
	}

	/** Shared background-swap-on-hover, deduplicated across the create tiles and
	 *  buttons to keep the token footprint down. */
	private static void hover(JButton b, Color base, Color hovered)
	{
		b.addMouseListener(new MouseAdapter()
		{
			@Override public void mouseEntered(MouseEvent e) { b.setBackground(hovered); }
			@Override public void mouseExited(MouseEvent e) { b.setBackground(base); }
		});
	}

	private JComponent buildCreateForm(com.goalplanner.model.GoalType type)
	{
		// Tall types split into a PICKER step then a DETAILS step; the rest render
		// their DETAILS directly (no picker).
		switch (type)
		{
			case SKILL: return dockCreateStep == CreateStep.PICKER
				? buildSkillPicker() : buildSkillDetails();
			case ACCOUNT: return buildAccountForm();
			case CUSTOM: return buildCustomForm();
			case BOSS: return dockCreateStep == CreateStep.PICKER
				? buildBossPicker() : buildBossDetails();
			case QUEST: return buildQuestForm();
			case DIARY: return buildDiaryForm();
			case ITEM_GRIND: return dockCreateStep == CreateStep.PICKER
				? buildItemPicker() : buildItemDetails();
			case COMBAT_ACHIEVEMENT: return buildCombatForm();
			// Any type not yet wired lands on a placeholder pointing at the
			// existing right-click add dialogs (which stay until parity).
			default: return buildPendingForm(type);
		}
	}

	private JComponent buildPendingForm(com.goalplanner.model.GoalType type)
	{
		JPanel body = formBody();
		JLabel note = new JLabel("<html>The " + tileLabel(type) + " form is coming to "
			+ "the dock.<br>For now, use right-click Add on a section header.</html>");
		note.setForeground(CREATE_FG_DIM);
		note.setFont(note.getFont().deriveFont(11f));
		note.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(note);
		return createFormScaffold(type, body, null);
	}

	// ----- per-type create forms -----

	/** SKILL step A (PICKER): only the skill icon grid, filling the dock. Tapping a
	 *  skill stashes it and auto-advances to the DETAILS step. Back returns to the
	 *  type grid. */
	private JComponent buildSkillPicker()
	{
		JPanel body = formBody();
		final net.runelite.api.Skill[] holder = { dockPickedSkill };
		JComponent grid = buildSkillPickerGrid(holder, () ->
		{
			dockPickedSkill = holder[0];
			navigateCreateStep(CreateStep.DETAILS);
		});
		addFormRow(body, "Pick a skill", grid);
		return createFormScaffold(com.goalplanner.model.GoalType.SKILL, body, null);
	}

	/** SKILL step B (DETAILS): the target + One-time/Repeatable segmented toggle for
	 *  the picked skill. Back returns to the picker. A "Make repeatable" seed
	 *  (note 5) prefills the target and opens on the Repeatable segment. */
	private JComponent buildSkillDetails()
	{
		final CreateSeed seed = dockCreateSeed;
		dockCreateSeed = null;
		final boolean seedRepeat = seed != null && seed.repeatable;
		final net.runelite.api.Skill skill = dockPickedSkill;
		// Update mode: the same form, pre-filled from the goal. A derived per-period
		// slice has no editable absolute target (it re-bases off its chunk), so it
		// shows the repeat block only.
		final Goal editing = editingGoal();
		final boolean derived = editing != null && editing.getRepeatChunk() > 0;

		JPanel body = formBody();
		addFormRow(body, "Skill", pickedHeader(
			skill != null ? new ImageIcon(skillIconManager.getSkillImage(skill, true)) : null,
			skill != null ? skill.getName() : "(none)"));

		// One-time vs Repeatable is an either/or segmented toggle: exactly one input
		// set shows, and the toggle is the single source of truth for which create
		// path runs (Task 2). One-time = a target field; Repeatable = period pills +
		// an "XP each period" amount, no target.
		SkillTargetForm target = new SkillTargetForm(99);
		if (seed != null && seed.targetXp != null)
		{
			target.setTargetXp(seed.targetXp);
		}
		else if (editing != null && !derived)
		{
			target.setTargetXp(editing.getTargetValue());
		}
		JPanel oneTimePane = new JPanel();
		oneTimePane.setLayout(new BoxLayout(oneTimePane, BoxLayout.Y_AXIS));
		oneTimePane.setOpaque(false);
		oneTimePane.setAlignmentX(Component.LEFT_ALIGNMENT);
		addFormRow(oneTimePane, "Target level or XP", target);

		// In update mode this pane doubles as the repeat EDITOR (pre-filled with the
		// goal's own period + chunk), so this form needs no addUpdateRepeatBlock.
		final com.goalplanner.model.RepeatPeriod[] period =
			{ initialPeriod(editing) };
		final JTextField amount = new JTextField(8);
		styleField(amount);
		if (derived)
		{
			amount.setText(Integer.toString(editing.getRepeatChunk()));
		}
		JPanel repeatPane = new JPanel();
		repeatPane.setLayout(new BoxLayout(repeatPane, BoxLayout.Y_AXIS));
		repeatPane.setOpaque(false);
		repeatPane.setAlignmentX(Component.LEFT_ALIGNMENT);
		JComponent pills = buildPeriodPills(period);
		pills.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel periodLbl = new JLabel("Repeat every");
		periodLbl.setForeground(CREATE_FG_DIM);
		periodLbl.setFont(periodLbl.getFont().deriveFont(10f));
		periodLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
		repeatPane.add(periodLbl);
		repeatPane.add(Box.createVerticalStrut(2));
		repeatPane.add(pills);
		repeatPane.add(Box.createVerticalStrut(6));
		addFormRow(repeatPane, "XP each period", amount);
		JLabel lock = new JLabel("Lands in the Repeatable section.");
		lock.setForeground(CREATE_FG_DIM);
		lock.setFont(lock.getFont().deriveFont(10f));
		lock.setAlignmentX(Component.LEFT_ALIGNMENT);
		repeatPane.add(lock);

		// Update mode opens on the goal's CURRENT mode: a per-period chunk is
		// Repeatable, anything else is One-time.
		final boolean[] repeatMode = { seedRepeat || derived };
		// The toggle shows in BOTH modes: update mode CONVERTS the goal between
		// One-time and Repeatable on save (convertGoalToOneTime /
		// convertGoalToRepeatable), so a goal is no longer stuck in the mode it was
		// created in. (FLAG: no "Relative" segment here - the skill CREATE form has
		// none either, and an edit-only third segment would break the create/edit
		// symmetry this form is built on. A relative target is entered as an
		// absolute one; the boss form keeps Relative because CREATE offers it.)
		JComponent segmented = buildModeToggle(repeatMode, () ->
		{
			oneTimePane.setVisible(!repeatMode[0]);
			repeatPane.setVisible(repeatMode[0]);
			remeasureDock();
		});
		body.add(segmented);
		body.add(Box.createVerticalStrut(8));
		oneTimePane.setVisible(!repeatMode[0]);
		repeatPane.setVisible(repeatMode[0]);
		body.add(oneTimePane);
		body.add(repeatPane);

		Runnable onAdd = () ->
		{
			if (skill == null)
			{
				warnCreate("Pick a skill first.");
				return;
			}
			if (editing != null)
			{
				final String editId = editing.getId();
				if (repeatMode[0])
				{
					// -> Repeatable: period + per-period XP in one call. setGoalRepeat
					// alone would be REFUSED on a chunk-less skill goal, and
					// setGoalRepeatChunk refuses a goal that has no chunk yet.
					final int chunk = parsePositiveInt(amount.getText());
					if (chunk <= 0)
					{
						warnCreate("Enter how much XP to gain each period.");
						return;
					}
					final com.goalplanner.model.RepeatPeriod p = period[0];
					if (derived && editing.getRepeatEvery() == p
						&& editing.getRepeatChunk() == chunk)
					{
						closeEditGoalForm();
						return;
					}
					// Re-bases against live XP -> client thread.
					saveGoalEditOnClientThread(() -> api.convertGoalToRepeatable(editId, p, chunk),
						"Could not read your XP - try again once you are logged in.");
					return;
				}
				// -> One-time: an absolute XP target. A repeatable goal is converted
				// back (period AND chunk cleared) rather than just retargeted.
				final int newXp = target.getTargetXp();
				if (newXp <= 0)
				{
					warnCreate("Enter a valid target level (2-99) or XP (1-200,000,000).");
					return;
				}
				saveGoalEdit(() ->
				{
					applyEditedTarget(editId, newXp, derived);
					return true;
				});
				return;
			}
			if (repeatMode[0])
			{
				final int chunk = parsePositiveInt(amount.getText());
				if (chunk <= 0)
				{
					warnCreate("Enter how much XP to gain each period.");
					return;
				}
				final com.goalplanner.model.RepeatPeriod p = period[0];
				// Repeatable-only: no target field, and no choose-section step. A fresh
				// repeatable is STANDALONE - it lives entirely in the built-in Repeatable
				// section with no endless parent stranded in a normal section. The
				// standalone creator reads live XP to seed the first period, so it runs
				// on the client thread; one compound for a clean single undo.
				runOnClientThread(() ->
				{
					api.beginCompound("Add repeatable skill goal");
					try
					{
						api.createStandaloneRepeatSkillGoal(skill, p, chunk);
					}
					finally
					{
						api.endCompound();
					}
					// Task 1: collapse AFTER the compound settles - the post-endCompound
					// debounced rebuild re-selects the new goal, so the winning collapse
					// must be queued here (after that post, before the debounce fires).
					javax.swing.SwingUtilities.invokeLater(this::finishDockCreate);
				});
				finishDockCreate();
			}
			else
			{
				final int xp = target.getTargetXp();
				if (xp <= 0)
				{
					warnCreate("Enter a valid target level (2-99) or XP (1-200,000,000).");
					return;
				}
				goToSectionPick(sectionId ->
				{
					String id = api.addSkillGoal(skill, xp);
					api.moveGoalToSection(id, sectionId);
				});
			}
		};
		return createFormScaffold(com.goalplanner.model.GoalType.SKILL, body, onAdd,
			() -> navigateCreateStep(CreateStep.PICKER));
	}

	/** A grid of skill icon buttons (all trainable skills, ~3 rows of 8). Tapping
	 *  one writes it to {@code out[0]} and highlights it. Icons come from
	 *  {@link SkillIconManager}, the same source the goal cards use. */
	private JComponent buildSkillPickerGrid(net.runelite.api.Skill[] out, Runnable onPick)
	{
		JPanel grid = new JPanel(new GridLayout(0, 8, 3, 3));
		grid.setOpaque(false);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);

		java.util.List<JButton> buttons = new ArrayList<>();
		java.util.Map<JButton, net.runelite.api.Skill> owner = new HashMap<>();
		Runnable refresh = () ->
		{
			for (JButton b : buttons)
			{
				boolean sel = owner.get(b) == out[0];
				b.setBackground(sel ? CREATE_SEL_BG : CREATE_TILE_BG);
				b.setBorder(BorderFactory.createLineBorder(
					sel ? CREATE_SEL_BORDER : CREATE_TILE_BG, 2));
			}
		};

		for (net.runelite.api.Skill skill : GOAL_SKILLS)
		{
			JButton b = new JButton(new ImageIcon(skillIconManager.getSkillImage(skill, true)));
			b.setToolTipText(skill.getName());
			b.setOpaque(true);
			b.setBackground(CREATE_TILE_BG);
			b.setFocusPainted(false);
			b.setContentAreaFilled(true);
			b.setBorderPainted(true);
			b.setMargin(new Insets(1, 1, 1, 1));
			b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			buttons.add(b);
			owner.put(b, skill);
			b.addActionListener(e -> {
				out[0] = skill;
				refresh.run();
				if (onPick != null) onPick.run();
			});
			grid.add(b);
		}
		refresh.run();
		return grid;
	}

	private JComponent buildAccountForm()
	{
		final Goal editing = editingGoal();
		JPanel body = formBody();

		// Leagues-scoped metrics (League Points/Tasks) only make sense on a
		// leagues profile - filter them out otherwise, matching the dialog.
		boolean leaguesProfile = com.goalplanner.persistence.GoalStore.PROFILE_LEAGUES
			.equals(goalStore.getActiveProfile());
		com.goalplanner.model.AccountMetric[] metrics =
			java.util.Arrays.stream(com.goalplanner.model.AccountMetric.values())
				.filter(m -> leaguesProfile || !m.isLeagues())
				.toArray(com.goalplanner.model.AccountMetric[]::new);
		JComboBox<com.goalplanner.model.AccountMetric> metricCombo = new JComboBox<>(metrics);
		metricCombo.setRenderer(textRenderer(
			v -> ((com.goalplanner.model.AccountMetric) v).getDisplayName()));
		styleField(metricCombo);
		// Update mode: the metric is fixed (no API re-points an existing account
		// goal), so it shows selected but locked; only the target is editable.
		if (editing != null)
		{
			try
			{
				metricCombo.setSelectedItem(com.goalplanner.model.AccountMetric
					.valueOf(editing.getAccountMetric()));
			}
			catch (RuntimeException ignored) { /* unknown/legacy metric: leave default */ }
			metricCombo.setEnabled(false);
			metricCombo.setToolTipText("An account goal's metric can't be changed.");
		}
		addFormRow(body, "Metric", metricCombo);

		JTextField targetField = new JTextField(10);
		styleField(targetField);
		if (editing != null)
		{
			targetField.setText(Integer.toString(editing.getTargetValue()));
		}
		addFormRow(body, "Target", targetField);

		// Quick-fill presets (Task 3): a Max button + a few nice-rounded fractions
		// (~25/50/75% of the ceiling), data-driven off AccountMetric.maxTarget (static -
		// no Client read). Tapping one fills the target field; typing a custom value
		// still works. Rebuilt whenever the metric changes.
		JLabel quickLbl = new JLabel("Quick fill");
		quickLbl.setForeground(CREATE_FG_DIM);
		quickLbl.setFont(quickLbl.getFont().deriveFont(10f));
		quickLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(quickLbl);
		body.add(Box.createVerticalStrut(2));
		JPanel presetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		presetRow.setOpaque(false);
		presetRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(presetRow);
		body.add(Box.createVerticalStrut(6));
		Runnable rebuildPresets = () ->
		{
			presetRow.removeAll();
			com.goalplanner.model.AccountMetric m =
				(com.goalplanner.model.AccountMetric) metricCombo.getSelectedItem();
			if (m != null)
			{
				int max = m.getMaxTarget();
				for (int v : com.goalplanner.ui.AccountTargetPresets.presetsFor(m))
				{
					final int val = v;
					String label = v == max
						? "Max (" + com.goalplanner.util.FormatUtil.formatNumber(v) + ")"
						: com.goalplanner.util.FormatUtil.formatNumber(v);
					JButton pill = flatButton(label, false);
					pill.setToolTipText("Set target to " + val);
					pill.addActionListener(e -> targetField.setText(Integer.toString(val)));
					presetRow.add(pill);
				}
			}
			presetRow.revalidate();
			remeasureDock();
		};
		metricCombo.addActionListener(e -> rebuildPresets.run());
		rebuildPresets.run();

		Runnable onAdd = () ->
		{
			final int target = parsePositiveInt(targetField.getText());
			if (target <= 0)
			{
				warnCreate("Enter a numeric target above zero.");
				return;
			}
			if (editing != null)
			{
				saveGoalEdit(() ->
				{
					api.changeTarget(editing.getId(), target);
					return true;
				});
				return;
			}
			final com.goalplanner.model.AccountMetric metric =
				(com.goalplanner.model.AccountMetric) metricCombo.getSelectedItem();
			if (metric == null) return;
			goToSectionPick(sectionId ->
			{
				String id = api.addAccountGoal(metric.name(), target);
				api.moveGoalToSection(id, sectionId);
			});
		};
		return createFormScaffold(com.goalplanner.model.GoalType.ACCOUNT, body, onAdd);
	}

	private JComponent buildCustomForm()
	{
		final Goal editing = editingGoal();
		JPanel body = formBody();

		JTextField nameField = new JTextField(16);
		styleField(nameField);
		if (editing != null && editing.getName() != null)
		{
			nameField.setText(editing.getName());
		}
		addFormRow(body, "Name", nameField);

		JTextField descField = new JTextField(16);
		styleField(descField);
		if (editing != null && editing.getDescription() != null)
		{
			descField.setText(editing.getDescription());
		}
		addFormRow(body, "Description (optional)", descField);
		// A custom goal owns its repeat state (it has no live tracker to derive
		// from), so update mode offers the Repeatable toggle + schedule here.
		final java.util.function.BooleanSupplier repeatApply = editing == null
			? () -> true
			: addUpdateRepeatBlock(body, editing, "Amount each period");

		Runnable onAdd = () ->
		{
			final String name = nameField.getText().trim();
			if (name.isEmpty())
			{
				warnCreate("Enter a name for the goal.");
				return;
			}
			if (editing != null)
			{
				final String newDesc = descField.getText().trim();
				saveGoalEdit(() ->
				{
					if (!repeatApply.getAsBoolean())
					{
						return false;
					}
					api.editCustomGoal(editing.getId(), name, newDesc);
					return true;
				});
				return;
			}
			final String desc = descField.getText().trim();
			goToSectionPick(sectionId ->
			{
				String id = api.addCustomGoal(name, desc);
				api.moveGoalToSection(id, sectionId);
			});
		};
		return createFormScaffold(com.goalplanner.model.GoalType.CUSTOM, body, onAdd);
	}

	/** BOSS step A (PICKER): a search field + tappable boss result rows filling the
	 *  dock. Tapping a boss stashes it and auto-advances to DETAILS. Back returns to
	 *  the type grid. */
	private JComponent buildBossPicker()
	{
		JPanel body = formBody();

		// Results scroll INSIDE a height-capped pane (Task 6) so the list never
		// grows the surface past the dock's cap and strands itself off-screen; the
		// search field above stays fixed while the list scrolls.
		final ScrollablePanel results = resultsColumn();
		JScrollPane resultsScroll = boundedResultsScroll(results);

		JTextField searchField = new JTextField(14);
		styleField(searchField);
		final String[] bosses = com.goalplanner.data.BossKillData.getBossNames();

		Runnable doSearch = () ->
		{
			results.removeAll();
			String q = searchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
			java.util.List<String> show;
			if (q.isEmpty())
			{
				// Default view: a SHORT curated head of common bosses, not the whole
				// list. Typing narrows across the full corpus below.
				show = recommendedBosses(bosses, 8);
			}
			else
			{
				show = new java.util.ArrayList<>();
				for (String b : bosses)
				{
					if (b.toLowerCase(java.util.Locale.ROOT).contains(q))
					{
						show.add(b);
						if (show.size() >= 25)
						{
							break;
						}
					}
				}
			}
			for (final String b : show)
			{
				results.add(tappableRow(null, b, null, () ->
				{
					dockPickedBoss = b;
					navigateCreateStep(CreateStep.DETAILS);
				}));
			}
			results.revalidate();
			results.repaint();
			remeasureDock();
		};
		searchField.addActionListener(e -> doSearch.run());
		JButton searchBtn = flatButton("Search", false);
		searchBtn.addActionListener(e -> doSearch.run());

		JPanel searchRow = new JPanel(new BorderLayout(4, 0));
		searchRow.setOpaque(false);
		searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		searchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			searchField.getPreferredSize().height));
		searchRow.add(searchField, BorderLayout.CENTER);
		searchRow.add(searchBtn, BorderLayout.EAST);

		JLabel bossLabel = new JLabel("Pick a boss (common bosses shown; type to search)");
		bossLabel.setForeground(CREATE_FG_DIM);
		bossLabel.setFont(bossLabel.getFont().deriveFont(10f));
		bossLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(bossLabel);
		body.add(Box.createVerticalStrut(2));
		body.add(searchRow);
		body.add(Box.createVerticalStrut(4));
		body.add(resultsScroll);
		autofocus(searchField);
		doSearch.run();
		return createFormScaffold(com.goalplanner.model.GoalType.BOSS, body, null);
	}

	/** BOSS step B (DETAILS): a 3-mode segmented toggle [Total | Relative |
	 *  Repeatable] for the picked boss (Task 2), mirroring the skill One-time/
	 *  Repeatable toggle. Total = an absolute kill target; Relative = "N kills beyond
	 *  my current count" (target = live KC + N); Repeatable = a per-period chunk that
	 *  lands standalone in the Repeatable section (no choose-section step). Exactly one
	 *  input set shows at a time. Back returns to the picker. */
	private JComponent buildBossDetails()
	{
		final CreateSeed seed = dockCreateSeed;
		dockCreateSeed = null;
		final boolean seedRepeat = seed != null && seed.repeatable;
		final String boss = dockPickedBoss;
		// Update mode (Selected view -> Edit goal): pre-filled, and a derived slice
		// edits its chunk rather than an absolute kill target.
		final Goal editing = editingGoal();
		final boolean derived = editing != null && editing.getRepeatChunk() > 0;

		JPanel body = formBody();
		addFormRow(body, "Boss", pickedHeader(null, boss != null ? boss : "(none)"));

		// Total (absolute) pane: a single "Target kill count" field.
		final JTextField kcField = new JTextField(8);
		styleField(kcField);
		if (seed != null && seed.targetCount != null)
		{
			kcField.setText(Integer.toString(seed.targetCount));
		}
		else if (editing != null && !derived)
		{
			kcField.setText(Integer.toString(editing.getTargetValue()));
		}
		JPanel totalPane = formBody();
		addFormRow(totalPane, "Target kill count", kcField);

		// Relative pane: "N kills beyond my current count". Target is resolved on the
		// client thread at add time (live KC + N).
		final JTextField relField = new JTextField(8);
		styleField(relField);
		JPanel relativePane = formBody();
		addFormRow(relativePane, "Kills beyond current", relField);
		JLabel relNote = new JLabel("Target = your current kill count + this.");
		relNote.setForeground(CREATE_FG_DIM);
		relNote.setFont(relNote.getFont().deriveFont(10f));
		relNote.setAlignmentX(Component.LEFT_ALIGNMENT);
		relativePane.add(relNote);

		// Repeatable pane: period pills + a per-period kill chunk (no target). Lands
		// standalone in the Repeatable section. In update mode it doubles as the
		// repeat EDITOR (pre-filled with the goal's own period + chunk), which is why
		// this form needs no separate addUpdateRepeatBlock.
		final com.goalplanner.model.RepeatPeriod[] period =
			{ initialPeriod(editing) };
		final JTextField chunkField = new JTextField(8);
		styleField(chunkField);
		if (derived)
		{
			chunkField.setText(Integer.toString(editing.getRepeatChunk()));
		}
		JPanel repeatPane = formBody();
		JComponent pills = buildPeriodPills(period);
		pills.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel periodLbl = new JLabel("Repeat every");
		periodLbl.setForeground(CREATE_FG_DIM);
		periodLbl.setFont(periodLbl.getFont().deriveFont(10f));
		periodLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
		repeatPane.add(periodLbl);
		repeatPane.add(Box.createVerticalStrut(2));
		repeatPane.add(pills);
		repeatPane.add(Box.createVerticalStrut(6));
		addFormRow(repeatPane, "Kills each period", chunkField);
		JLabel lock = new JLabel("Lands in the Repeatable section.");
		lock.setForeground(CREATE_FG_DIM);
		lock.setFont(lock.getFont().deriveFont(10f));
		lock.setAlignmentX(Component.LEFT_ALIGNMENT);
		repeatPane.add(lock);

		// "Add prerequisites" create option (Task 4), CHECKED by default: on = seed the
		// boss prereq tree (the existing default); off = the bare boss goal only. Only
		// applies to Total/Relative (a Repeatable chunk seeds no prereqs), so it hides in
		// Repeatable mode.
		final JCheckBox addPrereqs = createFilterToggle("Add prerequisites", true);
		addPrereqs.setToolTipText("Also add unmet requirements (skills/quests/items) as linked goals");

		// 0 = Total (default), 1 = Relative, 2 = Repeatable. A repeat seed opens on
		// the Repeatable segment (note 5); update mode opens on the goal's CURRENT
		// mode - a per-period chunk is Repeatable, anything else is Total ("Relative"
		// is an input convenience that resolves to an absolute target on save, so a
		// plain goal always opens on Total).
		final int[] mode = { seedRepeat || derived ? 2 : 0 };
		final JPanel[] panes = { totalPane, relativePane, repeatPane };
		Runnable applyMode = () ->
		{
			for (int i = 0; i < panes.length; i++)
			{
				panes[i].setVisible(i == mode[0]);
			}
			// Prerequisite seeding is a CREATE-only choice (an existing goal already
			// made it), and it never applies to a per-period chunk.
			addPrereqs.setVisible(editing == null && mode[0] != 2);
			remeasureDock();
		};
		// The segment bar shows in BOTH modes: update mode CONVERTS the goal between
		// Total / Relative / Repeatable on save (convertGoalToOneTime /
		// convertGoalToRepeatable), so a goal is no longer stuck in the mode it was
		// created in.
		JComponent segmented = buildSegmentedToggle(
			new String[] { "Total", "Relative", "Repeatable" }, mode, applyMode);
		body.add(segmented);
		body.add(Box.createVerticalStrut(8));
		body.add(totalPane);
		body.add(relativePane);
		body.add(repeatPane);
		body.add(Box.createVerticalStrut(4));
		body.add(addPrereqs);
		for (int i = 0; i < panes.length; i++)
		{
			panes[i].setVisible(i == mode[0]);
		}
		addPrereqs.setVisible(editing == null && mode[0] != 2);

		Runnable onAdd = () ->
		{
			if (boss == null)
			{
				warnCreate("Pick a boss first.");
				return;
			}
			if (editing != null)
			{
				final String editId = editing.getId();
				if (mode[0] == 2)
				{
					// -> Repeatable: set the period + per-period chunk. One API call
					// does both writes plus the live re-base (convertGoalToRepeatable);
					// setGoalRepeat alone would be REFUSED on a chunk-less boss goal.
					final int chunk = parsePositiveInt(chunkField.getText());
					if (chunk <= 0)
					{
						warnCreate("Enter how many kills to add each period.");
						return;
					}
					final com.goalplanner.model.RepeatPeriod p = period[0];
					if (derived && editing.getRepeatEvery() == p
						&& editing.getRepeatChunk() == chunk)
					{
						closeEditGoalForm();
						return;
					}
					// Re-bases against the live kill count -> client thread.
					saveGoalEditOnClientThread(() -> api.convertGoalToRepeatable(editId, p, chunk),
						"Could not read your kill count - try again once you are logged in.");
					return;
				}
				if (mode[0] == 1)
				{
					// -> Relative: resolve live KC + N into an absolute target, then
					// apply it the same way Total does (converting out of Repeatable
					// first when the goal was one).
					final int delta = parsePositiveInt(relField.getText());
					if (delta <= 0)
					{
						warnCreate("Enter how many kills beyond your current count.");
						return;
					}
					saveGoalEditOnClientThread(() ->
					{
						int currentKc = 0;
						int varpId = com.goalplanner.data.BossKillData.getVarpId(boss);
						if (client != null && varpId >= 0)
						{
							try { currentKc = client.getVarpValue(varpId); }
							catch (RuntimeException ignored) { /* unknown -> 0 */ }
						}
						int target = com.goalplanner.ui.RelativeTargetResolver
							.resolveKillCount(currentKc, delta);
						if (target <= 0)
						{
							return false;
						}
						applyEditedTarget(editId, target, derived);
						return true;
					}, "Could not read your kill count - try again once you are logged in.");
					return;
				}
				// -> Total: an absolute kill target. A repeatable goal is converted
				// back (period AND chunk cleared) rather than just retargeted.
				final int newKc = parsePositiveInt(kcField.getText());
				if (newKc <= 0)
				{
					warnCreate("Enter a target kill count above zero.");
					return;
				}
				saveGoalEdit(() ->
				{
					applyEditedTarget(editId, newKc, derived);
					return true;
				});
				return;
			}
			if (mode[0] == 2)
			{
				// Repeatable: standalone per-period chunk. No target, no choose-section
				// step (mirrors the skill repeatable path). Reads the live kill-count
				// varp, so client thread; one compound for a clean single undo.
				final int chunk = parsePositiveInt(chunkField.getText());
				if (chunk <= 0)
				{
					warnCreate("Enter how many kills to add each period.");
					return;
				}
				final com.goalplanner.model.RepeatPeriod p = period[0];
				runOnClientThread(() ->
				{
					api.beginCompound("Add repeatable boss goal");
					try
					{
						api.createStandaloneRepeatActivityGoal(boss, p, chunk);
					}
					finally
					{
						api.endCompound();
					}
					// Task 1: collapse AFTER the compound settles (see the skill
					// repeatable path) so the debounced rebuild finds no selection.
					javax.swing.SwingUtilities.invokeLater(this::finishDockCreate);
				});
				finishDockCreate();
			}
			else if (mode[0] == 1)
			{
				// Relative: target = live kill count + N. The KC read and the create
				// share one client-thread compound so the read is never off-thread and
				// undo reverses the whole gesture. An unknown/0 KC falls back to N.
				final int delta = parsePositiveInt(relField.getText());
				if (delta <= 0)
				{
					warnCreate("Enter how many kills beyond your current count.");
					return;
				}
				goToSectionPick(sectionId ->
					runOnClientThread(() ->
					{
						api.beginCompound("Add boss goal");
						try
						{
							int currentKc = 0;
							int varpId = com.goalplanner.data.BossKillData.getVarpId(boss);
							if (client != null && varpId >= 0)
							{
								try { currentKc = client.getVarpValue(varpId); }
								catch (RuntimeException ignored) { /* unknown -> 0 */ }
							}
							int target = com.goalplanner.ui.RelativeTargetResolver
								.resolveKillCount(currentKc, delta);
							if (target <= 0) { return; }
							String id = addPrereqs.isSelected()
								? api.addBossGoal(boss, target)
								: api.addBossGoalNoPrereqs(boss, target);
							if (id != null) { api.moveGoalToSection(id, sectionId); }
						}
						finally
						{
							api.endCompound();
						}
						// Task 1: collapse AFTER the compound settles so the debounced
						// onGoalsChanged rebuild (which re-selects the new goal) does not
						// re-open the edit view. chooseSection also collapses synchronously
						// on the pick, but that runs before this create; this wins last.
						javax.swing.SwingUtilities.invokeLater(this::finishDockCreate);
					}));
			}
			else
			{
				// Total (absolute): addBossGoal seeds boss prereqs, which resolve quest
				// prereqs against the live Client (resolveQuestRequirements/q.getState),
				// so create + move run on the client thread - same EDT-client-read latent
				// bug as diary.
				final int kc = parsePositiveInt(kcField.getText());
				if (kc <= 0)
				{
					warnCreate("Enter a target kill count above zero.");
					return;
				}
				final boolean withPrereqs = addPrereqs.isSelected();
				goToSectionPick(sectionId ->
					clientThreadCreateInSection("Add boss goal", sectionId,
						() -> withPrereqs
							? api.addBossGoal(boss, kc)
							: api.addBossGoalNoPrereqs(boss, kc)));
			}
		};
		return createFormScaffold(com.goalplanner.model.GoalType.BOSS, body, onAdd,
			() -> navigateCreateStep(CreateStep.PICKER));
	}

	private JComponent buildQuestForm()
	{
		JPanel body = formBody();

		final net.runelite.api.Quest[] quests = net.runelite.api.Quest.values();

		// Two filter toggles sit above the chooser and re-filter the list live:
		//  - "Incomplete only" (on by default) hides quests already FINISHED on this
		//    account.
		//  - "F2P only" restricts to free-to-play quests; its default follows the
		//    world (checked on a f2p world, unchecked on members/unknown).
		final JCheckBox incompleteOnly = createFilterToggle("Incomplete only", true);
		final JCheckBox f2pOnly = createFilterToggle("F2P only", false);
		incompleteOnly.setToolTipText("Hide quests you have already completed on this account");
		f2pOnly.setToolTipText("Show only free-to-play quests");

		final JComboBox<net.runelite.api.Quest> questCombo = new JComboBox<>();
		questCombo.setRenderer(textRenderer(v -> ((net.runelite.api.Quest) v).getName()));
		styleField(questCombo);

		// Completed set is empty until the live client read (below) fills it. Until
		// then the Incomplete filter simply hides nothing.
		final java.util.EnumSet<net.runelite.api.Quest> completed =
			java.util.EnumSet.noneOf(net.runelite.api.Quest.class);

		final Runnable applyFilter = () ->
		{
			Object prev = questCombo.getSelectedItem();
			javax.swing.DefaultComboBoxModel<net.runelite.api.Quest> model =
				new javax.swing.DefaultComboBoxModel<>();
			for (net.runelite.api.Quest q : quests)
			{
				if (incompleteOnly.isSelected() && completed.contains(q)) continue;
				if (f2pOnly.isSelected() && !com.goalplanner.data.QuestRequirements.isF2P(q)) continue;
				model.addElement(q);
			}
			questCombo.setModel(model);
			if (prev != null && model.getIndexOf(prev) >= 0)
			{
				questCombo.setSelectedItem(prev);
			}
		};
		incompleteOnly.addActionListener(e -> applyFilter.run());
		f2pOnly.addActionListener(e -> applyFilter.run());
		applyFilter.run();

		// Snapshot live client state (world membership + finished quests) on the
		// client thread - quest.getState reads varps and must not run on the EDT -
		// then push the result back to the EDT to re-derive the F2P default and refilter.
		runOnClientThread(() ->
		{
			final Client c = this.client;
			java.util.Set<net.runelite.api.WorldType> wt = c != null ? c.getWorldType() : null;
			final boolean f2pWorld = wt != null && !wt.contains(net.runelite.api.WorldType.MEMBERS);
			final java.util.EnumSet<net.runelite.api.Quest> done =
				java.util.EnumSet.noneOf(net.runelite.api.Quest.class);
			if (c != null)
			{
				for (net.runelite.api.Quest q : quests)
				{
					try
					{
						if (q.getState(c) == net.runelite.api.QuestState.FINISHED) done.add(q);
					}
					catch (RuntimeException ignored)
					{
						// A quest missing its varps on this client version just stays "not done".
					}
				}
			}
			SwingUtilities.invokeLater(() ->
			{
				completed.clear();
				completed.addAll(done);
				f2pOnly.setSelected(f2pWorld);
				applyFilter.run();
			});
		});

		// Toggles above, then the chooser.
		JPanel toggles = new JPanel();
		toggles.setLayout(new BoxLayout(toggles, BoxLayout.Y_AXIS));
		toggles.setOpaque(false);
		toggles.setAlignmentX(Component.LEFT_ALIGNMENT);
		toggles.add(incompleteOnly);
		toggles.add(f2pOnly);
		toggles.setMaximumSize(new Dimension(Integer.MAX_VALUE, toggles.getPreferredSize().height));
		body.add(toggles);
		body.add(Box.createVerticalStrut(6));
		addFormRow(body, "Quest", questCombo);

		// "Add prerequisites" create option (Task 4), CHECKED by default: on = seed the
		// unmet skill/quest requirement tree (the existing default); off = the bare quest
		// goal only. Distinct from the list filters above.
		final JCheckBox addPrereqs = createFilterToggle("Add prerequisites", true);
		addPrereqs.setToolTipText("Also add unmet skill/quest requirements as linked goals");
		body.add(addPrereqs);
		body.add(Box.createVerticalStrut(2));

		Runnable onAdd = () ->
		{
			final net.runelite.api.Quest quest = (net.runelite.api.Quest) questCombo.getSelectedItem();
			if (quest == null) return;
			final boolean withPrereqs = addPrereqs.isSelected();
			// addQuestGoal resolves quest prereqs against the live Client (q.getState),
			// so create + move run on the client thread - same EDT-client-read latent bug
			// as diary (Task 1 audit). The bare path (no prereqs) is client-safe but shares
			// the wrapper for one uniform single-undo compound.
			goToSectionPick(sectionId ->
				clientThreadCreateInSection("Add quest goal", sectionId,
					() -> withPrereqs
						? api.addQuestGoal(quest)
						: api.addQuestGoalWithPrereqs(quest, java.util.List.of())));
		};
		return createFormScaffold(com.goalplanner.model.GoalType.QUEST, body, onAdd);
	}

	/** A compact create-surface filter checkbox: transparent, CREATE-toned, 11pt,
	 *  left-aligned for a BoxLayout column. */
	private JCheckBox createFilterToggle(String text, boolean selected)
	{
		JCheckBox cb = new JCheckBox(text, selected);
		cb.setOpaque(false);
		cb.setForeground(CREATE_FG);
		cb.setFont(cb.getFont().deriveFont(11f));
		cb.setAlignmentX(Component.LEFT_ALIGNMENT);
		cb.setFocusPainted(false);
		cb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return cb;
	}

	/** Diary area display names, in AREA_KEYS order. Kept as the in-game journal
	 *  names so they read right and normalize back to their tracking key
	 *  (AchievementDiaryData.normalizeAreaKey special-cases Kourend/Western). */
	private static final String[] DIARY_AREAS = {
		"Ardougne", "Desert", "Falador", "Fremennik", "Kandarin", "Karamja",
		"Kourend & Kebos", "Lumbridge & Draynor", "Morytania", "Varrock",
		"Western Provinces", "Wilderness"
	};

	private JComponent buildDiaryForm()
	{
		JPanel body = formBody();

		JComboBox<String> areaCombo = new JComboBox<>(DIARY_AREAS);
		styleField(areaCombo);
		addFormRow(body, "Area", areaCombo);

		JComboBox<com.goalplanner.api.GoalPlannerApi.DiaryTier> tierCombo =
			new JComboBox<>(com.goalplanner.api.GoalPlannerApi.DiaryTier.values());
		tierCombo.setRenderer(textRenderer(v -> capitalize(((Enum<?>) v).name())));
		styleField(tierCombo);
		addFormRow(body, "Tier", tierCombo);

		Runnable onAdd = () ->
		{
			final String area = (String) areaCombo.getSelectedItem();
			final com.goalplanner.api.GoalPlannerApi.DiaryTier tier =
				(com.goalplanner.api.GoalPlannerApi.DiaryTier) tierCombo.getSelectedItem();
			if (area == null || tier == null) return;
			// addDiaryGoal resolves diary requirements against the live Client
			// (skills/quests/metrics), so create + move must run on the client thread -
			// running it on the EDT is the reported "diary add fails on all areas/tiers"
			// bug (dev-mode -ea client-thread assert). See clientThreadCreateInSection.
			goToSectionPick(sectionId ->
				clientThreadCreateInSection("Add diary goal", sectionId,
					() -> api.addDiaryGoal(area, tier)));
		};
		return createFormScaffold(com.goalplanner.model.GoalType.DIARY, body, onAdd);
	}

	/** ITEM step A (PICKER): a search field + tappable item result rows (icon +
	 *  name) filling the dock. Tapping an item stashes it and auto-advances to
	 *  DETAILS. Back returns to the type grid. */
	private JComponent buildItemPicker()
	{
		JPanel body = formBody();

		// Results scroll inside a height-capped pane (Task 6): the item list scrolls
		// in place rather than growing the surface past the dock cap.
		final ScrollablePanel results = resultsColumn();
		JScrollPane resultsScroll = boundedResultsScroll(results);

		JTextField searchField = new JTextField(14);
		styleField(searchField);

		Runnable doSearch = () ->
		{
			String query = searchField.getText().trim();
			results.removeAll();
			if (!query.isEmpty() && itemManager != null)
			{
				try
				{
					// Same source the icon picker uses; cap the list, and it scrolls
					// inside the bounded results pane past that cap.
					java.util.List<net.runelite.http.api.item.ItemPrice> found =
						itemManager.search(query);
					int max = Math.min(found.size(), 15);
					for (int i = 0; i < max; i++)
					{
						net.runelite.http.api.item.ItemPrice it = found.get(i);
						final int itemId = it.getId();
						final String name = it.getName();
						javax.swing.Icon icon = null;
						try { icon = new javax.swing.ImageIcon(itemManager.getImage(itemId)); }
						catch (Exception ignored) { }
						results.add(tappableRow(icon, name, null, () ->
						{
							dockPickedItemId = itemId;
							dockPickedItemName = name;
							navigateCreateStep(CreateStep.DETAILS);
						}));
					}
				}
				catch (Exception ignored) { }
			}
			results.revalidate();
			remeasureDock();
		};
		searchField.addActionListener(e -> doSearch.run());
		JButton searchBtn = flatButton("Search", false);
		searchBtn.addActionListener(e -> doSearch.run());

		JPanel searchRow = new JPanel(new BorderLayout(4, 0));
		searchRow.setOpaque(false);
		searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		searchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			searchField.getPreferredSize().height));
		searchRow.add(searchField, BorderLayout.CENTER);
		searchRow.add(searchBtn, BorderLayout.EAST);

		body.add(mutedTip("Tip: it's sometimes easier to add items from the "
			+ "in-game Collection Log."));
		body.add(Box.createVerticalStrut(6));

		JLabel itemLabel = new JLabel("Pick an item");
		itemLabel.setForeground(CREATE_FG_DIM);
		itemLabel.setFont(itemLabel.getFont().deriveFont(10f));
		itemLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(itemLabel);
		body.add(Box.createVerticalStrut(2));
		body.add(searchRow);
		body.add(Box.createVerticalStrut(4));
		body.add(resultsScroll);
		autofocus(searchField);
		return createFormScaffold(com.goalplanner.model.GoalType.ITEM_GRIND, body, null);
	}

	/** ITEM step B (DETAILS): the quantity for the picked item. Back returns to the
	 *  picker. */
	private JComponent buildItemDetails()
	{
		final int itemId = dockPickedItemId;
		final String name = dockPickedItemName;
		// Update mode: pre-filled quantity; a derived slice edits its per-period
		// chunk (the repeat block) instead of an absolute quantity.
		final Goal editing = editingGoal();
		final boolean derived = editing != null && editing.getRepeatChunk() > 0;

		JPanel body = formBody();
		javax.swing.Icon icon = null;
		if (itemId > 0)
		{
			try { icon = new javax.swing.ImageIcon(itemManager.getImage(itemId)); }
			catch (Exception ignored) { }
		}
		addFormRow(body, "Item", pickedHeader(icon, name != null ? name : "(none)"));

		JTextField qtyField = new JTextField(8);
		styleField(qtyField);
		if (editing != null && !derived)
		{
			qtyField.setText(Integer.toString(editing.getTargetValue()));
		}
		if (!derived)
		{
			addFormRow(body, "Quantity", qtyField);
		}
		final java.util.function.BooleanSupplier repeatApply = editing == null
			? () -> true
			: addUpdateRepeatBlock(body, editing, "Amount each period");

		Runnable onAdd = () ->
		{
			// Update mode first: an existing goal already HAS its item, so the
			// pick-an-item guard (a create-only concern) must not gate the save.
			if (editing != null)
			{
				final int newQty = derived ? 0 : parsePositiveInt(qtyField.getText());
				if (!derived && newQty <= 0)
				{
					warnCreate("Enter a quantity above zero.");
					return;
				}
				saveGoalEdit(() ->
				{
					if (!repeatApply.getAsBoolean())
					{
						return false;
					}
					if (!derived)
					{
						api.changeTarget(editing.getId(), newQty);
					}
					return true;
				});
				return;
			}
			if (itemId <= 0)
			{
				warnCreate("Pick an item first.");
				return;
			}
			final int qty = parsePositiveInt(qtyField.getText());
			if (qty <= 0)
			{
				warnCreate("Enter a quantity above zero.");
				return;
			}
			goToSectionPick(sectionId ->
			{
				String id = api.addItemGoal(itemId, qty);
				api.moveGoalToSection(id, sectionId);
			});
		};
		return createFormScaffold(com.goalplanner.model.GoalType.ITEM_GRIND, body, onAdd,
			() -> navigateCreateStep(CreateStep.PICKER));
	}

	private JComponent buildCombatForm()
	{
		JPanel body = formBody();

		final int[] selectedId = { -1 };
		JLabel selectedLabel = new JLabel("No task selected");
		selectedLabel.setForeground(CREATE_FG_DIM);
		selectedLabel.setFont(selectedLabel.getFont().deriveFont(10f));
		selectedLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel results = new JPanel();
		results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
		results.setOpaque(false);
		results.setAlignmentX(Component.LEFT_ALIGNMENT);

		JTextField searchField = new JTextField(14);
		styleField(searchField);

		Runnable doSearch = () ->
		{
			String query = searchField.getText().trim();
			results.removeAll();
			selectedId[0] = -1;
			selectedLabel.setText("No task selected");
			java.util.List<com.goalplanner.data.WikiCaRepository.CaInfo> found =
				api.searchCombatAchievements(query, 8);
			if (found.isEmpty() && !query.isEmpty())
			{
				JLabel none = new JLabel("No matches (task data still loading?)");
				none.setForeground(CREATE_FG_DIM);
				none.setFont(none.getFont().deriveFont(10f));
				none.setAlignmentX(Component.LEFT_ALIGNMENT);
				results.add(none);
			}
			for (com.goalplanner.data.WikiCaRepository.CaInfo ca : found)
			{
				String label = ca.name != null ? ca.name : ca.task;
				String tip = ca.tier != null ? ca.tier + " - " + ca.task : ca.task;
				results.add(buildPickRow(label, tip, ca.id, selectedId, selectedLabel, results));
			}
			results.revalidate();
			remeasureDock();
		};
		searchField.addActionListener(e -> doSearch.run());
		JButton searchBtn = flatButton("Search", false);
		searchBtn.addActionListener(e -> doSearch.run());

		JPanel searchRow = new JPanel(new BorderLayout(4, 0));
		searchRow.setOpaque(false);
		searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		searchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			searchField.getPreferredSize().height));
		searchRow.add(searchField, BorderLayout.CENTER);
		searchRow.add(searchBtn, BorderLayout.EAST);

		body.add(mutedTip("Tip: it's often easier to add these from the "
			+ "in-game Combat Achievements log."));
		body.add(Box.createVerticalStrut(6));

		JLabel taskLabel = new JLabel("Task");
		taskLabel.setForeground(CREATE_FG_DIM);
		taskLabel.setFont(taskLabel.getFont().deriveFont(10f));
		taskLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(taskLabel);
		body.add(Box.createVerticalStrut(2));
		body.add(searchRow);
		body.add(Box.createVerticalStrut(4));
		body.add(results);
		body.add(Box.createVerticalStrut(4));
		body.add(selectedLabel);

		Runnable onAdd = () ->
		{
			if (selectedId[0] <= 0)
			{
				warnCreate("Search for a combat task and pick one.");
				return;
			}
			final int caId = selectedId[0];
			goToSectionPick(sectionId ->
			{
				String id = api.addCombatAchievementGoal(caId);
				api.moveGoalToSection(id, sectionId);
			});
		};
		return createFormScaffold(com.goalplanner.model.GoalType.COMBAT_ACHIEVEMENT, body, onAdd);
	}

	/** Height cap (px) for a picker's scrollable results area (Task 6): the boss /
	 *  item result list scrolls INSIDE the dock rather than growing the surface past
	 *  {@code CREATE_MAX_H} and stranding the list off-screen. */
	private static final int PICKER_RESULTS_H = 150;

	/** A BoxLayout results panel that tracks the viewport width (so no horizontal
	 *  scroll) for use inside {@link #boundedResultsScroll}. */
	private ScrollablePanel resultsColumn()
	{
		ScrollablePanel p = new ScrollablePanel(null);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	/** Wrap a results column in a height-capped vertical scroll pane so a long
	 *  picker list scrolls in place while the search field above stays fixed
	 *  (Task 6). */
	private JScrollPane boundedResultsScroll(JComponent results)
	{
		JScrollPane sp = new JScrollPane(results,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		sp.setBorder(null);
		sp.setOpaque(false);
		sp.getViewport().setOpaque(false);
		sp.getVerticalScrollBar().setUnitIncrement(16);
		sp.setAlignmentX(Component.LEFT_ALIGNMENT);
		sp.setPreferredSize(new Dimension(0, PICKER_RESULTS_H));
		sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, PICKER_RESULTS_H));
		return sp;
	}

	/** A curated short head of commonly-picked bosses for the boss picker's default
	 *  (empty-search) view (Task 6), filtered to names actually present in the data;
	 *  topped up alphabetically to {@code n} so the default is never empty whatever
	 *  the corpus. */
	private java.util.List<String> recommendedBosses(String[] all, int n)
	{
		java.util.Set<String> present = new java.util.HashSet<>(java.util.Arrays.asList(all));
		String[] curated = {
			"Zulrah", "Vorkath", "Vardorvis", "Alchemical Hydra", "Cerberus",
			"Kraken", "Giant Mole", "Abyssal Sire", "Corporeal Beast", "Nex",
			"General Graardor", "Commander Zilyana", "Kree'arra", "K'ril Tsutsaroth",
			"Phantom Muspah", "Duke Sucellus",
		};
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
		for (String b : curated)
		{
			if (present.contains(b))
			{
				out.add(b);
			}
			if (out.size() >= n)
			{
				break;
			}
		}
		for (String b : all)
		{
			if (out.size() >= n)
			{
				break;
			}
			out.add(b);
		}
		return new java.util.ArrayList<>(out);
	}

	/** A tappable result row (optional icon + label) that runs {@code onPick} when
	 *  clicked. Used by the stepped tall pickers (boss/item), which auto-advance to
	 *  the DETAILS step on selection, so no persistent highlight is needed - just a
	 *  hover cue. */
	private JComponent tappableRow(javax.swing.Icon icon, String label, String tooltip,
		Runnable onPick)
	{
		final JPanel row = new RoundedPaint.RoundedPanel(new BorderLayout(6, 0), RoundedPaint.RADIUS);
		row.setBackground(CREATE_TILE_BG);
		row.setBorder(RoundedPaint.border(CREATE_TILE_BG, 1,
			RoundedPaint.RADIUS, new java.awt.Insets(3, 5, 3, 5)));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		if (tooltip != null)
		{
			row.setToolTipText(tooltip);
		}
		if (icon != null)
		{
			row.add(new JLabel(icon), BorderLayout.WEST);
		}
		JLabel nm = new JLabel(label);
		nm.setForeground(CREATE_FG);
		nm.setFont(nm.getFont().deriveFont(11f));
		row.add(nm, BorderLayout.CENTER);
		row.addMouseListener(new MouseAdapter()
		{
			@Override public void mouseEntered(MouseEvent e) { row.setBackground(CREATE_TILE_HOVER); }
			@Override public void mouseExited(MouseEvent e) { row.setBackground(CREATE_TILE_BG); }
			@Override public void mouseClicked(MouseEvent e) { if (onPick != null) onPick.run(); }
		});
		return row;
	}

	/** A tappable text search-result row (label + tooltip) carrying an int id.
	 *  Shared by the Combat picker; selecting writes {@code id} to
	 *  {@code selectedId[0]} and re-highlights within {@code results}. */
	private JComponent buildPickRow(String label, String tooltip, int id, int[] selectedId,
		JLabel selectedLabel, JPanel results)
	{
		JPanel row = new RoundedPaint.RoundedPanel(new BorderLayout(), RoundedPaint.RADIUS);
		row.setBackground(CREATE_TILE_BG);
		row.setBorder(RoundedPaint.border(CREATE_TILE_BG, 1,
			RoundedPaint.RADIUS, new java.awt.Insets(3, 5, 3, 5)));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText(tooltip);

		JLabel nm = new JLabel(label);
		nm.setForeground(CREATE_FG);
		nm.setFont(nm.getFont().deriveFont(11f));
		row.add(nm, BorderLayout.CENTER);

		row.addMouseListener(new MouseAdapter()
		{
			@Override public void mouseClicked(MouseEvent e)
			{
				selectedId[0] = id;
				selectedLabel.setText("Selected: " + label);
				for (Component c : results.getComponents())
				{
					boolean sel = c == row;
					c.setBackground(sel ? CREATE_SEL_BG : CREATE_TILE_BG);
					if (c instanceof JComponent)
					{
						((JComponent) c).setBorder(RoundedPaint.border(
							sel ? CREATE_SEL_BORDER : CREATE_TILE_BG, 1,
							RoundedPaint.RADIUS, new java.awt.Insets(3, 5, 3, 5)));
					}
				}
				results.repaint();
			}
		});
		return row;
	}

	/** "EASY" -> "Easy". Small helper for enum-name rendering in the create forms. */
	private static String capitalize(String s)
	{
		if (s == null || s.isEmpty()) return s;
		return s.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
			+ s.substring(1).toLowerCase(java.util.Locale.ROOT);
	}

	// ----- repeatable segmented toggle (ADR-0008) -----

	/** A 2-segment either/or toggle [One-time | Repeatable] in the period-pill
	 *  visual style (Task 2). Writes the choice to {@code mode[0]} (false =
	 *  One-time, true = Repeatable) and runs {@code onChange} after each switch so
	 *  the form can swap which input set shows - never both. */
	private JComponent buildModeToggle(boolean[] mode, Runnable onChange)
	{
		final int[] sel = { mode[0] ? 1 : 0 };
		return buildSegmentedToggle(new String[] { "One-time", "Repeatable" }, sel, () ->
		{
			mode[0] = sel[0] == 1;
			if (onChange != null) onChange.run();
		});
	}

	/** An N-segment either/or toggle in the period-pill visual (the general form of
	 *  {@link #buildModeToggle}). Writes the chosen segment index to {@code sel[0]}
	 *  and runs {@code onChange} after each switch so the form can swap which input
	 *  set shows - never more than one. Used for the boss Total/Relative/Repeatable
	 *  3-mode toggle (Task 2). */
	private JComponent buildSegmentedToggle(String[] labels, int[] sel, Runnable onChange)
	{
		JPanel row = new JPanel(new GridLayout(1, labels.length, 4, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		final JButton[] segs = new JButton[labels.length];
		Runnable refresh = () ->
		{
			for (int i = 0; i < segs.length; i++)
			{
				boolean on = i == sel[0];
				JButton b = segs[i];
				b.setBackground(on ? CREATE_SEL_BG : CREATE_TILE_BG);
				b.setForeground(on ? CREATE_PRIMARY_FG : CREATE_FG);
				b.setBorder(RoundedPaint.border(on ? CREATE_SEL_BORDER : CREATE_TILE_BG, 1,
					RoundedPaint.RADIUS, new java.awt.Insets(4, 10, 4, 10)));
			}
		};
		for (int i = 0; i < labels.length; i++)
		{
			final int idx = i;
			JButton b = new RoundedPaint.RoundedButton(labels[i]);
			segs[i] = b;
			b.setFont(b.getFont().deriveFont(Font.BOLD, 11f));
			b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			b.addActionListener(e ->
			{
				sel[0] = idx;
				refresh.run();
				if (onChange != null) onChange.run();
			});
			row.add(b);
		}
		refresh.run();
		return row;
	}

	/** A read-only "chosen pick" header row: optional icon + name, shown on a tall
	 *  type's DETAILS screen so the picker's selection stays visible. */
	private JComponent pickedHeader(javax.swing.Icon icon, String name)
	{
		JLabel l = new JLabel(name);
		if (icon != null)
		{
			l.setIcon(icon);
			l.setIconTextGap(6);
		}
		l.setForeground(CREATE_FG);
		l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	/** Daily / Weekly / Monthly pills; the tapped one is written to
	 *  {@code out[0]} and highlighted. */
	private JComponent buildPeriodPills(com.goalplanner.model.RepeatPeriod[] out)
	{
		com.goalplanner.model.RepeatPeriod[] periods = {
			com.goalplanner.model.RepeatPeriod.DAILY,
			com.goalplanner.model.RepeatPeriod.WEEKLY,
			com.goalplanner.model.RepeatPeriod.MONTHLY };
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		row.setOpaque(false);
		java.util.List<JButton> pills = new ArrayList<>();
		java.util.Map<JButton, com.goalplanner.model.RepeatPeriod> owner = new HashMap<>();
		Runnable refresh = () ->
		{
			for (JButton b : pills)
			{
				boolean sel = owner.get(b) == out[0];
				b.setBackground(sel ? CREATE_SEL_BG : CREATE_TILE_BG);
				b.setForeground(sel ? CREATE_PRIMARY_FG : CREATE_FG);
			}
		};
		for (com.goalplanner.model.RepeatPeriod p : periods)
		{
			JButton b = new RoundedPaint.RoundedButton(p.getLabel());
			b.setFont(b.getFont().deriveFont(11f));
			b.setBorder(new EmptyBorder(3, 10, 3, 10));
			b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			pills.add(b);
			owner.put(b, p);
			b.addActionListener(e -> { out[0] = p; refresh.run(); });
			row.add(b);
		}
		refresh.run();
		return row;
	}

	/** Re-lay-out the dock after a disclosure toggle changes the form's height,
	 *  so the dock grows/shrinks to fit (ADR-0008: the form grows to fit). */
	private void remeasureDock()
	{
		actionDock.revalidate();
		actionDock.repaint();
		revalidate();
		repaint();
	}

	// ----- create-surface shared UI helpers -----

	/** Wrap a create form's body in the standard scaffold: a full-width CREATE
	 *  indicator bar, a Back + type-title header, the body, and a primary Add
	 *  button. */
	private JComponent createFormScaffold(com.goalplanner.model.GoalType type,
		JComponent body, Runnable onAdd)
	{
		return createFormScaffold(type, body, onAdd, () -> navigateCreate(null));
	}

	/** As above, but {@code onBack} names where the header "Back" returns - the
	 *  type grid for a single-step form or picker, the PICKER step for a tall
	 *  type's DETAILS screen. */
	private JComponent createFormScaffold(com.goalplanner.model.GoalType type,
		JComponent body, Runnable onAdd, Runnable onBack)
	{
		// Update mode (the Selected view's "Edit goal"): the same scaffold, but Back
		// abandons the edit and returns to the Selected view, the title says Edit,
		// and the primary button saves instead of advancing to the section chooser.
		final boolean update = dockEditFormGoalId != null;
		JPanel inner = new JPanel(new BorderLayout(0, 6));
		inner.setOpaque(false);
		inner.setBorder(new EmptyBorder(6, 8, 8, 8));

		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setOpaque(false);
		JLabel title = new JLabel(update
			? "Edit " + tileLabel(type).toLowerCase(java.util.Locale.ROOT) + " goal"
			: tileLabel(type) + " goal");
		title.setForeground(CREATE_FG);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
		// Create mode keeps a header Back (it steps back through the create flow).
		// Update mode has no header Back: abandoning an edit is a deliberate red
		// Discard sitting beside Save changes in the footer (user feedback).
		if (!update)
		{
			JButton back = flatButton("Back", false);
			back.addActionListener(e -> onBack.run());
			header.add(back, BorderLayout.WEST);
		}
		header.add(title, BorderLayout.CENTER);
		inner.add(header, BorderLayout.NORTH);

		inner.add(body, BorderLayout.CENTER);

		if (onAdd != null)
		{
			// The primary button no longer creates directly (note 3): it validates
			// and advances to the landing-section chooser, which performs the create.
			// In update mode it validates and SAVES the existing goal instead - no
			// section step, no second goal.
			JButton add = flatButton(update ? "Save changes" : "Next: choose section", true);
			add.addActionListener(e -> onAdd.run());
			JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
			footer.setOpaque(false);
			if (update)
			{
				// Discard sits at the same level as Save changes, in red, so
				// abandoning an edit reads as the destructive choice it is.
				JButton discard = flatButton("Discard", false);
				discard.setForeground(DISCARD_FG);
				discard.setBackground(DISCARD_BG);
				hover(discard, DISCARD_BG, DISCARD_HOVER);
				discard.setToolTipText("Discard these changes");
				discard.addActionListener(e -> closeEditGoalForm());
				footer.add(discard);
			}
			footer.add(add);
			inner.add(footer, BorderLayout.SOUTH);
		}
		return plainSurface(inner);
	}

	private JPanel formBody()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	/** Add a left-aligned "label above field" row to a BoxLayout body, clamping
	 *  the field's height so BoxLayout does not stretch it vertically. */
	private void addFormRow(JPanel body, String label, JComponent field)
	{
		if (label != null)
		{
			JLabel l = new JLabel(label);
			l.setForeground(CREATE_FG_DIM);
			l.setFont(l.getFont().deriveFont(10f));
			l.setAlignmentX(Component.LEFT_ALIGNMENT);
			body.add(l);
			body.add(Box.createVerticalStrut(2));
		}
		field.setAlignmentX(Component.LEFT_ALIGNMENT);
		field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));
		body.add(field);
		body.add(Box.createVerticalStrut(6));
	}

	/** A small left-aligned muted advisory line (the {@link #buildAddedLine}
	 *  styling) for the create forms - "Tip: ..." helpers pointing at an easier
	 *  in-game path. Wraps so a long tip does not force the dock wider. */
	private JLabel mutedTip(String text)
	{
		JLabel l = new JLabel("<html>" + text + "</html>");
		l.setForeground(CREATE_FG_DIM);
		l.setFont(l.getFont().deriveFont(10f));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private JButton flatButton(String text, boolean primary)
	{
		JButton b = new RoundedPaint.RoundedButton(text);
		b.setForeground(primary ? CREATE_PRIMARY_FG : CREATE_FG);
		b.setBackground(primary ? CREATE_PRIMARY_BG : CREATE_TILE_BG);
		b.setFont(b.getFont().deriveFont(primary ? Font.BOLD : Font.PLAIN, 11f));
		b.setBorder(new EmptyBorder(4, 10, 4, 10));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		hover(b, primary ? CREATE_PRIMARY_BG : CREATE_TILE_BG,
			primary ? CREATE_PRIMARY_HOVER : CREATE_TILE_HOVER);
		return b;
	}

	private void styleField(JComponent f)
	{
		f.setForeground(CREATE_FG);
		f.setBackground(CREATE_FIELD_BG);
		f.setFont(f.getFont().deriveFont(11f));
		if (f instanceof JTextField)
		{
			// A rounded outline replaces the square field edge. The field is made
			// non-opaque so its square background fill can't poke square corners
			// past the rounded stroke; padding matches the prior EmptyBorder so the
			// field keeps its size. The dark surface shows through the rounded box.
			f.setOpaque(false);
			f.setBorder(RoundedPaint.border(CREATE_FIELD_STROKE, 1,
				RoundedPaint.RADIUS, new java.awt.Insets(3, 5, 3, 5)));
		}
	}

	private static javax.swing.DefaultListCellRenderer textRenderer(
		java.util.function.Function<Object, String> namer)
	{
		return new javax.swing.DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value,
				int index, boolean isSelected, boolean cellHasFocus)
			{
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (value != null)
				{
					setText(namer.apply(value));
				}
				return this;
			}
		};
	}

	private static String tileLabel(com.goalplanner.model.GoalType t)
	{
		switch (t)
		{
			case SKILL: return "Skill";
			case QUEST: return "Quest";
			case DIARY: return "Diary";
			case COMBAT_ACHIEVEMENT: return "Combat";
			case BOSS: return "Boss";
			case ITEM_GRIND: return "Item";
			case ACCOUNT: return "Account";
			case CUSTOM: return "Custom";
			default: return t.getDisplayName();
		}
	}

	/** Parse a target/quantity field: strips commas, returns -1 when blank or
	 *  not a positive integer. */
	private static int parsePositiveInt(String s)
	{
		if (s == null) return -1;
		try
		{
			int v = Integer.parseInt(s.trim().replace(",", ""));
			return v > 0 ? v : -1;
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private void warnCreate(String msg)
	{
		javax.swing.JOptionPane.showMessageDialog(this, msg,
			dockEditFormGoalId != null ? "Edit goal" : "Add goal",
			javax.swing.JOptionPane.WARNING_MESSAGE);
	}

	// ============================================================
	// Unified EDIT surface (ADR-0008): a selected goal renders the SAME per-type
	// form as create, pre-filled, with its PARAMETERS as inline commit-on-blur
	// fields plus the lifecycle ACTION chips. Mounted from refreshDock()'s GOAL
	// case via buildEditSurface (the single-place rule). Every chip REUSES an
	// existing dock* handler / dialog - none is rebuilt. Field commits go
	// straight to the API (each API method no-ops on an unchanged value, so an
	// idle blur never spams undo history).
	// ============================================================

	/** Whether {@code type} routes through the unified edit form yet. Types not
	 *  listed fall back to the legacy button-strip {@link #buildGoalDock} during
	 *  the incremental migration. */
	private boolean usesUnifiedEditForm(GoalType type)
	{
		switch (type)
		{
			case SKILL:
			case ITEM_GRIND:
			case BOSS:
			case CUSTOM:
			case ACCOUNT:
			case QUEST:
			case DIARY:
			case COMBAT_ACHIEVEMENT:
				return true;
			default:
				return false;
		}
	}

	/** Whether the Selected view offers an "Edit goal" button for {@code type} - i.e.
	 *  whether its create form has anything an existing goal can change. QUEST /
	 *  DIARY / COMBAT_ACHIEVEMENT targets are immutable (they complete off game
	 *  progress), so they stay a pure read-only summary. */
	private static boolean hasEditableParams(GoalType type)
	{
		switch (type)
		{
			case SKILL:
			case ITEM_GRIND:
			case BOSS:
			case CUSTOM:
			case ACCOUNT:
				return true;
			default:
				return false;
		}
	}

	/** Build the pre-filled edit surface for {@code g}: the complete checkbox, the
	 *  type's parameter fields, and the lifecycle action chips, under a full-width
	 *  SELECTED indicator bar. */
	private JComponent buildEditSurface(Goal g)
	{
		JComponent body;
		switch (g.getType())
		{
			case SKILL:       body = buildSkillEditBody(g); break;
			case ITEM_GRIND:  body = buildItemEditBody(g); break;
			case BOSS:        body = buildBossEditBody(g); break;
			case CUSTOM:      body = buildCustomEditBody(g); break;
			case ACCOUNT:     body = buildAccountEditBody(g); break;
			default:          body = buildThinEditBody(g); break; // QUEST/DIARY/CA
		}
		return editFormScaffold(g, body);
	}

	/** Wrap an edit body in the SELECTED scaffold: a full-width indicator bar, a
	 *  Complete/Reopen checkbox heading the form, the parameter fields, and the
	 *  lifecycle action chips. No Add button (edits apply on commit). */
	private JComponent editFormScaffold(Goal g, JComponent body)
	{
		final String gid = g.getId();
		final boolean complete = g.isComplete();
		// Manual completion is CUSTOM/ITEM-only (mirrors buildGoalDock): an
		// auto-tracked type completes off live data, so it gets a Reopen once done
		// but no manual "Complete" while incomplete.
		final boolean manual = g.getType() == GoalType.CUSTOM || g.getType() == GoalType.ITEM_GRIND;
		// Absolute goals (QUEST/DIARY/COMBAT_ACHIEVEMENT) complete purely off game
		// progress varbits - the user can never set completion by hand (note 4).
		final boolean absolute = g.getType() == GoalType.QUEST
			|| g.getType() == GoalType.DIARY
			|| g.getType() == GoalType.COMBAT_ACHIEVEMENT;
		JPanel inner = new JPanel(new BorderLayout(0, 6));
		inner.setOpaque(false);
		inner.setBorder(new EmptyBorder(6, 8, 8, 8));

		// Complete / Reopen as a checkbox heading the form (ADR-0008).
		JCheckBox done = null;
		if (absolute)
		{
			// An absolute goal ALWAYS shows the checkbox but greyed + disabled: it
			// reflects game-tracked state and can never be toggled by hand (note 4).
			// A disabled JCheckBox in RuneLite's dark LAF does not paint its tick, so a
			// completed goal would read as unchecked. Supply explicit painted icons that
			// render regardless of enabled state and reuse the same ShapeIcons check the
			// card selection UI uses, so the edit view agrees with g.isComplete(): a
			// visible green tick when complete, a visible empty box when not.
			done = new JCheckBox(complete ? "Completed" : "Complete");
			done.setOpaque(false);
			done.setForeground(CREATE_FG_DIM);
			done.setFont(done.getFont().deriveFont(11f));
			done.setSelected(complete);
			done.setEnabled(false);
			done.setDisabledIcon(ShapeIcons.checkboxEmpty(14, ABSOLUTE_CHECK_EMPTY));
			done.setDisabledSelectedIcon(ShapeIcons.checkboxChecked(14, ABSOLUTE_CHECK_DONE));
			done.setToolTipText("Tracked by game progress - can't be set manually.");
		}
		// Toggling completion changes the goal's structure, so force the form to
		// re-render. Omitted for an auto-tracked goal that is not yet complete (no
		// manual completion).
		else if (complete || manual)
		{
			final JCheckBox toggle = new JCheckBox(complete ? "Completed" : "Complete");
			toggle.setOpaque(false);
			toggle.setForeground(CREATE_FG);
			toggle.setFont(toggle.getFont().deriveFont(11f));
			toggle.setSelected(complete);
			toggle.setToolTipText(complete
				? "Mark incomplete and let tracking re-derive it"
				: "Mark this goal complete");
			toggle.addActionListener(e ->
			{
				if (toggle.isSelected())
				{
					api.markGoalComplete(gid);
				}
				else
				{
					api.markGoalIncomplete(gid);
				}
				refreshEditForm();
			});
			done = toggle;
		}

		// Part 1: a full-width "Deselect" button pinned at the TOP of the edit
		// surface (above the form), styled identically to the MULTI "Deselect (N)"
		// lead bar so single + multi match. The completion checkbox, when present,
		// sits just below it.
		JButton deselect = com.goalplanner.ui.dock.ActionDock.leadButton(
			"Deselect", "Clear the selection", () -> api.clearGoalSelection());
		deselect.setAlignmentX(Component.LEFT_ALIGNMENT);
		deselect.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			deselect.getPreferredSize().height));
		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setOpaque(false);
		north.add(deselect);
		if (done != null)
		{
			done.setAlignmentX(Component.LEFT_ALIGNMENT);
			north.add(Box.createVerticalStrut(6));
			north.add(done);
		}
		inner.add(north, BorderLayout.NORTH);

		inner.add(body, BorderLayout.CENTER);

		// Flattened selected view: "Added <date>", then the Data chips (flat, no
		// drill-in), then the itemized Relations block (list + Add relation), then
		// the remaining Actions drill-in group - top to bottom. Added is hidden for
		// goals created before the createdAt field existed (they deserialize as 0).
		JComponent added = buildAddedLine(g);
		JComponent relations = buildRelationsBlock(g);

		JPanel dataRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
		dataRow.setOpaque(false);
		dataRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		buildDataChips(g, dataRow);

		JComponent chips = buildEditChips(g);

		JPanel south = new JPanel();
		south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
		south.setOpaque(false);
		south.setAlignmentX(Component.LEFT_ALIGNMENT);
		// The Selected view is read-only; "Edit goal" opens this goal's own CREATE
		// form pre-filled in update mode (see openEditGoalForm). Types whose target
		// is immutable (QUEST / DIARY / COMBAT_ACHIEVEMENT) have nothing to edit, so
		// they get no button.
		if (hasEditableParams(g.getType()))
		{
			JButton editGoal = flatButton("Edit goal", false);
			editGoal.setToolTipText("Open this goal in the create form to change it");
			editGoal.addActionListener(e -> openEditGoalForm(g));
			JPanel editRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
			editRow.setOpaque(false);
			editRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			editRow.add(editGoal);
			editRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
				editRow.getPreferredSize().height));
			south.add(editRow);
			south.add(Box.createVerticalStrut(4));
		}
		if (added != null)
		{
			added.setAlignmentX(Component.LEFT_ALIGNMENT);
			south.add(added);
			south.add(Box.createVerticalStrut(4));
		}
		south.add(dataRow);
		if (relations != null)
		{
			relations.setAlignmentX(Component.LEFT_ALIGNMENT);
			south.add(Box.createVerticalStrut(4));
			south.add(relations);
		}
		chips.setAlignmentX(Component.LEFT_ALIGNMENT);
		south.add(Box.createVerticalStrut(4));
		south.add(chips);
		inner.add(south, BorderLayout.SOUTH);
		// No "SELECTED" indicator bar: the full-width Deselect button now heads the
		// edit surface, so the label was redundant (user feedback).
		return plainSurface(inner);
	}

	private static final java.time.format.DateTimeFormatter ADDED_DATE_FMT =
		java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH);

	/** A muted "Added: &lt;date&gt;" line for the Selected view, in the player's local
	 *  zone. Returns null when the goal predates the createdAt field (legacy goals
	 *  deserialize it as 0), so no "Jan 1, 1970" leaks through. */
	private JComponent buildAddedLine(Goal g)
	{
		long created = g.getCreatedAt();
		if (created <= 0)
		{
			return null;
		}
		String date = java.time.Instant.ofEpochMilli(created)
			.atZone(java.time.ZoneId.systemDefault())
			.toLocalDate()
			.format(ADDED_DATE_FMT);
		JLabel l = new JLabel("Added: " + date);
		l.setForeground(CREATE_FG_DIM);
		l.setFont(l.getFont().deriveFont(10f));
		return l;
	}

	/** Part 3: the itemized Relations block for the flattened Selected view. Each
	 *  relation renders as its own row - a direction arrow (up = Requires, down =
	 *  Required by), the related goal's name, and a small X that removes THAT single
	 *  edge (one undo, then re-render). Below the list sits a "+ Add relation"
	 *  button that reveals two direction buttons (Requires... / Required by...),
	 *  each entering relation-pick mode. Returns null only for a completed goal with
	 *  no relations (nothing to show and none can be added); an incomplete goal with
	 *  none still shows just "+ Add relation". */
	private JComponent buildRelationsBlock(Goal g)
	{
		final String gid = g.getId();
		final boolean complete = g.isComplete();
		java.util.List<String> reqs = api.getRequirements(gid);
		java.util.List<String> deps = api.getDependents(gid);
		boolean hasAny = (reqs != null && !reqs.isEmpty()) || (deps != null && !deps.isEmpty());
		// Relations can only be added while the goal is incomplete (mirrors the old
		// Requires / Required-by chips, which were gated on !complete).
		final boolean canAdd = !complete;
		if (!hasAny && !canAdd)
		{
			return null;
		}

		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Requires edges: up arrow (this goal sits ON TOP of what it needs). The X
		// drops the requirement this goal holds: removeRequirement(thisGoal, reqId).
		if (reqs != null)
		{
			for (final String reqId : reqs)
			{
				String name = relationName(reqId);
				if (name == null)
				{
					continue;
				}
				// Completed goals are reference history: their graph is read-only
				// (the old Relations menu and the Drop-reqs chip were both hidden
				// on complete), so the row renders with no X.
				p.add(relationEdgeRow(ShapeIcons.upTriangle(9, RELATION_REQ_COLOR), name,
					complete ? COMPLETE_RELATION_TIP : "Remove this requirement",
					complete ? null
						: () -> { api.removeRequirement(gid, reqId); refreshEditForm(); }));
			}
		}
		// Required-by edges: down arrow (things below depend on this goal). The
		// dependent is the one that HOLDS the requirement, so the X drops it from the
		// dependent's side: removeRequirement(dependentId, thisGoal).
		if (deps != null)
		{
			for (final String depId : deps)
			{
				String name = relationName(depId);
				if (name == null)
				{
					continue;
				}
				// getDependents() is the reverse index, which carries BOTH AND and
				// OR edges - but removeRequirement only unlinks AND edges, and no
				// API removes an OR edge at all. An OR-only dependent therefore has
				// nothing its X could do, so it renders locked (see relationEdgeRow).
				final boolean removable = !complete && isAndRequirementOf(depId, gid);
				String tip;
				if (complete)
				{
					tip = COMPLETE_RELATION_TIP;
				}
				else if (!removable)
				{
					tip = OR_RELATION_TIP;
				}
				else
				{
					tip = "Remove this dependent";
				}
				p.add(relationEdgeRow(ShapeIcons.downTriangle(9, RELATION_DEP_COLOR), name, tip,
					removable ? () -> { api.removeRequirement(depId, gid); refreshEditForm(); }
						: null));
			}
		}

		if (canAdd)
		{
			// "+ Add relation" reveals two direction buttons IN PLACE via a local
			// swap. Picking a direction enters relation-pick mode (which rebuilds the
			// whole surface), so the reveal never needs to survive a refresh.
			final JPanel addHost = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 0));
			addHost.setOpaque(false);
			addHost.setAlignmentX(Component.LEFT_ALIGNMENT);
			final Runnable[] showCollapsed = new Runnable[1];
			final Runnable showExpanded = () ->
			{
				addHost.removeAll();
				addHost.add(chip("Requires...", "Then click a goal to require it",
					() -> enterRelationMode(gid, true)));
				addHost.add(chip("Required by...", "Then click a goal that should require this",
					() -> enterRelationMode(gid, false)));
				addHost.add(chip("Cancel", "Keep relations as they are",
					() -> showCollapsed[0].run()));
				addHost.revalidate();
				addHost.repaint();
			};
			showCollapsed[0] = () ->
			{
				addHost.removeAll();
				addHost.add(chip("+ Add relation", "Add a requirement or dependent",
					showExpanded));
				addHost.revalidate();
				addHost.repaint();
			};
			showCollapsed[0].run();
			addHost.setMaximumSize(new Dimension(Integer.MAX_VALUE,
				addHost.getPreferredSize().height));
			if (hasAny)
			{
				p.add(Box.createVerticalStrut(4));
			}
			p.add(addHost);
		}
		return p;
	}

	private static final Color RELATION_REQ_COLOR = new Color(0x8F, 0xBF, 0x8F);
	private static final Color RELATION_DEP_COLOR = new Color(0xD0, 0xA8, 0x5A);

	/** One relation edge: a direction arrow + the related goal's name on the left,
	 *  and a small X on the right that removes just that edge. */
	private JComponent relationEdgeRow(javax.swing.Icon icon, String name,
		String removeTip, Runnable onRemove)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel l = new JLabel(name, icon, SwingConstants.LEFT);
		l.setForeground(CREATE_FG);
		l.setFont(l.getFont().deriveFont(12f));
		l.setIconTextGap(6);
		row.add(l, BorderLayout.CENTER);
		if (onRemove == null)
		{
			// Locked edge: no X to press (nothing it could do). A muted dash holds
			// the same slot so the list stays aligned, and its tooltip says why.
			JLabel lock = new JLabel("-");
			lock.setForeground(CREATE_FG_DIM);
			lock.setFont(lock.getFont().deriveFont(Font.BOLD, 10f));
			lock.setBorder(new EmptyBorder(1, 7, 1, 7));
			lock.setToolTipText(removeTip);
			row.add(lock, BorderLayout.EAST);
		}
		else
		{
			JButton x = flatButton("X", false);
			x.setToolTipText(removeTip);
			x.setFont(x.getFont().deriveFont(Font.BOLD, 10f));
			x.setBorder(new EmptyBorder(1, 7, 1, 7));
			x.addActionListener(e -> onRemove.run());
			row.add(x, BorderLayout.EAST);
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** Tooltip on a relation row that cannot be dropped because the goal is done. */
	private static final String COMPLETE_RELATION_TIP =
		"Completed goals keep their relations as history";
	/** Tooltip on an OR-only dependent: the link is an alternative prerequisite
	 *  seeded with the goal (boss / quest unlock data), and no API removes it. */
	private static final String OR_RELATION_TIP =
		"Alternative (OR) prerequisite - seeded with the goal, cannot be removed here";

	/** Whether {@code fromId} holds {@code toId} as a hard (AND) requirement - the
	 *  only edge kind {@code api.removeRequirement} can drop. A dependent listed by
	 *  {@code api.getDependents} that fails this test is linked by an OR edge only
	 *  (the reverse index carries both kinds), and removing it is a silent no-op. */
	private boolean isAndRequirementOf(String fromId, String toId)
	{
		Goal from = goalStore.findGoalById(fromId);
		return from != null && from.getRequiredGoalIds() != null
			&& from.getRequiredGoalIds().contains(toId);
	}

	/** Resolve a relation id to its goal name; null when the id has no live goal
	 *  (a dangling relation), so it is skipped from the list. */
	private String relationName(String id)
	{
		Goal r = goalStore.findGoalById(id);
		return (r != null && r.getName() != null) ? r.getName() : null;
	}

	/** Re-render the mounted edit form (after a structural change) by dropping
	 *  the mount guard and refreshing. */
	private void refreshEditForm()
	{
		dockEditMounted = false;
		refreshDock();
	}

	// --- "Edit goal" overlay: the CREATE form in UPDATE mode --------------------
	// The Selected view is read-only; its "Edit goal" button opens the goal's own
	// create form, pre-filled, with the primary button switched from "Next: choose
	// section" to "Save changes". There is NO second form: refreshDock mounts
	// buildCreateForm(type) exactly as the create flow does, and every builder
	// branches on editingGoal() for its pre-fill + save. Update mode never runs the
	// section-pick step and never creates a goal.

	/** The goal the mounted create form is UPDATING, or null in normal create mode.
	 *  Every create builder calls this to decide pre-fill + primary action. */
	private Goal editingGoal()
	{
		return dockEditFormGoalId == null ? null : goalStore.findGoalById(dockEditFormGoalId);
	}

	/** Open {@code g}'s create form in update mode. The picker step is skipped (no
	 *  API changes a goal's skill / boss / item), so the pick is stashed exactly the
	 *  way the picker screens stash theirs and the form opens on DETAILS. */
	private void openEditGoalForm(Goal g)
	{
		if (g == null)
		{
			return;
		}
		dockEditFormGoalId = g.getId();
		dockEditFormMounted = false;
		dockCreateSeed = null;
		dockCreateStep = CreateStep.DETAILS;
		resetCreatePicks();
		switch (g.getType())
		{
			case SKILL:      dockPickedSkill = skillOf(g); break;
			case BOSS:       dockPickedBoss = g.getBossName(); break;
			case ITEM_GRIND:
				dockPickedItemId = g.getItemId();
				dockPickedItemName = g.getName();
				break;
			default: break;
		}
		refreshDock();
	}

	/** Leave update mode and return to the goal's Selected view - the Back button
	 *  (no changes) and every successful save. Drops the edit-surface mount guard so
	 *  the summary re-renders off the freshly saved values. */
	private void closeEditGoalForm()
	{
		dockEditFormGoalId = null;
		dockEditFormMounted = false;
		dockCreateStep = CreateStep.PICKER;
		resetCreatePicks();
		dockEditMounted = false;
		refreshDock();
	}

	/** The period an update-mode form opens on: the goal's own when it repeats,
	 *  else the create default (Daily). */
	private static com.goalplanner.model.RepeatPeriod initialPeriod(Goal g)
	{
		return g != null && g.getRepeatEvery().isRepeating()
			? g.getRepeatEvery() : com.goalplanner.model.RepeatPeriod.DAILY;
	}

	/** Run an update-mode save as ONE undo entry: each setter still no-ops when the
	 *  value is unchanged, and an all-no-op compound records nothing at all
	 *  (CommandHistory drops an empty buffer). {@code apply} returns false when it
	 *  rejected the input (having warned) - the form then stays open, unsaved. */
	private void saveGoalEdit(java.util.function.BooleanSupplier apply)
	{
		boolean ok = false;
		api.beginCompound("Edit goal");
		try
		{
			ok = apply.getAsBoolean();
		}
		finally
		{
			api.endCompound();
		}
		if (ok)
		{
			closeEditGoalForm();
		}
	}

	/**
	 * Client-thread variant of {@link #saveGoalEdit}: a save whose mode conversion
	 * reads live client state (a repeatable re-bases off the live counter, Relative
	 * resolves live + N) must not run on the EDT - an EDT client read trips
	 * RuneLite's {@code -ea} client-thread assert and silently does nothing.
	 *
	 * <p>Input is validated on the EDT first; this runs the compound on the client
	 * thread and comes back to the EDT to close the form. {@code apply} returning
	 * false means the live read failed - the form stays open with {@code warning}
	 * shown, so a conversion is never a silent no-op. Callers short-circuit the
	 * "nothing actually changed" case themselves, so false always means failure.
	 */
	private void saveGoalEditOnClientThread(java.util.function.BooleanSupplier apply,
		String warning)
	{
		runOnClientThread(() ->
		{
			boolean ok = false;
			api.beginCompound("Edit goal");
			try
			{
				ok = apply.getAsBoolean();
			}
			finally
			{
				api.endCompound();
			}
			final boolean done = ok;
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				if (done)
				{
					closeEditGoalForm();
				}
				else
				{
					warnCreate(warning);
				}
			});
		});
	}

	/** Point an edited goal at an absolute target. A goal that is CURRENTLY a
	 *  per-period chunk is converted back to one-time first (clearing the period AND
	 *  the chunk, and letting reconcile return it to its home section);
	 *  {@code changeTarget} alone would leave it repeating with a hand-set target. */
	private void applyEditedTarget(String goalId, int target, boolean wasRepeating)
	{
		if (wasRepeating)
		{
			api.convertGoalToOneTime(goalId, target);
		}
		else
		{
			api.changeTarget(goalId, target);
		}
	}

	/** Update-mode repeat editor for the types whose CREATE form has no repeat
	 *  inputs (ITEM, CUSTOM) - and the shared editor for every type in update mode,
	 *  so a repeating goal can always retune its schedule. Renders a "Repeatable"
	 *  toggle (CUSTOM only: an auto-tracked slice's repeat is structural, it is
	 *  dropped with "Stop repeating"), the create form's own Daily/Weekly/Monthly
	 *  pills, and the per-period amount when the goal carries a chunk. Returns a
	 *  validate-then-apply supplier: false means the input was rejected (already
	 *  warned) and the save must abort. */
	private java.util.function.BooleanSupplier addUpdateRepeatBlock(JPanel body, Goal g,
		String amountLabel)
	{
		final String gid = g.getId();
		final boolean hasChunk = g.getRepeatChunk() > 0;
		final boolean toggleable = g.getType() == GoalType.CUSTOM;
		if (!toggleable && !hasChunk && !g.getRepeatEvery().isRepeating())
		{
			return () -> true;
		}

		final com.goalplanner.model.RepeatPeriod[] period = { initialPeriod(g) };
		final JCheckBox toggle = new JCheckBox("Repeatable");
		toggle.setOpaque(false);
		toggle.setForeground(CREATE_FG);
		toggle.setFont(toggle.getFont().deriveFont(11f));
		toggle.setAlignmentX(Component.LEFT_ALIGNMENT);
		toggle.setSelected(g.getRepeatEvery().isRepeating());
		toggle.setVisible(toggleable);

		final JTextField amount = new JTextField(8);
		styleField(amount);
		if (hasChunk)
		{
			amount.setText(Integer.toString(g.getRepeatChunk()));
		}

		JPanel detail = new JPanel();
		detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
		detail.setOpaque(false);
		detail.setAlignmentX(Component.LEFT_ALIGNMENT);
		detail.setVisible(!toggleable || toggle.isSelected());
		JLabel periodLbl = new JLabel("Repeat every");
		periodLbl.setForeground(CREATE_FG_DIM);
		periodLbl.setFont(periodLbl.getFont().deriveFont(10f));
		periodLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
		detail.add(periodLbl);
		detail.add(Box.createVerticalStrut(2));
		JComponent pills = buildPeriodPills(period);
		pills.setAlignmentX(Component.LEFT_ALIGNMENT);
		detail.add(pills);
		detail.add(Box.createVerticalStrut(6));
		if (hasChunk)
		{
			addFormRow(detail, amountLabel, amount);
		}

		toggle.addActionListener(e ->
		{
			detail.setVisible(toggle.isSelected());
			remeasureDock();
		});

		JLabel head = new JLabel("Repeat");
		head.setForeground(CREATE_FG_DIM);
		head.setFont(head.getFont().deriveFont(10f));
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		head.setVisible(toggleable);
		body.add(Box.createVerticalStrut(2));
		body.add(head);
		body.add(Box.createVerticalStrut(2));
		body.add(toggle);
		body.add(detail);
		body.add(Box.createVerticalStrut(6));

		return () ->
		{
			if (toggleable && !toggle.isSelected())
			{
				api.setGoalRepeat(gid, com.goalplanner.model.RepeatPeriod.NONE);
				return true;
			}
			int chunk = 0;
			if (hasChunk)
			{
				chunk = parsePositiveInt(amount.getText());
				if (chunk <= 0)
				{
					warnCreate("Enter how much to add each period.");
					return false;
				}
			}
			api.setGoalRepeat(gid, period[0]);
			if (chunk > 0)
			{
				api.setGoalRepeatChunk(gid, chunk);
			}
			return true;
		};
	}

	/** Select a section: the dock shows its actions. Mutually exclusive with the
	 *  goal selection, so any selected goals are cleared. Mirrors clicking a goal
	 *  card, but for a section header (the row body, not the chevron). */
	private void selectSection(String sectionId)
	{
		if (sectionId == null)
		{
			return;
		}
		selectedSectionId = sectionId;
		dockSectionGroup = null;
		// Enforce mutual exclusivity. A no-op when nothing is selected; when it
		// does clear a selection its callback funnels back through refreshDock,
		// which now resolves to the SECTION state (goals empty + section set).
		api.clearGoalSelection();
		refreshDock();
	}

	/** Clear the current section selection, resting the dock back to EMPTY. */
	private void clearSectionSelection()
	{
		selectedSectionId = null;
		dockSectionGroup = null;
		refreshDock();
	}

	/** Collapse the expanded surface from ANY state back to the resting footer.
	 *  Clears everything that drives the surface - the goal selection, the section
	 *  selection, and the create navigation - then collapses. The permanent footer
	 *  stays visible; refreshDock re-resolves to EMPTY + collapsed. Shared by
	 *  {@link #dismissDock()} (the grab-handle dismiss) and {@link #finishDockCreate()}
	 *  (the post-create collapse). */
	private void collapseDockToFooter()
	{
		selectedSectionId = null;
		dockSectionGroup = null;
		// A dismiss from update mode ("Edit goal") leaves it unsaved, like any other
		// overlay the grab handle drops.
		dockEditFormGoalId = null;
		dockEditFormMounted = false;
		dockCreateOpen = false;
		dockCreateNav = CreateNav.GRID;
		dockCreateType = null;
		dockPendingCreate = null;
		dockCreateStep = CreateStep.PICKER;
		dockCreateTargetSection = null;
		resetCreatePicks();
		actionDock.setExpanded(false);
		// Clearing the goal selection funnels back through refreshDock (which then
		// resolves EMPTY + collapsed); call it directly too so a no-selection
		// dismiss (create / section state) still re-renders to the resting footer.
		api.clearGoalSelection();
		refreshDock();
	}

	/** Task 8: dismiss the expanded surface from ANY state back to the resting
	 *  footer. */
	private void dismissDock()
	{
		collapseDockToFooter();
	}

	/** Task 1: after a successful dock create (ANY goal type, sync or async),
	 *  collapse to the resting footer instead of re-opening the new goal's EDIT view.
	 *  Every API create calls {@code selectAfterCreate}, which selects the new goal,
	 *  and a selected goal auto-expands its edit view in refreshDock - so without this
	 *  the pane stays open on the new goal after a create.
	 *
	 *  <p><b>Ordering guarantee.</b> For SYNC creates (skill one-time / item / account
	 *  / custom / CA) the create + selectAfterCreate run inline on the EDT, so calling
	 *  this synchronously right after the create clears that fresh selection before any
	 *  posted refresh runs - it stays collapsed. For ASYNC creates (diary / quest /
	 *  boss / standalone-repeatable) the create runs inside a client-thread compound;
	 *  selectAfterCreate's onSelectionChanged is suppressed while in the compound, and
	 *  the re-open instead rides the {@code onGoalsChanged} rebuild that endCompound
	 *  posts (a 200ms debounce that re-selects the new goal). Posting this via
	 *  {@code SwingUtilities.invokeLater} from INSIDE the client-thread runnable AFTER
	 *  endCompound queues it strictly after that onGoalsChanged post and well before
	 *  the debounced rebuild fires - so the selection is already cleared by the time
	 *  the rebuild runs, and the dock stays collapsed.
	 *
	 *  <p>The complete-on-add reveal is unaffected: {@code armCreateReveal} captured
	 *  {@code pendingRevealGoalId} from the selection before this clears it, and
	 *  {@link #maybeRevealPendingCreate()} scrolls the Completed card via
	 *  {@code scrollRectToVisible} keyed off that id, independent of the live
	 *  selection. */
	private void finishDockCreate()
	{
		collapseDockToFooter();
	}

	/** Task 1: open the in-dock create flow (type grid + stepper) with {@code sid}
	 *  as the fixed landing section. Clears the section selection so the dock
	 *  resolves to EMPTY (where the create surface lives), arms the target so
	 *  {@link #goToSectionPick} skips the chooser, and opens the grid. */
	private void openCreateFlowForSection(String sid)
	{
		dockCreateTargetSection = sid;
		selectedSectionId = null;
		dockSectionGroup = null;
		// Leaving any goal selection too keeps the state unambiguously EMPTY.
		api.clearGoalSelection();
		dockCreateOpen = true;
		dockCreateNav = CreateNav.GRID;
		dockCreateType = null;
		dockPendingCreate = null;
		dockCreateStep = CreateStep.PICKER;
		resetCreatePicks();
		refreshDock();
	}

	/** Drop the SECTION mount guard and refresh so an in-place section action
	 *  (drill-in group nav, a nesting/archive cycle) re-renders the surface off
	 *  fresh section data. Mirrors {@link #refreshEditForm}. */
	private void refreshSectionDock()
	{
		dockSectionMounted = false;
		refreshDock();
	}

	// --- Inline color picker overlay (inline-color pass) -----------------------
	// The Color actions used to open a Swing JOptionPane hosting ColorPickerField;
	// they now open an IN-DOCK swatch grid above the permanent footer. The overlay
	// is a transient nav target: the underlying goal / multi / section selection
	// stays put, and closing the overlay remounts that surface. Tapping a swatch
	// applies immediately (one undo) and closes.

	/** Open the color overlay for a single goal's edit form. */
	private void openColorSurfaceForGoal(String goalId)
	{
		dockColorReturn = ColorReturn.GOAL;
		dockColorTarget = goalId;
		dockColorMounted = false;
		refreshDock();
	}

	/** Open the color overlay for the current multi-selection (bulk recolor). */
	private void openColorSurfaceForMulti()
	{
		dockColorReturn = ColorReturn.MULTI;
		dockColorTarget = COLOR_TARGET_MULTI;
		dockColorMounted = false;
		refreshDock();
	}

	/** Open the color overlay for a section. */
	private void openColorSurfaceForSection(String sectionId)
	{
		dockColorReturn = ColorReturn.SECTION;
		dockColorTarget = sectionId;
		dockColorMounted = false;
		refreshDock();
	}

	/** Close the color overlay and return to the surface it belongs to WITHOUT
	 *  changing anything (also the path after a swatch applies). The overlay
	 *  replaced the expanded component, so force the edit / section surface to
	 *  remount; MULTI rebuilds its strips every refresh and needs no guard drop. */
	private void closeColorSurface()
	{
		dockColorReturn = null;
		dockColorTarget = null;
		dockColorMounted = false;
		dockEditMounted = false;
		dockSectionMounted = false;
		refreshDock();
	}

	/** Whether the color overlay's target still exists, so refreshDock can drop a
	 *  stale overlay (goal / section deleted, or the selection cleared) instead of
	 *  mounting an empty surface. */
	private boolean colorTargetValid()
	{
		if (dockColorReturn == null)
		{
			return false;
		}
		switch (dockColorReturn)
		{
			case GOAL:    return goalStore.findGoalById(dockColorTarget) != null;
			case SECTION: return findSectionView(dockColorTarget) != null;
			case MULTI:   return !api.getSelectedGoalIds().isEmpty();
			default:      return false;
		}
	}

	/** Build the in-dock color surface: a 4x3 grid of the curated
	 *  {@link ColorPickerField#PRESETS} rounded swatches plus a Default tile, headed
	 *  by a "Color" bar and a Back that returns without changing anything. The
	 *  currently-selected color is highlighted. Tapping a swatch (or Default) applies
	 *  immediately for the overlay's target and returns to the prior surface. */
	private JComponent buildColorSurface()
	{
		// Resolve the current selection + default per return type, and the apply sink.
		final int currentRgb;
		final int defaultRgb;
		final java.util.function.IntConsumer apply;
		switch (dockColorReturn)
		{
			case GOAL:
			{
				final String gid = dockColorTarget;
				Goal g = goalStore.findGoalById(gid);
				java.awt.Color c = g.getType().getColor();
				currentRgb = g.getCustomColorRgb();
				defaultRgb = (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
				apply = rgb -> { api.setGoalColor(gid, rgb); closeColorSurface(); };
				break;
			}
			case SECTION:
			{
				com.goalplanner.api.SectionView sv = findSectionView(dockColorTarget);
				final String sid = sv.id;
				currentRgb = sv.colorOverridden ? sv.colorRgb : -1;
				defaultRgb = sv.defaultColorRgb;
				apply = rgb -> { api.setSectionColor(sid, rgb); closeColorSurface(); };
				break;
			}
			case MULTI:
			default:
			{
				// A mixed selection has no single current color; default is neutral
				// (mirrors the old bulk dialog). Applies to EVERY selected goal as one
				// compound (one undo), reusing the bulk recolor path.
				currentRgb = -1;
				defaultRgb = 0x3C3C3C;
				apply = rgb -> {
					java.util.Set<String> ids =
						new java.util.LinkedHashSet<>(api.getSelectedGoalIds());
					api.beginCompound("Recolor " + ids.size() + " goals");
					try
					{
						for (String id : ids)
						{
							api.setGoalColor(id, rgb);
						}
					}
					finally
					{
						api.endCompound();
					}
					closeColorSurface();
				};
				break;
			}
		}

		JPanel inner = new JPanel(new BorderLayout(0, 6));
		inner.setOpaque(false);
		inner.setBorder(new EmptyBorder(6, 8, 8, 8));

		// Back returns to the prior surface WITHOUT changing anything.
		JPanel head = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 0));
		head.setOpaque(false);
		head.add(chip("< Back", "Back without changing the color",
			this::closeColorSurface));
		inner.add(head, BorderLayout.NORTH);

		// The 4x3 swatch grid + a full-width Default tile beneath it.
		JPanel body = new JPanel();
		body.setOpaque(false);
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

		JPanel grid = new JPanel(new GridLayout(3, 4, 6, 6));
		grid.setOpaque(false);
		grid.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		for (java.awt.Color preset : ColorPickerField.PRESETS)
		{
			int rgb = (preset.getRed() << 16) | (preset.getGreen() << 8) | preset.getBlue();
			grid.add(colorSwatch(preset, rgb == currentRgb, () -> apply.accept(rgb)));
		}
		grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
		body.add(grid);
		body.add(Box.createVerticalStrut(8));

		// Default resets to the goal/section's own default color (custom < 0).
		JButton def = chip("Default", "Reset to the default color",
			() -> apply.accept(-1));
		if (currentRgb < 0)
		{
			def.setBorder(RoundedPaint.border(CREATE_SEL_BORDER, 2,
				RoundedPaint.RADIUS, new Insets(4, 10, 4, 10)));
		}
		def.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		body.add(def);

		// Hex input: the inline custom-color path beyond the 12 presets (a full
		// color wheel stays a dialog concern, so it is intentionally not mirrored).
		// Accepts "#RRGGBB" or "RRGGBB"; the Set button or Enter commits through the
		// SAME apply sink the swatches use. Invalid input shows a brief inline hint
		// (no dialog) and is otherwise ignored.
		body.add(Box.createVerticalStrut(8));
		final JLabel hexHint = new JLabel(" ");
		hexHint.setForeground(CREATE_FG);
		hexHint.setFont(hexHint.getFont().deriveFont(10f));
		hexHint.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		JPanel hexRow = new JPanel(new BorderLayout(4, 0));
		hexRow.setOpaque(false);
		hexRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		JLabel hexLbl = new JLabel("Hex ");
		hexLbl.setForeground(CREATE_FG);
		hexLbl.setFont(hexLbl.getFont().deriveFont(11f));
		final JTextField hexField = new JTextField();
		styleField(hexField);
		hexField.setToolTipText("Custom color: #RRGGBB or RRGGBB");
		JButton hexBtn = flatButton("Set", true);
		hexBtn.setToolTipText("Apply this hex color");
		Runnable commitHex = () -> {
			int rgb = parseHexRgb(hexField.getText());
			if (rgb < 0)
			{
				hexHint.setText("Enter a hex color like #1F8B4C");
				hexHint.revalidate();
				hexHint.repaint();
				return;
			}
			apply.accept(rgb);
		};
		hexBtn.addActionListener(e -> commitHex.run());
		hexField.addActionListener(e -> commitHex.run());
		hexRow.add(hexLbl, BorderLayout.WEST);
		hexRow.add(hexField, BorderLayout.CENTER);
		hexRow.add(hexBtn, BorderLayout.EAST);
		hexRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, hexRow.getPreferredSize().height));
		body.add(hexRow);
		body.add(hexHint);

		inner.add(body, BorderLayout.CENTER);
		return surfaceShell("Color", false, inner);
	}

	/** Parse a "#RRGGBB" or "RRGGBB" hex string into a 0xRRGGBB int, or -1 if it is
	 *  not exactly six hex digits. -1 doubles as "invalid" here (a real color is
	 *  always >= 0), so callers guard on {@code < 0} and never mistake it for the
	 *  Default sentinel. Backs the inline custom-color path in {@link #buildColorSurface}. */
	private static int parseHexRgb(String raw)
	{
		if (raw == null)
		{
			return -1;
		}
		String s = raw.trim();
		if (s.startsWith("#"))
		{
			s = s.substring(1);
		}
		if (s.length() != 6)
		{
			return -1;
		}
		try
		{
			return Integer.parseInt(s, 16) & 0xFFFFFF;
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	/** A single rounded color swatch tile filled with {@code color}. Selected tiles
	 *  get a white 2px rounded outline; the rest a hairline. Tapping runs {@code apply}. */
	private JComponent colorSwatch(final java.awt.Color color, boolean selected, Runnable apply)
	{
		final RoundedPaint.RoundedPanel tile =
			new RoundedPaint.RoundedPanel(new BorderLayout(), RoundedPaint.RADIUS);
		tile.setBackground(color);
		tile.setPreferredSize(new Dimension(26, 26));
		tile.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		tile.setToolTipText(String.format("#%02X%02X%02X",
			color.getRed(), color.getGreen(), color.getBlue()));
		final java.awt.Color unselBorder = new java.awt.Color(80, 80, 80);
		tile.setBorder(RoundedPaint.border(selected ? CREATE_SEL_BORDER : unselBorder,
			selected ? 2 : 1, RoundedPaint.RADIUS, new Insets(2, 2, 2, 2)));
		tile.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				apply.run();
			}

			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				tile.setBorder(RoundedPaint.border(CREATE_SEL_BORDER, 2,
					RoundedPaint.RADIUS, new Insets(2, 2, 2, 2)));
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				tile.setBorder(RoundedPaint.border(selected ? CREATE_SEL_BORDER : unselBorder,
					selected ? 2 : 1, RoundedPaint.RADIUS, new Insets(2, 2, 2, 2)));
			}
		});
		return tile;
	}

	// ------------------------------------------------------------
	// Inline tag surfaces (inline-tag pass): the Add Tag / Remove Tag UX moved
	// into the dock, mirroring the inline color picker exactly. Openers set the
	// tag nav drivers and refresh; refreshDock mounts buildTagSurface above the
	// footer and returns early. Back / apply route through closeTagSurface, which
	// remounts the surface the overlay replaced. The right-click menus still open
	// the (intact, dead-in-dock) TagPickerDialog / MultiSelectDialog dialogs.
	// ------------------------------------------------------------

	/** Open the inline Add Tag surface for a single goal. */
	private void openTagAddSurfaceForGoal(String goalId)
	{
		dockTagMode = TagMode.ADD;
		dockTagReturn = TagReturn.GOAL;
		dockTagTarget = goalId;
		dockTagMounted = false;
		refreshDock();
	}

	/** Open the inline Remove Tag surface for a single goal. */
	private void openTagRemoveSurfaceForGoal(String goalId)
	{
		dockTagMode = TagMode.REMOVE;
		dockTagReturn = TagReturn.GOAL;
		dockTagTarget = goalId;
		dockTagMounted = false;
		refreshDock();
	}

	/** Open the inline Add Tag surface for the current multi-selection. */
	private void openTagAddSurfaceForMulti()
	{
		dockTagMode = TagMode.ADD;
		dockTagReturn = TagReturn.MULTI;
		dockTagTarget = TAG_TARGET_MULTI;
		dockTagMounted = false;
		refreshDock();
	}

	/** Open the inline Remove Tag surface for the current multi-selection. */
	private void openTagRemoveSurfaceForMulti()
	{
		dockTagMode = TagMode.REMOVE;
		dockTagReturn = TagReturn.MULTI;
		dockTagTarget = TAG_TARGET_MULTI;
		dockTagMounted = false;
		refreshDock();
	}

	/** Close the tag overlay and return to the surface it belongs to WITHOUT any
	 *  further change (also the path after a chip applies). Force the edit / section
	 *  surface to remount; MULTI rebuilds its strips every refresh. Mirrors
	 *  {@link #closeColorSurface}. */
	private void closeTagSurface()
	{
		dockTagMode = null;
		dockTagReturn = null;
		dockTagTarget = null;
		dockTagMounted = false;
		dockEditMounted = false;
		dockSectionMounted = false;
		refreshDock();
	}

	/** Rebuild the mounted tag surface in place (after a removal) so several tags
	 *  can be dropped without leaving the overlay. Keeps {@link #dockTagMode} set. */
	private void remountTagSurface()
	{
		dockTagMounted = false;
		refreshDock();
	}

	/** Whether the tag overlay's target still exists, so refreshDock can drop a
	 *  stale overlay (goal deleted, or the selection cleared) instead of mounting an
	 *  empty surface. Mirrors {@link #colorTargetValid}. */
	private boolean tagTargetValid()
	{
		if (dockTagReturn == null)
		{
			return false;
		}
		switch (dockTagReturn)
		{
			case GOAL:  return goalStore.findGoalById(dockTagTarget) != null;
			case MULTI: return !api.getSelectedGoalIds().isEmpty();
			default:    return false;
		}
	}

	/** Dispatch to the active inline tag surface (ADD or REMOVE). */
	private JComponent buildTagSurface()
	{
		return dockTagMode == TagMode.ADD ? buildTagAddSurface() : buildTagRemoveSurface();
	}

	/** Display name for a {@link com.goalplanner.model.TagCategory} enum name, or
	 *  the raw name if it does not resolve. */
	private static String tagCategoryDisplay(String categoryName)
	{
		if (categoryName == null)
		{
			return null;
		}
		try
		{
			return com.goalplanner.model.TagCategory.valueOf(categoryName).getDisplayName();
		}
		catch (IllegalArgumentException e)
		{
			return categoryName;
		}
	}

	/** Build the inline Add Tag surface (GOAL or MULTI): every existing tag from
	 *  {@code api.queryAllTags()} rendered as a rounded tappable chip that adds it and
	 *  returns, plus a "New tag" text field + Add button that creates-and-adds a
	 *  brand-new label. Tapping an existing chip preserves its category
	 *  ({@code addTagWithCategory}); a brand-new label goes to the OTHER (user/custom)
	 *  category via {@code api.addTag}, since this compact surface has no category
	 *  picker (the dialog's category dropdown is intentionally not mirrored). For
	 *  MULTI every add applies to the whole selection as one compound (one undo). */
	private JComponent buildTagAddSurface()
	{
		// (label, categoryName) -> add an existing tag preserving its category.
		final java.util.function.BiConsumer<String, String> addExisting;
		// label -> create-and-add a brand-new tag (OTHER / user-custom category).
		final java.util.function.Consumer<String> addNew;
		if (dockTagReturn == TagReturn.MULTI)
		{
			final java.util.Set<String> ids =
				new java.util.LinkedHashSet<>(api.getSelectedGoalIds());
			final int n = ids.size();
			addExisting = (label, cat) -> {
				api.beginCompound("Add tag '" + label + "' to " + n + " goals");
				try
				{
					for (String id : ids)
					{
						api.addTagWithCategory(id, label, cat);
					}
				}
				finally
				{
					api.endCompound();
				}
				closeTagSurface();
			};
			addNew = label -> {
				api.beginCompound("Add tag '" + label + "' to " + n + " goals");
				try
				{
					for (String id : ids)
					{
						api.addTag(id, label);
					}
				}
				finally
				{
					api.endCompound();
				}
				closeTagSurface();
			};
		}
		else
		{
			final String gid = dockTagTarget;
			addExisting = (label, cat) -> { api.addTagWithCategory(gid, label, cat); closeTagSurface(); };
			addNew = label -> { api.addTag(gid, label); closeTagSurface(); };
		}

		JPanel inner = new JPanel(new BorderLayout(0, 6));
		inner.setOpaque(false);
		inner.setBorder(new EmptyBorder(6, 8, 8, 8));

		// Back returns to the prior surface WITHOUT adding anything.
		JPanel head = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 0));
		head.setOpaque(false);
		head.add(chip("< Back", "Back without adding a tag", this::closeTagSurface));
		inner.add(head, BorderLayout.NORTH);

		JPanel body = new JPanel();
		body.setOpaque(false);
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

		// Existing tags as rounded tappable chips; tapping adds and returns.
		JPanel chips = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
		chips.setOpaque(false);
		chips.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		java.util.List<com.goalplanner.api.TagView> all = api.queryAllTags();
		for (com.goalplanner.api.TagView t : all)
		{
			final String label = t.label;
			final String cat = t.category;
			String catName = tagCategoryDisplay(cat);
			String tip = catName != null ? "Add " + label + " (" + catName + ")" : "Add " + label;
			chips.add(chip(label, tip, () -> addExisting.accept(label, cat)));
		}
		if (all.isEmpty())
		{
			JLabel none = new JLabel("No existing tags yet");
			none.setForeground(CREATE_FG);
			none.setFont(none.getFont().deriveFont(11f));
			chips.add(none);
		}
		chips.setMaximumSize(new Dimension(Integer.MAX_VALUE, chips.getPreferredSize().height));
		body.add(chips);
		body.add(Box.createVerticalStrut(8));

		// New-tag inline field + Add button (create-and-add a brand-new label).
		JPanel newRow = new JPanel(new BorderLayout(4, 0));
		newRow.setOpaque(false);
		newRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		final JTextField field = new JTextField();
		styleField(field);
		field.setToolTipText("Type a new tag label");
		JButton addBtn = flatButton("Add", true);
		addBtn.setToolTipText("Create and add this tag");
		Runnable commit = () -> {
			String text = field.getText() == null ? "" : field.getText().trim();
			if (!text.isEmpty())
			{
				addNew.accept(text);
			}
		};
		addBtn.addActionListener(e -> commit.run());
		field.addActionListener(e -> commit.run());
		newRow.add(field, BorderLayout.CENTER);
		newRow.add(addBtn, BorderLayout.EAST);
		newRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, newRow.getPreferredSize().height));
		body.add(newRow);

		inner.add(body, BorderLayout.CENTER);
		return surfaceShell("Add Tag", false, inner);
	}

	/** Build the inline Remove Tag surface (GOAL or MULTI): the removable tags as
	 *  rounded tappable chips. Tapping one removes it immediately and re-renders the
	 *  surface (so several can be dropped without leaving); Back returns. For a goal
	 *  the chips are {@link #removableTagsFor}; for MULTI they are the merged
	 *  removable set from {@code api.getRemovableTagsForSelection} (label + count),
	 *  each removed across the selection as one compound. */
	private JComponent buildTagRemoveSurface()
	{
		JPanel inner = new JPanel(new BorderLayout(0, 6));
		inner.setOpaque(false);
		inner.setBorder(new EmptyBorder(6, 8, 8, 8));

		// Back returns to the prior surface WITHOUT changing anything further.
		JPanel head = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 0));
		head.setOpaque(false);
		head.add(chip("< Back", "Back without removing more tags", this::closeTagSurface));
		inner.add(head, BorderLayout.NORTH);

		JPanel chips = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
		chips.setOpaque(false);

		boolean any = false;
		if (dockTagReturn == TagReturn.MULTI)
		{
			final java.util.Set<String> ids =
				new java.util.LinkedHashSet<>(api.getSelectedGoalIds());
			java.util.List<com.goalplanner.api.GoalPlannerInternalApi.TagRemovalOption> opts =
				api.getRemovableTagsForSelection(ids);
			for (com.goalplanner.api.GoalPlannerInternalApi.TagRemovalOption o : opts)
			{
				any = true;
				final String tagId = o.tagId;
				final String label = o.label;
				final int count = o.count;
				chips.add(chip(label, "Remove " + label + " (" + count + ") from the selection", () -> {
					api.beginCompound("Remove tag '" + label + "' from selection");
					try
					{
						api.bulkRemoveTagFromGoals(ids, tagId);
					}
					finally
					{
						api.endCompound();
					}
					remountTagSurface();
				}));
			}
		}
		else
		{
			final String gid = dockTagTarget;
			Goal g = goalStore.findGoalById(gid);
			java.util.List<Tag> removable = g != null
				? removableTagsFor(g) : java.util.Collections.emptyList();
			for (Tag t : removable)
			{
				any = true;
				final String label = t.getLabel();
				chips.add(chip(label,
					"Remove " + label + " (" + t.getCategory().getDisplayName() + ")",
					() -> { api.removeTag(gid, label); remountTagSurface(); }));
			}
		}
		if (!any)
		{
			JLabel none = new JLabel("No tags to remove");
			none.setForeground(CREATE_FG);
			none.setFont(none.getFont().deriveFont(11f));
			chips.add(none);
		}
		inner.add(chips, BorderLayout.CENTER);
		return surfaceShell("Remove Tag", false, inner);
	}

	// ------------------------------------------------------------
	// Inline Share surface (share-inline pass): the copy/save share flows moved
	// into the dock, mirroring the inline color / tag surfaces exactly. Openers
	// capture the target (goal ids / section id) and refresh; refreshDock mounts
	// buildShareSurface above the footer and returns early. Back routes through
	// closeShareSurface, which remounts whichever surface it was opened from. The
	// old ShareDialogs copy/save dialogs stay intact but are no longer reached
	// from the dock.
	// ------------------------------------------------------------

	/** Open the Share surface for a captured goal-id list (single-goal edit share
	 *  or the multi selection). The list is copied so a later selection change does
	 *  not mutate it. */
	private void openShareForGoals(java.util.List<String> goalIds)
	{
		if (!isShareAvailable())
		{
			return;
		}
		dockShareScope = ShareScope.GOALS;
		dockShareGoalIds = new java.util.ArrayList<>(goalIds);
		dockShareSectionId = null;
		dockShareMounted = false;
		refreshDock();
	}

	/** Open the Share surface for a single section. */
	private void openShareForSection(String sectionId)
	{
		if (!isShareAvailable())
		{
			return;
		}
		dockShareScope = ShareScope.SECTION;
		dockShareGoalIds = null;
		dockShareSectionId = sectionId;
		dockShareMounted = false;
		refreshDock();
	}

	/** Open the Share surface for every user section (one v2 multi-section code). */
	private void openShareForAllSections()
	{
		if (!isShareAvailable())
		{
			return;
		}
		dockShareScope = ShareScope.ALL;
		dockShareGoalIds = null;
		dockShareSectionId = null;
		dockShareMounted = false;
		refreshDock();
	}

	/** Close the Share overlay and return to the surface it was opened from WITHOUT
	 *  changing anything. Like {@link #closeColorSurface} it forces the edit /
	 *  section surfaces to remount (they were replaced by the overlay); MULTI
	 *  rebuilds its strips every refresh and needs no guard drop. */
	private void closeShareSurface()
	{
		dockShareScope = null;
		dockShareGoalIds = null;
		dockShareSectionId = null;
		dockShareMounted = false;
		dockEditMounted = false;
		dockSectionMounted = false;
		refreshDock();
	}

	/** Whether the Share overlay's captured target still has something to encode, so
	 *  refreshDock can drop a stale overlay instead of mounting an empty surface. */
	private boolean shareTargetValid()
	{
		if (dockShareScope == null)
		{
			return false;
		}
		switch (dockShareScope)
		{
			case GOALS:
				if (dockShareGoalIds == null)
				{
					return false;
				}
				for (String id : dockShareGoalIds)
				{
					if (goalStore.findGoalById(id) != null)
					{
						return true;
					}
				}
				return false;
			case SECTION: return findSectionView(dockShareSectionId) != null;
			case ALL:     return true;
			default:      return false;
		}
	}

	/** The player name for a share export, mirroring ShareDialogs' safeName: the
	 *  supplier's value, or "Someone" when it is empty / absent / throws. */
	private String sharePlayerName()
	{
		try
		{
			String n = playerNameSupplier != null ? playerNameSupplier.get() : null;
			return n != null && !n.isEmpty() ? n : "Someone";
		}
		catch (RuntimeException e)
		{
			return "Someone";
		}
	}

	/** Build the in-dock Share surface for the captured target: a read-only,
	 *  selectable, wrapping, scrollable code area (the paste-anywhere invite line the
	 *  copy dialogs produce), a Copy button (clipboard + inline confirm), and - when
	 *  the Saved Plans library is wired - a name field + Save button that banks the
	 *  raw canonical code via the same {@code savedPlanStore.add} path the save
	 *  dialogs use. Back returns without changing anything. */
	private JComponent buildShareSurface()
	{
		JPanel inner = new JPanel(new BorderLayout(0, 6));
		inner.setOpaque(false);
		inner.setBorder(new EmptyBorder(6, 8, 8, 8));

		// Back returns to the prior surface WITHOUT changing anything.
		JPanel head = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 0));
		head.setOpaque(false);
		head.add(chip("< Back", "Back without sharing", this::closeShareSurface));
		inner.add(head, BorderLayout.NORTH);

		// Resolve the bundle for the captured target.
		com.goalplanner.share.ShareBundle bundle;
		switch (dockShareScope)
		{
			case SECTION:
				bundle = api.exportSectionBundle(dockShareSectionId, sharePlayerName());
				break;
			case ALL:
				bundle = api.exportAllSectionsBundle(sharePlayerName());
				break;
			case GOALS:
			default:
				bundle = api.exportGoalsBundle(dockShareGoalIds, sharePlayerName());
				break;
		}

		JPanel body = new JPanel();
		body.setOpaque(false);
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

		if (bundle == null || bundle.totalGoalCount() == 0)
		{
			JLabel none = new JLabel("Nothing to share here.");
			none.setForeground(CREATE_FG);
			none.setFont(none.getFont().deriveFont(11f));
			none.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			body.add(none);
			inner.add(body, BorderLayout.CENTER);
			return surfaceShell("Share", false, inner);
		}

		final String code = shareCodec.encode(bundle);
		final String invite = com.goalplanner.share.ShareText.invite(bundle, code);

		// Read-only, selectable, wrapping code area (codes are long gzip+base64).
		final JTextArea codeArea = new JTextArea(invite, 4, 20);
		codeArea.setEditable(false);
		codeArea.setLineWrap(true);
		codeArea.setWrapStyleWord(false);
		codeArea.setFont(codeArea.getFont().deriveFont(11f));
		codeArea.setForeground(CREATE_FG);
		codeArea.setBackground(CREATE_FIELD_BG);
		codeArea.setCaretPosition(0);
		JScrollPane codeScroll = new JScrollPane(codeArea);
		codeScroll.setBorder(RoundedPaint.border(CREATE_FIELD_STROKE, 1,
			RoundedPaint.RADIUS, new java.awt.Insets(2, 2, 2, 2)));
		codeScroll.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		codeScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 84));
		body.add(codeScroll);
		body.add(Box.createVerticalStrut(6));

		// Copy the invite line to the system clipboard, with a brief inline confirm.
		JButton copyBtn = flatButton("Copy", true);
		copyBtn.setToolTipText("Copy the share code to your clipboard");
		copyBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		copyBtn.addActionListener(e -> {
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new java.awt.datatransfer.StringSelection(invite), null);
			showInfoNotice("Copied share code to your clipboard.");
		});
		body.add(copyBtn);

		// Save the raw canonical code into the Saved Plans library (when wired).
		if (isSavedPlansAvailable())
		{
			body.add(Box.createVerticalStrut(8));
			JLabel saveLbl = new JLabel("Save to your plans");
			saveLbl.setForeground(CREATE_FG);
			saveLbl.setFont(saveLbl.getFont().deriveFont(11f));
			saveLbl.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			body.add(saveLbl);
			body.add(Box.createVerticalStrut(2));

			JPanel saveRow = new JPanel(new BorderLayout(4, 0));
			saveRow.setOpaque(false);
			saveRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			final JTextField nameField = new JTextField(defaultSharePlanName(bundle));
			styleField(nameField);
			nameField.setToolTipText("Name this saved plan");
			JButton saveBtn = flatButton("Save", false);
			saveBtn.setToolTipText("Save this share code to your plans");
			Runnable commitSave = () -> {
				String name = nameField.getText() == null ? "" : nameField.getText().trim();
				if (name.isEmpty())
				{
					name = defaultSharePlanName(bundle);
				}
				savedPlanStore.add(name, code,
					com.goalplanner.share.SavedPlanSections.sectionNamesOf(bundle));
				showInfoNotice("Saved \"" + name + "\" to your plans.");
				closeShareSurface();
			};
			saveBtn.addActionListener(e -> commitSave.run());
			nameField.addActionListener(e -> commitSave.run());
			saveRow.add(nameField, BorderLayout.CENTER);
			saveRow.add(saveBtn, BorderLayout.EAST);
			saveRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, saveRow.getPreferredSize().height));
			body.add(saveRow);
		}

		inner.add(body, BorderLayout.CENTER);
		return surfaceShell("Share", false, inner);
	}

	/** The default saved-plan name for a bundle (mirrors ShareDialogs.defaultPlanName):
	 *  the first non-empty section name, else "Shared plan". */
	private static String defaultSharePlanName(com.goalplanner.share.ShareBundle bundle)
	{
		for (com.goalplanner.share.SectionShareDto s : bundle.effectiveSections())
		{
			if (s.getName() != null && !s.getName().trim().isEmpty())
			{
				return s.getName().trim();
			}
		}
		return "Shared plan";
	}

	// ------------------------------------------------------------
	// Inline Import + Saved Plans surfaces (import-inline / saved-goals-inline
	// pass): reached from the Import / Saved goals buttons under the create tiles
	// (and still from the header Options popup). Both mount above the footer via
	// the overlay pattern and return to the create grid on Back. Import reuses
	// ShareDialogs.doImport for the per-character re-import warning + the success
	// confirmation (the paste itself is inline). Saved goals is a genuine inline
	// list (name + Load + Delete); the heavier Edit / Copy / section-name-override
	// management still lives in the intact SavedPlansDialog.
	// ------------------------------------------------------------

	/** Open the inline Import surface (paste a share code). */
	private void openImportSurface()
	{
		if (!isShareAvailable())
		{
			return;
		}
		dockImportActive = true;
		dockImportMounted = false;
		refreshDock();
	}

	/** Close the Import surface and return to the create grid. */
	private void closeImportSurface()
	{
		dockImportActive = false;
		dockImportMounted = false;
		dockCreateMounted = false;
		refreshDock();
	}

	/** Open the inline Saved Plans surface (Load / Delete). */
	private void openSavedPlansSurface()
	{
		if (!isSavedPlansAvailable())
		{
			return;
		}
		dockSavedActive = true;
		dockSavedMounted = false;
		refreshDock();
	}

	/** Close the Saved Plans surface and return to the create grid. */
	private void closeSavedPlansSurface()
	{
		dockSavedActive = false;
		dockSavedMounted = false;
		dockCreateMounted = false;
		refreshDock();
	}

	/** Re-render the Saved Plans surface in place (after a Delete) without leaving. */
	private void remountSavedPlansSurface()
	{
		dockSavedMounted = false;
		refreshDock();
	}

	/** Build the inline Import surface: a wrapping, scrollable paste area + an
	 *  Import button. Blank / undecodable input shows a brief inline hint (no
	 *  dialog); a valid code is handed to {@link ShareDialogs#doImport} so the
	 *  per-character re-import warning and the "imported N goals" confirmation are
	 *  preserved exactly. On completion the surface returns to the create grid. */
	private JComponent buildImportSurface()
	{
		JPanel inner = new JPanel(new BorderLayout(0, 6));
		inner.setOpaque(false);
		inner.setBorder(new EmptyBorder(6, 8, 8, 8));

		JPanel head = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 0));
		head.setOpaque(false);
		head.add(chip("< Back", "Back without importing", this::closeImportSurface));
		inner.add(head, BorderLayout.NORTH);

		JPanel body = new JPanel();
		body.setOpaque(false);
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

		JLabel prompt = new JLabel("Paste a share code");
		prompt.setForeground(CREATE_FG);
		prompt.setFont(prompt.getFont().deriveFont(11f));
		prompt.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		body.add(prompt);
		body.add(Box.createVerticalStrut(2));

		final JTextArea codeArea = new JTextArea(4, 20);
		codeArea.setLineWrap(true);
		codeArea.setWrapStyleWord(false);
		codeArea.setFont(codeArea.getFont().deriveFont(11f));
		codeArea.setForeground(CREATE_FG);
		codeArea.setBackground(CREATE_FIELD_BG);
		JScrollPane codeScroll = new JScrollPane(codeArea);
		codeScroll.setBorder(RoundedPaint.border(CREATE_FIELD_STROKE, 1,
			RoundedPaint.RADIUS, new java.awt.Insets(2, 2, 2, 2)));
		codeScroll.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		codeScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 84));
		body.add(codeScroll);
		body.add(Box.createVerticalStrut(4));

		final JLabel hint = new JLabel(" ");
		hint.setForeground(CREATE_FG);
		hint.setFont(hint.getFont().deriveFont(10f));
		hint.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		JButton importBtn = flatButton("Import", true);
		importBtn.setToolTipText("Import the pasted share code");
		importBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		importBtn.addActionListener(e -> {
			String text = codeArea.getText();
			if (text == null || text.trim().isEmpty())
			{
				hint.setText("Paste a share code first.");
				return;
			}
			com.goalplanner.share.ShareBundle bundle;
			try
			{
				bundle = shareCodec.decode(text);
			}
			catch (com.goalplanner.share.ShareFormatException ex)
			{
				hint.setText("That does not look like a valid share code.");
				return;
			}
			// Clear the overlay BEFORE doImport so its onDone rebuild lands on the
			// create grid, not this surface; then a final refresh settles the dock
			// whether or not the re-import warning was accepted.
			dockImportActive = false;
			dockImportMounted = false;
			dockCreateMounted = false;
			ShareDialogs.doImport(GoalPanel.this, api, bundle, shareCodec.encode(bundle), this::rebuild);
			refreshDock();
		});
		body.add(hint);
		body.add(importBtn);

		inner.add(body, BorderLayout.CENTER);
		return surfaceShell("Import", false, inner);
	}

	/** Build the inline Saved Plans surface: each saved plan as a row (name +
	 *  decoded preview) with Load (import) and Delete actions. Empty shows a hint.
	 *  Load reuses {@link ShareDialogs#doImport} (re-import warning + confirmation);
	 *  Delete removes and re-renders in place. */
	private JComponent buildSavedPlansSurface()
	{
		JPanel inner = new JPanel(new BorderLayout(0, 6));
		inner.setOpaque(false);
		inner.setBorder(new EmptyBorder(6, 8, 8, 8));

		JPanel head = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 0));
		head.setOpaque(false);
		head.add(chip("< Back", "Back to create", this::closeSavedPlansSurface));
		inner.add(head, BorderLayout.NORTH);

		JPanel body = new JPanel();
		body.setOpaque(false);
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

		java.util.List<com.goalplanner.persistence.SavedPlan> plans = savedPlanStore.getPlans();
		if (plans.isEmpty())
		{
			JLabel none = new JLabel("No saved goals yet.");
			none.setForeground(CREATE_FG);
			none.setFont(none.getFont().deriveFont(11f));
			none.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			body.add(none);
		}
		else
		{
			for (com.goalplanner.persistence.SavedPlan plan : plans)
			{
				body.add(buildSavedPlanRow(plan));
				body.add(Box.createVerticalStrut(4));
			}
		}

		inner.add(body, BorderLayout.CENTER);
		return surfaceShell("Saved goals", false, inner);
	}

	/** One saved-plan row for {@link #buildSavedPlansSurface}: name + preview on the
	 *  left, Load / Delete chips on the right. */
	private JComponent buildSavedPlanRow(com.goalplanner.persistence.SavedPlan plan)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		JPanel text = new JPanel();
		text.setOpaque(false);
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		JLabel name = new JLabel(plan.getName() == null ? "(unnamed)" : plan.getName());
		name.setForeground(CREATE_FG);
		name.setFont(name.getFont().deriveFont(Font.BOLD, 11f));
		JLabel sub = new JLabel(savedPlanPreview(plan));
		sub.setForeground(CREATE_FG_DIM);
		sub.setFont(sub.getFont().deriveFont(10f));
		text.add(name);
		text.add(sub);
		row.add(text, BorderLayout.CENTER);

		JPanel actions = new JPanel(new WrapLayout(FlowLayout.RIGHT, 4, 0));
		actions.setOpaque(false);
		actions.add(chip("Load", "Import this saved plan", () -> loadSavedPlan(plan)));
		actions.add(chip("Delete", "Delete this saved plan", () -> {
			savedPlanStore.remove(plan.getId());
			remountSavedPlansSurface();
		}));
		row.add(actions, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** "3 goals - 2 sections", or "unreadable code" (mirrors SavedPlansDialog.preview). */
	private String savedPlanPreview(com.goalplanner.persistence.SavedPlan plan)
	{
		try
		{
			com.goalplanner.share.ShareBundle b = shareCodec.decode(plan.getCode());
			int n = b.totalGoalCount();
			int secs = b.effectiveSections().size();
			String s = n + (n == 1 ? " goal" : " goals");
			return secs > 1 ? s + " - " + secs + " sections" : s;
		}
		catch (RuntimeException e)
		{
			return "unreadable code";
		}
	}

	/** Import a saved plan (mirrors SavedPlansDialog.importPlan): decode, apply its
	 *  saved section-name overrides, then hand to {@link ShareDialogs#doImport}. An
	 *  unreadable code shows a brief inline notice instead. */
	private void loadSavedPlan(com.goalplanner.persistence.SavedPlan plan)
	{
		com.goalplanner.share.ShareBundle bundle;
		try
		{
			bundle = shareCodec.decode(plan.getCode());
		}
		catch (RuntimeException e)
		{
			showInfoNotice("That saved plan could not be read.");
			return;
		}
		com.goalplanner.share.SavedPlanSections.applySectionNames(bundle, plan.getSectionNames());
		// Clear the overlay first so doImport's rebuild lands on the grid.
		dockSavedActive = false;
		dockSavedMounted = false;
		dockCreateMounted = false;
		ShareDialogs.doImport(GoalPanel.this, api, bundle, shareCodec.encode(bundle), this::rebuild);
		refreshDock();
	}

	/** The current {@link com.goalplanner.api.SectionView} for {@code id}, or null
	 *  if no such section exists (e.g. it was deleted while selected). */
	private com.goalplanner.api.SectionView findSectionView(String id)
	{
		if (id == null)
		{
			return null;
		}
		for (com.goalplanner.api.SectionView sv : api.queryAllSections())
		{
			if (sv.id.equals(id))
			{
				return sv;
			}
		}
		return null;
	}

	/** Number of goals currently in the section (mirrors the menu's count). */
	private int countGoalsInSection(String sectionId)
	{
		int n = 0;
		for (Goal g : goalStore.getGoals())
		{
			if (sectionId.equals(g.getSectionId()))
			{
				n++;
			}
		}
		return n;
	}

	/** Advance a tri-state section override: Default (null) -> On (TRUE) ->
	 *  Off (FALSE) -> Default. Used by the nesting and archive cycle chips. */
	private static Boolean cycleOverride(Boolean current)
	{
		if (current == null)
		{
			return Boolean.TRUE;
		}
		if (Boolean.TRUE.equals(current))
		{
			return Boolean.FALSE;
		}
		return null;
	}

	/** Run {@code commit} when the user finishes a text field: Enter, or blur. */
	private void commitOnBlurOrEnter(JTextField f, Runnable commit)
	{
		f.addActionListener(e -> commit.run());
		f.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override public void focusLost(java.awt.event.FocusEvent e) { commit.run(); }
		});
	}

	private static net.runelite.api.Skill skillOf(Goal g)
	{
		if (g.getSkillName() == null) return null;
		try { return net.runelite.api.Skill.valueOf(g.getSkillName()); }
		catch (IllegalArgumentException e) { return null; }
	}

	// ----- per-type edit bodies -----
	//
	// The Selected view is READ-ONLY (user feedback): a goal's parameters render as
	// INFORMATION, not as live form fields. Changing them goes through the "Edit
	// goal" button, which opens the create-style form pre-filled in update mode
	// (see openEditGoalForm). Each body below is therefore a compact summary built
	// from the shared summary helpers; QUEST/DIARY/CA keep their thin body.

	/** A read-only "Label: value" line for the Selected view's parameter summary.
	 *  The label reuses the muted {@link #addFormRow} caption styling so the summary
	 *  reads as information rather than as a form. */
	private void addSummaryRow(JPanel body, String label, String value)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel l = new JLabel(label + ":");
		l.setForeground(CREATE_FG_DIM);
		l.setFont(l.getFont().deriveFont(10f));
		JLabel v = new JLabel(value);
		v.setForeground(CREATE_FG);
		v.setFont(v.getFont().deriveFont(11f));
		row.add(l);
		row.add(v);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		body.add(row);
		body.add(Box.createVerticalStrut(2));
	}

	/** A wrapped read-only text block (name / description) under a muted caption. */
	private void addSummaryText(JPanel body, String label, String text)
	{
		JLabel v = new JLabel("<html>" + escapeHtml(text) + "</html>");
		v.setForeground(CREATE_FG);
		v.setFont(v.getFont().deriveFont(11f));
		addFormRow(body, label, v);
	}

	/** "level 99 (13,034,431 XP)" when the target lands exactly on a level, else
	 *  "1,500,000 XP (level 74)". */
	private static String skillTargetText(int xp)
	{
		int safe = Math.max(0, xp);
		int level = net.runelite.api.Experience.getLevelForXp(safe);
		String xpTxt = com.goalplanner.util.FormatUtil.formatXp(safe) + " XP";
		if (level >= 1 && level <= 126
			&& net.runelite.api.Experience.getXpForLevel(level) == safe)
		{
			return "level " + level + " (" + xpTxt + ")";
		}
		return xpTxt + " (level " + level + ")";
	}

	/** Read-only repeat summary: the period and, for a derived per-period slice,
	 *  how much it re-bases by each period. Renders nothing for a goal that neither
	 *  repeats nor carries a chunk. */
	private void addRepeatSummary(JPanel body, Goal g, String amountLabel)
	{
		boolean repeating = g.getRepeatEvery().isRepeating();
		int chunk = g.getRepeatChunk();
		if (!repeating && chunk <= 0)
		{
			return;
		}
		if (repeating)
		{
			addSummaryRow(body, "Repeats", g.getRepeatEvery().getLabel());
		}
		if (chunk > 0)
		{
			addSummaryRow(body, amountLabel,
				com.goalplanner.util.FormatUtil.formatXp(chunk));
		}
	}

	private JComponent buildSkillEditBody(Goal g)
	{
		final boolean derived = g.getRepeatChunk() > 0;
		JPanel body = formBody();

		// Skill is read-only in edit: there is no API to change a goal's skill,
		// and changing it would be a different goal. Show it as an icon + name.
		net.runelite.api.Skill skill = skillOf(g);
		if (skill != null)
		{
			JPanel skillRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
			skillRow.setOpaque(false);
			skillRow.add(new JLabel(new ImageIcon(skillIconManager.getSkillImage(skill, true))));
			JLabel name = new JLabel(skill.getName());
			name.setForeground(CREATE_FG);
			name.setFont(name.getFont().deriveFont(12f));
			skillRow.add(name);
			addFormRow(body, "Skill", skillRow);
		}

		// A derived per-period slice's target re-bases each period off its chunk, so
		// its meaningful amount is the chunk (shown by the repeat summary), not the
		// raw target. A plain grind shows its absolute target.
		if (!derived)
		{
			addSummaryRow(body, "Target", skillTargetText(g.getTargetValue()));
		}

		addRepeatSummary(body, g, "XP each period");
		return body;
	}

	private JComponent buildBossEditBody(Goal g)
	{
		final boolean derived = g.getRepeatChunk() > 0;
		JPanel body = formBody();

		if (g.getBossName() != null)
		{
			JLabel boss = new JLabel(g.getBossName());
			boss.setForeground(CREATE_FG);
			boss.setFont(boss.getFont().deriveFont(12f));
			addFormRow(body, "Boss", boss);
		}

		if (!derived)
		{
			addSummaryRow(body, "Target",
				com.goalplanner.util.FormatUtil.formatXp(g.getTargetValue()) + " kills");
		}

		addRepeatSummary(body, g, "Kills each period");
		return body;
	}

	private JComponent buildItemEditBody(Goal g)
	{
		final boolean derived = g.getRepeatChunk() > 0;
		JPanel body = formBody();

		if (!derived)
		{
			addSummaryRow(body, "Target",
				com.goalplanner.util.FormatUtil.formatXp(g.getTargetValue()));
		}

		addRepeatSummary(body, g, "Amount each period");
		return body;
	}

	private JComponent buildCustomEditBody(Goal g)
	{
		JPanel body = formBody();

		addSummaryText(body, "Name", g.getName() != null ? g.getName() : "");
		if (g.getDescription() != null && !g.getDescription().trim().isEmpty())
		{
			addSummaryText(body, "Description", g.getDescription());
		}

		addRepeatSummary(body, g, "Amount each period");
		return body;
	}

	private JComponent buildAccountEditBody(Goal g)
	{
		JPanel body = formBody();

		// The metric is fixed for an existing account goal (no API changes it).
		String metricLabel = g.getName() != null ? g.getName() : "Account metric";
		JLabel metric = new JLabel(metricLabel);
		metric.setForeground(CREATE_FG);
		metric.setFont(metric.getFont().deriveFont(12f));
		addFormRow(body, "Metric", metric);

		addSummaryRow(body, "Target",
			com.goalplanner.util.FormatUtil.formatXp(g.getTargetValue()));
		return body;
	}

	/** QUEST / DIARY / COMBAT_ACHIEVEMENT: their target is immutable, so the edit
	 *  form is just a read-only name plus the lifecycle chips. */
	private JComponent buildThinEditBody(Goal g)
	{
		JPanel body = formBody();
		JLabel name = new JLabel("<html>" + escapeHtml(g.getName() != null ? g.getName() : "") + "</html>");
		name.setForeground(CREATE_FG);
		name.setFont(name.getFont().deriveFont(12f));
		addFormRow(body, tileLabel(g.getType()), name);
		if (g.getDescription() != null && !g.getDescription().isEmpty())
		{
			JLabel desc = new JLabel("<html>" + escapeHtml(g.getDescription()) + "</html>");
			desc.setForeground(CREATE_FG_DIM);
			desc.setFont(desc.getFont().deriveFont(10f));
			desc.setAlignmentX(Component.LEFT_ALIGNMENT);
			body.add(desc);
			body.add(Box.createVerticalStrut(6));
		}
		return body;
	}

	private static String escapeHtml(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Inline repeat controls for edit mode (ADR-0008), replacing the repeat
	 *  JOptionPane choosers. Renders only for goals that carry their OWN repeat
	 *  state - CUSTOM goals and derived per-period slices (repeatChunk > 0). A
	 *  plain auto-tracked grind has no own-repeat; it derives a slice via the
	 *  "Make repeatable" chip instead. */
	private void addEditRepeatControls(JPanel body, Goal g, String amountLabel)
	{
		final String gid = g.getId();
		final boolean hasChunk = g.getRepeatChunk() > 0;
		if (g.getType() != GoalType.CUSTOM && !hasChunk)
		{
			return;
		}

		final com.goalplanner.model.RepeatPeriod[] period = {
			g.getRepeatEvery().isRepeating() ? g.getRepeatEvery()
				: com.goalplanner.model.RepeatPeriod.DAILY };

		JCheckBox toggle = new JCheckBox("Repeatable");
		toggle.setOpaque(false);
		toggle.setForeground(CREATE_FG);
		toggle.setFont(toggle.getFont().deriveFont(11f));
		toggle.setAlignmentX(Component.LEFT_ALIGNMENT);
		toggle.setSelected(g.getRepeatEvery().isRepeating());

		JPanel detail = new JPanel();
		detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
		detail.setOpaque(false);
		detail.setAlignmentX(Component.LEFT_ALIGNMENT);
		detail.setVisible(toggle.isSelected());
		detail.add(Box.createVerticalStrut(4));
		detail.add(buildEditPeriodPills(gid, period));
		detail.add(Box.createVerticalStrut(4));
		if (hasChunk)
		{
			JTextField chunk = new JTextField(8);
			styleField(chunk);
			chunk.setText(Integer.toString(g.getRepeatChunk()));
			commitOnBlurOrEnter(chunk, () ->
			{
				int v = parsePositiveInt(chunk.getText());
				if (v <= 0) { chunk.setText(Integer.toString(g.getRepeatChunk())); return; }
				api.setGoalRepeatChunk(gid, v);
			});
			addFormRow(detail, amountLabel, chunk);
		}

		toggle.addActionListener(e ->
		{
			api.setGoalRepeat(gid, toggle.isSelected()
				? period[0] : com.goalplanner.model.RepeatPeriod.NONE);
			detail.setVisible(toggle.isSelected());
			remeasureDock();
		});

		JLabel head = new JLabel("Repeat");
		head.setForeground(CREATE_FG_DIM);
		head.setFont(head.getFont().deriveFont(10f));
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(Box.createVerticalStrut(2));
		body.add(head);
		body.add(Box.createVerticalStrut(2));
		body.add(toggle);
		body.add(detail);
		body.add(Box.createVerticalStrut(6));
	}

	/** Daily / Weekly / Monthly pills for edit mode: the tapped one is highlighted
	 *  AND committed to the goal via {@code setGoalRepeat}. */
	private JComponent buildEditPeriodPills(String gid, com.goalplanner.model.RepeatPeriod[] out)
	{
		com.goalplanner.model.RepeatPeriod[] periods = {
			com.goalplanner.model.RepeatPeriod.DAILY,
			com.goalplanner.model.RepeatPeriod.WEEKLY,
			com.goalplanner.model.RepeatPeriod.MONTHLY };
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		java.util.List<JButton> pills = new ArrayList<>();
		java.util.Map<JButton, com.goalplanner.model.RepeatPeriod> owner = new HashMap<>();
		Runnable refresh = () ->
		{
			for (JButton b : pills)
			{
				boolean sel = owner.get(b) == out[0];
				b.setBackground(sel ? CREATE_SEL_BG : CREATE_TILE_BG);
				b.setForeground(sel ? CREATE_PRIMARY_FG : CREATE_FG);
			}
		};
		for (com.goalplanner.model.RepeatPeriod p : periods)
		{
			JButton b = new RoundedPaint.RoundedButton(p.getLabel());
			b.setFont(b.getFont().deriveFont(11f));
			b.setBorder(new EmptyBorder(3, 10, 3, 10));
			b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			pills.add(b);
			owner.put(b, p);
			b.addActionListener(e ->
			{
				out[0] = p;
				refresh.run();
				api.setGoalRepeat(gid, p);
			});
			row.add(b);
		}
		refresh.run();
		return row;
	}

	// ----- edit-mode lifecycle action chips -----

	/** The drill-in groups the edit chips tree into. After the flatten pass (Data
	 *  is now a direct chip row and Relations an itemized inline list), ACTIONS is
	 *  the single remaining group - "only go deeper when necessary". */
	private enum EditGroup { ACTIONS }

	/** The drill-in groups the SECTION dock chips tree into. EDIT = rename/color/
	 *  delete; LAYOUT = nesting + archive override; SHARE = copy/save codes. */
	private enum SectionGroup { EDIT, LAYOUT, SHARE }

	/** The remaining drill-in group for the flattened edit form: at the top level
	 *  it is a single [Actions] chip; tapping it swaps the row for the action member
	 *  chips plus a "< Back". Data now renders as a direct chip row and Relations as
	 *  an itemized inline list (both above this), so only Actions still drills in.
	 *  Group navigation is held in {@link #dockEditGroup} and re-rendered via
	 *  {@link #refreshEditForm()}. */
	private JComponent buildEditChips(Goal g)
	{
		JPanel wrap = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
		wrap.setOpaque(false);

		if (dockEditGroup == null)
		{
			wrap.add(chip("Actions", "Move, copy, share, remove",
				() -> { dockEditGroup = EditGroup.ACTIONS; refreshEditForm(); }));
			return wrap;
		}

		wrap.add(chip("< Back", "Back to the goal",
			() -> { dockEditGroup = null; refreshEditForm(); }));
		switch (dockEditGroup)
		{
			case ACTIONS:   buildActionsChips(g, wrap); break;
			default:        break;
		}
		return wrap;
	}

	/** Data group: optional/required, color, tags, restore defaults. */
	private void buildDataChips(Goal g, JPanel wrap)
	{
		final String gid = g.getId();
		final boolean complete = g.isComplete();

		if (!complete)
		{
			wrap.add(chip(g.isOptional() ? "Required" : "Optional",
				g.isOptional() ? "Mark this goal required" : "Mark this goal optional",
				() -> { api.setGoalOptional(gid, !g.isOptional()); refreshEditForm(); }));
		}
		wrap.add(chip("Color", "Change this goal's color",
			() -> openColorSurfaceForGoal(gid)));
		wrap.add(chip("Add tag", "Add a tag to this goal",
			() -> openTagAddSurfaceForGoal(gid)));
		java.util.List<Tag> removable = removableTagsFor(g);
		if (!removable.isEmpty())
		{
			wrap.add(chip("Drop tags", "Remove tags from this goal",
				() -> openTagRemoveSurfaceForGoal(gid)));
		}
		if (api.isGoalOverridden(gid))
		{
			wrap.add(chip("Restore defaults", "Reset tags and color to their defaults",
				() -> { api.bulkRestoreDefaults(java.util.Collections.singleton(gid)); refreshEditForm(); }));
		}
	}

	/** Actions group: move/copy to section, seed reqs, Loadout Lab, share, remove.
	 *  The Requires / Required-by / Drop-reqs / Drop-dependents chips moved out of a
	 *  drill-in group: relations are now edited inline on the itemized list (per-row
	 *  X + "+ Add relation"). "Add reqs to section" (the seed action) landed here as
	 *  the one relation-adjacent action with no inline-list home. */
	private void buildActionsChips(Goal g, JPanel wrap)
	{
		final String gid = g.getId();
		final GoalType type = g.getType();

		wrap.add(chip("Move to section", "Move this goal to another section",
			() -> openMoveSurface(MoveMode.MOVE, java.util.Collections.singletonList(gid))));
		wrap.add(chip("Copy to section", "Duplicate this goal into another section",
			() -> openMoveSurface(MoveMode.COPY, java.util.Collections.singletonList(gid))));

		if (goalHasSeedableReqs(g))
		{
			wrap.add(chip("Add reqs to section",
				"Add this goal's requirements into its section", () -> dockSeedReqs(g)));
		}

		if (type == GoalType.BOSS && g.getBossName() != null && !g.getBossName().isEmpty())
		{
			LoadoutLabState labState = loadoutLabState();
			if (labState == LoadoutLabState.ENABLED)
			{
				final String monster = g.getBossName();
				wrap.add(chip("Loadout Lab", "Search this boss in Loadout Lab",
					() -> searchLoadoutLab(monster)));
			}
			else if (labState == LoadoutLabState.INSTALLED_DISABLED)
			{
				JButton off = flatButton("Lab is off", false);
				off.setToolTipText("Loadout Lab is installed but disabled");
				off.setEnabled(false);
				wrap.add(off);
			}
		}

		if (isShareAvailable())
		{
			final java.util.List<String> shareIds = java.util.Collections.singletonList(gid);
			wrap.add(chip("Share", "Copy or save a share code for this goal",
				() -> openShareForGoals(shareIds)));
		}

		wrap.add(chip("Remove", "Remove this goal (undoable)", () -> api.removeGoal(gid)));
	}

	// ============================================================
	// Section-surface (ADR-0007): the SECTION action dock.
	//
	// A selected section renders its actions as a surface above the permanent
	// create footer, exactly like the goal EDIT view. Assembly lives ONLY here,
	// reached from refreshDock()'s SECTION case (the single-place rule). Every
	// chip REUSES an existing dialog / API / GoalPanel handler - the still-intact
	// GoalContextMenuBuilder section menu is the parity reference; nothing is
	// rebuilt. Built-in sections (Incomplete/Completed/Repeatable) mirror the
	// menu's gating: no rename/delete/archive-override, and Add Goal is hidden on
	// the auto-managed Completed section.
	// ============================================================

	/** Build the section action surface for {@code sv}: a name header, a short
	 *  meta line, and the action chips (drill-in grouped when it gets crowded),
	 *  under a full-width indicator bar naming the section. */
	private JComponent buildSectionDock(com.goalplanner.api.SectionView sv)
	{
		JPanel inner = new JPanel(new BorderLayout(0, 6));
		inner.setOpaque(false);
		inner.setBorder(new EmptyBorder(6, 8, 8, 8));

		int goalCount = countGoalsInSection(sv.id);
		String kind = sv.builtIn
			? (sv.kind != null ? sv.kind.substring(0, 1)
				+ sv.kind.substring(1).toLowerCase(java.util.Locale.ROOT) + " (built-in)"
				: "Built-in")
			: "Section";
		JLabel meta = new JLabel(kind + " - " + goalCount
			+ (goalCount == 1 ? " goal" : " goals"));
		meta.setForeground(CREATE_FG_DIM);
		meta.setFont(meta.getFont().deriveFont(10f));
		inner.add(meta, BorderLayout.NORTH);

		inner.add(buildSectionChips(sv), BorderLayout.CENTER);
		// The indicator bar names the section (uppercased small-caps), heading the
		// surface so it is unmistakably about this section.
		return surfaceShell(sv.name, false, inner);
	}

	private JComponent buildSectionChips(com.goalplanner.api.SectionView sv)
	{
		JPanel wrap = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
		wrap.setOpaque(false);

		if (dockSectionGroup == null)
		{
			buildSectionChipsTop(sv, wrap);
			return wrap;
		}

		wrap.add(chip("< Back", "Back to section actions",
			() -> { dockSectionGroup = null; refreshSectionDock(); }));
		switch (dockSectionGroup)
		{
			case EDIT:   buildSectionEditChips(sv, wrap); break;
			case LAYOUT: buildSectionLayoutChips(sv, wrap); break;
			case SHARE:  buildSectionShareChips(sv, wrap); break;
			default:     break;
		}
		return wrap;
	}

	/** Top level: select-all, add-goal, the Edit/Layout/Share groups, deselect. */
	private void buildSectionChipsTop(com.goalplanner.api.SectionView sv, JPanel wrap)
	{
		final String sid = sv.id;
		final boolean builtIn = sv.builtIn;
		final boolean completed = "COMPLETED".equals(sv.kind);
		final int goalCount = countGoalsInSection(sid);

		// Select all / Deselect all - label flips like the menu; hidden when empty.
		if (goalCount > 0)
		{
			final boolean allSel = isAllSelectedInSection(sid);
			wrap.add(chip(allSel ? "Deselect all" : "Select all",
				allSel ? "Deselect every goal in this section"
					: "Select every goal in this section",
				() -> {
					if (allSel) api.deselectAllInSection(sid);
					else api.selectAllInSection(sid);
					// (De)selecting goals moves the dock to the goal/multi state.
				}));
		}

		// Add a goal into this section. Hidden on Completed (auto-managed), mirroring
		// the menu. Task 1: opens the in-dock create flow (type grid + stepper) with
		// this section as the fixed landing target, so the created goal lands here
		// with no section-pick step - not the Swing add-goal dialog.
		if (!completed)
		{
			wrap.add(chip("Add goal", "Add a goal into this section",
				() -> openCreateFlowForSection(sid)));
		}

		// Rename/color/delete only make sense on user sections; a built-in gets a
		// direct Change color chip (the one edit the menu allows on built-ins).
		if (!builtIn)
		{
			wrap.add(chip("Edit", "Rename, change color, or delete",
				() -> { dockSectionGroup = SectionGroup.EDIT; refreshSectionDock(); }));
		}
		else
		{
			wrap.add(chip("Change color", "Change this section's color",
				() -> openColorSurfaceForSection(sv.id)));
		}

		// Layout: dependency nesting (all sections) + completed handling (user only).
		wrap.add(chip("Layout", "Dependency nesting and completed-goal handling",
			() -> { dockSectionGroup = SectionGroup.LAYOUT; refreshSectionDock(); }));

		// Share: only when sharing is available and there are goals to encode.
		if (isShareAvailable() && goalCount > 0)
		{
			wrap.add(chip("Share", "Copy or save a share code",
				() -> { dockSectionGroup = SectionGroup.SHARE; refreshSectionDock(); }));
		}

		wrap.add(chip("Deselect", "Clear the section selection",
			this::clearSectionSelection));
	}

	/** Edit group (user sections only): rename, color, delete. */
	private void buildSectionEditChips(com.goalplanner.api.SectionView sv, JPanel wrap)
	{
		wrap.add(chip("Rename", "Rename this section",
			() -> dialogFactory.showRenameSectionDialog(sv)));
		wrap.add(chip("Change color", "Change this section's color",
			() -> openColorSurfaceForSection(sv.id)));
		wrap.add(chip("Delete", "Delete this section (undoable)",
			() -> confirmDeleteSection(sv)));
	}

	/** Layout group: the dependency-nesting cycle, plus the completed-goal archive
	 *  cycle on user sections. Each chip's label shows the current state and a tap
	 *  advances it (Default -> On -> Off -> Default), mirroring the menu's radios. */
	private void buildSectionLayoutChips(com.goalplanner.api.SectionView sv, JPanel wrap)
	{
		final String sid = sv.id;

		final boolean indentDefault = config.showDependenciesIndented();
		final Boolean nested = sv.nestedOverride;
		String nestState = nested == null
			? "Default (" + (indentDefault ? "nested" : "flat") + ")"
			: (Boolean.TRUE.equals(nested) ? "Nested" : "Flat");
		wrap.add(chip("Nesting: " + nestState,
			"Cycle dependency nesting: use default, always nested, or always flat",
			() -> {
				api.setSectionNestedOverride(sid, cycleOverride(nested));
				refreshSectionDock();
			}));

		if (!sv.builtIn)
		{
			final boolean archiveDefault = api.isAutoArchiveDefault();
			final Boolean archive = sv.autoArchiveOverride;
			String archState = archive == null
				? "Default (" + (archiveDefault ? "archive" : "inline") + ")"
				: (Boolean.TRUE.equals(archive) ? "Archive" : "Keep inline");
			wrap.add(chip("Completed: " + archState,
				"Cycle completed-goal handling: use default, auto-archive, or keep inline",
				() -> {
					api.setSectionAutoArchiveOverride(sid, cycleOverride(archive));
					refreshSectionDock();
				}));
		}
	}

	/** Share group: open the inline Share surface for this section or for all
	 *  sections (the surface carries both Copy and, when wired, Save). */
	private void buildSectionShareChips(com.goalplanner.api.SectionView sv, JPanel wrap)
	{
		final String sid = sv.id;
		wrap.add(chip("Share section", "Copy or save a share code for this section",
			() -> openShareForSection(sid)));
		wrap.add(chip("Share all", "Copy or save a share code for all sections",
			this::openShareForAllSections));
	}

	/** The section delete confirm, reusing the menu's move-instead prompt verbatim.
	 *  Clears the selection first since the section is about to vanish. */
	private void confirmDeleteSection(com.goalplanner.api.SectionView sv)
	{
		int goalCount = countGoalsInSection(sv.id);
		String plural = goalCount == 1 ? "goal" : "goals";
		javax.swing.JCheckBox moveInstead = new javax.swing.JCheckBox(
			"Move " + (goalCount == 1 ? "it" : "them")
				+ " to Default (Incomplete/Completed) instead");
		Object[] message = goalCount > 0
			? new Object[]{
				"Delete section \"" + sv.name + "\"?\n"
					+ "This also deletes its " + goalCount + " " + plural
					+ ". (Undo restores everything.)",
				moveInstead}
			: new Object[]{"Delete section \"" + sv.name + "\"?"};
		int confirm = JOptionPane.showConfirmDialog(
			this,
			message,
			"Delete Section",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);
		if (confirm == JOptionPane.YES_OPTION)
		{
			selectedSectionId = null;
			dockSectionGroup = null;
			api.deleteSection(sv.id, moveInstead.isSelected());
		}
	}

	private JButton chip(String label, String tooltip, Runnable action)
	{
		JButton b = flatButton(label, false);
		b.setToolTipText(tooltip);
		b.addActionListener(e -> action.run());
		return b;
	}

	/** A panel that fills the dock's width when hosted in the expanded scroll
	 *  area, so the BoxLayout rows and the {@link WrapLayout} chip flow lay out
	 *  against the real dock width. A plain JPanel would take only its preferred
	 *  width and clip (the dock suppresses horizontal scrolling), and the
	 *  full-width indicator bar would not span the dock. */
	private static final class ScrollablePanel extends JPanel implements javax.swing.Scrollable
	{
		/** When set, the panel paints a rounded card fill of this color (glam
		 *  surfaces); null keeps it fully transparent (result columns). */
		private Color cardBg = null;

		ScrollablePanel(java.awt.LayoutManager lm) { super(lm); }

		/** Paint a rounded {@link RoundedPaint#SURFACE_RADIUS} card behind this
		 *  panel so a surface reads as a card. Returns this for chaining. */
		ScrollablePanel asCard(Color bg) { this.cardBg = bg; return this; }

		@Override
		protected void paintComponent(java.awt.Graphics g)
		{
			if (cardBg != null)
			{
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				try
				{
					RoundedPaint.fill(g2, 0, 0, getWidth(), getHeight(),
						RoundedPaint.SURFACE_RADIUS, cardBg);
				}
				finally
				{
					g2.dispose();
				}
			}
			super.paintComponent(g);
		}

		@Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
		@Override public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) { return 16; }
		@Override public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) { return 48; }
		@Override public boolean getScrollableTracksViewportWidth() { return true; }
		@Override public boolean getScrollableTracksViewportHeight() { return false; }
	}

	/** A {@link FlowLayout} that reports a wrapped preferred size, so chips flow
	 *  onto multiple lines and grow the dock vertically instead of overflowing a
	 *  fixed-width, horizontal-scroll-suppressed surface. */
	private static final class WrapLayout extends FlowLayout
	{
		WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }

		@Override public Dimension preferredLayoutSize(Container target) { return layoutSize(target, true); }

		@Override public Dimension minimumLayoutSize(Container target)
		{
			Dimension d = layoutSize(target, false);
			d.width -= (getHgap() + 1);
			return d;
		}

		private Dimension layoutSize(Container target, boolean preferred)
		{
			synchronized (target.getTreeLock())
			{
				int targetWidth = target.getSize().width;
				if (targetWidth == 0)
				{
					targetWidth = Integer.MAX_VALUE;
				}
				int hgap = getHgap();
				int vgap = getVgap();
				java.awt.Insets insets = target.getInsets();
				int maxWidth = targetWidth - (insets.left + insets.right + hgap * 2);
				Dimension dim = new Dimension(0, 0);
				int rowWidth = 0;
				int rowHeight = 0;
				int n = target.getComponentCount();
				for (int i = 0; i < n; i++)
				{
					Component m = target.getComponent(i);
					if (!m.isVisible())
					{
						continue;
					}
					Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
					if (rowWidth + d.width > maxWidth && rowWidth > 0)
					{
						dim.width = Math.max(dim.width, rowWidth);
						dim.height += rowHeight + vgap;
						rowWidth = 0;
						rowHeight = 0;
					}
					if (rowWidth != 0)
					{
						rowWidth += hgap;
					}
					rowWidth += d.width;
					rowHeight = Math.max(rowHeight, d.height);
				}
				dim.width = Math.max(dim.width, rowWidth);
				dim.height += rowHeight;
				dim.width += insets.left + insets.right + hgap * 2;
				dim.height += insets.top + insets.bottom + vgap * 2;
				return dim;
			}
		}
	}
}
