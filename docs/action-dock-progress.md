# Action dock — create-surface progress (ADR-0008)

Status as of the `feat/action-dock` build of the contextual create surface.
Everything here is **render-path** work that has NOT been verified in-client
(no client available while the designer was AFK). Treat every layout/spacing
choice as provisional until screenshot-verified.

## What was built

### 1. Dock can render an arbitrary expanded surface (`ActionDock`)
The dock previously rendered only two button strips (`setRows`). It now also
accepts an arbitrary component for its expanded area via
`setExpandedComponent(JComponent)`; the two modes swap in a shared `centerHost`.
- The create surface grows to fit its form, capped at `CREATE_MAX_H` (300px);
  taller content scrolls inside the dock so the goal list keeps a usable
  minimum. `getPreferredSize()` reads the mounted view's preferred height.
- The peek-and-expand shell, collapse behaviour, and button-strip (selection)
  mode are unchanged. `setRows` swaps back out of create mode automatically.

### 2. Create navigation + grid (`GoalPanel.refreshDock` + helpers)
All create-surface assembly lives in `GoalPanel`, reached only from
`refreshDock()` via `buildCreateSurface()` — the single-place rule (ADR-0007).
- New nav state `dockCreateType` (a `GoalType`, or null = grid). It is
  deliberately **kept out of `DockContext`**, which stays pure and
  selection-only; create-nav is orthogonal to the selection-driven state, so
  adding it to `DockContext` would have coupled two unrelated axes. No
  `DockContext` change means its existing unit test still fully covers it.
- `dockCreateMounted` / `dockCreateMountedType` let `refreshDock` skip
  rebuilding the surface when the mounted view already matches the requested
  nav, so a half-filled form survives unrelated dock refreshes.
- **EMPTY state now renders the create surface** instead of the old lone
  "Add Section" strip. The type **GRID** shows all 8 tiles (Skill, Quest,
  Diary, Combat, Boss, Item, Account, Custom). "New Section" was removed from
  the grid (it read as confusing nested under "Add a goal"); sections keep
  their own creation path. Each tile carries a colored top rule for type
  identity and navigates the dock (no dialog, list never moves).
- A **Back** control on every form returns to the grid. On a successful create,
  `navigateCreate(null)` resets the create-nav to the grid; note that
  `selectAfterCreate` then selects the new goal, so the dock refreshes into that
  goal's GOAL-state action strip (see the post-create note in the deferred
  section).

### 3. Every create form is now wired (`GoalPanel.build*Form`)
All eight type tiles route to a real, wired form (no placeholders left in the
reachable path). Each form validates and calls the existing API; the dock
returns toward the grid via `navigateCreate(null)` on success (selection then
moves to the just-created goal, so the dock shows that goal's actions — the
existing `selectAfterCreate` behavior).

- **Skill** (`buildSkillForm`) — a GRID of `SkillIconManager` icon buttons
  (all trainable skills, `Skill.values()` minus OVERALL, ~3 rows of 8; tap to
  select + highlight) replacing the old dropdown, per live feedback. Then the
  shared `SkillTargetForm` (synced Level/XP). `api.addSkillGoal(skill, xp)`.
- **Repeatable disclosure** (`addRepeatDisclosure` + `buildPeriodPills` +
  `RepeatControls`) on Skill and Boss — a "More options" row reveals a
  Repeatable toggle; when on, Daily/Weekly/Monthly pills + a per-period amount
  appear and the note says it lands in the Repeatable section. Add then creates
  the long-term goal AND derives a per-period slice via
  `api.createDerivedRepeatGoal(parentId, period, chunk, activityName)`, wrapped
  in a `beginCompound/endCompound` for one undo, run through
  `runOnClientThread` (the derive reads live XP/varp, which asserts on the EDT
  under `-ea` and returns null — this is why the menu path also uses the client
  thread). Skill passes `activityName=null`; Boss passes the boss name.
- **Account** (`buildAccountForm`) — metric combo (leagues metrics filtered out
  off a leagues profile, matching the dialog) + target → `api.addAccountGoal`.
