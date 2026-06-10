package fr.zeffut.multiview.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryConfigTest {
    @Test
    void firstLoadGeneratesDistinctIdAndDefaultsEnabled(@TempDir Path dir) {
        Path file = dir.resolve("multiview-telemetry.json");
        TelemetryConfig cfg = TelemetryConfig.load(file);
        assertTrue(cfg.isEnabled());
        assertNotNull(cfg.getDistinctId());
        assertFalse(cfg.getDistinctId().isBlank());
        assertTrue(Files.exists(file), "config should be written on first load");
    }

    @Test
    void distinctIdIsStableAcrossLoads(@TempDir Path dir) {
        Path file = dir.resolve("multiview-telemetry.json");
        String first = TelemetryConfig.load(file).getDistinctId();
        String second = TelemetryConfig.load(file).getDistinctId();
        assertEquals(first, second);
    }

    @Test
    void disablingPersists(@TempDir Path dir) {
        Path file = dir.resolve("multiview-telemetry.json");
        TelemetryConfig cfg = TelemetryConfig.load(file);
        cfg.setEnabled(false);
        assertFalse(TelemetryConfig.load(file).isEnabled());
    }
}
