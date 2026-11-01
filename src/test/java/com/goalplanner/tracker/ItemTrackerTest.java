package com.goalplanner.tracker;

import com.goalplanner.api.GoalPlannerApiImpl;
import com.goalplanner.data.WikiCaRepository;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.persistence.GoalStore;
import com.goalplanner.service.GoalReorderingService;
import com.goalplanner.testsupport.InMemoryConfigManager;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ItemTracker}, the inventory+bank quantity tracker.
 *
 * <p>Covers the per-container memory: once a goal has seen the bank, a
 * container that can't be read keeps its last known count. Before that, only
 * growth past the persisted value is allowed.
 *
 * <p>Uses a real GoalStore + GoalPlannerApiImpl + ItemTracker against
 * Mockito-mocked Client / ItemContainer. The api is the canonical
 * mutation surface — trackers go through recordGoalProgress.
 */
class ItemTrackerTest
{
	private static final int CANNONBALL_ID = 2;

	private Client client;
	private ItemContainer inventoryContainer;
	private ItemContainer bankContainer;
	private GoalStore store;
	private GoalPlannerApiImpl api;
	private ItemTracker tracker;

	@BeforeEach
	void setUp()
	{
		client = mock(Client.class);
		inventoryContainer = mock(ItemContainer.class);
		bankContainer = mock(ItemContainer.class);

		ConfigManager configManager = InMemoryConfigManager.create();
		store = new GoalStore(configManager, new com.google.gson.Gson());
		store.load();

		ItemManager itemManager = mock(ItemManager.class);
		WikiCaRepository wikiCaRepository = mock(WikiCaRepository.class);
		GoalReorderingService reorderingService = new GoalReorderingService(store);
		api = new GoalPlannerApiImpl(store, reorderingService, itemManager, wikiCaRepository);

		tracker = new ItemTracker(client, api);
	}

	private Goal makeItemGoal(int itemId, int target)
	{
		Goal g = Goal.builder()
			.type(GoalType.ITEM_GRIND)
			.name("Cannonballs")
			.itemId(itemId)
			.targetValue(target)
			.currentValue(0)
			.build();
		store.addGoal(g);
		return g;
	}

	private Item mockItem(int id, int qty)
	{
		Item item = mock(Item.class);
		when(item.getId()).thenReturn(id);
		when(item.getQuantity()).thenReturn(qty);
		return item;
	}

	private void stubInventoryItems(Item... items)
	{
		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(items);
	}

	private void stubBankItems(Item... items)
	{
		when(client.getItemContainer(InventoryID.BANK)).thenReturn(bankContainer);
		when(bankContainer.getItems()).thenReturn(items);
	}

	private void stubBankNull()
	{
		when(client.getItemContainer(InventoryID.BANK)).thenReturn(null);
	}

	// ====================================================================
	// Bank loaded — full count behavior
	// ====================================================================

	@Test
	@DisplayName("With bank loaded, total count is inventory + bank")
	void fullCountSumsContainers()
	{
		Goal g = makeItemGoal(CANNONBALL_ID, 200);
		stubInventoryItems(mockItem(CANNONBALL_ID, 50));
		stubBankItems(mockItem(CANNONBALL_ID, 100));

		assertTrue(tracker.checkGoals(store.getGoals()));
		assertEquals(150, g.getCurrentValue());
	}

	@Test
	@DisplayName("With bank loaded, hitting target marks the goal complete")
	void fullCountFlipsCompleteOnTarget()
	{
		Goal g = makeItemGoal(CANNONBALL_ID, 200);
		stubInventoryItems(mockItem(CANNONBALL_ID, 100));
		stubBankItems(mockItem(CANNONBALL_ID, 100));

		assertTrue(tracker.checkGoals(store.getGoals()));
		assertEquals(200, g.getCurrentValue());
		assertTrue(g.isComplete());
	}

