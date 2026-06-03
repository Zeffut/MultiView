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
