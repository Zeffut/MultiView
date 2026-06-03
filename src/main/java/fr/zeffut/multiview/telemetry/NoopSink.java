package fr.zeffut.multiview.telemetry;

import java.util.Map;

/** Sink used when telemetry is disabled, in dev, or after an init failure. Drops everything. */
public final class NoopSink implements TelemetrySink {
    @Override public void capture(String distinctId, String event, Map<String, Object> properties) {}
    @Override public void flush() {}
    @Override public void shutdown() {}
}
