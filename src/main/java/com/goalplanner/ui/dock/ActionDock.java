package com.goalplanner.ui.dock;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.border.Border;
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
	// The PERMANENT footer: Create Goal | Create Section, always visible in every
	// state (ADR-0008 refinement). The contextual surface (create grid/form, a
	// selected goal's edit view, or the multi-select action strips) renders ABOVE
	// it in centerHost; the footer never swaps out and stays put when collapsed.
	private final JButton createGoalBtn = new JButton("Create Goal");
	private final JButton createSectionBtn = new JButton("Create Section");
	private final JPanel footerRow = new JPanel(new GridLayout(1, 2, 1, 0));
	private Runnable onCreateGoal = null;
	private Runnable onCreateSection = null;
	private boolean collapsed = true; // rest = just the permanent footer, surface hidden
	private Rows current = new Rows(null, List.of(), List.of());
	/** The surface component currently mounted above the footer (create grid/form
	 *  or an edit view), or null when the dock is in button-strip (multi) mode. */
	private JComponent customView = null;

	public ActionDock()
	{
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		// An engraved, center-glowing rule marks the seam between the goal list and
		// the dock - a deliberate separator rather than a flat hairline (Task C).
		setBorder(new DockDivider());

		content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.add(scrollStrip(topRow));
		content.add(scrollStrip(bottomRow));

		centerHost.setOpaque(false);
		centerHost.add(content, BorderLayout.CENTER);

		buildFooterRow();
		// Surface above, permanent create footer below.
		add(centerHost, BorderLayout.CENTER);
		add(footerRow, BorderLayout.SOUTH);
		applyCollapsed();
	}

	/**
	 * Wire the two permanent footer buttons. The footer is always visible, so
	 * this only (re)binds the callbacks the panel wants to run when Create Goal /
	 * Create Section are tapped. The caller owns the toggle/switch semantics
	 * (open the create surface, switch modes, or collapse) - the dock just
	 * forwards the click.
	 */
	public void setFooterActions(Runnable onCreateGoal, Runnable onCreateSection)
	{
		this.onCreateGoal = onCreateGoal;
		this.onCreateSection = onCreateSection;
	}

	/**
	 * Show or hide the contextual surface above the footer. The footer stays put
	 * either way. Collapsed = rest (just the two-button footer, list keeps its
	 * height); expanded = the mounted surface renders above the footer. The panel
	 * drives this: a selection auto-expands, a deselect collapses, the footer
	 * buttons toggle it.
	 */
	public void setExpanded(boolean expanded)
	{
		boolean wantCollapsed = !expanded;
		if (collapsed == wantCollapsed)
		{
			return; // already in the requested state
		}
		collapsed = wantCollapsed;
		applyCollapsed();
	}

	/** Whether the contextual surface is currently showing above the footer. */
	public boolean isExpanded()
	{
		return !collapsed;
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
	/** A hairline splitting the surface above from the permanent footer below. */
	private static final java.awt.Color FOOTER_TOP_RULE = new java.awt.Color(0x2A, 0x2A, 0x2C);

	private static final java.awt.Color PEEK_SECTION_BG = new java.awt.Color(0x1E, 0x26, 0x30);
	private static final java.awt.Color PEEK_SECTION_FG = new java.awt.Color(0xAF, 0xC8, 0xE6);

	/** The permanent footer: Create Goal | Create Section. Both forward their tap
	 *  to the panel-supplied callback; the panel decides whether that opens the
	 *  create surface, switches modes, or collapses (it owns the nav state). */
	private void buildFooterRow()
	{
		footerRow.setOpaque(false);
		styleCreateButton(createGoalBtn, PEEK_CREATE_BG, PEEK_CREATE_FG);
		styleCreateButton(createSectionBtn, PEEK_SECTION_BG, PEEK_SECTION_FG);
		createGoalBtn.setToolTipText("Create a goal");
		createSectionBtn.setToolTipText("Create a section");
		createGoalBtn.addActionListener(e -> {
			if (onCreateGoal != null)
			{
				onCreateGoal.run();
			}
		});
		createSectionBtn.addActionListener(e -> {
			if (onCreateSection != null)
			{
				onCreateSection.run();
			}
		});
		footerRow.add(createGoalBtn);
		footerRow.add(createSectionBtn);
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
		// Only the surface hides on collapse; the footer stays visible always.
		centerHost.setVisible(!collapsed);
		// The footer's top hairline divides it from the surface above - shown only
		// when expanded, so at rest it does not read as a double rule under the
		// dock's own DockDivider. A 1px empty border keeps the footer height steady.
		footerRow.setBorder(collapsed
			? BorderFactory.createEmptyBorder(1, 0, 0, 0)
			: BorderFactory.createMatteBorder(1, 0, 0, 0, FOOTER_TOP_RULE));
		revalidate();
		repaint();
	}

	@Override
	public Dimension getPreferredSize()
	{
		int surface;
		if (customView != null)
		{
			// A mounted surface (create grid/form or edit view): grow to its
			// preferred height, capped so the goal list keeps a usable minimum.
			surface = Math.min(CREATE_MAX_H, customView.getPreferredSize().height + 4);
		}
		else
		{
			surface = 2 * (ROW_H + 4) + 2;
		}
		// The permanent footer is always counted; the surface only adds height
		// while expanded.
		int footerH = footerRow.getPreferredSize().height;
		int h = footerH + (collapsed ? 0 : surface);
		return new Dimension(super.getPreferredSize().width, h);
	}

	/**
	 * The seam between the goal list and the dock: an engraved two-tone rule -
	 * a dark groove line with a lighter highlight beneath it - both tapering to
	 * transparent at the panel edges, plus a soft central glow so the divider
	 * reads as a deliberate flourish rather than a flat border. Painted entirely
	 * in code from {@link ColorScheme} tones, so it stays in step with the theme
	 * and ships no assets. A few pixels tall.
	 */
	private static final class DockDivider implements Border
	{
		private static final int HEIGHT = 6;

		@Override
		public Insets getBorderInsets(Component c)
		{
			return new Insets(HEIGHT, 0, 0, 0);
		}

		@Override
		public boolean isBorderOpaque()
		{
			return false;
		}

		@Override
		public void paintBorder(Component c, Graphics g, int x, int y, int width, int height)
		{
			if (width <= 0)
			{
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

				int cx = x + width / 2;
				int grooveY = y + 2;
				Color groove = shade(ColorScheme.DARKER_GRAY_COLOR, -14);
				Color highlight = shade(ColorScheme.DARK_GRAY_HOVER_COLOR, 18);

				// Two stacked hairlines, each fading to nothing at the edges.
				paintFadedLine(g2, x, width, grooveY, groove, 255);
				paintFadedLine(g2, x, width, grooveY + 1, highlight, 255);

				// Central glow: a short, brighter, edge-faded segment centered on cx.
				int glowW = Math.max(28, width / 3);
				paintFadedLine(g2, cx - glowW / 2, glowW, grooveY + 1,
					shade(ColorScheme.LIGHT_GRAY_COLOR, 0), 110);
			}
			finally
			{
				g2.dispose();
			}
		}

		/** A 1px horizontal line centered in {@code [lx, lx+w)} that ramps from
		 *  transparent at both ends to {@code peakAlpha} of {@code base} in the middle. */
		private static void paintFadedLine(Graphics2D g2, int lx, int w, int lineY,
			Color base, int peakAlpha)
		{
			Color solid = new Color(base.getRed(), base.getGreen(), base.getBlue(), peakAlpha);
			Color edge = new Color(base.getRed(), base.getGreen(), base.getBlue(), 0);
			int mid = lx + w / 2;
			g2.setPaint(new GradientPaint(lx, 0, edge, mid, 0, solid));
			g2.fillRect(lx, lineY, w / 2, 1);
			g2.setPaint(new GradientPaint(mid, 0, solid, lx + w, 0, edge));
			g2.fillRect(mid, lineY, w - w / 2, 1);
		}

		private static Color shade(Color base, int d)
		{
			return new Color(clamp(base.getRed() + d), clamp(base.getGreen() + d),
				clamp(base.getBlue() + d));
		}

		private static int clamp(int v)
		{
			return Math.max(0, Math.min(255, v));
		}
	}
}
