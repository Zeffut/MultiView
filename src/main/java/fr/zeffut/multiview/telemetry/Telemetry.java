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

    /**
     * Same as {@link #capture} but tagging the event with an explicit {@code app}, overriding the
     * context's default ({@code multiview}). Used by the embedded auto-update module, whose events
     * are segmented under {@code app=autoupdate} (shared across every host mod). The override works
     * because {@link TelemetryContext#enrich} applies the super-properties first and per-event
     * properties last, so this {@code app} value wins.
     */
    public static void captureForApp(String app, String event, Map<String, Object> properties) {
        Map<String, Object> props = new java.util.HashMap<>();
        if (properties != null) props.putAll(properties);
        props.put(EventNames.PROP_APP, app);
        capture(event, props);
    }

    /**
     * Reads a free-form string setting from the persisted config (used by the embedded auto-update
     * module: {@code auto_update}, {@code update_owner}, {@code update_all}, {@code update_exclude}).
     * Returns {@code fallback} when telemetry config is not yet loaded.
     */
    public static String setting(String key, String fallback) {
        TelemetryConfig cfg = config;
        return cfg == null ? fallback : cfg.setting(key, fallback);
    }

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
