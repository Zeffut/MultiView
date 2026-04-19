package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
