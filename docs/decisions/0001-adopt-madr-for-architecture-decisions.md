---
status: accepted
date: 2026-07-29
decision-makers: ajkatz
---

# Record architecture decisions as MADR files under docs/decisions/

## Context and Problem Statement

Significant decisions were appended to a single `DECISIONS.md` per repo. Four
months in, the plugin's file holds exactly one entry — the practice exists but
does not survive contact with a working session. The immediate trigger: while
designing repeatable goals, an earlier decision ("repeatable goals stay inline
in their home section") was superseded by a later one ("they live in their own
derived section") within a single conversation, and the append-only format has
no way to express that. A reader of `DECISIONS.md` cannot tell a live decision
from a dead one.

## Decision Drivers

* Supersession must be explicit. A decision log that cannot retire its own
  entries misleads more than it informs.
* Decisions routinely span two repos (`runelite-goal-planner` and
  `goalplanner-share-mcp`) — the share-codec decision below is one record that
  binds both. Records need stable identifiers to cite across repos.
* This project already enforces conventions mechanically (`checkDocs`,
  `checkTokens`, `checkGlyphs`). A decision record with structured frontmatter
  is checkable; free-form prose is not.
* The one existing entry already follows MADR's shape without knowing it, so
  adoption costs close to nothing.

## Considered Options

* Keep the append-only `DECISIONS.md`
* MADR 4.x, one file per decision under `docs/decisions/`
* Nygard-style lightweight ADRs (Context / Decision / Status / Consequences)

## Decision Outcome

Chosen option: "MADR 4.x, one file per decision", because it is the only option
that makes status and supersession first-class, and its optional-section design
means a small decision stays small. `docs/decisions/NNNN-kebab-title.md`, with
`DECISIONS.md` demoted to an index pointing at the directory.

Numbering is an identifier, not a chronology — `date:` in the frontmatter
carries the real ordering, which lets older decisions be backfilled with a
higher number without lying about when they were made.

### Consequences

* Good, because a superseded decision stays readable but is unambiguously
  marked dead, with a pointer to what replaced it.
* Good, because per-file git history shows when a specific decision changed,
  instead of one file whose blame is a pile of unrelated appends.
* Good, because the `Confirmation` section forces the question "what stops this
  from silently eroding?" at write time — the same instinct behind the existing
  gradle gates.
* Bad, because it is more ceremony per decision, which is exactly what kept the
  old file near-empty. Mitigated by treating every section except Context,
  Considered Options, and Decision Outcome as genuinely optional.
* Bad, because `/decision` currently emits the old shape, so the tooling and
  the convention diverge until it is updated.

### Confirmation

Review only, today. A `scripts/check_adr.py` gate wired into `preSubmit`
alongside `checkDocs`/`checkTokens`/`checkGlyphs` could validate that every
file parses, that `status` is a known value, that numbers are unique, and that
every "superseded by ADR-NNNN" resolves to a file that exists. Not built yet.

## Pros and Cons of the Options

### Keep the append-only DECISIONS.md

* Good, because zero migration and one file to read.
* Bad, because there is no status field, so superseded decisions are
  indistinguishable from live ones.
* Bad, because it has demonstrably not been used — one entry in four months.

### MADR 4.x

* Good, because status and supersession are first-class.
* Good, because structured frontmatter is machine-checkable.
* Neutral, because the full template is long; most sections are droppable.
* Bad, because more per-decision ceremony than a plain append.

### Nygard-style lightweight ADRs

* Good, because it is the smallest format that still has a Status field.
* Bad, because it has no structured frontmatter, so a linter would have to
  parse prose.
* Bad, because it drops "Decision Drivers" and per-option pros and cons, which
  is the part of the existing GPSHARE2 entry that carries the most value.

## More Information

Template: [0000-adr-template.md](0000-adr-template.md). MADR project:
https://adr.github.io/madr/

Revisit if the decision count stays under ~5 by end of 2026, which would mean
the ceremony is still the binding constraint rather than the format.
