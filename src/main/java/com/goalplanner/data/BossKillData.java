package com.goalplanner.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Maps boss display names to their VarPlayerID kill count variables.
 * Used by BossKillTracker to read live kill counts and by the
 * Add Goal dialog to populate the boss dropdown.
 *
 * <p>Sorted alphabetically by display name for the dropdown.
 */
public final class BossKillData
{
	/** Boss name → VarPlayerID for kill count. */
	private static final Map<String, Integer> BOSSES = new LinkedHashMap<>();

	static
	{
		loadIntMap("boss-killcount.tsv", BOSSES);
	}

	/** Boss name → pet item ID for the card icon. 0 = no pet icon. */
	private static final Map<String, Integer> PET_ICONS = new HashMap<>();

	static
	{
		loadIntMap("boss-pets.tsv", PET_ICONS);
	}

	/**
	 * Collection log name → list of matching boss data names.
	 * Used when right-clicking raid entries in the collection log
	 * to show one menu entry per tier.
	 */
	private static final Map<String, List<String>> COLLECTION_LOG_ALIASES = new HashMap<>();

	static
	{
		loadAliases("boss-cl-aliases.tsv", COLLECTION_LOG_ALIASES);
	}

	// ============================================================
	// Boss prerequisites - skill/unlock requirements to fight a boss.
	// Applied automatically by addBossGoal regardless of call site.
	// ============================================================

	/**
	 * Prerequisites to fight a specific boss. Mirrors the shape of
	 * {@link DiaryRequirements.Reqs} so boss gating has parity with
	 * diary-tier gating (skills, quests, unlocks, item reqs, account
	 * metrics, boss-kill prereqs, and OR-alternatives).
	 */
	public static final class BossPrereqs
	{
		public final List<SkillReq> skills;
		public final List<UnlockRef> unlocks;
		public final List<net.runelite.api.Quest> quests;
		public final List<ItemReq> itemReqs;
		public final List<AccountReq> accountReqs;
		public final List<BossReq> bossKills;
		/** OR-groups: if non-empty, ANY one satisfied unlocks the boss. */
		public final List<Alternative> alternatives;

		public BossPrereqs(List<SkillReq> skills, List<UnlockRef> unlocks)
		{
			this(skills, unlocks, Collections.emptyList());
		}

		public BossPrereqs(List<SkillReq> skills)
		{
			this(skills, Collections.emptyList(), Collections.emptyList());
		}

		public BossPrereqs(List<SkillReq> skills, List<UnlockRef> unlocks,
			List<net.runelite.api.Quest> quests)
		{
			this(skills, unlocks, quests,
				Collections.emptyList(), Collections.emptyList(),
				Collections.emptyList(), Collections.emptyList());
		}

		public BossPrereqs(List<SkillReq> skills, List<UnlockRef> unlocks,
			List<net.runelite.api.Quest> quests, List<BossReq> bossKills)
		{
			this(skills, unlocks, quests, bossKills,
				Collections.emptyList(), Collections.emptyList(),
				Collections.emptyList());
		}

		public BossPrereqs(List<SkillReq> skills, List<UnlockRef> unlocks,
			List<net.runelite.api.Quest> quests, List<BossReq> bossKills,
			List<ItemReq> itemReqs)
		{
			this(skills, unlocks, quests, bossKills, itemReqs,
				Collections.emptyList(), Collections.emptyList());
		}

		public BossPrereqs(List<SkillReq> skills, List<UnlockRef> unlocks,
			List<net.runelite.api.Quest> quests, List<BossReq> bossKills,
			List<ItemReq> itemReqs, List<AccountReq> accountReqs)
		{
			this(skills, unlocks, quests, bossKills, itemReqs, accountReqs,
				Collections.emptyList());
		}

