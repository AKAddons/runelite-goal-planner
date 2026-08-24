package com.goalplanner.data;

import net.runelite.api.gameval.VarbitID;

import java.util.HashMap;
import java.util.Map;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.IntUnaryOperator;

/**
 * Metadata for achievement diaries: widget IDs, sprite icon, and the
 * (area, tier) → completion-tracking lookup used by DiaryTracker.
 *
 * Widget IDs come from {@code InterfaceID.AreaTask} (group 259).
 *
 * <p>Two tracking modes:
 * <ul>
 *   <li><b>Boolean COMPLETE varbit</b> (most diaries): named
 *       {@code <AREA>_DIARY_<TIER>_COMPLETE} in {@link VarbitID}; flips
 *       0→1 when the tier is finished. Goal stores {@code targetValue=1}.</li>
 *   <li><b>Count varbit + task total</b> (Karamja Easy/Medium/Hard only):
 *       runelite-api doesn't expose boolean completes for these three tiers,
 *       only {@code KARAMJA_*_COUNT} task counters. Completion is detected
 *       by comparing the count against the tier's total task count, stored
 *       on the goal as {@code targetValue=<taskCount>}.</li>
 * </ul>
 */
public final class AchievementDiaryData
{
	private AchievementDiaryData() {}

	// InterfaceID.AreaTask constants - group 259
	public static final int GROUP_ID = 259;
	public static final int TASKBOX = 16973826; // clicked row layer (param1 on right-click)

	// Sprite icon: SpriteID.AchievementDiaryIcons.GREEN_ACHIEVEMENT_DIARIES
	public static final int DIARY_SPRITE_ID = 836;

	public enum Tier
	{
		EASY("Easy"),
		MEDIUM("Medium"),
		HARD("Hard"),
		ELITE("Elite");

		private final String displayName;

		Tier(String displayName) { this.displayName = displayName; }

		public String getDisplayName() { return displayName; }
	}

	/**
	 * Tracking spec for one (area, tier): which varbit to read and what
	 * value indicates completion. {@code requiredValue == 1} for boolean
	 * COMPLETE varbits; for Karamja Easy/Medium/Hard it's the tier's total
	 * task count compared against the {@code KARAMJA_*_COUNT} varbit.
	 */
	public static final class Tracking
	{
		public final int varbitId;
		public final int requiredValue;

		public Tracking(int varbitId, int requiredValue)
		{
			this.varbitId = varbitId;
			this.requiredValue = requiredValue;
		}
	}

	/**
	 * Map of "<AREA>|<TIER>" (uppercase, pipe-separated) to the boolean
	 * completion varbit ID. Karamja Easy/Medium/Hard are absent - those
	 * use {@link #KARAMJA_COUNT_VARBITS} instead.
	 */
	private static final Map<String, Integer> COMPLETION_VARBITS = new HashMap<>();

	/**
	 * Karamja Easy/Medium/Hard: count varbit ID and total task count. The
	 * tier is complete when {@code varbit value >= taskCount}.
	 */
	private static final Map<Tier, Tracking> KARAMJA_COUNT_VARBITS = new HashMap<>();
	static
	{
		KARAMJA_COUNT_VARBITS.put(Tier.EASY,   new Tracking(VarbitID.KARAMJA_EASY_COUNT, 10));
		KARAMJA_COUNT_VARBITS.put(Tier.MEDIUM, new Tracking(VarbitID.KARAMJA_MED_COUNT,  19));
		KARAMJA_COUNT_VARBITS.put(Tier.HARD,   new Tracking(VarbitID.KARAMJA_HARD_COUNT, 10));
	}

