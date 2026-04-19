package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimelineAlignerTest {

    @Test
    void parsesMetadataNameIso() {
        long epochTicks = TimelineAligner.parseMetadataNameToTicks("2026-02-20T23:25:15");
        assertTrue(epochTicks > 0);
    }

    @Test
    void parsesMetadataNameWithPath() {
        long a = TimelineAligner.parseMetadataNameToTicks("Sénat/empirenapo2026-02-20T23:20:16");
        long b = TimelineAligner.parseMetadataNameToTicks("2026-02-20T23:25:15");
        assertEquals(299L * 20L, b - a,
                "23:25:15 - 23:20:16 = 299s = 5980 ticks");
    }

    @Test
    void underscoreFormatAlsoParses() {
        long a = TimelineAligner.parseMetadataNameToTicks("2026-02-20T23_25_15");
        long b = TimelineAligner.parseMetadataNameToTicks("2026-02-20T23:25:15");
        assertEquals(a, b);
    }

    @Test
    void unparseableReturnsNegativeOne() {
        assertEquals(-1L, TimelineAligner.parseMetadataNameToTicks("pasuntimestamp"));
    }
}