		public BossPrereqs(List<SkillReq> skills, List<UnlockRef> unlocks,
			List<net.runelite.api.Quest> quests, List<BossReq> bossKills,
			List<ItemReq> itemReqs, List<AccountReq> accountReqs,
			List<Alternative> alternatives)
		{
			this.skills = Collections.unmodifiableList(skills);
			this.unlocks = Collections.unmodifiableList(unlocks);
			this.quests = Collections.unmodifiableList(quests);
			this.bossKills = Collections.unmodifiableList(bossKills);
			this.itemReqs = Collections.unmodifiableList(itemReqs);
			this.accountReqs = Collections.unmodifiableList(accountReqs);
			this.alternatives = Collections.unmodifiableList(alternatives);
		}
	}

	/** A skill requirement for a boss. */
	public static final class SkillReq
	{
		public final Skill skill;
		public final int level;

		public SkillReq(Skill skill, int level)
		{
			this.skill = skill;
			this.level = level;
		}
	}

	/** Reference to an unlock (by name + item icon). */
	public static final class UnlockRef
	{
		public final String name;
		public final int itemId;
		public final List<SkillReq> optionalSkills;

		public UnlockRef(String name, int itemId, List<SkillReq> optionalSkills)
		{
			this.name = name;
			this.itemId = itemId;
			this.optionalSkills = Collections.unmodifiableList(optionalSkills);
		}
	}

	/** An item requirement (e.g. specific gear/key/scroll to enter fight). */
	public static final class ItemReq
	{
		public final int itemId;
		public final String displayName;
		public final int quantity;

		public ItemReq(int itemId, String displayName, int quantity)
		{
			this.itemId = itemId;
			this.displayName = displayName;
			this.quantity = quantity;
		}
	}

	/** An account metric requirement (e.g. combat level threshold). */
	public static final class AccountReq
	{
		public final String metricName; // AccountMetric enum name
		public final int target;

		public AccountReq(String metricName, int target)
		{
			this.metricName = metricName;
			this.target = target;
		}
	}

	/** A boss-kill prerequisite (e.g. base DT2 kill before awakened). */
	public static final class BossReq
	{
		public final String bossName;
		public final int killCount;

		public BossReq(String bossName, int killCount)
		{
			this.bossName = bossName;
			this.killCount = killCount;
		}
	}

	/**
	 * An alternative path within a boss prereq set. Multiple alternatives
	 * form an OR-group: ANY one being satisfied unlocks the boss.
	 */
	public static final class Alternative
	{
		public final String label;
		public final List<SkillReq> skills;
		public final List<AccountReq> accounts;
		public final List<BossReq> bosses;
		public final List<net.runelite.api.Quest> quests;

		public Alternative(String label, List<SkillReq> skills,
			List<AccountReq> accounts)
		{
			this(label, skills, accounts, Collections.emptyList(), Collections.emptyList());
		}

		public Alternative(String label, List<SkillReq> skills,
			List<AccountReq> accounts, List<BossReq> bosses)
		{
			this(label, skills, accounts, bosses, Collections.emptyList());
		}

		public Alternative(String label, List<SkillReq> skills,
			List<AccountReq> accounts, List<BossReq> bosses,
			List<net.runelite.api.Quest> quests)
		{
			this.label = label;
			this.skills = Collections.unmodifiableList(skills);
			this.accounts = Collections.unmodifiableList(accounts);
			this.bosses = Collections.unmodifiableList(bosses);
			this.quests = Collections.unmodifiableList(quests);
		}
	}

	private static final Map<String, BossPrereqs> BOSS_PREREQS = new HashMap<>();

	private static volatile boolean prereqsLoaded;

