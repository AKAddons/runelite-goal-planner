package com.goalplanner.tracker;

import com.goalplanner.api.GoalPlannerApiImpl;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalStatus;
import com.goalplanner.model.GoalType;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.game.ItemVariationMapping;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks item/resource quantity goals by checking inventory and bank.
 * Overrides the base checkGoals() because each goal remembers its count per
 * container, and because of terminal completion logic that doesn't fit the
 * standard loop.
 */
@Slf4j
@Singleton
public class ItemTracker extends AbstractTracker
{
	/**
	 * Persistent storage containers we sum across when counting items.
	 * Transient reward chests are intentionally excluded.
	 */
	private static final InventoryID[] COUNTED_CONTAINERS = {
		InventoryID.INVENTORY,
		InventoryID.BANK,
		InventoryID.EQUIPMENT,
		InventoryID.SEED_VAULT,
		InventoryID.GROUP_STORAGE,
		InventoryID.KINGDOM_OF_MISCELLANIA,
	};

	/** Last bank read state logged: "missing", "empty" or "read". */
	private String lastBankRead;

	@Inject
	public ItemTracker(Client client, GoalPlannerApiImpl api)
	{
		super(client, api);
	}

	@Override
	protected GoalType targetType()
	{
		return GoalType.ITEM_GRIND;
	}

	@Override
	protected int readCurrentValue(Goal goal)
	{
		return countItem(goal.getItemId());
	}

	/**
	 * Overrides the base loop so a goal's total is the sum of its remembered
	 * per-container counts. A container the client can't read right now keeps
	 * its last known count, so a missing or empty bank read can't wipe a total
	 * and new loot adds to what was banked.
	 *
	 * <p>Until a goal has seen the bank it has nothing to sum, so it only grows
	 * past its stored value. That covers new goals and goals saved before
	 * per-container counts existed.
	 */
	@Override
	public boolean checkGoals(List<Goal> goals)
	{
		boolean anyUpdated = false;
		// Read every container ONCE, then each goal is a lookup per container, so
		// N item goals cost one container scan rather than N. Built lazily so a
		// tick with no active item goals scans nothing.
		Map<Integer, Map<Integer, Integer>> readable = null;

		for (Goal goal : goals)
		{
			if (goal.getType() != GoalType.ITEM_GRIND)
			{
				continue;
			}

			// Terminal once complete - dropping below target does NOT
			// auto-revert. User must manually mark incomplete.
			if (goal.getStatus() == GoalStatus.COMPLETE)
			{
				continue;
			}

			if (goal.getItemId() <= 0)
			{
				continue;
			}

			if (readable == null)
			{
				readable = snapshotReadableContainers();
			}
			int base = ItemVariationMapping.map(goal.getItemId());

			Map<Integer, Integer> counts = new HashMap<>();
			if (goal.getContainerCounts() != null)
			{
				counts.putAll(goal.getContainerCounts());
			}
			for (Map.Entry<Integer, Map<Integer, Integer>> container : readable.entrySet())
			{
				counts.put(container.getKey(), container.getValue().getOrDefault(base, 0));
			}
			int total = counts.values().stream().mapToInt(Integer::intValue).sum();

			if (counts.containsKey(InventoryID.BANK.getId()))
			{
				if (api.recordGoalContainerCounts(goal.getId(), counts))
				{
					anyUpdated = true;
				}
			}
			else if (total <= goal.getCurrentValue())
			{
				continue;
			}

			if (api.recordGoalProgress(goal.getId(), total))
			{
				anyUpdated = true;
			}
		}

		return anyUpdated;
	}

	/**
	 * Read every counted container once into {@code container id → base
	 * variation id → quantity}. Variations of one item (charge/degrade states)
	 * collapse to a single base id via {@link ItemVariationMapping}. Null
	 * containers are left out, and so are storage containers holding nothing:
	 * an empty read is not evidence the items are gone. Inventory and equipment
	 * really can be empty, so those count as read.
	 */
	private Map<Integer, Map<Integer, Integer>> snapshotReadableContainers()
	{
		Map<Integer, Map<Integer, Integer>> readable = new HashMap<>();
		for (InventoryID id : COUNTED_CONTAINERS)
		{
			ItemContainer container = client.getItemContainer(id);
			if (container == null)
			{
				continue;
			}
			Map<Integer, Integer> counts = new HashMap<>();
			for (Item item : container.getItems())
			{
				if (item.getId() > 0 && item.getQuantity() > 0)
				{
					counts.merge(ItemVariationMapping.map(item.getId()), item.getQuantity(), Integer::sum);
				}
			}
			if (!counts.isEmpty() || id == InventoryID.INVENTORY || id == InventoryID.EQUIPMENT)
			{
				readable.put(id.getId(), counts);
			}
		}
		logBankRead(readable.containsKey(InventoryID.BANK.getId()));
		return readable;
	}

	/** Log bank read changes, to show what a bad read looks like in the field. */
	private void logBankRead(boolean read)
	{
		String state = read ? "read" : client.getItemContainer(InventoryID.BANK) == null ? "missing" : "empty";
		if (!state.equals(lastBankRead))
		{
			log.debug("Bank container {} -> {} (game state {})", lastBankRead, state, client.getGameState());
			lastBankRead = state;
		}
	}

	/**
	 * Count total quantity of an item across every storage container.
	 * Public so the create-goal UI can snapshot a baseline for relative
	 * item goals.
	 *
	 * <p>Counts ALL variations of the item, not just the exact id: a goal for a
	 * degradable/chargeable item completes whether the owned copy is pristine,
	 * degraded, broken, charged, or uncharged. RuneLite's {@link
	 * ItemVariationMapping} groups these under one base id (e.g. Blood moon
	 * tassets / (degraded) / (broken)); an item with no variations maps to
	 * itself, so non-variant items count exactly as before.
	 */
	public int countItem(int itemId)
	{
		int base = ItemVariationMapping.map(itemId);
		int count = 0;
		for (InventoryID id : COUNTED_CONTAINERS)
		{
			count += countInContainer(id, base);
		}
		return count;
	}

	private int countInContainer(InventoryID containerId, int base)
	{
		ItemContainer container = client.getItemContainer(containerId);
		if (container == null)
		{
			return 0;
		}

		int count = 0;
		for (Item item : container.getItems())
		{
			// Same variation family as the goal's item → counts toward it.
			if (ItemVariationMapping.map(item.getId()) == base)
			{
				count += item.getQuantity();
			}
		}
		return count;
	}
}
