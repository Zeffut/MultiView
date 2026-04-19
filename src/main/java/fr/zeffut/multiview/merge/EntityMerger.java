package fr.zeffut.multiview.merge;

import java.util.*;

/**
 * Déduplication d'entités entre sources.
 *
 * - Clé primaire : UUID quand présent (players, nommés…)
 * - Heuristique fallback : même type + position ≤ 2 blocs + tick ±5
 *   (uniquement entre sources différentes — une même source ne peut voir
 *    la même entité sous deux localIds distincts)
 * - Ambiguïté (≥ 2 candidats) : résolue par confiance géographique
 *   (l'entité dont le POV enregistreur est le plus proche gagne)
 *
 * Fournit le globalId à utiliser pour un packet AddEntity donné.
 *
 * @implNote Non thread-safe. Appelé depuis le pipeline single-threaded.
 */
public final class EntityMerger {

    private static final int TICK_WINDOW = 5;
    private static final double DIST_WINDOW = 2.0;
    private static final int PURGE_AGE = 200;

    private static final class EntityState {
        final int globalId;
        final String type;
        int lastSeenTick;
        double x, y, z;
        final List<int[]> sources = new ArrayList<>(); // (sourceIdx, localId)

        EntityState(int globalId, String type, int tick, double x, double y, double z) {
            this.globalId = globalId;
            this.type = type;
            this.lastSeenTick = tick;
            this.x = x; this.y = y; this.z = z;
        }

        boolean hasSource(int sourceIdx) {
            for (int[] src : sources) {
                if (src[0] == sourceIdx) return true;
            }
            return false;
        }
    }

    private final SourcePovTracker pov;
    private final IdRemapper idRemapper;
    private final Map<UUID, Integer> uuidIndex = new HashMap<>();
    private final Map<Integer, EntityState> states = new HashMap<>();
    private int uuidMerged;
    private int heuristicMerged;
    private int ambiguousMerged;

    public EntityMerger(SourcePovTracker pov, IdRemapper idRemapper) {
        this.pov = pov;
        this.idRemapper = idRemapper;
    }

    /**
     * Enregistre un ClientboundAddEntity d'une source. Retourne le globalId à utiliser
     * pour les packets suivants de cette entité.
     *
     * @param uuid peut être null (entités sans UUID stable)
     * @param type chaîne stable (ex. "minecraft:zombie")
     */
    public int registerAddEntity(int sourceIdx, int localId, UUID uuid, String type,
                                 int tickAbs, double x, double y, double z) {
        // 1. Match UUID
        if (uuid != null && uuidIndex.containsKey(uuid)) {
            int g = uuidIndex.get(uuid);
            idRemapper.assignExisting(sourceIdx, localId, g);
            states.get(g).sources.add(new int[]{sourceIdx, localId});
            uuidMerged++;
            return g;
        }

        // 2. Heuristique — on ne fusionne pas deux entités de la même source
        List<EntityState> candidates = new ArrayList<>();
        for (EntityState e : states.values()) {
            if (!e.type.equals(type)) continue;
            if (Math.abs(e.lastSeenTick - tickAbs) > TICK_WINDOW) continue;
            double dx = e.x - x, dy = e.y - y, dz = e.z - z;
            if (Math.sqrt(dx*dx + dy*dy + dz*dz) > DIST_WINDOW) continue;
            // Contrainte de source-unicité : une source ne peut voir la même entité
            // sous deux localIds différents → on exclut les candidats déjà vus par cette source
            if (e.hasSource(sourceIdx)) continue;
            candidates.add(e);
        }

        EntityState chosen;
        if (candidates.isEmpty()) {
            int g = idRemapper.assign(sourceIdx, localId);
            chosen = new EntityState(g, type, tickAbs, x, y, z);
            chosen.sources.add(new int[]{sourceIdx, localId});
            states.put(g, chosen);
            if (uuid != null) uuidIndex.put(uuid, g);
            return g;
        } else if (candidates.size() == 1) {
            chosen = candidates.get(0);
            heuristicMerged++;
        } else {
            // Ambiguïté : on garde l'entité dont au moins une de ses sources
            // d'observation a le POV le plus proche de l'entité au tick considéré.
            chosen = candidates.stream()
                    .min(Comparator.comparingDouble(c -> minPovDistance(c, tickAbs, x, y, z)))
                    .orElseThrow();
            ambiguousMerged++;
        }

        idRemapper.assignExisting(sourceIdx, localId, chosen.globalId);
        chosen.sources.add(new int[]{sourceIdx, localId});
        chosen.lastSeenTick = Math.max(chosen.lastSeenTick, tickAbs);
        chosen.x = x; chosen.y = y; chosen.z = z;
        if (uuid != null) uuidIndex.put(uuid, chosen.globalId);
        return chosen.globalId;
    }

    /** Purge les entités dont lastSeenTick < currentTick - PURGE_AGE. À appeler toutes les 100 ticks. */
    public void purge(int currentTick) {
        states.values().removeIf(e -> e.lastSeenTick < currentTick - PURGE_AGE);
    }

    public int uuidMergedCount() { return uuidMerged; }
    public int heuristicMergedCount() { return heuristicMerged; }
    public int ambiguousMergedCount() { return ambiguousMerged; }

    // Pour le test — no-op : déjà compté dans registerAddEntity
    void incrementUuidMerged() { /* no-op : déjà compté dans register */ }

    private double minPovDistance(EntityState e, int tickAbs, double ex, double ey, double ez) {
        double min = Double.POSITIVE_INFINITY;
        for (int[] src : e.sources) {
            double d = pov.distanceTo(src[0], tickAbs, ex, ey, ez);
            if (d < min) min = d;
        }
        return min;
    }
}
