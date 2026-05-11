package fr.zeffut.multiview.merge;

import java.util.HashMap;
import java.util.Map;

/**
 * Bijection (sourceIdx, localEntityId) → globalEntityId.
 * Les globalIds commencent à 100_000_000 pour éviter toute collision avec
 * des localIds qui traîneraient dans des payloads non remappés.
 *
 * @implNote Non thread-safe. Le pipeline de merge (MergeOrchestrator) tourne
 *           sur un seul thread par design (streaming k-way merge, voir spec §2.1).
 *           Ne pas partager entre threads sans synchronisation externe.
 */
public final class IdRemapper {

    private static final int GLOBAL_ID_BASE = 100_000_000;

    private final Map<Long, Integer> mapping = new HashMap<>();
    private int nextGlobalId = GLOBAL_ID_BASE;

    /** Assigne un nouveau globalId si absent, sinon retourne l'existant. */
    public int assign(int sourceIdx, int localId) {
        return mapping.computeIfAbsent(key(sourceIdx, localId), k -> nextGlobalId++);
    }

    /** Force l'association (sourceIdx, localId) → globalId existant. */
    public void assignExisting(int sourceIdx, int localId, int globalId) {
        mapping.put(key(sourceIdx, localId), globalId);
    }

    /** Lookup. Lance IllegalStateException si absent. */
    public int remap(int sourceIdx, int localId) {
        Integer g = mapping.get(key(sourceIdx, localId));
        if (g == null) {
            throw new IllegalStateException(
                    "IdRemapper: (" + sourceIdx + ", " + localId + ") non assigné");
        }
        return g;
    }

    public boolean contains(int sourceIdx, int localId) {
        return mapping.containsKey(key(sourceIdx, localId));
    }

    /**
     * Removes a (sourceIdx, localId) mapping. Called by {@link EntityMerger#purge} to free
     * memory on long replays — without this, the mapping grows unboundedly for transient
     * entities (mobs spawned and destroyed during a multi-hour session).
     */
    public void remove(int sourceIdx, int localId) {
        mapping.remove(key(sourceIdx, localId));
    }

    /**
     * Removes every mapping whose globalId is in the provided set. Used by
     * {@link EntityMerger#purge} when a globalId is reclaimed.
     */
    public void removeByGlobalId(java.util.Set<Integer> globalIds) {
        if (globalIds == null || globalIds.isEmpty()) return;
        mapping.values().removeIf(globalIds::contains);
    }

    public int size() { return mapping.size(); }

    private static long key(int sourceIdx, int localId) {
        return ((long) sourceIdx << 32) | (localId & 0xFFFFFFFFL);
    }
}
