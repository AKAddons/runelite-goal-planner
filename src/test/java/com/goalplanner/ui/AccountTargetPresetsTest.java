package com.goalplanner.ui;

import com.goalplanner.model.AccountMetric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure quick-fill preset math for account-metric goals (Task 3). The presets are
 * data-driven off {@link AccountMetric#getMaxTarget()}, so these lock the
 * "max always last, fractions nice-rounded and de-duplicated" contract the dock's
 * account form relies on.
 */
class AccountTargetPresetsTest
{
	@Test
	@DisplayName("every metric offers presets ending in its max, all within [min, max]")
	void everyMetricEndsInMaxAndStaysInRange()
	{
		for (AccountMetric m : AccountMetric.values())
		{
			int[] presets = AccountTargetPresets.presetsFor(m);
			assertTrue(presets.length >= 1, m + " produced no presets");
			assertEquals(m.getMaxTarget(), presets[presets.length - 1],
				m + " last preset should be the max");
			for (int v : presets)
			{
				assertTrue(v >= m.getMinTarget() && v <= m.getMaxTarget(),
					m + " preset " + v + " out of [" + m.getMinTarget() + ", " + m.getMaxTarget() + "]");
			}
			// Strictly ascending (a byproduct of dedup + clamp + max-last).
			for (int i = 1; i < presets.length; i++)
			{
				assertTrue(presets[i] > presets[i - 1],
					m + " presets not strictly ascending: " + java.util.Arrays.toString(presets));
			}
		}
	}

	@Test
	@DisplayName("a small ceiling still yields distinct quarter/half/three-quarter steps")
	void smallCeilingStaysDistinct()
	{
		// DOM_DEEPEST_LEVEL max = 8 -> 2, 4, 6, 8.
		assertArrayEquals(new int[] { 2, 4, 6, 8 },
			AccountTargetPresets.presetsFor(AccountMetric.DOM_DEEPEST_LEVEL));
	}

	@Test
	@DisplayName("nice-round scales the step to the magnitude")
	void niceRoundScales()
	{
		assertEquals(31, AccountTargetPresets.niceRound(31));       // < 50 exact
		assertEquals(170, AccountTargetPresets.niceRound(169));     // step 5
		assertEquals(1190, AccountTargetPresets.niceRound(1188));   // step 10
		assertEquals(16000, AccountTargetPresets.niceRound(16000)); // step 100
		assertEquals(125000, AccountTargetPresets.niceRound(125000)); // step 1000
	}
}
