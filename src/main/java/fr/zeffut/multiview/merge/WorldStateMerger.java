package fr.zeffut.multiview.merge;

import java.util.HashMap;
import java.util.Map;

/**
 * Applique la règle "last-write-wins" par position de bloc globale (dimension, x, y, z).
 * Invoqué par PacketClassifier/MergeOrchestrator pour chaque BlockUpdate observé.
 *
 * Décide si un update doit être émis (true) ou droppé (false).
 *
 * @implNote Non thread-safe. Appelé depuis le pipeline single-threaded.
 */
public final class WorldStateMerger {

    private record BlockLww(int tickAbs, int blockStateId, int sourceIdx) {}

    private final Map<Long, BlockLww> state = new HashMap<>();
    private final Map<String, Long> dimensionIds = new HashMap<>();
    private int lwwConflicts;
    private int lwwOverwrites;

    /**
     * @return true si l'update doit être émis dans le stream de sortie
     *         (bloc jamais vu ou update strictement plus récent)
     */
    public boolean acceptBlockUpdate(String dimension, int x, int y, int z,
                                     int tickAbs, int blockStateId, int sourceIdx) {
        long key = blockKey(dimension, x, y, z);
        BlockLww current = state.get(key);

        if (current == null) {
            state.put(key, new BlockLww(tickAbs, blockStateId, sourceIdx));
            return true;
        }

        if (tickAbs > current.tickAbs) {
            state.put(key, new BlockLww(tickAbs, blockStateId, sourceIdx));
            lwwOverwrites++;
            return true;
        }

        if (tickAbs < current.tickAbs) {
            lwwConflicts++;
            return false;
        }

        // tickAbs == current.tickAbs → déjà émis par la 1re source
        return false;
    }

    public int lwwConflicts() { return lwwConflicts; }
    public int lwwOverwrites() { return lwwOverwrites; }

    private long blockKey(String dimension, int x, int y, int z) {
        long dimId = dimensionIds.computeIfAbsent(dimension, k -> (long) dimensionIds.size() + 1);
        long h = dimId;
        h = h * 31 + x;
        h = h * 31 + y;
        h = h * 31 + z;
        return h;
    }
}
