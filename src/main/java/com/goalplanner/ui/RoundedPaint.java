package com.goalplanner.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.border.Border;

/**
 * The single source of rounded, antialiased corners across the action dock
 * (glam pass). Swing paints hard 90-degree rectangles for buttons, panels, and
 * borders; this utility replaces that with tasteful rounded corners rendered
 * with {@link RenderingHints#KEY_ANTIALIASING} on, so corners stay smooth at
 * every font scale.
 *
 * <p>One radius vocabulary keeps everything visually consistent: {@link #RADIUS}
 * for the small interactive elements (buttons, tiles, chips, pills, fields) and
 * {@link #SURFACE_RADIUS} for the larger surface cards. Callers should reuse
 * {@link RoundedButton}, {@link RoundedPanel}, and {@link #border} rather than
 * scattering their own {@code fillRoundRect} calls.
 */
public final class RoundedPaint
{
	/** Corner radius for the small interactive elements - buttons, tiles, chips,
	 *  pills, fields. Nicely rounded, not bubble-round. */
	public static final int RADIUS = 8;
	/** Corner radius for the larger surface cards (create / edit / section
	 *  surfaces and their indicator bars). */
	public static final int SURFACE_RADIUS = 11;

	private RoundedPaint()
	{
	}

	/** Fill an antialiased rounded rectangle of corner {@code radius} in
	 *  {@code color}. The graphics is not disposed (caller owns it). */
	public static void fill(Graphics2D g2, int x, int y, int w, int h, int radius, Color color)
	{
		if (color == null || w <= 0 || h <= 0)
		{
			return;
		}
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(color);
		g2.fillRoundRect(x, y, w, h, radius * 2, radius * 2);
	}

	/** Fill a rectangle whose TOP corners are rounded and bottom corners square -
	 *  for a full-bleed header bar that caps a card. Achieved by rounding a taller
	 *  rect whose rounded bottom falls below the visible bounds. */
	public static void fillTop(Graphics2D g2, int w, int h, int radius, Color color)
	{
		if (color == null || w <= 0 || h <= 0)
		{
			return;
		}
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(color);
		g2.fillRoundRect(0, 0, w, h + radius, radius * 2, radius * 2);
	}

	/** A reusable antialiased rounded-rectangle border: a hairline stroke in
	 *  {@code color} plus {@code padding} inner insets. Radius is the corner
	 *  radius; thickness is the stroke width. */
	public static Border border(Color color, int thickness, int radius, Insets padding)
	{
		return new RoundedBorder(color, thickness, radius, padding);
	}

	/**
	 * An antialiased rounded-rectangle {@link Border}. Reports {@code padding} as
	 * its insets (so it doubles as the component's inner padding), and strokes a
	 * rounded outline in {@code color}. A transparent color paints only padding
	 * (useful when a component wants rounded padding but no visible outline).
	 */
	public static final class RoundedBorder implements Border
	{
		private final Color color;
		private final int thickness;
		private final int radius;
		private final Insets padding;

		public RoundedBorder(Color color, int thickness, int radius, Insets padding)
		{
			this.color = color;
			this.thickness = Math.max(1, thickness);
			this.radius = radius;
			this.padding = padding != null ? padding : new Insets(0, 0, 0, 0);
		}

		@Override
		public Insets getBorderInsets(Component c)
		{
			return new Insets(padding.top, padding.left, padding.bottom, padding.right);
		}

		@Override
		public boolean isBorderOpaque()
		{
			return false;
		}

		@Override
		public void paintBorder(Component c, Graphics g, int x, int y, int width, int height)
		{
			if (color == null || color.getAlpha() == 0 || width <= 0 || height <= 0)
			{
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(color);
				g2.setStroke(new BasicStroke(thickness));
				float off = thickness / 2f;
				g2.draw(new RoundRectangle2D.Float(
					x + off, y + off,
					width - thickness, height - thickness,
					radius * 2, radius * 2));
			}
			finally
			{
				g2.dispose();
			}
		}
	}

	/**
	 * A {@link JButton} that paints an antialiased rounded background from its
	 * current {@code getBackground()} (so hover / disabled background swaps still
	 * work) instead of the L&amp;F's square fill. Content-area fill and opacity
	 * are turned off in the constructor so no square corners bleed through; keep
	 * them off in callers. An optional top accent stripe (for the create tiles)
	 * inherits the rounded top corners.
	 */
	public static class RoundedButton extends JButton
	{
		private final int radius;
		private Color topAccent = null;
		private int topAccentHeight = 0;

		public RoundedButton(String text)
		{
			this(text, RADIUS);
		}

		public RoundedButton(String text, int radius)
		{
			super(text);
			this.radius = radius;
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
		}

		/** Paint a full-width accent stripe of {@code height} px across the top,
		 *  clipped to the rounded shape so it keeps the rounded top corners. */
		public RoundedButton withTopAccent(Color accent, int height)
		{
			this.topAccent = accent;
			this.topAccentHeight = height;
			return this;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				int w = getWidth();
				int h = getHeight();
				fill(g2, 0, 0, w, h, radius, getBackground());
				if (topAccent != null && topAccentHeight > 0)
				{
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
						RenderingHints.VALUE_ANTIALIAS_ON);
					g2.clip(new RoundRectangle2D.Float(0, 0, w, h, radius * 2, radius * 2));
					g2.setColor(topAccent);
					g2.fillRect(0, 0, w, topAccentHeight);
				}
			}
			finally
			{
				g2.dispose();
			}
			super.paintComponent(g);
		}
	}

	/**
	 * A {@link JPanel} that paints an antialiased rounded background from its
	 * {@code getBackground()}. Kept non-opaque so the rounded corners reveal
	 * whatever is behind; set a background to draw a rounded card / row.
	 */
	public static class RoundedPanel extends JPanel
	{
		private final int radius;

		public RoundedPanel(LayoutManager layout, int radius)
		{
			super(layout);
			this.radius = radius;
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Color bg = getBackground();
			if (bg != null)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				try
				{
					fill(g2, 0, 0, getWidth(), getHeight(), radius, bg);
				}
				finally
				{
					g2.dispose();
				}
			}
			super.paintComponent(g);
		}
	}
}
