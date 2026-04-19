package fr.zeffut.multiview.merge;

import java.util.TreeMap;

/**
 * Position du POV enregistreur de chaque source à un tick absolu donné.
 * Alimenté par MergeOrchestrator à chaque update de position du local player.
 */
public final class SourcePovTracker {

    public record Vec3(double x, double y, double z) {
        public static final Vec3 UNKNOWN = new Vec3(Double.NaN, Double.NaN, Double.NaN);
    }

    private final TreeMap<Integer, Vec3>[] perSource;

    @SuppressWarnings("unchecked")
    public SourcePovTracker(int sourceCount) {
        this.perSource = new TreeMap[sourceCount];
        for (int i = 0; i < sourceCount; i++) {
            this.perSource[i] = new TreeMap<>();
        }
    }

    public void update(int sourceIdx, int tickAbs, double x, double y, double z) {
        perSource[sourceIdx].put(tickAbs, new Vec3(x, y, z));
    }

    /** Dernière position connue au tick ≤ `tickAbs`. UNKNOWN si aucune. */
    public Vec3 positionAt(int sourceIdx, int tickAbs) {
        var entry = perSource[sourceIdx].floorEntry(tickAbs);
        return entry == null ? Vec3.UNKNOWN : entry.getValue();
    }

    /** Distance euclidienne du POV source au point (x,y,z) au tick. Infini si inconnue. */
    public double distanceTo(int sourceIdx, int tickAbs, double x, double y, double z) {
        Vec3 p = positionAt(sourceIdx, tickAbs);
        if (Double.isNaN(p.x())) return Double.POSITIVE_INFINITY;
        double dx = p.x() - x, dy = p.y() - y, dz = p.z() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
