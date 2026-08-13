# Changelog

All notable changes to the OSRS Goal Planner plugin will be documented
here. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versioning is [semver](https://semver.org/) with the caveat that the
0.x series is experimental and may include breaking changes on minor
bumps.

## [Unreleased]

### Fixed
- **A Miscellania favour goal now un-completes when your favour decays** — the
  two account metrics that can actually lose value (Kingdom approval and
  spendable slayer points) are judged purely on the live game value, so the
  goal re-opens the moment you drop below the target and completes again when
  you're back over it. Everything else stays sticky: levels, quest points and
  diary tiers can't be un-earned.
- **Progress percentages no longer round up to a misleading "100%"** — a goal
  at 99.6% now reads *99%*. Only a finished goal shows as finished.

## [0.5.0] — 2026-08-08

### Added
- **Repeatable goals.** Right-click a custom goal → **Repeat** → *Daily /
  Weekly / Monthly*. It moves into a new **Repeatable** section pinned to the
  top of the panel, stays there when you check it off, and un-checks itself
  when the period rolls over — so a long grind becomes a rotation instead of
  one unbroken sitting. The section only appears once you have a repeatable
  goal, and rollover happens even while you're logged out.
- **Repeatable goals from skills and items.** Right-click a long-term goal → **Repeatable goal** →
  period → size, and get a repeating slice of it. A skill goal offers XP chunks
  ("Woodcutting +300,000 XP" a day); an item goal works backwards from the drop
  and offers kill counts at whatever activity drops it ("General Graardor x20").
  The chunk measures from wherever you are now, so a skipped day doesn't pile up
  — and your original goal is left untouched. Items that come from shops or
  skilling don't offer it, and minigame drops don't yet.
- **Live "resets in" countdown.** Every repeatable card shows how long is left
  in the current period — *Daily - resets in 3h 42m* — ticking once a minute.
- **Repeat reset config.** Choose where the day boundary falls: the in-game
  daily reset (00:00 UTC, the default, so shared routines roll over at the same
  moment for everyone), local midnight, or a custom local hour.

### Added (multi-character)
- **Every character keeps its own plan.** Goals are now keyed by profile
  **and** character — the same split that already kept leagues separate from
  main, one level down. Two accounts sharing a RuneLite profile no longer
  share (or endanger) each other's goals; a world hop to Leagues or **Deadman**
  switches the profile axis while the character axis stays put. Your existing
  plan is adopted by the first character to log in after updating; other
  characters start fresh. Before login the panel shows the last character seen.

### Fixed
- **Re-importing a share code now warns first.** Pasting the same code twice
  silently duplicated every goal outside your Default plan. The plugin now
  remembers what each character has imported and asks before duplicating —
  confirming still allows it, for when two copies is what you want.
- **Goals no longer complete themselves against the wrong account.** If you play
  more than one account, a goal set could be scored against whichever account
  happened to be logged in — silently completing everything that account had
  already done, permanently, and syncing that to your other machines. Goals were
  stored per RuneLite profile, but nothing checked the profile matched the
  account, so separate profiles didn't prevent it. A goal set is now bound to
  the account that first tracks it, and tracking pauses with a chat warning if a
  different account logs in. Existing plans bind themselves on next login — no
  action needed.
- **Any completed goal can now be reopened.** Right-click → **Mark Incomplete**
  works on skill, boss, quest, diary and CA goals, not just custom ones, and
  bulk selections can be reset in one undoable step. Clearing a completion lets
  tracking re-derive the truth: a goal you really did finish comes straight
  back, a wrongly-completed one stays open. This is the recovery path for plans
  corrupted by the bug above — no need to delete and rebuild.

### Changed
- Completed repeatable goals deliberately do **not** graduate to the Completed
  list — they stay in Repeatable so it reads as a checklist that fills up
  during the day and empties at reset.

### Notes
- Making an *existing* goal repeat in place is still custom-only: auto-tracked
  types read an absolute lifetime counter. Deriving a repeatable goal from one
  covers them instead, by re-basing its target each period rather than storing
  a baseline.
- Not yet done: grouping the Repeatable section by deadline (daily/weekly/
  monthly sub-headers), and minigame drops (their counters are varbits the
  plugin does not read yet).
- Architecture decisions for this feature:
  [ADR-0003](docs/decisions/0003-repeatable-goals-live-in-a-derived-built-in-section.md),
  [ADR-0004](docs/decisions/0004-clock-driven-reset-via-period-keys.md),
  [ADR-0005](docs/decisions/0005-derived-goals-re-base-their-target-instead-of-storing-a-baseline.md).
### Added (Fallen From Grace — Wyrmscraig, 2026-07-29)
- **Fallen From Grace quest goal.** Requirement row seeded with its skills
  (Sailing 62, Crafting 60, Runecraft 47, Mining 53), the Pandemonium
  prerequisite, and the recommended Prayer 43 / combat 75.
- **Mad Angel boss.** Kill-count tracking (varp 5712), the Aggy pet on the card,
  and Fallen From Grace seeded as its prerequisite.
- **Mad Angel collection-log tags.** Hallowfell, Jar of light, Aggy, and the
  Ardeaglais teleport now auto-tag item goals to the boss.
- **Quest-point ceiling 339 → 341** (Fallen From Grace grants 2), so Quest Cape
  and max-QP goals target the right number. Tears of Guthix PB tracks it.

## [0.4.2] — 2026-07-11

RuneLite shipped **The Blood Moon Rises** in the `1.12.32` client release, so
the quest goal that was staged dormant in 0.4.1 is now live — and boss cards
now link out to Loadout Lab.

### Added
- **The Blood Moon Rises quest goal — activated.** With the quest now in the
  RuneLite `Quest` enum, its requirement row lights up: you can add a quest goal
  for it and it auto-tracks completion. No action needed from 0.4.1 users beyond
  updating.
- **Maggot King requires The Blood Moon Rises.** Adding a Maggot King Boss Kill
  Count goal now seeds the quest as a prerequisite, so the goal tree reflects
  that you must finish the quest to reach the boss.
- **"Search in Loadout Lab" on boss cards.** Right-click any Boss Kill Count
  goal to open that boss in Loadout Lab (our gear optimizer plugin): it computes
  the best set you actually own for the fight. The menu entry is install-aware —
  with Loadout Lab enabled it searches directly; installed but disabled, it
  says so; and if you don't have Loadout Lab, nothing appears — your menu
  stays clean.

## [0.4.1] — 2026-07-02

The Blood Moon Rises content update — everything the game update added,
except the quest goal itself (staged; see below).

### Added
- **Maggot King.** The quest's boss is fully trackable: a Boss Kill Count goal
  with live KC tracking and its pet (Maggot marquess) as the card icon — plus
  26 of its drops tagged with a *Maggot King* source, so an item goal for a
  Venator fang or a Tarnished ring auto-carries where it comes from.
- **Quest point total → 339.** The Blood Moon Rises awards 4 QP, so Quest Cape
  / max-QP account goals (and the Tears of Guthix window) now target 339.
- **The Blood Moon Rises quest goal — staged.** The quest's requirement data
  ships in this release but stays dormant: RuneLite hasn't yet cut a client
  release containing the quest, and the plugin now tolerates forward-declared
  quests instead of failing. The quest becomes addable automatically once the
  Plugin Hub builds against a RuneLite release that includes it — no plugin
  update needed.

## [0.4.0] — 2026-06-23

### Added
- **Saved Plans — a library of bookmarked share codes.** An optional step
  between export and import: bank a code to import later instead of adding its
  goals now. **Save share code…** sits beside *Copy share code* on every Share
  menu, and the import dialog offers **Save for later**. Manage the library from
  panel **⋯ → Saved plans…** — each entry shows a name you choose plus a decoded
  preview, with per-entry Import, Copy code, Edit (rename + change how each
  section is named on import), and Delete. The library is global (shared across
  all profiles, including leagues), since a share code carries goal definitions
  only.
- **Auto-track Miscellania.** When you raise your Kingdom of Miscellania approval
  (i.e. you're actively managing the kingdom), the plugin adds a *Misc. Approval
  100%* goal to your default section — whenever you don't already have one. Undo
  removes it, and deleting it isn't permanent: it returns next time approval
  rises while the goal is missing. A chat line notes it can be turned off under
  **Behaviour → Auto-track Miscellania** (on by default).
- **Per-profile goals.** Goals, sections and tags are now scoped to the active
  RuneLite config profile (the same way leagues are kept separate), and reload
  automatically when you switch profiles — each account/profile keeps its own
  plan instead of sharing one.
- **Collapsible nested view.** In the nested view, a goal with nested
  prerequisites gets a collapse chevron (and a right-click *Hide/Show nested
  prerequisites*); the collapsed state persists per goal. Import and export now
  also carry a section's nested/flat view preference.
- **Configurable panel font.** Choose a font family (Default / Sans-serif /
  Serif) and size scale (Small … Larger) under settings; the panel — card
  heights, icons and progress text — scales to match.
- **Section completion badge.** When every goal in a section is complete, the
  plugin's completion icon shows in the section header (left of the select-all
  checkbox).

### Changed
- **Right-click menu polish.** The panel's context menu now flips to grow
  *upward* when you click near the bottom of the screen, so it can't open
  off-screen. Boss goals get a **Change Amount** (edit the target kill count,
  like item quantities). Adding a goal from the **collection log** and the
  **combat-achievement log** now uses one consistent "Add Goal" submenu
  (Default + each section), matching the in-game quest menu. Collection-log
  items are added straight at quantity 1 (adjust afterward) with no prompt.
- **Tidier imports.** Imported sections keep their own name instead of being
  suffixed with "(from <player>)", and the import dialog is a multi-line field
  so long share codes paste without wrapping errors.
- **Performance & stability pass.** Coalesced the tracker suite to run at most
  once per game tick instead of on every varbit/stat event (many per tick) — the
  main source of in-game lag with a large plan. Debounced the search box so
  typing no longer rebuilds the whole panel per keystroke. Hardened
  `GoalStore`'s goal list against concurrent access from the client thread
  (trackers) and the EDT (panel/UI): `getGoals()` now returns a snapshot and all
  structural mutations + the order sort are locked, eliminating the intermittent
  `ConcurrentModificationException` / sort-corruption that could surface during
  long sessions. Cleaned up `shutDown()` to stop the rebuild timer and release
  the panel so a disable -> re-enable doesn't leak the card graph.
- **Source-size reduction for Plugin Hub headroom.** No user-facing behavior
  change — internal cleanup to stay well under the hub's 200k-token source
  cap. Externalized the remaining hardcoded data tables to resource files
  loaded at startup (quest XP/QP rewards, recommended skills/combat,
  display-name overrides, and miniquest/F2P/lamp flags → extra columns in
  `quest-requirements.tsv`; boss kill-count varps, pet icons and
  collection-log aliases → `boss-killcount.tsv` / `boss-pets.tsv` /
  `boss-cl-aliases.tsv`; diary completion varbits →
  `diary-completion-varbits.tsv`), and de-duplicated the repeated "add goal"
  command and "new section" menu boilerplate. Net: main source dropped from
  ~183k to ~167k tokens (~28k headroom after this release's feature, up from
  ~17k).

### Fixed
- **macOS Tahoe tofu glyphs.** Swing's font fallback on macOS Tahoe renders
  symbol/punctuation glyphs (bullets, arrows, the ellipsis, em/en dashes, the
  middle dot) as empty boxes. Swept every UI/chat string — menus, dialogs,
  tooltips, undo labels, the Saved Plans dialog, chat messages — to plain ASCII,
  including `\uXXXX`-escaped forms (e.g. the Options tooltip, card truncation).
  A new `checkGlyphs` gate (part of `preSubmit`) flags any that return.
- **Plugin Hub listing showed a commit hash and a stale version.** The hub
  listing reads `runelite-plugin.properties`, which had no `version=` key (so
  the version corner fell back to the commit sha) and a `description` that began
  with an inaccurate `[Experimental v0.2.0]` prefix. Added `version`, dropped
  the prefix.
- **Degradable/charged items didn't complete their goal.** An item goal now
  counts every charge/degrade variant of the item toward the target (blood moon
  tassets, Guthan's pieces, etc.), so owning a worn-down copy still completes it.
- **Item goals reset to 0 on logout.** Item counts are now cached across the
  logout/login boundary instead of momentarily snapping to 0.
- **Imported quest/boss/diary goals showed a plain colour dot.** They now carry
  the right icon on import — the quest sprite, the boss's pet item, the diary
  sprite — matching how the same goals look when added in-game.
- **Font rendering and large-font layout.** The panel font is resolved live so
  the Default family tracks the look-and-feel (fixes all-text-wrong on macOS
  Tahoe), a font change now reapplies without reloading the plugin (cards were
  being reused with the old font), and card heights, icons and the progress
  column scale with the font so larger sizes no longer clip. Nested cards use a
  compact one-line progress form so the right side stays readable, deep nesting
  uses a width-aware indent so cards keep a usable width, and the "Goal Planner"
  title no longer overlaps the toolbar buttons at large sizes.

## [0.3.1] — 2026-06-15

### Added
- **Blocked-by-prerequisites badge.** Quest, diary, and boss goals show a
  small ⚠ badge when they have a *direct* prerequisite you haven't met that
  isn't already in your plan — using the plugin's requirement data + your live
  account to surface blockers proactively. Hover lists what's in the way;
  click adds the missing requirements to the section. A requirement covered by
  a higher goal you already track (or already present) doesn't flag.
- **"Add requirements" for boss goals.** Right-click a boss goal (e.g.
  Abyssal Sire) → *Add requirements to this section* → *Incomplete only* /
  *All*, matching quests and diaries. Seeds the boss's full prerequisite
  tree — skills, quests, items, account metrics, boss-kill prereqs, unlock
  subtrees, and OR-group alternatives — reusing the same data that auto-seeds
  when you add a boss. "Incomplete only" skips requirements you already meet
  (read from live account state on the client thread), so met levels don't
  seed as completed goals. (Combat Achievements have no requirement data in
  the plugin, so they're not included.)

### Changed
- **Source-size reduction for the Plugin Hub review cap.** The four largest
  hardcoded data tables (collection-log item sources, and the quest / diary /
  boss requirement tables) moved out of compiled Java into resource files
  loaded at startup — TSV for the flat tables, JSON (via Gson) for the nested
  ones. No behaviour change (data round-trips through the same lookups; the
  JSON was generated by serializing the live tables); it drops the reviewed
  source from ~268k to ~180k tokens. Also better architecture — thousands of
  data rows don't belong in `.java`.

## [0.3.0] — 2026-06-15

Goal sharing release: share codes, cross-plugin transport, the nested
dependency "guide" view, per-section identity, and in-game Add Goal ▸
section menus.

### Added
- **Share codes.** Export a section, a goal selection, or every user
  section as a paste-anywhere code (`GPSHARE1:` single-section,
  `GPSHARE2:` multi-section — gzip+base64 over a versioned JSON wire).
  Codes carry goal *definitions* only (types, targets, tags, relations);
  progress always tracks against the recipient's own account. The invite
  line degrades gracefully for players without the plugin, and import
  tolerates surrounding chat/Discord text.
- **Import.** Paste a code to import as a new section (name + colour
  preserved) or loose goals; GPSHARE2 sections marked `targetDefault`
  land in the default plan with reuse-dedup, so re-importing never
  duplicates. Imported sections default to keep-inline (a checklist).
  One undo reverses a whole import.
- **Cross-plugin transport.** Other plugins can feed codes in via the
  `goalplanner:import-share` PluginMessage — no dependency on Goal
  Planner required.
- **Nested dependency "guide" view.** Sections can indent goals beneath
  the prerequisite they unlock (longest chain forms the spine, extra
  prereqs surfaced via tooltip). Global "Indent dependencies" default
  with a per-section tri-state override. Completed goals stay inline and
  sink to the bottom; chains render *through* them.
- **Add requirements ▸ section.** Seed a quest/diary goal's full
  requirement tree into its section — shared transitive prereqs are
  created once and reused; the "All" variant keeps already-met
  requirements as inline cards.
- **In-game Add Goal ▸ section menus.** Quest right-click menus offer
  Default or any user section in one gesture; diary rows get one
  top-level entry per tier (*Add Goal Easy/Medium/Hard/Elite ▸*) that
  opens straight into the section list — no nested tier hop. Diaries
  gained a bare add (no prereq seeding) to match quests.
- **Section header select-all.** A checkbox glyph on the right edge of
  every section header selects/unselects all goals in that section in
  one click (empty box = select all, ticked = unselect all), staying in
  sync with the context menu's Select All and shift-click ranges.
- **Quest-point ceiling → 335** (The Red Reef), with Tears of Guthix PB
  sharing it (tear collection is capped by quest points). Wiki-authoritative
  static, bumped per release — an earlier live quest-DB-table sum was tried
  but over-counted, so the static is the reliable source. Collection Log
  Slots still reads its live ceiling from the client.
- **Per-section identity.** Each section is its own namespace: dedup
  guards, skill auto-linking, Move/Duplicate-to-Section, and a "Remove
  duplicate goals" cleanup all operate per section, so the same goal can
  live once in each plan.
- **Auto-archive controls.** Global "auto-archive completed" default with
  a per-section override; archived goals remember their home section and
  flip back when it switches to keep-inline.
- **Account metrics.** Collection Log Slots (live slot ceiling from the
  client when synced) and Diary Tiers Completed (0–48) as ACCOUNT goals.

### Changed
- **Deleting a section now deletes its goals** — a section owns its
  goals. The confirm dialog says so, offers an opt-out checkbox that
  relocates them to Default (Incomplete/Completed) instead, and one undo
  restores the section, its goals, and all dependency edges. Completed
  goals previously archived out of the section are kept as history.
- Completed goals are first-class: they can be moved, duplicated, and
  customized, and may live inline in user sections (the old "guide mode"
  was removed in favor of completion-in-place + per-section overrides).
  **Change Amount** now works on completed SKILL/ITEM goals — the new
  target re-evaluates completion both ways (raising it past the recorded
  progress reopens the goal and trackers resume; lowering it to met
  progress completes it, archived/restored sections and undo included).
  **Add Goal** (Top/Bottom/Above/Below) is available from completed
  cards too — only the Completed built-in itself is excluded.
- Account goal targets above the metric's max are allowed — ceilings
  grow with game updates, so an over-max goal simply tracks until the
  game catches up. The Max button and in-game prompt still *suggest*
  the current (live) ceiling; they no longer enforce it.
- League Points / League Tasks are hidden from the Add Goal metric
  picker and the card context menu's Leagues shortcut on non-leagues
  profiles — on a main account those goals could never track.
- The Lumbridge & Draynor Elite seeded quest-point requirement follows
  the new 335 maximum.
- Section right-click menus render through the same ColumnMenu drill-down
  as goal menus.

### Removed
- The **Show Overlay** / **Max Overlay Goals** settings — they configured
  an in-game overlay that was never built, so they did nothing. (Found in
  a dead-code audit that also dropped a handful of unused internals.)

### Fixed
- The selection count went stale after undo: seeding requirements (or
  importing) selects the goals it creates, and undoing removed those goals
  from the plan but left their dead ids in the selection, so the header
  still read "N selected" for goals that no longer existed. The selection
  is now pruned to existing goals whenever the goal set changes (undo,
  redo, removal).
- "Add requirements → Incomplete only" seeded account requirements the
  player had already met — quest points / Kudos / combat gates, and the
  diary OR-unlocks like the Warriors' Guild entry (130 Att+Str OR 99 Attack
  OR 99 Strength), which seeded the whole OR-block and auto-completed even
  when a player already had 99 Attack. Account requirements (including each
  arm of an OR-unlock) are now pre-filtered against the player's live value
  via a shared `AccountMetric.currentValue`; an unlock whose alternative is
  already satisfied is skipped entirely. The "All" variant still shows the
  full tree.
- "Add requirements → Incomplete only" did nothing (while "All" worked):
  it resolved against live skill/quest state on the EDT, which asserts the
  client thread and silently threw. Both the Max button on the account-goal
  dialog and the requirement seeder now hop to the client thread for live
  reads; `AccountMetric.effectiveMaxTarget` also swallows an off-thread
  read defensively, degrading to the static ceiling instead of dying.
- Imported (and API-created) account goals now show their metric's icon —
  sprites resolve from the metric at query time instead of relying on a
  spriteId persisted at creation, so a shared Quest Points goal renders
  the same quest book a locally-created one does.
- Sharing a multi-select that spans sections preserves each goal's source
  section: the code exports one section entry per source (name + colour),
  with default-plan goals riding along as a default-target entry, instead
  of flattening everything into one combined import. A selection entirely
  within one user section keeps that section's identity; one entirely in
  the default plan stays a legacy loose-goals code.
- Dependency links between goals in *different* sections are carried by
  multi-section codes (a bundle-level cross-edge list alongside the
  section-scoped relation refs) and rewired on import; previously they
  were dropped silently.
- A completed goal manually moved into a user section stays there —
  reconcile no longer yanks it back to Completed on the next update.
- The nested view keeps correct indentation for chains running through
  completed goals instead of flattening the dependent to a root.
- Import chat messages reported "0 goal(s)" for multi-section codes;
  counts now sum across sections.
- Adding a diary goal with prerequisites returned the area name instead
  of the created goal's id (selection-after-add and API callers).
- Prerequisite seeding: all seed types land in the parent goal's section
  (not just quests); already-completed prerequisites are kept as inline
  cards by "Add requirements → All"; skill/account goals sync immediately
  on add instead of waiting for the next stat change.
- Imported sections apply their shared colour and respect the 40-char
  section-name cap.
- Bulk (multi-select) menus: "Move to Section" no longer disappears when
  the selection sits in the default list with no user sections, and both
  bulk Move/Duplicate to Section gained the "… to New Section…" entry the
  single-goal menu already had.

## [0.2.0] — 2026-06-02

### Added
- **Configurable panel font (family + size).** An Appearance config
  section adds a font-family picker (Default / Sans-serif / Serif) and a
  size scale (Small / Normal / Large / Larger) for the side panel, routed
  through a central `PanelFonts` helper and applied live. Addresses a
  plugin-hub user's "font is a little difficult to read" report. The
  controls are marked experimental in-config with a Discord link for
  usability feedback.

### Fixed
- **Stale skill levels when seeding goal prerequisites.** Adding a goal
  that seeds skill prereqs (e.g. Song of the Elves seeding Herblore /
  Farming) right after login could read the not-yet-synced skill cache,
  which reports every skill at its default level (1). Already-met skills
  then failed the skip check and seeded as spurious level-1 cards that
  only self-corrected once the game pushed stat updates. Such adds are
  now deferred through a new `SkillSyncGate` until the post-login stat
  burst has arrived and settled (with a wall-clock fallback so an add is
  never stranded), so prerequisites are filtered against accurate levels.
  Covers quest, achievement-diary, and boss seeding.

## [0.1.1] — 2026-05-28

First post-submission release. Right-click menus restructured into a
column-view drill-down, bulk-action surface expanded to mirror the
single-item shape, several persistence and refresh bugs fixed.

### Added
- **ColumnMenu** — column-view popup menu for bulk and single right-click,
  single-column drill-down with a back row. Keep-open flag for repeat-
  friendly actions. Clamped inside the RuneLite client window.
- **Bulk Customize > Relations** — bulk Requires… / Required by… /
  Remove Requirement / Remove Dependent against the multi-select.
- **Bulk Move** — Up / Down / Top / Bottom preserving relative order
  within the selection.
- **Bulk Mark Optional / Required** under Customize.
- **Move-to… pick mode** — click a section header or goal card as a
  drop target after a Move-to gesture; cancel with ESC.
- **Auto-select on add** — every `addXxxGoal` at the API layer leaves
  the newly created goal selected.
- **Auto-deselect rules** at the API layer covering pick-mode entry,
  arrow actions, and single-item right-click activations.
- **"Open in Quest Helper"** context menu item on achievement diary
  goals.
- **Goal customizations menu** grouped under a Customize submenu,
  Move actions promoted to a top-level sibling.
- **Tag submenu** that collapses Add Tag + Remove Tag together when
  both apply.
- **Discord community server.** README + issue-template config link
  to the public invite.
- README badges (Discord member count, license, latest release).
- `.github/` issue templates (bug report, feature request, data
  correction) and PR template.
- Screenshots under `assets/` with the sidebar overview inlined in
  the README as a hero image.
- Karamja diary varbits — Easy / Medium / Hard now auto-track instead
  of remaining manual-complete.
- `.githooks/pre-commit` blocks `java.lang.reflect` usage with a
  `@SuppressReflection` escape hatch for tests that genuinely need it.

### Changed
- Repository renamed from `runelite-goal-tracker` to
  `runelite-goal-planner` on GitHub. Old URL still redirects; the
  plugin-hub manifest points at the canonical URL.
- Empty-state hints rewritten to describe the right-click flow
  (Incomplete header, in-game skill / quest / diary / item / collection
  log entries, custom-section creation). The `+` button is gone.
- Goal-card right-click menu trimmed and consolidated; bulk menu
  restructured to mirror the single-item shape.
- Completed section now sorts by completion date, newest first.
- Move-to-Top / Move-to-Bottom baked into the arrow buttons via
  right-click; FlatLaf base font + 13pt for ColumnMenu rows; ASCII
  back glyph for cross-platform consistency.
- Remove Tag / Remove Requirement / Remove Dependent migrated to the
  multi-select checkbox dialog instead of nested submenus.
- QuestHelper plugin integration dropped — there's no published
  cross-plugin compile-time interop API.

### Fixed
- Panel didn't refresh when logging directly into Leagues.
- Move-to no-op when the source goal sat above the target.
- Completion state was lost across profile switches and login
  windows; canonical tags are now re-seeded on profile switch.
- Placeholder Discord invite URL in the sidebar Options menu.
- Plugin-hub deprecated-API check: replaced `WidgetID` and the shared
  Gson reference; added `build=standard` to plugin properties.
- "Shadows of Custodia" fix and assorted completion-UI cleanup landed
  during plugin-hub prep.

### Internal
- Rebranded source tree from `goaltracker` → `goalplanner` package +
  class names; renamed plugin to "Goal Planner".
- Reflection usage in tests replaced with direct package-private
  access.
- `addXxxGoal` APIs merged at the API layer; dev-only bulk helpers
  gated behind a flag.

## [0.1.0] — 2026-04-16 (Experimental first release)

> ⚠️ Experimental. Persistence format and public API are not stable;
> expect to re-create goals across upgrades until 1.0. See
> [ROADMAP.md](ROADMAP.md) for what's planned before 1.0.

### Added — Goal types

- **Skill goals** — target by Level (1–99) or raw XP up to 200M. Auto-
  track via `onStatChanged`. Same-skill goals auto-order lower-target
  above higher-target within their section.
- **Quest goals** — binary complete-when-finished tracking via
  `Quest.getState(client)`. Adding a quest offers "Add Goal with
  Requirements" that seeds the full transitive prereq chain.
- **Achievement diary goals** — all 11 areas verified across
  Easy/Medium/Hard/Elite tiers. Requirement data covers skills,
  quests, unlocks, boss kills, item reqs, and account metrics with
  OR-group alternatives (e.g. Warriors Guild entry). Karamja
  Easy/Medium/Hard lack named varbits and remain manual-complete.
- **Combat achievement goals** — 640 task slots covered via
  bit-packed `CA_TASK_COMPLETED_0..19` varplayers. Wiki data cache
  supplies task names, tiers, and monster tags; tier sword icons
  for Easy → Grandmaster.
- **Boss kill goals** — 89 bosses and activities covered, including
  GWD (+ Nex Frozen Door chain), slayer bosses, wilderness bosses,
  DT2 base + awakened variants, raids (CoX/CM, ToB/HM/Story, ToA
  tiers), waves (Jad, Zuk, Sol Heredit), skilling bosses, Varlamore
  content (Amoxliatl, Hueycoatl, Yama, Perilous Moons, Doom of
  Mokhaiotl), Zemouregal's Fort (Brutus + Demonic), The Gauntlet
  pair, Shellbane Gryphon, Fortis Colosseum waves, Barrows. 62 of
  89 have declared prereqs that auto-seed on goal creation.
- **Item / resource grind goals** — counts inventory + bank +
  equipment + seed vault + group storage + Kingdom of Miscellania.
  Bank-null pre-visit guard prevents wiping persisted totals to
  inventory-only on login. Relative-target mode ("Reach X" vs
  "Gain X more") on the Add Goal dialog.
- **Account metric goals** — 14 metrics: Quest Points, Combat Level,
  Total Level, CA Points, Slayer Points, Museum Kudos, Combined
  Att+Str, Misc Approval, Tears of Guthix PB, Chompy Kills,
  Colosseum Glory, DoM Deepest Level, League Points, League Tasks.
- **Custom goals** — free-text name + description + manual
  complete/incomplete.

### Added — Prereq seeding

- Widened `BossPrereqs` schema to match `DiaryRequirements.Reqs`
  (items, account metrics, boss-kill prereqs, OR-alternatives).
- `seedPrereqsInto` priority-queue BFS engine powers prereq seeding
  for diaries, quests, and bosses — a single shared implementation
  with consistent card-ordering (optional → skill/item/account →
  quest) and transitive quest chaining.
- Transitive quest chaining on boss goal creation: adding e.g.
  Vorkath now seeds Dragon Slayer II AND DS2's own prereq chain
  (Bone Voyage, Legends' Quest, etc.).
- OR-group seeding via `addOrRequirement` — alternative paths
  (e.g. 99 Attack OR 99 Strength OR 130 Att+Str combined for
  Warriors Guild) are stored as OR-edges, parent auto-completes
  when ANY one OR-child is complete AND all AND-children are
  complete.
- Pre-filtering in resolvers for recommended combat level and
  recommended skills — already-met recommendations don't seed as
  pre-completed cards.

### Added — UI

- Sections: built-in Incomplete + Completed plus user-defined
  sections, each renamable, recolorable, reorderable.
- Colors: 12-swatch curated palette + JColorChooser escape hatch.
  Per-section, per-goal, per-tag overrides. Category-level tag
  colors for BOSS / RAID / CLUE / MINIGAME.
- Tags: first-class entities with labels, categories, optional
  colors, optional icons (skill icons or bundled PNG). System vs
  user tag distinction with appropriate edit guards.
- Multi-select + bulk actions: Move to Section, Add Tag, Change
  Color, Mark Complete (CUSTOM), Remove.
- Undo / Redo: Ctrl-Z / Ctrl-Shift-Z for every user mutation.
  50-entry history cap. Tracker-driven mutations bypass the stack
  (skill XP, quest tick, item count change).
- Right-click menus on skills, quests, diary rows, CA tasks, boss
  entries in collection log, inventory/bank/CA items — all offer
  Add Goal (with prereq seeding where applicable).
- Search + filter on the goal list.
- Plugin sidebar icon.
- Relative goal targets (Reach X / Gain X more) for SKILL and
  ITEM_GRIND goals.

### Added — Public API

See [API.md](API.md) for the full method catalog.

- Read: `queryAllGoals`, `queryAllSections`.
- Create: `addSkillGoal`, `addSkillGoalForLevel`, `addItemGoal`,
  `addQuestGoal`, `addDiaryGoal`, `addCombatAchievementGoal`,
  `addBossGoal`, `addAccountGoal`, `addCustomGoal`.
- Prereq-chain create: `addQuestGoalWithPrereqs`,
  `addDiaryGoalWithPrereqs`.
- Mutate: `changeTarget`, `editCustomGoal`, `markGoalComplete`,
  `markGoalIncomplete`, `addTag`, `removeTag`, `restoreDefaultTags`,
  `removeGoal`.
- DTOs: `GoalView`, `SectionView`, `TagView` with type-specific
  attribute maps for BOSS and ACCOUNT types.

### Added — Infrastructure

- Command-pattern undo/redo with in-memory stacks, bulk-action
  single-step semantics, and ID-stability on add/remove cycles.
- `GoalStore` persistence via `ConfigManager`: three JSON blobs
  (`goalplanner.goals` / `.sections` / `.tags`) with load-time
  migrations for built-in section ordering and orphaned sectionIds.
- Eight trackers sharing an `AbstractTracker` template.
- `MockClient` + `MockGameState` + `TrackerTestHarness` test
  fixtures. Synthetic-data test hook (`swapPrereqsForTest`) for
  features that land without production data exercising them.

### Fixed

- **Goal section assignment persistence across restarts** —
  user-defined section assignments were being reverted to
  Incomplete on load.
- **EDT-swallowing bug in collection-log right-click** — the
  "Add KC Goal" path wrapped `addBossGoal` in `SwingUtilities.invokeLater`,
  causing `Quest.getState(client)` and `getRealSkillLevel` (called
  via the resolver) to throw on the EDT. Exceptions were silently
  swallowed by the AWT uncaught handler — chain never seeded. Fixed
  by hopping back to `clientThread.invokeLater` before the API
  call.
- **Recommended combat level seeded as already-completed card** —
  the resolver emitted a recommended-combat template without
  checking the player's live combat level. Downstream, the ACCOUNT
  goal was created with `currentValue=0` (builder default) so the
  post-creation `isComplete` check missed; the next AccountTracker
  tick flipped it to complete. Fixed by pre-filtering in the
  resolver.
- **Lag spike when adding goals** — compound gesture processing
  was firing the panel rebuild callback per goal; batched to once
  per compound.
- **Diary requirement corrections** — 11 area verification pass
  corrected task requirements; added 3 new account metrics
  (CHOMPY_KILLS, TOG_MAX_TEARS, MISC_APPROVAL), Zulrah / Thermy
  prereqs, OR-linked wildy boss pairs.
- **Data corrections on user review** — Amoxliatl quest gate
  (Heart of Darkness), Yama gate (A Kingdom Divided), Nex Frozen
  Door (1 KC per GWD room, not 40), TzKal-Zuk prereq (Fight Caves
  kill, not Fire cape item), DKs (no hard prereq, multiple access
  paths), Abyssal Sire (Enter the Abyss miniquest), Nightmare
  (Priest in Peril, not Sins of the Father), Grotesque Guardians
  (Brittle key). Brutus/Demonic Brutus (Defender of Varrock +
  BossReq), DoM (The Final Dawn).

### Internal

- `seedPrereqsInto` refactor: boss prereq seeding now routes through
  the same priority-queue BFS as quest + diary paths, producing
  consistent card-ordering.
- Additive constructor pattern for `BossPrereqs` — existing entries
  compile unchanged when new fields are added.
- `IntSupplier combatLevelLookup` lookup added to
  `QuestRequirementResolver` core overload (with backwards-compat
  3-arg overload) enabling live-state pre-filtering.
- EDT / client-thread discipline documented; existing
  `SwingUtilities.invokeLater` call sites audited.

### Known limitations

- Persistence has no schema version field — breaking changes will
  not migrate cleanly. Users upgrading across a breaking change
  should expect to re-create goals.
- Public API does not yet expose `queryAllTags`, requirement
  resolvers, or relation helpers (`addRequirement` / `addOrRequirement`).
  These may be promoted to public in 1.0.
- Some pets have no ItemID constant in runelite-api 1.12.23 (Doom
  of Mokhaiotl's "Dom", moon boss pets) — those entries ship
  without an icon. Will be filled in on api bump.
- Corporeal Beast's Summer's End quest is not in
  `net.runelite.api.Quest`; Corp ships without a quest prereq.
- Alternative OR-groups are seeded via edges but the UI doesn't
  yet visually group OR-children separately from AND-children in
  the card list.

## Not released

See [ROADMAP.md](ROADMAP.md).