- **Custom** (`buildCustomForm`) — name + optional description →
  `api.addCustomGoal`.
- **Boss** (`buildBossForm`) — `BossKillData.getBossNames()` combo + target KC
  → `api.addBossGoal`, plus the repeatable disclosure above.
- **Quest** (`buildQuestForm`) — `Quest.values()` combo (name-rendered) →
  `api.addQuestGoal`.
- **Diary** (`buildDiaryForm`) — area combo (`DIARY_AREAS`, in-game journal
  names that round-trip through `AchievementDiaryData.normalizeAreaKey`) + tier
  combo (`GoalPlannerApi.DiaryTier`) → `api.addDiaryGoal`.
- **Item** (`buildItemForm`) — `itemManager.search` (the icon picker's source)
  renders up to 8 tappable icon+name result rows (`buildItemResultRow`); pick
  one + quantity → `api.addItemGoal`. The ADR's "richest navigation" form.
- **Combat** (`buildCombatForm`) — a real CA name/description search
  (`buildPickRow`) replacing the numeric-id stopgap. Needed a new
  `WikiCaRepository.search(query, limit)` and a `searchCombatAchievements`
  accessor on `GoalPlannerApiImpl` (the repo is package-private). Pick →
  `api.addCombatAchievementGoal(id)`.

`buildPendingForm` remains only as a defensive fallback for a future GoalType
tile with no form — it is unreachable for the current eight.

### 4. Add-a-goal prominence (structure)
Already satisfied structurally: the resting peek bar is the green
"+ Add a goal" primary invitation (`ActionDock` `PEEK_CREATE_*`), and "New
Section" is omitted from the create surface (removed in `a1a4fae`; sections have
their own creation path). No competing side-by-side section affordance was
reintroduced. `promptAddSectionFromDock` is currently unused (left in place for
a future subordinate section control); the create grid title is "Add a goal".

## Unified create/edit form (ADR-0008): GOAL state is now the edit form

Selecting a single goal no longer renders the `buildGoalDock` button strip.
`refreshDock()`'s GOAL case now mounts the **same per-type form as create**,
pre-filled, via `setExpandedComponent` (like the create surface). The goal's
PARAMETERS are inline commit-on-blur/Enter FIELDS; its lifecycle + relations
are ACTION chips. This removes the `Amount` / `Name` / `Desc` / `Repeat`
JOptionPane-chooser buttons for the routed types. All assembly stays in
`GoalPanel` (`buildEditSurface` → `build*EditBody` + `editFormScaffold` +
`buildEditChips`), single-place rule intact.

`usesUnifiedEditForm(type)` gates the routing. It returns true for **SKILL,
ITEM_GRIND, BOSS, CUSTOM, ACCOUNT, QUEST, DIARY, COMBAT_ACHIEVEMENT** — i.e.
every user-selectable type. `buildGoalDock` (the legacy button strip) stays as
the fallback for anything not routed (e.g. COLLECTION_LOG) and is otherwise
now unreferenced by the GOAL path; it is a deletion candidate for the later
menu-removal step, not removed here.

### Mount guard (why in-progress edits survive)
`dockEditMounted` / `dockEditMountedGoalId` mirror the create-surface guard:
`refreshDock` re-mounts the edit form only when the selected goal id changes,
so a same-goal refresh does not wipe a half-typed field. In practice a field
commit does **not** even reach `refreshDock`: store mutations fire
`onGoalsChanged` → the debounced `panel.rebuild()`, which rebuilds the goal
LIST but never touches the dock, and `onSelectionChanged` only fires when a
prune drops a now-missing selected id (never on an in-place edit of the
selected goal). The guard is the belt-and-suspenders for the rare genuine
`refreshDock`. Structural chip actions call `refreshEditForm()` (drops the
guard + refreshes) so the form re-renders to reflect them.

### FIELDS — inline, apply-on-commit (blur or Enter), one undo each
Each commit calls the API directly; every API method already no-ops on an
unchanged value, so an idle blur never spams undo history. An invalid entry
(blank / non-positive) resets the field to the model value on commit.