	@Test
	@DisplayName("A relog with no containers loaded keeps the persisted total")
	void relogKeepsPersistedTotal()
	{
		Goal g = makeItemGoal(CANNONBALL_ID, 200);
		stubInventoryItems(mockItem(CANNONBALL_ID, 50));
		stubBankItems(mockItem(CANNONBALL_ID, 100));
		assertTrue(tracker.checkGoals(store.getGoals()));
		assertEquals(150, g.getCurrentValue());

		stubBankNull();
		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(null);

		tracker.checkGoals(store.getGoals());
		assertEquals(150, g.getCurrentValue());
	}

	@Test
	@DisplayName("New loot adds to the banked count when the bank can't be read")
	void lootAddsToRememberedBank()
	{
		Goal g = makeItemGoal(CANNONBALL_ID, 300);
		stubInventoryItems();
		stubBankItems(mockItem(CANNONBALL_ID, 80));
		tracker.checkGoals(store.getGoals());
		assertEquals(80, g.getCurrentValue());

		stubBankNull();
		stubInventoryItems(mockItem(CANNONBALL_ID, 20));

		assertTrue(tracker.checkGoals(store.getGoals()));
		assertEquals(100, g.getCurrentValue());
	}

	@Test
	@DisplayName("A bank read holding no items does not wipe the banked count")
	void emptyBankReadKeepsRememberedCount()
	{
		Goal g = makeItemGoal(CANNONBALL_ID, 300);
		stubInventoryItems();
		stubBankItems(mockItem(CANNONBALL_ID, 80));
		tracker.checkGoals(store.getGoals());

		stubBankItems();
		stubInventoryItems(mockItem(CANNONBALL_ID, 20));
		tracker.checkGoals(store.getGoals());
		assertEquals(100, g.getCurrentValue());
	}

	@Test
	@DisplayName("A bank of only placeholders does not wipe the banked count")
	void placeholderBankReadKeepsRememberedCount()
	{
		Goal g = makeItemGoal(CANNONBALL_ID, 300);
		stubInventoryItems();
		stubBankItems(mockItem(CANNONBALL_ID, 80));
		tracker.checkGoals(store.getGoals());

		stubBankItems(mockItem(CANNONBALL_ID, 0));
		tracker.checkGoals(store.getGoals());
		assertEquals(80, g.getCurrentValue());
	}

	@Test
	@DisplayName("A real bank read replaces the remembered count, including going to zero")
	void bankReadWithOtherItemsLowersCount()
	{
		Goal g = makeItemGoal(CANNONBALL_ID, 300);
		stubInventoryItems();
		stubBankItems(mockItem(CANNONBALL_ID, 80));
		tracker.checkGoals(store.getGoals());

		stubBankItems(mockItem(999, 1));
		assertTrue(tracker.checkGoals(store.getGoals()));
		assertEquals(0, g.getCurrentValue());
	}

	@Test
	@DisplayName("Depositing items keeps the total but marks the new split for saving")
	void depositSavesNewSplit()
	{
		Goal g = makeItemGoal(CANNONBALL_ID, 300);
		stubInventoryItems(mockItem(CANNONBALL_ID, 20));
		stubBankItems(mockItem(CANNONBALL_ID, 80));
		tracker.checkGoals(store.getGoals());
		store.saveDirtyGoals();

		stubInventoryItems();
		stubBankItems(mockItem(CANNONBALL_ID, 100));

		assertTrue(tracker.checkGoals(store.getGoals()));
		assertEquals(100, g.getCurrentValue());
		assertTrue(store.getDirtyGoalIds().contains(g.getId()));
		assertEquals(100, g.getContainerCounts().get(InventoryID.BANK.getId()));
	}

	@Test
	@DisplayName("Remembered counts survive a save and reload")
	void containerCountsSurviveJson()
	{
		com.google.gson.Gson gson = new com.google.gson.Gson();
		Goal g = makeItemGoal(CANNONBALL_ID, 300);
		stubInventoryItems();
		stubBankItems(mockItem(CANNONBALL_ID, 80));
		tracker.checkGoals(store.getGoals());

		Goal reloaded = gson.fromJson(gson.toJson(g), Goal.class);
		assertEquals(80, reloaded.getContainerCounts().get(InventoryID.BANK.getId()));
	}

