package com.goalplanner.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * A {@link FlowLayout} that reports a real preferred HEIGHT once its rows wrap,
 * so a container can grow to fit instead of clipping.
 *
 * <p>Extracted from GoalPanel so the action dock can wrap its chip strips too:
 * a horizontally scrolling strip on a 225px panel hides actions behind a pan
 * gesture with no scrollbar and no affordance.
 */
/** A {@link FlowLayout} that reports a wrapped preferred size, so chips flow
 *  onto multiple lines and grow the dock vertically instead of overflowing a
 *  fixed-width, horizontal-scroll-suppressed surface. */
public final class WrapLayout extends FlowLayout
{
	public WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }

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
			Insets insets = target.getInsets();
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
