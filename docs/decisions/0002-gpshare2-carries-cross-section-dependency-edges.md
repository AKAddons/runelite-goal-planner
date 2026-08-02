---
status: accepted
date: 2026-06-11
decision-makers: ajkatz
---

# GPSHARE2 carries cross-section dependency edges in an additive bundle-level field

> Migrated verbatim in substance from the pre-MADR `DECISIONS.md` entry of the
> same date, restructured into MADR sections. See [ADR-0001](0001-adopt-madr-for-architecture-decisions.md).

## Context and Problem Statement

A user bug report ("goals selected across two sections imported as one combined
section") led to per-section selection export, which made the cross-section
edge gap acute: the fix preserved sections but initially dropped the
dependency edges between them.

## Decision Drivers

* The format-freeze moment is the public release, not the code change — this
  was the last cheap opportunity to change the wire.
* Zero GPSHARE2 codes existed outside local testing at decision time.
* Sharing a multi-select that spans sections preserves the source sections (a
  user requirement), which makes cross-section edges common in real exports.
* The MCP server (`goalplanner-share-mcp`) must emit and decode whatever shape
  is chosen, so the cost is paid twice.

## Considered Options

* Bundle-level `crossEdges` list, added to v2 in place
* Section-scoped refs only, dropping cross-section edges with an export-time warning
* Make all refs bundle-global instead of section-scoped
* Defer to a future GPSHARE3

## Decision Outcome

Chosen option: "Bundle-level `crossEdges` list, added to v2 in place", because
it was the only option that preserved plan structure without either restructuring
every existing field's semantics or paying for a version migration later.

The GPSHARE2 (multi-section) share-code wire format carries dependency edges
between goals in different sections via a bundle-level `crossEdges` list —
`{fromSection, fromRef, toSection, toRef, or}` entries, where section values
index the bundle's `sections` list and refs are the per-section goal refs.
Per-goal `requires`/`orRequires` ref lists remain section-scoped. No v3.

### Consequences

* Good, because the additive shape keeps v1 untouched and lets older v2
  decoders (none exist) degrade by ignoring the field.
* Good, because section entries stay self-contained and understandable
  standalone.
* Bad, because import needs a third pass to resolve the edges through
  per-section ref maps; they are tracked in the one-undo journal like all
  imported relations.
* Bad, because the wire is frozen once 0.3.0 ships — any further change costs
  a real version migration.

### Confirmation

Implemented in commit 3a0790e for the 0.3.0 release. Round-trip encode/decode
tests in `ShareCodecTest`; the MCP side must stay in lockstep.

## Pros and Cons of the Options

### Bundle-level `crossEdges`, added to v2 in place

* Good, because additive — v1 untouched, unknown-field decoders degrade gracefully.
* Good, because it touches the least encode/decode/test surface.
* Bad, because it adds a third import pass.

### Section-scoped refs only, drop edges with an export-time warning

* Bad, because silently — or even loudly — losing plan structure is data loss
  the recipient cannot recover.

### All refs bundle-global

* Bad, because it restructures every existing field's semantics.
* Bad, because it breaks the property that a section entry is understandable
  standalone.
* Bad, because it touches far more encode/decode/test surface than an additive field.

### Defer to a future GPSHARE3

* Bad, because once codes circulate (planned request-share-codes Discord
  channel), the format is frozen by archived messages; a v3 migration later
  costs far more than an additive field now.

## More Information

Binds `goalplanner-share-mcp`, which must emit and decode the same field.
