package fr.zeffut.multiview.merge;

import java.util.*;

/** Single-threaded entity identity and observation lifecycle across recordings. */
public final class EntityMerger {
    private static final int TICK_WINDOW = 5;
    private static final double DIST_WINDOW = 2.0;

    private static final class EntityState {
        final int globalId;
        final String type;
        UUID uuid;
        int lastSeenTick;
        double x, y, z;
        boolean emitted;
        final List<int[]> sources = new ArrayList<>();
        EntityState(int globalId, UUID uuid, String type, int tick, double x, double y, double z) {
            this.globalId = globalId; this.uuid = uuid; this.type = type;
            this.lastSeenTick = tick; this.x = x; this.y = y; this.z = z;
        }
        boolean hasSource(int sourceIdx) {
            return sources.stream().anyMatch(s -> s[0] == sourceIdx);
        }
        void observe(int sourceIdx, int localId) {
            for (int[] s : sources) if (s[0] == sourceIdx && s[1] == localId) return;
            sources.add(new int[]{sourceIdx, localId});
        }
    }

    private final SourcePovTracker pov;
    private final IdRemapper idRemapper;
    private final Map<UUID, Integer> uuidIndex = new HashMap<>();
    // Canonical identity allocation is independent of live observations. Keep it
    // stable across a pre-spawn reference, another POV, remove and reappearance.
    private final Map<UUID, Integer> identityIds = new HashMap<>();
    void bindPrimaryIdentity(UUID uuid, int localId) { identityIds.put(uuid, localId); }
    int reserveIdentity(UUID uuid) {
        return identityIds.computeIfAbsent(uuid, key -> idRemapper.allocate());
    }
    private final Map<Integer, EntityState> states = new HashMap<>();
    private final Map<Integer, Integer> sourceEndTicks = new HashMap<>();
    private int uuidMerged;
    private int heuristicMerged;
    private int ambiguousMerged;

    public EntityMerger(SourcePovTracker pov, IdRemapper idRemapper) {
        this.pov = pov; this.idRemapper = idRemapper;
    }

    public int registerAddEntity(int sourceIdx, int localId, UUID uuid, String type,
                                 int tickAbs, double x, double y, double z) {
        if (uuid != null && uuidIndex.containsKey(uuid)) {
            int g = uuidIndex.get(uuid);
            checkReassignment(sourceIdx, localId, g);
            idRemapper.assignExisting(sourceIdx, localId, g);
            EntityState state = states.get(g);
            state.observe(sourceIdx, localId);
            state.lastSeenTick = Math.max(state.lastSeenTick, tickAbs);
            uuidMerged++;
            return g;
        }
        List<EntityState> candidates = new ArrayList<>();
        for (EntityState e : states.values()) {
            // Stable, different UUIDs are proof of distinct entities, not a heuristic match.
            if (uuid != null && e.uuid != null && !uuid.equals(e.uuid)) continue;
            if (!e.type.equals(type)) continue;
            if (Math.abs(e.lastSeenTick - tickAbs) > TICK_WINDOW) continue;
            double dx = e.x - x, dy = e.y - y, dz = e.z - z;
            if (Math.sqrt(dx*dx + dy*dy + dz*dz) > DIST_WINDOW) continue;
            if (e.hasSource(sourceIdx)) continue;
            candidates.add(e);
        }
        EntityState chosen;
        if (candidates.isEmpty()) {
            int g;
            if (uuid != null) {
                // Existing unindexed reservations remain a hard error: a packet
                // containing a different ID may already have reached the output.
                g = identityIds.containsKey(uuid) ? reserveIdentity(uuid)
                        : idRemapper.assign(sourceIdx, localId);
                identityIds.putIfAbsent(uuid, g);
                checkReassignment(sourceIdx, localId, g);
                idRemapper.assignExisting(sourceIdx, localId, g);
            } else {
                g = idRemapper.assign(sourceIdx, localId);
            }
            chosen = new EntityState(g, uuid, type, tickAbs, x, y, z);
            chosen.observe(sourceIdx, localId);
            states.put(g, chosen);
            if (uuid != null) uuidIndex.put(uuid, g);
            return g;
        } else if (candidates.size() == 1) {
            chosen = candidates.get(0); heuristicMerged++;
        } else {
            chosen = candidates.stream()
                    .min(Comparator.comparingDouble(c -> minPovDistance(c, tickAbs, x, y, z)))
                    .orElseThrow();
            ambiguousMerged++;
        }
        checkReassignment(sourceIdx, localId, chosen.globalId);
        idRemapper.assignExisting(sourceIdx, localId, chosen.globalId);
        chosen.observe(sourceIdx, localId);
        chosen.lastSeenTick = Math.max(chosen.lastSeenTick, tickAbs);
        chosen.x = x; chosen.y = y; chosen.z = z;
        if (uuid != null) { chosen.uuid = uuid; uuidIndex.put(uuid, chosen.globalId); }
        return chosen.globalId;
    }

