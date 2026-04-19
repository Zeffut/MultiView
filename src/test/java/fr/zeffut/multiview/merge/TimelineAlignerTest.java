package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    @Test
    void readVarIntHeadDecodesSimpleVarInt() {
        assertEquals(42, TimelineAligner.readVarIntHead(new byte[]{42}));
    }

    @Test
    void readVarIntHeadDecodesTwoBytes() {
        // 300 = 0x012C → VarInt encoding: 0xAC 0x02 (0xAC = 10101100, 0x02 = continuation bit cleared)
        // 300 = 0b100101100 → lower 7 bits = 0b0101100 = 0x2C → byte 0xAC (set high bit)
        //                    upper 2 bits = 0b10 = 0x02 → byte 0x02
        assertEquals(300, TimelineAligner.readVarIntHead(new byte[]{(byte) 0xAC, 0x02}));
    }

    @Test
    void readVarIntHeadThrowsOnEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> TimelineAligner.readVarIntHead(new byte[0]));
    }

    @Test
    void readGameTimeReadsLongAfterVarInt() {
        // [VarInt 42][long 1234567 BE, 8 bytes]
        byte[] payload = new byte[9];
        payload[0] = 42;
        long v = 1234567L;
        for (int i = 0; i < 8; i++) {
            payload[1 + i] = (byte) ((v >>> ((7 - i) * 8)) & 0xFF);
        }
        assertEquals(1234567L, TimelineAligner.readGameTime(payload));
    }

    @Test
    void readGameTimeWorksAfterMultiByteVarInt() {
        // [VarInt 300 (2 bytes)][long 5000 BE]
        byte[] payload = new byte[10];
        payload[0] = (byte) 0xAC;
        payload[1] = 0x02;
        long v = 5000L;
        for (int i = 0; i < 8; i++) {
            payload[2 + i] = (byte) ((v >>> ((7 - i) * 8)) & 0xFF);
        }
        assertEquals(5000L, TimelineAligner.readGameTime(payload));
    }

    @Test
    void varIntLengthReturnsCorrectByteCount() {
        assertEquals(1, TimelineAligner.varIntLength(new byte[]{42}));
        assertEquals(2, TimelineAligner.varIntLength(new byte[]{(byte) 0xAC, 0x02}));
    }

    @Test
    void cascadePrefersSetTimeAnchor() {
        var aligned = TimelineAligner.alignAll(
                List.of(
                        new TimelineAligner.Source("A", Optional.of(new TimelineAligner.SetTimeAnchor(10, 1000)), "2026-01-01T00:00:00", 100),
                        new TimelineAligner.Source("B", Optional.of(new TimelineAligner.SetTimeAnchor(5, 1050)), "2026-01-01T00:00:00", 100)
                ),
                Map.of());
        // A: abs=1000-10=990, B: abs=1050-5=1045. Normalize: A=0, B=55.
        assertEquals(0, aligned.tickOffsets()[0]);
        assertEquals(55, aligned.tickOffsets()[1]);
        assertEquals("setTimePacket", aligned.strategy());
    }

    @Test
    void cascadeFallsBackToMetadataWhenSetTimeMissing() {
        var aligned = TimelineAligner.alignAll(
                List.of(
                        new TimelineAligner.Source("A", Optional.empty(), "2026-01-01T00:00:00", 100),
                        new TimelineAligner.Source("B", Optional.empty(), "2026-01-01T00:00:05", 100)
                ),
                Map.of());
        // A commence à t=0s, B commence à t=5s → B offset = A + 5*20 = 100 ticks après
        assertEquals(100, aligned.tickOffsets()[1] - aligned.tickOffsets()[0]);
        assertEquals("metadataName", aligned.strategy());
    }

    @Test
    void cliOverrideIsApplied() {
        var aligned = TimelineAligner.alignAll(
                List.of(
                        new TimelineAligner.Source("A", Optional.empty(), "2026-01-01T00:00:00", 100),
                        new TimelineAligner.Source("B", Optional.empty(), "2026-01-01T00:00:00", 100)
                ),
                Map.of("B", 50));
        assertEquals(50, aligned.tickOffsets()[1] - aligned.tickOffsets()[0]);
        assertEquals("cliOverride", aligned.strategy());
    }

    @Test
    void normalizationStartsAtZero() {
        var aligned = TimelineAligner.alignAll(
                List.of(
                        new TimelineAligner.Source("A", Optional.of(new TimelineAligner.SetTimeAnchor(0, 1000)), "", 100),
                        new TimelineAligner.Source("B", Optional.of(new TimelineAligner.SetTimeAnchor(0, 2000)), "", 100)
                ),
                Map.of());
        int min = Math.min(aligned.tickOffsets()[0], aligned.tickOffsets()[1]);
        assertEquals(0, min);
    }

    @Test
    void mergedTotalTicksAccountsForOffsets() {
        var aligned = TimelineAligner.alignAll(
                List.of(
                        new TimelineAligner.Source("A", Optional.of(new TimelineAligner.SetTimeAnchor(0, 100)), "", 500),
                        new TimelineAligner.Source("B", Optional.of(new TimelineAligner.SetTimeAnchor(0, 200)), "", 300)
                ),
                Map.of());
        // A: offset 0, total 500 → end 500
        // B: offset 100, total 300 → end 400
        // mergedTotalTicks = max(500, 400) = 500
        assertEquals(500, aligned.mergedTotalTicks());
    }

    @Test
    void alignAllThrowsWhenMetadataNameUnparseable() {
        assertThrows(IllegalArgumentException.class, () ->
                TimelineAligner.alignAll(
                        List.of(
                                new TimelineAligner.Source("A", Optional.empty(), "no-timestamp-here", 100)
                        ),
                        Map.of()));
    }
}
