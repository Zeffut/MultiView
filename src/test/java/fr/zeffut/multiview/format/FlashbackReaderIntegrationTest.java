package fr.zeffut.multiview.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashbackReaderIntegrationTest {

    private static final Path REPLAYS_DIR = Paths.get(System.getProperty("user.dir"), "run", "replay");

    static boolean replaysAvailable() {
        return Files.isDirectory(REPLAYS_DIR)
                && hasAnyReplay(REPLAYS_DIR);
    }

    private static boolean hasAnyReplay(Path dir) {
        try {
            return Files.list(dir)
                    .filter(Files::isDirectory)
                    .anyMatch(d -> Files.isRegularFile(d.resolve("metadata.json")));
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    @EnabledIf("replaysAvailable")
    void openAllAvailableReplays() throws IOException {
        AtomicInteger count = new AtomicInteger();
        try (var stream = Files.list(REPLAYS_DIR)) {
            stream.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("metadata.json")))
                    .forEach(folder -> {
                        try {
                            FlashbackReplay replay = FlashbackReader.open(folder);
                            int sumDurations = replay.metadata().chunks().values().stream()
                                    .mapToInt(FlashbackMetadata.ChunkInfo::duration)
                                    .sum();
                            assertEquals(replay.metadata().totalTicks(), sumDurations,
                                    "total_ticks mismatch in " + folder);
                            long decoded = FlashbackReader.stream(replay).count();
                            assertTrue(decoded > 0, "no entries decoded for " + folder);
                            count.incrementAndGet();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        assertTrue(count.get() > 0, "no replays were inspected");
    }
}
