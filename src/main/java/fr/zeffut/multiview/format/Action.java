package fr.zeffut.multiview.format;

import io.netty.buffer.Unpooled;

/**
 * Union scellée des actions d'un segment .flashback. Les 8 variantes explicites
 * correspondent au catalogue identifié dans
 * {@code src/main/java/fr/zeffut/multiview/format/README.md §4}.
 * {@link Unknown} couvre les ids non répertoriés (forward-compat).
 */
public sealed interface Action permits
        Action.NextTick,
        Action.ConfigurationPacket,
        Action.GamePacket,
        Action.CreatePlayer,
        Action.MoveEntities,
        Action.CacheChunkRef,
        Action.VoiceChat,
        Action.EncodedVoiceChat,
        Action.Unknown {

    /** Avance le tick du replay de 1. Payload vide. */
    record NextTick() implements Action {}

    /** Payload = un packet MC clientbound protocole CONFIGURATION (opaque). */
    record ConfigurationPacket(byte[] bytes) implements Action {}

    /** Payload = un packet MC clientbound protocole PLAY (opaque). */
    record GamePacket(byte[] bytes) implements Action {}

    /** Création du joueur local au début d'un segment. Payload opaque en Phase 1. */
    record CreatePlayer(byte[] bytes) implements Action {}

    /** Batch de mouvements d'entités groupé par dimension. Payload opaque en Phase 1. */
    record MoveEntities(byte[] bytes) implements Action {}

    /**
     * Référence vers un chunk MC mis en cache dans {@code level_chunk_caches/}.
     * Décodé : payload = {@code VarInt cacheIndex}.
     */
    record CacheChunkRef(int cacheIndex) implements Action {}

    /** Payload Simple Voice Chat non-encoded. Opaque. */
    record VoiceChat(byte[] bytes) implements Action {}

    /** Payload Simple Voice Chat encoded (Arcade only, rare dans Flashback-native). */
    record EncodedVoiceChat(byte[] bytes) implements Action {}

    /** Id non catalogué — bytes préservés pour round-trip. */
    record Unknown(String id, byte[] payload) implements Action {}

    /** Helper pour décoder `CacheChunkRef` depuis ses bytes. */
    static CacheChunkRef decodeCacheChunkRef(byte[] payload) {
        int idx = VarInts.readVarInt(Unpooled.wrappedBuffer(payload));
        return new CacheChunkRef(idx);
    }
}
