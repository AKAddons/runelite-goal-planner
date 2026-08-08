package com.goalplanner.ui.nest;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Renders one section's goals as a <em>subtly nested</em> list: each real
 * {@link com.goalplanner.ui.GoalCard} is stacked full-width (so every card
 * behaviour - menus, arrows, selection, progress - is preserved untouched) but
 * left-indented by its dependency depth ({@link NestIndentAssigner}), with a
 * thin, low-contrast vertical guide drawn at each ancestor level (file-tree
 * style). This is the quiet alternative to the connector-rail view: no dots, no
 * S-curves - just indentation + a faint guide so the hierarchy is scannable.
 */
public final class SectionNestContainer extends JPanel
{
	/** One goal row: its indent level (0 = root), its card, and whether it is a
	 *  collapsible nest parent (and if so, whether currently collapsed). */
	public static final class Row
	{
		public final String id;
		public final int level;
		public final JComponent card;
		public final boolean parent;
		public final boolean collapsed;

		public Row(String id, int level, JComponent card)
		{
			this(id, level, card, false, false);
		}

		public Row(String id, int level, JComponent card, boolean parent, boolean collapsed)
		{
			this.id = id;
			this.level = Math.max(0, level);
			this.card = card;
			this.parent = parent;
			this.collapsed = collapsed;
		}
	}

	// Faint, theme-friendly guide on the dark panel - low contrast on purpose.
	private static final Color GUIDE = new Color(0x6a, 0x6a, 0x6a, 0x66);
	private static final Color CHEVRON_COLOR = new Color(0xB4, 0xB4, 0xDC);

	private static final int LEFT_PAD    = 14;  // margin before level-0 cards (room for the collapse chevron)
	private static final int INDENT_STEP = 13;  // px added per nesting level (max; shrinks to fit, see effectiveStep)
	private static final int MIN_STEP    = 7;   // floor for the per-level indent on a very narrow / deep panel
	private static final int MIN_CARD_W  = 150; // keep the deepest card at least this wide before squeezing the indent
	private static final int MAX_LEVEL   = 6;   // cap indent so deep chains don't run off a narrow panel
	private static final int ROW_GAP     = 4;   // vertical gap between cards
	private static final int TICK        = 5;   // short horizontal stub from the parent guide into the card

	private final List<Row> rows;
	private final int deepestLevel; // deepest capped level present, for indent bookkeeping
	private final int indentW; // widest indent at full step, for preferred-size bookkeeping
	/**
	 * Left gutter before level-0 cards. LEFT_PAD when anything actually nests
	 * (the gutter holds collapse chevrons and guide lines); 0 when the whole
	 * section is level-0 - a degenerate outline, common since repeatable goals
	 * auto-move out of their import section and can leave only flat cards
	 * behind. Without this, those cards render inset for no visible reason.
	 */
	private final int basePad;
	private int stepPx = INDENT_STEP; // effective per-level indent, width-adapted each doLayout()
	/** Collapse chevron per parent row id, positioned in the gutter in doLayout(). */
	private final java.util.Map<String, javax.swing.JLabel> chevrons = new java.util.HashMap<>();

	public SectionNestContainer(List<Row> rows, java.util.function.Consumer<String> onToggleCollapse)
	{
		this.rows = rows;
		setOpaque(false);
		setLayout(null); // manual layout in doLayout()

		int maxLevel = 0;
		for (Row r : rows) maxLevel = Math.max(maxLevel, capped(r.level));
		this.deepestLevel = maxLevel;
		this.basePad = maxLevel > 0 ? LEFT_PAD : 0;
		this.indentW = basePad + maxLevel * INDENT_STEP;

		for (Row r : rows)
		{
			add(r.card);
			// A clickable collapse chevron for goals that have a nested subtree.
			if (r.parent && onToggleCollapse != null)
			{
				javax.swing.JLabel chev = new javax.swing.JLabel(r.collapsed
					? com.goalplanner.ui.ShapeIcons.rightTriangle(8, CHEVRON_COLOR)
					: com.goalplanner.ui.ShapeIcons.downTriangle(8, CHEVRON_COLOR));
				chev.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				chev.setToolTipText(r.collapsed
					? "Show nested prerequisites" : "Hide nested prerequisites");
				final String gid = r.id;
				chev.addMouseListener(new java.awt.event.MouseAdapter()
				{
					@Override public void mousePressed(java.awt.event.MouseEvent e)
					{
						if (e.getButton() == java.awt.event.MouseEvent.BUTTON1) onToggleCollapse.accept(gid);
					}
				});
				chevrons.put(r.id, chev);
				add(chev);
			}
		}
	}

