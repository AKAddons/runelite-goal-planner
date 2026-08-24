package com.goalplanner.ui;

import com.goalplanner.api.SectionView;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.Image;
import java.util.function.BooleanSupplier;
import javax.swing.ImageIcon;

/**
 * A row rendered as a section header in the goal panel.
 * The chevron on the left edge is the collapse target (click it to
 * collapse/expand); its shape indicates the current state (down = expanded,
 * right = collapsed). Clicking anywhere else on the row SELECTS the section, so
 * the action dock shows that section's actions (mirrors clicking a goal card).
 * A selected header paints a highlight border. When the section has goals, a
 * checkbox glyph on the right edge selects/unselects all of them in one click
 * (mirrors the context menu's "Select All in Section"); it neither selects nor
 * collapses the section.
 */
public class SectionHeaderRow extends JPanel
{
	private static final Color BORDER_COLOR = new Color(60, 60, 60);
	private static final Color TEXT_COLOR = new Color(200, 200, 200);
	private static final Color CHEVRON_COLOR = new Color(160, 160, 160);
	/** Selection highlight stroke - matches GoalCard's white selection outline. */
	private static final Color SELECT_COLOR = Color.WHITE;
	private static final int ROW_HEIGHT = 22;
	private static final int CHEVRON_WIDTH = 14;
	private static final int CHECKBOX_SIZE = 10;

	/**
	 * Multiplier applied to each RGB channel of a user-picked section color so
	 * the header always has enough darkness to contrast against the existing
	 * light-grey label text. Lower = darker; 0.55 keeps the hue obvious while
	 * guaranteeing WCAG-ish contrast with 200-grey text.
	 */
	private static final double DARKEN_FACTOR = 0.55;

	/** Whether this header has a user color override; if so, paint a darkened fill. */
	private final boolean hasColor;
	private final Color darkenedFill;

	/** Select-all toggle state - null when the section has no goals (no glyph shown). */
	private final BooleanSupplier allSelectedState;
	private final JLabel selectToggle;

	/** This header's section id, so the panel can match it against the current
	 *  section selection when refreshing the highlight without a full rebuild. */
	private final String sectionId;
	/** Whether this section is the currently selected one (paints a highlight). */
	private boolean selected;

	public SectionHeaderRow(SectionView section, int goalCount, Runnable onToggle)
	{
		this(section, goalCount, onToggle, null, null, null, false);
	}

