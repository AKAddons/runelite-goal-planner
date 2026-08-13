package com.goalplanner.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AccountMetric.decays() - the classification that decides whether a goal's
 * completion is sticky or is re-derived from the live value every tick.
 *
 * <p>The expectation table below is deliberately exhaustive: adding a metric
 * without classifying it fails {@link #everyMetricIsClassified()}, so nobody
 * inherits "monotonic" by accident.
 */
class AccountMetricDecayTest
{
	private static Map<AccountMetric, Boolean> expected()
	{
		Map<AccountMetric, Boolean> m = new EnumMap<>(AccountMetric.class);
		// Falls during normal play.
		m.put(AccountMetric.MISC_APPROVAL, true);   // favour decays daily
		m.put(AccountMetric.SLAYER_POINTS, true);   // spendable balance
		// Counters of things done/obtained - the game never takes them back.
		m.put(AccountMetric.QUEST_POINTS, false);
		m.put(AccountMetric.CA_POINTS, false);
		m.put(AccountMetric.KUDOS, false);
		m.put(AccountMetric.CHOMPY_KILLS, false);
		m.put(AccountMetric.COLLECTION_LOG_SLOTS, false);
		m.put(AccountMetric.DIARY_TIERS_COMPLETED, false);
		m.put(AccountMetric.LEAGUE_TASKS, false);
		// Lifetime EARNED, not the spendable balance.
		m.put(AccountMetric.LEAGUE_POINTS, false);
		// Real (unboosted) levels.
		m.put(AccountMetric.TOTAL_LEVEL, false);
		m.put(AccountMetric.COMBAT_LEVEL, false);
		m.put(AccountMetric.ATT_STR_COMBINED, false);
		// Personal bests - a worse run does not lower the record.
		m.put(AccountMetric.TOG_MAX_TEARS, false);
		m.put(AccountMetric.COLOSSEUM_GLORY, false);
		m.put(AccountMetric.DOM_DEEPEST_LEVEL, false);
		return m;
	}

	@Test
	@DisplayName("every metric is explicitly classified as decaying or monotonic")
	void everyMetricIsClassified()
	{
		assertEquals(EnumSet.allOf(AccountMetric.class), expected().keySet(),
			"a new AccountMetric must be added to this table with a justification");
	}

	@Test
	@DisplayName("only Miscellania favour and slayer points can lose value")
	void classificationMatches()
	{
		for (Map.Entry<AccountMetric, Boolean> e : expected().entrySet())
		{
			assertEquals(e.getValue(), e.getKey().decays(), e.getKey().name());
		}
	}

	@Test
	@DisplayName("Miscellania favour decays; quest points do not")
	void spotCheck()
	{
		assertTrue(AccountMetric.MISC_APPROVAL.decays());
		assertFalse(AccountMetric.QUEST_POINTS.decays());
	}
}
