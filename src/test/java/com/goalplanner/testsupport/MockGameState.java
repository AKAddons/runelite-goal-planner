package com.goalplanner.testsupport;

import com.goalplanner.data.AchievementDiaryData;
import com.goalplanner.data.BossKillData;
import net.runelite.api.InventoryID;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable snapshot of OSRS game state for testing. Represents a player's
 * complete state (varps, varbits, skills, quests, items) as a data object
 * that can be copied and modified for before/after test scenarios.
 *
 * <p>Typical usage:
 * <pre>
 *   MockGameState before = new MockGameState().bossKills("Zulrah", 99);
 *   MockGameState after = before.copy().bossKills("Zulrah", 100);
 * </pre>
 *
 * <p>All fluent methods return {@code this} for chaining.
 */
public final class MockGameState
{
	/** VarPlayerID → value. Covers boss kills, quest points, CA packed varps. */
	private final Map<Integer, Integer> varps = new HashMap<>();

	/** VarbitID → value. Covers diaries, CA points, slayer points, kudos. */
	private final Map<Integer, Integer> varbits = new HashMap<>();

	/** Skill → XP. */
	private final Map<Skill, Integer> skillXp = new HashMap<>();

	/** Quest → state. */
	private final Map<Quest, QuestState> questStates = new HashMap<>();

	/** InventoryID → list of item stacks. */
	private final Map<InventoryID, List<MockItem>> itemContainers = new HashMap<>();

	private int totalLevel = 0;
	private int combatLevel = 3; // OSRS starting combat level
	private boolean seasonal = false; // true = WorldType.SEASONAL (leagues world)

	// -----------------------------------------------------------------
	// Item stack record
	// -----------------------------------------------------------------

	public static final class MockItem
	{
		public final int itemId;
		public final int quantity;

		public MockItem(int itemId, int quantity)
		{
			this.itemId = itemId;
			this.quantity = quantity;
		}
	}

	// -----------------------------------------------------------------
	// Raw state setters
	// -----------------------------------------------------------------

	/** Set a VarPlayer value directly. */
	public MockGameState varp(int varpId, int value)
	{
		varps.put(varpId, value);
		return this;
	}

	/** Set a Varbit value directly. */
	public MockGameState varbit(int varbitId, int value)
	{
		varbits.put(varbitId, value);
		return this;
	}

	// -----------------------------------------------------------------
	// Boss kill convenience
	// -----------------------------------------------------------------

	/**
	 * Set kill count for a boss by display name.
	 * Looks up the VarPlayerID via {@link BossKillData}.
	 *
	 * @throws IllegalArgumentException if the boss name is unknown
	 */
	public MockGameState bossKills(String bossName, int killCount)
	{
		int varpId = BossKillData.getVarpId(bossName);
		if (varpId < 0)
		{
			throw new IllegalArgumentException("Unknown boss: " + bossName);
		}
		return varp(varpId, killCount);
	}

	// -----------------------------------------------------------------
	// Diary convenience
	// -----------------------------------------------------------------

	/**
	 * Mark a diary tier as complete by area display name and tier.
	 * Looks up the tracking spec via {@link AchievementDiaryData} and sets
	 * the varbit to the required completion value (1 for boolean COMPLETE
	 * varbits, the tier task count for Karamja count varbits).
	 *
	 * @throws IllegalArgumentException if no tracking varbit exists for this diary
	 */
	public MockGameState diaryComplete(String areaDisplayName, AchievementDiaryData.Tier tier)
	{
		AchievementDiaryData.Tracking tracking = AchievementDiaryData.tracking(areaDisplayName, tier);
		if (tracking == null)
		{
			throw new IllegalArgumentException(
				"No tracking varbit for diary: " + areaDisplayName + " " + tier);
		}
		return varbit(tracking.varbitId, tracking.requiredValue);
	}

	// -----------------------------------------------------------------
	// Combat Achievement convenience
	// -----------------------------------------------------------------

	/**
	 * Mark a CA task as complete by task ID.
	 * Handles the bit-packing into the correct CA_TASK_COMPLETED varp.
	 */
	public MockGameState caTaskComplete(int caTaskId)
	{
		int varpIndex = caTaskId / 32;
		int bitIndex = caTaskId % 32;
		int varpId = getCaVarpId(varpIndex);
		int current = varps.getOrDefault(varpId, 0);
		return varp(varpId, current | (1 << bitIndex));
	}

