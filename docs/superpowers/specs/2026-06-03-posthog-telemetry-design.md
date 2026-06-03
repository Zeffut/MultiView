# PostHog Telemetry — Design

> Spec for adding a comprehensive, privacy-conscious telemetry layer to MultiView
> using the official `posthog-java` SDK (shaded/relocated into the mod jar).

## 1. Goals

- Capture as much *useful, anonymous* product data as possible so MultiView can be
  improved (which features are used, where merges fail, how long they take, on which
  MC/Flashback versions).
- Never break or slow down the mod. Telemetry is best-effort and fully fire-and-forget.
- Stay GDPR-friendly: anonymous identifiers, aggressive PII redaction, clear opt-out,
  first-run disclosure.

## 2. Decisions (locked)

| Decision            | Choice                                                                 |
|---------------------|------------------------------------------------------------------------|
| Consent model       | **Opt-out**, ON by default, anonymous, with config to disable + clear notice |
| Integration         | **`posthog-java` SDK, shaded & relocated** into the mod jar            |
| PostHog host        | EU cloud (`https://eu.i.posthog.com`)                                  |
| IP geolocation      | **Kept** (country/region level — useful for audience geography)         |
| Dev/CI behaviour    | Auto-disabled when `FabricLoader.isDevelopmentEnvironment()`           |
| Project / API key   | Shared `Default project` (id 192659), key `phc_zdMj4p5wo8EvfVApjb2EbfUHJ76zgYGM5wAGz5YJC359` (write-only). No dedicated project exists yet and the MCP cannot create one; events are tagged with an `app="multiview"` super property so they stay filterable and the key is a single constant to swap if a dedicated project is created later. |

## 3. Architecture

All telemetry lives behind a single facade so the rest of the codebase
(merge pipeline, UI, commands) only ever calls `Telemetry.capture(...)` and knows
nothing about PostHog.

```
fr.zeffut.multiview.telemetry/
├── Telemetry.java            ← static public facade (capture, identify, flush, shutdown)
├── TelemetryConfig.java      ← opt-out state + anonymous distinct_id, persisted as JSON
├── PostHogClient.java        ← wrapper around the shaded posthog-java SDK (init, batching, EU host)
├── TelemetryContext.java     ← "super properties" attached to EVERY event
├── EventNames.java           ← event-name constants (no magic strings)
├── Sanitizer.java            ← PII redaction (paths → basename/hash, no player names/UUIDs)
└── command/TelemetryCommand.java ← /mv telemetry on|off|status
```

### 3.1 Principles

- **Lazy, non-blocking init** in `onInitializeClient()`: a daemon thread, never on the
  render thread. If PostHog is unreachable → log at debug and continue.
- **Fault-tolerant facade**: every `capture()` is wrapped in a silent try/catch. A network
  error never propagates to the caller.
- **Anonymous distinct_id**: random UUID generated on first run, stored in
  `.minecraft/config/multiview-telemetry.json`. No link to the Minecraft account.
- **Write-only project key** embedded in the jar — standard for client analytics, not a
  sensitive secret.
- **Shading**: relocate `com.posthog.java.**` (and any transitive deps) under
  `fr.zeffut.multiview.libs.posthog.**` to avoid classpath conflicts with other mods.
  Verify the transitive dependency tree during implementation and relocate all of it.

### 3.2 Init / shutdown lifecycle

1. `MultiViewMod.onInitializeClient()` calls `Telemetry.init()`.
2. `Telemetry.init()` loads `TelemetryConfig`. If disabled (config, `-Dmultiview.telemetry=false`,
   or dev env) → no-op stub installed; all later calls are cheap no-ops.
3. Otherwise it builds `TelemetryContext` (super properties), spins up `PostHogClient`,
   sends `mod_loaded`, and (if `firstRunNotified=false`) queues the first-run chat notice.
