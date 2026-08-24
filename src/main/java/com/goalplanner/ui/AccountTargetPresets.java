package com.goalplanner.ui;

import com.goalplanner.model.AccountMetric;
import java.util.LinkedHashSet;

/**
 * Pure quick-fill target presets for account-metric goals (Task 3). Data-driven
 * off each metric's static {@link AccountMetric#getMaxTarget()} - no per-metric
 * hardcoded tables and no live Client read. The dock's account create form offers
 * these as one-tap buttons alongside the free-text target field, so common
 * milestones (a quarter/half/three-quarters of the ceiling, and the ceiling
 * itself) don't have to be typed. Typing a custom value still works.
 */
public final class AccountTargetPresets
{
	private AccountTargetPresets() {}

	/** The fractions of max offered below the Max button. */
	private static final double[] FRACTIONS = { 0.25, 0.50, 0.75 };

	/**
	 * Ordered, de-duplicated quick-fill targets for {@code metric}: roughly
	 * 25% / 50% / 75% of the ceiling (nice-rounded and clamped to the metric's
	 * [min, max] range), followed by the max itself. The max is always the last
	 * element, so the array is never empty. Fractions that round to a duplicate
	 * (common on small-ceiling metrics) are dropped.
	 */
	public static int[] presetsFor(AccountMetric metric)
	{
		if (metric == null)
		{
			return new int[0];
		}
		int min = metric.getMinTarget();
		int max = metric.getMaxTarget();
		// Preserve insertion order, drop duplicates.
		LinkedHashSet<Integer> out = new LinkedHashSet<>();

		int[] milestones = milestonesFor(metric);
		if (milestones != null)
		{
			// Curated significant milestones read better than fractions-of-max for
			// metrics like Total Level (594/1188/1782 was meaningless). Clamp into
			// range, keep those below max, then append max as the last element.
			for (int m : milestones)
			{
				if (m > min && m < max)
				{
					out.add(m);
				}
			}
		}
		else
		{
			for (double f : FRACTIONS)
			{
				int v = niceRound((int) Math.round(max * f));
				v = Math.max(min, Math.min(max, v));
				if (v > 0 && v < max)
				{
					out.add(v);
				}
			}
		}
		// Collection Log: the Gilded staff unlocks at 90% of the total, rounded
		// down to the nearest 25 - a headline tier between Dragon (1200) and full
		// completion. Computed off the live max so it tracks as the log grows.
		if (metric == AccountMetric.COLLECTION_LOG_SLOTS)
		{
			int gilded = (int) (Math.floor(0.9 * max / 25.0) * 25);
			if (gilded > min && gilded < max)
			{
				out.add(gilded);
			}
		}
		out.add(max);
		int[] arr = new int[out.size()];
		int i = 0;
		for (int v : out)
		{
			arr[i++] = v;
		}
		return arr;
	}

	/**
	 * Curated significant milestones (sub-max; the caller appends the metric's
	 * max) for metrics where a quarter/half/three-quarter of the ceiling reads as
	 * an arbitrary number. {@code null} = use the fraction fallback.
	 */
	private static int[] milestonesFor(AccountMetric metric)
	{
		switch (metric)
		{
			// 2277 = all 99s before Sailing (the classic max cape); max (2376) appended.
			case TOTAL_LEVEL:  return new int[]{ 1500, 2000, 2277 };
			case QUEST_POINTS: return new int[]{ 100, 200, 300 };
			// Collection-log reward-staff tiers: Bronze..Dragon are fixed slot
			// counts; Gilded (90% of the dynamic max) is added in presetsFor; the
			// full-completion max is appended by the caller.
			case COLLECTION_LOG_SLOTS: return new int[]{ 100, 300, 500, 700, 900, 1000, 1100, 1200 };
			default:           return null;
		}
	}

	/**
	 * Round {@code v} to a human-friendly step scaled to its magnitude (small
	 * values are left exact so tiny ceilings like Diary Tiers (48) or DoM depth
	 * (8) still produce distinct quarter/half/three-quarter presets).
	 */
	static int niceRound(int v)
	{
		if (v <= 0)
		{
			return 0;
		}
		if (v < 50)
		{
			return v;
		}
		int step;
		if (v < 200)
		{
			step = 5;
		}
		else if (v < 2000)
		{
			step = 10;
		}
		else if (v < 20000)
		{
			step = 100;
		}
		else
		{
			step = 1000;
		}
		return Math.round((float) v / step) * step;
	}
}
