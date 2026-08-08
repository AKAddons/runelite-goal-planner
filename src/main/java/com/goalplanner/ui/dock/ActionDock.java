package com.goalplanner.ui.dock;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
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
	private static final int HANDLE_H = 11;

	private final JPanel content = new JPanel();
	private final JPanel topRow = strip();
	private final JPanel bottomRow = strip();
	private final JButton collapseBtn = new JButton();
	private boolean collapsed = false;
	private Rows current = new Rows(null, List.of(), List.of());

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

		styleCollapse();
		add(collapseBtn, BorderLayout.NORTH);
		add(content, BorderLayout.CENTER);
		applyCollapsed();
	}

	/** Replace the dock's buttons. Height never changes. */
	public void setRows(Rows rows)
	{
		this.current = rows;
		rebuildStrip(topRow, rows.hint, rows.top);
		rebuildStrip(bottomRow, null, rows.bottom);
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

	private void rebuildStrip(JPanel row, String hint, List<Item> items)
	{
		row.removeAll();
		if (hint != null && !hint.isEmpty())
		{
			JLabel l = new JLabel(hint);
			l.setForeground(new java.awt.Color(160, 160, 160));
			row.add(l);
		}
		for (Item item : items)
		{
			JButton b = new JButton(item.label);
			b.setToolTipText(item.tooltip);
			b.setEnabled(item.enabled);
			b.setFocusPainted(false);
			b.setMargin(new java.awt.Insets(2, 7, 2, 7));
			b.setPreferredSize(new Dimension(
				b.getPreferredSize().width, ROW_H - 4));
			b.addActionListener(e -> item.action.run());
			row.add(b);
		}
	}

	private void styleCollapse()
	{
		collapseBtn.setFocusPainted(false);
		collapseBtn.setBorderPainted(false);
		collapseBtn.setContentAreaFilled(false);
		collapseBtn.setPreferredSize(new Dimension(0, HANDLE_H));
		collapseBtn.addActionListener(e -> {
			collapsed = !collapsed;
			applyCollapsed();
		});
	}

	private void applyCollapsed()
	{
		content.setVisible(!collapsed);
		collapseBtn.setIcon(collapsed
			? com.goalplanner.ui.ShapeIcons.upTriangle(7,
				new java.awt.Color(0xB4, 0xB4, 0xDC))
			: com.goalplanner.ui.ShapeIcons.downTriangle(7,
				new java.awt.Color(0xB4, 0xB4, 0xDC)));
		collapseBtn.setToolTipText(collapsed ? "Show actions" : "Hide actions");
		revalidate();
		repaint();
	}

	@Override
	public Dimension getPreferredSize()
	{
		int h = HANDLE_H + (collapsed ? 0 : 2 * (ROW_H + 4) + 2);
		return new Dimension(super.getPreferredSize().width, h);
	}
}
