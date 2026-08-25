package com.goalplanner.ui;

import com.goalplanner.api.GoalView;
import com.goalplanner.api.TagView;
import com.goalplanner.util.FormatUtil;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.SwingUtilities;

/**
 * Individual goal card. Consumes a {@link GoalView} (the public DTO) - does not
 * touch the internal {@code Goal} model directly. The panel obtains GoalViews
 * via {@code GoalPlannerApi.queryAllGoals()} so the read path is the same one
 * external consumer plugins use.
 */
public class GoalCard extends JPanel
{
	private static final Color BACKGROUND = new Color(30, 30, 30);
	private static final Color BACKGROUND_COMPLETE = new Color(50, 50, 50);
	private static final Color TEXT_PRIMARY = new Color(230, 230, 230);
	private static final Color ARROW_COLOR = new Color(180, 180, 180);
	private static final Color ARROW_HOVER = Color.WHITE;
	private static final int CARD_HEIGHT = 48;
	private static final int TAG_ROW_HEIGHT = 18;
	private static final int CORNER_RADIUS = 8;

	/**
	 * Approximate width in pixels available to the goal-name label after icon,
	 * status, and arrow components are subtracted from the sidebar. Used by the
	 * pixel-based truncation helper so we don't lose characters to char-count
	 * truncation followed by Swing's hard clip on overflow.
	 */
	private static final int NAME_WIDTH_PX = 130;

	/** Side length for the completion icon rendered on completed cards. */
	private static final int COMPLETION_ICON_PX = 16;

	/**
	 * Lazily-loaded completion icon, scaled from the plugin's sidebar icon
	 * so completed cards show the same visual identity that the plugin
	 * navigation button uses. Loaded once at first use; null if the
	 * resource can't be read (in which case completed cards render
	 * without the right-side icon - no fallback glyph).
	 */
	private static volatile ImageIcon completionIcon;

	private static ImageIcon loadCompletionIcon()
	{
		ImageIcon cached = completionIcon;
		if (cached != null) return cached;
		try (InputStream in = GoalCard.class.getResourceAsStream("/goal_icon.png"))
		{
			if (in == null) return null;
			BufferedImage raw = ImageIO.read(in);
			if (raw == null) return null;
			Image scaled = raw.getScaledInstance(
				COMPLETION_ICON_PX, COMPLETION_ICON_PX, Image.SCALE_SMOOTH);
			ImageIcon built = new ImageIcon(scaled);
			completionIcon = built;
			return built;
		}
		catch (IOException e)
		{
			return null;
		}
	}

	/** The plugin's completion icon (scaled, cached), or null if unavailable.
	 *  Shared so other UI (e.g. an all-complete section header) can show it. */
	public static ImageIcon completionIcon()
	{
		return loadCompletionIcon();
	}

	private GoalView view;
	private final JLabel nameLabel;
	private final JLabel statusLabel;
	/** When true, the status column uses the single-line compact form regardless
	 *  of font scale - set for narrow (nested) cards so progress doesn't clip. */
	private boolean compactStatus;
	private final JButton upButton;
	private final JButton downButton;

	private final SkillIconManager skillIconManager;
	private final SpriteManager spriteManager;
	private final ItemManager itemManager;

