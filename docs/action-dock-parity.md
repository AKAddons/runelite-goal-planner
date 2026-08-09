# Action dock parity table (ADR-0007)

Every context-menu action, its dock disposition, and the state it appears in.
The right-click menus cannot be removed until every row here is **done**. This
table is the spec; argue with the table, not the Swing.

Dispositions:
- **button** — a dock button in the listed state
- **dialog** — a dock button that opens an existing dialog (tag picker, color
  picker, section picker) — dialogs are not the popups being removed
- **inline** — an affordance on the card itself, not the dock
- **drop** — removed; drag-reorder or another path already covers it

## Goal selected (one)

| Action | Disposition | Notes |
|---|---|---|
| Mark Complete / Reopen | button | ✅ dock |
| Mark Optional / Required | button | ✅ dock (hidden on complete) |
| Remove Goal | button | ✅ dock |
| Change Amount | dialog | ✅ dock — SKILL→skill dialog, ITEM/BOSS→prompt; omitted otherwise |
| Change Name | dialog | ✅ dock — CUSTOM, JOptionPane (mirrors menu) |
| Change Description | dialog | ✅ dock — CUSTOM, JOptionPane (mirrors menu) |
| Change Color | dialog | ✅ dock — reuses showGoalColorDialog |
| Repeat (period) | button+submenu→inline row | ✅ dock — combo chooser: custom period / derived edit |
| Repeatable goal (derive) | dialog | ✅ dock — SKILL + derivable item; period→amount chooser |
| Add Tag / Remove Tags | dialog | ✅ dock — reuses TagPickerDialog / MultiSelectDialog |
| Requires… / Required by… | dialog | ✅ dock — enterRelationMode (click-to-link) |
| Remove Requirements / Dependents | dialog | ✅ dock — MultiSelectDialog, gated on edges existing |
| Add requirements to this section | button | ✅ dock — chooser: incomplete-only vs all |
| Restore Defaults | button | ✅ dock — only when overridden |
| Move to Section / New Section | dialog | ✅ dock — combo chooser (+ New section...) |
| Duplicate to Section / New | dialog | ✅ dock — combo chooser (+ New section...) |
| Move Up / Down / Top / Bottom | **drop** | drag-reorder covers ordering |
| Add Goal Above/Below This | **drop** | positional; use section-header add |
| Search in Loadout Lab | button | ✅ dock — BOSS, install-aware; disabled nudge when installed-off |
| Copy share code / Save share code | button | ✅ dock — single-goal share |
| Deselect this / all but this | **drop** | dock has Deselect; niche menu-isms |
| Wiki | inline | card already can carry a wiki link |

## Multiple goals selected

| Action | Disposition | Notes |
|---|---|---|
| N selected (hint) | inline | ✅ dock |
| Bulk reset done | button | ✅ dock |
| Bulk remove | button | ✅ dock |
| Bulk mark complete | button | ✅ dock — only when all selected are CUSTOM (mirrors menu) |
| Bulk optional / required | button | ✅ dock — applies to non-completed goals |
| Bulk move to section | dialog | ✅ dock — combo chooser (bulkMoveGoalsToSection) |
| Bulk duplicate to section | dialog | ✅ dock — combo chooser (duplicateGoalsToSection) |
| Bulk add/remove tag | dialog | ✅ dock — reuses showBulkAddTagDialog / showBulkRemoveTagDialog |
| Bulk change color | dialog | ✅ dock — reuses showBulkChangeColorDialog |
| Bulk restore defaults | button | ✅ dock — when any selected is overridden |
| Bulk copy / save share code | button | ✅ dock — one code for the selection |
| Deselect All | button | ✅ dock |
| Bulk relations (Requires / Required by / remove common) | dialog | NOT wired — bulk relation edge editing deferred (see progress doc) |
| Bulk move up/down/top/bottom | **drop** | in-section bulk reorder; drag-reorder covers it |

## Section selected (NEW — sections become selectable)

| Action | Disposition | Notes |
|---|---|---|
| Rename | dialog | |
| Change Color | dialog | |
| Delete Section | button | confirm |
| Add Goal (into section) | button | opens add-goal dialog targeting it |
| Add requirements to this section | button | |
| Dependency nesting (nested/flat/default) | button cycle | |
| Completed-goal handling (archive override) | button cycle | |
| Copy / Save share code (section) | button | |
| Select all / Deselect all in section | button | |

## Nothing selected (panel level)

| Action | Disposition | Notes |
|---|---|---|
| Add Goal | button | top-level add |
| Add Section | button | ✅ in skeleton |
| Import shared goals | button | |
| Saved plans | button | |
| Copy / Save share code (all sections) | button | |
| Undo / Redo | inline | already a top toolbar row; may fold in |

## Counts

- ~13 goal-state actions (after 5 drops), ~7 multi, ~9 section, ~6 panel.
- Scroll-overflow (ADR-0007) means no hard per-state ceiling; put the
  common actions in the visible slots and let the tail scroll.
- Removing GoalContextMenuBuilder deletes ~2,072 lines; the dock + dialogs
  it reuses should net well under that against the hub token cap.