	private static int getCaVarpId(int index)
	{
		// Mirror the CA_VARPS array from CombatAchievementTracker
		switch (index)
		{
			case 0: return VarPlayerID.CA_TASK_COMPLETED_0;
			case 1: return VarPlayerID.CA_TASK_COMPLETED_1;
			case 2: return VarPlayerID.CA_TASK_COMPLETED_2;
			case 3: return VarPlayerID.CA_TASK_COMPLETED_3;
			case 4: return VarPlayerID.CA_TASK_COMPLETED_4;
			case 5: return VarPlayerID.CA_TASK_COMPLETED_5;
			case 6: return VarPlayerID.CA_TASK_COMPLETED_6;
			case 7: return VarPlayerID.CA_TASK_COMPLETED_7;
			case 8: return VarPlayerID.CA_TASK_COMPLETED_8;
			case 9: return VarPlayerID.CA_TASK_COMPLETED_9;
			case 10: return VarPlayerID.CA_TASK_COMPLETED_10;
			case 11: return VarPlayerID.CA_TASK_COMPLETED_11;
			case 12: return VarPlayerID.CA_TASK_COMPLETED_12;
			case 13: return VarPlayerID.CA_TASK_COMPLETED_13;
			case 14: return VarPlayerID.CA_TASK_COMPLETED_14;
			case 15: return VarPlayerID.CA_TASK_COMPLETED_15;
			case 16: return VarPlayerID.CA_TASK_COMPLETED_16;
			case 17: return VarPlayerID.CA_TASK_COMPLETED_17;
			case 18: return VarPlayerID.CA_TASK_COMPLETED_18;
			case 19: return VarPlayerID.CA_TASK_COMPLETED_19;
			case 20: return VarPlayerID.CA_TASK_COMPLETED_20;
			default:
				throw new IllegalArgumentException("CA varp index out of range: " + index);
		}
	}

	// -----------------------------------------------------------------
	// Skill convenience
	// -----------------------------------------------------------------

	/** Set XP for a skill. */
	public MockGameState skillXp(Skill skill, int xp)
	{
		this.skillXp.put(skill, xp);
		return this;
	}

	// -----------------------------------------------------------------
	// Quest convenience
	// -----------------------------------------------------------------

	/** Set a quest to a specific state. */
	public MockGameState questState(Quest quest, QuestState state)
	{
		questStates.put(quest, state);
		return this;
	}

	/** Shorthand for marking a quest FINISHED. */
	public MockGameState questFinished(Quest quest)
	{
		return questState(quest, QuestState.FINISHED);
	}

	// -----------------------------------------------------------------
	// Item convenience
	// -----------------------------------------------------------------

	/** Add an item stack to a specific container. */
	public MockGameState addItem(InventoryID container, int itemId, int quantity)
	{
		itemContainers
			.computeIfAbsent(container, k -> new ArrayList<>())
			.add(new MockItem(itemId, quantity));
		return this;
	}

	/** Shorthand: add to inventory. */
	public MockGameState inventory(int itemId, int quantity)
	{
		return addItem(InventoryID.INVENTORY, itemId, quantity);
	}

	/** Shorthand: add to bank. */
	public MockGameState bank(int itemId, int quantity)
	{
		return addItem(InventoryID.BANK, itemId, quantity);
	}

	// -----------------------------------------------------------------
	// Account-level scalars
	// -----------------------------------------------------------------

	public MockGameState totalLevel(int level)
	{
		this.totalLevel = level;
		return this;
	}

	public MockGameState combatLevel(int level)
	{
		this.combatLevel = level;
		return this;
	}

	/** Set quest points via the QP VarPlayer. */
	public MockGameState questPoints(int qp)
	{
		return varp(VarPlayerID.QP, qp);
	}

	/**
	 * Mark this state as on a SEASONAL (leagues) world. Default is main.
	 * Used by the tracker's world-scope guard to decide which goals to
	 * track (leagues metrics on seasonal, everything else on main).
	 */
	public MockGameState seasonal(boolean seasonal)
	{
		this.seasonal = seasonal;
		return this;
	}

	// -----------------------------------------------------------------
	// Copy (for before/after scenarios)
	// -----------------------------------------------------------------

	/**
	 * Deep copy this state. The returned copy can be modified independently
	 * without affecting the original.
	 */
	public MockGameState copy()
	{
		MockGameState clone = new MockGameState();
		clone.varps.putAll(this.varps);
		clone.varbits.putAll(this.varbits);
		clone.skillXp.putAll(this.skillXp);
		clone.questStates.putAll(this.questStates);
		for (Map.Entry<InventoryID, List<MockItem>> e : this.itemContainers.entrySet())
		{
			clone.itemContainers.put(e.getKey(), new ArrayList<>(e.getValue()));
		}
		clone.totalLevel = this.totalLevel;
		clone.combatLevel = this.combatLevel;
		clone.seasonal = this.seasonal;
		return clone;
	}

	// -----------------------------------------------------------------
	// Accessors (package-private, used by MockClientFactory)
	// -----------------------------------------------------------------

	Map<Integer, Integer> getVarps() { return varps; }
	Map<Integer, Integer> getVarbits() { return varbits; }
	Map<Skill, Integer> getSkillXp() { return skillXp; }
	Map<Quest, QuestState> getQuestStates() { return questStates; }
	Map<InventoryID, List<MockItem>> getItemContainers() { return itemContainers; }
	int getTotalLevel() { return totalLevel; }
	int getCombatLevel() { return combatLevel; }
	boolean isSeasonal() { return seasonal; }
}
