package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Écrit un replay Flashback. Phase 2 : API {@code copy} qui reproduit un replay
 * source sur disque byte-à-byte pour les segments et les caches, sémantiquement
 * équivalent pour {@code metadata.json}.
 */
public final class FlashbackWriter {

    private FlashbackWriter() {}

    /**
     * Copie complète d'un replay vers un nouveau dossier.
     *
     * @param source       replay ouvert via {@link FlashbackReader#open(Path)}
     * @param destFolder   dossier de destination (créé si absent)
     */
    public static void copy(FlashbackReplay source, Path destFolder) throws IOException {
        Files.createDirectories(destFolder);

        // metadata.json
        try (Writer w = Files.newBufferedWriter(destFolder.resolve("metadata.json"))) {
            source.metadata().toJson(w);
        }

        // icon.png si présent
        Path srcIcon = source.folder().resolve("icon.png");
        if (Files.isRegularFile(srcIcon)) {
            Files.copy(srcIcon, destFolder.resolve("icon.png"), StandardCopyOption.REPLACE_EXISTING);
        }

        // level_chunk_caches/ entier
        Path srcCaches = source.folder().resolve("level_chunk_caches");
        if (Files.isDirectory(srcCaches)) {
            Path destCaches = destFolder.resolve("level_chunk_caches");
            Files.createDirectories(destCaches);
            try (Stream<Path> entries = Files.list(srcCaches)) {
                entries.forEach(entry -> {
                    try {
                        Files.copy(entry,
                                destCaches.resolve(entry.getFileName()),
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }

        // Segments
        for (Path srcSegment : source.segmentPaths()) {
            Path destSegment = destFolder.resolve(srcSegment.getFileName());
            byte[] original = Files.readAllBytes(srcSegment);
            byte[] written = roundTripSegmentBytes(srcSegment.getFileName().toString(), original);
            Files.write(destSegment, written);
        }
    }

    private static byte[] roundTripSegmentBytes(String name, byte[] original) {
        SegmentReader reader = new SegmentReader(name,
                new FlashbackByteBuf(Unpooled.wrappedBuffer(original)));
        SegmentWriter writer = new SegmentWriter(name, reader.registry());
        boolean snapshotClosed = false;
        while (reader.hasNext()) {
            boolean inSnap = reader.isPeekInSnapshot();
            SegmentReader.RawAction raw = reader.nextRaw();
            if (inSnap) {
                writer.writeSnapshotAction(raw.ordinal(), raw.payload());
            } else {
                if (!snapshotClosed) {
                    writer.endSnapshot();
                    snapshotClosed = true;
                }
                writer.writeLiveAction(raw.ordinal(), raw.payload());
            }
        }
        if (!snapshotClosed) {
            writer.endSnapshot();
        }
        ByteBuf out = writer.finish();
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        return bytes;
    }
}
