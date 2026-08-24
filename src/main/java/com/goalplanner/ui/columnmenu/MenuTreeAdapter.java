package com.goalplanner.ui.columnmenu;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSeparator;

/**
 * Converts an already-built {@link JPopupMenu} (or a {@link JMenu}
 * subtree) into a {@link MenuNode} tree the {@link ColumnMenu} can
 * render. Lets us reuse the existing buildBulkMenu construction
 * code without porting the menu-build logic to the new model.
 *
 * <p>Each leaf {@link JMenuItem}'s action listeners are wrapped in a
 * single Runnable that fires them with a synthesized ActionEvent -
 * preserves the existing menu's behavior verbatim.
 */
public final class MenuTreeAdapter
{
	/** Client-property key on a JMenuItem that, when set to Boolean.TRUE,
	 *  causes the adapter to produce a leaf node that doesn't dismiss the
	 *  menu when clicked. */
	public static final String KEEP_OPEN_PROPERTY = "columnmenu.keepOpen";

	private MenuTreeAdapter() {}

	public static List<MenuNode> fromPopup(JPopupMenu popup)
	{
		List<MenuNode> out = new ArrayList<>();
		for (int i = 0; i < popup.getComponentCount(); i++)
		{
			MenuNode n = fromComponent(popup.getComponent(i));
			if (n != null) out.add(n);
		}
		return out;
	}

	private static List<MenuNode> fromMenu(JMenu menu)
	{
		List<MenuNode> out = new ArrayList<>();
		for (Component c : menu.getMenuComponents())
		{
			MenuNode n = fromComponent(c);
			if (n != null) out.add(n);
		}
		return out;
	}

	private static MenuNode fromComponent(Component c)
	{
		if (c instanceof JPopupMenu.Separator || c instanceof JSeparator)
		{
			return MenuNode.separator();
		}
		if (c instanceof JMenu)
		{
			JMenu menu = (JMenu) c;
			MenuNode node = MenuNode.submenu(menu.getText(), fromMenu(menu));
			return menu.getToolTipText() != null
				? node.withTooltip(menu.getToolTipText()) : node;
		}
		if (c instanceof JMenuItem)
		{
			JMenuItem item = (JMenuItem) c;
			if (!item.isEnabled())
			{
				MenuNode disabled = MenuNode.leafDisabled(item.getText());
				return item.getToolTipText() != null
					? disabled.withTooltip(item.getToolTipText()) : disabled;
			}
			ActionListener[] listeners = item.getActionListeners();
			Runnable action = () -> {
				ActionEvent ev = new ActionEvent(
					item, ActionEvent.ACTION_PERFORMED, item.getActionCommand());
				for (ActionListener l : listeners) l.actionPerformed(ev);
			};
			// Items can opt out of dismiss-on-click via this client property -
			// useful for repeat-friendly actions like Move Up / Move Down.
			Object keepOpen = item.getClientProperty(KEEP_OPEN_PROPERTY);
			MenuNode leaf = Boolean.TRUE.equals(keepOpen)
				? MenuNode.leafStaysOpen(item.getText(), action)
				: MenuNode.leaf(item.getText(), action);
			// Radio / checkbox items carry a selected state - render as a
			// checkable row (a left dot marks the selected one).
			if (item instanceof JRadioButtonMenuItem
				|| item instanceof JCheckBoxMenuItem)
			{
				leaf = leaf.withCheck(true, item.isSelected());
			}
			if (item.getToolTipText() != null)
			{
				leaf = leaf.withTooltip(item.getToolTipText());
			}
			return leaf;
		}
		return null;
	}
}
