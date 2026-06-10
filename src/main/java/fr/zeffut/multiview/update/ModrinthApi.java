package fr.zeffut.multiview.update;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Minimal Modrinth v2 API client. Mapping-agnostic: references no Minecraft class. Gson is used
 * for parsing — it ships on the game classpath for every supported (MC x loader) node.
 *
 * <p>Endpoints used:
 * <ul>
 *   <li>{@code POST /v2/version_files/update} — batch "latest matching version" lookup by file
 *       hash, filtered by loader and game version;</li>
 *   <li>{@code GET /v2/user/{user}/projects} — the project ids owned by a given account, used to
 *       restrict silent updates to that account's mods.</li>
 * </ul>
 */
public final class ModrinthApi {

    private static final String BASE = System.getProperty("autoupdate.api", "https://api.modrinth.com/v2");
    private static final Gson GSON = new Gson();

    private final HttpClient http;
    private final String userAgent;

    public ModrinthApi(String modVersion) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.userAgent = "Zeffut/AutoUpdate/" + modVersion + " (github.com/Zeffut)";
    }

    /** One resolved "latest version" entry for a local file hash. */
    public record LatestVersion(String projectId, String versionId, String versionNumber,
                                String fileName, String url, String sha512) {}

    /** Project ids owned by {@code user} (any role on the project counts), mapped to slugs. */
    public Map<String, String> userProjects(String user) throws Exception {
        JsonArray arr = GSON.fromJson(get("/user/" + user + "/projects"), JsonArray.class);
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonElement e : arr) {
            JsonObject p = e.getAsJsonObject();
            out.put(p.get("id").getAsString(), p.get("slug").getAsString());
        }
        return out;
    }

    /**
     * Batch lookup: for each known SHA-512 hash, the latest version of the matching project that
     * supports the given loader and MC version. Unknown hashes are simply absent from the result.
     * The returned map is keyed by the LOCAL (queried) hash; {@code sha512} inside the value is
     * the hash of the latest version's primary file.
     */
    public Map<String, LatestVersion> latestVersions(Collection<String> sha512Hashes,
                                                     String loader, String mcVersion) throws Exception {
        JsonObject body = new JsonObject();
        JsonArray hashes = new JsonArray();
        sha512Hashes.forEach(hashes::add);
        body.add("hashes", hashes);
        body.addProperty("algorithm", "sha512");
        JsonArray loaders = new JsonArray();
        loaders.add(loader);
        body.add("loaders", loaders);
        JsonArray versions = new JsonArray();
        versions.add(mcVersion);
        body.add("game_versions", versions);

        JsonObject resp = GSON.fromJson(post("/version_files/update", GSON.toJson(body)), JsonObject.class);
        Map<String, LatestVersion> out = new LinkedHashMap<>();
        Set<String> queried = new LinkedHashSet<>(sha512Hashes);
        for (String hash : resp.keySet()) {
            if (!queried.contains(hash)) continue;
            JsonObject v = resp.getAsJsonObject(hash);
            JsonObject file = primaryFile(v.getAsJsonArray("files"));
            if (file == null) continue;
            out.put(hash, new LatestVersion(
                    v.get("project_id").getAsString(),
                    v.get("id").getAsString(),
                    v.get("version_number").getAsString(),
                    file.get("filename").getAsString(),
                    file.get("url").getAsString(),
                    file.getAsJsonObject("hashes").get("sha512").getAsString()));
        }
        return out;
    }

    private static JsonObject primaryFile(JsonArray files) {
        if (files == null || files.isEmpty()) return null;
        for (JsonElement e : files) {
            JsonObject f = e.getAsJsonObject();
            if (f.has("primary") && f.get("primary").getAsBoolean()) return f;
        }
        return files.get(0).getAsJsonObject();
    }

    private String get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        return send(req);
    }

    private String post(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return send(req);
    }

    private String send(HttpRequest req) throws Exception {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("Modrinth API " + req.uri().getPath()
                    + " -> HTTP " + resp.statusCode());
        }
        return resp.body();
    }
}
