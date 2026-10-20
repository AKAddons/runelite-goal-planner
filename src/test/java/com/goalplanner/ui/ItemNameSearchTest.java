package com.goalplanner.ui;

import java.util.List;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The dock's item picker used to search the Grand Exchange price list, so an
 * untradeable item could never be found. This scans item definitions instead.
 */
class ItemNameSearchTest
{
	private Client client;
	private ItemManager items;

	private static ItemComposition def(int id, String name, int note, int placeholder, boolean tradeable)
	{
		ItemComposition c = mock(ItemComposition.class);
		when(c.getId()).thenReturn(id);
		when(c.getName()).thenReturn(name);
		when(c.getNote()).thenReturn(note);
		when(c.getPlaceholderTemplateId()).thenReturn(placeholder);
		when(c.isTradeable()).thenReturn(tradeable);
		return c;
	}

	@BeforeEach
	void setUp()
	{
		client = mock(Client.class);
		items = mock(ItemManager.class);
		// Build every composition BEFORE stubbing the manager: a def() call inside
		// when(...).thenReturn(...) nests stubbings and Mockito rejects it.
		ItemComposition crushedStar = def(0, "Crushed star", -1, -1, true);
		ItemComposition stardust = def(1, "Stardust", -1, -1, false);
		ItemComposition notedStardust = def(2, "Stardust", 1, -1, false);
		ItemComposition nullName = def(3, "null", -1, -1, false);
		ItemComposition placeholder = def(4, "Shooting star", -1, 5, false);
		when(client.getItemCount()).thenReturn(5);
		when(items.getItemComposition(0)).thenReturn(crushedStar);
		when(items.getItemComposition(1)).thenReturn(stardust);
		when(items.getItemComposition(2)).thenReturn(notedStardust);
		when(items.getItemComposition(3)).thenReturn(nullName);
		when(items.getItemComposition(4)).thenReturn(placeholder);
	}

	private List<String> names(String query, int max)
	{
		return ItemNameSearch.search(client, items, query, max).stream()
			.map(h -> h.id + ":" + h.name).collect(Collectors.toList());
	}

	@Test
	@DisplayName("an untradeable item is found by name, and its noted twin is not")
	void findsUntradeableSkipsNoted()
	{
		assertEquals(List.of("1:Stardust"), names("stardust", 15));
	}

	@Test
	@DisplayName("a prefix match ranks ahead of a contains match even with a lower id, placeholders are skipped")
	void prefixBeforeContains()
	{
		assertEquals(List.of("1:Stardust", "0:Crushed star"), names("star", 15));
	}

	@Test
	@DisplayName("a blank query finds nothing")
	void blankFindsNothing()
	{
		assertTrue(names("   ", 15).isEmpty());
	}

	@Test
	@DisplayName("results stop at the cap")
	void capsResults()
	{
		assertEquals(1, names("star", 1).size());
	}
}