    private void checkReassignment(int sourceIdx, int localId, int globalId) {
        if (idRemapper.contains(sourceIdx, localId)
                && idRemapper.remap(sourceIdx, localId) != globalId) {
            throw new IllegalStateException("Entity reference assigned before UUID resolution: source="
                    + sourceIdx + ", localId=" + localId);
        }
    }

    /** Detach every observation from a finished source and return final despawns. */
    public List<Integer> endSourcesThrough(int tickAbs) {
        List<Integer> finished = sourceEndTicks.entrySet().stream()
                .filter(e -> e.getValue() <= tickAbs).map(Map.Entry::getKey).sorted().toList();
        List<Integer> removed = new ArrayList<>();
        for (int sourceIdx : finished) removed.addAll(endSource(sourceIdx));
        return removed;
    }

    public List<Integer> endSource(int sourceIdx) {
        List<Integer> removed = new ArrayList<>();
        for (EntityState state : new ArrayList<>(states.values())) {
            for (int[] source : new ArrayList<>(state.sources)) {
                if (source[0] == sourceIdx) {
                    int global = removeEntity(sourceIdx, source[1], Integer.MIN_VALUE);
                    if (global >= 0) removed.add(global);
                }
            }
        }
        // References can reserve IDs without ever producing an EntityState.
        idRemapper.removeSource(sourceIdx);
        sourceEndTicks.remove(sourceIdx);
        return removed;
    }

    /** Replace a synthetic sentinel observation with its real source-local ID. */
    public void bindSyntheticLocalId(int source, int localId, int globalId) {
        EntityState state = states.get(globalId);
        if (state == null) throw new IllegalStateException("Synthetic player has no live entity state");
        checkReassignment(source, localId, globalId);
        state.sources.removeIf(s -> s[0] == source && s[1] == -1);
        idRemapper.remove(source, -1);
        state.observe(source, localId);
        idRemapper.assignExisting(source, localId, globalId);
    }

    boolean hasEmittedEntity(int globalId) {
        EntityState state = states.get(globalId);
        return state != null && state.emitted;
    }

    /** True only while the target is actually present in the output as a player. */
    boolean hasEmittedPlayer(int globalId) {
        EntityState state = states.get(globalId);
        return state != null && state.emitted && "minecraft:player".equals(state.type);
    }

    /** Emit one spawn per live global entity, not once per observer. */
    public boolean shouldEmitAddEntity(int globalId) {
        EntityState state = states.get(globalId);
        if (state == null) throw new IllegalStateException("Unknown entity " + globalId);
        if (state.emitted) return false;
        state.emitted = true;
        return true;
    }

    public void setSourceEndTick(int sourceIdx, int endTick) {
        sourceEndTicks.put(sourceIdx, endTick);
    }

    /** Remove this observer; emit a despawn only when no active source still sees it.
     * An ended recording must not veto a later observed removal forever.
     * Returns the global ID to remove, or -1 for no output. */
    public int removeEntity(int sourceIdx, int localId, int tickAbs) {
        if (!idRemapper.contains(sourceIdx, localId)) return -1;
        int globalId = idRemapper.remap(sourceIdx, localId);
        EntityState state = states.get(globalId);
        if (state == null) {
            idRemapper.remove(sourceIdx, localId);
            return -1;
        }
        state.sources.removeIf(s -> s[0] == sourceIdx && s[1] == localId);
        idRemapper.remove(sourceIdx, localId);
        boolean stillObserved = state.sources.stream().anyMatch(s ->
                sourceEndTicks.getOrDefault(s[0], Integer.MAX_VALUE) >= tickAbs);
        if (stillObserved) return -1;
        states.remove(globalId);
        uuidIndex.values().removeIf(id -> id == globalId);
        idRemapper.removeByGlobalId(Set.of(globalId));
        return state.emitted ? globalId : -1;
    }

    /** No AddEntity for 200 ticks is not evidence of death. Reclaim at explicit
     * lifecycle removal instead; retain the identity of every still-emitted entity. */
    public void purge(int currentTick) { }

    public int uuidMergedCount() { return uuidMerged; }
    public int heuristicMergedCount() { return heuristicMerged; }
    public int ambiguousMergedCount() { return ambiguousMerged; }
    void incrementUuidMerged() { }

    private double minPovDistance(EntityState e, int tickAbs, double ex, double ey, double ez) {
        double min = Double.POSITIVE_INFINITY;
        for (int[] src : e.sources) {
            min = Math.min(min, pov.distanceTo(src[0], tickAbs, ex, ey, ez));
        }
        return min;
    }
}
