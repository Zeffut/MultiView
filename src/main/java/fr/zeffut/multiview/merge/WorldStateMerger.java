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

    /**
     * Composite key (record) — eliminates bit-packing collisions for arbitrary int coordinates.
     * Slightly higher per-entry cost than a packed {@code long} but bijective by construction.
     */
    private record BlockKey(int dimId, int x, int y, int z) {}

    private final Map<BlockKey, BlockLww> state = new HashMap<>();
    private final Map<String, Integer> dimensionIds = new HashMap<>();
    private int lwwConflicts;
    private int lwwOverwrites;

    /**
     * @return true si l'update doit être émis dans le stream de sortie
     *         (bloc jamais vu ou update strictement plus récent)
     */
    public boolean acceptBlockUpdate(String dimension, int x, int y, int z,
                                     int tickAbs, int blockStateId, int sourceIdx) {
        BlockKey key = blockKey(dimension, x, y, z);
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

    private BlockKey blockKey(String dimension, int x, int y, int z) {
        int dimId = dimensionIds.computeIfAbsent(dimension, k -> dimensionIds.size() + 1);
        return new BlockKey(dimId, x, y, z);
    }
}
