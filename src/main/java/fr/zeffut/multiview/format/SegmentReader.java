package fr.zeffut.multiview.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Décode un segment {@code cN.flashback}. Le header (magic + registry + snapshot size)
 * est lu eagerly dans le constructeur ; les actions sont émises lazy via
 * {@link #hasNext()} / {@link #next()}.
 *
 * <p>Format (spec complète dans format/README.md §2) :
 * <pre>
 *   int32 BE magic == 0xD780E884
 *   VarInt registryCount
 *   registryCount × [VarInt len, UTF-8 bytes]
 *   int32 BE snapshotSize
 *   snapshotSize bytes d'actions (= snapshot)
 *   actions jusqu'à EOF (= live stream)
 *
 *   Action := VarInt actionOrdinal, int32 BE payloadSize, payloadSize bytes
 * </pre>
 *
 * <p>Non thread-safe. Consume-once.
 */
public final class SegmentReader implements Iterator<Action> {
    public static final int MAGIC = 0xD780E884;

    private final String segmentName;
    private final FlashbackByteBuf buf;
    private final List<String> registry;
    private final int snapshotEndIndex;

    public SegmentReader(String segmentName, FlashbackByteBuf buf) {
        this.segmentName = segmentName;
        this.buf = buf;

        int magic = buf.readInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                    "Invalid Flashback segment magic in " + segmentName
                            + ": expected 0x" + Integer.toHexString(MAGIC)
                            + " got 0x" + Integer.toHexString(magic));
        }

        int count = buf.readVarInt();
        List<String> reg = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int len = buf.readVarInt();
            byte[] idBytes = buf.readBytes(len);
            reg.add(new String(idBytes, StandardCharsets.UTF_8));
        }
        this.registry = Collections.unmodifiableList(reg);

        int snapshotSize = buf.readInt();
        this.snapshotEndIndex = buf.readerIndex() + snapshotSize;
    }

    public String segmentName() {
        return segmentName;
    }

    public List<String> registry() {
        return registry;
    }

    /**
     * True si la prochaine {@link #next()} va retourner une action du bloc Snapshot
     * (par opposition au live stream). À consulter AVANT d'appeler next().
     */
    public boolean isPeekInSnapshot() {
        return buf.readerIndex() < snapshotEndIndex;
    }

    @Override
    public boolean hasNext() {
        return buf.isReadable();
    }

    @Override
    public Action next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        int ordinal = buf.readVarInt();
        if (ordinal < 0 || ordinal >= registry.size()) {
            throw new IllegalStateException(
                    "Action ordinal " + ordinal + " out of registry bounds ["
                            + registry.size() + ") in " + segmentName);
        }
        int payloadSize = buf.readInt();
        byte[] payload = payloadSize > 0 ? buf.readBytes(payloadSize) : new byte[0];
        String id = registry.get(ordinal);
        return ActionType.decode(id, payload);
    }

    /** Entrée brute (ordinal + payload bytes) — alternative à next() pour round-trip. */
    public record RawAction(int ordinal, byte[] payload) {}

    /** Lit la prochaine action sans la décoder. Utile pour round-trip byte-for-byte. */
    public RawAction nextRaw() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        int ordinal = buf.readVarInt();
        if (ordinal < 0 || ordinal >= registry.size()) {
            throw new IllegalStateException(
                    "Action ordinal " + ordinal + " out of registry bounds ["
                            + registry.size() + ") in " + segmentName);
        }
        int payloadSize = buf.readInt();
        byte[] payload = payloadSize > 0 ? buf.readBytes(payloadSize) : new byte[0];
        return new RawAction(ordinal, payload);
    }
}
