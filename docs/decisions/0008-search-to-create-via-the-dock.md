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

The dock is **contextual, inferred from selection - NOT tabbed** (user
correction 2026-08-08, superseding an earlier Select/Create tab sketch). The
goal LIST stays exactly as it is today and never re-lays-out; only the DOCK
changes. Nothing selected -> create surface; one+ goals/sections selected ->
their actions. This is the original DockContext.of(selection) model. Guiding
principle from the user: too much navigation is an anti-pattern - keep the
user inside their goal list whenever possible, so create navigates INSIDE the
dock, never at panel level.

Height tension resolved by PEEK-AND-EXPAND (user, 2026-08-08): the dock's
resting state is a single-line bar - a green "+ Add a goal" invitation when
nothing is selected, or a "<N> selected" peek when something is. The list gets
nearly the whole panel at rest. Tapping the bar expands the dock upward into
the full contextual surface (create grid/form, or the action grid); tapping
again collapses it. The bar is the persistent handle - chevron-up = expand,
chevron-down = collapse. Drag stays as a bonus for a custom split. So the
DEFAULT footprint is one line; the dock only claims height while actively in
use and hands it back after. One pattern for both create and act.

Within the expanded create surface: PROGRESSIVE DISCLOSURE keeps the form
minimal (type + primary field + Add), with Repeat/Section/Tags behind a
"more options" row that names what it hides. The type GRID is the create
entry (the 8 type tiles), not a mid-flow landing.

**The create surface fully replaces GoalDialogFactory** - the 1.0.0 thesis is
"drive the plugin without knowing a right-click exists." Flow (all inside the
dock):

1. Type grid (Skill/Quest/Diary/Combat/Boss/Item/Account/Custom).
2. Type-specific sub-view - navigation depth varies by type: Item = search +
   results grid + qty; Skill = skill pick + level/xp toggle; Account = metric
   list + target; Custom = name field. This is the "navigation and
   subnavigation" ask and the bulk of the build.
3. Parameters live in the form and it GROWS to fit them: toggle Repeatable and
   the period pills + per-period amount appear inline. The form reflects
   consequences (turn on Repeatable -> Section auto-locks to Repeatable).

The create surface shares the panel with the list (list on top, dock at
bottom); it does NOT take over or overlay. Resizable + progressively
disclosed so it stays as compact as the moment needs.

Search-to-create (this ADR's original subject): typing filters the list AND
the empty-selection dock offers "create these" for the query + its connected
goals, pre-seeding the create form - still no tab, still selection-inferred.

Confirmed as the **1.0.0** milestone (user, 2026-08-08). Mockups: three
show_widget passes this session (states; type grid + item sub-flow; skill form
repeat off/on). Build is per-type, screenshot-loop, one sub-view at a time.
The contextual dock + Skill sub-view first (most parameters), then Item (richest
navigation), then the rest.

## Unified create/edit form (user refinement 2026-08-08)

Create and EDIT are the SAME form, parameterized by mode. Selecting a goal
shows its values IN the form, pre-filled - the amount is an editable FIELD,
not a "Change Amount" button that opens a dialog. This also removes the
JOptionPane choosers for parametric edits. Every menu action sorts into:

- FIELDS (inline, edit in place, apply-on-commit = blur/Enter, one undo each):
  amount/level, name, description, repeat (toggle + period pills + chunk),
  color. These are the goal's PARAMETERS - present in both create and edit.
- ACTIONS (chips/buttons, edit mode only - no create equivalent): Complete/
  Reopen (a checkbox at the form top), Remove, Tags, Move/Duplicate,
  Requirements ("Needs"), Share, Loadout Lab. Lifecycle + relations.

Create mode = blank fields + primary "Add goal". Edit mode = prefilled fields
that commit on blur/Enter (no Add button) + the lifecycle chips. Same
per-type form component underneath (buildSkillForm etc. gain a mode + optional
goal). The full-width CREATE / SELECTED indicator bar heads the surface in
both modes.

## More Information

Depends on the dock (ADR-0007) reaching section-selection first, so the
context switch is proven before adding a fifth state. Naming: the create
block wants a label - "Create", "Add new", or unlabelled `+` rows; decide
in-client. The item<->boss graph is symmetric and already coded both ways.
