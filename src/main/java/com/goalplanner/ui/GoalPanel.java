package com.goalplanner.ui;

import com.goalplanner.model.Goal;
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
		undoButton.addActionListener(e -> api.undo());
		redoButton = new JButton(ShapeIcons.redoArrow(12, new Color(180, 180, 220)));
		redoButton.setMargin(new Insets(3, 6, 3, 6));
		redoButton.addActionListener(e -> api.redo());

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

		// Both mode banners share a vertical stack below the toolbar/search
		// row. They are mutually exclusive in practice (entering one mode
		// exits the other), but the layout supports either being shown.
		JPanel modeBanners = new JPanel();
		modeBanners.setLayout(new BoxLayout(modeBanners, BoxLayout.Y_AXIS));
		modeBanners.setOpaque(false);
		modeBanners.add(relationModeBanner);
		modeBanners.add(moveModeBanner);

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
				// In move-pick mode, the section title row acts as a drop
				// target for the source goal - particularly useful for
				// empty sections, where there's no card to click. Falls
				// through to the normal collapse toggle when not in mode.
				if (pendingMoveSourceId != null)
				{
					handleMovePickToSection(sectionIdRef);
					return;
				}
				api.toggleSectionCollapsed(sectionIdRef);
				// API callback rebuilds the panel.
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
			headerRows.add(headerRow);
			// All sections get a right-click menu. User sections get the full
			// rename/move/delete/color menu; built-ins get only Change Color.
			contextMenuBuilder.attachSectionContextMenu(headerRow, section, sectionViews);
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
				contextMenuBuilder.addContextMenu(card, goal, index, sectionStart, sectionEnd,
					nestParents.contains(goalIdRef)
						? () -> { api.toggleGoalNestCollapsed(goalIdRef); rebuild(); }
						: null);
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
				if (e.getButton() != MouseEvent.BUTTON1) return;
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
				boolean cmdCtrl = e.isMetaDown() || e.isControlDown();
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
		com.goalplanner.ui.dock.DockContext ctx =
			com.goalplanner.ui.dock.DockContext.of(api.getSelectedGoalIds());
		java.util.List<com.goalplanner.ui.dock.ActionDock.Item> top = new java.util.ArrayList<>();
		java.util.List<com.goalplanner.ui.dock.ActionDock.Item> bottom = new java.util.ArrayList<>();
		String hint = null;

		switch (ctx.getState())
		{
			case GOAL:
			{
				String gid = ctx.getSoleGoalId();
				Goal g = goalStore.findGoalById(gid);
				if (g == null) break;
				boolean manual = g.getType() == com.goalplanner.model.GoalType.CUSTOM
					|| g.getType() == com.goalplanner.model.GoalType.ITEM_GRIND;
				if (g.isComplete())
				{
					top.add(new com.goalplanner.ui.dock.ActionDock.Item("Reopen",
						"Mark incomplete and let tracking re-derive it",
						() -> api.markGoalIncomplete(gid)));
				}
				else if (manual)
				{
					top.add(new com.goalplanner.ui.dock.ActionDock.Item("Complete",
						"Mark this goal complete",
						() -> api.markGoalComplete(gid)));
				}
				top.add(new com.goalplanner.ui.dock.ActionDock.Item(
					g.isOptional() ? "Required" : "Optional",
					g.isOptional() ? "Mark this goal required" : "Mark this goal optional",
					() -> api.setGoalOptional(gid, !g.isOptional())));

				// Edit cluster (separator = Item with a null action).
				final Goal editGoal = g;
				if (g.getType() == com.goalplanner.model.GoalType.SKILL)
				{
					bottom.add(new com.goalplanner.ui.dock.ActionDock.Item("edit", null, null));
					bottom.add(new com.goalplanner.ui.dock.ActionDock.Item("Amount",
						"Change the target XP / level",
						() -> dialogFactory.showChangeSkillTargetDialog(editGoal)));
				}
				bottom.add(new com.goalplanner.ui.dock.ActionDock.Item("Deselect",
					"Clear the selection",
					() -> api.clearGoalSelection()));
				bottom.add(new com.goalplanner.ui.dock.ActionDock.Item("Remove",
					"Remove this goal (undoable)",
					() -> api.removeGoal(gid)));
				break;
			}
			case MULTI:
			{
				hint = ctx.getCount() + " selected";
				java.util.Set<String> ids =
					new java.util.LinkedHashSet<>(api.getSelectedGoalIds());
				top.add(new com.goalplanner.ui.dock.ActionDock.Item("Reset done",
					"Reopen every completed goal in the selection (one undo)",
					() -> api.bulkMarkIncomplete(ids)));
				top.add(new com.goalplanner.ui.dock.ActionDock.Item("Remove",
					"Remove every selected goal (one undo)",
					() -> api.bulkRemoveGoals(ids)));
				bottom.add(new com.goalplanner.ui.dock.ActionDock.Item("Deselect",
					"Clear the selection",
					() -> api.clearGoalSelection()));
				break;
			}
			case EMPTY:
			default:
			{
				hint = "Select a goal for actions";
				top.add(new com.goalplanner.ui.dock.ActionDock.Item("Add Section",
					"Create a new section",
					this::promptAddSectionFromDock));
				break;
			}
		}
		actionDock.setRows(new com.goalplanner.ui.dock.ActionDock.Rows(hint, top, bottom));
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
}
