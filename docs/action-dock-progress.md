# Action dock — create-surface progress (ADR-0008)

Status as of the `feat/action-dock` build of the contextual create surface.
Everything here is **render-path** work that has NOT been verified in-client
(no client available while the designer was AFK). Treat every layout/spacing
choice as provisional until screenshot-verified.

## Refinement pass (notes 2-6) — create/edit dock

Five user refinements to the just-built create/edit dock. All assembly still
lives in `GoalPanel.refreshDock` + its private `build*` helpers (single-place
rule); `DockContext` untouched; `GoalContextMenuBuilder` + `GoalDialogFactory`
still fully intact (deleted later). Commits, newest last:

- `0f23ab7` notes 4 + 6
- `049085b` note 2 (+ section-pick view scaffolding)
- `0269bf5` note 3
- `0e0a624` note 5

### New nav / state fields (all on `GoalPanel`)
- **`enum CreateNav { GRID, FORM, SECTION_NEW, SECTION_PICK }`** +
  `CreateNav dockCreateNav` — replaces the old `dockCreateType==null?grid:form`
  branch. `buildCreateSurface()` routes on it; `dockCreateType` still names the
  FORM's type. Mount guard now tracks `(dockCreateMountedNav, dockCreateMountedType)`.
  Helpers: `mountCreateSurface()`, `navigateCreateNav(CreateNav)`; `navigateCreate(type)`
  sets nav = `type==null?GRID:FORM`.
- **`Consumer<String> dockPendingCreate`** (note 3) — a validated goal-create
  awaiting its landing section; run once by `chooseSection(sectionId)`.
- **`CreateSeed dockCreateSeed`** (note 5) — `{ Skill skill; String bossName;
  boolean repeatable; Integer targetXp; Integer targetCount; }`, prefill for a
  freshly-opened create form; consumed once when the matching form builds.
- **`enum EditGroup { DATA, RELATIONS, ACTIONS }`** + `EditGroup dockEditGroup`
  (note 6) — which edit-chip drill-in group is open (null = top level); reset to
  null when a different goal mounts.

