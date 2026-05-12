# Building MultiView for multiple Minecraft versions

MultiView is published as one jar per supported MC range. Supported range today:
**1.21.9 and newer**. MC 26.1+ is staged but blocked on Loom (see below).

## Mappings stack

The project compiles against **Mojang Mappings** (mojmap). At build time, Loom
fetches mojmap from the MC version's manifest and remaps the obfuscated jar
to the named namespace. The produced jar is shipped in the intermediary
namespace for Fabric Loader to load on production clients.

Why mojmap and not Yarn? Yarn ships per MC version and lags behind Mojang
releases. Mojmap is published by Mojang on the same day as every MC release,
so we can build for new versions immediately without waiting for the
FabricMC mapping volunteers.

## Layout

```
MultiView/
├── build.gradle                     # mojmap + Loom config (per-version Java toolchain)
├── gradle.properties                # active build config (swapped by build-version.sh)
├── versions/
│   ├── 1.21.9.properties            # MC 1.21.9 / 1.21.10
│   ├── 1.21.11.properties           # MC 1.21.11 (current development target)
│   └── 26.1.properties              # MC 26.1.x — blocked on Loom (see status table)
├── libs/                            # local Flashback jars per MC range (gitignored)
├── scripts/
│   ├── build-version.sh             # swaps properties + ./gradlew build
│   ├── fetch-flashback.sh           # downloads matching Flashback jars from Modrinth
│   └── test-all-versions.sh         # multi-version merge regression suite (headless)
└── src/test/java/.../MergeIntegrationTest.java
                                     # headless merge test driven by real multiplayer replays
```

## Testing

### Multi-version regression (recommended)

```bash
./scripts/test-all-versions.sh
```

Iterates over every `versions/*.properties`, swaps `gradle.properties`,
runs the headless `MergeIntegrationTest` against the four reference
multiplayer replays in `run/flashback/replays/`, and prints a summary
table with per-version stats (merged ticks, entity dedup count, LWW
overwrites, packets deduplicated).

A version PASSes when the merge produces a valid zip, all stats are
non-zero, and merge runs in full-fidelity mode (i.e. Bootstrap initialized
the registry correctly — degraded PASSTHROUGH mode fails the test).

Typical timing: ~5 minutes total for two MC versions on real data.

### Headless single-version

```bash
./gradlew test --tests MergeIntegrationTest
```

Runs the same JUnit test against the active `gradle.properties` setup.
Requires the four reference replay zips in `run/flashback/replays/`:

- `2026-02-20T23_25_15.zip`
- `Hika_Civ_4.zip`
- `Jour_4_Romani_.zip`
- `Sénat_empirenapo2026-02-20T23_20_16.zip`

If any source is missing, the test is skipped (`@EnabledIf`).

### Runtime client test

For verifying Flashback × MC playback (not just merge), the in-mod
`TestHarness` boots an actual MC client and exercises the full flow:

```bash
echo '{"mode":"merge","sources":[...],"output":"x","playSeconds":60}' > run/.multiview-test.json
./gradlew runClient
cat run/.multiview-test-result.json
```

Use this when changing client-side UI or testing Flashback API
compatibility on a new MC version. Slower (~5 min) than the headless
test but covers the playback engine.

## Building jars

```bash
# List versions known to the repo
./scripts/build-version.sh --list

# Build a specific version (writes build/libs/multiview-<modver>-mc<version>.jar)
./scripts/build-version.sh 1.21.11

# Build every supported version
./scripts/build-version.sh --all
```

The build script swaps `gradle.properties` for the version's overrides, runs
`./gradlew clean build`, then restores the original on exit. Each jar is
copied with a `-mc<version>` suffix so multiple builds co-exist in `build/libs/`.

## Fetching Flashback jars

```bash
./scripts/fetch-flashback.sh           # download every variant we configure
./scripts/fetch-flashback.sh 1.21.11   # one version
./scripts/fetch-flashback.sh --list    # show what's present
```

## Adding a new MC version

1. Create `versions/<mc>.properties` using `versions/1.21.11.properties` as a template.
2. Look up matching versions:
   - **Fabric API**: <https://modrinth.com/mod/fabric-api/versions>
   - **Flashback**: <https://modrinth.com/mod/flashback/versions>
3. Run `./scripts/fetch-flashback.sh <mc>` to populate `libs/`.
4. Run `./scripts/test-all-versions.sh` to confirm the merge still passes.
5. Run `./scripts/build-version.sh <mc>` to produce the jar.
6. Add the version to the matrix in `.github/workflows/multi-version-build.yml`.

## Publishing to Modrinth

Authentication via `MODRINTH_TOKEN` in `.env` (gitignored). Manual via `curl`
for now — see commit history for example calls. Project ID: `ja9dG9KW`.

## Status snapshot

| MC version       | Build | Headless test | Runtime test | Modrinth |
|------------------|-------|---------------|--------------|----------|
| 1.21 – 1.21.8    | dropped | n/a         | n/a          | not published |
| 1.21.9 / 1.21.10 | ✓     | ✓ PASS        | ✓ PASS (record+merge harness) | ✓ id `t9NqZjHK` |
| 1.21.11          | ✓     | ✓ PASS        | ✓ PASS (real multiplayer replays) | ✓ id `uXWEHvBV` |
| 26.1.x           | blocked | blocked     | blocked      | not published |

### Why 26.1+ is blocked

MC 26.1+ ships **unobfuscated** bytecode (no mappings needed). Flashback ships
for 26.1 because they use a Loom 1.15-SNAPSHOT build that handles unobfuscated
MC jars natively. Our stable Loom releases (1.15.5, 1.16.1) explicitly require
a `mappings` declaration even when targeting unobfuscated MC, and Gradle's
plugin DSL doesn't resolve the `1.15-SNAPSHOT` alias to actual snapshot builds
— it falls back to release 1.15.5.

Workarounds attempted (none viable in this session):
- Pin exact snapshot timestamp via plugin DSL → unsupported syntax
- `loom.noIntermediateMappings()` + omit `mappings` → "Configuration 'mappings' has no dependencies"
- Resolution strategy override in `settings.gradle` → still resolves to release
- Custom `buildscript` classpath → not attempted; ~half-day task

When Loom ships stable support for unobfuscated MC (or we adopt the buildscript
classpath workaround), `versions/26.1.properties` is ready to drop in.
