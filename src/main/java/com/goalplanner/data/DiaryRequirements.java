package com.goalplanner.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ItemID;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Pre-defined achievement diary tier requirements (skill levels,
 * quest prerequisites, and unlock milestones). Keyed on
 * "{Area}|{TIER}" strings matching {@link AchievementDiaryData.Tier}.
 *
 * <p><b>Unlocks</b> are virtual milestones like "Fairy Rings" that
 * represent in-game capabilities with their own prerequisite trees.
 * They're seeded as CUSTOM goals with quest requirements attached,
 * replacing misleading quest entries (e.g. Fairytale II when only
 * fairy ring access is needed).
 */
public final class DiaryRequirements
{
	/** A single skill requirement. */
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

	/**
	 * A virtual unlock milestone (e.g. "Fairy Rings Unlocked").
	 * Seeded as a CUSTOM goal with quest prerequisites.
	 */
	public static final class Unlock
	{
		public final String name;
		public final List<Quest> prereqQuests;
		public final List<SkillReq> prereqSkills;
		public final List<AccountReq> prereqAccounts;
		/** Alternative paths (OR-group). Empty = no alternatives. */
		public final List<Alternative> alternatives;
		/** Item ID for the icon (e.g. fairy ring POH item). 0 = no icon. */
		public final int itemId;

		public Unlock(String name, List<Quest> prereqQuests, int itemId)
		{
			this(name, prereqQuests, Collections.emptyList(), Collections.emptyList(),
				Collections.emptyList(), itemId);
		}

		public Unlock(String name, List<Quest> prereqQuests, List<SkillReq> prereqSkills, int itemId)
		{
			this(name, prereqQuests, prereqSkills, Collections.emptyList(),
				Collections.emptyList(), itemId);
		}

		public Unlock(String name, List<Quest> prereqQuests, List<SkillReq> prereqSkills,
			List<AccountReq> prereqAccounts, int itemId)
		{
			this(name, prereqQuests, prereqSkills, prereqAccounts,
				Collections.emptyList(), itemId);
		}

		public Unlock(String name, List<Quest> prereqQuests, List<SkillReq> prereqSkills,
			List<AccountReq> prereqAccounts, List<Alternative> alternatives, int itemId)
		{
			this.name = name;
			this.prereqQuests = Collections.unmodifiableList(prereqQuests);
			this.prereqSkills = Collections.unmodifiableList(prereqSkills);
			this.prereqAccounts = Collections.unmodifiableList(prereqAccounts);
			this.alternatives = Collections.unmodifiableList(alternatives);
			this.itemId = itemId;
		}
	}

	/** A boss kill requirement (e.g. 1 Kalphite Queen kill for Desert Hard).
	 *  Boss-specific prereqs (skills, unlocks) are defined in
	 *  {@link BossKillData#getPrereqs} and auto-seeded by addBossGoal. */
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

	/** An account metric requirement (e.g. combined Att+Str 130). */
	public static final class AccountReq
	{
		public final String metricName; // AccountMetric enum name
		public final int target;
		public final List<Quest> prereqQuests;

		public AccountReq(String metricName, int target)
		{
			this(metricName, target, Collections.emptyList());
		}

		public AccountReq(String metricName, int target, List<Quest> prereqQuests)
		{
			this.metricName = metricName;
			this.target = target;
			this.prereqQuests = Collections.unmodifiableList(prereqQuests);
		}
	}

	/** An item requirement (e.g. KQ head for Desert Elite). */
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

	/** Full requirement set for a diary tier. */
	public static final class Reqs
	{
		public final List<SkillReq> skills;
		public final List<Quest> prereqQuests;
		public final List<Unlock> unlocks;
		public final List<BossReq> bossKills;
		public final List<ItemReq> itemReqs;
		public final List<AccountReq> accountReqs;

		public Reqs(List<SkillReq> skills, List<Quest> prereqQuests)
		{
			this(skills, prereqQuests, Collections.emptyList(), Collections.emptyList(),
				Collections.emptyList(), Collections.emptyList());
		}

		public Reqs(List<SkillReq> skills, List<Quest> prereqQuests, List<Unlock> unlocks)
		{
			this(skills, prereqQuests, unlocks, Collections.emptyList(),
				Collections.emptyList(), Collections.emptyList());
		}

		public Reqs(List<SkillReq> skills, List<Quest> prereqQuests,
			List<Unlock> unlocks, List<BossReq> bossKills)
		{
			this(skills, prereqQuests, unlocks, bossKills,
				Collections.emptyList(), Collections.emptyList());
		}

		public Reqs(List<SkillReq> skills, List<Quest> prereqQuests,
			List<Unlock> unlocks, List<BossReq> bossKills, List<ItemReq> itemReqs)
		{
			this(skills, prereqQuests, unlocks, bossKills, itemReqs, Collections.emptyList());
		}

