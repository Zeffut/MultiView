package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.packet.PlayPackets;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites WORLD GamePacket payloads to enforce LWW (last-write-wins) arbitration on
 * individual block updates via {@link WorldStateMerger}.
 *
 * <h2>Packet types handled</h2>
 * <ul>
 *   <li><b>BLOCK_UPDATE</b> ({@code BlockUpdateS2CPacket}): single block pos + state.
 *       Decoded via {@code BlockUpdateS2CPacket.CODEC}. If WorldStateMerger rejects the
 *       update (stale tick), {@code null} is returned → packet is dropped by caller.</li>
 *   <li><b>SECTION_BLOCKS_UPDATE</b> ({@code ChunkDeltaUpdateS2CPacket}): batch of block
 *       updates. Decoded via {@code ChunkDeltaUpdateS2CPacket.CODEC}. Each entry is tested
 *       against WorldStateMerger; survivors are re-encoded into a new packet. If all entries
 *       are stale, {@code null} is returned.</li>
 *   <li>All other WORLD packets (chunk load/unload, light, block entity, etc.): passthrough.</li>
 * </ul>
 *
 * <h2>Dimension handling</h2>
 * Each source declares a dimension key (its initial world from {@code metadata.world_name}),
 * passed via {@link #setSourceDimension(int, String)}. Block updates are scoped to that key
 * in {@link WorldStateMerger} so blocks at the same {@code (x, y, z)} in different worlds do
 * not collide in cross-source LWW arbitration.
 *
 * <p>Limitation: a source that crosses dimensions mid-recording (Nether portal, multi-world
 * teleport) keeps its initial key for the whole stream. Tracking dim changes per-tick via
 * RESPAWN parsing is deferred. In practice, replays that span multiple dims and need accurate
 * cross-source LWW are rare; one initial dim per source is a sound approximation.
 *
 * <h2>Fallback mode</h2>
 * If MC PlayStateFactories or codecs are unavailable at construction time (e.g. no Bootstrap
 * in unit test context), {@code fallbackMode} is set to {@code true} and all packets pass
 * through unchanged, preserving Phase 3 behaviour.
 *
 * @implNote Non thread-safe. Called from the single-threaded MergeOrchestrator pipeline.
 */
public final class WorldPacketRewriter {

    private static final Logger LOG = LoggerFactory.getLogger(WorldPacketRewriter.class);

    /** Default dimension key when a source has no recorded {@code world_name}. */
    private static final String DIMENSION_DEFAULT = "minecraft:overworld";

    /**
     * If true, codec access failed at construction time.
     * All packets pass through unchanged (Phase 3 passthrough behaviour).
     */
    private final boolean fallbackMode;

    private final WorldStateMerger worldMerger;

    /** Dimension key per source — populated via {@link #setSourceDimension}. */
    private final java.util.Map<Integer, String> sourceDimensions = new java.util.HashMap<>();

    /** Numeric protocol IDs for WORLD packet types we handle, resolved at construction. */
    private int idBlockUpdate;
    private int idSectionBlocksUpdate;

    /**
     * Set the dimension key for a source — usually the source's
     * {@code metadata.world_name}. Block updates from this source will key on the value
     * supplied here in {@link WorldStateMerger}, so blocks at the same coords in
     * different worlds do not LWW against each other.
     */
    public void setSourceDimension(int sourceIdx, String dimensionKey) {
        if (dimensionKey == null || dimensionKey.isBlank()) return;
        sourceDimensions.put(sourceIdx, dimensionKey);
    }

    private String dimensionFor(int sourceIdx) {
        return sourceDimensions.getOrDefault(sourceIdx, DIMENSION_DEFAULT);
    }

    public WorldPacketRewriter(WorldStateMerger worldMerger) {
        this.worldMerger = worldMerger;

        boolean fb;
        try {
            idBlockUpdate        = GamePacketDispatch.findId(PlayPackets.BLOCK_UPDATE);
            idSectionBlocksUpdate = GamePacketDispatch.findId(PlayPackets.SECTION_BLOCKS_UPDATE);
            fb = false;
        } catch (Throwable t) {
            LOG.warn("WorldPacketRewriter: PlayStateFactories unavailable ("
                    + t.getClass().getSimpleName() + "), block LWW disabled (fallback passthrough).");
            fb = true;
        }
        this.fallbackMode = fb;
    }

    /**
     * Rewrites a WORLD GamePacket payload applying LWW arbitration on block updates.
     *
     * @param sourceIdx source index (for WorldStateMerger)
     * @param tickAbs   absolute tick (for LWW comparison)
     * @param payload   raw GamePacket bytes (starts with VarInt packetId)
     * @return rewritten payload bytes, or {@code null} to drop the packet entirely
     */
    public byte[] rewrite(int sourceIdx, int tickAbs, byte[] payload) {
        if (fallbackMode) return payload;

        int pid = readFirstVarInt(payload);

        try {
            if (pid == idBlockUpdate) {
                return rewriteBlockUpdate(sourceIdx, tickAbs, payload);
            } else if (pid == idSectionBlocksUpdate) {
                return rewriteSectionBlocksUpdate(sourceIdx, tickAbs, payload);
            } else {
                // LEVEL_CHUNK_WITH_LIGHT, FORGET_LEVEL_CHUNK, LIGHT_UPDATE,
                // BLOCK_ENTITY_DATA, BLOCK_EVENT, CHUNKS_BIOMES: passthrough
                return payload;
            }
        } catch (Exception e) {
            LOG.warn("WorldPacketRewriter: failed to rewrite packetId=" + pid
                    + " from source=" + sourceIdx + ": " + e.getMessage());
            // On decode failure, pass through (safe fallback)
            return payload;
        }
    }

    // -------------------------------------------------------------------------
    // Private rewrite methods
    // -------------------------------------------------------------------------

    /**
     * Rewrites a {@code BlockUpdateS2CPacket} (BLOCK_UPDATE).
     * Decodes pos + state, calls WorldStateMerger, drops if stale.
     *
     * <p>{@code BlockUpdateS2CPacket.CODEC} takes a {@link RegistryByteBuf}; we use
     * {@link DynamicRegistryManager#EMPTY} since BlockState is in the vanilla static
     * registry and does not require a dynamic registry.
     *
     * @return payload (possibly the original reference if accepted), or {@code null} to drop
     */
    private byte[] rewriteBlockUpdate(int sourceIdx, int tickAbs, byte[] payload) {
        ByteBuf raw = Unpooled.wrappedBuffer(payload);

        // Skip the packetId VarInt to get to the packet body
        int packetIdStart = raw.readerIndex();
        readVarIntFromBuf(raw);
        int bodyStart = raw.readerIndex();

        // Decode body
        ByteBuf bodyBuf = raw.slice(bodyStart, raw.readableBytes());
        RegistryByteBuf registryBuf = new RegistryByteBuf(bodyBuf, DynamicRegistryManager.EMPTY);
        BlockUpdateS2CPacket pkt = BlockUpdateS2CPacket.CODEC.decode(registryBuf);

        BlockPos pos = pkt.getPos();
        BlockState state = pkt.getState();
        int blockStateId = Block.getRawIdFromState(state);

        boolean accepted = worldMerger.acceptBlockUpdate(
                dimensionFor(sourceIdx),
                pos.getX(), pos.getY(), pos.getZ(),
                tickAbs, blockStateId, sourceIdx);

        if (!accepted) {
            return null; // Drop stale update
        }

        // Accepted — return original payload unchanged (no re-encoding needed)
        return payload;
    }

    /**
     * Rewrites a {@code ChunkDeltaUpdateS2CPacket} (SECTION_BLOCKS_UPDATE).
     * Iterates all (pos, state) entries, LWW-tests each, re-encodes survivors.
     *
     * <p>{@code ChunkDeltaUpdateS2CPacket.CODEC} takes a plain {@link PacketByteBuf}.
     * We use {@code visitUpdates(BiConsumer)} to iterate entries without needing
     * reflection or private field access.
     *
     * <p>Re-encoding limitation: after filtering, we need to reconstruct the packet.
     * We do this by binary manipulation of the original wire format rather than
     * re-encoding through the packet constructor (which requires a live ChunkSection).
     * Wire format of SECTION_BLOCKS_UPDATE:
     * <pre>
     *   VarInt packetId
     *   long   sectionPos (encoded as long)
     *   VarInt count
     *   [ VarLong(stateId << 12 | shortLocalPos) ... ]
     * </pre>
     *
     * @return re-encoded payload with survivors only, or {@code null} if all dropped
     */
    private byte[] rewriteSectionBlocksUpdate(int sourceIdx, int tickAbs, byte[] payload) {
        ByteBuf raw = Unpooled.wrappedBuffer(payload);

        // Save the packetId bytes
        int packetIdStart = raw.readerIndex();
        readVarIntFromBuf(raw);
        int bodyStart = raw.readerIndex();

        // Decode via CODEC (PacketByteBuf wraps the body portion)
        ByteBuf bodyBuf = raw.slice(bodyStart, raw.readableBytes());
        PacketByteBuf pktBuf = new PacketByteBuf(bodyBuf);
        ChunkDeltaUpdateS2CPacket pkt = ChunkDeltaUpdateS2CPacket.CODEC.decode(pktBuf);

        // Collect survivors: (shortLocalPos, blockStateId)
        List<long[]> survivors = new ArrayList<>();

        pkt.visitUpdates((blockPos, blockState) -> {
            int bsId = Block.getRawIdFromState(blockState);
            boolean accepted = worldMerger.acceptBlockUpdate(
                    dimensionFor(sourceIdx),
                    blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                    tickAbs, bsId, sourceIdx);
            if (accepted) {
                // Reconstruct the VarLong entry: stateId << 12 | shortLocalPos
                ChunkSectionPos sectionPos = ChunkSectionPos.from(blockPos);
                short localPos = ChunkSectionPos.packLocal(blockPos);
                long entry = ((long) bsId << 12) | (localPos & 0xFFFL);
                survivors.add(new long[]{entry});
            }
        });

        if (survivors.isEmpty()) {
            return null; // All entries were stale — drop the packet
        }

        if (survivors.size() == countEntriesInSectionPacket(payload, bodyStart)) {
            // All survived — return original unchanged
            return payload;
        }

        // Re-encode with survivors only
        // Wire format: [packetId bytes] + long(sectionPos) + VarInt(count) + [VarLong entries]
        ByteBuf out = Unpooled.buffer(payload.length + 16);

        // Copy original packetId VarInt
        out.writeBytes(payload, packetIdStart, bodyStart - packetIdStart);

        // Re-read sectionPos from body (long, 8 bytes)
        ByteBuf bodyForSectionPos = Unpooled.wrappedBuffer(payload, bodyStart, payload.length - bodyStart);
        long sectionPosLong = bodyForSectionPos.readLong();

        out.writeLong(sectionPosLong);

        // Write count as VarInt
        VarInts.writeVarInt(out, survivors.size());

        // Write each survivor as VarLong
        for (long[] entry : survivors) {
            writeVarLong(out, entry[0]);
        }

        byte[] result = new byte[out.readableBytes()];
        out.readBytes(result);
        return result;
    }

    /**
     * Counts the number of entries in a SECTION_BLOCKS_UPDATE payload by parsing
     * the VarInt count field from the body.
     * Returns 0 on any parse error.
     */
    private static int countEntriesInSectionPacket(byte[] payload, int bodyStart) {
        try {
            // body: long(8 bytes) + VarInt count + entries
            ByteBuf buf = Unpooled.wrappedBuffer(payload, bodyStart, payload.length - bodyStart);
            buf.skipBytes(8); // sectionPos long
            return VarInts.readVarInt(buf);
        } catch (Exception e) {
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Reads a VarInt from {@code buf} advancing reader index, discarding the value. */
    private static void readVarIntFromBuf(ByteBuf buf) {
        VarInts.readVarInt(buf);
    }

    /** Reads and returns the first VarInt from a raw byte array without modifying it. */
    private static int readFirstVarInt(byte[] payload) {
        int value = 0, shift = 0;
        for (int i = 0; i < 5 && i < payload.length; i++) {
            byte b = payload[i];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
        }
        return value;
    }

    /**
     * Writes a VarLong to {@code buf}.
     * Used to re-encode SECTION_BLOCKS_UPDATE entries (stateId << 12 | localPos).
     */
    private static void writeVarLong(ByteBuf buf, long value) {
        while ((value & ~0x7FL) != 0) {
            buf.writeByte((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte((int) value);
    }
}
