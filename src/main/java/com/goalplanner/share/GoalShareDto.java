package com.goalplanner.share;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A single goal's shareable definition inside a {@link ShareBundle}.
 *
 * <p>Mirrors the definition-bearing fields of {@code com.goalplanner.model.Goal}
 * and deliberately omits recipient-specific state: {@code id}, {@code status},
 * {@code currentValue}, {@code completedAt}, {@code createdAt}, {@code priority}
 * and {@code sectionId} are all regenerated on import.
 *
 * <p>Relations are encoded structurally: each goal carries a bundle-local
 * {@link #ref} index, and {@link #requires}/{@link #orRequires} reference those
 * indices rather than store ids - so they re-wire to fresh ids on import and
 * any edge pointing outside the bundle is simply dropped.
 */
@Data
public class GoalShareDto
{
	/** Bundle-local index used to wire {@link #requires}/{@link #orRequires}. */
	private int ref;

	private String type;          // GoalType name
	private String name;
	private String description;
	private int targetValue;

	// Type-specific references (nullable / sentinel) - mirror Goal.
	private String skillName;
	private String questName;
	private String accountMetric;
	private String bossName;
	private int varbitId;
	private int itemId;
	private int spriteId;
	private String tooltip;
	private int caTaskId = -1;

	/** How often the goal repeats ({@code RepeatPeriod} name), or null/"NONE"
	 *  for an ordinary one-shot. Additive to GPSHARE2: an older decoder simply
	 *  ignores it and imports the goal as non-repeating. */
	private String repeatEvery;

	/** For a derived repeatable goal, the gain expected each period. Zero for a
	 *  plain repeat. The recipient re-bases their own target off their OWN
	 *  progress on import - the sender's absolute target is meaningless to
	 *  them, which is exactly why the chunk travels and the target does not. */
	private int repeatChunk;
	private int customColorRgb = -1;
	private boolean optional;
	private boolean autoSeeded;
	private String wikiUrl;
	private String inventorySetup;

	/** Tags resolved to {label, category, colour} - store ids are meaningless
	 *  cross-client, so they re-resolve (findOrCreate) on import. */
	private List<TagShareDto> tags = new ArrayList<>();

	/** AND-prerequisite edges, as bundle-local {@link #ref} indices. */
	private List<Integer> requires = new ArrayList<>();

	/** OR-prerequisite edges, as bundle-local {@link #ref} indices. */
	private List<Integer> orRequires = new ArrayList<>();
}
