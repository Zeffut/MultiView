# Building MultiView for multiple Minecraft versions

MultiView's source code currently targets Minecraft **1.21.11** (Yarn mappings). The repo is laid out so additional versions can be built without forking — they live as side-by-side property files that swap into the project root `gradle.properties` at build time.

## Layout

```
MultiView/
├── gradle.properties              # active build config (overwritten by build-version.sh)
├── versions/
│   ├── 1.21.properties            # MC 1.21 / 1.21.1
│   ├── 1.21.4.properties          # MC 1.21.4
│   ├── 1.21.5.properties          # MC 1.21.5
│   ├── 1.21.6.properties          # MC 1.21.6 / 1.21.7 / 1.21.8
│   ├── 1.21.9.properties          # MC 1.21.9 / 1.21.10
│   ├── 1.21.11.properties         # MC 1.21.11 (current development target)
│   └── 26.1.properties.PENDING    # MC 26.1.x — waiting for Yarn mappings
├── scripts/
│   └── build-version.sh           # swaps properties + runs ./gradlew build
└── .github/workflows/
    └── multi-version-build.yml    # matrix CI build for every version
```

## Building

```bash
# List versions known to the repo
./scripts/build-version.sh --list

# Build a specific version
./scripts/build-version.sh 1.21.11
# → produces build/libs/multiview-<modver>-mc1.21.11.jar

# Build every version
./scripts/build-version.sh --all
```

The script swaps the project's `gradle.properties` with the version's values, runs `./gradlew clean build`, then restores the original on exit. Each successful build is copied to `build/libs/multiview-<modver>-mc<version>.jar` so multiple builds can co-exist.

## Source code compatibility

Today the codebase is **only verified on 1.21.11**. Older versions in `versions/` are build infrastructure only — the actual compile *will fail* on most of them until version-conditional shims are added for the MC types whose API surface changed:

- `PlayPackets.PLAYER_INFO_UPDATE` etc. — protocol IDs differ between minor versions
- `PlayerListS2CPacket.Action` — enum gained `UPDATE_LIST_ORDER` and `UPDATE_HAT` in 1.21.5+
- `GameMessageS2CPacket.content()` — record accessor available since 1.20.5
- Flashback's own API (`Flashback.openReplayWorld`, `ReplayServer.replayPaused`, etc.) ships separate jars per MC range; the matching `flashback_modrinth_id` files would need to be downloaded into the build

The recommended path to actual multi-version support is **migrating to [Stonecutter](https://github.com/kikugie/stonecutter)** — a preprocessor designed for Fabric multi-version mods. With Stonecutter, version-conditional blocks are inline comments:

```java
//? if mc >= "1.21.5"
PlayerListS2CPacket.Action.UPDATE_HAT
//? else
PlayerListS2CPacket.Action.UPDATE_LIST_PRIORITY
//?}
```

Each entry under `versions/` would map to a Stonecutter target and produce its own jar. That's a 1–2 day refactor and would replace this property-file layer.

## Adding a new MC version

1. Create `versions/<mc-version>.properties` using one of the existing files as a template.
2. Look up the matching versions on Modrinth:
   - **Fabric API**: <https://modrinth.com/mod/fabric-api/versions>
   - **Flashback**: <https://modrinth.com/mod/flashback/versions>
3. Set `yarn_mappings` to the latest Yarn build for that MC version (<https://maven.fabricmc.net/net/fabricmc/yarn/>).
4. Add the new version to `.github/workflows/multi-version-build.yml`'s matrix.
5. Run `./scripts/build-version.sh <mc-version>` locally to verify it builds; iterate on shims if needed.

## Publishing each build

The current 0.3.0 release on Modrinth is for 1.21.11 only. To publish multi-version:

- Upload each jar as a **separate Modrinth version entry** with `game_versions` matching that build's `modrinth_game_versions` value.
- The Modrinth REST API call template lives in `scripts/publish-modrinth.sh` (TODO).
- Authentication: set `MODRINTH_TOKEN` in your shell or in GitHub Actions secrets before publishing.

## Status snapshot

| MC version range | Build config present | Compiles | Tested in runtime |
|------------------|----------------------|----------|--------------------|
| 1.21 / 1.21.1    | ✓                    | not yet  | no                |
| 1.21.4           | ✓                    | not yet  | no                |
| 1.21.5           | ✓                    | not yet  | no                |
| 1.21.6 – 1.21.8  | ✓                    | not yet  | no                |
| 1.21.9 / 1.21.10 | ✓                    | not yet  | no                |
| 1.21.11          | ✓                    | ✓        | ✓ (TestHarness)   |
| 26.1.x           | placeholder          | blocked  | no (Yarn missing)  |
