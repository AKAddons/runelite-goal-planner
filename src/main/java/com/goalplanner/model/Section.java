package com.goalplanner.model;

import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * A user-facing section in the goal panel. Sections group goals into visual
 * buckets with their own header row.
 *
 * All sections - built-in and user-defined - use UUID identifiers. The
 * {@link #builtInKind} field identifies built-in sections (Incomplete / Completed)
 * without requiring magic string IDs in code; user sections have builtInKind = null.
 *
 * Ordering is explicit via {@link #order}. Built-ins have fixed relative positions:
 * Incomplete at {@link #ORDER_INCOMPLETE}, Completed at {@link #ORDER_COMPLETED}.
 * User sections can be placed between them.
 */
@Data
@Builder
public class Section
{
	/**
	 * Built-in section roles. User-defined sections use {@code null} for this field.
	 */
	public enum BuiltInKind
	{
		REPEATABLE,
		INCOMPLETE,
		COMPLETED
	}

	/**
	 * Order value for the Repeatable built-in section (always first). It is the
	 * "what am I doing right now" list, so it sits above the backlog rather
	 * than below it.
	 */
	public static final int ORDER_REPEATABLE = Integer.MIN_VALUE;

	/**
	 * Order value for the Incomplete built-in section. Pinned just above
	 * Completed, so user-defined sections (1..N) render first, then
	 * Incomplete, then Completed.
	 */
	public static final int ORDER_INCOMPLETE = Integer.MAX_VALUE - 1;

	/** Order value for the Completed built-in section (always last). */
	public static final int ORDER_COMPLETED = Integer.MAX_VALUE;

	@Builder.Default
	private String id = UUID.randomUUID().toString();

	private String name;

	/** Lower values render first. */
	private int order;

	@Builder.Default
	private boolean collapsed = false;

	/**
	 * User-set color override for the section header, packed as 0xRRGGBB.
	 * -1 means "use the default neutral border color". Built-in sections ignore
	 * this (their header is fixed).
	 */
	@Builder.Default
	private int colorRgb = -1;

	/** Non-null for built-in sections (Incomplete / Completed). Null for user sections. */
	private BuiltInKind builtInKind;

	/**
	 * Per-section override for completed-goal archiving:
	 * {@code null} = inherit the global default; {@code TRUE} = always archive
	 * completed goals out to Completed; {@code FALSE} = always keep them inline.
	 * Built-ins ignore it.
	 */
	private Boolean autoArchiveOverride;

	/**
	 * Per-section override for the "Indent dependencies" nested view:
	 * {@code null} = follow the global default, {@code TRUE} = always nested,
	 * {@code FALSE} = never nested. Purely a view preference - does not change
	 * goal data or ordering. Mirrors {@link #autoArchiveOverride}.
	 */
	private Boolean nestedOverride;

	public boolean isBuiltIn()
	{
		return builtInKind != null;
	}
}