	@Test
	@DisplayName("A storage container read once keeps counting after a later bank read")
	void unreadStorageStillCounts()
	{
		Goal g = makeItemGoal(CANNONBALL_ID, 300);
		stubInventoryItems();
		stubBankItems(mockItem(CANNONBALL_ID, 10));
		stubContainer(InventoryID.SEED_VAULT, mockItem(CANNONBALL_ID, 5));
		tracker.checkGoals(store.getGoals());
		assertEquals(15, g.getCurrentValue());

		when(client.getItemContainer(InventoryID.SEED_VAULT)).thenReturn(null);
		tracker.checkGoals(store.getGoals());
		assertEquals(15, g.getCurrentValue());
	}

	@Test
	@DisplayName("A degraded/charge variant counts toward a goal for the base item")
	void variantCountsTowardGoal()
	{
		final int PRISTINE = 29025; // Blood moon tassets
		final int DEGRADED = 29045; // Blood moon tassets (degraded)
		// Precondition: RuneLite's variation data groups these under one base.
		assertEquals(
			net.runelite.client.game.ItemVariationMapping.map(PRISTINE),
			net.runelite.client.game.ItemVariationMapping.map(DEGRADED),
			"expected Blood moon tassets variants to share a variation base");

		Goal g = makeItemGoal(PRISTINE, 1);
		stubInventoryItems();
		stubBankItems(mockItem(DEGRADED, 1)); // owns only the degraded copy

		assertTrue(tracker.checkGoals(store.getGoals()));
		assertEquals(1, g.getCurrentValue());
		assertTrue(g.isComplete());
	}

	@Test
	@DisplayName("completed item goals are sticky and do not revert on drop")
	void completedItemGoalIsSticky()
	{
		Goal g = makeItemGoal(CANNONBALL_ID, 200);
		// First pass: full target
		stubInventoryItems(mockItem(CANNONBALL_ID, 100));
		stubBankItems(mockItem(CANNONBALL_ID, 100));
		tracker.checkGoals(store.getGoals());
		assertTrue(g.isComplete());
		assertEquals(200, g.getCurrentValue());

		// Second pass: dropped to 150 — goal should STAY complete and frozen at 200
		stubInventoryItems(mockItem(CANNONBALL_ID, 50));
		stubBankItems(mockItem(CANNONBALL_ID, 100));
		assertFalse(tracker.checkGoals(store.getGoals()));
		assertEquals(200, g.getCurrentValue());
		assertTrue(g.isComplete());
	}

	// ====================================================================
	// Pre-bank-visit guard — growth-only updates
	// ====================================================================

	@Test
	@DisplayName("Before first bank visit, allow growth from inventory-only")
	void preBankVisitAllowsGrowth()
	{
		Goal g = makeItemGoal(CANNONBALL_ID, 200);
		assertEquals(0, g.getCurrentValue());

		// Bank null, inventory has 50 — growth from 0 → 50 should land
		stubBankNull();
		stubInventoryItems(mockItem(CANNONBALL_ID, 50));

		assertTrue(tracker.checkGoals(store.getGoals()));
		assertEquals(50, g.getCurrentValue());
	}

	@Test
	@DisplayName("Before first bank visit, reject decreases (protects persisted value)")
	void preBankVisitRejectsDecrease()
	{
		Goal g = makeItemGoal(CANNONBALL_ID, 200);
		// Pretend the persisted value from a previous session was 1000
		g.setCurrentValue(1000);

		stubBankNull();
		stubInventoryItems(mockItem(CANNONBALL_ID, 50));

		assertFalse(tracker.checkGoals(store.getGoals()));
		assertEquals(1000, g.getCurrentValue()); // unchanged
	}

