package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdRemapperTest {

    @Test
    void mapThenRemapReturnsGlobalId() {
        IdRemapper remapper = new IdRemapper();
        int globalId = remapper.assign(0, 42);
        assertEquals(globalId, remapper.remap(0, 42));
    }

    @Test
    void differentSourcesGetDistinctGlobalIds() {
        IdRemapper remapper = new IdRemapper();
        int g1 = remapper.assign(0, 42);
        int g2 = remapper.assign(1, 42);
        assertNotEquals(g1, g2);
    }

    @Test
    void sameSourceAndLocalIdMappedTwiceKeepsSameGlobalId() {
        IdRemapper remapper = new IdRemapper();
        int g1 = remapper.assign(0, 42);
        int g2 = remapper.assign(0, 42);
        assertEquals(g1, g2);
    }

    @Test
    void remapOfUnmappedThrows() {
        IdRemapper remapper = new IdRemapper();
        assertThrows(IllegalStateException.class, () -> remapper.remap(0, 42));
    }

    @Test
    void globalIdsStartAbove100Million() {
        IdRemapper remapper = new IdRemapper();
        assertTrue(remapper.assign(0, 1) >= 100_000_000,
                "globalIds doivent commencer >= 100M pour éviter collision avec IDs locaux");
    }

    @Test
    void assignToExistingGlobalId() {
        IdRemapper remapper = new IdRemapper();
        int g = remapper.assign(0, 10);
        remapper.assignExisting(1, 99, g);
        assertEquals(g, remapper.remap(1, 99));
    }
}
