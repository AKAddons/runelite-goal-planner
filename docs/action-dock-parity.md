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
| Mark Complete / Reopen | button | ✅ in skeleton |
| Mark Optional / Required | button | ✅ in skeleton |
| Remove Goal | button | ✅ in skeleton |
| Change Amount | dialog | SKILL/ITEM/BOSS only; grey otherwise |
| Change Name | dialog | CUSTOM only |
| Change Description | dialog | CUSTOM only |
| Change Color | dialog | reuse ColorPickerField dialog |
| Repeat (period) | button+submenu→inline row | the Repeats/Amount picker, CUSTOM + derived |
| Repeatable goal (derive) | dialog | SKILL + item goals; the period×size picker |
| Add Tag / Remove Tags | dialog | reuse TagPickerDialog / TagManagementDialog |
| Requires… / Required by… | dialog | relation editors |
| Remove Requirements / Dependents | dialog | |
| Add requirements to this section | button | incomplete-only vs all → dialog choice |
| Restore Defaults | button | only when overridden |
| Move to Section / New Section | dialog | section picker |
| Duplicate to Section / New | dialog | section picker |
| Move Up / Down / Top / Bottom | **drop** | drag-reorder covers ordering |
| Add Goal Above/Below This | **drop** | positional; use section-header add |
| Search in Loadout Lab | button | install-aware; hidden when not installed |
| Copy share code / Save share code | button | single-goal share |
| Deselect this / all but this | **drop** | dock has Deselect; niche menu-isms |
| Wiki | inline | card already can carry a wiki link |

## Multiple goals selected

| Action | Disposition | Notes |
|---|---|---|
| N selected (hint) | inline | ✅ in skeleton |
| Bulk reset done | button | ✅ in skeleton |
| Bulk remove | button | ✅ in skeleton |
| Bulk move to section | dialog | section picker |
| Bulk add/remove tag | dialog | |
| Bulk restore defaults | button | |
| Deselect All | button | ✅ in skeleton |

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
