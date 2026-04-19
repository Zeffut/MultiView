package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VarIntsTest {

    @Test
    void readZero() {
        ByteBuf buf = Unpooled.wrappedBuffer(new byte[] { 0x00 });
        assertEquals(0, VarInts.readVarInt(buf));
    }

    @Test
    void readOneByteValues() {
        assertEquals(1, VarInts.readVarInt(Unpooled.wrappedBuffer(new byte[] { 0x01 })));
        assertEquals(127, VarInts.readVarInt(Unpooled.wrappedBuffer(new byte[] { 0x7F })));
    }

    @Test
    void readMultiByteValues() {
        assertEquals(128, VarInts.readVarInt(Unpooled.wrappedBuffer(new byte[] { (byte) 0x80, 0x01 })));
        assertEquals(300, VarInts.readVarInt(Unpooled.wrappedBuffer(new byte[] { (byte) 0xAC, 0x02 })));
        assertEquals(2097151, VarInts.readVarInt(Unpooled.wrappedBuffer(new byte[] { (byte) 0xFF, (byte) 0xFF, 0x7F })));
    }

    @Test
    void readMaxPositive() {
        assertEquals(Integer.MAX_VALUE, VarInts.readVarInt(
                Unpooled.wrappedBuffer(new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07 })));
    }

    @Test
    void readNegativeOne() {
        assertEquals(-1, VarInts.readVarInt(
                Unpooled.wrappedBuffer(new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F })));
    }

    @Test
    void rejectsOverflow() {
        ByteBuf overflow = Unpooled.wrappedBuffer(new byte[] {
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x7F });
        assertThrows(IllegalArgumentException.class, () -> VarInts.readVarInt(overflow));
    }

    @Test
    void roundTrip() {
        int[] values = { 0, 1, 127, 128, 255, 256, 2097151, 2097152, Integer.MAX_VALUE, -1 };
        for (int v : values) {
            ByteBuf buf = Unpooled.buffer();
            VarInts.writeVarInt(buf, v);
            assertEquals(v, VarInts.readVarInt(buf), "roundtrip failed for " + v);
        }
    }
}