	static
	{
		try (InputStream in = AchievementDiaryData.class.getResourceAsStream("diary-completion-varbits.tsv"))
		{
			if (in == null)
			{
				throw new IllegalStateException("missing resource: diary-completion-varbits.tsv");
			}
			try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(in, StandardCharsets.UTF_8)))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					if (line.isEmpty()) continue;
					int t = line.indexOf('\t');
					COMPLETION_VARBITS.put(line.substring(0, t), Integer.parseInt(line.substring(t + 1)));
				}
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("failed to load diary-completion-varbits.tsv", e);
		}
	}


	/**
	 * All 12 diary area keys, normalized as used in the varbit map. Every
	 * (area key, tier) pair has a tracking spec, so this enumerates all
	 * {@link #TOTAL_TIER_COUNT} tiers.
	 */
	public static final String[] AREA_KEYS = {
		"ARDOUGNE", "DESERT", "FALADOR", "FREMENNIK", "KANDARIN", "KARAMJA",
		"KOUREND", "LUMBRIDGE", "MORYTANIA", "VARROCK", "WESTERN", "WILDERNESS"
	};

	/** Total diary tiers across all areas (12 areas x 4 tiers = 48). */
	public static final int TOTAL_TIER_COUNT = AREA_KEYS.length * Tier.values().length;

	/**
	 * Count completed diary tiers (0..{@link #TOTAL_TIER_COUNT}) by reading
	 * each tier's tracking varbit through the supplied reader (typically
	 * {@code client::getVarbitValue}). A tier counts as complete when the
	 * varbit has reached the tracking spec's required value - 1 for boolean
	 * COMPLETE varbits, the tier task total for Karamja Easy/Medium/Hard
	 * count varbits.
	 */
	public static int countCompletedTiers(IntUnaryOperator varbitReader)
	{
		int completed = 0;
		for (String area : AREA_KEYS)
		{
			for (Tier tier : Tier.values())
			{
				Tracking t = tracking(area, tier);
				if (t != null && varbitReader.applyAsInt(t.varbitId) >= t.requiredValue)
				{
					completed++;
				}
			}
		}
		return completed;
	}

	/**
	 * Look up the tracking spec (varbit + required value) for a given area
	 * and tier. Returns null when neither a boolean COMPLETE varbit nor a
	 * Karamja count varbit is available (in which case the goal is manual).
	 */
	public static Tracking tracking(String areaDisplayName, Tier tier)
	{
		if (areaDisplayName == null || tier == null) return null;
		String areaKey = normalizeAreaKey(areaDisplayName);
		Integer boolVarbit = COMPLETION_VARBITS.get(areaKey + "|" + tier.name());
		if (boolVarbit != null) return new Tracking(boolVarbit, 1);
		if ("KARAMJA".equals(areaKey)) return KARAMJA_COUNT_VARBITS.get(tier);
		return null;
	}

	/**
	 * Parse the tier out of a diary goal's description (e.g.
	 * "Medium Achievement Diary" → MEDIUM). Returns null if the description
	 * doesn't start with a recognized tier word. Used by the load-time
	 * backfill to recover tier info from existing stored goals.
	 */
	public static Tier parseTierFromDescription(String description)
	{
		if (description == null) return null;
		String trimmed = description.trim();
		int space = trimmed.indexOf(' ');
		String firstWord = (space < 0 ? trimmed : trimmed.substring(0, space)).toUpperCase();
		try { return Tier.valueOf(firstWord); }
		catch (IllegalArgumentException e) { return null; }
	}

	/**
	 * Normalize a display area name into the constant key used in the varbit map.
	 * Handles single-word names ("Desert" → "DESERT") and the few multi-word
	 * aliases used by the diary interface vs. the VarbitID naming.
	 */
	public static String normalizeAreaKey(String displayName)
	{
		if (displayName == null) return "";
		String trimmed = displayName.trim();
		// "Western Provinces" → "WESTERN"
		if (trimmed.equalsIgnoreCase("Western Provinces")) return "WESTERN";
		// "Kourend & Kebos" → "KOUREND"
		if (trimmed.toLowerCase().startsWith("kourend")) return "KOUREND";
		return trimmed.toUpperCase().replace(' ', '_');
	}

	/**
	 * Parse the area display name out of an AreaTask menu entry's option string.
	 * The option looks like "Open &lt;col=ff9040&gt;Desert Journal&lt;/col&gt;" or
	 * "Wiki &lt;col=ff9040&gt;Desert Journal&lt;/col&gt;".
	 *
	 * Returns the trimmed area name (e.g. "Desert") or null if the option doesn't match.
	 */
	public static String parseAreaFromOption(String rawOption)
	{
		if (rawOption == null) return null;
		String stripped = rawOption.replaceAll("<col=[^>]*>", "")
			.replaceAll("</col>", "")
			.trim();
		// Drop leading verb (Open / Wiki / any single word + space)
		int firstSpace = stripped.indexOf(' ');
		if (firstSpace < 0) return null;
		String afterVerb = stripped.substring(firstSpace + 1).trim();
		// Drop trailing " Journal"
		if (afterVerb.toLowerCase().endsWith(" journal"))
		{
			afterVerb = afterVerb.substring(0, afterVerb.length() - " journal".length()).trim();
		}
		return afterVerb.isEmpty() ? null : afterVerb;
	}
}
