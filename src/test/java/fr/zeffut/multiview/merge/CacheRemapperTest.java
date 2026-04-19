package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CacheRemapperTest {

    @Test
    void concatTwoSourcesProducesCorrectMapping(@TempDir Path tmp) throws Exception {
        Path srcA = tmp.resolve("A/level_chunk_caches");
        Path srcB = tmp.resolve("B/level_chunk_caches");
        Files.createDirectories(srcA);
        Files.createDirectories(srcB);
        Files.writeString(srcA.resolve("0"), "A0");
        Files.writeString(srcA.resolve("1"), "A1");
        Files.writeString(srcB.resolve("0"), "B0");

        Path dest = tmp.resolve("merged/level_chunk_caches");
        CacheRemapper remapper = new CacheRemapper();
        remapper.concat(List.of(srcA, srcB), dest);

        assertEquals("A0", Files.readString(dest.resolve("0")));
        assertEquals("A1", Files.readString(dest.resolve("1")));
        assertEquals("B0", Files.readString(dest.resolve("2")));

        assertEquals(0, remapper.remap(0, 0));
        assertEquals(1, remapper.remap(0, 1));
        assertEquals(2, remapper.remap(1, 0));
    }

    @Test
    void missingCacheDirIsSkipped(@TempDir Path tmp) throws Exception {
        Path srcA = tmp.resolve("A/level_chunk_caches");
        Files.createDirectories(srcA);
        Files.writeString(srcA.resolve("0"), "A0");

        Path srcB = tmp.resolve("B/level_chunk_caches"); // n'existe pas

        Path dest = tmp.resolve("merged/level_chunk_caches");
        CacheRemapper remapper = new CacheRemapper();
        remapper.concat(List.of(srcA, srcB), dest);

        assertEquals("A0", Files.readString(dest.resolve("0")));
        assertEquals(0, remapper.remap(0, 0));
        assertFalse(remapper.contains(1, 0));
    }

    @Test
    void remapOfUnknownThrows() {
        CacheRemapper remapper = new CacheRemapper();
        assertThrows(IllegalStateException.class, () -> remapper.remap(0, 0));
    }
}
