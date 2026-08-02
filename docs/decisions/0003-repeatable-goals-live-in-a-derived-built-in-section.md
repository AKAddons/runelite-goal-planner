---
status: accepted
date: 2026-07-29
decision-makers: ajkatz
---

# Repeatable goals are a flag on Goal, surfaced through a derived built-in section

## Context and Problem Statement

A user asked for "bite-sized goals": daily slices of a long grind (300
chinchompas, 5 Mahogany Homes contracts, 10 CG runs) that check off during the
day and un-check the next, so a stretch goal becomes a rotation instead of one
unbroken sitting. Nothing in the plugin repeats — completion is terminal
(`completedAt > 0`), and every tracked counter is monotonic.

## Decision Drivers

* `GoalType` is what selects the tracker (`AbstractTracker.targetType()`), so
  anything expressed as a new type forfeits auto-tracking. The user's examples
  are almost all boss/minigame/skill counters, which is precisely what would
  be lost.
* Completed repeatable goals must stay visible in place — the section is a
  checklist that fills over the day, not a queue that drains.
* The feature is opt-in and most users will never touch it, so it must be
  invisible when unused.
* Completion already drives section membership (`reconcileCompletedSection`),
  so there is an established pattern for derived placement.

## Considered Options

* `repeatEvery` field on `Goal`, goals stay inline in their home section
* New `GoalType.DAILY`
* Section-level "this section resets daily" property
* `repeatEvery` field on `Goal` plus a derived built-in `REPEATABLE` section

## Decision Outcome

Chosen option: "`repeatEvery` field plus a derived built-in `REPEATABLE`
section", because it keeps repetition orthogonal to `GoalType` — so a
repeatable goal is still a BOSS goal and still auto-tracks — while giving the
daily list the dedicated, self-explanatory home the inline variant could not.

`Goal` gains `repeatEvery: NONE|DAILY|WEEKLY|MONTHLY`. `Section.BuiltInKind` gains
`REPEATABLE` at `ORDER_REPEATABLE = Integer.MIN_VALUE`, pinned above user
sections. `reconcileCompletedSection` becomes `reconcileDerivedSections` and
checks repeatable **before** completed, so a checked-off daily stays put.
Membership is derived, never manual: moves into and out of the section are
rejected, mirroring how Completed is excluded from Move to Section.

Within the section, goals group by deadline, one group per repeat period.
Groups are **ordered by computed next boundary, not by a fixed period
sequence**: daily is always soonest, but weekly and monthly genuinely swap —
on the 31st of a month falling on a Friday, monthly resets tomorrow while
weekly waits until Wednesday. Group order therefore changes day to day and
must come from `nextBoundary`, never from the declaration order of the enum.

Grouping is render-time only. `priority` still drives within-group order,
keeping drag-reorder intact.

### Consequences

* Good, because repetition composes with every existing goal type, and with
  tags, relations, and the share codec.
* Good, because a "300/day" goal and a "23,000 lifetime" goal need no linking
  machinery — two goals reading the same counter, one delta and one absolute,
  stay in sync for free.
* Good, because auto-archiving needs no carve-out: a repeatable goal is never
  in a user section, so archiving simply never applies to it.
* Bad, because `REPEATABLE` becomes the first built-in that hides when empty,
  breaking the existing rule that built-in headers are always visible so they
  can be right-clicked for Add Section. Acceptable because the affordance that
  creates repeatable goals lives on the *goal* context menu, so hiding the
  header orphans no entry point.
* Bad, because it reuses `archivedFromSectionId` to remember the home section,
  giving one persisted field two mechanisms. This is safe only because the two
  states are mutually exclusive — a repeatable goal never archives to Completed
  — which is an invariant, not an obvious property. Renaming it to
  `homeSectionId` would read better but breaks stored JSON without a
  `@SerializedName` alias.

### Confirmation

Pinned by `RepeatResetServiceTest`: a complete repeatable goal never appears in
the Completed section (`completedRepeatableStaysPut`), the
`archivedFromSectionId` mutual-exclusion invariant (`archivedFromInvariant`, so
a future edit to either mechanism fails loudly rather than corrupting
placement), and the home-section round trip when repeat is switched off
(`repeatOffRestoresHome`). Built-in ordering and the user-section-between-them
property are pinned in `GoalStoreTest`.

**Not yet built:** the deadline group headers. Goals currently render as one
flat list inside the Repeatable section rather than grouped by period, so the
"order groups by computed next boundary" rule above is a decision without an
implementation. `RepeatSchedule.formatCountdown` exists and is tested but is
not wired to any label.

## Pros and Cons of the Options

### `repeatEvery` field, goals stay inline

* Good, because it is the smallest possible change.
* Bad, because completed dailies mixed into a backlog section make neither list
  readable.
* Bad, because it needs an explicit auto-archive carve-out that the derived
  section gets for free.
* Superseded by the chosen option during design.

### New `GoalType.DAILY`

* Good, because it carries zero risk to the existing tracking core and could
  ship in a day.
* Bad, because it forfeits auto-tracking entirely — a DAILY goal could never be
  a CG-kill goal, which kills the feature's best use case.

### Section-level "this section resets daily"

* Good, because it matches the mental model with a single toggle and no
  per-goal config.
* Bad, because behaviour lives in *where a goal sits*, so dragging it out
  silently changes semantics.
* Bad, because sharing semantics get muddy.

## More Information

Reset mechanism: [ADR-0004](0004-clock-driven-reset-via-period-keys.md).

Staged: stage 1 is CUSTOM goals only (manual check-off, which is what the user
literally asked for); stage 2 adds baseline deltas for auto-tracked types;
stage 3 adds a `MINIGAME` type over the 19 `collection_minigames_*_completed`
varbits, which is independently valuable.

`MONTHLY` was added after the initial design. It is what makes the deadline
ordering non-trivial: with only daily and weekly the order was fixed, so a
hardcoded group sequence would have worked and would have quietly become wrong
the moment monthly landed.
