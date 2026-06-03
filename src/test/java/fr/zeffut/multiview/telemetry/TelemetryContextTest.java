package fr.zeffut.multiview.telemetry;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryContextTest {
    @Test
    void mergeKeepsBaseAndAddsEventProps() {
        Map<String, Object> base = Map.of(EventNames.PROP_APP, "multiview",
                EventNames.PROP_MOD_VERSION, "0.4.0");
        TelemetryContext ctx = new TelemetryContext(base, "session-123");
        Map<String, Object> merged = ctx.enrich(Map.of("count", 3));
        assertEquals("multiview", merged.get(EventNames.PROP_APP));
        assertEquals("0.4.0", merged.get(EventNames.PROP_MOD_VERSION));
        assertEquals("session-123", merged.get(EventNames.PROP_SESSION_ID));
        assertEquals(3, merged.get("count"));
    }

    @Test
    void eventPropsOverrideNothingCritical_andEnrichWithNullIsSafe() {
        TelemetryContext ctx = new TelemetryContext(Map.of(EventNames.PROP_APP, "multiview"), "s1");
        Map<String, Object> merged = ctx.enrich(null);
        assertEquals("multiview", merged.get(EventNames.PROP_APP));
        assertEquals("s1", merged.get(EventNames.PROP_SESSION_ID));
    }
}
