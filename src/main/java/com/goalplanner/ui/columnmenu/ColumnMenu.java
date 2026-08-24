package com.goalplanner.ui.columnmenu;

import com.goalplanner.ui.PanelFonts;
import net.runelite.client.ui.ColorScheme;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JWindow;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.Icon;

/**
 * Click-driven, hover-stable replacement for cascading {@link javax.swing.JPopupMenu}.
 *
 * <p>Renders the menu tree as a single column inside a non-focusable
 * {@link JWindow}. Drilling into a submenu replaces the current column
 * with the submenu's items and adds a "← &lt;parent&gt;" back row at
 * the top. Hitting that row pops back to the previous level. Clicking
 * a leaf runs the action and dismisses. Clicking outside dismisses.
 *
 * <p>Window is non-focusable so the host application (RuneLite) keeps
 * keyboard input - important for in-game gameplay.
 */
public final class ColumnMenu
{
	private static final int MIN_COLUMN_WIDTH = 180;
	private static final int MAX_COLUMN_WIDTH = 380;
	private static final int ROW_HEIGHT = 24;
	private static final int SEP_HEIGHT = 5;
	private static final int CHECK_GUTTER = 16; // left column reserved for the selection dot
	private static final int DOT_SIZE = 6;
	private static final int COLUMN_PADDING = 8; // 4 top + 4 bottom
	private static final int MAX_COLUMN_HEIGHT = 480;

	/**
	 * One width for the whole menu - sized to the widest label across ALL levels
	 * (computed up front in {@link #show}). This keeps deep submenus from
	 * clipping their text, and keeps the width consistent as the user drills in
	 * and back out. Clamped to {@link #MIN_COLUMN_WIDTH}..{@link #MAX_COLUMN_WIDTH}
	 * so a very long label can't push the column off-screen.
	 */
	private int columnWidth = MIN_COLUMN_WIDTH;


	private static final Color BG = ColorScheme.DARK_GRAY_COLOR;
	private static final Color BG_HOVER = brighten(BG, 18);
	private static final Color FG = new Color(0xE0, 0xE0, 0xE0);
	private static final Color FG_DIM = new Color(0xA0, 0xA0, 0xA0);
	private static final Color FG_DISABLED = new Color(0x80, 0x80, 0x80);
	// Lighter than BG so divider lines are actually visible against the
	// dark column. Using 0x40 happened to match DARK_GRAY_COLOR ~exactly,
	// which is why separators looked missing in the prototype.
	private static final Color SEP = new Color(0x60, 0x60, 0x60);
	private static final Color BORDER = new Color(0x20, 0x20, 0x20);

	private final JWindow window;
	private final JPanel root;
	private final Window ownerWindow;
	private final Deque<Frame> stack = new ArrayDeque<>();
	private AWTEventListener globalListener;

	/** A drill-down level: which submenu items to render and what to label
	 *  the back row when navigating up from here. */
	private static final class Frame
	{
		final String parentLabel; // null for root
		final List<MenuNode> items;
		Frame(String parentLabel, List<MenuNode> items)
		{
			this.parentLabel = parentLabel;
			this.items = items;
		}
	}

	public static void show(Component anchor, int x, int y, List<MenuNode> rootItems)
	{
		ColumnMenu m = new ColumnMenu(anchor);
		m.columnWidth = m.computeColumnWidth(rootItems);
		m.stack.push(new Frame(null, rootItems));
		m.renderCurrent();
		m.position(anchor, x, y);
		m.window.setVisible(true);
		m.attachGlobalListeners();
	}

	private ColumnMenu(Component anchor)
	{
		Window owner = SwingUtilities.getWindowAncestor(anchor);
		this.ownerWindow = owner;
		window = new JWindow(owner);
		window.setFocusableWindowState(false);
		window.setAlwaysOnTop(true);
		root = new JPanel(new BorderLayout());
		root.setBackground(BG);
		root.setBorder(BorderFactory.createLineBorder(BORDER, 1));
		window.setContentPane(root);
	}

	/**
	 * Width for every column = the widest label anywhere in the tree (so deep
	 * submenus don't clip their text), plus padding for the row border and the
	 * leading/trailing arrow column, clamped to [MIN, MAX] and to the owner
	 * window so a long label can't push the column off-screen.
	 */
	private int computeColumnWidth(List<MenuNode> rootItems)
	{
		FontMetrics fm = new JLabel().getFontMetrics(PanelFonts.derive(Font.PLAIN, 13f));
		int widest = maxLabelWidth(rootItems, fm);
		int needed = widest + 20 + 26; // 10+10 row border + ~26 arrow column + slack
		int cap = MAX_COLUMN_WIDTH;
		if (ownerWindow != null && ownerWindow.getWidth() > 0)
		{
			cap = Math.min(cap, ownerWindow.getWidth() - 24);
		}
		return Math.max(MIN_COLUMN_WIDTH, Math.min(needed, cap));
	}

