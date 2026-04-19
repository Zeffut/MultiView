package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashbackByteBufTest {

    @Test
    void readsStringWithVarIntPrefix() {
        byte[] bytes = new byte[] { 0x05, 'h', 'e', 'l', 'l', 'o' };
        FlashbackByteBuf buf = new FlashbackByteBuf(Unpooled.wrappedBuffer(bytes));
        assertEquals("hello", buf.readString());
    }

    @Test
    void readsNamespacedId() {
        String id = "flashback:action/next_tick";
        byte[] payload = id.getBytes(StandardCharsets.UTF_8);
        ByteBuf underlying = Unpooled.buffer();
        underlying.writeByte(payload.length);
        underlying.writeBytes(payload);
        FlashbackByteBuf buf = new FlashbackByteBuf(underlying);
        assertEquals(id, buf.readNamespacedId());
    }

    @Test
    void readsVarIntDelegatesToVarInts() {
        FlashbackByteBuf buf = new FlashbackByteBuf(
                Unpooled.wrappedBuffer(new byte[] { (byte) 0xAC, 0x02 }));
        assertEquals(300, buf.readVarInt());
    }

    @Test
    void readsRawBytes() {
        FlashbackByteBuf buf = new FlashbackByteBuf(
                Unpooled.wrappedBuffer(new byte[] { 0x01, 0x02, 0x03, 0x04 }));
        assertArrayEquals(new byte[] { 0x01, 0x02, 0x03 }, buf.readBytes(3));
    }

    @Test
    void isReadableReflectsUnderlying() {
        FlashbackByteBuf buf = new FlashbackByteBuf(Unpooled.wrappedBuffer(new byte[] { 0x42 }));
        assertTrue(buf.isReadable());
        buf.readBytes(1);
        assertFalse(buf.isReadable());
    }

    @Test
    void readsBigEndianInt() {
        FlashbackByteBuf buf = new FlashbackByteBuf(
                Unpooled.wrappedBuffer(new byte[] { 0x12, 0x34, 0x56, 0x78 }));
        assertEquals(0x12345678, buf.readInt());
    }

    @Test
    void readsMagicNumber() {
        FlashbackByteBuf buf = new FlashbackByteBuf(
                Unpooled.wrappedBuffer(new byte[] { (byte) 0xD7, (byte) 0x80, (byte) 0xE8, (byte) 0x84 }));
        assertEquals(0xD780E884, buf.readInt());
    }

    @Test
    void readerIndexReflectsPosition() {
        FlashbackByteBuf buf = new FlashbackByteBuf(
                Unpooled.wrappedBuffer(new byte[] { 0x01, 0x02, 0x03 }));
        assertEquals(0, buf.readerIndex());
        buf.readBytes(2);
        assertEquals(2, buf.readerIndex());
    }
}
