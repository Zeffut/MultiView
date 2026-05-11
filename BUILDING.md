# Building MultiView for multiple Minecraft versions

MultiView is published as one jar per supported MC range. Supported range:
**1.21.9 and newer**. Older versions (1.21–1.21.8) are not supported because
the Yarn API surface used by the merge pipeline changed in ways that would
require a proper preprocessor migration (Stonecutter) — out of scope.

## Layout

```
MultiView/
├── gradle.properties              # active build config (overwritten by build-version.sh)
├── versions/
│   ├── 1.21.9.properties          # MC 1.21.9 / 1.21.10
│   ├── 1.21.11.properties         # MC 1.21.11 (current development target)
│   └── 26.1.properties.PENDING    # MC 26.1.x — waiting for Yarn mappings
├── libs/                          # local Flashback jars per MC range (gitignored)
├── scripts/
│   ├── build-version.sh           # swaps properties + ./gradlew build
│   └── fetch-flashback.sh         # downloads matching Flashback jars from Modrinth
└── .github/workflows/
    ├── build.yml                  # CI: build active version
    └── multi-version-build.yml    # CI: build every supported version
```

## Building

```bash
# List versions known to the repo
./scripts/build-version.sh --list

# Build a specific version (writes build/libs/multiview-<modver>-mc<version>.jar)
./scripts/build-version.sh 1.21.11

# Build every supported version
./scripts/build-version.sh --all
```

The build script swaps `gradle.properties` for the version's overrides, runs
`./gradlew clean build`, then restores the original on exit. Each jar is also
copied with a `-mc<version>` suffix so multiple builds can co-exist in `build/libs/`.

## Fetching Flashback jars

Flashback ships separate jars per MC range — Modrinth knows which jar matches
which MC version. The helper script populates `libs/` automatically:

```bash
./scripts/fetch-flashback.sh           # download every variant we configure
./scripts/fetch-flashback.sh 1.21.11   # one version
./scripts/fetch-flashback.sh --list    # show what's present
```

## Adding a new MC version

1. Create `versions/<mc>.properties` using `versions/1.21.11.properties` as a template.
2. Look up the matching versions on Modrinth:
   - **Fabric API**: <https://modrinth.com/mod/fabric-api/versions>
   - **Flashback**: <https://modrinth.com/mod/flashback/versions>
3. Set `yarn_mappings` to the latest Yarn build for that MC version
   (<https://maven.fabricmc.net/net/fabricmc/yarn/>).
4. Run `./scripts/fetch-flashback.sh <mc>` to populate `libs/`.
5. Run `./scripts/build-version.sh <mc>` to verify it builds.
6. Add the version to the matrix in `.github/workflows/multi-version-build.yml`.

## Publishing to Modrinth

Each version range gets its own Modrinth version entry. Authentication via
`MODRINTH_TOKEN` (stored in the local `.env`, gitignored). Today the publish
flow is invoked manually with `curl` — see the commit history for example
calls. A `scripts/publish-modrinth.sh` helper is planned.

## Status snapshot

| MC version range | Build config | Compiles | TestHarness PASS | Modrinth |
|------------------|--------------|----------|-------------------|----------|
| 1.21 – 1.21.8    | dropped      | n/a      | n/a               | not published |
| 1.21.9 / 1.21.10 | ✓            | ✓        | not yet           | ✓ (id `1cDwxRNN`) |
| 1.21.11          | ✓            | ✓        | ✓                 | ✓ (id `7n86kr13`) |
| 26.1.x           | placeholder  | blocked  | n/a               | blocked (Yarn missing) |

## Why not Stonecutter?

A full Stonecutter migration was attempted to cover 1.21–1.21.8 too, but the
0.7.x Groovy DSL integration with Loom 1.16 requires more bespoke config than
the current scope justifies. The property-swap layout above stays in place;
Stonecutter remains an option for a 0.4.x dedicated session if/when the
supported range needs to be extended downward again.
