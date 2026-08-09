package com.goalplanner.ui.dock;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import net.runelite.client.ui.ColorScheme;

/**
 * The permanent control panel docked under the goal list (ADR-0007).
 *
 * <p>Two fixed-height rows of compact action buttons. Selection changes swap
 * the CONTENT, never the height - a dock that resizes on click shifts the
 * list under the cursor. Each row is a horizontally scrollable strip, so
 * overflow is reached by scrolling, never via a popup menu (removing popups
 * is the whole point). A chevron collapses the dock to a slim handle;
 * collapse is session-only for now.
 *
 * <p>The dock is deliberately dumb: it renders whatever {@link Row} lists the
 * panel hands it. All state decisions live in {@link DockContext}; all action
 * assembly lives in GoalPanel, so the coming parity pass edits one method.
 */
public class ActionDock extends JPanel
{
	/** One dock button: short ASCII label, one-clause tooltip, action. */
	public static final class Item
	{
		final String label;
		final String tooltip;
		final Runnable action;
		final boolean enabled;

		public Item(String label, String tooltip, Runnable action)
		{
			this(label, tooltip, action, true);
		}

		public Item(String label, String tooltip, Runnable action, boolean enabled)
		{
			this.label = label;
			this.tooltip = tooltip;
			this.action = action;
			this.enabled = enabled;
		}
	}

	/** The two strips. Either may be empty (the row still reserves height). */
	public static final class Rows
	{
		final List<Item> top;
		final List<Item> bottom;
		final String hint;

		/** @param hint short status text shown at the left of the top row
		 *              (e.g. "3 selected"); null for none. */
		public Rows(String hint, List<Item> top, List<Item> bottom)
		{
			this.hint = hint;
			this.top = top != null ? top : List.of();
			this.bottom = bottom != null ? bottom : List.of();
		}
	}

	private static final int ROW_H = 26;
	private static final int PEEK_H = 28;
	/** Ceiling for the create surface's expanded height; taller forms scroll
	 *  inside the dock rather than shoving the list off-screen (ADR-0008 says
	 *  the form grows to fit, but the list must keep a usable minimum). */
	private static final int CREATE_MAX_H = 300;

	private final JPanel content = new JPanel();
	private final JPanel centerHost = new JPanel(new BorderLayout());
	private final JPanel topRow = strip();
	private final JPanel bottomRow = strip();
	private final JButton peekBar = new JButton();
	// The NORTH header swaps between the single peek bar (selection states) and
	// the two-button create row (nothing selected): Create Goal / Create Section.
	private final JPanel peekHost = new JPanel(new BorderLayout());
	private final JButton createGoalBtn = new JButton("Create Goal");
	private final JButton createSectionBtn = new JButton("Create Section");
	private final JPanel peekCreateRow = new JPanel(new GridLayout(1, 2, 1, 0));
	private boolean createPeekMode = false;
	private Runnable onCreateGoal = null;
	private Runnable onCreateSection = null;
	private boolean collapsed = true; // rest state is the one-line peek
	private String peekText = "Add a goal";
	private boolean peekAccent = true; // green when it is the create invitation
	private Rows current = new Rows(null, List.of(), List.of());
	/** The create-surface component currently mounted (grid or a type form),
	 *  or null when the dock is in button-strip (selection) mode. */
	private JComponent customView = null;

