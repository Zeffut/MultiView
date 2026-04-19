package fr.zeffut.multiview.merge;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.zip.CRC32C;

/**
 * Déduplication des packets globaux par (packetTypeId, tickAbs, contentHash).
 * Utilisé pour éviter d'émettre 2× le même packet GLOBAL (ex. ClientboundSetTimePacket)
 * observé par plusieurs sources au même tick.
 */
public final class GlobalDeduper {

    private record Entry(int packetTypeId, int tickAbs, long contentHash) {}

    private final Set<Entry> seen = new HashSet<>();

    public boolean shouldEmit(int packetTypeId, int tickAbs, byte[] payload) {
        Entry e = new Entry(packetTypeId, tickAbs, hash(payload));
        return seen.add(e);
    }

    /** Supprime les entrées dont tickAbs < threshold. À appeler périodiquement. */
    public void purgeOlderThan(int threshold) {
        Iterator<Entry> it = seen.iterator();
        while (it.hasNext()) {
            if (it.next().tickAbs < threshold) it.remove();
        }
    }

    public int size() { return seen.size(); }

    private static long hash(byte[] payload) {
        CRC32C crc = new CRC32C();
        crc.update(payload, 0, payload.length);
        return crc.getValue();
    }
}
