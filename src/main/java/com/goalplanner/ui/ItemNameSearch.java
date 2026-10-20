package com.goalplanner.ui;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;

/**
 * Item search by name over the game's item definitions, so untradeable items
 * (Stardust, quest items, minigame rewards) are found. {@link ItemManager#search}
 * only knows the Grand Exchange price list. Call on the client thread.
 */
final class ItemNameSearch
{
	/** One search hit: the item id and its display name. */
	static final class Hit
	{
		final int id;
		final String name;

		Hit(int id, String name)
		{
			this.id = id;
			this.name = name;
		}
	}

	private ItemNameSearch()
	{
	}

	static List<Hit> search(Client client, ItemManager items, String query, int max)
	{
		List<Hit> starts = new ArrayList<>();
		List<Hit> contains = new ArrayList<>();
		String q = query == null ? "" : query.trim().toLowerCase();
		if (q.isEmpty() || max <= 0)
		{
			return starts;
		}
		int count = client.getItemCount();
		for (int id = 0; id < count && starts.size() < max; id++)
		{
			ItemComposition def = items.getItemComposition(id);
			if (def == null || def.getNote() != -1 || def.getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			String name = def.getName();
			if (name == null || name.equals("null"))
			{
				continue;
			}
			String lower = name.toLowerCase();
			if (lower.startsWith(q))
			{
				starts.add(new Hit(id, name));
			}
			else if (lower.contains(q) && starts.size() + contains.size() < max)
			{
				contains.add(new Hit(id, name));
			}
		}
		for (Hit h : contains)
		{
			if (starts.size() >= max)
			{
				break;
			}
			starts.add(h);
		}
		return starts;
	}
}