	/**
	 * @param onSelect runs when the row body (anywhere but the chevron and the
	 *                 select-all toggle) is left-clicked - selects the section so
	 *                 the dock shows its actions. Null leaves the row unselectable.
	 * @param allSelectedState true when every goal in the section is currently
	 *                         selected (drives the glyph + tooltip); null hides
	 *                         the select-all toggle entirely.
	 * @param onToggleSelectAll runs on glyph click - select all when not all
	 *                          selected, unselect all otherwise.
	 */
	public SectionHeaderRow(SectionView section, int goalCount, Runnable onToggle,
		Runnable onSelect,
		BooleanSupplier allSelectedState, Runnable onToggleSelectAll,
		boolean allCompleted)
	{
		this.sectionId = section.id;
		setLayout(new BorderLayout());

		// User-set color fills the header face with a darkened version of the
		// picked color so the existing light-grey label text always contrasts.
		// Default sections stay transparent with a 1px neutral underline.
		this.hasColor = section.colorOverridden;
		if (hasColor)
		{
			Color picked = new Color(section.colorRgb);
			this.darkenedFill = new Color(
				(int) (picked.getRed() * DARKEN_FACTOR),
				(int) (picked.getGreen() * DARKEN_FACTOR),
				(int) (picked.getBlue() * DARKEN_FACTOR));
			setOpaque(false); // paintComponent draws the fill manually
		}
		else
		{
			this.darkenedFill = null;
			setOpaque(false);
		}
		setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR),
			new EmptyBorder(4, 8, 4, 8)
		));
		// Match GoalCard's sizing idiom so BoxLayout treats the row identically:
		// preferred width 0 + max width MAX_VALUE + default CENTER alignment lets the
		// row stretch to the full panel width.
		setPreferredSize(new Dimension(0, ROW_HEIGHT));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
		setAlignmentX(Component.CENTER_ALIGNMENT);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		// Programmatic shape icon avoids font-fallback failure on macOS Tahoe
		// where ▶/▼ Unicode glyphs render as missing-glyph placeholders.
		JLabel chevron = new JLabel(section.collapsed
			? ShapeIcons.rightTriangle(8, CHEVRON_COLOR)
			: ShapeIcons.downTriangle(8, CHEVRON_COLOR));
		chevron.setPreferredSize(new Dimension(CHEVRON_WIDTH, ROW_HEIGHT));
		chevron.setToolTipText(section.collapsed ? "Expand section" : "Collapse section");
		// The chevron is the collapse target. It has its own listener, so Swing
		// routes clicks on it here and NOT to the row's select listener below - the
		// two are cleanly separated (chevron = collapse, row body = select).
		chevron.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getButton() == MouseEvent.BUTTON1 && onToggle != null)
				{
					onToggle.run();
				}
			}
		});

		JLabel nameLabel = new JLabel(section.name.toUpperCase(), SwingConstants.CENTER);
		nameLabel.setForeground(TEXT_COLOR);
		nameLabel.setFont(PanelFonts.derive(Font.BOLD, 10f));

		// Right edge: the select-all/unselect-all checkbox when the section has
		// goals, otherwise an empty spacer of equal width so the centered name
		// stays visually centered despite the chevron on the left edge.
		this.allSelectedState = allSelectedState;
		// Right edge: an optional all-complete badge to the LEFT of the select-all
		// toggle. BorderLayout (not FlowLayout) so children stretch to the row's
		// bordered content height instead of being clipped at their preferred size.
		JPanel eastPanel = new JPanel(new BorderLayout(2, 0));
		eastPanel.setOpaque(false);

		// Every goal in a non-empty section is complete -> show the plugin's
		// completion icon as a section badge, scaled to fit the short header row.
		if (allCompleted && goalCount > 0)
		{
			ImageIcon src = GoalCard.completionIcon();
			if (src != null)
			{
				int px = ROW_HEIGHT - 10; // fit within the 4px-bordered content height
				Image scaled = src.getImage().getScaledInstance(px, px, Image.SCALE_SMOOTH);
				JLabel doneBadge = new JLabel(new ImageIcon(scaled));
				doneBadge.setToolTipText("All goals in this section are complete");
				eastPanel.add(doneBadge, BorderLayout.WEST);
			}
		}

		if (allSelectedState != null && onToggleSelectAll != null && goalCount > 0)
		{
			this.selectToggle = new JLabel();
			// A few px wider than the chevron column: the glyph is a small
			// target, so give the hit area some slack.
			selectToggle.setPreferredSize(new Dimension(CHEVRON_WIDTH + 6, ROW_HEIGHT));
			selectToggle.setHorizontalAlignment(SwingConstants.CENTER);
			refreshSelectToggle();
			selectToggle.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					// mousePressed, not mouseClicked: clicked only fires when
					// press and release land on the same pixel, so rapid
					// repeat clicks (which drift slightly) get swallowed.
					// Swing doesn't bubble child events to the row, so this
					// never also triggers the collapse toggle.
					if (e.getButton() == MouseEvent.BUTTON1)
					{
						onToggleSelectAll.run();
					}
				}
			});
			eastPanel.add(selectToggle, BorderLayout.EAST);
		}
		else
		{
			this.selectToggle = null;
			JLabel spacer = new JLabel();
			spacer.setPreferredSize(new Dimension(CHEVRON_WIDTH, ROW_HEIGHT));
			eastPanel.add(spacer, BorderLayout.EAST);
		}
		add(eastPanel, BorderLayout.EAST);

		add(chevron, BorderLayout.WEST);
		add(nameLabel, BorderLayout.CENTER);

		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// Clicking the row body SELECTS the section - left OR right click
				// (right-click is retired as a context-menu trigger in 1.0.0; it now
				// just selects, like on goal cards). The chevron and select-all glyph
				// have their own listeners, so their clicks never reach here.
				if ((e.getButton() == MouseEvent.BUTTON1 || e.getButton() == MouseEvent.BUTTON3)
					&& onSelect != null)
				{
					onSelect.run();
				}
			}
		});
	}

	/** This header's section id. */
	public String getSectionId()
	{
		return sectionId;
	}

	/** Paint (or clear) the selected-section highlight. Called by the panel when
	 *  the section selection changes without a full list rebuild. */
	public void setSelected(boolean sel)
	{
		if (this.selected != sel)
		{
			this.selected = sel;
			repaint();
		}
	}

	/**
	 * Re-read the selection state and update the checkbox glyph + tooltip.
	 * Called by the panel whenever the goal selection changes (the header rows
	 * themselves aren't rebuilt on selection-only updates).
	 */
	public void refreshSelectToggle()
	{
		if (selectToggle == null || allSelectedState == null)
		{
			return;
		}
		boolean allSelected = allSelectedState.getAsBoolean();
		selectToggle.setIcon(allSelected
			? ShapeIcons.checkboxChecked(CHECKBOX_SIZE, CHEVRON_COLOR)
			: ShapeIcons.checkboxEmpty(CHECKBOX_SIZE, CHEVRON_COLOR));
		selectToggle.setToolTipText(allSelected
			? "Unselect all in section"
			: "Select all in section");
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		// Paint the darkened user-color fill ourselves so FlatLaf/UI delegates
		// don't override it. Matches the pattern in ColorPickerField swatches
		// and GoalCard's background paint.
		if (hasColor && darkenedFill != null)
		{
			g.setColor(darkenedFill);
			g.fillRect(0, 0, getWidth(), getHeight());
		}
		super.paintComponent(g);

		// Selection outline, painted last so it sits above the fill and the
		// bottom hairline. Mirrors GoalCard's 2px inset white outline.
		if (selected)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setColor(SELECT_COLOR);
			g2.setStroke(new BasicStroke(2f));
			g2.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
			g2.dispose();
		}
	}
}
