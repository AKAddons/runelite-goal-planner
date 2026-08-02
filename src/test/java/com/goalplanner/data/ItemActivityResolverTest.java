package com.goalplanner.data;

import com.goalplanner.testsupport.DataResourcesInitExtension;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Joins two shipped reference tables, so these tests are as much about the
 * DATA agreeing with itself as about the code - a rename in either TSV should
 * fail here rather than silently produce an empty menu.
 */
@ExtendWith(DataResourcesInitExtension.class)
class ItemActivityResolverTest
{
	@Nested
	@DisplayName("resolving an item to its activity")
	class Resolving
	{
		@Test
		@DisplayName("a God Wars unique resolves to its boss and kill-count varp")
		void bandosChestplate()
		{
			List<ItemActivityResolver.Activity> found = ItemActivityResolver.resolve(11832);
			assertEquals(1, found.size(), "expected exactly one activity for Bandos chestplate");
			assertEquals("General Graardor", found.get(0).getName());
			assertEquals(1504, found.get(0).getVarpId());
		}

		@Test
		@DisplayName("a slayer-boss unique resolves too")
		void abyssalWhip()
		{
			List<ItemActivityResolver.Activity> found = ItemActivityResolver.resolve(4151);
			assertEquals("Abyssal Sire", found.get(0).getName());
		}

		@Test
		@DisplayName("every resolved activity carries a real varp, never the -1 sentinel")
		void varpsAreReal()
		{
			for (int itemId : new int[]{11832, 4151, 11785})
			{
				for (ItemActivityResolver.Activity a : ItemActivityResolver.resolve(itemId))
				{
					assertTrue(a.getVarpId() > 0,
						a.getName() + " resolved with no kill-count varp");
				}
			}
		}
	}

	@Nested
	@DisplayName("items with nothing to grind")
	class NoActivity
	{
		@Test
		@DisplayName("an item with no source at all resolves to nothing")
		void unknownItem()
		{
			assertTrue(ItemActivityResolver.resolve(Integer.MAX_VALUE).isEmpty());
			assertFalse(ItemActivityResolver.hasActivity(Integer.MAX_VALUE));
		}

		@Test
		@DisplayName("resolving item id 0 is safe")
		void zeroId()
		{
			assertTrue(ItemActivityResolver.resolve(0).isEmpty());
		}
	}

	@Nested
	@DisplayName("label reconciliation")
	class LabelMatching
	{
		@Test
		@DisplayName("an exactly-spelled boss name matches")
		void exactName()
		{
			assertEquals(List.of("General Graardor"),
				ItemActivityResolver.matchActivities("General Graardor"));
		}

		@Test
		@DisplayName("a leading article is not a mismatch")
		void leadingArticle()
		{
			// item-sources says "The Hueycoatl"; boss-killcount says "Hueycoatl".
			assertEquals(List.of("Hueycoatl"),
				ItemActivityResolver.matchActivities("The Hueycoatl"));
		}

		@Test
		@DisplayName("case differences are not a mismatch")
		void caseDiffers()
		{
			// item-sources says "Crazy archaeologist"; boss-killcount capitalises it.
			assertEquals(List.of("Crazy Archaeologist"),
				ItemActivityResolver.matchActivities("Crazy archaeologist"));
		}

		@Test
		@DisplayName("a group label splits into its member bosses")
		void oneToMany()
		{
			List<String> moons = ItemActivityResolver.matchActivities("Moons of Peril");
			assertTrue(moons.size() > 1, "Moons of Peril should split into its individual moons");
			assertTrue(moons.contains("Blue Moon"), "got: " + moons);

			List<String> dks = ItemActivityResolver.matchActivities("Dagannoth Kings");
			assertEquals(3, dks.size(), "got: " + dks);
		}

		@Test
		@DisplayName("a wilderness pair splits on 'and' without hardcoding the pair")
		void wildernessPair()
		{
			assertEquals(List.of("Callisto", "Artio"),
				ItemActivityResolver.matchActivities("Callisto and Artio"));
			assertEquals(List.of("Venenatis", "Spindel"),
				ItemActivityResolver.matchActivities("Venenatis and Spindel"));
		}