	private static int capped(int level) { return Math.min(level, MAX_LEVEL); }

	/** Left x of a card at the given (capped) level. */
	private int cardX(int level) { return basePad + capped(level) * stepPx; }

	/** X of the vertical guide for ancestor level k (1-based). */
	private int guideX(int k) { return basePad + (k - 1) * stepPx + stepPx / 2; }

	/** Per-level indent that fits the current panel width: full {@link #INDENT_STEP}
	 *  when there's room, shrinking toward {@link #MIN_STEP} so the deepest card keeps
	 *  at least {@link #MIN_CARD_W} px instead of clipping its progress column. */
	private int effectiveStep(int width)
	{
		if (deepestLevel <= 0 || width <= 0) return INDENT_STEP;
		// Reserve more card width at larger font scales (the card content grows with
		// the font); the MIN_STEP floor keeps the hierarchy legible regardless.
		int minCard = Math.round(MIN_CARD_W * com.goalplanner.ui.PanelFonts.scale());
		int budget = width - basePad - minCard;
		int fit = budget / deepestLevel;
		return Math.max(MIN_STEP, Math.min(INDENT_STEP, fit));
	}

	@Override
	public void doLayout()
	{
		int width = getWidth();
		stepPx = effectiveStep(width);
		int y = 0;
		for (Row r : rows)
		{
			int x = cardX(r.level);
			int h = r.card.getPreferredSize().height;
			r.card.setBounds(x, y, Math.max(40, width - x), h);
			javax.swing.JLabel chev = chevrons.get(r.id);
			if (chev != null)
			{
				int cw = chev.getPreferredSize().width;
				int ch = chev.getPreferredSize().height;
				chev.setBounds(Math.max(1, x - cw - 2), y + (h - ch) / 2, cw, ch);
			}
			y += h + ROW_GAP;
		}
	}

	@Override
	public Dimension getPreferredSize()
	{
		int h = 0;
		for (Row r : rows) h += r.card.getPreferredSize().height + ROW_GAP;
		// Width driven by the parent (BoxLayout); report indent + a modest card
		// minimum so a section never collapses below usability.
		return new Dimension(indentW + 120, Math.max(h, 1));
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		if (rows.isEmpty()) return;

		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(GUIDE);
		g2.setStroke(new BasicStroke(1f));

		for (Row r : rows)
		{
			int lvl = capped(r.level);
			if (lvl == 0) continue;

			int top = r.card.getY();
			int bottom = top + r.card.getHeight() + ROW_GAP; // span the gap so guides read continuous
			int midY = top + r.card.getHeight() / 2;

			// Vertical guide at every ancestor level (file-tree spine). Consecutive
			// rows sharing a level stack into one continuous-looking line.
			for (int k = 1; k <= lvl; k++)
			{
				int gx = guideX(k);
				g2.drawLine(gx, top, gx, bottom);
			}

			// Short horizontal stub from this card's immediate parent guide into it.
			int stubX = guideX(lvl);
			g2.drawLine(stubX, midY, stubX + TICK, midY);
		}
		g2.dispose();
	}

	/** Convenience for callers that don't track child components separately. */
	public List<Component> cards()
	{
		List<Component> out = new ArrayList<>(rows.size());
		for (Row r : rows) out.add(r.card);
		return out;
	}
}
