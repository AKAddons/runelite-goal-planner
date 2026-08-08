# Decisions

Architecture decisions are recorded as [MADR](https://adr.github.io/madr/)
files in **[`docs/decisions/`](docs/decisions/)** — one file per decision.

Start from [`0000-adr-template.md`](docs/decisions/0000-adr-template.md). Every
section except Context, Considered Options, and Decision Outcome is optional;
drop what would be filler. Numbers are identifiers, not chronology — `date:`
in the frontmatter carries the real ordering.

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](docs/decisions/0001-adopt-madr-for-architecture-decisions.md) | Record architecture decisions as MADR files under `docs/decisions/` | accepted |
| [0002](docs/decisions/0002-gpshare2-carries-cross-section-dependency-edges.md) | GPSHARE2 carries cross-section dependency edges in an additive bundle-level field | accepted |
| [0003](docs/decisions/0003-repeatable-goals-live-in-a-derived-built-in-section.md) | Repeatable goals live in a derived built-in section | accepted |
| [0004](docs/decisions/0004-clock-driven-reset-via-period-keys.md) | Reset repeatable goals from a period key on a clock timer | accepted |
| [0005](docs/decisions/0005-derived-goals-re-base-their-target-instead-of-storing-a-baseline.md) | Bite-sized goals re-base their target each period instead of storing a baseline | accepted |
| [0006](docs/decisions/0006-repeat-fields-are-additive-to-gpshare2.md) | Repeat fields ride GPSHARE2 additively; the chunk travels, the target does not | accepted |
| [0007](docs/decisions/0007-selection-driven-action-dock.md) | A selection-driven action dock replaces the panel right-click menus | accepted |
| [0008](docs/decisions/0008-search-to-create-via-the-dock.md) | Search-to-create: the action dock is the creation surface | proposed |

Decisions that bind both this repo and `goalplanner-share-mcp` are recorded
here and cited by number from the MCP repo.
