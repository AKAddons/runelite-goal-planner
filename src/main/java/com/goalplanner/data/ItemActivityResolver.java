package com.goalplanner.data;

import com.goalplanner.model.ItemTag;
import com.goalplanner.model.TagCategory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.runelite.client.game.ItemVariationMapping;

/**
 * Answers "what repeatable activity drops this item?" by joining the two
 * reference tables the plugin already ships: {@link ItemSourceData}
 * ({@code itemId -> source label -> category}) and {@link BossKillData}
 * ({@code boss name -> kill-count varp}).
 *
 * <p>This is what lets an item goal spawn a repeatable activity goal - "you
 * want a Bandos chestplate, so kill General Graardor 20 times today".
 *
 * <p>Two joins are deliberately lossy and it matters which:
 * <ul>
 *   <li>Only grindable categories resolve. Roughly half of all item rows are
 *       {@link TagCategory#OTHER} - shop items, skilling drops, one-offs - and
 *       a bronze bar has no boss to farm. Callers must treat an empty result
 *       as "no menu entry", not as an error.</li>
 *   <li>Source labels and boss names are two vocabularies that mostly, but not
 *       always, agree. {@link #matchActivities} reconciles the mechanical
 *       differences (case, a leading "The", the collection-log alias table's
 *       one-to-many splits like Perilous Moons); anything left over is a real
 *       data gap rather than a naming one.</li>
 * </ul>
 */
public final class ItemActivityResolver
{
	private ItemActivityResolver() {}

	/** A trackable activity: a boss name plus the varp holding its kill count. */
	public static final class Activity
	{
		private final String name;
		private final int varpId;

		Activity(String name, int varpId)
		{
			this.name = name;
			this.varpId = varpId;
		}

		public String getName() { return name; }

		public int getVarpId() { return varpId; }

		@Override
		public String toString() { return name + " (varp " + varpId + ")"; }
	}

	/**
	 * Categories worth offering a repeatable goal for.
	 *
	 * <p>SKILLING and QUEST are excluded by design: a skilling drop is better
	 * served by an XP chunk off the skill goal, and a quest happens once.
	 *
	 * <p>MINIGAME is excluded by CIRCUMSTANCE, not design, and should be added
	 * back. Measured: all 22 minigame source labels (Pest Control, Mahogany
	 * Homes, Tithe Farm, Barbarian Assault, ...) resolve to nothing, because
	 * minigames have no row in boss-killcount.tsv - their completion counters
	 * are the 19 {@code collection_minigames_*_completed} varbits, which the
	 * plugin does not read yet. Including the category before those are wired
	 * would offer menu entries that produce untrackable goals.
	 */
	private static boolean isGrindable(TagCategory category)
	{
		return category == TagCategory.BOSS
			|| category == TagCategory.RAID;
	}

	/**
	 * Source labels naming a GROUP of bosses that share one collection-log
	 * entry. The generic " and " split below handles the wilderness pairs;
	 * these are the ones whose group name shares no words with its members.
	 * Every target verified present in boss-killcount.tsv by test.
	 */
	private static final java.util.Map<String, String[]> GROUP_LABELS =
		java.util.Map.of(
			"Dagannoth Kings",
			new String[]{"Dagannoth Prime", "Dagannoth Rex", "Dagannoth Supreme"},
			"Moons of Peril",
			new String[]{"Blue Moon", "Blood Moon", "Eclipse Moon"});

	/**
	 * Every trackable activity that drops {@code itemId}, in source order and
	 * de-duplicated. Empty when the item has no grindable source or its source
	 * has no kill-count varp - both are ordinary outcomes, not failures.
	 */
	public static List<Activity> resolve(int itemId)
	{
		// Variant mapping FIRST. Chargeable and degradable items are stored
		// under one variant in item-sources (Scythe of vitur is 22324, not the
		// charged 22325), so skipping this silently returns nothing for exactly
		// the high-value items a player is most likely to be grinding for.
		int base = ItemVariationMapping.map(itemId);
		List<ItemTag> tags = ItemSourceData.getTags(base);
		if (tags.isEmpty() && base != itemId)
		{
			tags = ItemSourceData.getTags(itemId);
		}

		List<Activity> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (ItemTag tag : tags)
		{
			if (!isGrindable(tag.getCategory()))
			{
				continue;
			}
			for (String bossName : matchActivities(tag.getLabel()))
			{
				if (seen.add(bossName))
				{
					out.add(new Activity(bossName, BossKillData.getVarpId(bossName)));
				}
			}
		}
		return out;
	}

	/** True when {@code itemId} has at least one trackable activity. */
	public static boolean hasActivity(int itemId)
	{
		return !resolve(itemId).isEmpty();
	}

	/**
	 * Reconcile one source label against the boss-name vocabulary, returning
	 * every boss it corresponds to. One label can legitimately mean several
	 * bosses - "Dagannoth Kings" is three, "Perilous Moons" is four - so the
	 * return is a list, and a caller offering a menu shows one entry per boss.
	 */
	static List<String> matchActivities(String label)
	{
		List<String> out = new ArrayList<>();
		if (label == null || label.isEmpty())
		{
			return out;
		}

		// 1. Exact name. The common case: ~three quarters of source labels are
		//    already spelled the way boss-killcount spells them.
		if (BossKillData.isKnownBoss(label))
		{
			out.add(label);
			return out;
		}

		// 2. The collection-log alias table, which already encodes the
		//    one-to-many splits (raid tiers, Perilous Moons, Nightmare variants).
		List<String> aliased = BossKillData.resolveCollectionLogName(label);
		if (aliased != null)
		{
			for (String name : aliased)
			{
				if (BossKillData.isKnownBoss(name))
				{
					out.add(name);
				}
			}
			if (!out.isEmpty())
			{
				return out;
			}
		}

		// 3. Named groups whose label shares no words with its members.
		String[] group = GROUP_LABELS.get(label);
		if (group != null)
		{
			for (String name : group)
			{
				if (BossKillData.isKnownBoss(name))
				{
					out.add(name);
				}
			}
			if (!out.isEmpty())
			{
				return out;
			}
		}

		// 4. Mechanical spelling differences: case, and a leading article that
		//    one table carries and the other does not ("The Hueycoatl").
		for (String candidate : BossKillData.getBossNames())
		{
			if (normalize(candidate).equals(normalize(label)))
			{
				out.add(candidate);
				return out;
			}
		}

		// 5. Wilderness bosses share one collection-log entry with their
		//    singles-plus counterpart ("Callisto and Artio"). Split and match
		//    each half rather than enumerating every pair by hand.
		if (label.contains(" and "))
		{
			for (String half : label.split(" and "))
			{
				String trimmed = half.trim();
				if (BossKillData.isKnownBoss(trimmed))
				{
					out.add(trimmed);
				}
			}
		}
		return out;
	}

	/** Lowercase, strip a leading "the ", collapse to alphanumerics. */
	private static String normalize(String s)
	{
		String t = s.toLowerCase(java.util.Locale.ROOT).trim();
		if (t.startsWith("the "))
		{
			t = t.substring(4);
		}
		return t.replaceAll("[^a-z0-9]", "");
	}
}
