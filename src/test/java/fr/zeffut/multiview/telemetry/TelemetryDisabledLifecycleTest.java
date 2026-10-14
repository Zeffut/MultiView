package fr.zeffut.multiview.telemetry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Non-ingestive: production init is called ONLY while a disabling gate is active. */
class TelemetryDisabledLifecycleTest {
    @AfterEach void cleanup() { Telemetry.shutdown(); }

    @Test void devInitNeverEnables(@TempDir Path dir) {
        Telemetry.init(dir.resolve("config.json"), "test", true);
        assertFalse(Telemetry.isEnabled());
        assertDoesNotThrow(() -> Telemetry.capture("test_not_sent"));
        Telemetry.shutdown();
        assertFalse(Telemetry.isEnabled());
    }

    @Test void systemKillSwitchNeverEnables(@TempDir Path dir) {
        String previous = System.getProperty("multiview.telemetry");
        try {
            System.setProperty("multiview.telemetry", "false");
            Telemetry.init(dir.resolve("config.json"), "test", false);
            assertFalse(Telemetry.isEnabled());
            assertDoesNotThrow(() -> Telemetry.capture("test_not_sent"));
        } finally {
            if (previous == null) System.clearProperty("multiview.telemetry");
            else System.setProperty("multiview.telemetry", previous);
        }
    }

    @Test void persistedDisabledInitNeverEnables(@TempDir Path dir) {
        Path file = dir.resolve("config.json");
        TelemetryConfig.load(file).setEnabled(false);
        Telemetry.init(file, "test", false);
        assertFalse(Telemetry.isEnabled());
        assertDoesNotThrow(() -> Telemetry.capture("test_not_sent"));
    }

    @Test void runtimeOptOutPersistsAndDropsSubsequentEvents(@TempDir Path dir) {
        Path file = dir.resolve("config.json");
        TelemetryConfig cfg = TelemetryConfig.load(file);
        TelemetryTest.RecordingSink recorder = new TelemetryTest.RecordingSink();
        Telemetry.initForTest(cfg, new TelemetryContext(Map.of("app", "multiview"), "test"), recorder);
        Telemetry.setEnabled(false); // must select NoopSink, never a production client
        assertFalse(Telemetry.isEnabled());
        assertFalse(TelemetryConfig.load(file).isEnabled());
        assertEquals(1, recorder.calls.size());
        assertEquals(EventNames.EVT_TELEMETRY_OPTOUT, recorder.calls.getFirst().event());
        Telemetry.capture("test_not_sent");
        assertEquals(1, recorder.calls.size());
        assertDoesNotThrow(Telemetry::shutdown);
        assertDoesNotThrow(Telemetry::shutdown);
    }
}