		public Reqs(List<SkillReq> skills, List<Quest> prereqQuests,
			List<Unlock> unlocks, List<BossReq> bossKills,
			List<ItemReq> itemReqs, List<AccountReq> accountReqs)
		{
			this.skills = Collections.unmodifiableList(skills);
			this.prereqQuests = Collections.unmodifiableList(prereqQuests);
			this.unlocks = Collections.unmodifiableList(unlocks);
			this.bossKills = Collections.unmodifiableList(bossKills);
			this.itemReqs = Collections.unmodifiableList(itemReqs);
			this.accountReqs = Collections.unmodifiableList(accountReqs);
		}
	}

	// ============================================================
	// Predefined unlocks - reusable across multiple diary areas
	// ============================================================

	/** Fairy rings: requires Fairytale I + Lost City (NOT full Fairytale II). */
	public static final Unlock FAIRY_RINGS = new Unlock(
		"Fairy Rings Unlocked",
		List.of(Quest.FAIRYTALE_I__GROWING_PAINS, Quest.LOST_CITY),
		772); // ItemID.DRAMEN_STAFF

	/**
	 * An alternative path for an unlock. Multiple alternatives form an
	 * OR-group: ANY one being satisfied unlocks the goal.
	 */
	public static final class Alternative
	{
		public final String label;
		public final List<SkillReq> skills;
		public final List<AccountReq> accounts;
		public final List<BossReq> bosses;

		public Alternative(String label, List<SkillReq> skills, List<AccountReq> accounts)
		{
			this(label, skills, accounts, Collections.emptyList());
		}

		public Alternative(String label, List<SkillReq> skills, List<AccountReq> accounts,
			List<BossReq> bosses)
		{
			this.label = label;
			this.skills = Collections.unmodifiableList(skills);
			this.accounts = Collections.unmodifiableList(accounts);
			this.bosses = Collections.unmodifiableList(bosses);
		}
	}

	/** Warriors Guild entry: 130 combined Att+Str OR 99 Attack OR 99 Strength. */
	public static final Unlock WARRIORS_GUILD = new Unlock(
		"Warriors Guild Entry",
		List.of(), // no shared quest prereqs
		List.of(), // no shared skill prereqs
		List.of(), // no shared account prereqs
		List.of(
			new Alternative("Att+Str Combined >= 130",
				List.of(), List.of(new AccountReq("ATT_STR_COMBINED", 130))),
			new Alternative("99 Attack",
				List.of(new SkillReq(Skill.ATTACK, 99)), List.of()),
			new Alternative("99 Strength",
				List.of(new SkillReq(Skill.STRENGTH, 99)), List.of())
		),
		ItemID.STEEL_DEFENDER);

	/** Mith grapple: requires 59 Fletching + 59 Smithing to craft. */
	public static final Unlock MITH_GRAPPLE = new Unlock(
		"Mith Grapple",
		List.of(), // no quest prereqs
		List.of(new SkillReq(Skill.FLETCHING, 59), new SkillReq(Skill.SMITHING, 59)),
		ItemID.MITH_GRAPPLE);


	private static final Map<String, Reqs> TABLE = new HashMap<>();

	private static String key(String area, AchievementDiaryData.Tier tier)
	{
		return area + "|" + tier.name();
	}

	/**
	 * Look up the requirements for a diary tier.
	 *
	 * @return the requirements, or {@code null} if not in the table
	 */
	public static Reqs lookup(String area, AchievementDiaryData.Tier tier)
	{
		if (area == null || tier == null) return null;
		return TABLE.get(key(area, tier));
	}

	/** True iff the diary tier has non-empty requirements. */
	public static boolean hasRequirements(String area, AchievementDiaryData.Tier tier)
	{
		Reqs r = lookup(area, tier);
		if (r == null) return false;
		return !r.skills.isEmpty() || !r.prereqQuests.isEmpty() || !r.unlocks.isEmpty();
	}

	// ============================================================
	// Wiki-sourced diary requirements (2026-04-11), loaded from
	// diary-requirements.json at plugin start-up via the injected
	// client Gson - see GoalPlannerPlugin.startUp().
	// ============================================================

	private static volatile boolean loaded;

	/**
	 * Loads the diary requirement table from its bundled JSON resource.
	 * Idempotent and thread-safe; the first caller wins and subsequent
	 * calls are no-ops. Pass the client's injected {@link com.google.gson.Gson}
	 * (the plugin must never create its own - the hub forbids it).
	 */
	public static void init(com.google.gson.Gson gson)
	{
		if (loaded)
		{
			return;
		}
		synchronized (DiaryRequirements.class)
		{
			if (loaded)
			{
				return;
			}
			try (InputStream in = DiaryRequirements.class.getResourceAsStream("diary-requirements.json"))
			{
				if (in == null)
				{
					throw new IllegalStateException("missing resource: diary-requirements.json");
				}
				try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
				{
					TABLE.putAll(gson.fromJson(reader,
						new com.google.gson.reflect.TypeToken<Map<String, Reqs>>(){}.getType()));
				}
			}
			catch (IOException e)
			{
				throw new UncheckedIOException("failed to load diary-requirements.json", e);
			}
			loaded = true;
		}
	}

	private DiaryRequirements() {}
}