- **Target / level / amount** → `api.changeTarget(id, newTarget)`.
  - SKILL: the shared `SkillTargetForm`, seeded by the new
    `SkillTargetForm.setTargetXp(int)` and committed through the new
    `SkillTargetForm.onCommit(Runnable)` (fires on Enter / blur of either the
    Level or XP row). Target XP round-trip is unit-tested (`SkillTargetFormTest`).
  - ITEM_GRIND (quantity), BOSS (kill count), ACCOUNT (target): a `JTextField`
    via `commitOnBlurOrEnter`.
  - The skill icon+name, boss name, and account metric are shown **read-only**
    (there is no API to change a goal's skill/boss/metric — that is a different
    goal).
- **Name / Description** (CUSTOM only) → `api.editCustomGoal(id, name, desc)`
  (name commit passes `desc=null` to keep it; desc commit passes `name=null`).
- **Repeat** → INLINE now (the point of the change): a Repeatable toggle +
  Daily/Weekly/Monthly pills (`buildEditPeriodPills`, each commits
  `api.setGoalRepeat`) + a per-period amount field (`api.setGoalRepeatChunk`).
  `addEditRepeatControls` renders this ONLY for goals that carry their own
  repeat state — CUSTOM goals and derived per-period slices (`repeatChunk > 0`).
  A derived slice edits its per-period **chunk** here and its raw target field
  is suppressed (the chunk drives the target). Toggling off →
  `setGoalRepeat(NONE)`.
- **Color**: kept as an action chip reusing `dialogFactory.showGoalColorDialog`
  — an inline color-swatch field is deferred glam (see below). This is the one
  FIELD from ADR-0008 not yet inline.

### ACTIONS — chips (edit mode only), each REUSES an existing handler
`buildEditChips` lays the lifecycle + relations out in a `WrapLayout` flow
(wraps to multiple lines, grows the dock vertically). Complete/Reopen is the
checkbox at the form top (CUSTOM/ITEM manual-complete rule mirrored from
`buildGoalDock`; auto-tracked types get Reopen only once complete). The chips:
Optional/Required, **Make repeatable** (derive — a plain SKILL / derivable
ITEM grind; deriving creates a NEW slice goal, so it is an action not the
inline toggle), Color, Add tag / Drop tags, Requires / Required by / Drop reqs
/ Drop dependents (gated on `!complete` + edges), Add reqs to section,
Move/Copy to section, Restore defaults, Loadout Lab (BOSS, install-aware),
Copy/Save code, Deselect, Remove. Every one calls the same `dock*` helper /
dialog `buildGoalDock` used — no dialog rebuilt.

### Full-width indicator + width-tracking surface
Both surfaces are headed by a **full-width** context bar (`indicatorBar` inside
`surfaceShell`): green **CREATE** for the create grid/forms, neutral
**SELECTED** for the edit form. To make the bar actually span the fixed-width
RuneLite side panel — and to let BoxLayout rows and the chip `WrapLayout` lay
out against the real dock width instead of clipping — the shell root is a new
`ScrollablePanel` (`Scrollable`, `getScrollableTracksViewportWidth()=true`).
Create forms now also fill the dock width as a side effect (previously they
took only their preferred width). `buildCreateGrid` + `createFormScaffold` were
re-wrapped through `surfaceShell`.

### QUEST / DIARY / COMBAT_ACHIEVEMENT — thin edit
Immutable targets, so `buildThinEditBody` is just a read-only name (+ optional
description) plus the lifecycle chips. No editable parameter field.

### Needs the designer's in-client screenshot verification (HEAVY render path)
- **Commit-on-blur feel**: does tabbing/clicking away from the target/name/desc
  field commit cleanly, with no focus jump (the form is NOT rebuilt on commit,
  so focus should be stable) and no surprise undo entries on an untouched blur?
- **Inline repeat controls**: the toggle → pills → amount reveal, the pill
  selected-state colors, the chunk field, and that toggling grows/shrinks the
  dock (`remeasureDock`) without clipping. Highest-risk visual after the create
  disclosure.
