package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the binary splice helpers in {@link EntityPacketRewriter}.
 *
 * <p>These tests use the package-private static helpers
 * {@code spliceVarIntEntityId} and {@code spliceInt32EntityId} so they
 * do not require Minecraft Bootstrap (no PlayStateFactories) and run purely
 * as unit tests.
 *
 * <h2>Root-cause regression test</h2>
 * Before the fix, {@code ENTITY_EVENT} (EntityStatusS2CPacket) was routed
 * through {@code rewriteSingleEntityId}, which reads the entity ID as a VarInt.
 * But {@code EntityStatusS2CPacket} encodes the entity ID as a fixed int32
 * (4 bytes). For entity IDs 0–127 the first byte of the int32 has its
 * continuation bit clear, so the VarInt reader consumed only 1 byte instead of
 * 4. The rewriter then wrote a 4-byte global-ID VarInt and appended the
 * remaining 3 bytes of the original int32, inflating the payload by 3 bytes.
 * Flashback reported: "Had 9 bytes available, only read 6".
 *
 * <p>The fix adds {@code rewriteEntityEventInt32} (dispatched when
 * {@code pid == idEntityEvent}) that reads/writes the entity ID as int32.
 */
class EntityPacketRewriterSpliceTest {

    // -------------------------------------------------------------------------
    // VarInt splice (rewriteSingleEntityId path)
    // -------------------------------------------------------------------------

    /**
     * Basic splice: 1-byte packetId, 1-byte entityId, 4-byte trailing body.
     * Entity ID expands from 1 byte (local=42) to 4 bytes (global=100_000_000).
     * Output payload must be exactly 1+4+4 = 9 bytes.
     */
    @Test
    void varIntSplice_preservesPacketIdAndTrailingBytes() {
        int packetId = 0x28;        // MOVE_ENTITY_POS_ROT (example, 1-byte VarInt)
        int localEntityId = 42;     // 1-byte VarInt
        int globalEntityId = 100_000_000; // 4-byte VarInt

        // Trailing body (4 bytes): deltaX=short, deltaY=short
        byte[] trailingBody = { 0x01, 0x02, 0x03, 0x04 };

        byte[] payload = buildVarIntPayload(packetId, localEntityId, trailingBody);
        // Original layout: [0x28][0x2A][0x01 0x02 0x03 0x04] = 6 bytes
        assertEquals(6, payload.length);

        byte[] rewritten = EntityPacketRewriter.spliceVarIntEntityId(payload, globalEntityId);

        // Decode the rewritten payload and verify fields
        ByteBuf out = Unpooled.wrappedBuffer(rewritten);

        int decodedPid = VarInts.readVarInt(out);
        assertEquals(packetId, decodedPid, "packetId must be preserved");

        int decodedEid = VarInts.readVarInt(out);
        assertEquals(globalEntityId, decodedEid, "entity ID must be remapped to globalEntityId");

        // Remaining bytes must match trailingBody byte-for-byte
        assertEquals(trailingBody.length, out.readableBytes(),
                "trailing bytes length must match original");
        byte[] actualTrailing = new byte[out.readableBytes()];
        out.readBytes(actualTrailing);
        assertArrayEquals(trailingBody, actualTrailing,
                "trailing bytes must be identical to original (no corruption)");
    }

