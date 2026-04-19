package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlobalDeduperTest {

    @Test
    void firstEmissionReturnsTrue() {
        GlobalDeduper d = new GlobalDeduper();
        assertTrue(d.shouldEmit(0x42, 100, new byte[]{1, 2, 3}));
    }

    @Test
    void duplicateAtSameTickReturnsFalse() {
        GlobalDeduper d = new GlobalDeduper();
        d.shouldEmit(0x42, 100, new byte[]{1, 2, 3});
        assertFalse(d.shouldEmit(0x42, 100, new byte[]{1, 2, 3}));
    }

    @Test
    void sameContentDifferentTickIsNotDedup() {
        GlobalDeduper d = new GlobalDeduper();
        d.shouldEmit(0x42, 100, new byte[]{1, 2, 3});
        assertTrue(d.shouldEmit(0x42, 101, new byte[]{1, 2, 3}));
    }

    @Test
    void sameTickDifferentContentIsNotDedup() {
        GlobalDeduper d = new GlobalDeduper();
        d.shouldEmit(0x42, 100, new byte[]{1, 2, 3});
        assertTrue(d.shouldEmit(0x42, 100, new byte[]{4, 5, 6}));
    }

    @Test
    void purgeOldEntries() {
        GlobalDeduper d = new GlobalDeduper();
        d.shouldEmit(0x42, 100, new byte[]{1, 2, 3});
        d.purgeOlderThan(300); // garde tick ≥ 300, donc purge
        assertTrue(d.shouldEmit(0x42, 100, new byte[]{1, 2, 3}),
                "après purge, le même key réapparaît comme nouveau");
    }
}
