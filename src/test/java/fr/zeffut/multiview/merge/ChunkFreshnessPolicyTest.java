package fr.zeffut.multiview.merge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ChunkFreshnessPolicyTest {

    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void freshestChunkWinsForSharedRegionAcrossFourPovs() {
        ChunkFreshnessPolicy policy = new ChunkFreshnessPolicy();

        assertEquals(Optional.of(true), policy.tryShouldEmit(
                OVERWORLD, 100, 0, levelChunkPayload(0x25, 12, -7, (byte) 1)));
        assertEquals(Optional.of(false), policy.tryShouldEmit(
                OVERWORLD, 100, 1, levelChunkPayload(0x25, 12, -7, (byte) 2)));
        assertEquals(Optional.of(false), policy.tryShouldEmit(
                OVERWORLD, 100, 2, levelChunkPayload(0x25, 12, -7, (byte) 3)));
        assertEquals(Optional.of(false), policy.tryShouldEmit(
                OVERWORLD, 100, 3, levelChunkPayload(0x25, 12, -7, (byte) 4)));

        assertEquals(Optional.of(true), policy.tryShouldEmit(
                OVERWORLD, 101, 3, levelChunkPayload(0x25, 12, -7, (byte) 5)),
                "a newer full-chunk packet must replace the earlier shared-region state");
        assertEquals(Optional.of(false), policy.tryShouldEmit(
                OVERWORLD, 100, 0, levelChunkPayload(0x25, 12, -7, (byte) 6)),
                "a late stale packet must not overwrite the freshest chunk");
    }

    @Test
    void undecodablePayloadLeavesExistingContentDedupFallbackAvailable() {
        ChunkFreshnessPolicy policy = new ChunkFreshnessPolicy();

        assertEquals(Optional.empty(), policy.tryShouldEmit(OVERWORLD, 100, 0, new byte[]{(byte) 0x80}),
                "a malformed packet id must not be assigned a guessed chunk position");
        assertEquals(Optional.empty(), policy.tryShouldEmit(OVERWORLD, 100, 0, new byte[]{0x25, 1, 2, 3}),
                "a payload without both chunk coordinates must use the existing fallback");
    }

    private static byte[] levelChunkPayload(int packetId, int chunkX, int chunkZ, byte marker) {
        ByteBuf buf = Unpooled.buffer();
        writeVarInt(buf, packetId);
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        buf.writeByte(marker);
        byte[] payload = new byte[buf.readableBytes()];
        buf.readBytes(payload);
        return payload;
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
