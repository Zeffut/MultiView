package fr.zeffut.multiview.telemetry;

import java.util.Map;

/** Output boundary for telemetry. Real impl talks to PostHog; tests record calls. */
public interface TelemetrySink {
    void capture(String distinctId, String event, Map<String, Object> properties);
    void flush();
    void shutdown();
}
