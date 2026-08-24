package com.goalplanner.share;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import java.util.Collections;

/**
 * Transport-neutral payload for sharing a section or a set of goals with
 * another player who runs the plugin. Carries goal <em>definitions</em> only -
 * recipient-specific state (ids, progress, completion, section membership) is
 * stripped on export and regenerated on import, so imported goals track
 * against the recipient's own account.
 *
 * <p>Serialized via {@link ShareCodec}. The {@link #v} schema version lets the
 * decoder reject payloads from an incompatible plugin version up front - the
 * persistence/share format may still change before a stable 1.0.
 */
@Data
public class ShareBundle
{
	/** Current share-format schema version. Bump on any breaking change. */
	public static final int SCHEMA_VERSION = 2;

	/** Legacy single-section schema version (still decoded and, for plain
	 *  single-section bundles, still emitted - older plugin builds import it). */
	public static final int SCHEMA_VERSION_V1 = 1;

	/** What the bundle represents. */
	public enum Kind
	{
		/** A whole section and its goals. */
		SECTION,
		/** A loose set of goals with no section identity. */
		GOALS
	}

	/** Wire schema version. {@link ShareCodec#encode} sets this from the bundle
	 *  shape (see {@code normalizeForWire}); the default matches a legacy-built
	 *  single-section bundle so hand-constructed bundles stay v1-comparable. */
	private int v = SCHEMA_VERSION_V1;
	private Kind kind;

	/** Display name (RSN) of the sharer, shown in the import prompt. */
	private String sharedBy;

	/** Name of the shared section; null for {@link Kind#GOALS}. */
	private String sectionName;

	/** Section colour override (0xRRGGBB; -1 = default). Ignored for GOALS. */
	private int sectionColorRgb = -1;

	/** The shared goal definitions. Relations between them are encoded as
	 *  bundle-local {@code ref} indices on each {@link GoalShareDto}.
	 *  v1 payloads only; {@code null} on the v2 wire (see {@link #sections}). */
	private List<GoalShareDto> goals = new ArrayList<>();

	/**
	 * v2 payloads: one entry per shared section ({@code null} on the v1 wire).
	 * Consumers should read {@link #effectiveSections()} instead of branching
	 * on the schema version.
	 */
	private List<SectionShareDto> sections;

	/**
	 * Version-neutral view: the v2 section list, or the legacy single-section
	 * fields wrapped as one {@link SectionShareDto}. Never null/empty (a legacy
	 * bundle with no goals yields one section with an empty goal list).
	 */
	public List<SectionShareDto> effectiveSections()
	{
		if (sections != null && !sections.isEmpty())
		{
			return sections;
		}
		SectionShareDto legacy = new SectionShareDto();
		legacy.setName(kind == Kind.SECTION ? sectionName : null);
		legacy.setColorRgb(sectionColorRgb);
		legacy.setGoals(goals != null ? goals : new ArrayList<>());
		return Collections.singletonList(legacy);
	}

	/**
	 * v2 payloads: dependency edges between goals in DIFFERENT sections,
	 * which the per-section {@code requires}/{@code orRequires} refs cannot
	 * express. {@code null} on the v1 wire and when no edges cross sections.
	 */
	private List<CrossEdgeDto> crossEdges;

	/** Total goals across all sections, version-neutral (v1 or v2 shape). */
	public int totalGoalCount()
	{
		int n = 0;
		for (SectionShareDto s : effectiveSections())
		{
			n += s.getGoals() != null ? s.getGoals().size() : 0;
		}
		return n;
	}

	/**
	 * True when this bundle needs the v2 wire format: more than one section, or
	 * any section targeting the recipient's default plan. Plain single-section
	 * bundles stay on the v1 wire so older plugin builds keep importing them.
	 */
	public boolean needsV2()
	{
		List<SectionShareDto> secs = effectiveSections();
		if (secs.size() > 1)
		{
			return true;
		}
		return secs.get(0).isTargetDefault();
	}
}
