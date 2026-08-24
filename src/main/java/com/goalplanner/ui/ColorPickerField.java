package com.goalplanner.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.LineBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.Graphics;
import java.awt.Insets;

/**
 * Reusable color picker widget for section / goal / tag color overrides.
 *
 * <p>Layout:
 * <ul>
 *   <li>A small preview swatch + "Current" label at the top</li>
 *   <li>A grid of curated preset swatches - click to select</li>
 *   <li>A "More..." button opening a full {@link JColorChooser}</li>
 *   <li>A "Reset to default" button that clears the override</li>
 * </ul>
 *
 * <p>State: either a concrete RGB int, or -1 meaning "no override".
 * {@link #getSelectedRgb()} returns the current value.
 */
public class ColorPickerField extends JPanel
{
	/** Curated palette tuned for the RuneLite dark sidebar. 12 swatches, 4×3 grid. */
	public static final Color[] PRESETS = new Color[]{
		new Color(0xE74C3C), // red
		new Color(0xE67E22), // orange
		new Color(0xF1C40F), // yellow
		new Color(0x2ECC71), // green
		new Color(0x1ABC9C), // teal
		new Color(0x3498DB), // blue
		new Color(0x9B59B6), // purple
		new Color(0xE91E63), // pink
		new Color(0x8B4513), // brown
		new Color(0x607D8B), // slate
		new Color(0x455A64), // dark slate
		new Color(0x2C3E50), // midnight
	};

	private static final int SWATCH = 22;
	private static final Color DEFAULT_BORDER = new Color(80, 80, 80);
	private static final Color SELECTED_BORDER = Color.WHITE;

	/** Current selection: -1 means "no override / use default". */
	private int selectedRgb;
	/** The underlying default color for this item - shown as the preview when
	 *  selectedRgb == -1, and used as the reset target. */
	private final int defaultRgb;

	private final JPanel previewSwatch;
	private final JLabel previewLabel;

	/** Backward-compatible constructor; uses a neutral fallback for default. */
	public ColorPickerField(int initialRgb)
	{
		this(initialRgb, 0x3C3C3C);
	}

	public ColorPickerField(int initialRgb, int defaultRgb)
	{
		this.selectedRgb = initialRgb;
		this.defaultRgb = defaultRgb;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);

		// --- Preview row -------------------------------------------------
		JPanel previewRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		previewRow.setOpaque(false);
		previewSwatch = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				// Paint the fill ourselves so FlatLaf/UI delegates don't override it.
				int rgb = selectedRgb >= 0 ? selectedRgb : defaultRgb;
				g.setColor(new Color(rgb));
				g.fillRect(0, 0, getWidth(), getHeight());
			}
		};
		previewSwatch.setOpaque(false);
		previewSwatch.setPreferredSize(new Dimension(SWATCH, SWATCH));
		previewSwatch.setBorder(new LineBorder(DEFAULT_BORDER, 1));
		previewLabel = new JLabel();
		previewRow.add(previewSwatch);
		previewRow.add(previewLabel);
		add(previewRow);

		add(Box.createVerticalStrut(4));

		// --- Swatch grid -------------------------------------------------
		JPanel grid = new JPanel(new GridLayout(3, 4, 4, 4));
		grid.setOpaque(false);
		for (Color preset : PRESETS)
		{
			grid.add(makeSwatchButton(preset));
		}
		add(grid);

		add(Box.createVerticalStrut(6));

		// --- Action buttons ---------------------------------------------
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		actions.setOpaque(false);

		JButton more = new JButton("More...");
		more.setMargin(new Insets(2, 6, 2, 6));
		more.addActionListener(e -> {
			Color initial = selectedRgb >= 0 ? new Color(selectedRgb) : Color.GRAY;
			Color picked = JColorChooser.showDialog(this, "Pick a color", initial);
			if (picked != null) setSelectedRgb(rgbFromColor(picked));
		});
		actions.add(more);

		JButton reset = new JButton("Reset to default");
		reset.setMargin(new Insets(2, 6, 2, 6));
		reset.addActionListener(e -> setSelectedRgb(-1));
		actions.add(reset);

		add(actions);

		refreshPreview();
	}

	/** @return selected RGB packed as 0xRRGGBB, or -1 for "no override". */
	public int getSelectedRgb()
	{
		return selectedRgb;
	}

	public void setSelectedRgb(int rgb)
	{
		this.selectedRgb = rgb < 0 ? -1 : (rgb & 0xFFFFFF);
		refreshPreview();
	}

	private void refreshPreview()
	{
		previewLabel.setText(selectedRgb >= 0
			? String.format("Color: #%06X", selectedRgb)
			: String.format("Color: default (#%06X)", defaultRgb));
		previewSwatch.repaint();
	}

	private JPanel makeSwatchButton(final Color color)
	{
		JPanel swatch = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				// Paint the fill ourselves so FlatLaf/UI delegates don't override it.
				g.setColor(color);
				g.fillRect(0, 0, getWidth(), getHeight());
			}
		};
		swatch.setOpaque(false);
		swatch.setPreferredSize(new Dimension(SWATCH, SWATCH));
		swatch.setBorder(new LineBorder(DEFAULT_BORDER, 1));
		swatch.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		swatch.setToolTipText(String.format("#%06X", rgbFromColor(color)));
		swatch.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				setSelectedRgb(rgbFromColor(color));
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				swatch.setBorder(new LineBorder(SELECTED_BORDER, 1));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				swatch.setBorder(new LineBorder(DEFAULT_BORDER, 1));
			}
		});
		return swatch;
	}

	private static int rgbFromColor(Color c)
	{
		return (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
	}
}
