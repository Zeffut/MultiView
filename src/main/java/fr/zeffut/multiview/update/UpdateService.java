package fr.zeffut.multiview.update;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.zeffut.multiview.telemetry.Telemetry;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The whole auto-update pipeline, fully invisible to the player. Mapping-agnostic: references no
 * Minecraft class (loader facts come from {@link Platform}).
 *
 * <p>Flow, on a background daemon thread started at client init:
 * <ol>
 *   <li><b>Reconcile</b> — read {@code .autoupdate/state.json} from the previous session: emit
 *       {@code upd_update_applied} for swaps that landed, keep failed swaps pending for retry;</li>
 *   <li><b>Scan</b> — SHA-512 every jar in {@code mods/};</li>
 *   <li><b>Resolve</b> — one batch Modrinth lookup ({@code /version_files/update}) returns the
 *       latest version per local file for the current loader + MC version; only projects owned by
 *       the configured account (default {@code Zeffut}) are eligible unless {@code update_all};</li>
 *   <li><b>Stage</b> — download new jars into {@code .autoupdate/staging/} and verify their
 *       SHA-512 (never two copies of one mod inside {@code mods/});</li>
 *   <li><b>Swap at shutdown</b> — a JVM shutdown hook deletes the old jar then moves the staged
 *       one into {@code mods/}; if the old jar is locked (Windows), a detached
 *       {@link JanitorMain} process finishes the swap after the game exits.</li>
 * </ol>
 *
 * <p>Config ({@code multiview-telemetry.json} settings): {@code auto_update} (default true),
 * {@code update_owner} (default Zeffut), {@code update_all} (default false),
 * {@code update_exclude} (comma-separated slugs/project ids). System-property overrides:
 * {@code -Dautoupdate.enabled=false}, {@code -Dautoupdate.mods.dir=<path>}.
 */
public final class UpdateService {

    private static final Logger LOG = LoggerFactory.getLogger("AutoUpdate");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Mod embedding this copy of the module. */
    private static final String HOST_MOD_ID = "multiview";
    /** Version of the embedded auto-update module itself (independent of the host mod). */
    private static final String UPDATER_VERSION = "1.0.0";
    /** All upd_* events are segmented under this fixed app, whatever the host mod. */
    private static final String TELEMETRY_APP = "autoupdate";
    /**
     * JVM-global lock key: several Zeffut mods in one instance each embed this module, but only
     * the first one to start may run the updater (others would race on staging/state files).
     */
    private static final String GLOBAL_LOCK_KEY = "zeffut.autoupdate.lock";

    private final String loader;
    private final String mcVersion;
    private final String modVersion;
    private final ModrinthApi api;
    private final Path modsDir;
    private final Path workDir;     // <gameDir>/.autoupdate
    private final Path stagingDir;  // <gameDir>/.autoupdate/staging
    private final Path stateFile;   // <gameDir>/.autoupdate/state.json

    private final List<Pending> pendings = new ArrayList<>();

    private record Pending(String projectId, String slug, String from, String to,
                           String oldJar, String stagedJar, String targetJar) {}