### Note 2 — Create Section opens an in-dock form
`dockCreateSection`'s JOptionPane is gone. `ActionDock`'s Create Section header
button now EXPANDS the dock (mirrors Create Goal's toggle) and runs its callback,
which sets `dockCreateNav=SECTION_NEW` and mounts `buildSectionNewForm()`: an
autofocus "New section name" field + a primary "Create section" button, in the
same `surfaceShell("Create", true, ...)` as the goal forms. Enter or the button
commits `api.createSection(name.trim())` (blank ignored) then returns to the
type grid. Like Create Goal, tapping the header button while already expanded
just collapses (toggle parity) — from the grid there is no header jump into the
section form; that is the accepted mirror of Create Goal.

### Note 3 — pick a landing section as a separate step
Every goal form's primary button is now **"Next: choose section"**. Its `onAdd`
validates, builds a pending `Consumer<String sectionId>`, and calls
`goToSectionPick(consumer)` (nav -> SECTION_PICK) instead of creating. The
consumer does the create then `api.moveGoalToSection(newId, sectionId)`. For a
repeatable skill/boss goal the PARENT lands in the chosen section (inside the
client-thread compound) and the derived slice still auto-lands in Repeatable.
`buildSectionPickForm()` lists the default **Incomplete** section first
(highlighted green as the preselected choice), then every user section, then a
"+ New section" reveal (inline name field, creates-and-selects in one go via
`chooseSection`). Picking runs the pending consumer + returns to the grid.
"< Back" abandons the pending create and returns to the type grid. Applies to
all eight forms. **Known limitation:** Back does not preserve form field values
(forward flow is the norm) — flagged in the chip tooltip and here.

### Note 4 — absolute goals show a disabled completion box
`editFormScaffold`: `absolute = QUEST || DIARY || COMBAT_ACHIEVEMENT` now ALWAYS
renders the Complete/Reopen checkbox but **disabled + greyed** (`setEnabled(false)`,
`setSelected(g.isComplete())`, no listener) with tooltip "Tracked by game
progress - can't be set manually." Replaces the old show-Reopen-only-when-complete
behavior. SKILL/BOSS/ACCOUNT and CUSTOM/ITEM keep their existing `complete||manual`
enabled checkbox unchanged.

### Note 5 — Make repeatable hands off to the create flow
The edit "Make repeatable" chip no longer prompts. SKILL -> `makeRepeatableFromSkill(g)`
sets `dockCreateSeed{skill, repeatable=true, targetXp=g.getTargetValue()}`,
`api.clearGoalSelection()`, then `navigateCreate(SKILL)`. BOSS ->
`makeRepeatableFromBoss(g)` (analogous, `targetCount`). `buildSkillForm` /
`buildBossForm` consume the seed: preselect the skill icon / boss combo, prefill
the target, and open the repeat disclosure pre-checked (new
`addRepeatDisclosure(body, label, startOpen)` overload). The item-source path
(`dockDeriveItemRepeat`, item -> boss-kill slice) has no single obvious create
form, so it KEEPS its existing chooser (flagged). Seeding creates a fresh parent
+ slice through the normal create flow, as intended. Sequencing note: the
selection model clears synchronously (the `onSelectionChanged` callback is
`invokeLater`), so `navigateCreate` sees EMPTY state and the seed survives; the
later async `refreshSelection` refresh hits the mount guard and no-ops.

### Note 6 — edit chips tree into drill-in groups
`buildEditChips` restructured from ~15 flat chips into a drill-in tree. Top level
(`buildEditChipsTop`): `[Make repeatable?] [Data] [Relations] [Actions] [Deselect]`.
Tapping a group swaps the row for that group's member chips + a "< Back" (via
`dockEditGroup` + `refreshEditForm`). Membership (every prior gate/handler
preserved verbatim):
- **Data** (`buildDataChips`): Optional/Required (when !complete), Color, Add tag,
  Drop tags (when present), Restore defaults (when overridden).
- **Relations** (`buildRelationsChips`): Requires, Required by, Drop reqs, Drop
  dependents (all when !complete + edges), Add reqs to section (when seedable).
- **Actions** (`buildActionsChips`): Move to section, Copy to section, Loadout Lab
  (boss + install-aware), Copy code / Save code (when available), Remove.
The completion checkbox stays at the form top (not in a group). A same-goal
`refreshEditForm` keeps the open group; a different goal resets it to top level.

## Refinement pass 2 — stepped tall forms + skill One-time/Repeatable toggle

Three user refinements to the just-built create flow. Still one assembly point
(`GoalPanel.refreshDock` -> `build*`); `DockContext`, `GoalContextMenuBuilder`,
`GoalDialogFactory` untouched. Commit `4f58b12`.

### New step-nav / pick state (all on `GoalPanel`)
- **`enum CreateStep { PICKER, DETAILS }`** + **`CreateStep dockCreateStep`**
  (default PICKER) — the sub-step inside a *tall* type's FORM. Tall types =
  SKILL / BOSS / ITEM_GRIND (`isTallType`); every other type renders DETAILS
  directly (no picker). `buildCreateForm` routes a tall type on `dockCreateStep`.
- **Pick holders** `dockPickedSkill` / `dockPickedBoss` / `dockPickedItemId` +
  `dockPickedItemName` — what the PICKER stashed, read by the DETAILS builder.
  `resetCreatePicks()` clears them whenever the flow leaves FORM or changes type
  (called from `navigateCreate` and the selection-exists reset in `refreshDock`).
- **Mount guard** gains `dockCreateMountedStep`: a half-filled DETAILS screen now
  survives an unrelated dock refresh (was `(nav, type)`, now `(nav, type, step)`).
