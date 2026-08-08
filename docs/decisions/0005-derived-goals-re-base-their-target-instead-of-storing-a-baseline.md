---
status: accepted
date: 2026-08-01
decision-makers: ajkatz
---

# Bite-sized goals re-base their target each period instead of storing a baseline

## Context and Problem Statement

[ADR-0004](0004-clock-driven-reset-via-period-keys.md) left auto-tracked types
out of repetition, noting they would need "per-period baselines". The intended
design was a `baselineValue` field with progress reported as `live - baseline`.

Then the requirement changed shape: rather than making an existing goal repeat
in place, the user asked to *derive* a bite-sized goal from a long-term one - a
daily XP chunk off a skill goal, a kill-count chunk off an item goal. That
reframing makes a different implementation available, and the baseline design
turns out to be the worse of the two.

## Decision Drivers

* Trackers report a cumulative lifetime counter. Any design that clears a
  derived goal's progress is silently undone on the next tick.
* A baseline has to be captured from the live client. The rollover runs on a
  clock timer that fires while the player is logged out, where there is no
  client to read - so the baseline design forces a two-phase reset and gives up
  the logged-out property that [ADR-0004](0004-clock-driven-reset-via-period-keys.md)
  deliberately bought.
* `changeTargetInternal` already re-evaluates completion on retarget: *"raising
  the target past the recorded progress reopens the goal"*. The reopen a
  rollover needs is already written and already tested.
* Fewer fields beats more fields when both work.

## Considered Options

* Re-base the target: `targetValue = currentValue + repeatChunk` at each rollover
* Store a `baselineValue` and have trackers report `live - baseline`
* Leave auto-tracked types out of repetition entirely

## Decision Outcome

Chosen option: "Re-base the target".

A derived goal carries `repeatChunk` (gain N per period). At rollover the
service moves the goal posts rather than resetting progress - the next N from
wherever the player now stands. Progress is never cleared.

### Consequences

* Good, because **no tracker changes at all**. A derived goal is an ordinary
  SKILL or BOSS goal with an absolute target; every existing tracker handles it
  unchanged.
* Good, because the re-base reads only stored state - `currentValue` is already
  persisted and no progress can happen offline - so rollovers keep working
  while logged out. No two-phase reset, and ADR-0004's property survives.
* Good, because no `baselineValue` field, and the reopen reuses retarget logic
  that already exists.
* Good, because a missed period re-bases to one chunk instead of accumulating:
  skip a day and you owe 50, not 100. Pinned by
  `chunkGoalRebasesEvenWhenIgnored`.
* Bad, because creation must read the live counter to set the first target, and
  there is no safe fallback if that read fails - a BOSS goal targeting 20 when
  the player is at 1,847 lifetime kills completes itself instantly. Creation
  therefore returns null rather than creating a broken goal.
* Bad, because `targetValue` now changes over a goal's life, so it is no longer
  a stable record of what the user asked for. `repeatChunk` holds the intent.
* Neutral: this does not make an *existing* goal repeatable in place. That
  remains unsupported for auto-tracked types, and would still need a baseline.

### Confirmation

`RepeatResetServiceTest.chunkGoalRebasesTarget` pins that a rollover moves the
target and does NOT clear the cumulative value - the mistake this decision
exists to prevent. `chunkGoalRebasesEvenWhenIgnored` pins non-accumulation.
`CreateDerivedRepeatGoalTest` pins that every creation path targets
`live + chunk` and refuses rather than guessing when the live value is
unreadable.

## Pros and Cons of the Options

### Re-base the target

* Good, because zero tracker changes and one fewer field.
* Good, because it preserves logged-out rollover.
* Bad, because `targetValue` becomes mutable state rather than a record of intent.

### Store a baselineValue

* Good, because it keeps `targetValue` meaning "what the user asked for".
* Good, because it would also work for making an existing goal repeat in place.
* Bad, because capturing it needs a live client read at a moment when the player
  is usually logged out, forcing a two-phase reset.
* Bad, because every tracker has to learn to subtract, including `ItemTracker`,
  which overrides the shared loop.

### Leave auto-tracked types out

* Bad, because the user's original examples - CG runs, chinchompas, Mahogany
  Homes - are all auto-tracked. It declines the actual request.

## More Information

Supersedes nothing outright, but narrows
[ADR-0004](0004-clock-driven-reset-via-period-keys.md): where 0004 anticipated
per-period baselines, derived goals need none. 0004's clock model and period
keys are unchanged and still carry this.

Not addressed here: how big a chunk *should* be. No drop-rate or XP-rate data
exists in either repo, so sizes are user-chosen from presets.
