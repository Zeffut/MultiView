# MultiView

[![Modrinth Downloads](https://img.shields.io/modrinth/dt/multiview?label=downloads&logo=modrinth)](https://modrinth.com/mod/multiview)
[![Modrinth Version](https://img.shields.io/modrinth/v/multiview?logo=modrinth&label=version)](https://modrinth.com/mod/multiview/versions)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/minecraft-1.21.9%20%7C%201.21.10%20%7C%201.21.11%20%7C%2026.1%2B-green)](#compatibility)

**MultiView** is a Fabric add-on for [Flashback](https://modrinth.com/mod/flashback). It takes **multiple `.flashback` replays recorded by different players from the same Minecraft session** and merges them into **one unified replay** — an "omniscient observer" view containing the union of every chunk, entity and event seen from any POV.

## What it does

Each player records their own POV using Flashback. MultiView aligns the recordings tick-by-tick and merges them into a single replay that behaves as if a single observer with unlimited render distance had recorded everything.

- **All explored chunks** from every POV are present in the merged replay.
- **All recording players** are visible at the same time.
- **All entities and events** seen by at least one POV are kept.
- **Free Camera** to roam, **Spectate Player** to follow a specific recorder.

## Use cases

- **Content creators** — SMP recaps, PvP tournaments, machinima with multi-angle coverage.
- **Server staff** — multi-angle review of incidents and anti-cheat investigations.
- **Cinematographers** — free camera through a multiplayer scene with no info loss.

## Quick start

1. Each player records their POV with Flashback during the same session.
2. Collect the produced `.zip` files into your own `<gameDir>/flashback/replays/` folder.
3. Launch Minecraft with **Flashback + MultiView** installed.
4. Click the camera icon → **Select Replay**.
5. Tick the checkbox at the right of each replay you want to merge (minimum 2).
6. Click **Merge N Replays** in the top-right.
7. Wait for the progress screen to finish.
8. `merged_<timestamp>.zip` appears in the list — open it like any other replay.

Chat-only fallback (e.g. when you want to script merges):

```
/mv merge <source1> <source2> <output>
```

## Compatibility

| Minecraft | Fabric Loader | Flashback | MultiView version |
| --- | --- | --- | --- |
| 1.21.9 / 1.21.10 | 0.19.2+ | 0.39.x | `0.3.2+mc1.21.9` |
| 1.21.11 | 0.19.2+ | 0.39.4 | `0.3.2+mc1.21.11` |
| 26.1 / 26.1.1 / 26.1.2 | 0.19.2+ | 0.40.0 | `0.3.2+mc26.1` |

Requires Java 21 on 1.21.x and Java 25 on 26.1+. Fabric API is required.

## Features

- **N-way merge** of Flashback replays from the same session.
- **Tick-perfect time alignment** via `ClientboundSetTimePacket`, with a fallback on `metadata.name`.
- **Cross-source deduplication** — chunks (SHA-256 128-bit content hash), player info updates, system chat by content, entity moves.
- **Multi-dimension support** — secondary POVs keep their dimension changes recorded as markers.
- **Aggregated markers** from every POV merged onto the unified timeline.
- **Integrated UI** — per-replay checkboxes in Flashback's *Select Replay* screen, no command typing required (1.21.x).
- **Atomic rollback** — writes to a `.part` file and atomically renames on success, so a failed merge never destroys your existing replays.
- **Bounded memory** — chat dedup is LRU-capped, chunk dedup uses cryptographic hashing, no unbounded growth on long sessions.
- **i18n** — French and English.

## Known limitations

- **Secondary POVs are entities, not cameras.** Flashback only supports one local player, so the camera follows the POV that started recording first ("primary"); the other recorders are visible as regular player entities.
- **4+ POV merges** may show minor visual artefacts in zones where several POVs hold conflicting chunk versions.
- See `SPEC.md` for the full technical limitations list.

## Build from source

```bash
# Default branch — Minecraft 1.21.11
./gradlew build
```

The jar lands in `build/libs/`.

Multi-version builds use a templating script:

```bash
./scripts/build-version.sh 1.21.9
./scripts/build-version.sh 1.21.11
./scripts/build-version.sh 26.1     # requires JDK 25
```

## Development client

```bash
./gradlew runClient
```

Local development with Flashback:

1. Download `Flashback-<ver>-for-MC<mc>.jar` from [Modrinth](https://modrinth.com/mod/flashback).
2. Drop it into `libs/` (compile-time) and `run/mods/` (runtime). Both folders are git-ignored — **never commit the Flashback jar**.
3. On 1.21.11 with Yarn `+build.4`, apply the `lattice` mixin patch documented in `SPEC.md` §10.

## Documentation

- [`CHANGELOG.md`](CHANGELOG.md) — release history.
- [`SPEC.md`](SPEC.md) — full spec, design journal, technical debt list.
- [`docs/superpowers/specs/`](docs/superpowers/specs/) — per-phase design documents.
- [`docs/superpowers/plans/`](docs/superpowers/plans/) — per-phase implementation plans.

## Contributing

Issues and pull requests are welcome on [GitHub](https://github.com/Zeffut/MultiView/issues). Please attach the affected MC version and a short reproduction (or sample replay folder when possible).

## License

[MIT](LICENSE) — Zeffut, 2026.

*Flashback remains under its proprietary license by Moulberry. MultiView is a fully independent addon and does not redistribute any Flashback code.*