	/**
	 * Loads the boss prerequisite table from its bundled JSON resource.
	 * Idempotent and thread-safe; the first caller wins. Pass the client's
	 * injected {@link com.google.gson.Gson} (the plugin must never create
	 * its own - the hub forbids it). Called from GoalPlannerPlugin.startUp().
	 */
	public static void init(com.google.gson.Gson gson)
	{
		if (prereqsLoaded)
		{
			return;
		}
		synchronized (BossKillData.class)
		{
			if (prereqsLoaded)
			{
				return;
			}
			try (InputStream in = BossKillData.class.getResourceAsStream("boss-prereqs.json"))
			{
				if (in == null)
				{
					throw new IllegalStateException("missing resource: boss-prereqs.json");
				}
				try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
				{
					BOSS_PREREQS.putAll(gson.fromJson(reader,
						new com.google.gson.reflect.TypeToken<Map<String, BossPrereqs>>(){}.getType()));
				}
			}
			catch (IOException e)
			{
				throw new UncheckedIOException("failed to load boss-prereqs.json", e);
			}
			prereqsLoaded = true;
		}
	}

	/**
	 * Get prerequisites for a boss. Returns null if no prereqs defined.
	 */
	public static BossPrereqs getPrereqs(String bossName)
	{
		return BOSS_PREREQS.get(bossName);
	}

	/**
	 * Resolve a collection log entry name to matching boss data names.
	 * Returns a list of boss names (one per tier/variant) if the name
	 * maps to multiple entries, or a single-element list for exact
	 * matches. Returns empty if unknown.
	 */
	public static List<String> resolveCollectionLogName(String name)
	{
		if (name == null) return Collections.emptyList();
		List<String> aliases = COLLECTION_LOG_ALIASES.get(name);
		if (aliases != null) return aliases;
		if (BOSSES.containsKey(name)) return List.of(name);
		return Collections.emptyList();
	}

	/**
	 * All boss names in alphabetical order for the dropdown.
	 */
	public static String[] getBossNames()
	{
		String[] names = BOSSES.keySet().toArray(new String[0]);
		Arrays.sort(names);
		return names;
	}

	/**
	 * Look up the VarPlayerID for a boss. Returns -1 if unknown.
	 */
	public static int getVarpId(String bossName)
	{
		Integer id = BOSSES.get(bossName);
		return id != null ? id : -1;
	}

	/**
	 * Check if a boss name is known.
	 */
	public static boolean isKnownBoss(String bossName)
	{
		return BOSSES.containsKey(bossName);
	}

	/**
	 * Get the pet item ID for a boss (used as the goal card icon).
	 * Returns 0 if no pet mapping exists.
	 */
	public static int getPetItemId(String bossName)
	{
		Integer id = PET_ICONS.get(bossName);
		return id != null ? id : 0;
	}

	private static void loadIntMap(String resource, Map<String, Integer> map)
	{
		try (InputStream in = BossKillData.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				throw new IllegalStateException("missing resource: " + resource);
			}
			try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(in, StandardCharsets.UTF_8)))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					if (line.isEmpty()) continue;
					int t = line.indexOf('\t');
					map.put(line.substring(0, t), Integer.parseInt(line.substring(t + 1)));
				}
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("failed to load " + resource, e);
		}
	}

	private static void loadAliases(String resource, Map<String, List<String>> map)
	{
		try (InputStream in = BossKillData.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				throw new IllegalStateException("missing resource: " + resource);
			}
			try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(in, StandardCharsets.UTF_8)))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					if (line.isEmpty()) continue;
					int t = line.indexOf('\t');
					map.put(line.substring(0, t),
						List.of(line.substring(t + 1).split(";")));
				}
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("failed to load " + resource, e);
		}
	}


	private BossKillData() {}

	/**
	 * Test-only hook: swap the prereqs for a boss and return the previous
	 * entry so tests can restore after running. Used to validate seeding
	 * code paths (e.g. Alternative OR-group seeding) without shipping a
	 * production boss whose prereqs trigger the path.
	 *
	 * <p>Do not call from production code. Tests MUST restore the previous
	 * value in an @AfterEach to avoid cross-test state leakage.
	 */
	static BossPrereqs swapPrereqsForTest(String bossName, BossPrereqs prereqs)
	{
		if (prereqs == null)
		{
			return BOSS_PREREQS.remove(bossName);
		}
		return BOSS_PREREQS.put(bossName, prereqs);
	}
}
