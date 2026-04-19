package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.List;

/**
 * Écrit un segment {@code cN.flashback} : magic + registry + snapshot borné + live stream.
 * Complément symétrique de {@link SegmentReader}.
 *
 * <p>Workflow :
 * <pre>
 *   SegmentWriter w = new SegmentWriter(name, registry);
 *   w.writeSnapshotAction(ord, payload);  // 0..N fois
 *   w.endSnapshot();                       // obligatoire, écrit le snapshotSize
 *   w.writeLiveAction(ord, payload);       // 0..N fois
 *   ByteBuf out = w.finish();              // bytes complets du segment
 * </pre>
 *
 * <p>Non thread-safe. Usage single-pass.
 */
public final class SegmentWriter {
    private final String segmentName;
    private final List<String> registry;
    private final ByteBuf header;
    private final ByteBuf snapshot;
    private final ByteBuf live;
    private boolean snapshotClosed = false;
    private boolean finished = false;

    public SegmentWriter(String segmentName, List<String> registry) {
        this.segmentName = segmentName;
        this.registry = List.copyOf(registry);
        this.header = Unpooled.buffer();
        this.snapshot = Unpooled.buffer();
        this.live = Unpooled.buffer();
        writeHeader();
    }

    private void writeHeader() {
        FlashbackByteBuf h = new FlashbackByteBuf(header);
        h.writeInt(SegmentReader.MAGIC);
        h.writeVarInt(registry.size());
        for (String id : registry) {
            h.writeString(id);
        }
    }

    public void writeSnapshotAction(int ordinal, byte[] payload) {
        if (snapshotClosed) {
            throw new IllegalStateException("snapshot already closed in " + segmentName);
        }
        writeAction(snapshot, ordinal, payload);
    }

    public void endSnapshot() {
        if (snapshotClosed) {
            throw new IllegalStateException("snapshot already closed in " + segmentName);
        }
        snapshotClosed = true;
    }

    public void writeLiveAction(int ordinal, byte[] payload) {
        if (!snapshotClosed) {
            throw new IllegalStateException("call endSnapshot() before writeLiveAction() in " + segmentName);
        }
        writeAction(live, ordinal, payload);
    }

    private void writeAction(ByteBuf target, int ordinal, byte[] payload) {
        if (ordinal < 0 || ordinal >= registry.size()) {
            throw new IllegalArgumentException(
                    "ordinal " + ordinal + " out of registry bounds [" + registry.size() + ")");
        }
        FlashbackByteBuf b = new FlashbackByteBuf(target);
        b.writeVarInt(ordinal);
        b.writeInt(payload.length);
        if (payload.length > 0) {
            b.writeBytes(payload);
        }
    }

    public ByteBuf finish() {
        if (finished) {
            throw new IllegalStateException("finish() already called on " + segmentName);
        }
        if (!snapshotClosed) {
            throw new IllegalStateException("endSnapshot() must be called before finish() in " + segmentName);
        }
        finished = true;

        ByteBuf out = Unpooled.buffer();
        out.writeBytes(header);
        out.writeInt(snapshot.readableBytes());
        out.writeBytes(snapshot);
        out.writeBytes(live);
        return out;
    }

    public String segmentName() {
        return segmentName;
    }
}
