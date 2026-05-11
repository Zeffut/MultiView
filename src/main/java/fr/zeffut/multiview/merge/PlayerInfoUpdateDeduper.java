package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.registry.DynamicRegistryManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Dedup-by-UUID for {@code PlayerListS2CPacket} (PLAYER_INFO_UPDATE / PLAYER_INFO_REMOVE).
 *
 * <p>The default {@link GlobalDeduper} content-hash misses these packets because each source
 * records slightly different bytes for the same join event (the {@code UPDATE_LATENCY}
 * action carries a per-source ping value). Result: every cross-source player join produces
 * "X joined the game" twice (or more) in the merged replay's chat.
 *
 * <p>Strategy: track UUIDs that have already been ADD_PLAYER'd in the merged stream. When
 * a subsequent {@code PLAYER_INFO_UPDATE} contains an {@code ADD_PLAYER} action with a single
 * already-known UUID, drop the packet. Multi-entry packets fall through (the simpler
 * heuristic avoids re-encoding for safety). PLAYER_INFO_REMOVE is parsed to clear UUIDs
 * so a leave/rejoin cycle is correctly re-announced.
 *
 * <p>Wire format expected (MC 1.21.x):
 * <pre>
 *   PLAYER_INFO_UPDATE := VarInt packetId
 *                       + 1-byte EnumSet&lt;Action&gt; (bit 0 = ADD_PLAYER)
 *                       + VarInt entryCount
 *                       + entryCount × { UUID(16) + per-action fields ... }
 *
 *   PLAYER_INFO_REMOVE := VarInt packetId
 *                       + VarInt count
 *                       + count × UUID(16)
 * </pre>
 *
 * @implNote Non thread-safe. Called from the single-threaded merge pipeline.
 */
public final class PlayerInfoUpdateDeduper {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerInfoUpdateDeduper.class);
    private static final int ADD_PLAYER_BIT = 0x01;

    private final Set<UUID> announcedUuids = new HashSet<>();
    private int duplicateAddDropped;
    private int removesProcessed;
    private int codecDecodedCount;
    private int codecFailureCount;
    /** True if we successfully verified the MC codec is available (Bootstrap initialized). */
    private final boolean codecAvailable;

    public PlayerInfoUpdateDeduper() {
        boolean ok;
        try {
            // Touch the codec field to trigger class initialization. Throws if Bootstrap
            // is missing (e.g. unit tests without MC runtime) — fall back to heuristic
            // first-UUID dedup in that case.
            Object _probe = PlayerListS2CPacket.CODEC;
            ok = _probe != null;
        } catch (Throwable t) {
            ok = false;
        }
        this.codecAvailable = ok;
    }

    /**
     * Decide whether a {@code PLAYER_INFO_UPDATE} packet payload should be emitted.
     *
     * @param payload raw GamePacket bytes (starts with VarInt packetId)
     * @return {@code true} to emit, {@code false} to drop as a duplicate ADD_PLAYER
     */
    public boolean shouldEmitInfoUpdate(byte[] payload) {
        if (payload == null || payload.length < 4) return true;

        // Preferred path: full decode via MC's PacketCodec — extracts every entry's UUID,
        // letting us drop only when EVERY entry is already known. Re-orderings of the
        // same bulk across sources still get caught.
        if (codecAvailable) {
            try {
                ByteBuf raw = Unpooled.wrappedBuffer(payload);
                VarInts.readVarInt(raw); // skip packetId
                RegistryByteBuf rbuf = new RegistryByteBuf(raw, DynamicRegistryManager.EMPTY);
                PlayerListS2CPacket pkt = PlayerListS2CPacket.CODEC.decode(rbuf);

                if (!pkt.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) {
                    return true; // not an ADD packet — leave content-hash dedup to the caller
                }

                List<UUID> newUuids = new ArrayList<>();
                boolean allKnown = true;
                for (PlayerListS2CPacket.Entry entry : pkt.getEntries()) {
                    UUID u = entry.profileId();
                    if (u != null && !announcedUuids.contains(u)) {
                        allKnown = false;
                        newUuids.add(u);
                    }
                }

                codecDecodedCount++;
                if (codecDecodedCount <= 3) {
                    LOG.warn("[PIU-DIAG] codec decoded ok #{}: actions={} entries={} allKnown={}",
                            codecDecodedCount, pkt.getActions(), pkt.getEntries().size(), allKnown);
                }

                if (allKnown) {
                    duplicateAddDropped++;
                    return false;
                }
                announcedUuids.addAll(newUuids);
                return true;
            } catch (Throwable t) {
                codecFailureCount++;
                if (codecFailureCount <= 3) {
                    LOG.warn("[PIU-DIAG] codec decode failed #{}: {} {}",
                            codecFailureCount, t.getClass().getSimpleName(), t.getMessage());
                }
                // fall through to heuristic
            }
        }

        // Fallback path (unit tests, or codec failure): heuristic single-UUID dedup.
        ByteBuf buf = Unpooled.wrappedBuffer(payload);
        try {
            VarInts.readVarInt(buf); // skip packetId
            if (!buf.isReadable()) return true;
            int actionsBitmask = buf.readUnsignedByte();
            boolean hasAdd = (actionsBitmask & ADD_PLAYER_BIT) != 0;
            int count = VarInts.readVarInt(buf);

            if (count < 1 || buf.readableBytes() < 16) return true;

            long msb = buf.readLong();
            long lsb = buf.readLong();
            UUID firstUuid = new UUID(msb, lsb);

            if (hasAdd) {
                if (announcedUuids.contains(firstUuid)) {
                    duplicateAddDropped++;
                    return false;
                }
                announcedUuids.add(firstUuid);
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Process a {@code PLAYER_INFO_REMOVE} packet payload — drop the listed UUIDs from
     * {@code announcedUuids} so a subsequent leave/rejoin is correctly re-announced.
     * Always returns {@code true} (the packet itself is emitted unchanged).
     */
    public boolean shouldEmitInfoRemove(byte[] payload) {
        if (payload == null || payload.length < 4) return true;
        ByteBuf buf = Unpooled.wrappedBuffer(payload);
        try {
            VarInts.readVarInt(buf); // skip packetId
            int count = VarInts.readVarInt(buf);
            for (int i = 0; i < count && buf.readableBytes() >= 16; i++) {
                long msb = buf.readLong();
                long lsb = buf.readLong();
                announcedUuids.remove(new UUID(msb, lsb));
                removesProcessed++;
            }
        } catch (Exception ignore) {
            // best-effort
        }
        return true;
    }

    public int duplicateAddDropped() { return duplicateAddDropped; }
    public int removesProcessed() { return removesProcessed; }
}