    /**
     * Splice when local entity ID is already 2 bytes (e.g., 200 = 0xC8 0x01).
     * Output must still be correct with new global ID and unchanged trailing.
     */
    @Test
    void varIntSplice_twoByteLocalEntityId() {
        int packetId = 0x10;
        int localEntityId = 200;        // 2-byte VarInt: 0xC8 0x01
        int globalEntityId = 100_000_001;

        byte[] trailing = { (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF };
        byte[] payload = buildVarIntPayload(packetId, localEntityId, trailing);

        byte[] rewritten = EntityPacketRewriter.spliceVarIntEntityId(payload, globalEntityId);

        ByteBuf out = Unpooled.wrappedBuffer(rewritten);
        assertEquals(packetId, VarInts.readVarInt(out), "packetId preserved");
        assertEquals(globalEntityId, VarInts.readVarInt(out), "global entity ID correct");
        byte[] actualTrailing = new byte[out.readableBytes()];
        out.readBytes(actualTrailing);
        assertArrayEquals(trailing, actualTrailing, "trailing bytes identical");
    }

    /**
     * Splice with no trailing bytes (entity ID is the last field).
     */
    @Test
    void varIntSplice_noTrailingBytes() {
        int packetId = 0x05;
        int localEntityId = 1;
        int globalEntityId = 100_000_002;

        byte[] payload = buildVarIntPayload(packetId, localEntityId, new byte[0]);

        byte[] rewritten = EntityPacketRewriter.spliceVarIntEntityId(payload, globalEntityId);

        ByteBuf out = Unpooled.wrappedBuffer(rewritten);
        assertEquals(packetId, VarInts.readVarInt(out), "packetId preserved");
        assertEquals(globalEntityId, VarInts.readVarInt(out), "global entity ID correct");
        assertEquals(0, out.readableBytes(), "no trailing bytes expected");
    }

    /**
     * Splice when global ID is the same size as local ID (1 byte → 1 byte).
     * Payload length must remain the same.
     */
    @Test
    void varIntSplice_sameVarIntSize_payloadLengthUnchanged() {
        int packetId = 0x07;
        int localEntityId = 5;   // 1-byte VarInt
        int globalEntityId = 99; // 1-byte VarInt

        byte[] trailing = { 0x11, 0x22 };
        byte[] payload = buildVarIntPayload(packetId, localEntityId, trailing);

        byte[] rewritten = EntityPacketRewriter.spliceVarIntEntityId(payload, globalEntityId);

        assertEquals(payload.length, rewritten.length,
                "when old and new VarInts are same size, payload length must not change");
        ByteBuf out = Unpooled.wrappedBuffer(rewritten);
        assertEquals(packetId, VarInts.readVarInt(out));
        assertEquals(globalEntityId, VarInts.readVarInt(out));
    }

    // -------------------------------------------------------------------------
    // Int32 splice (rewriteEntityEventInt32 path — ENTITY_EVENT regression)
    // -------------------------------------------------------------------------

    /**
     * Regression test for the ENTITY_EVENT bug.
     *
     * <p>Before the fix: spliceVarIntEntityId was used for ENTITY_EVENT. For
     * entity ID=1 (int32 = [0x00 0x00 0x00 0x01]), the VarInt reader consumed
     * only the first byte (0x00 → localId=0), then wrote a 4-byte global VarInt
     * in its place, leaving the remaining 3 bytes of the int32 as "phantom"
     * trailing data. Flashback reported: "Had 9 bytes available, only read 6".
     *
     * <p>After the fix: spliceInt32EntityId correctly reads and writes a 4-byte
     * fixed int32, so the output payload has the same size as the input (the
     * global ID is also stored as int32, matching what the codec writes).
     */
    @Test
    void int32Splice_entityEvent_regression_noExtraBytes() {
        int packetId = 0x1E;        // ENTITY_EVENT packet ID (example)
        int localEntityId = 1;      // int32: [0x00 0x00 0x00 0x01]
        int globalEntityId = 200_000_000; // arbitrary large ID, also written as int32

        byte eventId = 0x09;        // entity status byte (e.g. "play death sound")

        // Build the ENTITY_EVENT payload: [VarInt packetId][int32 entityId][byte eventId]
        ByteBuf builder = Unpooled.buffer(6);
        VarInts.writeVarInt(builder, packetId);  // 1 byte
        builder.writeInt(localEntityId);          // 4 bytes (big-endian int32)
        builder.writeByte(eventId);               // 1 byte
        byte[] payload = new byte[builder.readableBytes()];
        builder.readBytes(payload);
        assertEquals(6, payload.length, "ENTITY_EVENT payload must be 6 bytes");

        // Apply the correct int32 splice
        byte[] rewritten = EntityPacketRewriter.spliceInt32EntityId(payload, globalEntityId);

        // Output must also be 6 bytes: [VarInt packetId][int32 globalId][byte eventId]
        assertEquals(6, rewritten.length,
                "int32 splice must not change payload size (int32 in → int32 out)");

        ByteBuf out = Unpooled.wrappedBuffer(rewritten);
        int decodedPid = VarInts.readVarInt(out);
        assertEquals(packetId, decodedPid, "packetId preserved");

        int decodedEid = out.readInt(); // fixed int32
        assertEquals(globalEntityId, decodedEid, "entity ID must be globalEntityId (int32)");

        assertEquals(1, out.readableBytes(), "exactly 1 trailing byte (eventId) expected");
        assertEquals(eventId, out.readByte(), "eventId byte preserved unchanged");
    }

    /**
     * Demonstrates what the OLD (broken) VarInt splice would produce for
     * ENTITY_EVENT, to confirm the 3-byte inflation that caused the Flashback crash.
     * This test documents the pre-fix behavior and should NOT be reverted.
     */
    @Test
    void int32Splice_varIntSplice_wouldInflatePayloadByThreeBytes() {
        int packetId = 0x1E;
        int localEntityId = 1;      // int32: [0x00][0x00][0x00][0x01]
        byte eventId = 0x09;

        // Build the 6-byte ENTITY_EVENT payload
        ByteBuf builder = Unpooled.buffer(6);
        VarInts.writeVarInt(builder, packetId);
        builder.writeInt(localEntityId);
        builder.writeByte(eventId);
        byte[] payload = new byte[builder.readableBytes()];
        builder.readBytes(payload);

        // Simulate the OLD broken approach: treat as VarInt
        // The VarInt reader would read the first byte of the int32 (= 0x00) → entityId=0
        // It would NOT read the remaining 3 bytes of the int32
        // The 3 remaining bytes of int32 + the eventId byte become "trailing"
        int brokenGlobalId = 100_000_000; // 4-byte VarInt
        byte[] brokenRewritten = EntityPacketRewriter.spliceVarIntEntityId(payload, brokenGlobalId);

        // Old code would produce: [packetId=1][globalId VarInt=4][0x00 0x00 0x01 0x09] = 9 bytes
        // Flashback reads: [packetId=1][int32=4][eventId=1] = 6 bytes → 3 unconsumed → crash
        assertEquals(9, brokenRewritten.length,
                "OLD VarInt splice inflates ENTITY_EVENT payload from 6 to 9 bytes (the bug)");
    }

    /**
     * Int32 splice with different trailing data lengths.
     */
    @Test
    void int32Splice_preservesTrailingBytes() {
        int packetId = 0x1E;
        int localEntityId = 42;
        int globalEntityId = 999_999_999;
        byte[] trailing = { 0x0A, 0x0B, 0x0C };

        ByteBuf builder = Unpooled.buffer(8);
        VarInts.writeVarInt(builder, packetId);
        builder.writeInt(localEntityId);
        for (byte b : trailing) builder.writeByte(b);
        byte[] payload = new byte[builder.readableBytes()];
        builder.readBytes(payload);

        byte[] rewritten = EntityPacketRewriter.spliceInt32EntityId(payload, globalEntityId);

        ByteBuf out = Unpooled.wrappedBuffer(rewritten);
        assertEquals(packetId, VarInts.readVarInt(out), "packetId preserved");
        assertEquals(globalEntityId, out.readInt(), "int32 entity ID correct");
        byte[] actualTrailing = new byte[out.readableBytes()];
        out.readBytes(actualTrailing);
        assertArrayEquals(trailing, actualTrailing, "trailing bytes preserved");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a synthetic GamePacket payload: VarInt(packetId) + VarInt(entityId) + trailing.
     */
    private static byte[] buildVarIntPayload(int packetId, int entityId, byte[] trailing) {
        ByteBuf buf = Unpooled.buffer(10 + trailing.length);
        VarInts.writeVarInt(buf, packetId);
        VarInts.writeVarInt(buf, entityId);
        if (trailing.length > 0) {
            buf.writeBytes(trailing);
        }
        byte[] result = new byte[buf.readableBytes()];
        buf.readBytes(result);
        return result;
    }
}
