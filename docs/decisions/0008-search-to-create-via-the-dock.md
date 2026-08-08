---
status: proposed
date: 2026-08-08
decision-makers: ajkatz
---

# Search-to-create: the action dock is the creation surface

## Context and Problem Statement

Creating goals means knowing to right-click a header and walk a dialog - the
same discoverability problem the dock (ADR-0007) solves for actions. The user
wants the search bar to double as the front door: type "zulrah", see your
Zulrah goals, then create a Zulrah goal AND its connected goals (its drops)
without a dialog; type "prayer", create a Prayer goal. The user's refinement:
the creation surface IS the dock we are already building, not a separate draft
card or dialog.

## Decision Drivers

* The dock (ADR-0007) is already a contextual, selection-driven surface with
  scroll-overflow rows. Search-to-create is one more context, not a new widget.
* The connected-goals graph already has code: ItemActivityResolver resolves
  item->boss (built for repeatable goals); item-sources.tsv reversed is
  boss->drops (18 rows for Zulrah, confirmed). Skills/quests/items resolve by
  name against data the plugin already ships.
* One create path beats three. A dialog, an in-game right-click, and a search
  box should converge on the same surface.
* Render-path: this is the eighth-bug category. Screenshot loop, not one-shot.

## Considered Options

* Dialog (status quo) — undiscoverable, the thing being removed
* A separate inline "draft card" component with editable fields
* The dock becomes the creation surface (user's refinement)

## Decision Outcome

Chosen option: "The dock becomes the creation surface."

DockContext gains a search-query dimension. State machine:

| condition | dock |
|---|---|
| goal selected | goal actions (built) |
| multi selected | bulk actions (built) |
| section selected | section actions (planned) |
| search text + no selection | `+ Add <match> goal` + connected suggestions |
| nothing | panel actions (built) |

Typing filters the list to matching goals (existing searchGoals) AND puts
create-suggestions in the dock: the exact match in the top row, connected
goals scrolling in the bottom row. A suggestion either creates with a sane
default target or drops an inline target field in the dock (it already
renders editable content). No new component for v1 - the dock is enough.

### Consequences

* Good, because creation, actions, and bulk all live in one contextual
  surface reached the same way. The three create entry points converge.
* Good, because the connected-goals resolvers already exist - v1 is wiring,
  not new data.
* Bad, and this is the crux: RANKING. Zulrah has ~18 drop rows; 18
  suggestions is noise. There is no notability data locally (the wall that
  stalled the bite-sized-suggestion slider). v1 shows the direct match + its
  single strongest edge (item->its boss, boss->KC), long tail behind an
  "expand" affordance - NOT a firehose. Suggestion ranking is the whole risk.
* Bad, because inline editing in a fixed-height dock is tight; a target field
  may need the dock to borrow a row, which brushes the never-resize rule.
  Resolve during the screenshot pass.

### Confirmation

The suggestion resolver (query -> ranked create-suggestions) is pure and
unit-tested - the ranking is exactly what must not silently degrade. The dock
rendering is screenshot-verified.

## Refined vision (the 1.0.0 target)

The panel is **moded**, not selection-inferred - explicit `Select` / `Create`
tabs. This fixes the "janky" scattered-chip read and reconciles with
ADR-0007's anti-jitter rule: height changing on a MODE switch is a deliberate
drawer-open; the rule only ever forbade height jumping around WITHIN a mode.

**Create mode fully replaces GoalDialogFactory** - the 1.0.0 thesis is "drive
the plugin without knowing a right-click exists." Flow:

1. Type grid (Skill/Quest/Diary/Combat/Boss/Item/Account/Custom).
2. Type-specific sub-view - navigation depth varies by type: Item = search +
   results grid + qty; Skill = skill pick + level/xp toggle; Account = metric
   list + target; Custom = name field. This is the "navigation and
   subnavigation" ask and the bulk of the build.
3. Parameters live in the form and it GROWS to fit them: toggle Repeatable and
   the period pills + per-period amount appear inline. The form reflects
   consequences (turn on Repeatable -> Section auto-locks to Repeatable).

Create mode is tall (~340px) and should TAKE OVER the panel (overlay the list)
when active, not split it - creating is a focused act.

Search-to-create (this ADR's original subject) becomes: typing in Select mode
surfaces matching goals AND a "create these" affordance that hands off into
Create mode pre-seeded with the query and its connected goals.

Confirmed as the **1.0.0** milestone (user, 2026-08-08). Mockups: three
show_widget passes this session (states; type grid + item sub-flow; skill form
repeat off/on). Build is per-type, screenshot-loop, one sub-view at a time.
The moded shell + Skill sub-view first (most parameters), then Item (richest
navigation), then the rest.

## More Information

Depends on the dock (ADR-0007) reaching section-selection first, so the
context switch is proven before adding a fifth state. Naming: the create
block wants a label - "Create", "Add new", or unlabelled `+` rows; decide
in-client. The item<->boss graph is symmetric and already coded both ways.