	public ActionDock()
	{
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
			ColorScheme.DARK_GRAY_HOVER_COLOR));

		content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.add(scrollStrip(topRow));
		content.add(scrollStrip(bottomRow));

		centerHost.setOpaque(false);
		centerHost.add(content, BorderLayout.CENTER);

		stylePeekBar();
		buildCreatePeekRow();
		peekHost.setOpaque(false);
		peekHost.add(peekBar, BorderLayout.CENTER);
		add(peekHost, BorderLayout.NORTH);
		add(centerHost, BorderLayout.CENTER);
		applyCollapsed();
	}

	/**
	 * The resting bar's label and tone. {@code accent} true = the green
	 * "+ Add a goal" create invitation (nothing selected); false = a neutral
	 * "<N> selected" peek.
	 */
	public void setPeek(String text, boolean accent)
	{
		this.peekText = text != null ? text : "";
		this.peekAccent = accent;
		if (createPeekMode)
		{
			// Leaving the create invitation: restore the single peek bar.
			createPeekMode = false;
			peekHost.removeAll();
			peekHost.add(peekBar, BorderLayout.CENTER);
		}
		applyCollapsed();
	}

	/**
	 * Put the create invitation in the header as two side-by-side buttons,
	 * Create Goal and Create Section (ADR-0008), replacing the single "+ Add a
	 * goal" bar. Create Goal toggles the create surface open (and runs
	 * {@code onCreateGoal} when expanding, so the caller can reset the surface
	 * to its type grid); Create Section runs {@code onCreateSection} directly -
	 * it is a prompt, not a dock expansion.
	 */
	public void setCreatePeek(Runnable onCreateGoal, Runnable onCreateSection)
	{
		this.onCreateGoal = onCreateGoal;
		this.onCreateSection = onCreateSection;
		if (!createPeekMode)
		{
			createPeekMode = true;
			peekHost.removeAll();
			peekHost.add(peekCreateRow, BorderLayout.CENTER);
		}
		applyCollapsed();
	}

	/** Replace the expanded content with the two button strips (selection
	 *  mode). Does not force the dock open. */
	public void setRows(Rows rows)
	{
		if (customView != null)
		{
			// Leave create mode: swap the strips back into the center host.
			centerHost.removeAll();
			centerHost.add(content, BorderLayout.CENTER);
			customView = null;
		}
		this.current = rows;
		rebuildStrip(topRow, rows.hint, rows.top);
		rebuildStrip(bottomRow, null, rows.bottom);
		revalidate();
		repaint();
	}

	/**
	 * Replace the expanded content with an arbitrary component - the create
	 * surface (type grid or a type-specific form) from ADR-0008. The dock
	 * grows to fit it, capped at {@link #CREATE_MAX_H}; beyond that the form
	 * scrolls inside the dock. Does not force the dock open.
	 */
	public void setExpandedComponent(JComponent view)
	{
		this.customView = view;
		centerHost.removeAll();
		JScrollPane sp = new JScrollPane(view,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		sp.setBorder(null);
		sp.setOpaque(false);
		sp.getViewport().setOpaque(false);
		sp.getVerticalScrollBar().setUnitIncrement(16);
		centerHost.add(sp, BorderLayout.CENTER);
		revalidate();
		repaint();
	}

	private static JPanel strip()
	{
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		p.setOpaque(false);
		return p;
	}

	/** A row that scroll-overflows horizontally: wheel over the strip pans it. */
	private JScrollPane scrollStrip(JPanel row)
	{
		JScrollPane sp = new JScrollPane(row,
			JScrollPane.VERTICAL_SCROLLBAR_NEVER,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		sp.setBorder(null);
		sp.setOpaque(false);
		sp.getViewport().setOpaque(false);
		Dimension d = new Dimension(0, ROW_H + 4);
		sp.setPreferredSize(d);
		sp.setMinimumSize(d);
		sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_H + 4));
		// Wheel pans horizontally within the strip; without this, hidden
		// overflow buttons would be unreachable (scrollbars are suppressed to
		// keep the fixed height honest).
		sp.addMouseWheelListener(e -> {
			javax.swing.JScrollBar h = sp.getHorizontalScrollBar();
			h.setValue(h.getValue() + e.getWheelRotation() * 24);
		});
		return sp;
	}

	private static final java.awt.Color BTN_BG = new java.awt.Color(0x3B, 0x3B, 0x3B);
	private static final java.awt.Color BTN_HOVER = new java.awt.Color(0x4C, 0x4C, 0x52);
	private static final java.awt.Color BTN_FG = new java.awt.Color(0xDC, 0xDC, 0xDC);
	private static final java.awt.Color BTN_FG_OFF = new java.awt.Color(0x77, 0x77, 0x77);
	private static final java.awt.Color HINT_FG = new java.awt.Color(0x9A, 0x9A, 0x9A);

	private void rebuildStrip(JPanel row, String hint, List<Item> items)
	{
		row.removeAll();
		if (hint != null && !hint.isEmpty())
		{
			JLabel l = new JLabel(hint.toUpperCase(java.util.Locale.ROOT));
			l.setForeground(HINT_FG);
			l.setFont(l.getFont().deriveFont(java.awt.Font.BOLD, 10f));
			l.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 6));
			row.add(l);
		}
		for (Item item : items)
		{
			// An item with no action is a group separator: a faint small-caps
			// label, so a scrolled strip still reads as clusters.
			if (item.action == null)
			{
				JLabel sep = new JLabel(item.label.toUpperCase(java.util.Locale.ROOT));
				sep.setForeground(HINT_FG);
				sep.setFont(sep.getFont().deriveFont(java.awt.Font.BOLD, 9f));
				sep.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 2));
				row.add(sep);
				continue;
			}
			row.add(makeButton(item));
		}
	}

	private JButton makeButton(Item item)
	{
		JButton b = new JButton(item.label);
		b.setToolTipText(item.tooltip);
		b.setEnabled(item.enabled);
		b.setFocusPainted(false);
		b.setBorderPainted(false);
		b.setContentAreaFilled(false);
		b.setOpaque(true);
		b.setBackground(BTN_BG);
		b.setForeground(item.enabled ? BTN_FG : BTN_FG_OFF);
		b.setFont(b.getFont().deriveFont(11f));
		b.setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 9));
		b.setCursor(java.awt.Cursor.getPredefinedCursor(
			item.enabled ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
		b.setPreferredSize(new Dimension(
			b.getPreferredSize().width + 2, ROW_H - 2));
		if (item.enabled)
		{
			b.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override public void mouseEntered(java.awt.event.MouseEvent e) { b.setBackground(BTN_HOVER); }
				@Override public void mouseExited(java.awt.event.MouseEvent e) { b.setBackground(BTN_BG); }
			});
			b.addActionListener(e -> item.action.run());
		}
		return b;
	}

	private static final java.awt.Color PEEK_CREATE_BG = new java.awt.Color(0x1D, 0x2A, 0x1F);
	private static final java.awt.Color PEEK_CREATE_FG = new java.awt.Color(0xBF, 0xE0, 0xBF);
	private static final java.awt.Color PEEK_NEUTRAL_BG = new java.awt.Color(0x22, 0x22, 0x24);
	private static final java.awt.Color PEEK_NEUTRAL_FG = new java.awt.Color(0xBC, 0xBC, 0xBC);

	private void stylePeekBar()
	{
		peekBar.setFocusPainted(false);
		peekBar.setBorderPainted(false);
		peekBar.setContentAreaFilled(true);
		peekBar.setOpaque(true);
		peekBar.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
		peekBar.setFont(peekBar.getFont().deriveFont(java.awt.Font.BOLD, 12f));
		peekBar.setBorder(BorderFactory.createEmptyBorder(6, 11, 6, 11));
		peekBar.setPreferredSize(new Dimension(0, PEEK_H));
		peekBar.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		peekBar.addActionListener(e -> {
			collapsed = !collapsed;
			applyCollapsed();
		});
	}

	private static final java.awt.Color PEEK_SECTION_BG = new java.awt.Color(0x1E, 0x26, 0x30);
	private static final java.awt.Color PEEK_SECTION_FG = new java.awt.Color(0xAF, 0xC8, 0xE6);

	private void buildCreatePeekRow()
	{
		peekCreateRow.setOpaque(false);
		styleCreateButton(createGoalBtn, PEEK_CREATE_BG, PEEK_CREATE_FG);
		styleCreateButton(createSectionBtn, PEEK_SECTION_BG, PEEK_SECTION_FG);
		// Create Goal opens the create surface below (toggle), like the peek bar;
		// on expand it runs onCreateGoal so the caller can reset to the grid.
		createGoalBtn.addActionListener(e -> {
			boolean expanding = collapsed;
			collapsed = !collapsed;
			if (expanding && onCreateGoal != null)
			{
				onCreateGoal.run();
			}
			applyCollapsed();
		});
		// Create Section opens the create surface below (toggle), like Create Goal;
		// on expand it runs onCreateSection so the caller can mount the in-dock
		// new-section form (note 2).
		createSectionBtn.addActionListener(e -> {
			boolean expanding = collapsed;
			collapsed = !collapsed;
			if (expanding && onCreateSection != null)
			{
				onCreateSection.run();
			}
			applyCollapsed();
		});
		peekCreateRow.add(createGoalBtn);
		peekCreateRow.add(createSectionBtn);
	}

	private void styleCreateButton(JButton b, java.awt.Color bg, java.awt.Color fg)
	{
		b.setFocusPainted(false);
		b.setBorderPainted(false);
		b.setContentAreaFilled(true);
		b.setOpaque(true);
		b.setBackground(bg);
		b.setForeground(fg);
		b.setFont(b.getFont().deriveFont(java.awt.Font.BOLD, 12f));
		b.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		b.setPreferredSize(new Dimension(0, PEEK_H));
		b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		java.awt.Color hover = bg.brighter();
		b.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override public void mouseEntered(java.awt.event.MouseEvent e) { b.setBackground(hover); }
			@Override public void mouseExited(java.awt.event.MouseEvent e) { b.setBackground(bg); }
		});
	}

	private void applyCollapsed()
	{
		centerHost.setVisible(!collapsed);
		peekBar.setText((peekAccent ? "+  " : "") + peekText);
		peekBar.setBackground(peekAccent ? PEEK_CREATE_BG : PEEK_NEUTRAL_BG);
		peekBar.setForeground(peekAccent ? PEEK_CREATE_FG : PEEK_NEUTRAL_FG);
		// Chevron trails on the right: up = tap to expand, down = tap to close.
		peekBar.setHorizontalTextPosition(javax.swing.SwingConstants.LEFT);
		peekBar.setIcon(collapsed
			? com.goalplanner.ui.ShapeIcons.upTriangle(7, new java.awt.Color(0x6A, 0x8A, 0x6A))
			: com.goalplanner.ui.ShapeIcons.downTriangle(7, new java.awt.Color(0x6A, 0x8A, 0x6A)));
		peekBar.setIconTextGap(8);
		peekBar.setToolTipText(collapsed ? "Open" : "Close");
		// Create Goal / Create Section are plain buttons (no chevron): they read as
		// actions, not a peek toggle.
		createGoalBtn.setToolTipText(collapsed ? "Open goal creation" : "Close");
		createSectionBtn.setToolTipText("Create a new section");
		revalidate();
		repaint();
	}

	@Override
	public Dimension getPreferredSize()
	{
		int expanded;
		if (customView != null)
		{
			// Create surface: grow to the form's preferred height, capped.
			expanded = Math.min(CREATE_MAX_H, customView.getPreferredSize().height + 4);
		}
		else
		{
			expanded = 2 * (ROW_H + 4) + 2;
		}
		int h = PEEK_H + (collapsed ? 0 : expanded);
		return new Dimension(super.getPreferredSize().width, h);
	}
}