- **Full-width indicator bar**: confirm it truly spans edge-to-edge in both
  modes and reads as a context header, not a button.
- **WrapLayout chip flow**: confirm chips wrap onto multiple lines against the
  panel width and the dock grows to fit (capped at 300px, then scrolls). First
  measure before the panel is realized can under-report height (targetWidth 0 →
  single row); a revalidate corrects it — confirm no lasting clip.
- **Layout when the form grows**: a rich BOSS/SKILL edit (read-only header +
  target + repeat block + ~15 chips) is the tallest surface; confirm the 300px
  cap + internal scroll keeps the goal list usable and nothing is stranded.
- **Auto-tracked staleness**: a field edit that flips completion (e.g. lowering
  a target below current) updates the LIST card but not the mounted form's
  checkbox until reselect — confirm this is acceptable or force a refresh.
- Font scales 1.0 / 1.3 across all of the above.

### Deferred glam (screenshot loop)
- **Inline color field**: color stays a dialog chip; an inline swatch/preset
  picker committing `api.setGoalColor` is the eventual FIELD form.
- Additive-green vs act-on-gray chip accents (same deferral as the strip).
- Read-only header styling (skill icon size, boss/metric label), the
  SELECTED bar wording (currently just "SELECTED" — could carry the goal name).
- Whether post-edit the form should also live-refresh structural chips without
  a reselect.

## Select surface (ADR-0007): GOAL + MULTI actions migrated

The dock's SELECT side now carries the full goal-selection and
multi-selection action surface — the dock replaces right-click on goals.
All assembly is in `GoalPanel.refreshDock` → `buildGoalDock` /
`buildMultiDock` plus private `dock*` helpers (single-place rule). Every
button REUSES an existing dialog/flow; no dialog was rebuilt. The
right-click menus (`GoalContextMenuBuilder`) + `GoalDialogFactory` remain
fully intact — deletion is the later step once this is screenshot-verified.

Layout: top row = lifecycle + primary edits; bottom row = the organize
cluster, grouped by small-caps separators and reached by horizontal scroll
when it overflows (ADR-0007 scroll-overflow, no per-state ceiling).

