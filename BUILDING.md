# Building and validation

The maintenance candidate has five target configurations: `1.21.9` (also Minecraft 1.21.10), `1.21.11`, `26.1` (26.1.x), `26.2`, and `26.3`. Java 21 is required for 1.21.x and Java 25 for 26.x; make both JDKs available to Gradle. Gradle itself must run on Java 25 when building 26.x. Fabric API and Flashback are required at runtime.

```sh
./scripts/build-version.sh --list
./scripts/build-version.sh 26.3
./scripts/build-version.sh --all
```

The scripts download the exact Flashback JAR in each properties file and verify its SHA-512 before building. Builds run the JUnit suite. The final target JAR is `build/libs/multiview-0.5.1-mc<TARGET>.jar`; `--all` copies each final JAR to `dist/` before the next clean. These builds are not releases. The 26.x targets use unobfuscated `net.fabricmc.fabric-loom` and directly distribute the shaded JAR; 1.21.x uses the remapping Loom plugin. Do not use generated identity mappings for 26.x.

## Regression gates

The ordinary JUnit build includes the updater lifecycle and loopback-only PostHog wire tests; some replay-dependent tests can be skipped without a corpus. A successful `build` alone does not prove a replay merge. With the required real replay files in `run/flashback/replays` (see `MergeIntegrationTest` for exact names), run:

```sh
./scripts/test-all-versions.sh
```

This gate reports PASS only when its real integration test executes successfully on every target; missing corpus/SKIP is a failure. Target builds and tests modify `gradle.properties` temporarily; do not run them concurrently in the same checkout.

Before release, independently review the exact candidate SHA and obtain a green maintenance matrix CI for it. Test the final CI JARs, not project classes, in isolated clients with telemetry disabled: startup, Flashback UI, a real multi-POV merge and playback, recovery/update and privacy gates as applicable. Record source SHA, artifact hashes and actual evidence; do not treat a compile as a runtime test. No publishing, tag creation, or branch protection changes are performed by the maintenance workflow.