	private int maxLabelWidth(List<MenuNode> items, FontMetrics fm)
	{
		int max = 0;
		for (MenuNode n : items)
		{
			if (n.separator) continue;
			if (n.label != null) max = Math.max(max, fm.stringWidth(n.label));
			if (!n.children.isEmpty()) max = Math.max(max, maxLabelWidth(n.children, fm));
		}
		return max;
	}

	/**
	 * Replace the visible column with the top-of-stack frame's items.
	 * If we're not at the root, prepend a "← &lt;parentLabel&gt;" row that
	 * pops back to the prior frame on click.
	 */
	private void renderCurrent()
	{
		root.removeAll();
		Frame frame = stack.peek();
		if (frame == null) return;

		JPanel inner = new JPanel();
		inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
		inner.setBackground(BG);
		inner.setBorder(new EmptyBorder(4, 0, 4, 0));

		int rowCount = 0;
		int sepCount = 0;
		boolean hasBack = stack.size() > 1;
		if (hasBack)
		{
			inner.add(buildBackRow(frame.parentLabel));
			rowCount++;
			inner.add(buildSeparator());
			sepCount++;
		}

		for (MenuNode node : frame.items)
		{
			if (node.separator)
			{
				inner.add(buildSeparator());
				sepCount++;
			}
			else
			{
				inner.add(buildRow(node));
				rowCount++;
			}
		}

		JScrollPane scroll = new JScrollPane(inner);
		scroll.setBorder(null);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		scroll.getViewport().setBackground(BG);
		scroll.setBackground(BG);

		// Compute exact height - sum of row heights + separator heights +
		// padding. Avoids the long empty tail under-content because we no
		// longer over-estimate via a flat ROW_HEIGHT count.
		int contentHeight = rowCount * ROW_HEIGHT + sepCount * SEP_HEIGHT + COLUMN_PADDING;
		int height = Math.min(contentHeight, MAX_COLUMN_HEIGHT);
		scroll.setPreferredSize(new Dimension(columnWidth, height));

		root.add(scroll, BorderLayout.CENTER);
		window.pack();
		repositionIfOffscreen();
	}

	private JSeparator buildSeparator()
	{
		JSeparator sep = new JSeparator(JSeparator.HORIZONTAL);
		sep.setForeground(SEP);
		sep.setBackground(SEP);
		sep.setPreferredSize(new Dimension(columnWidth, SEP_HEIGHT));
		sep.setMaximumSize(new Dimension(columnWidth, SEP_HEIGHT));
		sep.setBorder(new EmptyBorder(2, 0, 2, 0));
		return sep;
	}