### GOAL state (`buildGoalDock`) — dialogs/flows reused
- **Complete / Reopen, Optional / Required, Remove, Deselect** — direct API.
- **Change Amount** (`dockChangeAmount`) — SKILL routes to
  `dialogFactory.showChangeSkillTargetDialog`; ITEM_GRIND/BOSS use a
  quantity/kill-count `JOptionPane` prompt → `api.changeTarget` (mirrors the
  menu's inline prompts). Omitted for types with no numeric target.
- **Change Name / Description** (`dockChangeName` / `dockChangeDescription`)
  — CUSTOM only, not-complete → `api.editCustomGoal`.
- **Change Color** — reuses `dialogFactory.showGoalColorDialog`.
- **Repeat** (`buildRepeatItem` + `dockSetCustomRepeat` / `dockEditRepeat` /
  `dockDeriveRepeat` / `dockDeriveItemRepeat`) — one button, branch by goal:
  CUSTOM sets a period (`setGoalRepeat`); an existing derived goal
  (`repeatChunk > 0`) edits period/amount (`setGoalRepeat` /
  `setGoalRepeatChunk`); a SKILL or derivable ITEM grind derives a per-period
  slice (`createDerivedRepeatGoal`, run on the client thread — it reads live
  XP / kill-count). Item goals with multiple activities pick the activity
  first. Period×amount rendered as chained combo choosers.
- **Add tag / Drop tags** (`dockAddTag` / `dockRemoveTags`, `removableTagsFor`)
  — reuses `TagPickerDialog` and `MultiSelectDialog`; removable-tag rule
  mirrors the menu (CUSTOM = any, else non-default only).
- **Requires / Required by** — reuses `enterRelationMode` (click-to-link).
  **Drop reqs / Drop dependents** (`dockRemoveRequirements` /
  `dockRemoveDependents`) — `MultiSelectDialog`, shown only when edges exist.
- **Add reqs to section** (`dockSeedReqs`, `goalHasSeedableReqs`) — QUEST/
  DIARY/BOSS with game-data reqs; chooser offers incomplete-only vs all,
  seeded on the client thread.
- **Move to section / Copy to section** (`dockMoveToSection` /
  `dockDuplicateToSection`) — combo chooser mirroring the menu's destination
  logic (Default entry when the goal is outside it, every other user section,
  New section...). Reuses `moveGoalToSection` / `moveGoalsToDefault` /
  `duplicateGoalsToSection` and `createSection` (`promptNewSectionThen`).
- **Restore defaults** — only when `api.isGoalOverridden`, via
  `bulkRestoreDefaults`.
- **Loadout Lab** — BOSS only, install-aware: enabled → `searchLoadoutLab`;
  installed-but-disabled → a disabled "Lab is off" nudge; not installed →
  absent (mirrors the menu exactly).
- **Copy code / Save code** — single-goal share via `copyGoalsShareCode` /
  `saveGoalsPlan`, gated on `isShareAvailable` / `isSavedPlansAvailable`.

### MULTI state (`buildMultiDock`) — dialogs/flows reused
- **Reset done, Remove, Deselect all** — direct bulk API.
- **Complete** — only when every selected goal is CUSTOM (mirrors the bulk
  menu); one compound.
- **Optional / Required** (`bulkSetOptional`) — applies to the non-completed
  goals; one compound each.
- **Color** — reuses `dialogFactory.showBulkChangeColorDialog`.
- **Add tag / Drop tags** — reuses `showBulkAddTagDialog` /
  `showBulkRemoveTagDialog` (+ `getRemovableTagsForSelection`).
- **Move to section / Copy to section** (`dockBulkMoveToSection` /
  `dockBulkDuplicateToSection`) — combo chooser mirroring the bulk menu's
  destination logic; reuses `bulkMoveGoalsToSection` / `moveGoalsToDefault` /
  `duplicateGoalsToSection`.
- **Restore defaults** — when any selected goal is overridden.
- **Copy code / Save code** — one code for the whole selection.

### Dropped per the parity table (intentional, not missing)
Move up/down/top/bottom, add above/below this, deselect-this /
deselect-all-but-this — drag-reorder and the dock's Deselect cover these.
Submenu picks that the fixed-height strip can't nest (section, repeat
period/amount, seed scope, activity) render through `dockChooser`, a
single-choice combo prompt — the dock's stand-in for a menu submenu, so no
popup is revived.

### NOT wired (and why)
- **Bulk relations** (bulk Requires / Required by, remove-common-requirements
  / dependents): the bulk menu computes edge intersections and enters a
  multi-source relation mode. `enterRelationMode(Set, boolean)` exists, but
  the remove-common flows and the intersection UX are the fiddliest surface
  and lower value than the rest; deferred to a follow-up. Single-goal
  relations are fully wired.
- **Leagues shortcut submenu** (direct-create league point/task goals at
  tier milestones): a create-side, leagues-profile-only affordance that lives
  on the goal-card "Add Goal" submenu, not a select action on the goal —
  belongs with the create surface / section work, not this pass.

### Needs the designer's in-client screenshot verification
- The bottom organize row is long on a rich goal (tags + reqs + move + lab +
  share + remove). Scroll-overflow is supposed to pan it; CONFIRM the tail is
  reachable by wheel and that Remove/Deselect at the far right aren't stranded
  — may want the most destructive/common actions pulled left, or a second
  pass on top/bottom split.
- Separator small-caps labels ("tag", "requires", "organize", "share",
  "lab", "goal", "select") as cluster dividers inside a scrolling strip —
  confirm they read as groups and aren't mistaken for buttons.
- The `dockChooser` combo prompt (JOptionPane) for section/repeat/seed picks
  — acceptable as a stand-in, but it IS a popup dialog; confirm the feel vs a
  future inline dock row. The parity table calls these "dialog" disposition,
  so a combo dialog is within spec, but the 1.0.0 thesis prefers staying in
  the dock — an inline picker row is the eventual target.
