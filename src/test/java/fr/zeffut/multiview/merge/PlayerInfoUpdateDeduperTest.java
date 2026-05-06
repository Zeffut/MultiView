package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerInfoUpdateDeduperTest {

    /** Build a minimal PLAYER_INFO_UPDATE payload: actions bitmask + count + entries. */
    private static byte[] buildInfoUpdate(int packetId, int actionsBitmask, UUID... uuids) {
        ByteBuf buf = Unpooled.buffer();
        VarInts.writeVarInt(buf, packetId);
        buf.writeByte(actionsBitmask);
        VarInts.writeVarInt(buf, uuids.length);
        for (UUID u : uuids) {
            buf.writeLong(u.getMostSignificantBits());
            buf.writeLong(u.getLeastSignificantBits());
            // For ADD_PLAYER: would normally include GameProfile fields. For tests we don't
            // need them — the deduper only inspects UUID + bitmask.
        }
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    private static byte[] buildInfoRemove(int packetId, UUID... uuids) {
        ByteBuf buf = Unpooled.buffer();
        VarInts.writeVarInt(buf, packetId);
        VarInts.writeVarInt(buf, uuids.length);
        for (UUID u : uuids) {
            buf.writeLong(u.getMostSignificantBits());
            buf.writeLong(u.getLeastSignificantBits());
        }
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    @Test
    void firstAddPlayerEmitted() {
        PlayerInfoUpdateDeduper d = new PlayerInfoUpdateDeduper();
        UUID u = UUID.randomUUID();
        assertTrue(d.shouldEmitInfoUpdate(buildInfoUpdate(64, 0x01, u)));
    }

    @Test
    void duplicateAddPlayerDropped() {
        PlayerInfoUpdateDeduper d = new PlayerInfoUpdateDeduper();
        UUID u = UUID.randomUUID();
        d.shouldEmitInfoUpdate(buildInfoUpdate(64, 0x01, u));
        assertFalse(d.shouldEmitInfoUpdate(buildInfoUpdate(64, 0x01, u)),
                "second ADD_PLAYER for same UUID should be dropped");
        assertEquals(1, d.duplicateAddDropped());
    }

    @Test
    void differentUuidsBothEmitted() {
        PlayerInfoUpdateDeduper d = new PlayerInfoUpdateDeduper();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(d.shouldEmitInfoUpdate(buildInfoUpdate(64, 0x01, a)));
        assertTrue(d.shouldEmitInfoUpdate(buildInfoUpdate(64, 0x01, b)));
    }

    @Test
    void packetWithoutAddBitNotDeduped() {
        PlayerInfoUpdateDeduper d = new PlayerInfoUpdateDeduper();
        UUID u = UUID.randomUUID();
        // bit 4 = UPDATE_LATENCY, no ADD_PLAYER
        assertTrue(d.shouldEmitInfoUpdate(buildInfoUpdate(64, 0x10, u)));
        assertTrue(d.shouldEmitInfoUpdate(buildInfoUpdate(64, 0x10, u)),
                "non-ADD packets should not trigger UUID dedup");
    }

    @Test
    void removeAllowsRejoin() {
        PlayerInfoUpdateDeduper d = new PlayerInfoUpdateDeduper();
        UUID u = UUID.randomUUID();
        d.shouldEmitInfoUpdate(buildInfoUpdate(64, 0x01, u));
        d.shouldEmitInfoRemove(buildInfoRemove(65, u));
        assertTrue(d.shouldEmitInfoUpdate(buildInfoUpdate(64, 0x01, u)),
                "after PLAYER_INFO_REMOVE, the same UUID should be re-announceable");
    }

    @Test
    void malformedPayloadFailsOpen() {
        PlayerInfoUpdateDeduper d = new PlayerInfoUpdateDeduper();
        assertTrue(d.shouldEmitInfoUpdate(new byte[]{0}), "empty body → emit, do not throw");
        assertTrue(d.shouldEmitInfoUpdate(null), "null payload → emit, do not throw");
    }
}