- **`navigateCreateStep(CreateStep)`** — advance/return between PICKER and DETAILS
  (the guard sees the step change and remounts). Picking auto-advances (PICKER ->
  DETAILS); a tall DETAILS "Back" returns to PICKER; a picker/single-step "Back"
  returns to the type GRID. `createFormScaffold` gained an `onBack` overload for
  this (default = grid).

### Stepper shape (Task 1)
- **SKILL PICKER** (`buildSkillPicker`): only the skill icon grid, filling the
  dock. `buildSkillPickerGrid` gained an `onPick` callback; tapping a skill sets
  `dockPickedSkill` and auto-advances.
- **BOSS PICKER** (`buildBossPicker`): converted from a combo to a **search +
  results** list (matches the tap-to-advance model; a combo can't cleanly
  auto-advance). Filters `BossKillData.getBossNames()` by substring, caps at 12,
  tap auto-advances. Empty query shows the first 12 as an initial scannable view.
- **ITEM PICKER** (`buildItemPicker`): the existing `itemManager.search` results,
  now via the new `tappableRow` (icon + name, hover cue, no persistent highlight
  since it auto-advances). `buildItemResultRow` removed (dead after this).
- **DETAILS** screens (`buildSkillDetails` / `buildBossDetails` /
  `buildItemDetails`): a read-only `pickedHeader` (icon + name of the pick) + the
  target/options inputs + the unchanged "Next: choose section". `tappableRow` and
  `pickedHeader` are the two new shared helpers.
- **Make-repeatable seed** (note 5) still lands: `navigateCreate` detects a
  SKILL/BOSS seed, preselects the pick, and sets step = DETAILS so it jumps
  straight past the picker; the DETAILS builder consumes the seed's target/repeat.