	private JPanel buildBackRow(String parentLabel)
	{
		String label = parentLabel != null ? parentLabel : "Back";
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(BG);
		row.setBorder(new EmptyBorder(2, 10, 2, 10));
		row.setPreferredSize(new Dimension(columnWidth, ROW_HEIGHT));
		row.setMaximumSize(new Dimension(columnWidth, ROW_HEIGHT));

		// "<" matches the ">" submenu indicator - ASCII, font-friendly,
		// renders consistently on macOS where unicode arrows like ← can
		// fall back to colored emoji glyphs on default fonts.
		JLabel arrow = new JLabel("<");
		arrow.setForeground(FG_DIM);
		arrow.setFont(PanelFonts.derive(Font.BOLD, 13f));
		arrow.setBorder(new EmptyBorder(0, 0, 0, 6));
		row.add(arrow, BorderLayout.WEST);

		JLabel text = new JLabel(label);
		text.setForeground(FG_DIM);
		text.setFont(PanelFonts.derive(Font.PLAIN, 13f));
		row.add(text, BorderLayout.CENTER);

		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override public void mouseEntered(MouseEvent e) { row.setBackground(BG_HOVER); }
			@Override public void mouseExited(MouseEvent e) { row.setBackground(BG); }
			@Override public void mouseClicked(MouseEvent e)
			{
				if (e.getButton() != MouseEvent.BUTTON1) return;
				stack.pop();
				renderCurrent();
			}
		});
		return row;
	}

	private JPanel buildRow(MenuNode node)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(BG);
		row.setBorder(new EmptyBorder(2, 10, 2, 10));
		row.setPreferredSize(new Dimension(columnWidth, ROW_HEIGHT));
		row.setMaximumSize(new Dimension(columnWidth, ROW_HEIGHT));

		if (node.checkable)
		{
			// Left gutter - a dot on the selected row, blank on the others
			// (the gutter stays present on every checkable row so the labels
			// keep their alignment).
			JLabel mark = new JLabel(node.checked ? dotIcon(node.enabled ? FG : FG_DISABLED) : null);
			mark.setPreferredSize(new Dimension(CHECK_GUTTER, ROW_HEIGHT));
			row.add(mark, BorderLayout.WEST);
		}

		JLabel label = new JLabel(node.label);
		label.setForeground(node.enabled ? FG : FG_DISABLED);
		label.setFont(PanelFonts.derive(Font.PLAIN, 13f));
		row.add(label, BorderLayout.CENTER);

		if (node.isSubmenu())
		{
			JLabel arrow = new JLabel(">");
			arrow.setForeground(node.enabled ? FG_DIM : SEP);
			arrow.setFont(PanelFonts.derive(Font.BOLD, 13f));
			row.add(arrow, BorderLayout.EAST);
		}

		if (node.tooltip != null) row.setToolTipText(node.tooltip);

		if (node.enabled && !node.separator)
		{
			row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			row.addMouseListener(new MouseAdapter()
			{
				@Override public void mouseEntered(MouseEvent e) { row.setBackground(BG_HOVER); }
				@Override public void mouseExited(MouseEvent e) { row.setBackground(BG); }
				@Override public void mouseClicked(MouseEvent e)
				{
					if (e.getButton() != MouseEvent.BUTTON1) return;
					if (node.isLeaf())
					{
						if (node.keepOpen)
						{
							// Run the action in place; menu stays visible
							// so the user can click again. The underlying
							// goal state changes; the menu items don't
							// re-render in the prototype, so the click
							// just becomes a no-op once the action no
							// longer applies.
							node.action.run();
						}
						else
						{
							Runnable action = node.action;
							close();
							if (action != null) action.run();
						}
					}
					else if (node.isSubmenu())
					{
						stack.push(new Frame(node.label, node.children));
						renderCurrent();
					}
				}
			});
		}

		return row;
	}

	private void position(Component anchor, int x, int y)
	{
		Point screen = anchor.getLocationOnScreen();
		window.setLocation(screen.x + x, screen.y + y);
	}

	/**
	 * Keep the popup on the visible screen. Horizontally it slides left so the
	 * right edge stays on-screen. Vertically, if the menu would run off the
	 * bottom (e.g. you right-clicked near the bottom of a window docked at the
	 * bottom of the display), it FLIPS to grow upward from the click point
	 * instead of hanging off-screen. Uses the actual monitor's usable bounds
	 * (minus the dock/taskbar), not the client window, so it can't spill below
	 * the screen even when the client itself sits at the screen edge.
	 */
	private void repositionIfOffscreen()
	{
		Rectangle bounds = window.getBounds();
		int left;
		int top;
		int rightLimit;
		int bottomLimit;
		GraphicsConfiguration gc = window.getGraphicsConfiguration();
		if (gc != null)
		{
			Rectangle screen = gc.getBounds();
			Insets ins = Toolkit.getDefaultToolkit().getScreenInsets(gc);
			left = screen.x + ins.left;
			top = screen.y + ins.top;
			rightLimit = screen.x + screen.width - ins.right;
			bottomLimit = screen.y + screen.height - ins.bottom;
		}
		else
		{
			Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
			left = screen.x;
			top = screen.y;
			rightLimit = screen.x + screen.width;
			bottomLimit = screen.y + screen.height;
		}

		int x = bounds.x;
		int y = bounds.y;
		if (x + bounds.width > rightLimit)
		{
			x = Math.max(left, rightLimit - bounds.width);
		}
		if (y + bounds.height > bottomLimit)
		{
			// Flip up: the menu's bottom lands at the click point and it grows
			// upward. Clamp to the top if it's too tall to fit entirely above.
			y = bounds.y - bounds.height;
			if (y < top)
			{
				y = Math.max(top, bottomLimit - bounds.height);
			}
		}
		if (x != bounds.x || y != bounds.y) window.setLocation(x, y);
	}

	private void attachGlobalListeners()
	{
		globalListener = event -> {
			if (event instanceof MouseEvent)
			{
				MouseEvent me = (MouseEvent) event;
				if (me.getID() == MouseEvent.MOUSE_PRESSED)
				{
					Point p = me.getLocationOnScreen();
					if (!window.getBounds().contains(p)) close();
				}
			}
		};
		Toolkit.getDefaultToolkit().addAWTEventListener(
			globalListener, AWTEvent.MOUSE_EVENT_MASK);
	}

	private void close()
	{
		if (globalListener != null)
		{
			Toolkit.getDefaultToolkit().removeAWTEventListener(globalListener);
			globalListener = null;
		}
		window.dispose();
	}

	/** Small filled dot used to mark the selected row in a checkable group. */
	private static Icon dotIcon(Color color)
	{
		return new Icon()
		{
			@Override public int getIconWidth() { return DOT_SIZE; }
			@Override public int getIconHeight() { return DOT_SIZE; }
			@Override public void paintIcon(Component c, Graphics g, int x, int y)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(color);
				g2.fillOval(x, y, DOT_SIZE, DOT_SIZE);
				g2.dispose();
			}
		};
	}

	private static Color brighten(Color c, int amount)
	{
		return new Color(
			Math.min(255, c.getRed() + amount),
			Math.min(255, c.getGreen() + amount),
			Math.min(255, c.getBlue() + amount));
	}
}
