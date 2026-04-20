package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Écrit un segment {@code cN.flashback} : magic + registry + snapshot borné + live stream.
 * Complément symétrique de {@link SegmentReader}.
 *
 * <p>Workflow (in-memory) :
 * <pre>
 *   SegmentWriter w = new SegmentWriter(name, registry);
 *   w.writeSnapshotAction(ord, payload);  // 0..N fois
 *   w.endSnapshot();                       // obligatoire, écrit le snapshotSize
 *   w.writeLiveAction(ord, payload);       // 0..N fois
 *   ByteBuf out = w.finish();              // bytes complets du segment
 * </pre>
 *
 * <p>Workflow (streaming to file — avoids single 2GB array limit) :
 * <pre>
 *   SegmentWriter w = new SegmentWriter(name, registry);
 *   w.writeSnapshotAction(ord, payload);
 *   w.endSnapshot();
 *   w.openStreamingFile(path);             // opens FileChannel; header + snapshotSize written
 *   w.writeLiveAction(ord, payload);       // streamed directly to file
 *   w.finishStreaming();                    // flushes and closes channel
 * </pre>
 *
 * <p>Non thread-safe. Usage single-pass.
 */
public final class SegmentWriter {
    private final String segmentName;
    private final List<String> registry;
    private final ByteBuf header;
    private final ByteBuf snapshot;
    /** Accumulates live actions in in-memory mode (null in streaming mode). */
    private ByteBuf live;
    private boolean snapshotClosed = false;
    private boolean finished = false;

    /** Non-null only in streaming mode (set by {@link #openStreamingFile}). */
    private FileChannel streamingChannel;
    /** Whether we are in streaming mode. */
    private boolean streaming = false;

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

    /**
     * Opens a file for streaming live output, writing the segment header + snapshotSize + snapshot
     * immediately. Subsequent {@link #writeLiveAction} calls go directly to the file channel.
     * Must be called after {@link #endSnapshot()} and before any {@link #writeLiveAction}.
     *
     * @param dest path to write (created or truncated)
     * @throws IOException if file creation fails
     * @throws IllegalStateException if endSnapshot() was not called first, or already finished
     */
    public void openStreamingFile(Path dest) throws IOException {
        if (!snapshotClosed) {
            throw new IllegalStateException("call endSnapshot() before openStreamingFile() in " + segmentName);
        }
        if (finished) {
            throw new IllegalStateException("already finished in " + segmentName);
        }
        streaming = true;
        streamingChannel = FileChannel.open(dest,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

        // Write: header bytes
        writeNio(ByteBufUtil.getBytes(header));
        // Write: snapshotSize (int32 big-endian)
        int snapshotLen = snapshot.readableBytes();
        writeNio(new byte[]{
                (byte) (snapshotLen >>> 24),
                (byte) (snapshotLen >>> 16),
                (byte) (snapshotLen >>> 8),
                (byte)  snapshotLen
        });
        // Write: snapshot bytes
        if (snapshotLen > 0) {
            writeNio(ByteBufUtil.getBytes(snapshot));
        }
        // Release the in-memory live buffer — won't be used in streaming mode
        if (live != null) {
            live.release();
            live = null;
        }
    }

    public void writeLiveAction(int ordinal, byte[] payload) throws IOException {
        if (!snapshotClosed) {
            throw new IllegalStateException("call endSnapshot() before writeLiveAction() in " + segmentName);
        }
        if (streaming) {
            writeActionNio(ordinal, payload);
        } else {
            writeAction(live, ordinal, payload);
        }
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

    /**
     * Writes one action directly to the streaming FileChannel.
     * Encodes: VarInt ordinal + int32 payloadLen + payload bytes.
     */
    private void writeActionNio(int ordinal, byte[] payload) throws IOException {
        if (ordinal < 0 || ordinal >= registry.size()) {
            throw new IllegalArgumentException(
                    "ordinal " + ordinal + " out of registry bounds [" + registry.size() + ")");
        }
        // VarInt for ordinal (1–5 bytes)
        byte[] varIntBuf = encodeVarInt(ordinal);
        // int32 big-endian payload length (4 bytes)
        byte[] lenBuf = {
                (byte) (payload.length >>> 24),
                (byte) (payload.length >>> 16),
                (byte) (payload.length >>> 8),
                (byte)  payload.length
        };
        // Write all three parts
        writeNio(varIntBuf);
        writeNio(lenBuf);
        if (payload.length > 0) {
            writeNio(payload);
        }
    }

    private void writeNio(byte[] data) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(data);
        while (buf.hasRemaining()) {
            streamingChannel.write(buf);
        }
    }

    private static byte[] encodeVarInt(int value) {
        byte[] tmp = new byte[5];
        int i = 0;
        while ((value & ~0x7F) != 0) {
            tmp[i++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        tmp[i++] = (byte) value;
        byte[] out = new byte[i];
        System.arraycopy(tmp, 0, out, 0, i);
        return out;
    }

    /**
     * In-memory finish: assembles header + snapshotSize + snapshot + live into a single ByteBuf.
     * Must not be called in streaming mode.
     */
    public ByteBuf finish() {
        if (streaming) {
            throw new IllegalStateException("use finishStreaming() in streaming mode for " + segmentName);
        }
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

    /**
     * Streaming finish: flushes and closes the FileChannel.
     * Must be called in streaming mode (after {@link #openStreamingFile}).
     */
    public void finishStreaming() throws IOException {
        if (!streaming) {
            throw new IllegalStateException("not in streaming mode for " + segmentName);
        }
        if (finished) {
            throw new IllegalStateException("finishStreaming() already called on " + segmentName);
        }
        finished = true;
        streamingChannel.force(true);
        streamingChannel.close();
        streamingChannel = null;

        // Release in-memory buffers
        header.release();
        snapshot.release();
    }

    public String segmentName() {
        return segmentName;
    }
}
