package fr.zeffut.multiview.test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;

import fr.zeffut.multiview.merge.MergeOptions;
import fr.zeffut.multiview.merge.MergeOrchestrator;
import fr.zeffut.multiview.merge.MergeReport;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Autonomous test harness driven by a marker file ({@link #CONFIG_FILE}). Allows me to
 * iterate on merge fixes without manual MC interaction.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>{@link ClientLifecycleEvents#CLIENT_STARTED} fires — check for the config file.
 *       If absent, the harness stays dormant (normal user session).</li>
 *   <li>Parse the JSON config: list of source replay names (zips in
 *       {@code <gameDir>/flashback/replays/}), output base name, number of playback ticks.</li>
 *   <li>Run {@link MergeOrchestrator#run(MergeOptions, java.util.function.Consumer)} on a
 *       background thread.</li>
 *   <li>On merge success, call {@link Flashback#openReplayWorld(Path)} from the client
 *       thread to start playback of the merged zip.</li>
 *   <li>Tick counter ticks until {@code playTicks} elapses or {@code playTimeoutSeconds}
 *       wall-clock seconds pass. During play, capture chat events
 *       ({@code ClientReceiveMessageEvents.GAME}) and log every {@code "joined the game"}
 *       line.</li>
 *   <li>Write {@link #RESULT_FILE} as JSON with: merge stats, chat join counts per player,
 *       crash report files created during the run, and pass/fail verdict.</li>
 *   <li>Call {@link MinecraftClient#scheduleStop()} so the JVM exits with code 0.</li>
 * </ol>
 *
 * <h2>Externally</h2>
 * <pre>{@code
 *   cat > run/.multiview-test.json <<EOF
 *   { "sources": ["a.zip", "b.zip"], "output": "test", "playTicks": 5000 }
 *   EOF
 *   ./gradlew runClient
 *   cat run/.multiview-test-result.json
 * }</pre>
 *
 * The harness is opt-in by file presence so it never disturbs normal user sessions.
 */
public final class TestHarness implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(TestHarness.class);
    private static final String CONFIG_FILE = ".multiview-test.json";
    private static final String RESULT_FILE = ".multiview-test-result.json";

    private enum Phase { IDLE, MERGE, OPEN, PLAY, DONE }
    private volatile Phase phase = Phase.IDLE;

    private Config config;
    private Path replayFolder;
    private Path resultFile;
    private long startTimeMs;
    private long playStartTimeMs;
    private final AtomicInteger playTickCounter = new AtomicInteger(0);
    private int lastReplayTickSeen = -1;
    private boolean loggedUnpauseOnce;
    private boolean loggedUnpauseFailure;
    private final AtomicBoolean mergeFinished = new AtomicBoolean(false);
    private volatile MergeReport mergeReport;
    private volatile String mergeError;
    private volatile Path mergedZipPath;

    private final List<String> phaseLog = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> joinedCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> leftCounts = new ConcurrentHashMap<>();
    private final List<String> recentMessages = Collections.synchronizedList(new ArrayList<>());
    private final List<String> errorMessages = Collections.synchronizedList(new ArrayList<>());
    private int chatMessagesReceived;

    private final ExecutorService mergeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "multiview-testharness-merge");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(this::onClientStarted);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        // Subscribe to both GAME (system messages — "joined the game") and CHAT
        // (player-to-player) to catch every line that lands in the chat HUD.
        ClientReceiveMessageEvents.GAME.register(this::onChat);
        // CHAT signature varies between fabric-api versions; GAME alone covers "joined the game".
    }

    private void onClientStarted(MinecraftClient mc) {
        try {
            replayFolder = Flashback.getReplayFolder();
            Path cfg = replayFolder.getParent().getParent().resolve(CONFIG_FILE);
            // gameDir / flashback / replays  → gameDir = parent.parent
            if (!Files.isRegularFile(cfg)) {
                LOG.info("[TestHarness] no {} found — dormant.", CONFIG_FILE);
                return;
            }
            resultFile = cfg.resolveSibling(RESULT_FILE);
            try (Reader r = Files.newBufferedReader(cfg)) {
                config = new Gson().fromJson(r, Config.class);
            }
            if (config == null || config.sources == null || config.sources.size() < 2) {
                LOG.warn("[TestHarness] config malformed (need ≥2 sources). Aborting.");
                return;
            }
            startTimeMs = System.currentTimeMillis();
            phaseLog.add(timestamp() + " STARTED with " + config.sources.size() + " sources");
            LOG.info("[TestHarness] activated. config={}", config);
            phase = Phase.MERGE;
            // Run the merge on a background thread so we don't block the client thread.
            mergeExecutor.submit(this::runMerge);
        } catch (Throwable t) {
            LOG.error("[TestHarness] init failed", t);
            errorMessages.add("init: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            phase = Phase.DONE;
            writeResultFile();
        }
    }

    private void runMerge() {
        try {
            List<Path> sourcePaths = new ArrayList<>();
            for (String name : config.sources) {
                Path p = replayFolder.resolve(name);
                if (!Files.exists(p)) {
                    throw new IOException("source replay not found: " + p);
                }
                sourcePaths.add(p);
            }
            String output = config.output != null && !config.output.isBlank()
                    ? config.output
                    : "harness_output";
            Path destBase = replayFolder.resolve(output);
            MergeOptions options = new MergeOptions(sourcePaths, destBase, Map.of(), true);
            phaseLog.add(timestamp() + " MERGE starting");
            mergeReport = MergeOrchestrator.run(options, p ->
                    phaseLog.add(timestamp() + " merge: " + p));
            mergedZipPath = destBase.resolveSibling(destBase.getFileName() + ".zip");
            phaseLog.add(timestamp() + " MERGE done → " + mergedZipPath.getFileName());
        } catch (Throwable t) {
            mergeError = t.getClass().getSimpleName() + ": " + t.getMessage();
            phaseLog.add(timestamp() + " MERGE FAILED: " + mergeError);
            LOG.error("[TestHarness] merge failed", t);
        } finally {
            mergeFinished.set(true);
        }
    }

    private void onTick(MinecraftClient mc) {
        // Global watchdog: regardless of phase, force-quit if total elapsed exceeds the
        // configured max. Prevents a stuck MC window when something goes wrong outside
        // any of the per-phase handlers.
        if (phase != Phase.IDLE && phase != Phase.DONE) {
            int maxMin = config != null && config.maxRunMinutes != null
                    ? config.maxRunMinutes : 20;
            if (System.currentTimeMillis() - startTimeMs > maxMin * 60_000L) {
                errorMessages.add("watchdog timeout: " + maxMin + " minutes elapsed in phase=" + phase);
                phaseLog.add(timestamp() + " WATCHDOG firing — force quit");
                phase = Phase.DONE;
                finalizeAndQuit(mc);
                return;
            }
        }

        if (phase == Phase.MERGE && mergeFinished.get()) {
            if (mergeError != null) {
                errorMessages.add("merge: " + mergeError);
                phase = Phase.DONE;
                finalizeAndQuit(mc);
                return;
            }
            phase = Phase.OPEN;
            phaseLog.add(timestamp() + " OPENING " + mergedZipPath.getFileName());
            try {
                // GameOptions.pauseOnLostFocus is a public boolean field in 1.21.11.
                try {
                    java.lang.reflect.Field f = mc.options.getClass().getField("pauseOnLostFocus");
                    f.setBoolean(mc.options, false);
                    phaseLog.add(timestamp() + " disabled GameOptions.pauseOnLostFocus");
                } catch (Throwable t) {
                    phaseLog.add(timestamp() + " could not disable pauseOnLostFocus: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
                Flashback.openReplayWorld(mergedZipPath);
            } catch (Throwable t) {
                errorMessages.add("open: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                LOG.error("[TestHarness] openReplayWorld failed", t);
                phase = Phase.DONE;
                finalizeAndQuit(mc);
                return;
            }
            playStartTimeMs = System.currentTimeMillis();
            phase = Phase.PLAY;
            int playSec = config.playSeconds != null ? config.playSeconds : 60;
            phaseLog.add(timestamp() + " PLAYING (target=" + playSec + "s wall-clock)");
            return;
        }

        if (phase == Phase.PLAY) {
            // Flashback opens replays paused by default — unpause once per tick if the
            // ReplayServer is available so playback actually progresses. The replay tick
            // counter we record is read from the server (real progression), not the client
            // tick handler (which fires regardless of pause state).
            if (Flashback.isInReplay() && mc.getServer() instanceof ReplayServer rs) {
                // Force-unpause both the integrated server (which gates server-side ticking
                // via paused field) AND Flashback's replayPaused flag. The replay's tick()
                // re-reads paused state from isPaused() each tick, so we must keep both
                // false continuously until replay completes.
                try {
                    java.lang.reflect.Field paused = rs.getClass().getSuperclass().getDeclaredField("paused");
                    paused.setAccessible(true);
                    if (paused.getBoolean(rs)) {
                        paused.setBoolean(rs, false);
                        if (!loggedUnpauseOnce) {
                            phaseLog.add(timestamp() + " forced IntegratedServer.paused=false");
                            loggedUnpauseOnce = true;
                        }
                    }
                } catch (Throwable t) {
                    if (!loggedUnpauseFailure) {
                        phaseLog.add(timestamp() + " could not unpause IntegratedServer: " + t.getMessage());
                        loggedUnpauseFailure = true;
                    }
                }
                if (rs.replayPaused) {
                    rs.replayPaused = false;
                }
                // Flashback splits the tick rate into "auto" (desiredTickRate) and "manual"
                // (desiredTickRateManual). The UI's speed slider writes the manual field,
                // so we mirror that: manual=true.
                rs.setDesiredTickRate(200f, true);
                // Also raise the underlying MC server's tick rate — /tick rate 200 — so the
                // server's processing loop doesn't cap us at vanilla 20 Hz.
                try {
                    rs.getTickManager().setTickRate(200f);
                } catch (Throwable ignore) {}
                int replayTick = rs.getReplayTick();
                if (replayTick != lastReplayTickSeen) {
                    lastReplayTickSeen = replayTick;
                    int n = playTickCounter.incrementAndGet();
                    if (n == 1 || n % 200 == 0) {
                        phaseLog.add(timestamp() + " replayTick=" + replayTick + " observed=" + n);
                    }
                }
            }
            // Play duration is governed by WALL-CLOCK seconds — easier to reason about than
            // ticks (the client's tick rate during early replay loading can exceed 20 Hz).
            // This guarantees we see the bulk-PIU "joined the game" broadcasts that fire
            // in the first few seconds of replay.
            long elapsedMs = System.currentTimeMillis() - playStartTimeMs;
            int playSeconds = config.playSeconds != null ? config.playSeconds : 60;
            if (elapsedMs > playSeconds * 1000L) {
                phase = Phase.DONE;
                phaseLog.add(timestamp() + " reached " + playSeconds + "s playback ("
                        + playTickCounter.get() + " ticks observed) — finalizing");
                finalizeAndQuit(mc);
                return;
            }
            // Hard safety timeout for the play phase.
            int timeoutSec = config.playTimeoutSeconds != null ? config.playTimeoutSeconds : 600;
            if (elapsedMs > timeoutSec * 1000L) {
                phase = Phase.DONE;
                phaseLog.add(timestamp() + " play timeout " + timeoutSec + "s — finalizing");
                errorMessages.add("play timeout " + timeoutSec + "s");
                finalizeAndQuit(mc);
            }
        }
    }

    private void onChat(Text message, boolean overlay) {
        if (overlay) return;
        if (phase == Phase.IDLE || phase == Phase.DONE) return;
        String s = message.getString();
        if (s == null) return;
        chatMessagesReceived++;
        // Keep the first 100 distinct messages we see for debugging (no flood, ring-style).
        synchronized (recentMessages) {
            if (recentMessages.size() < 100) recentMessages.add("[" + phase + " t+" + playTickCounter.get() + "] " + s);
        }
        if (s.endsWith(" joined the game")) {
            String player = s.substring(0, s.length() - " joined the game".length());
            joinedCounts.merge(player, 1, Integer::sum);
        } else if (s.endsWith(" left the game")) {
            String player = s.substring(0, s.length() - " left the game".length());
            leftCounts.merge(player, 1, Integer::sum);
        }
    }

    private void finalizeAndQuit(MinecraftClient mc) {
        writeResultFile();
        // schedule stop on the next render frame
        mc.scheduleStop();
    }

    /**
     * Scan {@code logs/latest.log} for "X joined the game" / "left the game" lines so we
     * count duplicates even when Fabric chat events are bypassed by Flashback's
     * FakePlayer dispatch path. Filters to lines after our merge started so we don't
     * count messages from previous sessions.
     */
    private void scanLogForChatLines() {
        try {
            Path logFile = Path.of("logs", "latest.log");
            if (!Files.isRegularFile(logFile)) return;
            List<String> lines = Files.readAllLines(logFile);
            for (String line : lines) {
                if (line.contains(" joined the game") && line.contains("[CHAT]")) {
                    int chatIdx = line.indexOf("[CHAT] ");
                    if (chatIdx < 0) continue;
                    String msg = line.substring(chatIdx + "[CHAT] ".length());
                    if (msg.endsWith(" joined the game")) {
                        String player = msg.substring(0, msg.length() - " joined the game".length());
                        joinedCounts.merge(player, 1, Integer::sum);
                        synchronized (recentMessages) {
                            if (recentMessages.size() < 100) recentMessages.add("[log] " + msg);
                        }
                    }
                } else if (line.contains(" left the game") && line.contains("[CHAT]")) {
                    int chatIdx = line.indexOf("[CHAT] ");
                    if (chatIdx < 0) continue;
                    String msg = line.substring(chatIdx + "[CHAT] ".length());
                    if (msg.endsWith(" left the game")) {
                        String player = msg.substring(0, msg.length() - " left the game".length());
                        leftCounts.merge(player, 1, Integer::sum);
                    }
                }
            }
            chatMessagesReceived = joinedCounts.values().stream().mapToInt(Integer::intValue).sum()
                    + leftCounts.values().stream().mapToInt(Integer::intValue).sum();
        } catch (Throwable t) {
            errorMessages.add("log scan failed: " + t.getMessage());
        }
    }

    private synchronized void writeResultFile() {
        // Always scan the log file before finalizing — covers messages that Fabric events
        // might miss in Flashback's replay context.
        scanLogForChatLines();

        if (resultFile == null) return;
        try {
            JsonObject root = new JsonObject();
            root.addProperty("startTime", startTimeMs);
            root.addProperty("durationMs", System.currentTimeMillis() - startTimeMs);
            root.addProperty("phase", phase.name());
            root.addProperty("playTicksObserved", playTickCounter.get());
            root.addProperty("mergeSucceeded", mergeReport != null && mergeError == null);

            if (mergeReport != null) {
                JsonObject stats = new JsonObject();
                stats.addProperty("entitiesMergedByUuid", mergeReport.stats.entitiesMergedByUuid);
                stats.addProperty("blocksLwwOverwrites", mergeReport.stats.blocksLwwOverwrites);
                stats.addProperty("blocksLwwConflicts", mergeReport.stats.blocksLwwConflicts);
                stats.addProperty("globalPacketsDeduped", mergeReport.stats.globalPacketsDeduped);
                stats.addProperty("mergedTotalTicks", mergeReport.mergedTotalTicks);
                stats.addProperty("outOfOrderPackets", mergeReport.stats.outOfOrderPackets);
                root.add("mergeStats", stats);
            }
            if (mergedZipPath != null) {
                root.addProperty("mergedZip", mergedZipPath.toString());
            }

            // Chat join counts — flag SUSPICIOUS duplicates only. A player can legitimately
            // join multiple times in the original session (leave-rejoin cycles). Only count
            // as suspicious when joins > leaves+1 (more joins than disconnections justify).
            JsonObject joins = new JsonObject();
            int suspiciousDuplicates = 0;
            int legitMultiJoin = 0;
            int totalJoinExcess = 0;
            for (Map.Entry<String, Integer> e : new HashMap<>(joinedCounts).entrySet()) {
                joins.addProperty(e.getKey(), e.getValue());
                int j = e.getValue();
                int l = leftCounts.getOrDefault(e.getKey(), 0);
                if (j > 1) {
                    // Legit pattern: joins == leaves (left, currently absent) or joins == leaves+1
                    // (currently in). Anything else is a dedup miss.
                    if (j == l || j == l + 1) legitMultiJoin++;
                    else {
                        suspiciousDuplicates++;
                        totalJoinExcess += (j - Math.max(l + 1, 1));
                    }
                }
            }
            root.add("joinedCounts", joins);
            root.addProperty("duplicateJoinPlayers", suspiciousDuplicates);
            root.addProperty("legitMultiJoin", legitMultiJoin);
            root.addProperty("totalJoinExcess", totalJoinExcess);

            JsonObject leaves = new JsonObject();
            for (Map.Entry<String, Integer> e : new HashMap<>(leftCounts).entrySet()) {
                leaves.addProperty(e.getKey(), e.getValue());
            }
            root.add("leftCounts", leaves);
            root.addProperty("chatMessagesReceived", chatMessagesReceived);
            com.google.gson.JsonArray recents = new com.google.gson.JsonArray();
            synchronized (recentMessages) {
                for (String m : recentMessages) recents.add(m);
            }
            root.add("recentMessages", recents);

            // Crash report files written during this run (so I can know if MC crashed
            // on a separate thread, even if the harness itself didn't see it).
            List<String> crashReports = new ArrayList<>();
            Path crashDir = resultFile.resolveSibling("crash-reports");
            if (Files.isDirectory(crashDir)) {
                long since = startTimeMs;
                try (var s = Files.list(crashDir)) {
                    s.filter(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis() >= since; }
                        catch (IOException ex) { return false; }
                    }).forEach(p -> crashReports.add(p.getFileName().toString()));
                }
            }
            com.google.gson.JsonArray crashes = new com.google.gson.JsonArray();
            crashReports.forEach(crashes::add);
            root.add("crashReportsSinceStart", crashes);

            com.google.gson.JsonArray phases = new com.google.gson.JsonArray();
            synchronized (phaseLog) {
                for (String line : phaseLog) phases.add(line);
            }
            root.add("phaseLog", phases);

            com.google.gson.JsonArray errors = new com.google.gson.JsonArray();
            synchronized (errorMessages) {
                for (String e : errorMessages) errors.add(e);
            }
            root.add("errors", errors);

            // Verdict: PASS if no crashes, no errors, mergeSucceeded, and no SUSPICIOUS
            // duplicate joins (legit leave/rejoin cycles are accepted).
            boolean pass = mergeError == null
                    && errorMessages.isEmpty()
                    && crashReports.isEmpty()
                    && suspiciousDuplicates == 0;
            root.addProperty("verdict", pass ? "PASS" : "FAIL");

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (var w = Files.newBufferedWriter(resultFile)) {
                w.write(gson.toJson(root));
            }
            LOG.info("[TestHarness] result written → {} (verdict={})",
                    resultFile.getFileName(), pass ? "PASS" : "FAIL");
        } catch (Throwable t) {
            LOG.error("[TestHarness] failed to write result", t);
        }
    }

    private static String timestamp() {
        long t = System.currentTimeMillis();
        return String.format("[t+%4ds]", (t % 1_000_000) / 1000);
    }

    /** JSON shape of {@code .multiview-test.json}. */
    private static final class Config {
        List<String> sources;
        String output;
        Integer playTicks;       // legacy, ignored — replaced by playSeconds
        Integer playSeconds;     // wall-clock playback duration, default 60s
        Integer playTimeoutSeconds; // hard cap, default 600
        Integer maxRunMinutes;   // global watchdog, default 20

        @Override
        public String toString() {
            return "Config{sources=" + sources + ", output=" + output
                    + ", playTicks=" + playTicks + ", playTimeoutSeconds=" + playTimeoutSeconds
                    + ", maxRunMinutes=" + maxRunMinutes + "}";
        }
    }
}
