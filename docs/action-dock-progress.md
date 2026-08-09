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
