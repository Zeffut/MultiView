package fr.zeffut.multiview.merge;

import java.util.*;

/** Bounded source-local lifetimes, collected before any output packet is written.
 * Stores identities only, never packet bodies or a world snapshot. */
final class EntityIdentityIndex {
    static final int MAX_LIFETIMES = 500_000;
    static final class ResolutionFailure extends IllegalStateException {
        ResolutionFailure(String message) { super(message); }
    }
    private static final class Life {
        final UUID uuid;
        int end = Integer.MAX_VALUE;
        Life(UUID uuid) { this.uuid = uuid; }
    }
    private final Map<Long, NavigableMap<Integer, Life>> lifetimes = new HashMap<>();
    private int count;
    void add(int source, int local, int tick, UUID uuid) {
        if (uuid == null) throw new ResolutionFailure("Missing UUID in identity prepass");
        var timeline = lifetimes.computeIfAbsent(key(source, local), k -> new TreeMap<>());
        Life sameTick = timeline.get(tick);
        if (sameTick != null) {
            if (!sameTick.uuid.equals(uuid)) throw new ResolutionFailure(
                    "Ambiguous same-tick entity ID reuse: source=" + source + ", local=" + local + ", tick=" + tick);
            return;
        }
        if (++count > MAX_LIFETIMES) throw new ResolutionFailure("Entity identity prepass exceeds " + MAX_LIFETIMES + " lifetimes");
        timeline.put(tick, new Life(uuid));
    }
    void remove(int source, int local, int tick) {
        var timeline = lifetimes.get(key(source, local));
        if (timeline != null) {
            var previous = timeline.floorEntry(tick);
            if (previous != null) previous.getValue().end = Math.min(previous.getValue().end, tick);
        }
    }
    UUID resolve(int source, int local, int tick) {
        var timeline = lifetimes.get(key(source, local));
        if (timeline != null) {
            var previous = timeline.floorEntry(tick);
            if (previous != null && tick < previous.getValue().end) return previous.getValue().uuid;
            var next = timeline.ceilingEntry(tick);
            if (previous != null && next != null) {
                throw new ResolutionFailure("Ambiguous reference between entity lifetimes: source="
                        + source + ", local=" + local + ", tick=" + tick);
            }
            if (next != null) return next.getValue().uuid;
        }
        // No AddEntity for this source-local lifetime exists anywhere in the
        // input. Preserve the reference as an isolated, unspawned ID (vanilla
        // ignores stale references); never guess another source's identity.
        return null;
    }
    private static long key(int source, int local) { return ((long) source << 32) | (local & 0xffffffffL); }
}