	@Test
	@DisplayName("After a bank read, losing the bank keeps it counted and carried items can still drop")
	void carriedDropsWhileBankRemembered()
	{
		Goal g = makeItemGoal(CANNONBALL_ID, 200);
		stubInventoryItems(mockItem(CANNONBALL_ID, 50));
		stubBankItems(mockItem(CANNONBALL_ID, 100));
		tracker.checkGoals(store.getGoals());
		assertEquals(150, g.getCurrentValue());

		stubBankNull();
		stubInventoryItems(mockItem(CANNONBALL_ID, 30));
		assertTrue(tracker.checkGoals(store.getGoals()));
		assertEquals(130, g.getCurrentValue());
	}

	// ====================================================================
	// Filtering / no-op cases
	// ====================================================================

	@Test
	@DisplayName("Skips non-ITEM_GRIND goals")
	void skipsNonItemGoals()
	{
		Goal skill = Goal.builder().type(GoalType.SKILL).name("Mining")
			.skillName("MINING").targetValue(100).build();
		store.addGoal(skill);

		stubInventoryItems();
		stubBankItems();

		assertFalse(tracker.checkGoals(store.getGoals()));
		assertEquals(0, skill.getCurrentValue());
	}

	@Test
	@DisplayName("Skips item goals with itemId <= 0")
	void skipsUnboundItemGoals()
	{
		Goal g = Goal.builder().type(GoalType.ITEM_GRIND).name("Unbound")
			.itemId(0).targetValue(100).build();
		store.addGoal(g);

		stubInventoryItems();
		stubBankItems();

		assertFalse(tracker.checkGoals(store.getGoals()));
	}

	@Test
	@DisplayName("Returns false when nothing changes (no-op detection)")
	void noopWhenCountUnchanged()
	{
		Goal g = makeItemGoal(CANNONBALL_ID, 200);
		stubInventoryItems(mockItem(CANNONBALL_ID, 50));
		stubBankItems(mockItem(CANNONBALL_ID, 100));

		// First pass: 0 → 150
		assertTrue(tracker.checkGoals(store.getGoals()));
		// Second pass with identical containers: should be no-op
		assertFalse(tracker.checkGoals(store.getGoals()));
	}

	// ====================================================================
	// Comprehensive container counting
	// ====================================================================

	private void stubContainer(InventoryID id, Item... items)
	{
		ItemContainer c = mock(ItemContainer.class);
		when(c.getItems()).thenReturn(items);
		when(client.getItemContainer(id)).thenReturn(c);
	}

	@Test
	@DisplayName("countItem sums equipment + seed vault + group storage when present")
	void countItemSumsAllContainers()
	{
		stubInventoryItems(mockItem(CANNONBALL_ID, 10));
		stubBankItems(mockItem(CANNONBALL_ID, 20));
		stubContainer(InventoryID.EQUIPMENT, mockItem(CANNONBALL_ID, 5));
		stubContainer(InventoryID.SEED_VAULT, mockItem(CANNONBALL_ID, 100));
		stubContainer(InventoryID.GROUP_STORAGE, mockItem(CANNONBALL_ID, 200));
		stubContainer(InventoryID.KINGDOM_OF_MISCELLANIA, mockItem(CANNONBALL_ID, 3));

		assertEquals(338, tracker.countItem(CANNONBALL_ID));
	}

	@Test
	@DisplayName("countItem ignores containers that return null")
	void countItemIgnoresMissingContainers()
	{
		// Only inventory + equipment populated; bank/seed/group/misc all null.
		stubInventoryItems(mockItem(CANNONBALL_ID, 7));
		stubContainer(InventoryID.EQUIPMENT, mockItem(CANNONBALL_ID, 1));

		assertEquals(8, tracker.countItem(CANNONBALL_ID));
	}

	@Test
	@DisplayName("countItem returns 0 when no containers populated")
	void countItemZeroWhenAllNull()
	{
		assertEquals(0, tracker.countItem(CANNONBALL_ID));
	}

	@Test
	@DisplayName("countItem only counts matching itemId, ignores other items in containers")
	void countItemFiltersById()
	{
		stubInventoryItems(mockItem(CANNONBALL_ID, 50), mockItem(999, 1000));
		stubContainer(InventoryID.EQUIPMENT, mockItem(999, 1));
		assertEquals(50, tracker.countItem(CANNONBALL_ID));
	}
}
