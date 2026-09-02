package fr.zeffut.multiview.merge;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Selects one complete chunk state per world region.
 *
 * <p>A level-chunk packet starts with its packet id followed by two big-endian chunk
 * coordinates. When those coordinates can be read without ambiguity, packets for the
 * same dimension/chunk are resolved last-write-wins by absolute tick. Equal ticks keep
 * the first packet, which is deterministic because the stream merge orders ties by
 * source index. Callers must retain their existing content-hash behaviour when this
 * class returns {@link Optional#empty()}.
 *
 * <p>Non thread-safe; used by the single-threaded merge pipeline.
 */
public final class ChunkFreshnessPolicy {

    private record ChunkKey(String dimension, int x, int z) {}
    private record Freshness(int tickAbs, int sourceIdx) {}

    private final Map<ChunkKey, Freshness> freshestByChunk = new HashMap<>();

    /**
     * Returns an emission decision only when the payload safely exposes chunk coordinates.
     * An empty result deliberately leaves the caller's legacy content-hash deduplication in
     * control, rather than assigning a guessed position to an opaque packet.
     */
    public Optional<Boolean> tryShouldEmit(String dimension, int tickAbs, int sourceIdx, byte[] payload) {
        ChunkKey key = tryReadChunkKey(dimension, payload);
        if (key == null) return Optional.empty();

        Freshness current = freshestByChunk.get(key);
        if (current == null || tickAbs > current.tickAbs()) {
            freshestByChunk.put(key, new Freshness(tickAbs, sourceIdx));
            return Optional.of(true);
        }
        return Optional.of(false);
    }

    private static ChunkKey tryReadChunkKey(String dimension, byte[] payload) {
        if (dimension == null || payload == null) return null;
        int index = afterVarInt(payload);
        if (index < 0 || payload.length - index < 8) return null;
        int x = readInt(payload, index);
        int z = readInt(payload, index + 4);
        return new ChunkKey(dimension, x, z);
    }

    /** @return first byte after a valid VarInt, or -1 for malformed/truncated input. */
    private static int afterVarInt(byte[] payload) {
        for (int i = 0; i < 5; i++) {
            if (i >= payload.length) return -1;
            if ((payload[i] & 0x80) == 0) return i + 1;
        }
        return -1;
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }
}
