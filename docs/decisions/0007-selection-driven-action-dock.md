---
status: accepted
date: 2026-08-08
decision-makers: ajkatz
---

# A selection-driven action dock replaces the panel's right-click menus

## Context and Problem Statement

Every mutating action in the panel hides behind right-click context menus -
125 items across a 2,072-line builder. Nothing in the UI advertises that the
menus exist, which makes the plugin's entire mutation surface undiscoverable.
The user wants a permanent control panel docked at the bottom of the sidebar
and the popup menus gone. In-game right-click menus (stats tab, collection
log, etc.) are explicitly out of scope and stay.

## Decision Drivers

* The selection model (selectedGoalIds, multi-select, select-all) already
  exists and answers the question a fixed panel must answer: what does an
  action operate on?
* Sidebar geometry: 242px wide; the user's real window gives the goal list
  ~11 cards. One dock row of icon buttons is ~32px, about 0.6 of a card.
* Eight render-path bugs slipped past a green suite in 0.5.0. This is the
  project's largest-ever Swing change; screenshots drive truth, not tests.

## Considered Options

* Fixed single row with a "More" overflow menu
* Fixed two-row dock, content contextual, height constant, collapsible
* Variable height per selection state
* Three labeled rows

## Decision Outcome

Chosen option: "Fixed two-row dock, collapsible", with one amendment from
review: **overflow is scroll, not a menu.** Each row is a horizontally
scrollable strip - the visible slot count is a tuning knob, and the tail of
a state's actions is reached by scrolling the row, never by opening a popup
(which would reintroduce the thing being removed).

The dock is selection-driven with four states: nothing selected (panel-level
actions), one goal, multiple goals, one section (sections become selectable;
their header menu dies with the goal menus). Selection changes swap the
dock's CONTENT, never its HEIGHT - a panel that resizes on click makes the
list shift under the cursor, the same jitter completed-dailies sorting
deliberately avoids. A chevron collapses the dock to a slim handle for
small screens and large font scales.

### Consequences

* Good, because every action becomes discoverable; bulk actions - today the
  least visible - become the most.
* Good, because scroll-overflow removes the hard 13-actions-per-state
  ceiling a fixed grid imposed, at the cost of scrolled-away actions being
  semi-hidden. Aim for the important actions in the visible slots anyway.
* Bad, because the dock taxes ~1.2 cards of list height (more at font scale
  1.3), partially mitigated by collapse.
* Bad, because positional menu actions (add above/below this goal, move
  up/down) have no dock equivalent; drag-reorder absorbs ordering and the
  positional adds are dropped.
* Migration: the dock is built ALONGSIDE the menus, verified for parity via
  a full 125-action disposition table, and the menus are removed last -
  both never ship together in a release.

### Confirmation

DockContext (pure state resolver) is unit-tested. The dock itself is
render-path: verified by in-client screenshots per increment.

## More Information

Skeleton ships first: empty/one-goal/multi states wired to existing API
actions, text buttons, session-only collapse. The parity table, section
selection, iconography (ShapeIcons - tofu constraint), and persistent
collapse follow. Height numbers: CARD_HEIGHT 48 x font scale + 4 gap;
dock row 26px buttons + insets = 64px total for two rows.
