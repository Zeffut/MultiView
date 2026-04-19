package fr.zeffut.multiview.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashbackWriterIntegrationTest {

    private static final Path REPLAYS_DIR = Paths.get(System.getProperty("user.dir"), "run", "replay");

    static boolean replaysAvailable() {
        try (var s = Files.list(REPLAYS_DIR)) {
            return s.anyMatch(d -> Files.isRegularFile(d.resolve("metadata.json")));
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    @EnabledIf("replaysAvailable")
    void copyFirstReplayPreservesSegmentsAndCachesByteForByte(@TempDir Path tmp) throws IOException {
        Path source;
        try (Stream<Path> dirs = Files.list(REPLAYS_DIR)) {
            source = dirs
                    .filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("metadata.json")))
                    .findFirst()
                    .orElseThrow();
        }
        Path dest = tmp.resolve("copy");
        FlashbackReplay replay = FlashbackReader.open(source);
        FlashbackWriter.copy(replay, dest);

        // Segments byte-for-byte
        for (Path segPath : replay.segmentPaths()) {
            byte[] a = Files.readAllBytes(segPath);
            byte[] b = Files.readAllBytes(dest.resolve(segPath.getFileName()));
            assertArrayEquals(a, b, "segment mismatch on " + segPath.getFileName());
        }

        // level_chunk_caches byte-for-byte
        Path srcCaches = source.resolve("level_chunk_caches");
        Path dstCaches = dest.resolve("level_chunk_caches");
        if (Files.isDirectory(srcCaches)) {
            try (Stream<Path> files = Files.list(srcCaches)) {
                for (Path srcFile : files.toList()) {
                    Path dstFile = dstCaches.resolve(srcFile.getFileName());
                    assertTrue(Files.isRegularFile(dstFile), "missing cache file " + srcFile.getFileName());
                    assertArrayEquals(Files.readAllBytes(srcFile), Files.readAllBytes(dstFile),
                            "cache bytes mismatch on " + srcFile.getFileName());
                }
            }
        }

        // icon.png byte-for-byte (si présent)
        Path srcIcon = source.resolve("icon.png");
        if (Files.isRegularFile(srcIcon)) {
            assertArrayEquals(Files.readAllBytes(srcIcon), Files.readAllBytes(dest.resolve("icon.png")));
        }

        // metadata.json sémantiquement équivalent
        FlashbackReplay round = FlashbackReader.open(dest);
        assertEquals(replay.metadata().uuid(), round.metadata().uuid());
        assertEquals(replay.metadata().totalTicks(), round.metadata().totalTicks());
        assertEquals(replay.metadata().chunks().size(), round.metadata().chunks().size());
        assertEquals(replay.metadata().markers().size(), round.metadata().markers().size());
    }
}
