package fr.zeffut.multiview.format;

import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class FlashbackReader {

    private FlashbackReader() {}

    /** Ouvre un replay depuis un dossier contenant metadata.json + cN.flashback. */
    public static FlashbackReplay open(Path folder) throws IOException {
        Path metadataPath = folder.resolve("metadata.json");
        if (!Files.isRegularFile(metadataPath)) {
            throw new IOException("metadata.json not found in " + folder);
        }
        FlashbackMetadata metadata;
        try (Reader r = Files.newBufferedReader(metadataPath)) {
            metadata = FlashbackMetadata.fromJson(r);
        }

        List<Path> segments = new ArrayList<>();
        for (String name : metadata.chunks().keySet()) {
            Path seg = folder.resolve(name);
            if (!Files.isRegularFile(seg)) {
                throw new IOException("segment " + name + " declared in metadata but missing from " + folder);
            }
            segments.add(seg);
        }
        return new FlashbackReplay(folder, metadata, List.copyOf(segments));
    }

    /**
     * Stream global d'entries sur tous les segments, ticks absolus remontés depuis le
     * début du replay. Les durées déclarées dans metadata servent à calculer les bornes
     * de tick attendues par segment — non utilisées pour le décodage, seulement le tick
     * reporté.
     */
    public static Stream<PacketEntry> stream(FlashbackReplay replay) {
        return replay.segmentPaths().stream().flatMap(segment -> streamSegment(replay, segment));
    }

    private static Stream<PacketEntry> streamSegment(FlashbackReplay replay, Path segment) {
        String segmentName = segment.getFileName().toString();
        int baseTick = baseTickFor(replay, segmentName);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(segment);
        } catch (IOException e) {
            throw new RuntimeException("failed to read " + segment, e);
        }
        FlashbackByteBuf buf = new FlashbackByteBuf(Unpooled.wrappedBuffer(bytes));
        SegmentReader reader = new SegmentReader(segmentName, buf);

        final int[] tick = { baseTick };
        Iterator<PacketEntry> it = new Iterator<>() {
            @Override public boolean hasNext() { return reader.hasNext(); }
            @Override public PacketEntry next() {
                boolean inSnap = reader.isPeekInSnapshot();
                Action a = reader.next();
                if (a instanceof Action.NextTick && !inSnap) {
                    tick[0]++;
                }
                return new PacketEntry(tick[0], segmentName, inSnap, a);
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    private static int baseTickFor(FlashbackReplay replay, String segmentName) {
        int base = 0;
        for (var entry : replay.metadata().chunks().entrySet()) {
            if (entry.getKey().equals(segmentName)) return base;
            base += entry.getValue().duration();
        }
        throw new IllegalArgumentException("segment " + segmentName + " not in metadata.chunks");
    }
}