		@Test
		@DisplayName("minigame labels resolve to nothing until the minigame counters are wired")
		void minigamesNotYetSupported()
		{
			// Documents a known limitation rather than asserting it is correct:
			// Pest Control has a completion varbit, just not one this plugin reads.
			assertTrue(ItemActivityResolver.matchActivities("Pest Control").isEmpty());
		}

		@Test
		@DisplayName("an unknown label matches nothing rather than guessing")
		void unknownLabel()
		{
			assertTrue(ItemActivityResolver.matchActivities("Not A Real Boss").isEmpty());
			assertTrue(ItemActivityResolver.matchActivities(null).isEmpty());
			assertTrue(ItemActivityResolver.matchActivities("").isEmpty());
		}
	}

	@Nested
	@DisplayName("coverage across the shipped data")
	class Coverage
	{
		/**
		 * Not a threshold for its own sake: this pins the measured join rate so
		 * that a rename in either TSV shows up as a failure here instead of as
		 * a menu entry that quietly stopped appearing.
		 */
		@Test
		@DisplayName("most grindable source labels resolve to a real boss")
		void joinRateHolds() throws Exception
		{
			int total = 0;
			int matched = 0;
			List<String> misses = new java.util.ArrayList<>();
			for (String label : grindableLabels())
			{
				total++;
				if (!ItemActivityResolver.matchActivities(label).isEmpty())
				{
					matched++;
				}
				else
				{
					misses.add(label);
				}
			}
			assertTrue(total > 40, "expected a meaningful number of grindable labels, got " + total);
			assertTrue(matched * 100 / total >= 90,
				"join rate dropped to " + (matched * 100 / total) + "% - unmatched: " + misses);
		}

		/**
		 * The two labels that genuinely have no boss row, as opposed to the
		 * many that merely spell things differently. Pinned by name so that
		 * fixing either one fails here and prompts removing it from the list -
		 * a gap list that silently grows is worthless.
		 */
		@Test
		@DisplayName("exactly two boss/raid labels are real data gaps, both known")
		void knownDataGaps() throws Exception
		{
			List<String> misses = new java.util.ArrayList<>();
			for (String label : grindableLabels())
			{
				if (ItemActivityResolver.matchActivities(label).isEmpty())
				{
					misses.add(label);
				}
			}
			// Deranged Archaeologist has varp 1661 but no boss-killcount row.
			// Thermonuclear smoke devil has no total_*_kills varp at all.
			assertEquals(List.of("Deranged Archaeologist", "Thermonuclear smoke devil"),
				misses.stream().sorted().collect(java.util.stream.Collectors.toList()));
		}

		@Test
		@DisplayName("every minigame label is still an unresolved gap, and there are many")
		void minigameGapIsWholesale() throws Exception
		{
			int unresolved = 0;
			for (String label : labelsForCategory("MINIGAME"))
			{
				if (ItemActivityResolver.matchActivities(label).isEmpty())
				{
					unresolved++;
				}
			}
			// Not an endorsement - a marker. When the collection_minigames_*
			// varbits land, this count drops and this test should be replaced
			// by real coverage.
			assertTrue(unresolved >= 20,
				"minigames started resolving (" + unresolved + " left) - wire them into the resolver");
		}

		/**
		 * Distinct BOSS/RAID/MINIGAME source labels, read straight from the
		 * shipped TSV rather than through ItemSourceData, so the test sees the
		 * same raw vocabulary the resolver has to reconcile.
		 */
		private java.util.Set<String> grindableLabels() throws Exception
		{
			java.util.Set<String> labels = new java.util.LinkedHashSet<>();
			labels.addAll(labelsForCategory("BOSS"));
			labels.addAll(labelsForCategory("RAID"));
			return labels;
		}

		/** Distinct source labels in one category, read straight from the TSV. */
		private java.util.Set<String> labelsForCategory(String wanted) throws Exception
		{
			java.util.Set<String> labels = new java.util.LinkedHashSet<>();
			try (java.io.InputStream in = ItemSourceData.class
				.getResourceAsStream("item-sources.tsv");
				java.io.BufferedReader r = new java.io.BufferedReader(
					new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)))
			{
				String line;
				while ((line = r.readLine()) != null)
				{
					String[] parts = line.split("\t");
					if (parts.length >= 3 && parts[2].trim().equals(wanted))
					{
						labels.add(parts[1].trim());
					}
				}
			}
			return labels;
		}
	}
}