4. A JVM shutdown hook (or Fabric `ClientLifecycleEvents.CLIENT_STOPPING`) flushes and
   closes the client so buffered events are not lost.

## 4. Event taxonomy

Every event also carries the super properties of §5.

### Lifecycle / session
- `mod_loaded` — versions (mod, MC, Flashback, Fabric loader), OS+arch, Java version,
  locale, UI capability (`modern`/`classic`/`disabled`), whether Flashback is present.
- `session_heartbeat` — fired once every 15 min while the game runs (session-length /
  retention signal).

### Merge (core)
- `merge_started` — source replay count, total input size, each chosen `MergeOptions`,
  trigger (`ui` / `command`).
- `merge_phase_completed` — one event per pipeline phase (read, align, dedup, rewrite,
  write) with duration → identifies bottlenecks.
- `merge_completed` — total duration, output size, merged packet count, deduplicated
  entity count, block count, conflicts resolved, compression ratio.
- `merge_failed` — error category, failing phase, exception type, elapsed before failure.
- `merge_cancelled` — phase, elapsed time.
- `overlap_validation_failed` — when `OverlapValidator` blocks disjoint timelines
  (source count, gap magnitude).

### UI
- `ui_opened` / `ui_closed` (UI session duration), `ui_file_selected` (file count),
  `ui_merge_clicked`, `progress_screen_viewed`.

### Commands & tools
- `command_used` — which command (`merge`, `inspect`) via chat.
- `inspect_performed` — what was inspected (metadata, size).

### Errors / exceptions
- Unhandled mod exceptions captured via PostHog error tracking, with **sanitized**
  stack traces (paths → basename, no player data).

## 5. Super properties (attached to every event)

`app` (constant `"multiview"` — discriminator so events stay filterable in the shared
project), `mod_version`, `mc_version`, `flashback_version`, `fabric_loader_version`,
`java_version`, `os_name`, `os_arch`, `locale`, `ui_capability`,
`distinct_id` (anonymous UUID), `session_id` (UUID regenerated each game launch).

## 6. Privacy / redaction (non-negotiable, centralized in `Sanitizer`)

Never sent:
- player names / UUIDs found inside replays,
- world / server names,
- full file paths (basename only, or a hash when correlation is needed),
- replay contents (aggregated counters and sizes only),

Kept:
- IP-based geolocation at country/region level (PostHog default, IP not stored beyond
  geo lookup as configured on the project).

## 7. Config & opt-out

`.minecraft/config/multiview-telemetry.json`:
```json
{ "enabled": true, "distinctId": "<uuid>", "firstRunNotified": false }
```

- `/mv telemetry off` → `enabled=false`, flush + stop immediately.
- `/mv telemetry on`  → re-enable.
- `/mv telemetry status` → show state + distinct_id (transparency).
- First run: one informational chat message
  ("MultiView sends anonymous stats to improve — `/mv telemetry off` to disable")
  → sets `firstRunNotified=true`.
- Override via `-Dmultiview.telemetry=false` (CI / advanced users).
- Auto-disabled in dev env (`FabricLoader.isDevelopmentEnvironment()`).

## 8. Documentation

- README: add a "Telemetry & privacy" section explaining what is collected, that it is
  anonymous and opt-out, and how to disable it.
- CHANGELOG entry for the release that introduces telemetry.

## 9. Testing

- `SanitizerTest` — paths, player names, world names are stripped/hashed correctly.
- `TelemetryConfigTest` — JSON round-trip, default values, enable/disable transitions.
- `TelemetryFacadeTest` — capture is a no-op when disabled; never throws on client error
  (inject a failing client).
- `EventNamesTest` — no duplicate / empty constants.
- Merge pipeline tests must still pass with telemetry wired in (a fake/no-op client in tests).

## 10. Out of scope (YAGNI)

- Feature flags / A-B testing from PostHog.
- Remote config.
- Per-player identification or any account linkage.
