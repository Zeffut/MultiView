# PostHog SDK compatibility decision

Candidate checked on 2026-10-14: Maven Central `com.posthog:posthog-server:2.21.1`
(`maven-metadata.xml` latest/release). This is the modern SDK, not the legacy
`com.posthog.java:posthog` coordinate. The legacy coordinate's release is 1.2.0.

## Decision / exception

FlashbackTurbo uses the fixed legacy 1.2.0 instead of 1.1.1. MultiView retains
its fixed legacy 1.2.0. Neither project is claimed to be on the modern SDK.
Do not use `LATEST`, `2.+`, a personal API key, or production ingestion to test.

The modern server SDK is not a drop-in upgrade: it changes Java API and runtime
dependencies, requires a new shading/relocation strategy, and introduces
server-oriented automatic behaviour. Its 2.21.1 source `PostHogConfig.kt` has
`sendFeatureFlagEvent=true` and `preloadFeatureFlags=true` defaults (lines 44–57).
Its `remoteConfig` switch is deprecated and explicitly a no-op: remote config
is always enabled (lines 59–66). Copying the existing Builder configuration
would therefore not demonstrate the unchanged capture-only client contract.
This is a qualification exception, NOT a claim that every modern configuration
is inherently incompatible, nor approval to enable new data collection.

Owner: Forge. Reopen modern migration when a loopback-only spike demonstrates:
no unrequested remote-config/feature-flag/error-capture traffic or events,
same anonymous distinct ID and payload/privacy behaviour, config/dev/kill-switch
and runtime opt-out respected, bounded queue/flush/shutdown, no leaked Kotlin,
OkHttp, Okio, PostHog or JSON namespaces, all supported Java/Minecraft targets,
and independent review plus CI on the exact candidate SHA. Preserve event
names, application segmentation and existing disclosure; do not identify users
or enable additional collection as part of a dependency upgrade.

## Compatibility contract for legacy 1.2.0

The shipped SDK is an internally shaded uber-jar. The project additionally
relocates its PostHog namespace. Capture uses `capture(id,event,properties)`;
no `identify`, `alias`, `set` or `setOnce` is added. QueueManager drains on its
timer and `shutdown()` stops/joins its worker. There is no public `flush()`;
MultiView's existing flush is a no-op, not a guaranteed immediate drain.
Shutdown is NOT claimed to be bounded during network failure (legacy joins the
worker). This limitation is retained, not silently fixed by upgrading a pin.

`PostHogWireTest` starts a real loopback HTTP receiver, uses a dummy token,
queues one event, shuts down, and asserts the actual batch, anonymous ID,
properties, UUID/timestamp, no identify/person mutation, and repeated shutdown.
It never initializes a production SDK client or sends to an ingestion host.
Existing facade, sanitization, anonymous-ID and export-context tests stay in
scope. Archive/headless checks are non-ingestive, not an in-game GUI smoke.

No Gradle lockfiles existed for this upgrade. Resolve `dependencyInsight` on
`runtimeClasspath` and inspect the distributable, not just the declared pin.
No release, merge or deployment is authorized by this decision.

Sources: https://repo.maven.apache.org/maven2/com/posthog/posthog-server/maven-metadata.xml
and https://repo.maven.apache.org/maven2/com/posthog/posthog-server/2.21.1/posthog-server-2.21.1-sources.jar
and https://posthog.com/docs/libraries/java .