	public GoalCard(GoalView view, ActionListener onMoveUp, ActionListener onMoveDown,
					Runnable onMoveToTop, Runnable onMoveToBottom,
					SkillIconManager skillIconManager, ItemManager itemManager,
					SpriteManager spriteManager, Runnable onAddMissingRequirements)
	{
		this.view = view;
		this.skillIconManager = skillIconManager;
		this.spriteManager = spriteManager;
		this.itemManager = itemManager;

		// Tags render on completed cards too - completed goals are first-class
		// section members now (movable, duplicable, taggable), so their tags show
		// the same as active goals.
		List<TagView> allTags = combinedTags(view);
		boolean hasTags = !allTags.isEmpty();
		// Grow the fixed-pixel box with the font scale so larger text gets the extra
		// vertical room it needs - otherwise the 2-line name/progress columns wrap
		// and clip against a 48px card at the "Large"/"Larger" scales.
		float s = PanelFonts.scale();
		final int cardH = Math.round(CARD_HEIGHT * s);
		final int tagH = Math.round(TAG_ROW_HEIGHT * s);
		final int iconPx = Math.round(18 * s);
		final int sideH = cardH - Math.round(12 * s); // arrow/completion column height
		int height = hasTags ? cardH + tagH : cardH;

		setLayout(new BorderLayout(0, 0));
		setBorder(new EmptyBorder(4, 10, 4, 4));
		setPreferredSize(new Dimension(0, height));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		setOpaque(false);

		// Top row: icon + name | status/progress | arrows
		JPanel topRow = new JPanel(new BorderLayout(4, 0));
		topRow.setOpaque(false);

		// Left: icon + name
		JPanel leftPanel = new JPanel(new BorderLayout(6, 0));
		leftPanel.setOpaque(false);

		JLabel iconLabel = buildIcon(itemManager);
		iconLabel.setPreferredSize(new Dimension(iconPx, iconPx));
		leftPanel.add(iconLabel, BorderLayout.WEST);

		nameLabel = new JLabel(formatNameHtml());
		nameLabel.setForeground(view.optional ? new Color(160, 160, 160) : TEXT_PRIMARY);
		nameLabel.setFont(PanelFonts.derive(Font.BOLD, 12f));
		nameLabel.setVerticalAlignment(SwingConstants.CENTER);
		leftPanel.add(nameLabel, BorderLayout.CENTER);

		// Blocked-by-prereqs badge: a small amber warning glyph when this goal
		// has DIRECT requirements the player hasn't met and that aren't in the
		// plan. Click = add them ("Incomplete only"). Hidden on completed goals.
		if (!isComplete() && view.blockedRequirements != null && !view.blockedRequirements.isEmpty()
			&& onAddMissingRequirements != null)
		{
			JLabel blockedBadge = new JLabel(ShapeIcons.warning(11, new Color(0xE0, 0x9A, 0x2B)));
			blockedBadge.setPreferredSize(new Dimension(16, 18));
			blockedBadge.setHorizontalAlignment(SwingConstants.CENTER);
			blockedBadge.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			int n = view.blockedRequirements.size();
			StringBuilder tip = new StringBuilder("<html><b>").append(n)
				.append(n == 1 ? " requirement" : " requirements")
				.append(" likely blocking this, not in your plan:</b><br>");
			for (String r : view.blockedRequirements)
			{
				tip.append("&nbsp;&nbsp;- ").append(FormatUtil.escapeHtml(r)).append("<br>");
			}
			tip.append("<i>Click to add them to this section.</i></html>");
			blockedBadge.setToolTipText(tip.toString());
			blockedBadge.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					if (e.getButton() == MouseEvent.BUTTON1)
					{
						onAddMissingRequirements.run();
					}
				}
			});
			leftPanel.add(blockedBadge, BorderLayout.EAST);
		}

		setToolTipText(buildTooltipHtml(view, skillIconManager));

		// Right side: status (XP, percent, checkmark, etc.)
		statusLabel = new JLabel(formatPercent());
		statusLabel.setForeground(TEXT_PRIMARY);
		statusLabel.setFont(PanelFonts.derive(11f));
		statusLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		statusLabel.setVerticalAlignment(SwingConstants.CENTER);

		upButton = createArrowButton(true, onMoveUp, onMoveToTop);
		downButton = createArrowButton(false, onMoveDown, onMoveToBottom);

		topRow.add(leftPanel, BorderLayout.WEST);
		topRow.add(statusLabel, BorderLayout.CENTER);

		if (!isComplete())
		{
			JPanel arrowPanel = new JPanel(new GridLayout(2, 1, 0, 0));
			arrowPanel.setOpaque(false);
			arrowPanel.setPreferredSize(new Dimension(20, sideH));
			arrowPanel.add(upButton);
			arrowPanel.add(downButton);
			topRow.add(arrowPanel, BorderLayout.EAST);
		}
		else
		{
			// Completion marker - render the plugin's sidebar icon
			// scaled small on the right edge. Signals "done" with the
			// plugin's own visual identity rather than a generic check.
			ImageIcon icon = loadCompletionIcon();
			if (icon != null)
			{
				JLabel completionLabel = new JLabel(icon);
				completionLabel.setHorizontalAlignment(SwingConstants.CENTER);
				completionLabel.setVerticalAlignment(SwingConstants.CENTER);
				completionLabel.setPreferredSize(new Dimension(20, sideH));
				topRow.add(completionLabel, BorderLayout.EAST);
			}
		}

		// Stack: top row + tags below (tags span full card width)
		if (hasTags)
		{
			JPanel content = new JPanel(new BorderLayout(0, 0));
			content.setOpaque(false);
			content.add(topRow, BorderLayout.CENTER);
			content.add(buildTagRow(allTags), BorderLayout.SOUTH);
			add(content, BorderLayout.CENTER);
		}
		else
		{
			add(topRow, BorderLayout.CENTER);
		}
	}

	/** Combine default + custom tags into a single render list. */
	private static List<TagView> combinedTags(GoalView view)
	{
		List<TagView> out = new ArrayList<>();
		if (view.defaultTags != null) out.addAll(view.defaultTags);
		if (view.customTags != null) out.addAll(view.customTags);
		return out;
	}

	private JLabel buildIcon(ItemManager itemManager)
	{
		// Skill icon
		if ("SKILL".equals(view.type))
		{
			String skillName = (String) view.attributes.get("skillName");
			if (skillName != null && skillIconManager != null)
			{
				try
				{
					Skill skill = Skill.valueOf(skillName);
					return new JLabel(new ImageIcon(skillIconManager.getSkillImage(skill, true)));
				}
				catch (Exception ignored) {}
			}
			return makeColorDot(backgroundColor());
		}

		// Item image (ITEM_GRIND goals + CUSTOM goals with itemId, e.g. unlock milestones)
		{
			Object idObj = view.attributes.get("itemId");
			if (idObj instanceof Number && itemManager != null)
			{
				int itemId = ((Number) idObj).intValue();
				if (itemId > 0)
				{
					JLabel label = new JLabel();
					ItemImageCache.applyTo(label, itemId, itemManager);
					return label;
				}
			}
		}

		// Account goal with item/bundled icon key (e.g. kudos → item:11182)
		if ("ACCOUNT".equals(view.type))
		{
			String iconKey = (String) view.attributes.get("iconKey");
			if (iconKey != null)
			{
				BufferedImage icon = resolveIcon(iconKey);
				if (icon != null)
				{
					BufferedImage scaled = new BufferedImage(
						18, 18, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2d = scaled.createGraphics();
					g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
						RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2d.drawImage(icon, 0, 0, 18, 18, null);
					g2d.dispose();
					return new JLabel(new ImageIcon(scaled));
				}
			}
			// Fall through to sprite path if no iconKey or resolve failed
		}

		// Sprite (CA tier sword, quest book, diary book, account icons, etc.)
		if (view.spriteId > 0 && spriteManager != null)
		{
			final JLabel spriteLabel = new JLabel();
			spriteManager.getSpriteAsync(view.spriteId, 0, img ->
				SwingUtilities.invokeLater(() -> {
					if (img == null) return;
					BufferedImage scaled = new BufferedImage(
						18, 18, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2d = scaled.createGraphics();
					g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
						RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2d.drawImage(img, 0, 0, 18, 18, null);
					g2d.dispose();
					spriteLabel.setIcon(new ImageIcon(scaled));
				}));
			return spriteLabel;
		}

		return makeColorDot(backgroundColor());
	}

	private JPanel buildTagRow(List<TagView> allTags)
	{
		JPanel tagRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
		tagRow.setOpaque(false);
		tagRow.setBorder(new EmptyBorder(0, 0, 0, 0));

		// Sort: tags with icons first, then tags without.
		List<TagView> sorted = new ArrayList<>(allTags);
		sorted.sort((a, b) -> {
			boolean aIcon = a.iconKey != null && !a.iconKey.isEmpty();
			boolean bIcon = b.iconKey != null && !b.iconKey.isEmpty();
			if (aIcon != bIcon) return aIcon ? -1 : 1;
			return 0;
		});

		// Collapse 3+ BOSS or QUEST tags into "Multiple" per category.
		// Same rule as bosses: when a goal accumulates enough same-category
		// associations that the pills overflow, a single collapsed pill
		// reads better and the tooltip still enumerates the underlying
		// labels. Quest associations use the same pattern because they
		// behave identically on the card.
		List<TagView> bossTags = new ArrayList<>();
		List<TagView> questTags = new ArrayList<>();
		List<TagView> otherTags = new ArrayList<>();
		for (TagView tag : sorted)
		{
			if ("BOSS".equals(tag.category)) bossTags.add(tag);
			else if ("QUEST".equals(tag.category)) questTags.add(tag);
			else otherTags.add(tag);
		}

		for (TagView tag : otherTags)
		{
			tagRow.add(createTagComponent(tag));
		}

		addCategoryTagsWithCollapse(tagRow, bossTags, "BOSS", "Dropped by");
		addCategoryTagsWithCollapse(tagRow, questTags, "QUEST", "Required by");

		return tagRow;
	}

	/**
	 * Render a same-category group of tags: either the tags themselves
	 * (when &lt; 3) or a single collapsed "Multiple" pill with a tooltip
	 * enumerating the underlying labels (when ≥ 3).
	 */
	private void addCategoryTagsWithCollapse(
		JPanel tagRow, List<TagView> tags, String category, String tooltipVerb)
	{
		if (tags.isEmpty()) return;
		if (tags.size() >= 3)
		{
			TagView multi = new TagView("Multiple", category, tags.get(0).colorRgb);
			JComponent pill = createTagComponent(multi);
			StringBuilder tooltip = new StringBuilder("<html>").append(tooltipVerb).append(":<br>");
			for (TagView t : tags)
			{
				tooltip.append("- ").append(FormatUtil.escapeHtml(t.label)).append("<br>");
			}
			tooltip.append("</html>");
			pill.setToolTipText(tooltip.toString());
			tagRow.add(pill);
		}
		else
		{
			for (TagView tag : tags) tagRow.add(createTagComponent(tag));
		}
	}

	/** Convert the packed RGB on a TagView to a Swing Color. */
	private static Color tagColor(TagView tag)
	{
		return new Color(tag.colorRgb);
	}

	private JComponent createTagComponent(TagView tag)
	{
		// Uniform icon resolver. If the tag has an iconKey, try
		// to resolve it via SkillIconManager (for Skill enum names) then via
		// bundled /icons/<key>.png. Falls through to colored pill if both fail.
		if (tag.iconKey != null && !tag.iconKey.isEmpty())
		{
			BufferedImage img = resolveIcon(tag.iconKey);
			if (img != null)
			{
				BufferedImage scaled = new BufferedImage(11, 11, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g2d = scaled.createGraphics();
				g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g2d.drawImage(img, 0, 0, 11, 11, null);
				g2d.dispose();
				JLabel iconLabel = new JLabel(new ImageIcon(scaled));
				iconLabel.setPreferredSize(new Dimension(11, 11));
				iconLabel.setMaximumSize(new Dimension(11, 11));
				iconLabel.setToolTipText(tag.label);
				return iconLabel;
			}
		}

		return createTagPill(tag);
	}

	/**
	 * Resolve an iconKey to a BufferedImage, or null if not found.
	 *
	 * <p>Resolution order:
	 * <ol>
	 *   <li>{@code item:<itemId>} prefix → {@link ItemManager#getImage(int)}</li>
	 *   <li>Skill enum name (case-insensitive) → {@link SkillIconManager#getSkillImage}</li>
	 *   <li>Bundled classpath resource at {@code /icons/<key>.png}</li>
	 * </ol>
	 *
	 * <p>Returns null if all lookups fail; caller falls back to colored pill.
	 */
	private BufferedImage resolveIcon(String iconKey)
	{
		// item:<id> prefix → ItemManager
		if (iconKey.startsWith("item:") && itemManager != null)
		{
			try
			{
				int itemId = Integer.parseInt(iconKey.substring("item:".length()));
				return itemManager.getImage(itemId);
			}
			catch (NumberFormatException ignored) {}
			catch (Exception ignored) {}
		}
		// Skill enum match → SkillIconManager
		if (skillIconManager != null)
		{
			try
			{
				Skill skill = Skill.valueOf(iconKey.toUpperCase());
				return skillIconManager.getSkillImage(skill, true);
			}
			catch (IllegalArgumentException ignored) {}
		}
		// Bundled resource → /icons/<key>.png
		try (InputStream in = getClass().getResourceAsStream("/icons/" + iconKey + ".png"))
		{
			if (in != null)
			{
				return ImageIO.read(in);
			}
		}
		catch (IOException ignored) {}
		return null;
	}

	private static JLabel createTagPill(TagView tag)
	{
		JLabel pill = new JLabel(tag.label)
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				Color c = tagColor(tag);
				g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 50));
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
				g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 120));
				g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
				g2.dispose();
				super.paintComponent(g);
			}
		};
		pill.setForeground(tagColor(tag));
		pill.setFont(PanelFonts.derive(Font.PLAIN, 9f));
		pill.setBorder(new EmptyBorder(1, 5, 1, 5));
		pill.setOpaque(false);
		return pill;
	}

	private static JLabel makeColorDot(Color color)
	{
		return new JLabel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(color);
				int size = 12;
				int x = (getWidth() - size) / 2;
				int y = (getHeight() - size) / 2;
				g2.fillOval(x, y, size, size);
				g2.dispose();
			}
		};
	}

	private JButton createArrowButton(boolean up, ActionListener action, Runnable jumpAction)
	{
		final int iconSize = 7;
		final Icon idle = up
			? ShapeIcons.upTriangle(iconSize, ARROW_COLOR)
			: ShapeIcons.downTriangle(iconSize, ARROW_COLOR);
		final Icon hover = up
			? ShapeIcons.upTriangle(iconSize, ARROW_HOVER)
			: ShapeIcons.downTriangle(iconSize, ARROW_HOVER);

		JButton btn = new JButton(idle);
		btn.setContentAreaFilled(false);
		btn.setBorderPainted(false);
		btn.setFocusPainted(false);
		btn.setMargin(new Insets(0, 0, 0, 0));
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		btn.setToolTipText(up
			? "Move up (right-click: move to top)"
			: "Move down (right-click: move to bottom)");
		btn.addActionListener(action);

		btn.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e) { btn.setIcon(hover); }
			@Override
			public void mouseExited(MouseEvent e) { btn.setIcon(idle); }
			@Override
			public void mousePressed(MouseEvent e)
			{
				// Right-click jumps the goal to the section edge. The button's
				// own ActionListener only fires on left-click (BUTTON1), so this
				// MousePressed handler doesn't double-trigger with the arrow's
				// normal one-step move.
				if (SwingUtilities.isRightMouseButton(e) && jumpAction != null)
				{
					jumpAction.run();
				}
			}
		});

		return btn;
	}

	public void update(GoalView view)
	{
		this.view = view;
		nameLabel.setText(formatNameHtml());
		statusLabel.setText(formatPercent());
		repaint();
	}

	public void setFirstInList(boolean first)
	{
		upButton.setVisible(!first);
	}

	/** The goal ID this card represents. */
	public String getGoalId()
	{
		return view.id;
	}

	/** Update the selection highlight without rebuilding the card. */
	public void setSelected(boolean selected)
	{
		if (view.selected != selected)
		{
			view.selected = selected;
			repaint();
		}
	}

	public void setLastInList(boolean last)
	{
		downButton.setVisible(!last);
	}

	/** Toggle the compact single-line status form (for narrow/nested cards).
	 *  Applied live each rebuild, like selection - not part of the card signature. */
	public void setCompactStatus(boolean compact)
	{
		if (compact != compactStatus)
		{
			compactStatus = compact;
			statusLabel.setText(formatPercent());
		}
	}

	private Color backgroundColor()
	{
		return new Color(view.backgroundColorRgb);
	}

	private boolean isComplete()
	{
		return view.completedAt > 0;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		int w = getWidth();
		int h = getHeight();
		Color baseColor = backgroundColor();

		// Background: type-colored tint over a flat base
		g2.setColor(isComplete() ? BACKGROUND_COMPLETE : BACKGROUND);
		g2.fillRoundRect(0, 0, w, h, CORNER_RADIUS, CORNER_RADIUS);

		Color tint = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), 40);
		g2.setColor(tint);
		g2.fillRoundRect(0, 0, w, h, CORNER_RADIUS, CORNER_RADIUS);

		// Optional goals: diagonal hatching overlay (like Outlook optional meetings).
		if (view.optional)
		{
			Shape oldClip = g2.getClip();
			g2.setClip(new RoundRectangle2D.Float(0, 0, w, h, CORNER_RADIUS, CORNER_RADIUS));
			g2.setColor(new Color(255, 255, 255, 30));
			g2.setStroke(new BasicStroke(2f));
			int spacing = 10;
			for (int x = -h; x < w + h; x += spacing)
			{
				g2.drawLine(x, h, x + h, 0);
			}
			g2.setClip(oldClip);
		}

		// Selection indicator
		if (view.selected)
		{
			g2.setStroke(new BasicStroke(2f));
			g2.setColor(isComplete() ? new Color(140, 140, 140) : Color.WHITE);
			// Inset by 1px on each side so the 2px stroke sits cleanly inside the bounds.
			g2.drawRoundRect(1, 1, w - 2, h - 2, CORNER_RADIUS, CORNER_RADIUS);
		}

		g2.dispose();
		super.paintComponent(g);
	}

	/**
	 * Truncate {@code text} to fit within {@code maxPx} pixels using the given
	 * font, appending an ellipsis when truncation is needed. Uses
	 * {@link FontMetrics#stringWidth} for accurate per-glyph width - replaces
	 * char-count truncation which double-clipped wide CA titles.
	 */
	/** Throwaway component used solely to obtain FontMetrics in a label-independent
	 *  way. Avoids the order-of-init issue where formatNameHtml() runs as an
	 *  argument to {@code new JLabel(...)} before the nameLabel field is assigned.
	 */
	private static final Canvas FONT_METRICS_CANVAS = new Canvas();

	private String truncateToWidth(String text, Font font, int maxPx)
	{
		if (text == null || text.isEmpty()) return text;
		FontMetrics fm = FONT_METRICS_CANVAS.getFontMetrics(font);
		if (fm.stringWidth(text) <= maxPx) return text;
		String ellipsis = "...";
		int ellipsisWidth = fm.stringWidth(ellipsis);
		int budget = maxPx - ellipsisWidth;
		if (budget <= 0) return ellipsis;
		StringBuilder sb = new StringBuilder();
		int width = 0;
		for (int i = 0; i < text.length(); i++)
		{
			int cw = fm.charWidth(text.charAt(i));
			if (width + cw > budget) break;
			sb.append(text.charAt(i));
			width += cw;
		}
		return sb.toString().trim() + ellipsis;
	}

	// Measurement fonts for truncation - resolved through PanelFonts so they track
	// the configured family + size scale (and stay in sync with the rendered labels).
	private static Font nameFont()
	{
		return PanelFonts.derive(Font.BOLD, 12f);
	}

	private static Font descFont()
	{
		return PanelFonts.derive(Font.PLAIN, 9f);
	}

	private String fitName(String text)
	{
		return truncateToWidth(text, nameFont(), NAME_WIDTH_PX);
	}

	private String fitDescription(String text)
	{
		return truncateToWidth(text, descFont(), NAME_WIDTH_PX);
	}


	/**
	 * Subtitle for a repeating goal: the period plus when this one is due, e.g.
	 * {@code "Daily - by 00:00"}.
	 *
	 * <p>Computed from the LIVE period every render rather than read from the
	 * stored description. The description is written once at creation, so a goal
	 * switched from daily to weekly kept claiming "Daily" until something else
	 * rewrote it.
	 */
	/**
	 * Subtitle for a repeatable goal that is DONE for this period. It stays in
	 * the Repeatable section rather than graduating, so the useful fact is when
	 * it comes back - a wall-clock time, not a countdown. You are not working on
	 * it, so "how long is left" no longer means anything to act on.
	 */
	private String completedRepeatSubtitle()
	{
		return view.resetsAtClock == null || view.resetsAtClock.isEmpty()
			? ""
			: "resets - " + view.resetsAtClock;
	}

	private String repeatSubtitle()
	{
		if (view.repeatEvery == null || "NONE".equals(view.repeatEvery))
		{
			return view.description == null ? "" : view.description;
		}
		String label;
		try
		{
			label = com.goalplanner.model.RepeatPeriod.valueOf(view.repeatEvery).getLabel();
		}
		catch (IllegalArgumentException e)
		{
			return view.description == null ? "" : view.description;
		}
		return view.resetsAtLabel == null || view.resetsAtLabel.isEmpty()
			? label
			: label + " - " + view.resetsAtLabel;
	}

	private String formatNameHtml()
	{
		String line1;
		String line2;

		switch (view.type == null ? "CUSTOM" : view.type)
		{
			case "SKILL":
				String skillName = (String) view.attributes.get("skillName");
				String skillDisplay = skillName != null ? Skill.valueOf(skillName).getName() : view.name;
				if (view.repeatChunk > 0)
				{
					// Period-relative values: "100,000 XP this period" is not
					// "level 49". Running them through getLevelForXp would render
					// "49 Prayer / Lv 1 / 49" on a level-96 account.
					line1 = fitName(view.name);
					line2 = "";
					break;
				}
				int currentLevel = Math.max(1, net.runelite.api.Experience.getLevelForXp(
					Math.max(0, view.currentValue)));
				int targetLevel = Math.max(1, net.runelite.api.Experience.getLevelForXp(
					Math.max(0, view.targetValue)));
				// Prefix target level so the card reads "99 Strength" rather than just
				// "Strength" - matches the ITEM_GRIND pattern ("200x Cannonballs") and
				// keeps the completed-state label meaningful once line 2 is replaced
				// with the completion date.
				line1 = fitName(targetLevel + " " + skillDisplay);
				line2 = "Lv " + currentLevel + " / " + targetLevel;
				break;
			case "ITEM_GRIND":
				// Prepend target qty so the display reads "200x Cannonballs"
				// instead of just "Cannonballs". Skip the prefix when target is
				// 1 (e.g. "Jar of Miasma" not "1x Jar of Miasma" - uniques read
				// more naturally without the qty marker).
				String itemTitle = view.targetValue > 1
					? FormatUtil.formatNumber(view.targetValue) + "x " + view.name
					: view.name;
				line1 = fitName(itemTitle);
				if (view.currentValue < 0)
				{
					line2 = "? / " + FormatUtil.formatNumber(view.targetValue);
				}
				else
				{
					line2 = FormatUtil.formatNumber(view.currentValue) + " / "
						+ FormatUtil.formatNumber(view.targetValue);
				}
				break;
			case "DIARY":
				// Diary title carries tier suffix so the date description on completed
				// cards doesn't drop the tier info.
				String tier = (String) view.attributes.get("tier");
				String tierWord = tier != null
					? tier.substring(0, 1) + tier.substring(1).toLowerCase()
					: "";
				line1 = tierWord.isEmpty()
					? fitName(view.name)
					: fitName(view.name + " - " + tierWord);
				line2 = (view.description != null && !view.description.isEmpty())
					? fitDescription(view.description)
					: "";
				break;
			case "ACCOUNT":
				line1 = fitName(view.name);
				line2 = view.currentValue + " / " + view.targetValue;
				break;
			case "CUSTOM":
			default:
				line1 = fitName(view.name);
				line2 = (view.description != null && !view.description.isEmpty())
					? fitDescription(view.description)
					: "";
				break;
		}

		// Completed goals: replace line 2 with the completion date.
		if (isComplete())
		{
			// BOSS cards normally show just "Barrows" in the title with
			// "50 kills" in the description. Replacing the description
			// with the completion date drops the target KC, so prepend
			// it to the title on completion ("50x Barrows") - matches
			// the ITEM_GRIND pattern and keeps the target count visible.
			String type = view.type == null ? "CUSTOM" : view.type;
			if ("BOSS".equals(type) && view.targetValue > 0)
			{
				line1 = fitName(FormatUtil.formatNumber(view.targetValue) + "x " + view.name);
			}
			line2 = "Completed " + formatCompletionDate(view.completedAt);
		}

		// Every repeating goal shows its period and countdown, whatever its type.
		// This lived in the SKILL branch only, so boss/item/custom repeatables
		// silently had no timer. Applied after the completed-date override on
		// purpose: for something that comes back, "resets in 3h" is more useful
		// than the date you finished it.
		if (view.repeatEvery != null && !"NONE".equals(view.repeatEvery))
		{
			line2 = isComplete() ? completedRepeatSubtitle() : repeatSubtitle();
		}

		if (line2.isEmpty())
		{
			return FormatUtil.escapeHtml(line1);
		}

		return "<html>" + FormatUtil.escapeHtml(line1)
			+ "<br><span style='font-size:9px; color:#a0a0a0'>"
			+ FormatUtil.escapeHtml(fitDescription(line2)) + "</span></html>";
	}

	private static String formatCompletionDate(long epochMillis)
	{
		SimpleDateFormat fmt = new SimpleDateFormat("MMM d, yyyy");
		return fmt.format(new Date(epochMillis));
	}

	private String formatPercent()
	{
		if (isComplete())
		{
			// Completion is signaled by the goal_icon.png in the EAST slot;
			// skip the text checkmark so we don't render two markers.
			return "";
		}
		String type = view.type == null ? "CUSTOM" : view.type;
		if ("CUSTOM".equals(type))
		{
			return "";
		}
		// Combat achievements, quests, and diaries are binary; no progress shown
		// until complete (handled above - empty status, goal_icon.png marks done).
		if ("COMBAT_ACHIEVEMENT".equals(type) || "QUEST".equals(type) || "DIARY".equals(type))
		{
			return "";
		}
		if ("ITEM_GRIND".equals(type) && view.currentValue < 0)
		{
			return "?";
		}
		// Hide the percentage on item goals until you actually have at least
		// one of the item - "0%" reads as noise on a fresh goal.
		if ("ITEM_GRIND".equals(type) && view.currentValue <= 0)
		{
			return "";
		}
		if (("SKILL".equals(type) || "BOSS".equals(type)) && view.targetValue > 0)
		{
			int remaining = Math.max(0, view.targetValue - view.currentValue);
			// Floors and caps at 99 - every path here is an incomplete goal, so
			// "100%" would be a lie (see FormatUtil.formatProgressPercent).
			String pct = FormatUtil.formatProgressPercent(view.currentValue, view.targetValue);
			// When the status column is narrow - a nested card, or any card above
			// Normal scale - the rich two-line form wraps and clips, so collapse to a
			// single compact token. SKILL keeps just the percent (levels already show
			// in the name); BOSS keeps the KC count (the boss name carries no
			// progress). Full detail stays in the tooltip.
			if (compactStatus || PanelFonts.scale() > 1.0f)
			{
				return "SKILL".equals(type)
					? pct
					: FormatUtil.formatNumber(view.currentValue) + " / " + FormatUtil.formatNumber(view.targetValue);
			}
			String unit = "SKILL".equals(type) ? " left" : " kills left";
			String counts = FormatUtil.formatNumber(view.currentValue) + " / "
				+ FormatUtil.formatNumber(view.targetValue);
			// The percent is a bonus, not load-bearing (the bar shows it too).
			// Past ~10 chars, "0 / 300K (0%)" wraps the column into three
			// clipped lines - so wide counts drop the percent token instead.
			String top = counts.length() <= 10
				? counts + " (" + pct + ")"
				: counts;
			return "<html>"
				+ top
				+ "<br><span style='font-size:9px; color:#a0a0a0'>"
				+ FormatUtil.formatNumber(remaining) + unit + "</span></html>";
		}
		return FormatUtil.formatProgressPercent(view.currentValue, view.targetValue);
	}

	/**
	 * Build the card's hover tooltip as HTML. Composed of up to three
	 * sections:
	 * <ol>
	 *   <li>Base: the type-specific tooltip from {@code attributes.tooltip}
	 *       if present (CA task description, quest requirements from wiki,
	 *       etc.), or the full name when it's truncated on the card face,
	 *       or null if neither applies.</li>
	 *   <li>Requires: comma-separated list of goals this one requires
	 *       (outgoing edges). Omitted if empty.</li>
	 *   <li>Required by: comma-separated list of goals that require this
	 *       one (incoming edges). Omitted if empty.</li>
	 * </ol>
	 *
	 * <p>Returns null if all three sections are empty (no tooltip at all).
	 */
	private static String buildTooltipHtml(GoalView view, SkillIconManager skillIconManager)
	{
		String base = (String) view.attributes.get("tooltip");
		if (base == null || base.isEmpty())
		{
			if (view.name != null && view.name.length() > 22) base = view.name;
		}

		List<GoalView.RelationView> requires = view.requiresNames != null
			? view.requiresNames : Collections.emptyList();
		List<GoalView.RelationView> orRequires = view.orRequiresNames != null
			? view.orRequiresNames : Collections.emptyList();
		List<GoalView.RelationView> requiredBy = view.requiredByNames != null
			? view.requiredByNames : Collections.emptyList();
		List<GoalView.RelationView> orRequiredBy = view.orRequiredByNames != null
			? view.orRequiredByNames : Collections.emptyList();

		boolean hasRelations = !requires.isEmpty() || !orRequires.isEmpty()
			|| !requiredBy.isEmpty() || !orRequiredBy.isEmpty();
		if ((base == null || base.isEmpty()) && !hasRelations) return null;

		StringBuilder sb = new StringBuilder("<html>");
		if (base != null && !base.isEmpty())
		{
			// Base tooltip may or may not already contain HTML. Strip any outer
			// <html>...</html> wrapper so we can re-compose without nesting.
			String stripped = base;
			if (stripped.startsWith("<html>")) stripped = stripped.substring(6);
			if (stripped.endsWith("</html>")) stripped = stripped.substring(0, stripped.length() - 7);
			sb.append(stripped);
			if (hasRelations) sb.append("<br><br>");
		}
		// Split into required vs recommended (optional).
		List<GoalView.RelationView> reqRequired = new ArrayList<>();
		List<GoalView.RelationView> reqRecommended = new ArrayList<>();
		for (GoalView.RelationView r : requires)
		{
			if (r.optional) reqRecommended.add(r); else reqRequired.add(r);
		}
		// For "required by" / "recommended by": if THIS goal is optional,
		// all parents see it as a recommendation, not a requirement.
		List<GoalView.RelationView> byRequired;
		List<GoalView.RelationView> byRecommended;
		if (view.optional)
		{
			byRequired = Collections.emptyList();
			byRecommended = requiredBy;
		}
		else
		{
			byRequired = requiredBy;
			byRecommended = Collections.emptyList();
		}

		boolean first = true;
		if (!reqRequired.isEmpty() || !orRequires.isEmpty())
		{
			sb.append("<b>Requires:</b> ");
			if (!reqRequired.isEmpty())
			{
				sb.append(formatRelations(reqRequired, skillIconManager));
			}
			if (!reqRequired.isEmpty() && !orRequires.isEmpty())
			{
				sb.append(", ");
			}
			if (!orRequires.isEmpty())
			{
				sb.append("(").append(formatRelationsOr(orRequires, skillIconManager)).append(")");
			}
			first = false;
		}
		if (!reqRecommended.isEmpty())
		{
			if (!first) sb.append("<br>");
			sb.append("<b>Recommends:</b> ").append(formatRelations(reqRecommended, skillIconManager));
			first = false;
		}
		if (!byRequired.isEmpty())
		{
			if (!first) sb.append("<br>");
			sb.append("<b>Required by:</b> ").append(formatRelations(byRequired, skillIconManager));
			first = false;
		}
		if (!byRecommended.isEmpty())
		{
			if (!first) sb.append("<br>");
			sb.append("<b>Recommended by:</b> ").append(formatRelations(byRecommended, skillIconManager));
			first = false;
		}
		if (!orRequiredBy.isEmpty())
		{
			if (!first) sb.append("<br>");
			sb.append("<b>Also Completed By:</b> ").append(formatRelations(orRequiredBy, skillIconManager));
		}
		sb.append("</html>");
		return sb.toString();
	}

	/**
	 * Format a list of relation views for tooltip display. Skill relations
	 * render compactly as "SkillName Level"; non-skill relations use the
	 * goal name. Items separated by ", ".
	 */
	private static String formatRelations(List<GoalView.RelationView> relations,
										  SkillIconManager skillIconManager)
	{
		return formatRelationsWithSeparator(relations, skillIconManager, ", ");
	}

	/**
	 * Format OR-relations with " OR " separator for inline display
	 * inside parentheses (e.g., "(Attack 99 OR Strength 99)").
	 */
	private static String formatRelationsOr(List<GoalView.RelationView> relations,
											SkillIconManager skillIconManager)
	{
		return formatRelationsWithSeparator(relations, skillIconManager, " OR ");
	}

	private static String formatRelationsWithSeparator(List<GoalView.RelationView> relations,
													   SkillIconManager skillIconManager, String separator)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < relations.size(); i++)
		{
			if (i > 0) sb.append(separator);
			GoalView.RelationView rv = relations.get(i);
			if (rv.skillName != null)
			{
				sb.append(FormatUtil.escapeHtml(formatSkillName(rv.skillName)))
					.append(" ").append(rv.targetLevel);
			}
			else
			{
				sb.append(FormatUtil.escapeHtml(rv.name));
			}
		}
		return sb.toString();
	}

	/**
	 * Convert a Skill enum name (e.g. "CRAFTING") to title case ("Crafting").
	 */
	private static String formatSkillName(String enumName)
	{
		if (enumName == null || enumName.isEmpty()) return enumName;
		return enumName.substring(0, 1).toUpperCase()
			+ enumName.substring(1).toLowerCase();
	}
}
