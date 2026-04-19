package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentRoundTripTest {

    private static final Path REPLAYS_DIR = Paths.get(System.getProperty("user.dir"), "run", "replay");

    static boolean replaysAvailable() {
        return Files.isDirectory(REPLAYS_DIR);
    }

    @Test
    @EnabledIf("replaysAvailable")
    void roundTripEverySegmentOfEveryReplayByteForByte() throws IOException {
        AtomicInteger segmentsTested = new AtomicInteger();
        try (var replayDirs = Files.list(REPLAYS_DIR)) {
            replayDirs
                    .filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("metadata.json")))
                    .forEach(folder -> {
                        try (var segFiles = Files.list(folder)) {
                            segFiles
                                    .filter(p -> p.getFileName().toString().matches("c\\d+\\.flashback"))
                                    .forEach(seg -> {
                                        try {
                                            byte[] original = Files.readAllBytes(seg);
                                            byte[] round = roundTripSegment(seg.getFileName().toString(), original);
                                            assertArrayEquals(original, round,
                                                    "byte mismatch on " + seg);
                                            segmentsTested.incrementAndGet();
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        assertTrue(segmentsTested.get() > 0, "no segment was round-tripped");
    }

    private static byte[] roundTripSegment(String name, byte[] original) {
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
