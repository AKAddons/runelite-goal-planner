---
status: accepted
date: 2026-07-29
decision-makers: ajkatz
---

# Reset repeatable goals by comparing period keys on a clock timer, not by timestamp arithmetic on game ticks

## Context and Problem Statement

Repeatable goals ([ADR-0003](0003-repeatable-goals-live-in-a-derived-built-in-section.md))
need to know when "the next day" has started, and the section shows a live
"resets in HH:MM" countdown. The obvious implementation — store the last reset
timestamp, subtract, hook `onGameTick` — is wrong in two ways that only surface
in the cases players actually hit: being logged out overnight, and daylight
saving.

## Decision Drivers

* Stage 1 is CUSTOM goals only, which need **zero** client data — reset is pure
  clock arithmetic, so it can and should work while logged out. `onGameTick`
  does not fire when logged out.
* A countdown that reaches 00:00 while nothing resets is the worst possible bug
  for this feature: it is visible, it looks broken, and it invites a bug report.
* Being offline for five days must produce **one** reset, not five.
* The boundary is user-configurable (game reset UTC / local midnight / custom
  hour), so any arithmetic has to survive DST transitions in the local-zone cases.

## Considered Options

* Stored timestamp + delta arithmetic, checked on `onGameTick`
* Period key + `onGameTick`
* Period key + repeating Swing timer
* Rolling 24h from the moment the user checked the goal off

## Decision Outcome

Chosen option: "Period key + repeating Swing timer".

A period key is a plain integer bucket derived from the boundary-adjusted local
date. Each goal stores `lastPeriodKey`; if the computed key differs, reset and
restamp. This makes the check idempotent and inherently correct for the hard
cases: five days offline is one key change, and DST is handled by `java.time`
rather than by hand-rolled offsets.

Each period brings its own bucketing function — there is no shared divisor:

| Period | Key |
| --- | --- |
| `DAILY` | `date.toEpochDay()` |
| `WEEKLY` | `floorDiv(date.toEpochDay() - 6, 7)` — Wednesday-anchored, matching OSRS |
| `MONTHLY` | `date.getYear() * 12 + date.getMonthValue()` — anchored to the 1st |

Months are not uniform-length, so `MONTHLY` cannot divide epoch days the way
the other two do. Anchoring to the 1st also sidesteps the "31st of every month"
problem entirely — there is no month where the anchor date fails to exist.

A single `nextBoundary(mode, hour, period)` feeds **both** the countdown and
the period key, so the two cannot drift apart.

The driver is one repeating `javax.swing.Timer` at **one-minute** granularity
that recomputes the countdown labels and runs the period-key check, with plugin
startup and `ProfileChanged` as backstops for "the plugin was closed
overnight". The `onGameTick` hook is dropped entirely for stage 1 — no
client-thread involvement at all.

### Consequences

* Good, because resets work while logged out, which is when most day boundaries
  are actually crossed.
* Good, because the check is idempotent, so running it every minute is free and
  a missed run is self-healing.
* Good, because the countdown is load-bearing rather than decorative — the same
  timer that displays the boundary enforces it, which is what keeps them
  honest.
* Bad, because a repeating timer is new to this codebase (the existing two are
  one-shot debounces) and leaks if not stopped in `shutDown()`.
* Bad, because minute granularity means a reset can lag the true boundary by up
  to 60 seconds. Accepted: seconds precision on a come-back-tomorrow timer is
  false precision at 60x the repaints.
* Bad, because the countdown must never call `rebuild()` — that is the
  200ms-debounced full-panel path. It mutates label text directly, which is a
  constraint a future edit could violate without any test noticing.

### Confirmation

Tests must pin the period key across all three boundary modes, across a DST
transition day, and for a five-days-offline gap producing exactly one reset.

`MONTHLY` needs its own cases, because its arithmetic is the odd one out:
28-, 30- and 31-day months each advancing the key by exactly one, a February
in a leap year, and a December-to-January rollover (the `year * 12` term is
what makes that work — a bare `getMonthValue()` would collide every January).

All of the above are pinned in `RepeatScheduleTest` and `RepeatResetServiceTest`.
The shared-`nextBoundary` property is asserted directly by
`keyFlipsExactlyAtBoundary`, which walks every period against three boundary
hours and checks the key holds at `boundary - 1ms` and has changed at
`boundary`.

**Implemented with one gap:** the one-minute timer runs the reset check and is
stopped in `shutDown()`, but it does not yet update any countdown label,
because the group headers that would show one are not built (see ADR-0003).
`ProfileChanged` is not yet wired as a backstop either - startup and the minute
timer are.

## Pros and Cons of the Options

### Stored timestamp + delta arithmetic on `onGameTick`

* Bad, because it does not fire while logged out, so the countdown runs to zero
  and nothing happens.
* Bad, because "how many resets did I miss" becomes explicit arithmetic that
  has to get the five-days-offline case right by hand.
* Bad, because DST has to be handled manually with fixed offsets.

### Period key + `onGameTick`

* Good, because the key comparison fixes the offline-gap and DST problems.
* Bad, because it still cannot fire while logged out — the correct model on the
  wrong clock.

### Period key + repeating Swing timer

* Good, because it works logged out and needs no client data.
* Good, because one mechanism serves both the countdown and the reset.
* Bad, because it introduces a repeating timer with a lifecycle to manage.

### Rolling 24h from check-off

* Good, because it never punishes a late-night session.
* Good, because per-goal deadlines would make deadline sorting non-degenerate.
* Bad, because the list drifts out of phase with itself, so "my dailies" stops
  being one coherent list.
* Bad, because it does not match in-game daily resets, which is the mental
  model players already have.

## More Information

Rejected in favour of a configurable boundary defaulting to game reset (00:00
UTC), so shared daily routines behave identically for sender and recipient.
