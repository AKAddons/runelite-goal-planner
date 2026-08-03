package com.goalplanner.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Public DTO for a goal - the canonical "render-friendly snapshot" returned by
 * {@link GoalPlannerApi#queryAllGoals()}.
 *
 * <p>Core fields are type-agnostic. Type-specific extras live in {@link #attributes}
 * with documented per-type schemas:
 *
 * <ul>
 *   <li><b>SKILL</b>: {@code skillName} (String, net.runelite.api.Skill enum name)</li>
 *   <li><b>QUEST</b>: {@code questName} (String, net.runelite.api.Quest enum name),
 *       {@code tooltip} (String, optional)</li>
 *   <li><b>DIARY</b>: {@code area} (String), {@code tier} (String - "EASY"/"MEDIUM"/"HARD"/"ELITE"),
 *       {@code varbitId} (Integer), {@code tooltip} (String, optional)</li>
 *   <li><b>ITEM_GRIND</b>: {@code itemId} (Integer)</li>
 *   <li><b>COMBAT_ACHIEVEMENT</b>: {@code caTaskId} (Integer), {@code tier} (String - "EASY".."GRANDMASTER"),
 *       {@code monster} (String), {@code tooltip} (String, optional)</li>
 *   <li><b>CUSTOM</b>: no extras</li>
 * </ul>
 *
 * <p>Optional keys are <em>absent</em> from the map, not null. Consumers can use
 * {@link Map#containsKey(Object)} or {@link Map#getOrDefault(Object, Object)}.
 */
public class GoalView
{
	public String id;
	/** Goal type as a string: SKILL, QUEST, DIARY, ITEM_GRIND, COMBAT_ACHIEVEMENT, CUSTOM. */
	public String type;
	public String name;
	public String description;
	public int currentValue;
	public int targetValue;

	/** Non-zero for a repeatable goal: the gain expected each period. Renderers
	 *  must not do absolute-value math (level lookups, thresholds) on
	 *  currentValue/targetValue when this is set - those are period-relative. */
	public int repeatChunk;
	/** 0 if not complete; nonzero millis-since-epoch when the goal was marked complete. */
	public long completedAt;
	public String sectionId;

	// ----- display hints -----

	/** RuneLite sprite id for the icon, or 0 if no sprite. */
	public int spriteId;
	/** Packed RGB color (0xRRGGBB) for the card background tint - override or default. */
	public int backgroundColorRgb;
	/** The type-default background color, even when an override is set. Lets
	 *  the UI preview what "reset to default" would revert to. */
	public int defaultBackgroundColorRgb;
	/** True when {@link #backgroundColorRgb} is a user override (not the type default). */
	public boolean backgroundColorOverridden;
	/** Ephemeral UI selection state - true when this card is currently selected
	 *  in the panel. Lost on plugin restart. */
	public boolean selected;
	/** True when the user has marked this goal as optional. */
	public boolean optional;
	/** True when this goal's nested prerequisite subtree is collapsed in the
	 *  nested view (persisted per profile). */
	public boolean nestCollapsed;
	/** Labels of DIRECT prerequisites the player hasn't met and that aren't in
	 *  the plan - drives the "blocked" badge. Empty = no badge. */
	public List<String> blockedRequirements = java.util.Collections.emptyList();
	/** Auto-generated tags from goal creation (boss/raid/tier/etc).
	 *  Cannot be removed by the user; restored by Restore Defaults. */
	public List<TagView> defaultTags;
	/** User-added tags layered on top of {@link #defaultTags}.
	 *  These are the only tags removable via the Remove Tag UI. */
	public List<TagView> customTags;

	// ----- relations -----

	/** Goals this one requires (outgoing edges), resolved at queryAllGoals
	 *  time. Empty list if none. Used by the card hover tooltip to show
	 *  the "Requires:" line. Implicit skill-chain edges (same skill,
	 *  different level) are excluded - those are internal bookkeeping. */
	public List<RelationView> requiresNames;
	/** OR-prerequisite edges (any one completing satisfies the link).
	 *  Empty if no OR-prereqs. Used by the card tooltip. */
	public List<RelationView> orRequiresNames;
	/** Goals that require this one via AND-edges (incoming), resolved at
	 *  queryAllGoals time. Used by the card hover tooltip. */
	public List<RelationView> requiredByNames;
	/** Goals that or-require this one (incoming OR-edges). Shows as
	 *  "Or-required by:" in the tooltip. */
	public List<RelationView> orRequiredByNames;

	/**
	 * Lightweight DTO for a single relation edge, carrying enough metadata
	 * to render skill icons in tooltips (quest→skill links) or fall back
	 * to the goal name for non-skill relations.
	 */
	public static class RelationView
	{
		/** Display name of the related goal (fallback text). */
		public String name;
		/** Non-null for SKILL goals - the Skill enum name. Enables compact
		 *  icon+level rendering in tooltips. */
		public String skillName;
		/** Target level for SKILL goals (used for compact display). */
		public int targetLevel;
		/** True when this relation target is an optional/recommended goal. */
		public boolean optional;

		public RelationView(String name, String skillName, int targetLevel, boolean optional)
		{
			this.name = name;
			this.skillName = skillName;
			this.targetLevel = targetLevel;
			this.optional = optional;
		}
	}
	// ----- type-specific extras -----

	public Map<String, Object> attributes = new HashMap<>();
}