### Skill segmented toggle (Task 2)
SKILL DETAILS **replaces** `addRepeatDisclosure` (the "More options ->
Repeatable checkbox -> stacked inputs" model) with a horizontal 2-segment
**[ One-time | Repeatable ]** toggle at the top (`buildModeToggle`, reusing the
period-pill visual style; One-time active by default). It is the **single source
of truth** for which create path runs and swaps which input set shows — never
both stacked:
- **One-time**: the `SkillTargetForm` (target level/XP) only ->
  `api.addSkillGoal(skill, xp)`.
- **Repeatable**: Daily/Weekly/Monthly pills + an "XP each period" amount, **no
  target** -> the repeatable path (Task 3).
This also removes the "More options" link for skill (the earlier
More-options-and-Next-on-one-line ask is now moot — they don't coexist). **BOSS
keeps `addRepeatDisclosure` this pass** — flagged: boss can get the same toggle
once its repeatable-no-target semantics are confirmed.

### Repeatable-only create path (Task 3) — DECISION
`createDerivedRepeatGoal` needs a parent, and a grep of `GoalPlannerApi` /
`GoalPlannerApiImpl` found **no parent-less repeatable-goal create method** (only
`createDerivedRepeatGoal(parentGoalId, ...)`; `setGoalRepeat` is CUSTOM-only). So
the repeatable-only skill path creates the parent with an **endless target — the
XP hardcap `XP_HARDCAP = 200_000_000`** so it never completes — then derives the
per-period slice off it, as one `beginCompound/endCompound` on the client thread
(the derive reads live XP). **No new API surface added this pass.** A true
parent-less standalone repeatable goal is a desirable follow-up (cleaner model,
no vestigial 200M parent).

### Peek bar full-width (Task 4) — VERIFIED, no change
The neutral "N selected" `peekBar` is already structurally full-width: an opaque
`contentAreaFilled` `JButton` in `peekHost` `BorderLayout.CENTER`, and `peekHost`
sits in the dock's `BorderLayout.NORTH` (always full container width). Its
`Dimension(0, PEEK_H)` width is ignored by `BorderLayout.CENTER`, which stretches
it edge-to-edge — identical to the two create buttons (same host). No border /
alignment / preferred-size quirk narrows it, so no code change was invented.
**Observation for the screenshot loop:** the neutral bg `0x222224` (34,34,36)
barely contrasts with the dock's `DARKER_GRAY_COLOR` `0x1E1E1E` (30,30,30), so
the full-width bar can *read* as not-full-width because it blends into the dock —
unlike the green/blue create buttons. If the human wants it to read as clearly as
the create buttons, a contrast bump on `PEEK_NEUTRAL_BG` is the lever (color is
their screenshot-loop domain), not a width fix.

## NEEDS-SCREENSHOT (refinement pass 2) — in-client loop

- **Tap-to-advance feel** on all three tall pickers (skill icon grid / boss
  search+results / item search+results): tapping an entry should jump straight to
  DETAILS with no flicker, and the auto-advance should feel immediate not laggy.
- **Back navigation** across the new depth: picker <-> details <-> type grid.
  Confirm a tall DETAILS "Back" returns to the PICKER (not the grid), a picker
  "Back" returns to the grid, and the dock grows/shrinks per screen without clip.
  Known limitation carried over: Back does not preserve a half-filled DETAILS
  field (forward flow is the norm).
- **Skill segmented One-time/Repeatable swap**: the toggle reads as a segmented
  either/or (period-pill style), and switching shows **only one** input set —
  One-time = target field; Repeatable = pills + "XP each period" + the
  "Lands in the Repeatable section" note, with the target field gone.
- **Repeatable-only create landing**: creating a Repeatable skill goal lands the
  per-period slice in Repeatable, and the endless 200M parent behaves sanely (it
  should never show as complete). Confirm the parent's presence/appearance is
  acceptable pending the parent-less follow-up.
- **Make-repeatable seed** now jumps to SKILL/BOSS DETAILS with the pick
  preselected and (skill) the Repeatable segment active + target prefilled — no
  flash of the picker step.
- **Boss picker as search+results** (was a combo): the boss list is scannable,
  the initial first-12 view is useful, and typing narrows correctly.
- **Item picker icons** via `new ImageIcon(itemManager.getImage(id))` (static
  snapshot vs the old async `addTo`): confirm icons actually appear once the
  image loads (a results revalidate should trigger the repaint).
- **Full-width neutral peek** contrast (Task 4 above): eyeball whether the
  "N selected" bar reads edge-to-edge or wants a bg contrast bump.

## NEEDS-SCREENSHOT (refinement pass 1) — heavy render path

- **Section-pick step feel + default selection** (note 3): does the highlighted
  "Incomplete (default)" row read as preselected? Row height/spacing, the list
  scrolling inside the dock when there are many user sections, and that picking
  lands the goal in the right section + jumps to its edit form.
- **Inline "+ New section" field** in the section picker (note 3) and the
  standalone new-section form (note 2): autofocus actually lands, Enter commits,
  the reveal grows the dock without clipping.
- **Create Section expand** (note 2): the header button expands the dock into the
  section form (not a prompt); confirm the toggle-collapse-when-open behavior is
  acceptable or wants a grid-level entry.
- **Drill-in group nav + Back** (note 6): tapping Data/Relations/Actions swaps the
  chip row cleanly, "< Back" returns, the dock regrows/shrinks per group size, and
  a same-goal action (e.g. Optional) stays in-group while a reselect resets to top.
- **Disabled absolute checkbox** (note 4): greyed + unclickable on QUEST/DIARY/CA,
  correct label (Complete vs Completed) and tooltip, at font scales 1.0 / 1.3.
- **Make-repeatable pre-seed landing** (note 5): the SKILL/BOSS chip lands on the
  right create form with skill/boss preselected, target prefilled, repeat
  disclosure already open + checked; the per-period amount is intentionally blank
  (validates on Next). Confirm the selection-clear -> create-surface transition
  has no flicker back to the edit form.
- **"Next: choose section" button** wording/width at both font scales.
- Known limitation to eyeball: **Back from section-pick loses form field values.**

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

## Glam + fix pass — absolute-check, quest filters, dock divider

Three independent tasks on `feat/action-dock`, committed separately, each
green on `compileJava` + `test` + `checkGlyphs`. Assembly rules unchanged:
`GoalContextMenuBuilder` + `GoalDialogFactory` intact, `DockContext` untouched,
UI strings ASCII.

### Task A — absolute-goal edit checkbox shows the accurate state
`editFormScaffold` (GoalPanel) rendered a QUEST/DIARY/COMBAT_ACHIEVEMENT
completion `JCheckBox` with `setEnabled(false)`. In RuneLite's dark LAF a
disabled checkbox does not paint its tick, so a COMPLETED absolute goal read as
unchecked even though its card showed the green completion marker. Fix: supply
explicit painted disabled icons that render regardless of enabled state -
`setDisabledSelectedIcon(ShapeIcons.checkboxChecked(14, green))` +
`setDisabledIcon(ShapeIcons.checkboxEmpty(14, muted))`. State still reads off
the same `g.isComplete()` source as the card; label stays Completed/Complete;
tooltip and greyed foreground keep it reading as non-interactive. New color
constants `ABSOLUTE_CHECK_DONE` / `ABSOLUTE_CHECK_EMPTY` in GoalPanel.

### Task B — Quest create: two filter toggles
`buildQuestForm()` gained two live filter toggles above the quest combo. Data
sources found and used:
- **World / membership**: `client.getWorldType()` (the `net.runelite.api.Client`
  the panel already holds via `setClient`) tested for `WorldType.MEMBERS`. The
  "F2P only" toggle defaults CHECKED when the world is definitively free-to-play
  (`worldType != null && !contains(MEMBERS)`), UNCHECKED on a members or unknown
  (logged-out) world. The toggle stays usable either way.
- **Quest completion**: `Quest.getState(client) == QuestState.FINISHED`, the same
  live read `GoalCreationService.isQuestFinished` and `QuestTracker` use. This
  reads varps, so it is done on the client thread via the panel's
  `runOnClientThread(...)` executor, then the finished set + f2p-world flag are
  pushed back to the EDT with `SwingUtilities.invokeLater` to set the F2P default
  and refilter. "Incomplete only" is CHECKED by default and hides FINISHED quests.
- **F2P data**: `com.goalplanner.data.QuestRequirements.isF2P(Quest)`, backed by
  the `F` flag in column 11 of `quest-requirements.tsv`. 23 quests are flagged
  f2p, matching the current OSRS free-to-play quest count. NO f2p data gap - the
  toggle filters against real data. (Caveat: a quest with no row in
  quest-requirements.tsv is treated as non-f2p and thus hidden while "F2P only"
  is on, which is correct - all 23 f2p quests have rows.)

Toggling either box rebuilds the combo model in place (`DefaultComboBoxModel`)
and preserves the current selection when it survives the filter. The existing
Next-choose-section flow (`goToSectionPick`) is unchanged. Helper
`createFilterToggle(text, selected)` styles the two checkboxes to the create tone.

### Task C — artistic divider between the dock and the goal list
`ActionDock`'s plain `createMatteBorder(1,0,0,0, DARK_GRAY_HOVER_COLOR)` top
border is replaced by a custom `DockDivider implements Border` (nested in
ActionDock). It paints an engraved two-tone rule: a dark groove hairline with a
lighter highlight beneath it, both tapering to transparent at the panel edges
via `GradientPaint`, plus a short brighter center glow so the seam reads as a
deliberate flourish. 6px tall, all tones derived from `ColorScheme`
(DARKER_GRAY / DARK_GRAY_HOVER / LIGHT_GRAY), no assets, ASCII only.

### NEEDS-SCREENSHOT (this pass)
- **Task A**: a COMPLETED quest/diary/CA goal in the edit surface now shows a
  visible green tick; an INCOMPLETE one shows a visible empty box. Verify both,
  and that it still reads as locked/greyed (not clickable).
- **Task B**: quest create form on a MEMBERS world (F2P unchecked by default) and
  on a F2P world (F2P checked by default); toggling "Incomplete only" and
  "F2P only" re-filters the combo live; long quest names still fit the 242px
  combo; brief flip of the F2P default as the async client read lands.
- **Task C**: the new dock divider at rest and with the create surface expanded;
  confirm the center glow + edge taper read as intended and the height is tasteful.

## Standalone repeatable create + complete-on-add surfacing

Two decisions, one pass. Assembly still lives in `GoalPanel.refreshDock` + its
`build*` helpers; new API surface is in `GoalCreationService` (exposed via
`GoalPlannerApi`/`Impl`).

### Task 1 — a fresh Repeatable goal is STANDALONE (no endless parent, no choose-section)
The old fresh-create Repeatable path built an "endless" long-term PARENT skill
goal at 200M XP (`XP_HARDCAP`), moved it to a chosen section, then derived a
per-period slice off it (`createDerivedRepeatGoal`). That stranded a visible
endless parent in a normal section — the split the user rejected. `XP_HARDCAP`
is gone (was the only use), and `goToSectionPick` is skipped on this path.

- **`GoalCreationService.buildSkillChunk` / `buildActivityChunk`** refactored to
  take RAW identity (`String skillName` / `String activityName`) plus a
  `derivedFromId` instead of a `Goal parent`, so the same period-target math
  (`live + chunk`, via `RelativeTargetResolver`) serves both a derived slice
  (`derivedFromId = parent.getId()`) and a standalone (`derivedFromId = null`).
- **New shared tail `commitRepeatChunk(Goal, RepeatPeriod)`** — undoable add +
  `reconcileDerivedSections()` (which lands any repeating goal in Repeatable) +
  log. `createDerivedRepeatGoal` now routes through it too (single assembly).
- **New API `createStandaloneRepeatSkillGoal(Skill, RepeatPeriod, int chunk)`**
  and **`createStandaloneRepeatActivityGoal(String bossName, RepeatPeriod, int
  chunk)`** — parentless, self-contained repeating goals. `RepeatResetService`
  re-bases each period without reading a parent (verified), so a standalone
  slice is a complete goal. Exposed on `GoalPlannerApi`; `Impl` wraps each with
  `selectAfterCreate(id)`.
- **UI**: the SKILL create form's Repeatable branch and the BOSS form's
  repeat-disclosure branch now call the standalone creator inside a client-thread
  compound (live XP/KC read) and `navigateCreate(null)` straight back to the
  grid — NO `goToSectionPick`. The one-time branches are unchanged (still pick a
  section). The **"Make repeatable" CHIP on an EXISTING goal is untouched** — it
  legitimately derives off a real long-term parent (`createDerivedRepeatGoal`).
- **BOSS handled** (not deferred): the boss repeat path had the identical endless
  200M-KC-parent hack and got the same standalone treatment.
- **Tests** (`CreateDerivedRepeatGoalTest`): new `StandaloneSkill` +
  `StandaloneBoss` nested classes — happy path (repeating, `live+chunk` target,
  `derivedFromGoalId == null`, lands in Repeatable), the "exactly one goal
  created, no endless parent" assertion, and edge cases (null skill/boss,
  chunk <= 0, null/NONE period, no client).

### NEEDS-SCREENSHOT (this pass)
- Skill create -> Repeatable toggle -> pick period + XP each period -> Create:
  the new goal appears in the built-in **Repeatable** section, there is **NO
  choose-section step**, and there is **NO endless 200M parent** stranded in a
  normal section.
- Boss create -> open the repeat disclosure -> Create: same — lands in Repeatable
  directly, no section prompt, no endless parent.
