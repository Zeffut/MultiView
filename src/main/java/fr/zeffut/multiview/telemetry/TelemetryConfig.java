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
