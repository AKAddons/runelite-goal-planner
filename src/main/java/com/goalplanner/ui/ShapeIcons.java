package com.goalplanner.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.AffineTransform;

/**
 * Programmatic Swing icons for the small UI glyphs we used to render with
 * Unicode characters (▲ ▼ ▶ ✕). Drawing the shapes via Graphics2D avoids the
 * font-fallback failure on macOS Tahoe (and any other OS/JDK combo where the
 * BMP-Symbols glyphs aren't in the default sans-serif fallback chain), and
 * scales cleanly at any DPI.
 *
 * Visual treatment is OSRS sprite-inspired: a four-tone bevel with a dark
 * outline, a base fill, a brighter top/left highlight, and a subtle drop
 * shadow underneath. It's chunky on purpose - feels at home next to RuneLite.
 */
public final class ShapeIcons
{
	private ShapeIcons() {}

	/** Solid upward-pointing triangle (OSRS-bevel styling). */
	public static Icon upTriangle(int size, Color color)
	{
		return new TriangleIcon(size, color, Direction.UP);
	}

	/** Solid downward-pointing triangle (OSRS-bevel styling). */
	public static Icon downTriangle(int size, Color color)
	{
		return new TriangleIcon(size, color, Direction.DOWN);
	}

	/** Solid right-pointing triangle (collapsed chevron). */
	public static Icon rightTriangle(int size, Color color)
	{
		return new TriangleIcon(size, color, Direction.RIGHT);
	}

	/** "Close" / X icon - beveled diagonals like an OSRS UI button. */
	public static Icon closeX(int size, Color color)
	{
		return new CloseIcon(size, color);
	}




	/** Curved undo arrow - left-facing hook. */
	public static Icon undoArrow(int size, Color color)
	{
		return new UndoArrow(size, color, true);
	}

	/** Curved redo arrow - right-facing hook (mirror of undo). */
	public static Icon redoArrow(int size, Color color)
	{
		return new UndoArrow(size, color, false);
	}

	/** Horizontal three-dot glyph for "more options" / overflow menus. */
	public static Icon moreDots(int size, Color color)
	{
		return new MoreDotsIcon(size, color);
	}

	/** Empty checkbox outline - "select all" affordance. */
	public static Icon checkboxEmpty(int size, Color color)
	{
		return new CheckboxIcon(size, color, false);
	}

	/** Ticked checkbox - "unselect all" affordance (everything already selected). */
	public static Icon checkboxChecked(int size, Color color)
	{
		return new CheckboxIcon(size, color, true);
	}

	/** Warning triangle with an exclamation - the "blocked by prereqs" badge. */
	public static Icon warning(int size, Color color)
	{
		return new WarningIcon(size, color);
	}

	private enum Direction { UP, DOWN, RIGHT }

	// ----- color helpers -----

	/** Darken a color by a fixed amount, clamped at 0. Used for outlines/shadows. */
	private static Color darken(Color c, int amount)
	{
		return new Color(
			Math.max(0, c.getRed() - amount),
			Math.max(0, c.getGreen() - amount),
			Math.max(0, c.getBlue() - amount),
			c.getAlpha()
		);
	}

	/** Brighten a color by a fixed amount, clamped at 255. Used for highlights. */
	private static Color lighten(Color c, int amount)
	{
		return new Color(
			Math.min(255, c.getRed() + amount),
			Math.min(255, c.getGreen() + amount),
			Math.min(255, c.getBlue() + amount),
			c.getAlpha()
		);
	}

	private static final class TriangleIcon implements Icon
	{
		private final int size;
		private final Color color;
		private final Direction dir;

		TriangleIcon(int size, Color color, Direction dir)
		{
			this.size = size;
			this.color = color;
			this.dir = dir;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

				Path2D body = trianglePath(x, y, size, dir);

				// Drop shadow - same shape, offset 1px down/right, semi-transparent
				Path2D shadow = trianglePath(x + 1, y + 1, size, dir);
				g2.setColor(new Color(0, 0, 0, 110));
				g2.fill(shadow);

				// Base fill
				g2.setColor(color);
				g2.fill(body);

				// Top/leading-edge highlight - partial stroke along the brightest edge
				g2.setColor(lighten(color, 60));
				g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				drawHighlightEdge(g2, x, y, size, dir);

				// Outline - dark border for chunky OSRS feel
				g2.setColor(darken(color, 90));
				g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.draw(body);
			}
			finally
			{
				g2.dispose();
			}
		}

