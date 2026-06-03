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
