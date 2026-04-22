package fr.zeffut.multiview.merge;

import net.minecraft.network.packet.PacketType;
import net.minecraft.network.packet.PlayPackets;
import net.minecraft.network.state.PlayStateFactories;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Builds the {@code Map<Integer, Category>} dispatch table for clientbound
 * GamePackets by introspecting the MC 1.21.11 Yarn API at runtime.
 *
 * <h2>Strategy A — full introspection via PlayStateFactories.S2C</h2>
 * <p>
 * Uses {@link PlayStateFactories#S2C}{@code .buildUnbound().forEachPacketType(...)},
 * which yields each registered S2C packet type with its numeric protocol ID.
 * Packet types are matched against {@link PlayPackets} constants to assign
 * the correct {@link Category}.
 * </p>
 *
 * <h2>Fallback (Strategy C)</h2>
 * <p>
 * If introspection fails at runtime for any reason, a PASSTHROUGH fallback is
 * returned and a warning is emitted in the {@link MergeReport}.
 * </p>
 */
public final class GamePacketDispatch {

    private GamePacketDispatch() {}

    /**
     * Build the dispatch table.
     *
     * @param report merge report — receives a warning if the fallback is used
     * @return an {@link IntFunction} from packetId → {@link Category}
     */
    public static IntFunction<Category> buildOrFallback(MergeReport report) {
        try {
            return build();
        } catch (Throwable e) {
            report.warn("GamePacketDispatch: introspection failed (" + e
                    + "), all GamePackets → PASSTHROUGH. Merge will be degraded.");
            return pid -> {
                report.stats.passthroughPackets.merge("packetId=" + pid, 1, Integer::sum);
                return Category.PASSTHROUGH;
            };
        }
    }

    /**
     * Build the dispatch table without a fallback. Throws on failure.
     * Package-private for unit testing.
     */
    static IntFunction<Category> build() {
        // Build the category lookup: PacketType → Category
        Map<PacketType<?>, Category> byType = buildCategoryByType();

        // Iterate all S2C play packet types with their numeric IDs
        Map<Integer, Category> table = new HashMap<>();
        PlayStateFactories.S2C.buildUnbound().forEachPacketType((type, id) -> {
            Category cat = byType.getOrDefault(type, Category.PASSTHROUGH);
            table.put(id, cat);
        });

        return pid -> table.getOrDefault(pid, Category.PASSTHROUGH);
    }

    /**
     * Returns the numeric protocol ID for a given {@link PacketType}, or -1 if
     * not found. Used by {@link MinecraftPacketIdProvider}.
     */
    static int findId(PacketType<?> target) {
        int[] result = {-1};
        PlayStateFactories.S2C.buildUnbound().forEachPacketType((type, id) -> {
            if (type == target) result[0] = id;
        });
        return result[0];
    }

    // -------------------------------------------------------------------------
    // Category mapping
    // -------------------------------------------------------------------------

    private static Map<PacketType<?>, Category> buildCategoryByType() {
        Map<PacketType<?>, Category> m = new HashMap<>();

        // --- WORLD packets ---
        // ChunkDataS2CPacket (LEVEL_CHUNK_WITH_LIGHT)
        m.put(PlayPackets.LEVEL_CHUNK_WITH_LIGHT, Category.WORLD);
        // UnloadChunkS2CPacket (FORGET_LEVEL_CHUNK)
        m.put(PlayPackets.FORGET_LEVEL_CHUNK, Category.WORLD);
        // BlockUpdateS2CPacket (BLOCK_UPDATE)
        m.put(PlayPackets.BLOCK_UPDATE, Category.WORLD);
        // ChunkDeltaUpdateS2CPacket (SECTION_BLOCKS_UPDATE)
        m.put(PlayPackets.SECTION_BLOCKS_UPDATE, Category.WORLD);
        // LightUpdateS2CPacket (LIGHT_UPDATE)
        m.put(PlayPackets.LIGHT_UPDATE, Category.WORLD);
        // BlockEntityUpdateS2CPacket (BLOCK_ENTITY_DATA)
        m.put(PlayPackets.BLOCK_ENTITY_DATA, Category.WORLD);
        // BlockEventS2CPacket (BLOCK_EVENT)
        m.put(PlayPackets.BLOCK_EVENT, Category.WORLD);
        // ChunkBiomeDataS2CPacket (CHUNKS_BIOMES)
        m.put(PlayPackets.CHUNKS_BIOMES, Category.WORLD);

        // --- ENTITY packets ---
        // EntitySpawnS2CPacket (ADD_ENTITY)
        m.put(PlayPackets.ADD_ENTITY, Category.ENTITY);
        // EntitiesDestroyS2CPacket (REMOVE_ENTITIES)
        m.put(PlayPackets.REMOVE_ENTITIES, Category.ENTITY);
        // EntityS2CPacket.MoveRelative (MOVE_ENTITY_POS)
        m.put(PlayPackets.MOVE_ENTITY_POS, Category.ENTITY);
        // EntityS2CPacket.Rotate (MOVE_ENTITY_ROT)
        m.put(PlayPackets.MOVE_ENTITY_ROT, Category.ENTITY);
        // EntityS2CPacket.RotateAndMoveRelative (MOVE_ENTITY_POS_ROT)
        m.put(PlayPackets.MOVE_ENTITY_POS_ROT, Category.ENTITY);
        // EntityPositionS2CPacket (TELEPORT_ENTITY)
        m.put(PlayPackets.TELEPORT_ENTITY, Category.ENTITY);
        // EntityPositionSyncS2CPacket (ENTITY_POSITION_SYNC)
        m.put(PlayPackets.ENTITY_POSITION_SYNC, Category.ENTITY);
        // EntityTrackerUpdateS2CPacket (SET_ENTITY_DATA)
        m.put(PlayPackets.SET_ENTITY_DATA, Category.ENTITY);
        // EntityStatusS2CPacket (ENTITY_EVENT)
        m.put(PlayPackets.ENTITY_EVENT, Category.ENTITY);
        // EntityVelocityUpdateS2CPacket (SET_ENTITY_MOTION)
        m.put(PlayPackets.SET_ENTITY_MOTION, Category.ENTITY);
        // EntityAnimationS2CPacket (ANIMATE)
        m.put(PlayPackets.ANIMATE, Category.ENTITY);
        // EntitySetHeadYawS2CPacket (ROTATE_HEAD)
        m.put(PlayPackets.ROTATE_HEAD, Category.ENTITY);
        // EntityEquipmentUpdateS2CPacket (SET_EQUIPMENT)
        m.put(PlayPackets.SET_EQUIPMENT, Category.ENTITY);
        // EntityAttachS2CPacket (SET_ENTITY_LINK)
        m.put(PlayPackets.SET_ENTITY_LINK, Category.ENTITY);
        // EntityPassengersSetS2CPacket (SET_PASSENGERS)
        m.put(PlayPackets.SET_PASSENGERS, Category.ENTITY);
        // EntityAttributesS2CPacket (UPDATE_ATTRIBUTES)
        m.put(PlayPackets.UPDATE_ATTRIBUTES, Category.ENTITY);
        // EntityStatusEffectS2CPacket (UPDATE_MOB_EFFECT)
        m.put(PlayPackets.UPDATE_MOB_EFFECT, Category.ENTITY);
        // RemoveEntityStatusEffectS2CPacket (REMOVE_MOB_EFFECT)
        m.put(PlayPackets.REMOVE_MOB_EFFECT, Category.ENTITY);
        // EntityDamageS2CPacket (DAMAGE_EVENT)
        m.put(PlayPackets.DAMAGE_EVENT, Category.ENTITY);
        // ItemPickupAnimationS2CPacket (TAKE_ITEM_ENTITY)
        m.put(PlayPackets.TAKE_ITEM_ENTITY, Category.ENTITY);

        // --- EGO packets ---
        // HealthUpdateS2CPacket (SET_HEALTH)
        m.put(PlayPackets.SET_HEALTH, Category.EGO);
        // ExperienceBarUpdateS2CPacket (SET_EXPERIENCE)
        m.put(PlayPackets.SET_EXPERIENCE, Category.EGO);
        // InventoryS2CPacket (CONTAINER_SET_CONTENT)
        m.put(PlayPackets.CONTAINER_SET_CONTENT, Category.EGO);
        // ScreenHandlerSlotUpdateS2CPacket (CONTAINER_SET_SLOT)
        m.put(PlayPackets.CONTAINER_SET_SLOT, Category.EGO);
        // OpenScreenS2CPacket (OPEN_SCREEN)
        m.put(PlayPackets.OPEN_SCREEN, Category.EGO);
        // CloseScreenS2CPacket (CONTAINER_CLOSE_S2C)
        m.put(PlayPackets.CONTAINER_CLOSE_S2C, Category.EGO);
        // UpdateSelectedSlotS2CPacket (SET_CARRIED_ITEM_S2C)
        m.put(PlayPackets.SET_CARRIED_ITEM_S2C, Category.EGO);
        // PlayerAbilitiesS2CPacket (PLAYER_ABILITIES_S2C)
        m.put(PlayPackets.PLAYER_ABILITIES_S2C, Category.EGO);
        // ScreenHandlerPropertyUpdateS2CPacket (CONTAINER_SET_DATA)
        m.put(PlayPackets.CONTAINER_SET_DATA, Category.EGO);
        // SetCursorItemS2CPacket (SET_CURSOR_ITEM)
        m.put(PlayPackets.SET_CURSOR_ITEM, Category.EGO);
        // SetPlayerInventoryS2CPacket (SET_PLAYER_INVENTORY)
        m.put(PlayPackets.SET_PLAYER_INVENTORY, Category.EGO);
        // PlayerPositionLookS2CPacket (PLAYER_POSITION)
        m.put(PlayPackets.PLAYER_POSITION, Category.EGO);
        // DamageTiltS2CPacket (HURT_ANIMATION)
        m.put(PlayPackets.HURT_ANIMATION, Category.EGO);
        // DeathMessageS2CPacket (PLAYER_COMBAT_KILL)
        m.put(PlayPackets.PLAYER_COMBAT_KILL, Category.EGO);
        // PlayerRespawnS2CPacket (RESPAWN) — dimension change, MUST follow primary only.
        // Otherwise secondary's dim change drags the replay client into their dimension,
        // causing primary's subsequent chunk packets to be applied to the wrong dimension
        // and silently dropped by Flashback → missing chunks.
        m.put(PlayPackets.RESPAWN, Category.EGO);
        // GameJoinS2CPacket (LOGIN) — initial world setup, keep primary only for same reason.
        m.put(PlayPackets.LOGIN, Category.EGO);
        // ChunkRenderDistanceCenterS2CPacket (SET_CHUNK_CACHE_CENTER)
        // — tells the client where to center chunk loading. Must follow the primary
        // POV only; secondary sources' values would fight over which chunks load.
        m.put(PlayPackets.SET_CHUNK_CACHE_CENTER, Category.EGO);
        // ChunkLoadDistanceS2CPacket (SET_CHUNK_CACHE_RADIUS)
        m.put(PlayPackets.SET_CHUNK_CACHE_RADIUS, Category.EGO);
        // SimulationDistanceS2CPacket
        m.put(PlayPackets.SET_SIMULATION_DISTANCE, Category.EGO);

        // --- GLOBAL packets ---
        // PlayerListS2CPacket (PLAYER_INFO_UPDATE, PLAYER_INFO_REMOVE)
        // Must be GLOBAL so GlobalDeduper deduplicates identical entries across sources
        // instead of emitting one copy per source (which causes player list flickering).
        m.put(PlayPackets.PLAYER_INFO_UPDATE, Category.GLOBAL);
        m.put(PlayPackets.PLAYER_INFO_REMOVE, Category.GLOBAL);
        // WorldTimeUpdateS2CPacket (SET_TIME)
        m.put(PlayPackets.SET_TIME, Category.GLOBAL);
        // GameMessageS2CPacket (SYSTEM_CHAT)
        m.put(PlayPackets.SYSTEM_CHAT, Category.GLOBAL);
        // ChatMessageS2CPacket (PLAYER_CHAT)
        m.put(PlayPackets.PLAYER_CHAT, Category.GLOBAL);
        // ProfilelessChatMessageS2CPacket (DISGUISED_CHAT)
        m.put(PlayPackets.DISGUISED_CHAT, Category.GLOBAL);
        // ExplosionS2CPacket (EXPLODE)
        m.put(PlayPackets.EXPLODE, Category.GLOBAL);
        // PlaySoundS2CPacket (SOUND)
        m.put(PlayPackets.SOUND, Category.GLOBAL);
        // PlaySoundFromEntityS2CPacket (SOUND_ENTITY)
        m.put(PlayPackets.SOUND_ENTITY, Category.GLOBAL);
        // StopSoundS2CPacket (STOP_SOUND)
        m.put(PlayPackets.STOP_SOUND, Category.GLOBAL);
        // ParticleS2CPacket (LEVEL_PARTICLES)
        m.put(PlayPackets.LEVEL_PARTICLES, Category.GLOBAL);
        // WorldEventS2CPacket (LEVEL_EVENT)
        m.put(PlayPackets.LEVEL_EVENT, Category.GLOBAL);
        // GameStateChangeS2CPacket (GAME_EVENT)
        m.put(PlayPackets.GAME_EVENT, Category.GLOBAL);

        return m;
    }
}
