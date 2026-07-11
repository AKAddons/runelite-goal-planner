# Development

Everything you need to build, test, and contribute to Goal Planner. The
user-facing feature guide lives in the [README](../README.md).

## Build & run from source

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew run
```

Requires JDK 21 (Zulu recommended on macOS for FlatLaf compatibility). The
`run` task launches RuneLite in developer mode with the plugin loaded.

```bash
./gradlew test          # full suite
./gradlew checkDocs     # documentation-gap audit (see below)
```

## Project layout

```
src/main/java/com/goalplanner/
├── GoalPlannerPlugin.java        # Lifecycle, event handlers, MenuEntry injection
├── GoalPlannerConfig.java        # RuneLite plugin settings
├── api/                          # Public + internal API impl (GoalPlannerApi, DTOs, services)
├── command/                      # Undo/redo command pattern (Command, CompositeCommand, CommandHistory)
├── data/                         # Quest / diary / CA / boss requirement tables + resolvers
├── model/                        # Persisted entities (Goal, Section, ItemTag, enums)
├── persistence/
│   └── GoalStore.java            # ConfigManager-backed JSON + migrations + reconcile
├── service/
│   └── GoalReorderingService.java  # Skill-chain + section-aware ordering rules
├── tracker/                      # Skill / Quest / Diary / CA / Item / Boss / Account trackers + base
├── ui/                           # Swing panel, cards, dialogs, pickers, icons
└── util/                         # Formatting helpers
```

See [ARCHITECTURE.md](../ARCHITECTURE.md) for the full module + data-flow
walkthrough and key invariants.

## Public API

Other plugins can declare `@PluginDependency(GoalPlannerPlugin.class)` and
inject `GoalPlannerApi` to read goals + sections + tags and create new ones.
Full reference: [API.md](../API.md).

### Cross-plugin import message

Any plugin in the same client can hand Goal Planner a share bundle to import
by posting a RuneLite `PluginMessage` — no dependency on Goal Planner
required (the convention shortest-path / quest-helper use to interop):

| field | value |
|---|---|
| **namespace** | `goalplanner` |
| **name** | `import-share` |
| **data** | `{ "code": "<a Goal Planner share code, e.g. GPSHARE1:…>" }` |

```java
eventBus.post(new PluginMessage(
    "goalplanner", "import-share",
    java.util.Map.of("code", shareCode)));