- Repeat's chained choosers (period → amount, or activity → period → amount)
  are several dialogs deep for the derive path; confirm that's tolerable or
  wants collapsing into one form (the create surface's repeatable disclosure
  is the richer pattern to mirror later).
- Label truncation: button labels are short ASCII ("Drop reqs", "Add reqs to
  section", "Move to section"); confirm they fit and read clearly at font
  scales 1.0 / 1.3.

### Deferred glam (screenshot loop)
- **Additive-green vs act-on-gray** (ADR-0007/0008): all dock buttons render
  uniform gray today. `ActionDock.makeButton` would need an accent flag on
  `Item`; deliberately skipped this pass (the human polishes color via
  screenshots, per the build guidance). Add-tag / Requires / Add-reqs are the
  additive candidates for green.
- Exact grouping order, which actions earn a top-row slot vs the scrolling
  bottom row, separator styling, spacing.

## Over the token cap — on purpose

`checkTokens` is RED and expected to be: main source is ~200,365 / 200,000. Per
ADR-0008 + memory, the plan is to build the full create UX over cap now, then
DELETE the right-click menus (`GoalContextMenuBuilder`) + `GoalDialogFactory`
(~21k tokens) once in-client parity is verified, which brings it back under.
Until that deletion step, do NOT gate on `preSubmit`/`checkTokens`. `compileJava`
+ `test` + `checkGlyphs` are green at every commit on this branch.

## Needs the designer's in-client screenshot verification

- **Skill icon grid**: 8-wide `GridLayout` at 242px — icon size (getSkillImage
  small), the 23-skill wrap (last row of 7), the selected highlight (green bg +
  border), fit at font scales 1.0 / 1.3.
- **Repeatable disclosure growth**: tapping "More options" then Repeatable grows
  the form; `remeasureDock()` calls `revalidate()` up through the panel — CONFIRM
  the dock actually re-lays-out (grows/shrinks) and does not clip, and that the
  goal list gives the height back on collapse. This is the highest-risk visual.
- **Period pills** selected-state colors, and the per-period amount field glam.
- **Item / Combat search rows**: row height (26-28px), icon alignment, selected
  highlight, the "No matches (loading?)" empty state for CA before wiki load.
- **Combo widths** (Account/Quest/Diary/Boss) at 242px — long names (quests,
  "Collection Log Slots", "Kourend & Kebos") may clip; may want name-truncation
  or a searchable combo later.
- The create surface height cap (300px) + per-form scroll feel.
- The expand/collapse handoff: peek → grid → tile → form → Back → grid →
  collapse, no list jitter.

## Deferred glam / polish (left for the screenshot loop)

- Exact colors/spacing across all forms; the level/xp field glam; final knob
  styling on the peek bar and Add button.
- Type-tile color swatch icon (still a colored top rule, not an icon).
- Item form: no repeatable disclosure (item repeat needs an activity picker via
  `ItemActivityResolver` — a richer flow; boss/skill cover the repeat ask).
- Diary "Lumbridge & Draynor" normalizes to `LUMBRIDGE_&_DRAYNOR`, which has no
  tracking varbit, so that one lands as a manual goal — same as the in-game
  journal menu path today (pre-existing, not introduced here). If tracking is
  wanted, extend `normalizeAreaKey` (mirror its Kourend/Western special-cases).
- Boss/Account create do not seed prerequisite varps or run the dialog's
  compound seeding; they are the minimal add. Parity pass can enrich later.
- Post-create the dock jumps to the new goal's action strip (via
  `selectAfterCreate`) rather than staying on the grid — confirm this is the
  desired feel or switch to "clear selection, stay on grid" for rapid multi-add.

## Guardrails honored

- Right-click menus (`GoalContextMenuBuilder`) + `GoalDialogFactory` untouched —
  dock built alongside; deletion is the separate later step.
- All dock content assembly is in `GoalPanel.refreshDock` + private helpers.
- `DockContext` unchanged (pure, still unit-tested); create-nav lives in
  `dockCreateType` on `GoalPanel`.
- UI strings ASCII (checkGlyphs green). `compileJava` + `test` green at every
  commit. `checkTokens` intentionally red (see above).
