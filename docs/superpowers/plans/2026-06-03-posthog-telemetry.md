# PostHog Telemetry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an anonymous, opt-out, fault-tolerant PostHog telemetry layer to MultiView so product usage, merge performance, and errors can be analysed and the mod improved.

**Architecture:** A single static `Telemetry` facade hides PostHog entirely. It writes through a `TelemetrySink` interface (real impl = `PostHogClient` wrapping the shaded `posthog-java` SDK; test impl = a recording fake). Every event is enriched with super properties from `TelemetryContext` and a PII `Sanitizer` strips anything sensitive. State (anonymous `distinctId`, opt-out flag) lives in `TelemetryConfig` persisted as JSON. All capture is best-effort: a network/SDK failure never propagates to the mod.

**Tech Stack:** Java 21, Fabric Loom, `com.posthog.java:posthog:1.2.0` (shaded & relocated via the GradleUp Shadow plugin), Gson (already provided by Minecraft), JUnit 5.

---

## Key facts (verified)

- `posthog-java` 1.2.0 is a self-contained uber-jar: okhttp3/kotlin/okio/org.json are already
  relocated under `com.posthog.java.shaded.*` inside it. Its published POM declares only
  **test-scope** dependencies → **no runtime transitive deps**. Relocating the single prefix
  `com.posthog.java` covers everything.
- PostHog Java API:
  ```java
  PostHog posthog = new PostHog.Builder("phc_...").host("https://eu.i.posthog.com").build();
  posthog.capture(distinctId, eventName, propertiesMap); // Map<String,Object>
  posthog.flush();
  posthog.shutdown();
  ```
  There are no built-in "super properties" — we merge them into each event's properties map ourselves.
- Project API key (write-only, shared `Default project` id 192659):
  `phc_zdMj4p5wo8EvfVApjb2EbfUHJ76zgYGM5wAGz5YJC359`. Host: `https://eu.i.posthog.com`.
- Existing integration points:
  - `MultiViewMod.onInitializeClient()` — mod entry point (`src/main/java/fr/zeffut/multiview/MultiViewMod.java`).
  - `MergeCommand.execute(...)` runs `MergeOrchestrator.run(options, progress)` on a daemon executor
    and gets back a `MergeReport`.
  - `MergeOrchestrator.run(MergeOptions, Consumer<String> progress)` calls `progress.accept(phaseName)`
    at each phase boundary.
  - `MergeReport.Stats` carries final counters; `MergeOptions` carries the inputs.
  - UI lives in two source roots: `src/main/java-modern/.../ui/MergeUi.java` and
    `src/main/java-classic/.../ui/MergeUi.java` (one is compiled per MC version).

## File Structure

New package `fr.zeffut.multiview.telemetry`:

| File | Responsibility |
|------|----------------|
| `Telemetry.java` | Static public facade. init/capture/flush/shutdown, no-op when disabled, never throws. |
| `TelemetrySink.java` | Interface: `capture(distinctId, event, props)`, `flush()`, `shutdown()`. |
| `PostHogClient.java` | `TelemetrySink` impl wrapping shaded `posthog-java` (EU host, key). |
| `NoopSink.java` | `TelemetrySink` impl that drops everything (disabled/dev/error path). |
| `TelemetryConfig.java` | JSON-persisted state: `enabled`, `distinctId`, `firstRunNotified`. Path-injectable. |
| `TelemetryContext.java` | Super properties map (versions, OS, locale, ui capability, app, ids). |
| `EventNames.java` | Event-name + property-key constants. |
| `Sanitizer.java` | PII redaction helpers (paths → basename, strip player/world data, stack trim). |
| `command/TelemetryCommand.java` | `/mv telemetry on\|off\|status` + first-run notice helper. |

Tests under `src/test/java/fr/zeffut/multiview/telemetry/`.

Instrumentation (no new files) touches: `MultiViewMod`, `MergeCommand`, `MergeOrchestrator`,
both `MergeUi` variants, `InspectCommand`, plus `build.gradle` / `settings.gradle` / README / CHANGELOG.

---

## Task 1: Gradle — add & shade posthog-java

**Files:**
- Modify: `settings.gradle` (add Shadow plugin to `pluginManagement`)
- Modify: `build.gradle` (plugin, repository, shadow config, relocation, remapJar wiring)

- [ ] **Step 1: Declare the Shadow plugin in settings.gradle**

In `settings.gradle`, the `pluginManagement.repositories` block already has `gradlePluginPortal()`,
so no repo change is needed. No edit required here — confirm by reading the file. (Kept as an explicit
step so the next steps assume the plugin resolves from the portal.)

- [ ] **Step 2: Apply the Shadow plugin**

In `build.gradle`, change the `plugins { }` block to:

```groovy
plugins {
    id 'fabric-loom' version "${loom_version}"
    id 'com.gradleup.shadow' version '8.3.5'
    id 'maven-publish'
}
```

> Compat note: this repo uses the Gradle 9.4.1 wrapper. If `com.gradleup.shadow:8.3.5` fails to
> apply on Gradle 9, bump to the latest `8.3.x`, or to the `9.x` line of the GradleUp Shadow plugin
> (which targets Gradle 9). Resolve the version at Step 6 (the first `./gradlew clean build`) before
> proceeding; do not leave the build red.

- [ ] **Step 3: Add Maven Central to repositories**

Replace the empty `repositories { }` block with:

```groovy
repositories {
    mavenCentral()
}
```

- [ ] **Step 4: Add the posthog dependency on a dedicated `shadow` configuration**

Inside `dependencies { }`, add (near the other declarations):

```groovy
    // PostHog telemetry SDK — shaded & relocated into the mod jar (see shadowJar below).
    // The published artifact is a self-contained uber-jar (okhttp/kotlin/okio/org.json already
    // bundled under com.posthog.java.shaded.*), so it has no runtime transitive deps.
    shadow "com.posthog.java:posthog:1.2.0"
    implementation "com.posthog.java:posthog:1.2.0"
```

And register the `shadow` configuration. Add this near the top of `build.gradle`, after the
`base { }` block:

```groovy
configurations {
    shadow
}
```

- [ ] **Step 5: Configure relocation + wire Shadow into Loom's remapJar**

Add at the end of `build.gradle`:

```groovy
shadowJar {
    configurations = [project.configurations.shadow]
    // Relocate the entire posthog namespace (covers its internally-shaded okhttp/kotlin/okio/org.json
    // which already live under com.posthog.java.shaded.*).
    relocate 'com.posthog.java', 'fr.zeffut.multiview.libs.posthog'
    archiveClassifier = 'dev-shadow'
}

// Loom remaps the *shadowed* jar so the relocated PostHog classes ship inside the final mod jar.
remapJar {
    dependsOn shadowJar
    inputFile = shadowJar.archiveFile
}

tasks.named('build') {
    dependsOn shadowJar
}
```

- [ ] **Step 6: Build and verify the relocated classes are bundled**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL.

Then verify the relocation landed in the remapped jar:

Run: `unzip -l build/libs/multiview-*.jar | grep -c 'fr/zeffut/multiview/libs/posthog/PostHog'`
Expected: `1` (the relocated `PostHog.class` is present).