		private static Path2D trianglePath(int x, int y, int size, Direction dir)
		{
			Path2D p = new Path2D.Float();
			switch (dir)
			{
				case UP:
					p.moveTo(x + size / 2.0, y);
					p.lineTo(x + size, y + size);
					p.lineTo(x, y + size);
					break;
				case DOWN:
					p.moveTo(x, y);
					p.lineTo(x + size, y);
					p.lineTo(x + size / 2.0, y + size);
					break;
				case RIGHT:
					p.moveTo(x, y);
					p.lineTo(x + size, y + size / 2.0);
					p.lineTo(x, y + size);
					break;
			}
			p.closePath();
			return p;
		}

		private static void drawHighlightEdge(Graphics2D g2, int x, int y, int size, Direction dir)
		{
			switch (dir)
			{
				case UP:
					// Left slope
					g2.drawLine(x + size / 2, y, x, y + size);
					break;
				case DOWN:
					// Top edge
					g2.drawLine(x, y, x + size, y);
					break;
				case RIGHT:
					// Top slope
					g2.drawLine(x, y, x + size, y + size / 2);
					break;
			}
		}

		@Override public int getIconWidth() { return size + 1; }
		@Override public int getIconHeight() { return size + 1; }
	}

	private static final class MinusIcon implements Icon
	{
		private final int size;
		private final Color color;

		MinusIcon(int size, Color color)
		{
			this.size = size;
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int pad = 1;
				int x1 = x + pad, x2 = x + size - pad;
				int cy = y + size / 2;

				// Drop shadow
				g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(new Color(0, 0, 0, 130));
				g2.drawLine(x1 + 1, cy + 1, x2 + 1, cy + 1);

				// Outline (thicker dark stroke)
				g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(darken(color, 100));
				g2.drawLine(x1, cy, x2, cy);

				// Base color (slightly thinner so the dark outline shows)
				g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(color);
				g2.drawLine(x1, cy, x2, cy);

				// Highlight along the upper edge of the bar
				g2.setStroke(new BasicStroke(0.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(lighten(color, 80));
				g2.drawLine(x1, cy - 1, x1 + 2, cy - 1);
			}
			finally
			{
				g2.dispose();
			}
		}

		@Override public int getIconWidth() { return size + 1; }
		@Override public int getIconHeight() { return size + 1; }
	}

	private static final class PlusIcon implements Icon
	{
		private final int size;
		private final Color color;

		PlusIcon(int size, Color color)
		{
			this.size = size;
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int pad = 1;
				int x1 = x + pad, y1 = y + pad;
				int x2 = x + size - pad, y2 = y + size - pad;
				int cx = x + size / 2;
				int cy = y + size / 2;

				// Drop shadow
				g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(new Color(0, 0, 0, 130));
				g2.drawLine(x1 + 1, cy + 1, x2 + 1, cy + 1);
				g2.drawLine(cx + 1, y1 + 1, cx + 1, y2 + 1);

				// Outline (thicker dark stroke)
				g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(darken(color, 100));
				g2.drawLine(x1, cy, x2, cy);
				g2.drawLine(cx, y1, cx, y2);

				// Base color (slightly thinner so the dark outline shows)
				g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(color);
				g2.drawLine(x1, cy, x2, cy);
				g2.drawLine(cx, y1, cx, y2);

				// Highlight on the upper edges
				g2.setStroke(new BasicStroke(0.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(lighten(color, 80));
				g2.drawLine(x1, cy - 1, x1 + 2, cy - 1);
				g2.drawLine(cx - 1, y1, cx - 1, y1 + 2);
			}
			finally
			{
				g2.dispose();
			}
		}

		@Override public int getIconWidth() { return size + 1; }
		@Override public int getIconHeight() { return size + 1; }
	}

	private static final class CloseIcon implements Icon
	{
		private final int size;
		private final Color color;

		CloseIcon(int size, Color color)
		{
			this.size = size;
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int pad = 1;
				int x1 = x + pad, y1 = y + pad;
				int x2 = x + size - pad, y2 = y + size - pad;

				// Drop shadow - diagonals offset 1px
				g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(new Color(0, 0, 0, 130));
				g2.drawLine(x1 + 1, y1 + 1, x2 + 1, y2 + 1);
				g2.drawLine(x2 + 1, y1 + 1, x1 + 1, y2 + 1);

				// Outline - slightly thicker dark stroke
				g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(darken(color, 100));
				g2.drawLine(x1, y1, x2, y2);
				g2.drawLine(x2, y1, x1, y2);

				// Base color stroke on top - slightly thinner so the dark outline shows
				g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(color);
				g2.drawLine(x1, y1, x2, y2);
				g2.drawLine(x2, y1, x1, y2);

				// Highlight - thin bright stroke along the upper edges of the diagonals
				g2.setStroke(new BasicStroke(0.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(lighten(color, 80));
				g2.drawLine(x1, y1, x1 + 2, y1 + 2);
				g2.drawLine(x2, y1, x2 - 2, y1 + 2);
			}
			finally
			{
				g2.dispose();
			}
		}

		@Override public int getIconWidth() { return size + 1; }
		@Override public int getIconHeight() { return size + 1; }
	}

	private static final class UndoArrow implements Icon
	{
		private final int size;
		private final Color color;
		private final boolean mirrored;

		UndoArrow(int size, Color color, boolean mirrored)
		{
			this.size = size;
			this.color = color;
			this.mirrored = mirrored;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

				// Draw a 3/4 arc with an arrowhead at one end. The arc opens
				// downward and the head sits on the upper-left (undo) or
				// upper-right (redo, mirrored).
				int pad = 2;
				int s = size - pad;
				int cx = x + size / 2;
				int cy = y + size / 2 + 1;
				int r = s / 2;

				if (mirrored)
				{
					// Flip horizontally around the center
					AffineTransform at = g2.getTransform();
					g2.translate(2 * cx, 0);
					g2.scale(-1, 1);
				}

				// Drop shadow arc
				g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(new Color(0, 0, 0, 110));
				g2.drawArc(cx - r + 1, cy - r + 1, r * 2, r * 2, 60, 240);

				// Outline arc
				g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(darken(color, 100));
				g2.drawArc(cx - r, cy - r, r * 2, r * 2, 60, 240);

				// Base color arc
				g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(color);
				g2.drawArc(cx - r, cy - r, r * 2, r * 2, 60, 240);

				// Arrowhead at the end of the arc (60° = upper-right of arc circle).
				// The arc starts at angle 60 (upper right) and sweeps 240° CCW,
				// ending at angle 300. We place the head at the START (60°).
				double startAngleRad = Math.toRadians(60);
				int hx = (int) Math.round(cx + r * Math.cos(startAngleRad));
				int hy = (int) Math.round(cy - r * Math.sin(startAngleRad));
				int headSize = Math.max(2, s / 4);
				Path2D head = new Path2D.Float();
				head.moveTo(hx, hy);
				head.lineTo(hx - headSize, hy);
				head.lineTo(hx, hy - headSize);
				head.closePath();
				g2.setColor(darken(color, 100));
				g2.fill(head);
				g2.setColor(color);
				g2.draw(head);
			}
			finally
			{
				g2.dispose();
			}
		}

		@Override public int getIconWidth() { return size + 2; }
		@Override public int getIconHeight() { return size + 2; }
	}

	private static final class TagIcon implements Icon
	{
		private final int size;
		private final Color color;

		TagIcon(int size, Color color)
		{
			this.size = size;
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

				// Tag silhouette: a quad with a triangular point on the left.
				// The eyelet hole is a small circle near the point.
				int pad = 1;
				int x0 = x + pad, y0 = y + pad;
				int s = size - pad * 2;
				int pointX = x0;
				int bodyLeft = x0 + s / 4;
				int right = x0 + s;
				int top = y0;
				int bottom = y0 + s;
				int midY = y0 + s / 2;

				Path2D body = new Path2D.Float();
				body.moveTo(pointX, midY);
				body.lineTo(bodyLeft, top);
				body.lineTo(right, top);
				body.lineTo(right, bottom);
				body.lineTo(bodyLeft, bottom);
				body.closePath();

				// Drop shadow
				Path2D shadow = new Path2D.Float();
				shadow.append(body.getPathIterator(AffineTransform.getTranslateInstance(1, 1)), false);
				g2.setColor(new Color(0, 0, 0, 110));
				g2.fill(shadow);

				// Base fill
				g2.setColor(color);
				g2.fill(body);

				// Outline
				g2.setColor(darken(color, 90));
				g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.draw(body);

				// Eyelet hole - small circle just inside the body near the point
				int holeR = Math.max(1, s / 6);
				int holeCx = bodyLeft + holeR + 1;
				int holeCy = midY;
				g2.setColor(darken(color, 90));
				g2.fillOval(holeCx - holeR, holeCy - holeR, holeR * 2, holeR * 2);
				g2.setColor(new Color(40, 40, 40));
				g2.fillOval(holeCx - holeR + 1, holeCy - holeR + 1, holeR * 2 - 2, holeR * 2 - 2);

				// Top-edge highlight
				g2.setColor(lighten(color, 60));
				g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.drawLine(bodyLeft + 1, top + 1, right - 1, top + 1);
			}
			finally
			{
				g2.dispose();
			}
		}

		@Override public int getIconWidth() { return size + 2; }
		@Override public int getIconHeight() { return size + 2; }
	}

	/** Three horizontal dots - universal "more options" glyph. */
	private static final class MoreDotsIcon implements Icon
	{
		private final int size;
		private final Color color;

		MoreDotsIcon(int size, Color color)
		{
			this.size = size;
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int dot = Math.max(2, size / 4);
				int cy = y + size / 2 - dot / 2;
				int span = size - dot;
				int cx1 = x;
				int cx2 = x + span / 2;
				int cx3 = x + span;

				// Drop shadow
				g2.setColor(new Color(0, 0, 0, 120));
				g2.fillOval(cx1 + 1, cy + 1, dot, dot);
				g2.fillOval(cx2 + 1, cy + 1, dot, dot);
				g2.fillOval(cx3 + 1, cy + 1, dot, dot);

				// Dots
				g2.setColor(color);
				g2.fillOval(cx1, cy, dot, dot);
				g2.fillOval(cx2, cy, dot, dot);
				g2.fillOval(cx3, cy, dot, dot);
			}
			finally
			{
				g2.dispose();
			}
		}

		@Override public int getIconWidth() { return size + 1; }
		@Override public int getIconHeight() { return size + 1; }
	}
	/**
	 * Square checkbox, empty or ticked - same four-tone bevel treatment as the
	 * other glyphs. Used as the section-header select-all/unselect-all toggle.
	 */
	private static final class CheckboxIcon implements Icon
	{
		private final int size;
		private final Color color;
		private final boolean checked;

		CheckboxIcon(int size, Color color, boolean checked)
		{
			this.size = size;
			this.color = color;
			this.checked = checked;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int pad = 1;
				int x1 = x + pad, y1 = y + pad;
				int box = size - 2 * pad;

				// Drop shadow
				g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(new Color(0, 0, 0, 110));
				g2.drawRect(x1 + 1, y1 + 1, box, box);

				// Outline
				g2.setColor(darken(color, 100));
				g2.drawRect(x1, y1, box, box);

				// Base color box
				g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(color);
				g2.drawRect(x1, y1, box, box);

				// Highlight along the top edge
				g2.setStroke(new BasicStroke(0.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(lighten(color, 80));
				g2.drawLine(x1 + 1, y1 + 1, x1 + box - 1, y1 + 1);

				if (checked)
				{
					// Tick: short down-stroke then long up-stroke, inset in the box.
					int tx1 = x1 + box / 5;
					int ty1 = y1 + box / 2;
					int tx2 = x1 + (int) (box * 0.42);
					int ty2 = y1 + box - box / 4;
					int tx3 = x1 + box - box / 6;
					int ty3 = y1 + box / 5;
					g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					g2.setColor(darken(color, 100));
					g2.drawLine(tx1 + 1, ty1 + 1, tx2 + 1, ty2 + 1);
					g2.drawLine(tx2 + 1, ty2 + 1, tx3 + 1, ty3 + 1);
					g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					g2.setColor(lighten(color, 60));
					g2.drawLine(tx1, ty1, tx2, ty2);
					g2.drawLine(tx2, ty2, tx3, ty3);
				}
			}
			finally
			{
				g2.dispose();
			}
		}

		@Override public int getIconWidth() { return size + 1; }
		@Override public int getIconHeight() { return size + 1; }
	}

	/** Rounded warning triangle with a beveled exclamation mark. */
	private static final class WarningIcon implements Icon
	{
		private final int size;
		private final Color color;

		WarningIcon(int size, Color color)
		{
			this.size = size;
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				Path2D tri = new Path2D.Float();
				tri.moveTo(x + size / 2.0, y);
				tri.lineTo(x + size, y + size);
				tri.lineTo(x, y + size);
				tri.closePath();

				// Drop shadow
				g2.setColor(new Color(0, 0, 0, 90));
				g2.translate(1, 1);
				g2.fill(tri);
				g2.translate(-1, -1);

				// Body + dark outline
				g2.setColor(color);
				g2.fill(tri);
				g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(darken(color, 110));
				g2.draw(tri);

				// Exclamation: short bar + dot, dark for contrast on the amber body.
				int cx = x + size / 2;
				g2.setColor(darken(color, 150));
				g2.setStroke(new BasicStroke(Math.max(1.2f, size / 7f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.drawLine(cx, y + (int) (size * 0.40), cx, y + (int) (size * 0.66));
				g2.fillOval(cx - 1, y + (int) (size * 0.74), 2, 2);
			}
			finally
			{
				g2.dispose();
			}
		}

		@Override public int getIconWidth() { return size + 2; }
		@Override public int getIconHeight() { return size + 2; }
	}
}
