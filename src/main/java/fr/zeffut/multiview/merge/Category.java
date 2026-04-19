package fr.zeffut.multiview.merge;

/** Catégorie de dispatch déterminée par {@link PacketClassifier}. */
public enum Category {
    TICK,          // NextTick action, rejoué recalé
    CONFIG,        // ConfigurationPacket, dedup par hash
    LOCAL_PLAYER,  // CreateLocalPlayer, traité par MergeOrchestrator
    WORLD,         // blocs + chunks, vers WorldStateMerger
    ENTITY,        // AddEntity/RemoveEntities/Move/etc., vers EntityMerger
    EGO,           // santé/XP/inventaire, vers EgoRouter (attaché au sourceUuid)
    GLOBAL,        // time/chat/son/explosion, vers GlobalDeduper
    CACHE_REF,     // CacheChunkRef, vers CacheRemapper
    PASSTHROUGH    // inconnu, rejoué tel quel
}
