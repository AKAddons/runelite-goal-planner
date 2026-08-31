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
import com.goalplanner.ui.RoundedPaint;
import net.runelite.client.ui.ColorScheme;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;
import javax.swing.BoxLayout;
import javax.swing.JScrollBar;

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
		/** Optional full-width button pinned ABOVE the two strips (e.g. the MULTI
		 *  "Deselect (N)"); null for none. */
		final Item lead;

		/** @param hint short status text shown at the left of the top row
		 *              (e.g. "3 selected"); null for none. */
		public Rows(String hint, List<Item> top, List<Item> bottom)
		{
			this(hint, top, bottom, null);
		}

		public Rows(String hint, List<Item> top, List<Item> bottom, Item lead)
		{
			this.hint = hint;
			this.top = top != null ? top : List.of();
			this.bottom = bottom != null ? bottom : List.of();
			this.lead = lead;
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
	/** Holds the mounted surface (content strips or a custom view) BELOW the grab
	 *  handle, so swapping the surface never removes the handle (Task 8). */
	private final JPanel surfaceHost = new JPanel(new BorderLayout());
	/** Holds the optional full-width lead button above the strips (MULTI
	 *  "Deselect (N)"); hidden when no lead is set. */
	private final JPanel leadHost = new JPanel(new BorderLayout());
	private final JPanel topRow = strip();
	private final JPanel bottomRow = strip();
	/** Drag-down / click-to-dismiss handle pinned at the top of the expanded
	 *  surface (Task 8). Only visible while expanded (it lives inside centerHost,
	 *  which hides on collapse). */
	private final GrabHandle grabHandle = new GrabHandle();
	/** Panel-supplied dismiss action: clear whatever drives the surface (goal /
	 *  section selection, create nav) and collapse, so the dock rests at the footer
	 *  from ANY state. Falls back to a plain collapse when unset. */
	private Runnable onDismiss = null;
	// The PERMANENT footer: Create Goal | Create Section, always visible in every
	// state (ADR-0008 refinement). The contextual surface (create grid/form, a
	// selected goal's edit view, or the multi-select action strips) renders ABOVE
	// it in centerHost; the footer never swaps out and stays put when collapsed.
	private final JButton createGoalBtn = new RoundedPaint.RoundedButton("Create Goal");
	private final JButton createSectionBtn = new RoundedPaint.RoundedButton("Create Section");
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

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		leadHost.setOpaque(false);
		leadHost.setVisible(false);
		leadHost.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(leadHost);
		content.add(wrapStrip(topRow));
		content.add(wrapStrip(bottomRow));

		// Opaque dark backing so nothing behind the dock (e.g. an optional goal's
		// diagonal hatch in the list) bleeds through the rounded, non-opaque surface
		// card or the non-opaque DockDivider seam.
		centerHost.setOpaque(true);
		centerHost.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		// The grab handle stays pinned at the top of the expanded surface; the
		// swappable surface (strips or a custom view) lives in surfaceHost below it.
		surfaceHost.setOpaque(true);
		surfaceHost.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		surfaceHost.add(content, BorderLayout.CENTER);
		centerHost.add(grabHandle, BorderLayout.NORTH);
		centerHost.add(surfaceHost, BorderLayout.CENTER);

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

	/** Wire the drag-down / click dismiss action (Task 8). The panel clears the
	 *  surface drivers (selection, section, create nav) and collapses; without it
	 *  the handle just collapses the surface. */
	public void setOnDismiss(Runnable onDismiss)
	{
		this.onDismiss = onDismiss;
	}

	/** Fired by the grab handle: hand off to the panel-supplied dismiss, or fall
	 *  back to a plain collapse. */
	private void triggerDismiss()
	{
		if (onDismiss != null)
		{
			onDismiss.run();
		}
		else
		{
			setExpanded(false);
		}
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
			// Leave create mode: swap the strips back into the surface host.
			surfaceHost.removeAll();
			surfaceHost.add(content, BorderLayout.CENTER);
			customView = null;
		}
		this.current = rows;
		renderLead(rows.lead);
		rebuildStrip(topRow, rows.hint, rows.top);
		rebuildStrip(bottomRow, null, rows.bottom);
		revalidate();
		repaint();
	}

	/** Render (or clear) the full-width lead button pinned above the strips. A
	 *  null lead hides the host so it reserves no height. */
	private void renderLead(Item lead)
	{
		leadHost.removeAll();
		if (lead == null || lead.action == null)
		{
			leadHost.setVisible(false);
			return;
		}
		JButton b = leadButton(lead.label, lead.tooltip, lead.action);
		leadHost.add(b, BorderLayout.CENTER);
		leadHost.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			b.getPreferredSize().height + 4));
		leadHost.setVisible(true);
	}

	/** Build a full-width "lead" button styled like the MULTI "Deselect (N)" bar
	 *  pinned above the strips. Shared so the single-goal edit surface can pin an
	 *  identical Deselect at its top (single + multi match). */
	public static JButton leadButton(String label, String tooltip, Runnable action)
	{
		final JButton b = new RoundedPaint.RoundedButton(label);
		b.setToolTipText(tooltip);
		b.setBackground(BTN_BG);
		b.setForeground(BTN_FG);
		b.setFont(b.getFont().deriveFont(Font.BOLD, 11f));
		b.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.addMouseListener(new MouseAdapter()
		{
			@Override public void mouseEntered(MouseEvent e) { b.setBackground(BTN_HOVER); }
			@Override public void mouseExited(MouseEvent e) { b.setBackground(BTN_BG); }
		});
		if (action != null)
		{
			b.addActionListener(e -> action.run());
		}
		return b;
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
		surfaceHost.removeAll();
		// The view must TRACK the viewport width: a plain panel reports the
		// preferred width of its widest row, and with the horizontal bar
		// forbidden the overflow was simply clipped - the Options surface
		// lost every chip past the dock edge (field report 2026-08-27).
		// Width-tracked, WrapLayout learns the real width and wraps.
		JScrollPane sp = new JScrollPane(new TracksWidth(view),
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		sp.setBorder(null);
		// Opaque (dock background) rather than transparent: the surface must fully
		// cover whatever is behind the dock, or an optional goal's diagonal hatch
		// shows through the rounded card's margins.
		sp.setOpaque(true);
		sp.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		sp.getViewport().setOpaque(true);
		sp.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		sp.getVerticalScrollBar().setUnitIncrement(16);
		surfaceHost.add(sp, BorderLayout.CENTER);
		revalidate();
		repaint();
	}

	/** A viewport view pinned to the viewport's width, never wider - the
	 * vertical-only scroll contract, made true for layout as well. */
	private static final class TracksWidth extends JPanel implements javax.swing.Scrollable
	{
		TracksWidth(JComponent view)
		{
			super(new BorderLayout());
			setOpaque(false);
			add(view, BorderLayout.CENTER);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d)
		{
			return 64;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	private static JPanel strip()
	{
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		p.setOpaque(false);
		// LEFT, not the JPanel default of CENTER: BoxLayout lines its children
		// up by their alignment points, so a centred strip beside the
		// left-aligned "Deselect (N)" lead pushed that button to the right half
		// of the dock. The old scroll strips hid this by declaring a preferred
		// width of 0.
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	/** A row that scroll-overflows horizontally: wheel over the strip pans it. */
	/**
	 * Chip strips WRAP rather than scroll. A 225px panel fits about two chips
	 * per row, so a single scrolling row hid most actions behind a mouse-wheel
	 * pan with no scrollbar and no affordance - reachable in theory, invisible
	 * in practice. Vertical space is the cheaper currency here: the dock
	 * already grows for mounted forms.
	 */
	private JPanel wrapStrip(JPanel row)
	{
		row.setLayout(new com.goalplanner.ui.WrapLayout(FlowLayout.LEFT, 4, 2));
		row.setOpaque(false);
		return row;
	}

	private static final Color BTN_BG = new Color(0x3B, 0x3B, 0x3B);
	private static final Color BTN_HOVER = new Color(0x4C, 0x4C, 0x52);
	private static final Color BTN_FG = new Color(0xDC, 0xDC, 0xDC);
	private static final Color BTN_FG_OFF = new Color(0x77, 0x77, 0x77);
	private static final Color HINT_FG = new Color(0x9A, 0x9A, 0x9A);

	private void rebuildStrip(JPanel row, String hint, List<Item> items)
	{
		row.removeAll();
		if (hint != null && !hint.isEmpty())
		{
			JLabel l = new JLabel(hint.toUpperCase(Locale.ROOT));
			l.setForeground(HINT_FG);
			l.setFont(l.getFont().deriveFont(Font.BOLD, 10f));
			l.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 6));
			row.add(l);
		}
		for (Item item : items)
		{
			// An item with no action is a group separator: a faint small-caps
			// label, so a scrolled strip still reads as clusters.
			if (item.action == null)
			{
				JLabel sep = new JLabel(item.label.toUpperCase(Locale.ROOT));
				sep.setForeground(HINT_FG);
				sep.setFont(sep.getFont().deriveFont(Font.BOLD, 9f));
				sep.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 2));
				row.add(sep);
				continue;
			}
			row.add(makeButton(item));
		}
	}

	private JButton makeButton(Item item)
	{
		JButton b = new RoundedPaint.RoundedButton(item.label);
		b.setToolTipText(item.tooltip);
		b.setEnabled(item.enabled);
		b.setBackground(BTN_BG);
		b.setForeground(item.enabled ? BTN_FG : BTN_FG_OFF);
		b.setFont(b.getFont().deriveFont(11f));
		b.setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 9));
		b.setCursor(Cursor.getPredefinedCursor(
			item.enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
		b.setPreferredSize(new Dimension(
			b.getPreferredSize().width + 2, ROW_H - 2));
		if (item.enabled)
		{
			b.addMouseListener(new MouseAdapter()
			{
				@Override public void mouseEntered(MouseEvent e) { b.setBackground(BTN_HOVER); }
				@Override public void mouseExited(MouseEvent e) { b.setBackground(BTN_BG); }
			});
			b.addActionListener(e -> item.action.run());
		}
		return b;
	}

	private static final Color PEEK_CREATE_BG = new Color(0x1D, 0x2A, 0x1F);
	private static final Color PEEK_CREATE_FG = new Color(0xBF, 0xE0, 0xBF);
	/** A hairline splitting the surface above from the permanent footer below. */
	private static final Color FOOTER_TOP_RULE = new Color(0x2A, 0x2A, 0x2C);

	private static final Color PEEK_SECTION_BG = new Color(0x1E, 0x26, 0x30);
	private static final Color PEEK_SECTION_FG = new Color(0xAF, 0xC8, 0xE6);

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

	private void styleCreateButton(JButton b, Color bg, Color fg)
	{
		b.setFocusPainted(false);
		b.setBorderPainted(false);
		b.setBackground(bg);
		b.setForeground(fg);
		b.setFont(b.getFont().deriveFont(Font.BOLD, 12f));
		b.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		b.setPreferredSize(new Dimension(0, PEEK_H));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		Color hover = bg.brighter();
		b.addMouseListener(new MouseAdapter()
		{
			@Override public void mouseEntered(MouseEvent e) { b.setBackground(hover); }
			@Override public void mouseExited(MouseEvent e) { b.setBackground(bg); }
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
			// Wrapped strips have a real preferred height, but WrapLayout can
			// only compute it if the row already HAS a width - an unsized row
			// falls back to Integer.MAX_VALUE and reports a single row, which
			// is exactly how the extra chips got clipped. Seed the width from
			// the dock before asking.
			int stripW = getWidth() - 8;
			if (stripW > 0)
			{
				topRow.setSize(stripW, 1);
				bottomRow.setSize(stripW, 1);
			}
			surface = topRow.getPreferredSize().height
				+ bottomRow.getPreferredSize().height + 6;
			// The optional full-width lead button (MULTI "Deselect (N)") sits above
			// the two strips; count its height only while it is showing.
			if (leadHost.isVisible())
			{
				surface += leadHost.getPreferredSize().height;
			}
		}
		// The grab handle sits above the surface while expanded (Task 8).
		if (!collapsed)
		{
			surface += grabHandle.getPreferredSize().height;
		}
		// The permanent footer is always counted; the surface only adds height
		// while expanded.
		int footerH = footerRow.getPreferredSize().height;
		int h = footerH + (collapsed ? 0 : surface);
		return new Dimension(super.getPreferredSize().width, h);
	}

	private static final int HANDLE_H = 12;
	private static final int DISMISS_DRAG_THRESHOLD = 24;
	private static final Color HANDLE_COLOR = new Color(0x6A, 0x6A, 0x70);
	private static final Color HANDLE_HOVER = new Color(0x9A, 0x9A, 0xA2);

	/**
	 * A short centered horizontal bar at the top of the expanded surface (Task 8)
	 * signalling the dock is dismissable. Dragging it DOWN past a small threshold
	 * collapses the surface; a plain CLICK is the no-drag fallback. Both route
	 * through {@link #triggerDismiss()} so the panel can also clear the surface
	 * drivers (selection / section / create nav), resting the dock at the footer
	 * from any state.
	 */
	private final class GrabHandle extends JPanel
	{
		private int pressY = -1;
		private boolean fired = false;
		private boolean hover = false;

		GrabHandle()
		{
			// Opaque: every pixel inside the dock must be painted by an opaque
			// component this pass, so nothing behind (an optional goal's diagonal
			// hatch) can survive in the handle band.
			setOpaque(true);
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			Dimension d = new Dimension(0, HANDLE_H);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(new Dimension(Integer.MAX_VALUE, HANDLE_H));
			setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
			setToolTipText("Drag down or click to dismiss");
			MouseAdapter ma = new MouseAdapter()
			{
				@Override public void mousePressed(MouseEvent e)
				{
					pressY = e.getYOnScreen();
					fired = false;
				}

				@Override public void mouseDragged(MouseEvent e)
				{
					if (!fired && pressY >= 0
						&& e.getYOnScreen() - pressY > DISMISS_DRAG_THRESHOLD)
					{
						fired = true;
						triggerDismiss();
					}
				}

				@Override public void mouseReleased(MouseEvent e)
				{
					pressY = -1;
				}

				@Override public void mouseClicked(MouseEvent e)
				{
					// No-drag fallback: a click also collapses.
					if (!fired)
					{
						triggerDismiss();
					}
				}

				@Override public void mouseEntered(MouseEvent e)
				{
					hover = true;
					repaint();
				}

				@Override public void mouseExited(MouseEvent e)
				{
					hover = false;
					repaint();
				}
			};
			addMouseListener(ma);
			addMouseMotionListener(ma);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
				int w = 36;
				int barH = 4;
				int x = (getWidth() - w) / 2;
				int y = (getHeight() - barH) / 2;
				g2.setColor(hover ? HANDLE_HOVER : HANDLE_COLOR);
				g2.fillRoundRect(x, y, w, barH, barH, barH);
			}
			finally
			{
				g2.dispose();
			}
		}
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
