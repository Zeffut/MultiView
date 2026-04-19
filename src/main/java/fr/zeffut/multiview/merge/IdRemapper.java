package fr.zeffut.multiview.merge;

import java.util.HashMap;
import java.util.Map;

/**
 * Bijection (sourceIdx, localEntityId) → globalEntityId.
 * Les globalIds commencent à 100_000_000 pour éviter toute collision avec
 * des localIds qui traîneraient dans des payloads non remappés.
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

    private static long key(int sourceIdx, int localId) {
        return ((long) sourceIdx << 32) | (localId & 0xFFFFFFFFL);
    }
}
