package com.goalplanner.ui;

import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache for scaled item images. Uses AsyncBufferedImage.onLoaded()
 * to properly handle RuneLite's async image loading.
 */
public final class ItemImageCache
{
	private ItemImageCache() {}

	private static final ConcurrentHashMap<Integer, ImageIcon> CACHE = new ConcurrentHashMap<>();

	/**
	 * Get a cached icon, or null if not yet loaded.
	 */
	public static ImageIcon get(int itemId)
	{
		return CACHE.get(itemId);
	}

	/**
	 * Set a JLabel's icon to the item image. Handles async loading properly.
	 * If cached, sets immediately. If not, sets when loaded via callback.
	 */
	public static void applyTo(JLabel label, int itemId, ItemManager itemManager)
	{
		ImageIcon cached = CACHE.get(itemId);
		if (cached != null)
		{
			label.setIcon(cached);
			return;
		}

		// Request async image and register callback
		AsyncBufferedImage abi = itemManager.getImage(itemId);
		abi.onLoaded(() ->
		{
			BufferedImage scaled = new BufferedImage(18, 18, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g2d = scaled.createGraphics();
			g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2d.drawImage(abi, 0, 0, 18, 18, null);
			g2d.dispose();

			ImageIcon icon = new ImageIcon(scaled);
			CACHE.put(itemId, icon);

			SwingUtilities.invokeLater(() ->
			{
				label.setIcon(icon);
				label.revalidate();
				label.repaint();
			});
		});
	}
}
