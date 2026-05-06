package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.packet.PlayPackets;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites ENTITY GamePacket payloads to remap local entity IDs to global IDs.
 *
 * <h2>Decode strategy per packet type</h2>
 * <ul>
 *   <li><b>ADD_ENTITY</b> (EntitySpawnS2CPacket): Use {@code EntitySpawnS2CPacket.CODEC}
 *       (requires RegistryByteBuf) to fully decode, extract entityId+UUID+type+pos,
 *       call {@link EntityMerger#registerAddEntity}, reconstruct with globalId, re-encode.</li>
 *   <li><b>REMOVE_ENTITIES</b>: Binary decode — VarInt packetId + VarInt count + N VarInt IDs.
 *       Remap each ID, re-encode.</li>
 *   <li><b>ENTITY_POSITION_SYNC, MOVE_ENTITY_POS, MOVE_ENTITY_ROT, MOVE_ENTITY_POS_ROT,
 *       TELEPORT_ENTITY, SET_ENTITY_DATA, ENTITY_EVENT, SET_ENTITY_MOTION, ANIMATE,
 *       ROTATE_HEAD, UPDATE_ATTRIBUTES, UPDATE_MOB_EFFECT, REMOVE_MOB_EFFECT,
 *       DAMAGE_EVENT</b>: Binary splice — entity ID is the VarInt immediately following
 *       the packet ID VarInt.</li>
 *   <li><b>SET_ENTITY_LINK</b>: Two entity IDs (attached + holding), both remapped.</li>
 *   <li><b>SET_PASSENGERS</b>: One vehicle ID + VarInt count + N passenger IDs.</li>
 *   <li><b>TAKE_ITEM_ENTITY</b>: Two entity IDs (item entity + collector), then VarInt count.</li>
 *   <li><b>SET_EQUIPMENT</b>: Single entity ID (first VarInt after packetId).</li>
 * </ul>
 *
 * <p>Unknown entity packets fall through to {@link #rewriteSingleEntityId} which uses
 * the binary splice approach on the assumption that entity ID is always first.
 *
 * @implNote Non thread-safe. Called from the single-threaded MergeOrchestrator pipeline.
 */
public final class EntityPacketRewriter {

    private static final Logger LOG = LoggerFactory.getLogger(EntityPacketRewriter.class);

    /**
     * If true, PlayStateFactories was not available and entity rewriting is disabled.
     * All packets will pass through unchanged (same as Phase 3 behaviour).
     */
    private final boolean fallbackMode;

    /**
     * DynamicRegistryManager used for decoding/encoding ADD_ENTITY payloads.
     * Needed because {@code EntitySpawnS2CPacket.CODEC} looks up the
     * {@code minecraft:entity_type} registry at runtime via
     * {@link RegistryByteBuf#getRegistryManager()}.
     *
     * <p>Built from the static {@link Registries#ENTITY_TYPE} after
     * {@code Bootstrap.initialize()} has populated it (always true in the
     * Minecraft Fabric client environment). If construction fails (e.g. in
     * unit tests that bypass Bootstrap), {@link #addEntityDecodeAvailable} is
     * set to {@code false} and ADD_ENTITY packets fall through as passthrough.
     */
    private final DynamicRegistryManager registryManager;

    /**
     * True iff {@link #registryManager} was successfully built AND contains a
     * non-empty {@code minecraft:entity_type} registry. When false, ADD_ENTITY
     * decode is bypassed (passthrough) but other entity packets still get
     * rewritten via the binary splice path (which does not need a DRM).
     */
    private final boolean addEntityDecodeAvailable;

    /** Protocol IDs for entity packet types, resolved once at construction time. */
    private int idAddEntity;
    private int idRemoveEntities;
    private int idMoveEntityPos;
    private int idMoveEntityRot;
    private int idMoveEntityPosRot;
    private int idTeleportEntity;
    private int idEntityPositionSync;
    private int idSetEntityData;
    private int idEntityEvent;
    private int idSetEntityMotion;
    private int idAnimate;
    private int idRotateHead;
    private int idSetEquipment;
    private int idSetEntityLink;
    private int idSetPassengers;
    private int idUpdateAttributes;
    private int idUpdateMobEffect;
    private int idRemoveMobEffect;
    private int idDamageEvent;
    private int idTakeItemEntity;

    // Per-source local player entity IDs (discovered when AddEntity UUID matches CreatePlayer UUID)
    private final int[] localPlayerEntityId;
    // Per-source CreatePlayer UUIDs — extracted once from the first CreatePlayer seen per source
    private final UUID[] localPlayerUuid;

    private final EntityMerger entityMerger;
    private final IdRemapper idRemapper;
    private final SourcePovTracker povTracker;

    public EntityPacketRewriter(EntityMerger entityMerger, IdRemapper idRemapper,
                                SourcePovTracker povTracker, int sourceCount) {
        this.entityMerger = entityMerger;
        this.idRemapper = idRemapper;
        this.povTracker = povTracker;
        this.localPlayerEntityId = new int[sourceCount];
        this.localPlayerUuid = new UUID[sourceCount];
        java.util.Arrays.fill(this.localPlayerEntityId, -1);

        // Resolve protocol IDs once. If PlayStateFactories is unavailable (e.g. no Bootstrap
        // in unit test context), fall back to passthrough mode (same as Phase 3).
        boolean fb;
        try {
            idAddEntity         = GamePacketDispatch.findId(PlayPackets.ADD_ENTITY);
            idRemoveEntities    = GamePacketDispatch.findId(PlayPackets.REMOVE_ENTITIES);
            idMoveEntityPos     = GamePacketDispatch.findId(PlayPackets.MOVE_ENTITY_POS);
            idMoveEntityRot     = GamePacketDispatch.findId(PlayPackets.MOVE_ENTITY_ROT);
            idMoveEntityPosRot  = GamePacketDispatch.findId(PlayPackets.MOVE_ENTITY_POS_ROT);
            idTeleportEntity    = GamePacketDispatch.findId(PlayPackets.TELEPORT_ENTITY);
            idEntityPositionSync= GamePacketDispatch.findId(PlayPackets.ENTITY_POSITION_SYNC);
            idSetEntityData     = GamePacketDispatch.findId(PlayPackets.SET_ENTITY_DATA);
            idEntityEvent       = GamePacketDispatch.findId(PlayPackets.ENTITY_EVENT);
            idSetEntityMotion   = GamePacketDispatch.findId(PlayPackets.SET_ENTITY_MOTION);
            idAnimate           = GamePacketDispatch.findId(PlayPackets.ANIMATE);
            idRotateHead        = GamePacketDispatch.findId(PlayPackets.ROTATE_HEAD);
            idSetEquipment      = GamePacketDispatch.findId(PlayPackets.SET_EQUIPMENT);
            idSetEntityLink     = GamePacketDispatch.findId(PlayPackets.SET_ENTITY_LINK);
            idSetPassengers     = GamePacketDispatch.findId(PlayPackets.SET_PASSENGERS);
            idUpdateAttributes  = GamePacketDispatch.findId(PlayPackets.UPDATE_ATTRIBUTES);
            idUpdateMobEffect   = GamePacketDispatch.findId(PlayPackets.UPDATE_MOB_EFFECT);
            idRemoveMobEffect   = GamePacketDispatch.findId(PlayPackets.REMOVE_MOB_EFFECT);
            idDamageEvent       = GamePacketDispatch.findId(PlayPackets.DAMAGE_EVENT);
            idTakeItemEntity    = GamePacketDispatch.findId(PlayPackets.TAKE_ITEM_ENTITY);
            fb = false;
        } catch (Throwable t) {
            LOG.warn("EntityPacketRewriter: PlayStateFactories unavailable ("
                    + t.getClass().getSimpleName() + "), entity rewriting disabled (fallback passthrough).");
            fb = true;
        }
        this.fallbackMode = fb;

        // Resolve a DynamicRegistryManager containing minecraft:entity_type.
        // This is required to decode ADD_ENTITY payloads via EntitySpawnS2CPacket.CODEC
        // (see RegistryByteBuf.getRegistryManager() + PacketCodecs.registryValue).
        //
        // Strategy: build an ImmutableImpl DRM directly from the static
        // Registries.ENTITY_TYPE registry. After Bootstrap.initialize() runs
        // (always true in the Fabric client), this registry is fully populated.
        // In unit tests that skip Bootstrap, the registry is empty — we detect
        // this via size() and fall back to passthrough on ADD_ENTITY only.
        DynamicRegistryManager drm;
        boolean decodeOk;
        try {
            Registry<?> entityTypeRegistry = Registries.ENTITY_TYPE;
            drm = new DynamicRegistryManager.ImmutableImpl(List.<Registry<?>>of(entityTypeRegistry));
            decodeOk = entityTypeRegistry.size() > 0;
            if (!decodeOk) {
                LOG.debug("EntityPacketRewriter: Registries.ENTITY_TYPE is empty "
                        + "(Bootstrap not initialized?); ADD_ENTITY decode disabled.");
            }
        } catch (Throwable t) {
            LOG.warn("EntityPacketRewriter: failed to build DynamicRegistryManager ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + "); ADD_ENTITY decode disabled.");
            drm = DynamicRegistryManager.EMPTY;
            decodeOk = false;
        }
        this.registryManager = drm;
        this.addEntityDecodeAvailable = decodeOk;
    }

    /**
     * Records the local player UUID for {@code sourceIdx} from a CreatePlayer payload.
     * The CreatePlayer payload starts with 16 bytes = UUID (most-significant + least-significant longs).
     * This must be called before any entity packets are processed for this source.
     *
     * @param sourceIdx  source index
     * @param createPlayerBytes raw bytes of the CreatePlayer action payload
     */
    public void recordLocalPlayerUuid(int sourceIdx, byte[] createPlayerBytes) {
        if (createPlayerBytes.length < 16) return;
        ByteBuf buf = Unpooled.wrappedBuffer(createPlayerBytes);
        long msb = buf.readLong();
        long lsb = buf.readLong();
        localPlayerUuid[sourceIdx] = new UUID(msb, lsb);
    }

    /**
     * Rewrites an ENTITY GamePacket payload: remaps entity IDs from source-local to global.
     * Returns the rewritten payload (may be the original reference if no rewrite was needed).
     *
     * @param sourceIdx  source index (for IdRemapper lookup)
     * @param tickAbs    absolute tick (for EntityMerger + SourcePovTracker)
     * @param payload    raw GamePacket bytes (starts with VarInt packetId)
     * @return rewritten payload bytes
     */
    public byte[] rewrite(int sourceIdx, int tickAbs, byte[] payload) {
        // If PlayStateFactories was not available, fall through to passthrough
        if (fallbackMode) return payload;

        // Peek at the packet ID
        int pid = readFirstVarInt(payload);

        try {
            if (pid == idAddEntity) {
                // ADD_ENTITY requires a populated minecraft:entity_type registry.
                // If unavailable (unit test without Bootstrap), pass through unchanged:
                // the entity stays with its source-local ID, but at least playback
                // does not crash. In the real Fabric runtime Registries.ENTITY_TYPE
                // is always populated.
                if (!addEntityDecodeAvailable) return payload;
                return rewriteAddEntity(sourceIdx, tickAbs, payload);
            } else if (pid == idRemoveEntities) {
                return rewriteRemoveEntities(sourceIdx, payload);
            } else if (pid == idSetEntityLink) {
                return rewriteSetEntityLink(sourceIdx, payload);
            } else if (pid == idSetPassengers) {
                return rewriteSetPassengers(sourceIdx, payload);
            } else if (pid == idTakeItemEntity) {
                return rewriteTakeItemEntity(sourceIdx, payload);
            } else if (pid == idEntityEvent) {
                // EntityStatusS2CPacket (ENTITY_EVENT) uses a fixed int32 (not VarInt)
                // for the entity ID — see EntityStatusS2CPacket.readInt() / writeInt().
                // rewriteSingleEntityId would misread it as a VarInt, producing a payload
                // with 3 extra bytes that Flashback's handler cannot consume.
                return rewriteEntityEventInt32(sourceIdx, payload);
            } else {
                // All other single-entity-ID packets: binary splice (VarInt entity ID)
                return rewriteSingleEntityId(sourceIdx, payload);
            }
        } catch (Exception e) {
            // If rewriting fails (e.g. entity not yet registered), log and pass through
            LOG.warn("EntityPacketRewriter: failed to rewrite packetId=" + pid
                    + " from source=" + sourceIdx + ": " + e.getMessage());
            return payload;
        }
    }

    /**
     * Processes a MoveEntities action payload to update SourcePovTracker for the local player.
     * Does not modify the payload — MoveEntities passthrough is retained (see TODO below).
     *
     * <p>Format: {@code VarInt dimensionCount; foreach: ResourceKey<Level> + VarInt entityCount;
     * foreach entity: VarInt entityId + double x + double y + double z + ...}
     *
     * <p>TODO Phase 4.E: remap entity IDs within MoveEntities payloads.
     */
    public void processMoveEntities(int sourceIdx, int tickAbs, byte[] payload) {
        // POV tracking is best-effort — skip entirely in fallback mode
        if (fallbackMode) return;
        // Only track local player positions if we know the local player entity ID
        int localEid = localPlayerEntityId[sourceIdx];
        if (localEid < 0) return;

        try {
            ByteBuf buf = Unpooled.wrappedBuffer(payload);
            int dimensionCount = VarInts.readVarInt(buf);
            for (int d = 0; d < dimensionCount; d++) {
                // ResourceKey<Level> = ResourceLocation = 2 VarInt-prefixed strings (namespace + path)
                // Actually ResourceLocation.encode writes: VarInt-length string (full "namespace:path")
                skipResourceLocation(buf);
                int entityCount = VarInts.readVarInt(buf);
                for (int e = 0; e < entityCount; e++) {
                    int eid = VarInts.readVarInt(buf);
                    double x = buf.readDouble();
                    double y = buf.readDouble();
                    double z = buf.readDouble();
                    // float yaw + float pitch + float headYaw + boolean onGround
                    buf.skipBytes(4 + 4 + 4 + 1);
                    if (eid == localEid) {
                        povTracker.update(sourceIdx, tickAbs, x, y, z);
                    }
                }
            }
        } catch (Exception e) {
            // Non-fatal: POV tracking is best-effort
            LOG.debug("EntityPacketRewriter: MoveEntities parse failed for POV tracking: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Private rewrite methods
    // -------------------------------------------------------------------------

    /**
     * Rewrites ADD_ENTITY (EntitySpawnS2CPacket) using the MC codec for full decode.
     *
     * <p>Uses a {@link RegistryByteBuf} bound to {@link #registryManager}, which
     * contains the populated {@code minecraft:entity_type} registry — required
     * by {@code EntitySpawnS2CPacket.CODEC} via {@code PacketCodecs.registryValue}.
     *
     * <p>Must only be called when {@link #addEntityDecodeAvailable} is {@code true};
     * otherwise the registry lookup would throw
     * {@code "Missing registry: minecraft:entity_type"}.
     */
    private byte[] rewriteAddEntity(int sourceIdx, int tickAbs, byte[] payload) {
        ByteBuf raw = Unpooled.wrappedBuffer(payload);

        // Save the packetId bytes (VarInt, 1-5 bytes)
        int packetIdStart = raw.readerIndex();
        readVarIntFromBuf(raw); // skip packetId
        int bodyStart = raw.readerIndex();

        // Decode body with EntitySpawnS2CPacket.CODEC
        ByteBuf bodyBuf = raw.slice(bodyStart, raw.readableBytes());
        RegistryByteBuf registryBuf = new RegistryByteBuf(bodyBuf, registryManager);

        EntitySpawnS2CPacket spawn = EntitySpawnS2CPacket.CODEC.decode(registryBuf);

        int localId = spawn.getEntityId();
        UUID uuid = spawn.getUuid();
        double x = spawn.getX();
        double y = spawn.getY();
        double z = spawn.getZ();

        // Get entity type as string (e.g. "minecraft:zombie")
        String typeStr = Registries.ENTITY_TYPE.getId(spawn.getEntityType()).toString();

        // Check if this is the local player (UUID matches CreatePlayer UUID)
        if (uuid != null && uuid.equals(localPlayerUuid[sourceIdx])) {
            localPlayerEntityId[sourceIdx] = localId;
            // Update POV position immediately
            povTracker.update(sourceIdx, tickAbs, x, y, z);
        }

        // Register with EntityMerger — gets globalId (new or merged)
        int globalId = entityMerger.registerAddEntity(sourceIdx, localId, uuid, typeStr, tickAbs, x, y, z);

        // Reconstruct the packet with globalId
        EntitySpawnS2CPacket remapped = new EntitySpawnS2CPacket(
                globalId, uuid, x, y, z,
                spawn.getYaw(), spawn.getPitch(),
                spawn.getEntityType(), spawn.getEntityData(),
                spawn.getVelocity(), spawn.getHeadYaw()
        );

        // Re-encode: packetId bytes (original) + new body
        ByteBuf out = Unpooled.buffer(payload.length + 8);
        // Copy original packetId VarInt bytes
        out.writeBytes(payload, packetIdStart, bodyStart - packetIdStart);
        // Encode remapped packet body
        ByteBuf newBodyBuf = Unpooled.buffer(payload.length);
        RegistryByteBuf outRegistryBuf = new RegistryByteBuf(newBodyBuf, registryManager);
        EntitySpawnS2CPacket.CODEC.encode(outRegistryBuf, remapped);
        out.writeBytes(newBodyBuf);

        byte[] result = new byte[out.readableBytes()];
        out.readBytes(result);
        return result;
    }

    /**
     * Rewrites REMOVE_ENTITIES (EntitiesDestroyS2CPacket).
     * Format: VarInt packetId + VarInt count + [count x VarInt entityId]
     *
     * <p>IDs that have no mapping (not yet registered) are remapped via {@code assign}
     * to avoid dropping the packet — the global ID will be unused but harmless.
     */
    private byte[] rewriteRemoveEntities(int sourceIdx, byte[] payload) {
        ByteBuf in = Unpooled.wrappedBuffer(payload);

        // Read packetId
        int packetIdStart = in.readerIndex();
        readVarIntFromBuf(in);
        int afterPid = in.readerIndex();

        // Read count
        int count = VarInts.readVarInt(in);

        // Read entity IDs
        int[] localIds = new int[count];
        for (int i = 0; i < count; i++) {
            localIds[i] = VarInts.readVarInt(in);
        }

        // Rebuild payload
        ByteBuf out = Unpooled.buffer(payload.length + 5 * count);
        // packetId
        out.writeBytes(payload, packetIdStart, afterPid - packetIdStart);
        // count
        VarInts.writeVarInt(out, count);
        // remapped IDs + unregister to allow same-UUID respawns (player leave→rejoin)
        for (int localId : localIds) {
            int globalId = idRemapper.contains(sourceIdx, localId)
                    ? idRemapper.remap(sourceIdx, localId)
                    : idRemapper.assign(sourceIdx, localId); // assign if unseen (edge case)
            VarInts.writeVarInt(out, globalId);
        }

        byte[] result = new byte[out.readableBytes()];
        out.readBytes(result);
        return result;
    }

    /**
     * Rewrites SET_ENTITY_LINK (EntityAttachS2CPacket).
     * Format: VarInt packetId + int32 attachedEntityId + int32 holdingEntityId
     *
     * <p>NOTE: EntityAttachS2CPacket uses int32 (not VarInt) for both IDs according
     * to the MC wire format. We remap both.
     */
    private byte[] rewriteSetEntityLink(int sourceIdx, byte[] payload) {
        ByteBuf in = Unpooled.wrappedBuffer(payload);

        // Read and copy packetId
        int packetIdStart = in.readerIndex();
        readVarIntFromBuf(in);
        int afterPid = in.readerIndex();

        // EntityAttachS2CPacket uses VarInt for attached, then VarInt for holding
        // Let's verify by checking the CODEC — actually looking at the binary format:
        // The MC protocol uses VarInt for entity IDs in most packets.
        // EntityAttachS2CPacket writes: buf.writeInt(attachedEntityId); buf.writeInt(holdingEntityId);
        // Wait — let me check if it's int or VarInt
        // From the javap output and MC source: EntityAttachS2CPacket uses int (fixed 4 bytes) for both IDs
        // Actually MC 1.21 uses VarInt for most entity IDs. Let's use binary splice as fallback.
        // To be safe, use the single-entity splice for attached, and handle holding specially.

        // Use binary splice approach: read packetId, then two VarInts
        int attached = VarInts.readVarInt(in);
        int holding  = VarInts.readVarInt(in);
        // remaining bytes
        int remaining = in.readableBytes();

        int globalAttached = safeRemap(sourceIdx, attached);
        // holding can be -1 (no holder in MC). Remap only if positive.
        int globalHolding = holding < 0 ? holding : safeRemap(sourceIdx, holding);

        ByteBuf out = Unpooled.buffer(payload.length + 8);
        out.writeBytes(payload, packetIdStart, afterPid - packetIdStart);
        VarInts.writeVarInt(out, globalAttached);
        VarInts.writeVarInt(out, globalHolding);
        if (remaining > 0) {
            out.writeBytes(payload, payload.length - remaining, remaining);
        }

        byte[] result = new byte[out.readableBytes()];
        out.readBytes(result);
        return result;
    }

    /**
     * Rewrites SET_PASSENGERS (EntityPassengersSetS2CPacket).
     * Format: VarInt packetId + VarInt vehicleId + VarInt count + [count x VarInt passengerId]
     */
    private byte[] rewriteSetPassengers(int sourceIdx, byte[] payload) {
        ByteBuf in = Unpooled.wrappedBuffer(payload);

        int packetIdStart = in.readerIndex();
        readVarIntFromBuf(in);
        int afterPid = in.readerIndex();

        int vehicleId = VarInts.readVarInt(in);
        int count = VarInts.readVarInt(in);
        int[] passengerIds = new int[count];
        for (int i = 0; i < count; i++) {
            passengerIds[i] = VarInts.readVarInt(in);
        }

        int globalVehicle = safeRemap(sourceIdx, vehicleId);

        ByteBuf out = Unpooled.buffer(payload.length + 5 * (count + 1));
        out.writeBytes(payload, packetIdStart, afterPid - packetIdStart);
        VarInts.writeVarInt(out, globalVehicle);
        VarInts.writeVarInt(out, count);
        for (int pid : passengerIds) {
            VarInts.writeVarInt(out, safeRemap(sourceIdx, pid));
        }

        byte[] result = new byte[out.readableBytes()];
        out.readBytes(result);
        return result;
    }

    /**
     * Rewrites TAKE_ITEM_ENTITY (ItemPickupAnimationS2CPacket).
     * Format: VarInt packetId + VarInt entityId + VarInt collectorEntityId + VarInt stackAmount
     */
    private byte[] rewriteTakeItemEntity(int sourceIdx, byte[] payload) {
        ByteBuf in = Unpooled.wrappedBuffer(payload);

        int packetIdStart = in.readerIndex();
        readVarIntFromBuf(in);
        int afterPid = in.readerIndex();

        int entityId    = VarInts.readVarInt(in);
        int collectorId = VarInts.readVarInt(in);
        // stackAmount: VarInt — read and re-write as-is
        int afterSecondId = in.readerIndex();
        int stackAmount = VarInts.readVarInt(in);

        int globalEntity    = safeRemap(sourceIdx, entityId);
        int globalCollector = safeRemap(sourceIdx, collectorId);

        ByteBuf out = Unpooled.buffer(payload.length + 8);
        out.writeBytes(payload, packetIdStart, afterPid - packetIdStart);
        VarInts.writeVarInt(out, globalEntity);
        VarInts.writeVarInt(out, globalCollector);
        VarInts.writeVarInt(out, stackAmount);

        byte[] result = new byte[out.readableBytes()];
        out.readBytes(result);
        return result;
    }

    /**
     * Binary splice: remaps the VarInt that immediately follows the VarInt packetId.
     * This is the entity ID for all single-entity entity packets.
     *
     * @param sourceIdx source index
     * @param payload   raw GamePacket bytes
     * @return rewritten payload
     */
    private byte[] rewriteSingleEntityId(int sourceIdx, byte[] payload) {
        ByteBuf in = Unpooled.wrappedBuffer(payload);

        // Read packetId (skip)
        readVarIntFromBuf(in);
        int entityIdStart = in.readerIndex();

        // Read entityId
        int localId = VarInts.readVarInt(in);
        int afterEntityId = in.readerIndex();

        int globalId = safeRemap(sourceIdx, localId);

        // Rebuild: original packetId bytes + VarInt(globalId) + rest
        ByteBuf out = Unpooled.buffer(payload.length + 5);
        out.writeBytes(payload, 0, entityIdStart);
        VarInts.writeVarInt(out, globalId);
        int remaining = payload.length - afterEntityId;
        if (remaining > 0) {
            out.writeBytes(payload, afterEntityId, remaining);
        }

        byte[] result = new byte[out.readableBytes()];
        out.readBytes(result);
        return result;
    }

    /**
     * Rewrites ENTITY_EVENT (EntityStatusS2CPacket) which uses a <b>fixed int32</b>
     * (big-endian 4 bytes) for the entity ID, unlike most entity packets that use VarInt.
     *
     * <p>Wire format: {@code VarInt packetId + int32 entityId + byte eventId}
     *
     * <p>If this packet were routed through {@link #rewriteSingleEntityId}, the VarInt
     * reader would misinterpret the first byte of the int32 as a complete 1-byte VarInt
     * (for entity IDs 0–127, whose first byte has the continuation bit clear).  The
     * rewriter would then write a 4-byte global-ID VarInt in place of that 1 byte,
     * leaving the last 3 bytes of the original int32 as unexpected trailing data.
     * Result: payload grows by 3 bytes, and Flashback reports
     * "Had N bytes available, only read N-3".
     *
     * @param sourceIdx source index
     * @param payload   raw GamePacket bytes
     * @return rewritten payload with remapped int32 entity ID
     */
    private byte[] rewriteEntityEventInt32(int sourceIdx, byte[] payload) {
        ByteBuf in = Unpooled.wrappedBuffer(payload);

        // Read packetId (VarInt, 1–5 bytes)
        int packetIdStart = in.readerIndex();
        readVarIntFromBuf(in);
        int afterPid = in.readerIndex();

        // Read entity ID as fixed int32 (big-endian, 4 bytes)
        int localId = in.readInt();
        int afterEntityId = in.readerIndex(); // = afterPid + 4

        int globalId = safeRemap(sourceIdx, localId);

        // Rebuild: packetId bytes + int32(globalId) + rest
        ByteBuf out = Unpooled.buffer(payload.length);
        out.writeBytes(payload, packetIdStart, afterPid - packetIdStart);
        out.writeInt(globalId);
        int remaining = payload.length - afterEntityId;
        if (remaining > 0) {
            out.writeBytes(payload, afterEntityId, remaining);
        }

        byte[] result = new byte[out.readableBytes()];
        out.readBytes(result);
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Remaps an entity ID. If not yet registered (e.g. entity was spawned before
     * the merge window), assigns a fresh global ID to avoid dropping packets.
     */
    private int safeRemap(int sourceIdx, int localId) {
        if (idRemapper.contains(sourceIdx, localId)) {
            return idRemapper.remap(sourceIdx, localId);
        }
        // Entity not registered via AddEntity (e.g. was in snapshot): assign new global ID
        return idRemapper.assign(sourceIdx, localId);
    }

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
     * Skips a ResourceLocation (= VarInt-prefixed UTF-8 string) in {@code buf}.
     * Used when parsing MoveEntities dimension keys.
     */
    private static void skipResourceLocation(ByteBuf buf) {
        int len = VarInts.readVarInt(buf);
        buf.skipBytes(len);
    }

    // -------------------------------------------------------------------------
    // Package-private static helpers — used by unit tests only
    // -------------------------------------------------------------------------

    /**
     * Package-private static splice helper exposed for unit tests.
     * Replaces the VarInt entity ID (second VarInt in the payload, after the packet ID)
     * with {@code newEntityId}, preserving all other bytes.
     *
     * <p>This mirrors the logic of {@link #rewriteSingleEntityId} but takes the
     * new entity ID directly, without needing an IdRemapper or Bootstrap.
     */
    static byte[] spliceVarIntEntityId(byte[] payload, int newEntityId) {
        ByteBuf in = Unpooled.wrappedBuffer(payload);
        readVarIntFromBuf(in);            // skip packetId
        int entityIdStart = in.readerIndex();
        VarInts.readVarInt(in);           // skip old entity ID
        int afterEntityId = in.readerIndex();

        ByteBuf out = Unpooled.buffer(payload.length + 5);
        out.writeBytes(payload, 0, entityIdStart);
        VarInts.writeVarInt(out, newEntityId);
        int remaining = payload.length - afterEntityId;
        if (remaining > 0) {
            out.writeBytes(payload, afterEntityId, remaining);
        }
        byte[] result = new byte[out.readableBytes()];
        out.readBytes(result);
        return result;
    }

    /**
     * Package-private static splice helper exposed for unit tests.
     * Replaces the fixed int32 entity ID (after the packet ID VarInt) with
     * {@code newEntityId}, preserving all other bytes.
     *
     * <p>This mirrors the logic of {@link #rewriteEntityEventInt32} but takes the
     * new entity ID directly.
     */
    static byte[] spliceInt32EntityId(byte[] payload, int newEntityId) {
        ByteBuf in = Unpooled.wrappedBuffer(payload);
        readVarIntFromBuf(in);            // skip packetId
        int afterPid = in.readerIndex();
        in.readInt();                     // skip old int32 entity ID
        int afterEntityId = in.readerIndex(); // = afterPid + 4

        ByteBuf out = Unpooled.buffer(payload.length);
        out.writeBytes(payload, 0, afterPid);
        out.writeInt(newEntityId);
        int remaining = payload.length - afterEntityId;
        if (remaining > 0) {
            out.writeBytes(payload, afterEntityId, remaining);
        }
        byte[] result = new byte[out.readableBytes()];
        out.readBytes(result);
        return result;
    }
}