```

Goal Planner decodes the code and imports the goals into a new user section.
Build codes with the `ShareCodec` / `ShareBundle` classes in
`com.goalplanner.share`, or with the
[goalplanner-share-mcp](https://github.com/ajkatz/goalplanner-share-mcp)
server.

### Share wire format

Two wire versions exist. `GPSHARE1:` carries one section (or loose goals) and
is what single-section shares still emit, so any plugin build imports them.
`GPSHARE2:` carries **multiple sections in one code** — each imports as its
own section, in one undo — and a section can be marked `targetDefault`,
landing its goals in the recipient's **Default plan** instead (existing
equivalent goals are reused, so re-importing never duplicates). Dependency
links **between** sections travel on a bundle-level cross-edge list (per-goal
relation refs stay section-scoped) and are rewired on import.

## Testing

```bash
./gradlew test
```

Covers the API impl, persistence + migrations, all trackers, the reordering
service, requirement resolvers, OR-group seeding, and integration flows for
deep prereq chains. New code that adds public API methods ships with tests in
the same change. See [TESTING.md](../TESTING.md) for the fixture pattern,
mock-vs-fake rules, and the MockClient thread-affinity caveat.

### Testing cross-plugin link-ins locally

`./gradlew run` (the dev client) also loads **companion plugin jars** dropped
into `dev-plugins/` (gitignored): the dev main
(`GoalPlannerPluginTest.COMPANION_PLUGINS`) reflectively loads known plugin
classes when their jar is on the classpath. This is how a link-in can be
tested end-to-end before (or without) a hub install — e.g. a local Loadout
Lab build receiving our "search" PluginMessage in the same client.

**Gotcha — hub copies conflict with dev-plugins copies.** The dev client uses
your real RuneLite profile, so a plugin installed from the Plugin Hub loads
*alongside* the same-named jar from `dev-plugins/`, and RuneLite disables one
same-name plugin in favor of the other (hub externals start first and win).
Symptom: the link-in menu item shows, but the click lands in the hub build
without the inbound handler - a silent no-op. Before testing, disable (or
uninstall) the hub copy of any plugin you're overriding in `dev-plugins/`;
remember that hub install state is shared with your main client.

## Documentation stays in lockstep

[`docs/features.json`](features.json) is the central feature registry: every
user-facing feature maps to its README section, its demo clip in `docs/img/`,
the code that implements it, and the tests that cover it.

**Any PR that adds or changes a feature updates the registry in the same
PR** — a new entry (or a `notes` + version bump on the existing one), the
README section with its clip, and a CHANGELOG line. Then run:

```bash
./gradlew checkDocs
```

It audits the registry against the README, `docs/img/`, and the source tree.
**Structural** drift (a README feature section gone, dead code/test paths,
unclaimed media) fails the task; **gaps** (unrecorded clips, untested
features) print as a running to-do report. Treat the structural section like
a failing test.

Recording clips: [Kap](https://getkap.co/) at panel width, exported as GIF,
then optimized with `gifsicle -O3 --lossy --scale` to a uniform width. Name
each file after its registry `media` entry and drop it in `docs/img/`.

## Before submitting a Plugin Hub update

The Plugin Hub's automated reviewer reads the plugin's **main source** (the
whole codebase, comments stripped — **tests are not counted**) and refuses to
review it above a hard **200,000-token** cap. This is a *release-time* concern,
not a per-edit one, so it's a pre-submission gate rather than a commit hook.

**Factor it into planning.** Large hardcoded data tables are the usual
culprit (each ~3.6 chars/token). When a change adds a big static data set,
plan to put the data in a **resource file** loaded at startup — TSV for flat
tables, JSON (via Gson) for nested ones — not in compiled `.java`. Resources
aren't counted (same as `docs/img/` and the README), and it's cleaner anyway.
The data resources under `src/main/resources/com/goalplanner/data/` are the
pattern; each is loaded by a small static block (TSV via `BufferedReader`) or
`init(Gson)` (JSON) in its owning `data/*` class:

| Resource | Owner | Shape |
|---|---|---|
| `quest-requirements.tsv` | `QuestRequirements` | `quest ⇥ skills ⇥ prereqs ⇥ qpReq ⇥ cmbReq ⇥ xpRewards ⇥ qpReward ⇥ recSkills ⇥ recCombat ⇥ displayOverride ⇥ flags(M/F/L)` |
| `item-sources.tsv` | `ItemSourceData` | itemId ⇥ display name |
| `boss-killcount.tsv` | `BossKillData` | boss name ⇥ kill-count varp id |
| `boss-pets.tsv` | `BossKillData` | boss name ⇥ pet item id |
| `boss-cl-aliases.tsv` | `BossKillData` | collection-log name ⇥ `boss;boss;…` |
| `boss-prereqs.json` | `BossKillData` | boss → nested prerequisite tree |
| `diary-completion-varbits.tsv` | `AchievementDiaryData` | `AREA\|TIER` ⇥ completion varbit id |
| `diary-requirements.json` | `DiaryRequirements` | area+tier → nested requirements |

The integer-id TSVs (`boss-*`, `diary-completion-varbits`) were generated once
from the symbolic `VarPlayerID`/`ItemID`/`VarbitID` constants by a throwaway
test tool that dumped the resolved values — the TSV is now the source of truth,
edited by hand.

Run the gate before re-pinning the hub PR:

```bash
./gradlew preSubmit     # = test + checkDocs + checkTokens + checkGlyphs
./gradlew checkTokens   # just the size estimate, anytime
./gradlew checkGlyphs   # flag macOS-Tahoe tofu glyphs in UI/chat strings
```

`checkTokens` prefers `tiktoken` (`pip install tiktoken`) for an exact count
and otherwise uses a calibrated char estimate that runs slightly high (fails
safe). It passes comfortably, warns near the cap, and **fails the build** if a
submission would breach it — with the largest files listed so you know what to
externalize.

## More

- [CHANGELOG.md](../CHANGELOG.md) — release notes
- [API.md](../API.md) — public API reference
- [ARCHITECTURE.md](../ARCHITECTURE.md) — module map, data flow, invariants
- [CONTRIBUTING.md](../CONTRIBUTING.md) — commit style, test-first rule, known pitfalls
- [TESTING.md](../TESTING.md) — test fixtures, mock-vs-fake rules
- [ROADMAP.md](../ROADMAP.md) — planned work
- [DECISIONS.md](../DECISIONS.md) — architecture decision log