Run: `unzip -l build/libs/multiview-*.jar | grep -c '^.*com/posthog/java/PostHog.class'`
Expected: `0` (no un-relocated PostHog classes leak).

- [ ] **Step 7: Commit**

```bash
git add settings.gradle build.gradle
git commit -m "build: add posthog-java, shaded & relocated under fr.zeffut.multiview.libs.posthog"
```

---

## Task 2: EventNames constants

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/telemetry/EventNames.java`
- Test: `src/test/java/fr/zeffut/multiview/telemetry/EventNamesTest.java`

- [ ] **Step 1: Write the failing test**

```java
package fr.zeffut.multiview.telemetry;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EventNamesTest {
    @Test
    void allEventConstantsAreUniqueAndNonBlank() throws IllegalAccessException {
        List<String> values = new ArrayList<>();
        for (Field f : EventNames.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class
                    && f.getName().startsWith("EVT_")) {
                String v = (String) f.get(null);
                assertFalse(v == null || v.isBlank(), "blank event constant: " + f.getName());
                values.add(v);
            }
        }
        Set<String> unique = new HashSet<>(values);
        assertEquals(values.size(), unique.size(), "duplicate event names: " + values);
        assertFalse(values.isEmpty(), "no event constants found");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'fr.zeffut.multiview.telemetry.EventNamesTest'`
Expected: FAIL — `EventNames` does not exist (compile error).

- [ ] **Step 3: Create EventNames**

```java
package fr.zeffut.multiview.telemetry;

/** Event names and property keys for PostHog. No magic strings elsewhere. */
public final class EventNames {
    private EventNames() {}

    // Lifecycle / session
    public static final String EVT_MOD_LOADED = "mod_loaded";
    public static final String EVT_SESSION_HEARTBEAT = "session_heartbeat";

    // Merge
    public static final String EVT_MERGE_STARTED = "merge_started";
    public static final String EVT_MERGE_PHASE_COMPLETED = "merge_phase_completed";
    public static final String EVT_MERGE_COMPLETED = "merge_completed";
    public static final String EVT_MERGE_FAILED = "merge_failed";
    public static final String EVT_MERGE_CANCELLED = "merge_cancelled";
    public static final String EVT_OVERLAP_VALIDATION_FAILED = "overlap_validation_failed";

    // UI
    public static final String EVT_UI_OPENED = "ui_opened";
    public static final String EVT_UI_CLOSED = "ui_closed";
    public static final String EVT_UI_FILE_SELECTED = "ui_file_selected";
    public static final String EVT_UI_MERGE_CLICKED = "ui_merge_clicked";

    // Commands & tools
    public static final String EVT_COMMAND_USED = "command_used";
    public static final String EVT_INSPECT_PERFORMED = "inspect_performed";

    // Errors
    public static final String EVT_MOD_ERROR = "mod_error";
    public static final String EVT_TELEMETRY_OPTOUT = "telemetry_optout";

    // Common property keys
    public static final String PROP_APP = "app";
    public static final String PROP_MOD_VERSION = "mod_version";
    public static final String PROP_MC_VERSION = "mc_version";
    public static final String PROP_FLASHBACK_VERSION = "flashback_version";
    public static final String PROP_FABRIC_LOADER_VERSION = "fabric_loader_version";
    public static final String PROP_JAVA_VERSION = "java_version";
    public static final String PROP_OS_NAME = "os_name";
    public static final String PROP_OS_ARCH = "os_arch";
    public static final String PROP_LOCALE = "locale";
    public static final String PROP_UI_CAPABILITY = "ui_capability";
    public static final String PROP_SESSION_ID = "session_id";
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'fr.zeffut.multiview.telemetry.EventNamesTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/multiview/telemetry/EventNames.java \
        src/test/java/fr/zeffut/multiview/telemetry/EventNamesTest.java
git commit -m "feat(telemetry): add EventNames constants"
```

---

## Task 3: Sanitizer (PII redaction)

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/telemetry/Sanitizer.java`
- Test: `src/test/java/fr/zeffut/multiview/telemetry/SanitizerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package fr.zeffut.multiview.telemetry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SanitizerTest {
    @Test
    void basenameStripsFullPath() {
        assertEquals("merged_2026.zip",
                Sanitizer.basename("/Users/alice/.minecraft/replay/merged_2026.zip"));
        assertEquals("merged_2026.zip",
                Sanitizer.basename("C:\\Users\\alice\\replay\\merged_2026.zip"));
        assertEquals("", Sanitizer.basename(null));
    }

    @Test
    void stackTraceIsTrimmedAndPathsStripped() {
        Exception e = new IllegalStateException("boom at /Users/alice/.minecraft/x");
        String s = Sanitizer.stackSummary(e, 5);
        assertFalse(s.contains("/Users/alice"), "absolute path leaked: " + s);
        assertTrue(s.contains("IllegalStateException"));
        // at most 5 frames + header → bounded length
        assertTrue(s.lines().count() <= 6, "stack not trimmed: " + s);
    }

    @Test
    void redactMessageRemovesUserHomePaths() {
        assertEquals("boom at <path>",
                Sanitizer.redactMessage("boom at /Users/alice/.minecraft/x"));
        assertEquals("boom at <path>",
                Sanitizer.redactMessage("boom at C:\\Users\\alice\\AppData\\x"));
        assertEquals("no path here", Sanitizer.redactMessage("no path here"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'fr.zeffut.multiview.telemetry.SanitizerTest'`
Expected: FAIL — `Sanitizer` does not exist.

- [ ] **Step 3: Implement Sanitizer**

```java
package fr.zeffut.multiview.telemetry;

import java.util.regex.Pattern;

/**
 * Central PII redaction. Telemetry NEVER sends player names/UUIDs, world/server names,
 * full file paths, or replay contents. These helpers enforce that at the boundary.
 */
public final class Sanitizer {
    private Sanitizer() {}

    // Matches unix (/Users/.., /home/..) and windows (C:\Users\..) absolute paths.
    private static final Pattern PATH = Pattern.compile(
            "(?:[A-Za-z]:\\\\|/)(?:[^\\s\"']*[\\\\/])?[^\\s\"']*");

    /** Last path segment of a unix or windows path; "" for null. */
    public static String basename(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /** Replace any absolute path in a free-text message with "<path>". */
    public static String redactMessage(String message) {
        if (message == null) return "";
        return PATH.matcher(message).replaceAll("<path>");
    }

    /**
     * Compact, path-redacted exception summary: "ExceptionType: redactedMessage"
     * plus up to {@code maxFrames} stack frames (class.method only, no file paths).
     */
    public static String stackSummary(Throwable t, int maxFrames) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(redactMessage(t.getMessage()));
        StackTraceElement[] frames = t.getStackTrace();
        int n = Math.min(maxFrames, frames.length);
        for (int i = 0; i < n; i++) {
            StackTraceElement f = frames[i];
            sb.append('\n').append(f.getClassName()).append('.').append(f.getMethodName());
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'fr.zeffut.multiview.telemetry.SanitizerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/multiview/telemetry/Sanitizer.java \
        src/test/java/fr/zeffut/multiview/telemetry/SanitizerTest.java
git commit -m "feat(telemetry): add PII Sanitizer"
```

---

## Task 4: TelemetryConfig (JSON-persisted state)

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/telemetry/TelemetryConfig.java`
- Test: `src/test/java/fr/zeffut/multiview/telemetry/TelemetryConfigTest.java`

- [ ] **Step 1: Write the failing test**

```java
package fr.zeffut.multiview.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryConfigTest {
    @Test
    void firstLoadGeneratesDistinctIdAndDefaultsEnabled(@TempDir Path dir) {
        Path file = dir.resolve("multiview-telemetry.json");
        TelemetryConfig cfg = TelemetryConfig.load(file);
        assertTrue(cfg.isEnabled());
        assertFalse(cfg.isFirstRunNotified());
        assertNotNull(cfg.getDistinctId());
        assertFalse(cfg.getDistinctId().isBlank());
        assertTrue(Files.exists(file), "config should be written on first load");
    }

    @Test
    void distinctIdIsStableAcrossLoads(@TempDir Path dir) {
        Path file = dir.resolve("multiview-telemetry.json");
        String first = TelemetryConfig.load(file).getDistinctId();
        String second = TelemetryConfig.load(file).getDistinctId();
        assertEquals(first, second);
    }

    @Test
    void disablingPersists(@TempDir Path dir) {
        Path file = dir.resolve("multiview-telemetry.json");
        TelemetryConfig cfg = TelemetryConfig.load(file);
        cfg.setEnabled(false);
        assertFalse(TelemetryConfig.load(file).isEnabled());
    }

    @Test
    void markNotifiedPersists(@TempDir Path dir) {
        Path file = dir.resolve("multiview-telemetry.json");
        TelemetryConfig cfg = TelemetryConfig.load(file);
        cfg.markFirstRunNotified();
        assertTrue(TelemetryConfig.load(file).isFirstRunNotified());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'fr.zeffut.multiview.telemetry.TelemetryConfigTest'`
Expected: FAIL — `TelemetryConfig` does not exist.

- [ ] **Step 3: Implement TelemetryConfig**

```java
package fr.zeffut.multiview.telemetry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Persistent, anonymous telemetry state stored as JSON in the config dir.
 * Path is injected so the class is unit-testable without Fabric.
 */
public final class TelemetryConfig {
    private static final Logger LOG = LoggerFactory.getLogger(TelemetryConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Serialised fields (Gson). Defaults apply when the file is absent.
    private boolean enabled = true;
    private String distinctId;
    private boolean firstRunNotified = false;

    private transient Path file;

    private TelemetryConfig() {}

    /** Load from {@code file}, generating a distinctId + writing defaults on first run. */
    public static TelemetryConfig load(Path file) {
        TelemetryConfig cfg = null;
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                cfg = GSON.fromJson(json, TelemetryConfig.class);
            } catch (Exception e) {
                LOG.warn("[MultiView] telemetry config unreadable, recreating: {}", e.getMessage());
            }
        }
        if (cfg == null) cfg = new TelemetryConfig();
        cfg.file = file;
        boolean dirty = false;
        if (cfg.distinctId == null || cfg.distinctId.isBlank()) {
            cfg.distinctId = UUID.randomUUID().toString();
            dirty = true;
        }
        if (!Files.exists(file)) dirty = true;
        if (dirty) cfg.save();
        return cfg;
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("[MultiView] could not persist telemetry config: {}", e.getMessage());
        }
    }

    public boolean isEnabled() { return enabled; }
    public String getDistinctId() { return distinctId; }
    public boolean isFirstRunNotified() { return firstRunNotified; }

    public void setEnabled(boolean value) { this.enabled = value; save(); }
    public void markFirstRunNotified() { this.firstRunNotified = true; save(); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'fr.zeffut.multiview.telemetry.TelemetryConfigTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/multiview/telemetry/TelemetryConfig.java \
        src/test/java/fr/zeffut/multiview/telemetry/TelemetryConfigTest.java
git commit -m "feat(telemetry): add JSON-persisted TelemetryConfig"
```

---

## Task 5: TelemetrySink interface + NoopSink

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/telemetry/TelemetrySink.java`
- Create: `src/main/java/fr/zeffut/multiview/telemetry/NoopSink.java`

- [ ] **Step 1: Create the interface**

```java
package fr.zeffut.multiview.telemetry;

import java.util.Map;

/** Output boundary for telemetry. Real impl talks to PostHog; tests record calls. */
public interface TelemetrySink {
    void capture(String distinctId, String event, Map<String, Object> properties);
    void flush();
    void shutdown();
}
```

- [ ] **Step 2: Create NoopSink**

```java
package fr.zeffut.multiview.telemetry;

import java.util.Map;

/** Sink used when telemetry is disabled, in dev, or after an init failure. Drops everything. */
public final class NoopSink implements TelemetrySink {
    @Override public void capture(String distinctId, String event, Map<String, Object> properties) {}
    @Override public void flush() {}
    @Override public void shutdown() {}
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/fr/zeffut/multiview/telemetry/TelemetrySink.java \
        src/main/java/fr/zeffut/multiview/telemetry/NoopSink.java
git commit -m "feat(telemetry): add TelemetrySink interface + NoopSink"
```

---

## Task 6: PostHogClient (real sink)

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/telemetry/PostHogClient.java`

> Note: this class imports the **relocated** PostHog package
> `fr.zeffut.multiview.libs.posthog.PostHog`. The relocation only happens in `shadowJar`,
> so `compileJava` against the un-relocated `com.posthog.java.PostHog` would fail. To compile
> against the relocated name while developing, the dependency is declared on both `shadow` and
> `implementation` in Task 1 — but `implementation` keeps the ORIGINAL package
> `com.posthog.java.PostHog`. Therefore this class MUST import the original package
> `com.posthog.java.PostHog`; Shadow rewrites the bytecode reference to the relocated package at
> package time. Import the original `com.posthog.java.*` names here.

- [ ] **Step 1: Implement PostHogClient**

```java
package fr.zeffut.multiview.telemetry;

import com.posthog.java.PostHog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** {@link TelemetrySink} backed by the (shaded) posthog-java SDK, EU host. */
public final class PostHogClient implements TelemetrySink {
    private static final Logger LOG = LoggerFactory.getLogger(PostHogClient.class);

    private final PostHog posthog;

    public PostHogClient(String apiKey, String host) {
        this.posthog = new PostHog.Builder(apiKey).host(host).build();
    }

    @Override
    public void capture(String distinctId, String event, Map<String, Object> properties) {
        try {
            posthog.capture(distinctId, event, properties);
        } catch (Throwable t) {
            LOG.debug("[MultiView] telemetry capture failed: {}", t.getMessage());
        }
    }

    @Override
    public void flush() {
        try { posthog.flush(); } catch (Throwable t) { LOG.debug("flush failed: {}", t.getMessage()); }
    }

    @Override
    public void shutdown() {
        try { posthog.shutdown(); } catch (Throwable t) { LOG.debug("shutdown failed: {}", t.getMessage()); }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL (resolves `com.posthog.java.PostHog` from the `implementation` dep).

- [ ] **Step 3: Build the full jar and verify capture wiring relocates**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL.

Run: `unzip -p build/libs/multiview-*.jar fr/zeffut/multiview/telemetry/PostHogClient.class | javap -c -p - 2>/dev/null | grep -c 'fr/zeffut/multiview/libs/posthog/PostHog'`
Expected: a number `>= 1` (bytecode references point at the relocated class).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/fr/zeffut/multiview/telemetry/PostHogClient.java
git commit -m "feat(telemetry): add PostHogClient sink (EU host)"
```

---

## Task 7: TelemetryContext (super properties)

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/telemetry/TelemetryContext.java`
- Test: `src/test/java/fr/zeffut/multiview/telemetry/TelemetryContextTest.java`

- [ ] **Step 1: Write the failing test**

```java
package fr.zeffut.multiview.telemetry;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryContextTest {
    @Test
    void mergeKeepsBaseAndAddsEventProps() {
        Map<String, Object> base = Map.of(EventNames.PROP_APP, "multiview",
                EventNames.PROP_MOD_VERSION, "0.4.0");
        TelemetryContext ctx = new TelemetryContext(base, "session-123");
        Map<String, Object> merged = ctx.enrich(Map.of("count", 3));
        assertEquals("multiview", merged.get(EventNames.PROP_APP));
        assertEquals("0.4.0", merged.get(EventNames.PROP_MOD_VERSION));
        assertEquals("session-123", merged.get(EventNames.PROP_SESSION_ID));
        assertEquals(3, merged.get("count"));
    }

    @Test
    void eventPropsOverrideNothingCritical_andEnrichWithNullIsSafe() {
        TelemetryContext ctx = new TelemetryContext(Map.of(EventNames.PROP_APP, "multiview"), "s1");
        Map<String, Object> merged = ctx.enrich(null);
        assertEquals("multiview", merged.get(EventNames.PROP_APP));
        assertEquals("s1", merged.get(EventNames.PROP_SESSION_ID));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'fr.zeffut.multiview.telemetry.TelemetryContextTest'`
Expected: FAIL — `TelemetryContext` does not exist.

- [ ] **Step 3: Implement TelemetryContext**

```java
package fr.zeffut.multiview.telemetry;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds the super properties attached to every event. Construct via {@link #fromEnvironment}
 * in production, or directly (with a literal map) in tests.
 */
public final class TelemetryContext {
    private final Map<String, Object> superProps;
    private final String sessionId;

    public TelemetryContext(Map<String, Object> superProps, String sessionId) {
        this.superProps = new HashMap<>(superProps);
        this.sessionId = sessionId;
    }

    /** Merge super props + session id into per-event props (event props win on key clash). */
    public Map<String, Object> enrich(Map<String, Object> eventProps) {
        Map<String, Object> out = new HashMap<>(superProps);
        out.put(EventNames.PROP_SESSION_ID, sessionId);
        if (eventProps != null) out.putAll(eventProps);
        return out;
    }

    /**
     * Build the production context from the Fabric/Minecraft runtime + the detected UI capability.
     * Kept out of unit tests because it touches FabricLoader.
     */
    public static TelemetryContext fromEnvironment(String uiCapability) {
        Map<String, Object> p = new HashMap<>();
        p.put(EventNames.PROP_APP, "multiview");
        p.put(EventNames.PROP_MOD_VERSION, modVersion("multiview"));
        p.put(EventNames.PROP_MC_VERSION, modVersion("minecraft"));
        p.put(EventNames.PROP_FLASHBACK_VERSION, modVersion("flashback"));
        p.put(EventNames.PROP_FABRIC_LOADER_VERSION, modVersion("fabricloader"));
        p.put(EventNames.PROP_JAVA_VERSION, System.getProperty("java.version", "unknown"));
        p.put(EventNames.PROP_OS_NAME, System.getProperty("os.name", "unknown"));
        p.put(EventNames.PROP_OS_ARCH, System.getProperty("os.arch", "unknown"));
        p.put(EventNames.PROP_LOCALE, java.util.Locale.getDefault().toLanguageTag());
        p.put(EventNames.PROP_UI_CAPABILITY, uiCapability);
        return new TelemetryContext(p, java.util.UUID.randomUUID().toString());
    }

    private static String modVersion(String modId) {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer(modId)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("absent");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'fr.zeffut.multiview.telemetry.TelemetryContextTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/multiview/telemetry/TelemetryContext.java \
        src/test/java/fr/zeffut/multiview/telemetry/TelemetryContextTest.java
git commit -m "feat(telemetry): add TelemetryContext super properties"
```

---

## Task 8: Telemetry facade

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/telemetry/Telemetry.java`
- Test: `src/test/java/fr/zeffut/multiview/telemetry/TelemetryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package fr.zeffut.multiview.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryTest {
    /** Recording sink for assertions. */
    static final class RecordingSink implements TelemetrySink {
        record Call(String distinctId, String event, Map<String, Object> props) {}
        final List<Call> calls = new ArrayList<>();
        boolean fail = false;
        @Override public void capture(String id, String e, Map<String, Object> p) {
            if (fail) throw new RuntimeException("network down");
            calls.add(new Call(id, e, p));
        }
        @Override public void flush() {}
        @Override public void shutdown() {}
    }

    private TelemetryContext ctx() {
        return new TelemetryContext(Map.of(EventNames.PROP_APP, "multiview"), "sess-1");
    }

    @Test
    void captureEnrichesAndForwards(@TempDir Path dir) {
        TelemetryConfig cfg = TelemetryConfig.load(dir.resolve("c.json"));
        RecordingSink sink = new RecordingSink();
        Telemetry.initForTest(cfg, ctx(), sink);

        Telemetry.capture(EventNames.EVT_MOD_LOADED, Map.of("k", 1));

        assertEquals(1, sink.calls.size());
        RecordingSink.Call c = sink.calls.get(0);
        assertEquals(cfg.getDistinctId(), c.distinctId());
        assertEquals(EventNames.EVT_MOD_LOADED, c.event());
        assertEquals("multiview", c.props().get(EventNames.PROP_APP));
        assertEquals("sess-1", c.props().get(EventNames.PROP_SESSION_ID));
        assertEquals(1, c.props().get("k"));
        Telemetry.shutdown();
    }

    @Test
    void disabledConfigIsNoOp(@TempDir Path dir) {
        TelemetryConfig cfg = TelemetryConfig.load(dir.resolve("c.json"));
        cfg.setEnabled(false);
        RecordingSink sink = new RecordingSink();
        Telemetry.initForTest(cfg, ctx(), sink);
        Telemetry.capture(EventNames.EVT_MOD_LOADED, Map.of());
        assertTrue(sink.calls.isEmpty());
        Telemetry.shutdown();
    }

    @Test
    void sinkErrorNeverPropagates(@TempDir Path dir) {
        TelemetryConfig cfg = TelemetryConfig.load(dir.resolve("c.json"));
        RecordingSink sink = new RecordingSink();
        sink.fail = true;
        Telemetry.initForTest(cfg, ctx(), sink);
        assertDoesNotThrow(() -> Telemetry.capture(EventNames.EVT_MOD_LOADED, Map.of()));
        Telemetry.shutdown();
    }

    @Test
    void captureBeforeInitIsSafe() {
        Telemetry.shutdown(); // ensure uninitialised
        assertDoesNotThrow(() -> Telemetry.capture(EventNames.EVT_MOD_LOADED, Map.of()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'fr.zeffut.multiview.telemetry.TelemetryTest'`
Expected: FAIL — `Telemetry` does not exist.

- [ ] **Step 3: Implement Telemetry**

```java
package fr.zeffut.multiview.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Static, fault-tolerant telemetry facade. The rest of the mod only ever calls
 * {@link #capture}. Every call is a cheap no-op when disabled and never throws.
 */
public final class Telemetry {
    private static final Logger LOG = LoggerFactory.getLogger(Telemetry.class);

    private static final String API_KEY = "phc_zdMj4p5wo8EvfVApjb2EbfUHJ76zgYGM5wAGz5YJC359";
    private static final String HOST = "https://eu.i.posthog.com";

    private static volatile TelemetryConfig config;
    private static volatile TelemetryContext context;
    private static volatile TelemetrySink sink;

    private Telemetry() {}

    /**
     * Production init. Loads config, decides enablement (config flag, -Dmultiview.telemetry=false,
     * dev env), builds the real sink. Best-effort: any failure leaves telemetry disabled.
     */
    public static void init(java.nio.file.Path configFile, String uiCapability, boolean devEnv) {
        try {
            TelemetryConfig cfg = TelemetryConfig.load(configFile);
            boolean propDisabled = "false".equalsIgnoreCase(
                    System.getProperty("multiview.telemetry", "true"));
            boolean enabled = cfg.isEnabled() && !propDisabled && !devEnv;
            config = cfg;
            context = TelemetryContext.fromEnvironment(uiCapability);
            sink = enabled ? new PostHogClient(API_KEY, HOST) : new NoopSink();
            if (enabled) {
                LOG.info("[MultiView] telemetry enabled (anonymous, opt-out via /mv telemetry off).");
            } else {
                LOG.info("[MultiView] telemetry disabled (config/prop/dev).");
            }
        } catch (Throwable t) {
            LOG.warn("[MultiView] telemetry init failed, disabling: {}", t.getMessage());
            sink = new NoopSink();
        }
    }

    /** Test seam: inject config/context/sink directly. */
    public static void initForTest(TelemetryConfig cfg, TelemetryContext ctx, TelemetrySink s) {
        config = cfg;
        context = ctx;
        sink = cfg.isEnabled() ? s : new NoopSink();
    }

    public static void capture(String event) { capture(event, Map.of()); }

    public static void capture(String event, Map<String, Object> properties) {
        TelemetrySink s = sink;
        TelemetryContext ctx = context;
        TelemetryConfig cfg = config;
        if (s == null || ctx == null || cfg == null) return;
        try {
            s.capture(cfg.getDistinctId(), event, ctx.enrich(properties));
        } catch (Throwable t) {
            LOG.debug("[MultiView] telemetry capture dropped: {}", t.getMessage());
        }
    }

    public static boolean isEnabled() {
        TelemetryConfig cfg = config;
        return cfg != null && cfg.isEnabled() && !(sink instanceof NoopSink);
    }

    public static String distinctId() {
        TelemetryConfig cfg = config;
        return cfg == null ? "(uninitialised)" : cfg.getDistinctId();
    }

    /** Turn telemetry on/off at runtime (persists to config). */
    public static void setEnabled(boolean enabled) {
        TelemetryConfig cfg = config;
        if (cfg == null) return;
        if (!enabled && isEnabled()) {
            capture(EventNames.EVT_TELEMETRY_OPTOUT); // last event before going dark
            flush();
        }
        cfg.setEnabled(enabled);
        TelemetrySink old = sink;
        sink = enabled ? new PostHogClient(API_KEY, HOST) : new NoopSink();
        if (old != null) old.shutdown();
    }

    public static void flush() {
        TelemetrySink s = sink;
        if (s != null) try { s.flush(); } catch (Throwable ignored) {}
    }

    public static void shutdown() {
        TelemetrySink s = sink;
        if (s != null) try { s.shutdown(); } catch (Throwable ignored) {}
        sink = null;
        context = null;
        config = null;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'fr.zeffut.multiview.telemetry.TelemetryTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/multiview/telemetry/Telemetry.java \
        src/test/java/fr/zeffut/multiview/telemetry/TelemetryTest.java
git commit -m "feat(telemetry): add fault-tolerant Telemetry facade"
```

---

## Task 9: TelemetryCommand (/mv telemetry on|off|status) + first-run notice

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/telemetry/command/TelemetryCommand.java`

- [ ] **Step 1: Implement TelemetryCommand**

```java
package fr.zeffut.multiview.telemetry.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.zeffut.multiview.telemetry.Telemetry;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/** {@code /mv telemetry on|off|status} — runtime opt-out + transparency. */
public final class TelemetryCommand {
    private TelemetryCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("mv")
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("telemetry")
                                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on")
                                        .executes(c -> set(c.getSource(), true)))
                                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                                        .executes(c -> set(c.getSource(), false)))
                                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                                        .executes(c -> status(c.getSource())))));
    }

    private static int set(FabricClientCommandSource src, boolean enabled) {
        Telemetry.setEnabled(enabled);
        src.sendFeedback(Component.literal(
                "[MultiView] Telemetry " + (enabled ? "enabled" : "disabled") + "."));
        return Command.SINGLE_SUCCESS;
    }

    private static int status(FabricClientCommandSource src) {
        src.sendFeedback(Component.literal(String.format(
                "[MultiView] Telemetry: %s | anonymous id: %s",
                Telemetry.isEnabled() ? "ON" : "OFF", Telemetry.distinctId())));
        return Command.SINGLE_SUCCESS;
    }

    /** One-time chat notice on first run. Caller decides when to show + persist. */
    public static Component firstRunNotice() {
        return Component.literal("[MultiView] Sends anonymous usage stats to improve the mod. "
                + "Run \"/mv telemetry off\" to disable.");
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/fr/zeffut/multiview/telemetry/command/TelemetryCommand.java
git commit -m "feat(telemetry): add /mv telemetry on|off|status command"
```

---

## Task 10: Wire init/shutdown/mod_loaded into MultiViewMod

**Files:**
- Modify: `src/main/java/fr/zeffut/multiview/MultiViewMod.java`

- [ ] **Step 1: Detect UI capability as a field**

In `MultiViewMod`, the `try { Class.forName("...MergeUi")... }` block currently logs on failure.
Capture the outcome in a local `String uiCapability` before initialising telemetry. Replace the
body of `onInitializeClient()` with:

```java
    @Override
    public void onInitializeClient() {
        LOGGER.info("MultiView loaded — addon pour Flashback, merge de replays multi-joueurs.");

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            InspectCommand.register(dispatcher);
            MergeCommand.register(dispatcher);
            fr.zeffut.multiview.telemetry.command.TelemetryCommand.register(dispatcher);
        });

        String uiCapability;
        try {
            Class<?> mergeUi = Class.forName("fr.zeffut.multiview.ui.MergeUi");
            mergeUi.getDeclaredMethod("register").invoke(null);
            uiCapability = detectUiVariant();
        } catch (Throwable t) {
            LOGGER.warn("MultiView UI disabled on this MC version "
                    + "({}: {}). Use /mv merge via chat instead.",
                    t.getClass().getSimpleName(),
                    t.getMessage() != null ? t.getMessage() : "(no message)");
            uiCapability = "disabled";
        }

        initTelemetry(uiCapability);
    }

    /** "modern" or "classic" depending on which MergeUi source root was compiled in. */
    private static String detectUiVariant() {
        // Both variants ship the same class name; the marker file distinguishes them.
        // The modern variant declares a static field UI_VARIANT = "modern"; classic = "classic".
        try {
            Class<?> mergeUi = Class.forName("fr.zeffut.multiview.ui.MergeUi");
            Object v = mergeUi.getDeclaredField("UI_VARIANT").get(null);
            return String.valueOf(v);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private void initTelemetry(String uiCapability) {
        try {
            java.nio.file.Path configFile = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve("multiview-telemetry.json");
            boolean devEnv = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .isDevelopmentEnvironment();
            fr.zeffut.multiview.telemetry.Telemetry.init(configFile, uiCapability, devEnv);

            // mod_loaded with whether Flashback is actually present.
            boolean flashbackPresent = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .isModLoaded("flashback");
            fr.zeffut.multiview.telemetry.Telemetry.capture(
                    fr.zeffut.multiview.telemetry.EventNames.EVT_MOD_LOADED,
                    java.util.Map.of("flashback_present", flashbackPresent));

            // First-run notice (queued to the player once they join a world).
            maybeShowFirstRunNotice(configFile);

            // Flush + close on game shutdown so buffered events aren't lost.
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
                    .register(client -> fr.zeffut.multiview.telemetry.Telemetry.shutdown());

            // NOTE: the periodic session heartbeat is wired in Task 11 (after HeartbeatScheduler
            // is created) — do NOT reference HeartbeatScheduler here yet or this task won't compile.
        } catch (Throwable t) {
            LOGGER.warn("[MultiView] telemetry setup failed: {}", t.getMessage());
        }
    }

    private void maybeShowFirstRunNotice(java.nio.file.Path configFile) {
        fr.zeffut.multiview.telemetry.TelemetryConfig cfg =
                fr.zeffut.multiview.telemetry.TelemetryConfig.load(configFile);
        if (cfg.isFirstRunNotified()) return;
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(new net.fabricmc.fabric.api.client.event.lifecycle.v1
                        .ClientTickEvents.EndTick() {
                    boolean shown = false;
                    @Override public void onEndTick(net.minecraft.client.Minecraft client) {
                        if (shown || client.player == null) return;
                        client.player.displayClientMessage(
                                fr.zeffut.multiview.telemetry.command.TelemetryCommand.firstRunNotice(),
                                false);
                        cfg.markFirstRunNotified();
                        shown = true;
                    }
                });
    }
```

> If the `ClientTickEvents.EndTick` anonymous-class form does not compile on the target Fabric API,
> use the lambda form: `ClientTickEvents.END_CLIENT_TICK.register(client -> { ... })` with a
> `boolean[] shown = {false}` captured array. Pick whichever the API version accepts; verify in Step 3.

- [ ] **Step 2: Add the UI_VARIANT marker to both MergeUi variants**

In `src/main/java-modern/fr/zeffut/multiview/ui/MergeUi.java`, add inside the class:

```java
    public static final String UI_VARIANT = "modern";
```

In `src/main/java-classic/fr/zeffut/multiview/ui/MergeUi.java`, add:

```java
    public static final String UI_VARIANT = "classic";
```

- [ ] **Step 3: Build to confirm wiring + API forms compile**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL. If the anonymous-class tick form fails, switch to the lambda form noted above and rebuild.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/fr/zeffut/multiview/MultiViewMod.java \
        src/main/java-modern/fr/zeffut/multiview/ui/MergeUi.java \
        src/main/java-classic/fr/zeffut/multiview/ui/MergeUi.java
git commit -m "feat(telemetry): init telemetry + mod_loaded + first-run notice in mod entrypoint"
```

---

## Task 11: HeartbeatScheduler (session_heartbeat)

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/telemetry/HeartbeatScheduler.java`

- [ ] **Step 1: Implement HeartbeatScheduler**

```java
package fr.zeffut.multiview.telemetry;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Fires {@code session_heartbeat} every 15 min while the game runs. Daemon thread. */
public final class HeartbeatScheduler {
    private static final ScheduledExecutorService EXEC =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "multiview-telemetry-heartbeat");
                t.setDaemon(true);
                return t;
            });
    private static final AtomicInteger COUNT = new AtomicInteger();
    private static volatile boolean started = false;

    private HeartbeatScheduler() {}

    public static synchronized void start() {
        if (started) return;
        started = true;
        EXEC.scheduleAtFixedRate(() ->
                Telemetry.capture(EventNames.EVT_SESSION_HEARTBEAT,
                        Map.of("heartbeat_index", COUNT.incrementAndGet())),
                15, 15, TimeUnit.MINUTES);
    }
}
```

- [ ] **Step 2: Wire HeartbeatScheduler.start() into MultiViewMod**

In `MultiViewMod.initTelemetry(...)`, immediately after the `CLIENT_STOPPING` registration
(the spot marked by the NOTE comment from Task 10), add:

```java
            // Periodic session heartbeat.
            fr.zeffut.multiview.telemetry.HeartbeatScheduler.start();
```

- [ ] **Step 3: Build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/fr/zeffut/multiview/telemetry/HeartbeatScheduler.java \
        src/main/java/fr/zeffut/multiview/MultiViewMod.java
git commit -m "feat(telemetry): add 15-min session_heartbeat scheduler + wiring"
```

---

## Task 12: Instrument the merge pipeline

**Files:**
- Modify: `src/main/java/fr/zeffut/multiview/merge/command/MergeCommand.java`

> Strategy: instrument at the call site (MergeCommand) — `merge_started` before the run,
> `merge_completed`/`merge_failed` after, and wrap the `Consumer<String> progress` to time each
> phase and emit `merge_phase_completed`. This keeps `MergeOrchestrator` itself telemetry-free.

- [ ] **Step 1: Emit merge_started, phase timings, and merge_completed/failed**

In `MergeCommand.execute(...)`, replace the `EXECUTOR.submit(() -> { ... })` body with:

```java
        final long startNanos = System.nanoTime();
        final java.util.Map<String, Object> startProps = new java.util.HashMap<>();
        startProps.put("source_count", sources.size());
        startProps.put("input_bytes_total", totalSize(sources));
        startProps.put("force", options.force());
        startProps.put("tick_overrides", options.tickOverrides().size());
        startProps.put("trigger", "command");
        fr.zeffut.multiview.telemetry.Telemetry.capture(
                fr.zeffut.multiview.telemetry.EventNames.EVT_MERGE_STARTED, startProps);

        EXECUTOR.submit(() -> {
            // Phase timer: emit merge_phase_completed for the *previous* phase each time a new
            // phase boundary is reported.
            final long[] phaseStart = { System.nanoTime() };
            final String[] phaseName = { null };
            java.util.function.Consumer<String> progress = phase -> {
                long now = System.nanoTime();
                if (phaseName[0] != null) {
                    fr.zeffut.multiview.telemetry.Telemetry.capture(
                            fr.zeffut.multiview.telemetry.EventNames.EVT_MERGE_PHASE_COMPLETED,
                            java.util.Map.of(
                                    "phase", phaseName[0],
                                    "duration_ms", (now - phaseStart[0]) / 1_000_000L));
                }
                phaseName[0] = phase;
                phaseStart[0] = now;
                Minecraft.getInstance().execute(() ->
                        source.sendFeedback(Component.literal("[MultiView] " + phase)));
            };

            try {
                MergeReport report = MergeOrchestrator.run(options, progress);
                // last phase
                if (phaseName[0] != null) {
                    fr.zeffut.multiview.telemetry.Telemetry.capture(
                            fr.zeffut.multiview.telemetry.EventNames.EVT_MERGE_PHASE_COMPLETED,
                            java.util.Map.of("phase", phaseName[0],
                                    "duration_ms", (System.nanoTime() - phaseStart[0]) / 1_000_000L));
                }
                Path destZip = dest.resolveSibling(dest.getFileName() + ".zip");
                long outBytes = java.nio.file.Files.exists(destZip)
                        ? java.nio.file.Files.size(destZip) : -1L;

                java.util.Map<String, Object> done = new java.util.HashMap<>();
                done.put("duration_ms", (System.nanoTime() - startNanos) / 1_000_000L);
                done.put("output_bytes", outBytes);
                done.put("source_count", sources.size());
                done.put("entities_merged_uuid", report.stats.entitiesMergedByUuid);
                done.put("entities_merged_heuristic", report.stats.entitiesMergedByHeuristic);
                done.put("entities_ambiguous", report.stats.entitiesAmbiguousMerged);
                done.put("blocks_overwrites", report.stats.blocksLwwOverwrites);
                done.put("blocks_conflicts", report.stats.blocksLwwConflicts);
                done.put("globals_deduped", report.stats.globalPacketsDeduped);
                done.put("merged_total_ticks", report.mergedTotalTicks);
                done.put("alignment_strategy", String.valueOf(report.alignmentStrategy));
                done.put("warnings", report.warnings.size());
                fr.zeffut.multiview.telemetry.Telemetry.capture(
                        fr.zeffut.multiview.telemetry.EventNames.EVT_MERGE_COMPLETED, done);

                Minecraft.getInstance().execute(() ->
                        source.sendFeedback(Component.literal(String.format(
                                "[MultiView] Done → %s | %d entities merged, %d blocks overwritten, %d globals deduped.",
                                destZip.toAbsolutePath(),
                                report.stats.entitiesMergedByUuid + report.stats.entitiesMergedByHeuristic,
                                report.stats.blocksLwwOverwrites,
                                report.stats.globalPacketsDeduped))));
            } catch (Throwable t) {
                fr.zeffut.multiview.telemetry.Telemetry.capture(
                        fr.zeffut.multiview.telemetry.EventNames.EVT_MERGE_FAILED,
                        java.util.Map.of(
                                "phase", String.valueOf(phaseName[0]),
                                "error_type", t.getClass().getSimpleName(),
                                "error_message",
                                    fr.zeffut.multiview.telemetry.Sanitizer.redactMessage(
                                        String.valueOf(t.getMessage())),
                                "duration_ms", (System.nanoTime() - startNanos) / 1_000_000L,
                                "source_count", sources.size()));
                LOG.error("[MultiView] Merge failed", t);
                Minecraft.getInstance().execute(() ->
                        source.sendError(Component.literal("[MultiView] Merge failed: " + t.getMessage())));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Sum of source sizes (file size for zips, recursive for folders); -1 on error. */
    private static long totalSize(List<Path> sources) {
        try {
            long total = 0;
            for (Path s : sources) {
                if (Files.isRegularFile(s)) {
                    total += Files.size(s);
                } else if (Files.isDirectory(s)) {
                    try (Stream<Path> w = Files.walk(s)) {
                        total += w.filter(Files::isRegularFile).mapToLong(p -> {
                            try { return Files.size(p); } catch (Exception e) { return 0L; }
                        }).sum();
                    }
                }
            }
            return total;
        } catch (Exception e) {
            return -1L;
        }
    }
```

> The `command_used` event (Task 13) is emitted at the start of `execute(...)`, before any
> path validation, so even refused merges are counted.

- [ ] **Step 2: Build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/command/MergeCommand.java
git commit -m "feat(telemetry): instrument merge pipeline (started/phase/completed/failed)"
```

---

## Task 13: Instrument commands & inspect

**Files:**
- Modify: `src/main/java/fr/zeffut/multiview/merge/command/MergeCommand.java`
- Modify: `src/main/java/fr/zeffut/multiview/inspect/InspectCommand.java`

- [ ] **Step 1: command_used for merge**

At the very top of `MergeCommand.execute(...)` (before resolving `replayRoot`), add:

```java
        fr.zeffut.multiview.telemetry.Telemetry.capture(
                fr.zeffut.multiview.telemetry.EventNames.EVT_COMMAND_USED,
                java.util.Map.of("command", "merge", "source_count", sourceNames.size()));
```

- [ ] **Step 2: command_used + inspect_performed for inspect**

Read `InspectCommand.java` to find its `execute(...)` entry method. At the start of that method, add:

```java
        fr.zeffut.multiview.telemetry.Telemetry.capture(
                fr.zeffut.multiview.telemetry.EventNames.EVT_COMMAND_USED,
                java.util.Map.of("command", "inspect"));
```

After a successful inspection (where the inspected replay's metadata/size is known — locate the
point where the command prints its result), add an `inspect_performed` capture. Use the values
already computed there; if only a tick count or size is available, send those:

```java
        fr.zeffut.multiview.telemetry.Telemetry.capture(
                fr.zeffut.multiview.telemetry.EventNames.EVT_INSPECT_PERFORMED,
                java.util.Map.of("ok", true));
```

> Keep the inspect props to non-PII aggregates only (counts, sizes, booleans). Never send the
> inspected folder name — use `Sanitizer.basename(...)` only if a name is truly needed.

- [ ] **Step 3: Build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/command/MergeCommand.java \
        src/main/java/fr/zeffut/multiview/inspect/InspectCommand.java
git commit -m "feat(telemetry): instrument command_used + inspect_performed"
```

---

## Task 14: Instrument the UI (both variants)

**Files:**
- Modify: `src/main/java-modern/fr/zeffut/multiview/ui/MergeUi.java`
- Modify: `src/main/java-classic/fr/zeffut/multiview/ui/MergeUi.java`

> Apply the SAME edits to both files (they share class/method names). Use `Telemetry.capture`
> with the events: `ui_opened` when the merge button is added to the screen, `ui_file_selected`
> when selection changes, `ui_merge_clicked` when the merge starts, and
> `overlap_validation_failed` when `OverlapValidator` reports no overlap.

- [ ] **Step 1: ui_opened**

In the method that builds + adds the merge button to the screen (around the `addWidgetToScreen`
call), after the button is added, insert:

```java
        fr.zeffut.multiview.telemetry.Telemetry.capture(
                fr.zeffut.multiview.telemetry.EventNames.EVT_UI_OPENED);
```

- [ ] **Step 2: ui_file_selected + overlap_validation_failed**

In the selection-update method (the one that sets `mergeButton.active` based on overlap — around
the `multiview.button.merge_selected.no_overlap` tooltip branch), add at the start of the method:

```java
        fr.zeffut.multiview.telemetry.Telemetry.capture(
                fr.zeffut.multiview.telemetry.EventNames.EVT_UI_FILE_SELECTED,
                java.util.Map.of("selected_count", n));
```

And in the branch that disables the button because timelines don't overlap (the
`no_overlap` tooltip branch), add:

```java
            fr.zeffut.multiview.telemetry.Telemetry.capture(
                    fr.zeffut.multiview.telemetry.EventNames.EVT_OVERLAP_VALIDATION_FAILED,
                    java.util.Map.of("selected_count", n));
```

> `n` is the selected-count variable already present in that method. If the variable has a
> different name in one variant, use that variant's name.

- [ ] **Step 3: ui_merge_clicked**

In the method that starts the merge from the UI (where `outputName` is built and
`MergeOrchestrator.run(...)` is submitted), add before submitting:

```java
        fr.zeffut.multiview.telemetry.Telemetry.capture(
                fr.zeffut.multiview.telemetry.EventNames.EVT_UI_MERGE_CLICKED,
                java.util.Map.of("trigger", "ui"));
```

- [ ] **Step 4: merge_cancelled**

Locate the cancel handler (the recently-added cancel button — search both variants and
`MergeProgressScreen.java` for `cancel`). At the point where the user cancels an in-progress merge,
add:

```java
        fr.zeffut.multiview.telemetry.Telemetry.capture(
                fr.zeffut.multiview.telemetry.EventNames.EVT_MERGE_CANCELLED,
                java.util.Map.of("trigger", "ui"));
```

> If the cancel logic lives in `MergeProgressScreen` rather than `MergeUi`, add the capture there
> instead and include that file in the commit. If a phase name is available at cancel time, add it
> as a `"phase"` property; otherwise omit it.

- [ ] **Step 5: Build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java-modern/fr/zeffut/multiview/ui/MergeUi.java \
        src/main/java-classic/fr/zeffut/multiview/ui/MergeUi.java \
        src/main/java-modern/fr/zeffut/multiview/ui/MergeProgressScreen.java \
        src/main/java-classic/fr/zeffut/multiview/ui/MergeProgressScreen.java
git commit -m "feat(telemetry): instrument merge UI events incl. cancel (both variants)"
```

---

## Task 15: Error capture (sanitized)

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/telemetry/ErrorReporter.java`

> Approach: a small helper the mod calls from its own catch blocks to report a handled error as a
> `mod_error` event with sanitized type/message/stack. We do NOT install a global
> `Thread.UncaughtExceptionHandler` (that would capture unrelated Minecraft/other-mod crashes and
> risk PII); we only report errors originating in MultiView code paths.

- [ ] **Step 1: Implement ErrorReporter**

```java
package fr.zeffut.multiview.telemetry;

import java.util.Map;

/** Reports a handled MultiView error to telemetry, fully sanitized. */
public final class ErrorReporter {
    private ErrorReporter() {}

    public static void report(String where, Throwable t) {
        if (t == null) return;
        Telemetry.capture(EventNames.EVT_MOD_ERROR, Map.of(
                "where", where,
                "error_type", t.getClass().getName(),
                "error_message", Sanitizer.redactMessage(String.valueOf(t.getMessage())),
                "stack", Sanitizer.stackSummary(t, 8)));
    }
}
```

- [ ] **Step 2: Call it from the merge failure path**

In `MergeCommand.execute(...)`, inside the `catch (Throwable t)` of the merge submit block
(added in Task 12), add after the `EVT_MERGE_FAILED` capture:

```java
                fr.zeffut.multiview.telemetry.ErrorReporter.report("merge", t);
```

- [ ] **Step 3: Build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/fr/zeffut/multiview/telemetry/ErrorReporter.java \
        src/main/java/fr/zeffut/multiview/merge/command/MergeCommand.java
git commit -m "feat(telemetry): add sanitized ErrorReporter for handled mod errors"
```

---

## Task 16: Documentation (README + CHANGELOG)

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add a "Telemetry & privacy" section to README.md**

Append this section to `README.md` (after the features/usage sections):

```markdown
## Telemetry & privacy

MultiView sends **anonymous** usage statistics via [PostHog](https://posthog.com) (EU servers)
to help improve the mod. It is **enabled by default** but fully optional.

**What is collected:** mod/Minecraft/Flashback versions, OS and Java version, locale, which
features are used, merge counts/durations/sizes/outcomes, and sanitized error reports.

**What is never collected:** player names or UUIDs, world/server names, file paths (only file
names/sizes), or any replay contents. An anonymous random ID is used — it is not linked to your
Minecraft account.

**How to disable:**
- In game: `/mv telemetry off` (and `/mv telemetry status` to check).
- Or set the JVM flag `-Dmultiview.telemetry=false`.
- Telemetry is automatically disabled in Fabric development environments.
```

- [ ] **Step 2: Add a CHANGELOG entry**

Add to the top of the unreleased/next-version section in `CHANGELOG.md`:

```markdown
### Added
- Anonymous, opt-out telemetry (PostHog, EU) to guide development: merge metrics,
  feature usage, versions, and sanitized error reports. Disable with `/mv telemetry off`
  or `-Dmultiview.telemetry=false`. See the README "Telemetry & privacy" section.
```

- [ ] **Step 3: Commit**

```bash
git add README.md CHANGELOG.md
git commit -m "docs: document telemetry & privacy (README + CHANGELOG)"
```

---

## Task 17: Full verification

- [ ] **Step 1: Run the entire test suite**

Run: `./gradlew clean test`
Expected: PASS — all existing tests + new telemetry tests green.

- [ ] **Step 2: Build the shippable jar**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify relocation + no key leakage surprises in the jar**

Run: `unzip -l build/libs/multiview-*.jar | grep -E 'fr/zeffut/multiview/libs/posthog' | head`
Expected: relocated PostHog classes listed.

Run: `unzip -l build/libs/multiview-*.jar | grep -c 'com/posthog/java/PostHog.class'`
Expected: `0`.

- [ ] **Step 4: Manual smoke test (in dev client)**

Run the dev client (`./gradlew runClient`), join a singleplayer world. Because dev env disables
telemetry, confirm the log shows `telemetry disabled (config/prop/dev)` and `/mv telemetry status`
reports `OFF`. This confirms wiring works without sending dev events.

To exercise the live path once, temporarily run with `-Dmultiview.telemetry=true` AND comment out
the dev-env check is NOT needed — instead verify in PostHog's "Activity" (live events) that a
`mod_loaded` event with `app=multiview` arrives when running a non-dev build. (Optional; requires
a packaged jar in a real instance.)

- [ ] **Step 5: Final commit if any fixups were needed**

```bash
git add -A
git commit -m "chore(telemetry): verification fixups"
```

---

## Notes for the implementer

- **Never throw from telemetry.** Every capture path is already guarded; keep it that way.
- **Never add PII.** When adding a new event, route any string that could contain a path/name
  through `Sanitizer`. Counts, sizes, booleans, durations, and enum-like strings are safe.
- **Relocation gotcha:** source code imports the ORIGINAL `com.posthog.java.*` names; Shadow
  rewrites them to `fr.zeffut.multiview.libs.posthog.*` at package time. Do not import the
  relocated package directly.
- **Key swap:** if a dedicated PostHog project is created later, change only `Telemetry.API_KEY`.
