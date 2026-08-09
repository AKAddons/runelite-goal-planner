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
  Diary, Combat, Boss, Item, Account, Custom) plus a "New Section" footer that
  preserves the skeleton's one panel action. Each tile carries a colored top
  rule for type identity and navigates the dock (no dialog, list never moves).
- A **Back** control on every form returns to the grid; a successful create
  also returns to the grid (`navigateCreate(null)`), and since creating does
  not select the new goal, the dock stays in EMPTY/grid so the user can add
  another.

### 3. Skill create form (`buildSkillForm`) — the one wired form
Skill picker (all skills, name-rendered) + the shared `SkillTargetForm`
(synced Level/XP) + **Add goal**. On Add it validates the XP (1..200M) and
calls `api.addSkillGoal(skill, xp)`. This is the priority-2 deliverable in its
**core** form.

## What is NOT built (and exactly why)

The plugin sits at ~99% of the Plugin Hub token cap. Baseline main source was
192,135 tokens; the submit gate (`checkTokens`) fails at 195,000 (a deliberate
5k safety buffer under the bot's hard 200,000). That left only ~2,865 tokens
for this **entire** feature. The create shell (dock plumbing + grid + tiles +
scaffold + form helpers + Skill form) already consumes essentially all of it —
the branch currently sits at ~194,850, ~150 tokens under the gate.

The headroom to build the rest arrives when `GoalContextMenuBuilder` (~2,072
lines / ~14,700 tokens) is deleted — but ADR-0007 says the menus are removed
**last**, only after in-client parity is verified, which could not happen with
the designer AFK. So the following were designed and (mostly) written, then
**cut to stay under the gate**, in rough priority order to restore:

1. **Skill "Repeatable" progressive-disclosure flow** — the "More options" row
   → Repeatable toggle → Daily/Weekly/Monthly period pills → per-period XP →
   Section auto-locks to "Repeatable". Was fully written (incl. a
   `buildPeriodPills` helper and a `createDerivedRepeatGoal` submit path) and
   removed. **This is the single most important thing to restore** — it is the
   "most parameters" reason Skill was built first.
2. **Item form** — search (`itemManager.search`) + results list + quantity →
   `api.addItemGoal`. Fully written and removed (ADR calls this the "richest
   navigation"; it was the priority-3 deliverable).
3. **Custom form** — name + description → `api.addCustomGoal`. Written, removed.
4. **Quest / Boss / Diary / Account forms** — all written against existing API
   (`addQuestGoal`, `addBossGoal`, `addDiaryGoal`, `addAccountGoal`) and
   removed. Diary area display names and the leagues-metric filter were sorted
   out and are in git history on this branch's earlier working tree if needed.
5. **Combat (CA) form** — was a stopgap numeric task-id entry because
   `WikiCaRepository` is not reachable from `GoalPanel` (package-private on the
   API impl, no getter). A real CA-name search needs an accessor first.
6. **Type-tile color swatch icon** — a `ShapeIcons.filledSquare` was added then
   reverted; tiles use a colored top rule instead. Restore the icon for a
   richer grid when there is room.

Every un-built tile routes to a small **placeholder** ("The X form is coming to
the dock. For now, use right-click Add on a section header."), so the grid is
fully navigable and nothing dead-ends. The right-click menus remain the
complete, working creation surface (guardrail: menus stay until parity).

## Needs the designer's in-client screenshot verification

- The grid: 2x4 `GridLayout` at 242px sidebar width — tile text fit, the
  colored top rules, wrapping/height at font scales 1.0 and 1.3.
- The create surface height: does it feel right capped at 300px, and does the
  goal list keep enough room when a form is expanded?
- Skill form spacing (label-above-field rows, combo width, the Back/Add row).
- The expand/collapse handoff: tap the green "Add a goal" peek → grid → tile →
  form → Back → grid → collapse. Confirm no list jitter and the dock hands its
  height back on collapse.
- Placeholder tiles read as "coming soon", not broken.

## Guardrails honored

- Right-click menus (`GoalContextMenuBuilder`) untouched — dock built alongside.
- All dock content assembly is in `GoalPanel.refreshDock` + private helpers.
- `DockContext` unchanged (pure, still unit-tested).
- UI strings ASCII (checkGlyphs green). `preSubmit` EXIT=0 at every commit.
