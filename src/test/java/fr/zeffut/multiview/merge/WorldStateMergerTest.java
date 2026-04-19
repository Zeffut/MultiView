package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldStateMergerTest {

    private static final String DIM = "minecraft:overworld";

    @Test
    void firstUpdateForPosIsAccepted() {
        WorldStateMerger m = new WorldStateMerger();
        assertTrue(m.acceptBlockUpdate(DIM, 0, 0, 0, 100, 42, 0));
    }

    @Test
    void olderUpdateIsRejected() {
        WorldStateMerger m = new WorldStateMerger();
        m.acceptBlockUpdate(DIM, 0, 0, 0, 100, 42, 0);
        assertFalse(m.acceptBlockUpdate(DIM, 0, 0, 0, 50, 99, 1),
                "un update plus ancien doit être rejeté");
    }

    @Test
    void newerUpdateOverwrites() {
        WorldStateMerger m = new WorldStateMerger();
        m.acceptBlockUpdate(DIM, 0, 0, 0, 100, 42, 0);
        assertTrue(m.acceptBlockUpdate(DIM, 0, 0, 0, 200, 99, 1));
    }

    @Test
    void sameTickDuplicateIsRejected() {
        WorldStateMerger m = new WorldStateMerger();
        m.acceptBlockUpdate(DIM, 0, 0, 0, 100, 42, 0);
        assertFalse(m.acceptBlockUpdate(DIM, 0, 0, 0, 100, 42, 1),
                "même tick, même bloc = déjà émis");
    }

    @Test
    void differentDimensionsAreIndependent() {
        WorldStateMerger m = new WorldStateMerger();
        m.acceptBlockUpdate("minecraft:overworld", 0, 0, 0, 100, 42, 0);
        assertTrue(m.acceptBlockUpdate("minecraft:the_nether", 0, 0, 0, 50, 99, 1),
                "dimensions distinctes = positions distinctes");
    }

    @Test
    void conflictStatsAreTracked() {
        WorldStateMerger m = new WorldStateMerger();
        m.acceptBlockUpdate(DIM, 0, 0, 0, 100, 42, 0);
        m.acceptBlockUpdate(DIM, 0, 0, 0, 50, 99, 1);  // rejeté (plus ancien)
        m.acceptBlockUpdate(DIM, 0, 0, 0, 200, 77, 0); // accepté (overwrite)
        assertEquals(1, m.lwwConflicts());
        assertEquals(1, m.lwwOverwrites());
    }
}