    public static void start() {
        // First host mod to start wins; every other embedded copy stays dormant.
        synchronized (System.getProperties()) {
            if (System.getProperty(GLOBAL_LOCK_KEY) != null) {
                LOG.info("[AutoUpdate] updater already running (host mod: {}), staying dormant",
                        System.getProperty(GLOBAL_LOCK_KEY));
                return;
            }
            System.setProperty(GLOBAL_LOCK_KEY, HOST_MOD_ID);
        }
        UpdateService service = new UpdateService();
        Thread worker = new Thread(service::run, "autoupdate-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private UpdateService() {
        this.loader = Platform.loader();
        this.mcVersion = Platform.mcVersion();
        this.modVersion = Platform.modVersion();
        this.api = new ModrinthApi(modVersion);
        String dirOverride = System.getProperty("autoupdate.mods.dir");
        this.modsDir = dirOverride != null ? Path.of(dirOverride) : Platform.gameDir().resolve("mods");
        this.workDir = Platform.gameDir().resolve(".autoupdate");
        this.stagingDir = workDir.resolve("staging");
        this.stateFile = workDir.resolve("state.json");
    }

    private void run() {
        long startedAt = System.currentTimeMillis();
        try {
            reconcilePreviousSession();

            if (!enabled()) {
                LOG.info("[AutoUpdate] disabled by config/property, skipping update check");
                return;
            }
            if (!Files.isDirectory(modsDir)) {
                LOG.info("[AutoUpdate] mods directory {} not found, nothing to do", modsDir);
                return;
            }

            // 1. Scan + hash local jars.
            Map<String, Path> jarsByHash = new LinkedHashMap<>();
            Set<String> busyFiles = new HashSet<>();
            for (Pending p : pendings) {
                if (p.oldJar != null) busyFiles.add(p.oldJar);
                busyFiles.add(p.targetJar);
            }
            try (var stream = Files.list(modsDir)) {
                for (Path jar : stream.filter(f -> f.toString().endsWith(".jar")).toList()) {
                    if (busyFiles.contains(jar.toAbsolutePath().toString())) continue;
                    jarsByHash.put(sha512(jar), jar);
                }
            }
            if (jarsByHash.isEmpty()) {
                LOG.info("[AutoUpdate] no jars found in {}", modsDir);
                return;
            }

            // 2. Resolve latest matching versions in one batch call.
            Map<String, ModrinthApi.LatestVersion> latest =
                    api.latestVersions(jarsByHash.keySet(), loader, mcVersion);

            // 3. Eligibility: owned projects (or update_all), minus excludes.
            boolean updateAll = "true".equalsIgnoreCase(Telemetry.setting("update_all", "false"));
            String owner = Telemetry.setting("update_owner", "Zeffut");
            Map<String, String> ownedProjects = updateAll ? Map.of() : api.userProjects(owner);
            Set<String> excluded = new HashSet<>();
            for (String token : Telemetry.setting("update_exclude", "").split(",")) {
                if (!token.isBlank()) excluded.add(token.trim().toLowerCase(Locale.ROOT));
            }

            int updatesFound = 0;
            for (Map.Entry<String, ModrinthApi.LatestVersion> entry : latest.entrySet()) {
                String localHash = entry.getKey();
                ModrinthApi.LatestVersion lv = entry.getValue();
                Path localJar = jarsByHash.get(localHash);
                String slug = updateAll ? lv.projectId() : ownedProjects.get(lv.projectId());

                if (!updateAll && slug == null) continue;                       // not our mod
                if (excluded.contains(lv.projectId().toLowerCase(Locale.ROOT))) continue;
                if (slug != null && excluded.contains(slug.toLowerCase(Locale.ROOT))) continue;
                if (lv.sha512().equals(localHash)) continue;                    // already latest
                if (alreadyPending(lv.projectId())) continue;

                Path target = modsDir.resolve(lv.fileName());
                if (Files.exists(target)) continue;                             // already present

                try {
                    Path staged = download(lv);
                    Pending pending = new Pending(lv.projectId(), slug, fileVersionLabel(localJar),
                            lv.versionNumber(), localJar.toAbsolutePath().toString(),
                            staged.toAbsolutePath().toString(), target.toAbsolutePath().toString());
                    pendings.add(pending);
                    updatesFound++;
                    LOG.info("[AutoUpdate] staged update for {}: {} -> {} ({})",
                            slug != null ? slug : lv.projectId(), pending.from, pending.to, lv.fileName());
                    emit("update_staged", Map.of(
                            "project_id", lv.projectId(),
                            "project_slug", slug != null ? slug : "unknown",
                            "to_version", lv.versionNumber()));
                } catch (Exception e) {
                    LOG.warn("[AutoUpdate] failed to stage update for {}: {}", lv.projectId(), e.toString());
                    emit("update_failed", Map.of(
                            "project_id", lv.projectId(), "stage", "download", "reason", e.toString()));
                }
            }

            saveState();
            if (!pendings.isEmpty()) {
                Runtime.getRuntime().addShutdownHook(new Thread(this::applyAll, "autoupdate-swap"));
            }

            emit("check_completed", Map.of(
                    "scanned", jarsByHash.size(),
                    "matched", latest.size(),
                    "updates_found", updatesFound,
                    "duration_ms", System.currentTimeMillis() - startedAt));
            LOG.info("[AutoUpdate] check completed: {} jars scanned, {} matched on Modrinth, "
                    + "{} update(s) staged ({} ms)", jarsByHash.size(), latest.size(), updatesFound,
                    System.currentTimeMillis() - startedAt);
        } catch (Throwable t) {
            LOG.warn("[AutoUpdate] update check failed: {}", t.toString());
            emit("update_failed", Map.of(
                    "stage", "check", "reason", t.toString()));
        }
    }

    // ---- previous-session reconciliation ------------------------------------------------------

    private void reconcilePreviousSession() {
        List<Pending> previous = loadState();
        if (previous.isEmpty()) return;
        for (Pending p : previous) {
            boolean oldGone = p.oldJar == null || !Files.exists(Path.of(p.oldJar));
            boolean targetThere = Files.exists(Path.of(p.targetJar));
            Path staged = Path.of(p.stagedJar);
            if (oldGone && targetThere) {
                // Swap landed: report it and clean any staging leftover (janitor copy path).
                try { Files.deleteIfExists(staged); } catch (Exception ignored) {}
                LOG.info("[AutoUpdate] update applied: {} {} -> {}", label(p), p.from, p.to);
                emit("update_applied", Map.of(
                        "project_id", p.projectId,
                        "project_slug", p.slug != null ? p.slug : "unknown",
                        "from_version", p.from, "to_version", p.to));
            } else if (!oldGone && Files.exists(staged)) {
                // Swap did not happen (crash / locked file): retry at this session's shutdown.
                pendings.add(p);
                LOG.info("[AutoUpdate] retrying pending update for {} at next shutdown", label(p));
            } else if (!oldGone && targetThere) {
                // Both jars present (partial swap): delete the old one at shutdown.
                pendings.add(new Pending(p.projectId, p.slug, p.from, p.to, p.oldJar, null, p.targetJar));
                LOG.warn("[AutoUpdate] duplicate jars for {}; old jar will be removed at shutdown", label(p));
            }
            // else: nothing usable left, drop silently.
        }
        saveState();
    }

    // ---- shutdown swap -------------------------------------------------------------------------

    private void applyAll() {
        List<Pending> forJanitor = new ArrayList<>();
        for (Pending p : pendings) {
            try {
                if (p.oldJar != null) Files.deleteIfExists(Path.of(p.oldJar));
                if (p.stagedJar != null) {
                    Files.move(Path.of(p.stagedJar), Path.of(p.targetJar),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception lockedOrFailed) {
                forJanitor.add(p);
            }
        }
        if (!forJanitor.isEmpty()) spawnJanitor(forJanitor);
    }

    private void spawnJanitor(List<Pending> jobs) {
        try {
            String classpath = janitorClasspath(jobs);
            if (classpath == null) return;
            String javaBin = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                            ? "java.exe" : "java").toString();
            List<String> cmd = new ArrayList<>(List.of(javaBin, "-cp", classpath,
                    JanitorMain.class.getName(),
                    String.valueOf(ProcessHandle.current().pid())));
            for (Pending p : jobs) {
                cmd.add(p.oldJar != null ? p.oldJar : "-");
                cmd.add(p.stagedJar != null ? p.stagedJar : "-");
                cmd.add(p.targetJar != null ? p.targetJar : "-");
            }
            new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Exception ignored) {
            // staged files stay in place; the swap is retried next session
        }
    }

    /**
     * Classpath for the janitor process: this (host) mod's own jar, which embeds JanitorMain.
     * When the host mod is updating ITSELF, its current jar is among the files to delete — the
     * janitor must instead run from the freshly STAGED jar (the new version also embeds
     * JanitorMain and outlives the old one). Returns null in dev environments (classes dir,
     * no jar to exec).
     */
    private String janitorClasspath(List<Pending> jobs) {
        Path self = null;
        try {
            self = Path.of(UpdateService.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath();
        } catch (Exception ignored) {
        }
        if (self != null) {
            for (Pending p : jobs) {
                if (p.oldJar != null && p.stagedJar != null
                        && Path.of(p.oldJar).toAbsolutePath().equals(self)) {
                    return p.stagedJar; // self-update: run the janitor from the NEW jar
                }
            }
            if (self.toString().endsWith(".jar")) return self.toString();
        }
        return null;
    }

    /** Emits an {@code upd_*} event under the shared {@code app=autoupdate} segment. */
    private void emit(String shortName, Map<String, Object> properties) {
        Map<String, Object> props = new LinkedHashMap<>(properties);
        props.put("host_mod", HOST_MOD_ID);
        props.put("updater_version", UPDATER_VERSION);
        Telemetry.captureForApp(TELEMETRY_APP, "upd_" + shortName, props);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private boolean enabled() {
        String sys = System.getProperty("autoupdate.enabled");
        if (sys != null) return Boolean.parseBoolean(sys);
        return "true".equalsIgnoreCase(Telemetry.setting("auto_update", "true"));
    }

    private boolean alreadyPending(String projectId) {
        return pendings.stream().anyMatch(p -> p.projectId.equals(projectId));
    }

    private Path download(ModrinthApi.LatestVersion lv) throws Exception {
        Files.createDirectories(stagingDir);
        Path tmp = stagingDir.resolve(lv.fileName() + ".part");
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(lv.url()))
                .header("User-Agent", "Zeffut/AutoUpdate/" + modVersion).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) throw new IllegalStateException("download HTTP " + resp.statusCode());
        try (InputStream in = resp.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!sha512(tmp).equals(lv.sha512())) {
            Files.deleteIfExists(tmp);
            throw new IllegalStateException("SHA-512 mismatch for " + lv.fileName());
        }
        Path staged = stagingDir.resolve(lv.fileName());
        Files.move(tmp, staged, StandardCopyOption.REPLACE_EXISTING);
        return staged;
    }

    private static String sha512(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Best-effort "current version" label derived from the local file name. */
    private static String fileVersionLabel(Path jar) {
        String name = jar.getFileName().toString();
        return name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
    }

    private static String label(Pending p) {
        return p.slug != null ? p.slug : p.projectId;
    }

    // ---- state persistence ----------------------------------------------------------------------

    private List<Pending> loadState() {
        List<Pending> out = new ArrayList<>();
        try {
            if (!Files.exists(stateFile)) return out;
            JsonObject root = GSON.fromJson(Files.readString(stateFile), JsonObject.class);
            JsonArray arr = root.getAsJsonArray("pending");
            if (arr == null) return out;
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                out.add(new Pending(
                        str(o, "project_id"), str(o, "slug"), str(o, "from"), str(o, "to"),
                        str(o, "old"), str(o, "staged"), str(o, "target")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void saveState() {
        try {
            Files.createDirectories(workDir);
            JsonArray arr = new JsonArray();
            for (Pending p : pendings) {
                JsonObject o = new JsonObject();
                o.addProperty("project_id", p.projectId);
                if (p.slug != null) o.addProperty("slug", p.slug);
                o.addProperty("from", p.from);
                o.addProperty("to", p.to);
                if (p.oldJar != null) o.addProperty("old", p.oldJar);
                if (p.stagedJar != null) o.addProperty("staged", p.stagedJar);
                o.addProperty("target", p.targetJar);
                arr.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("pending", arr);
            Files.writeString(stateFile, GSON.toJson(root));
        } catch (Exception ignored) {
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
