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

## More Information

Depends on the dock (ADR-0007) reaching section-selection first, so the
context switch is proven before adding a fifth state. Naming: the create
block wants a label - "Create", "Add new", or unlabelled `+` rows; decide
in-client. The item<->boss graph is symmetric and already coded both ways.
