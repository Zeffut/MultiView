package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EntityLifecycleTest {
    private final IdRemapper ids = new IdRemapper();
    private final EntityMerger merger = new EntityMerger(new SourcePovTracker(2), ids);
    private final UUID uuid = UUID.fromString("f8dd77bc-383a-438a-bfe2-38fb6b5b4fe7");
    private int add(int source, int local, int tick) {
        return merger.registerAddEntity(source, local, uuid, "minecraft:slime", tick, 0, 64, 0);
    }
    @Test void realCorpusOffset160MustNotSpawnSameUuidTwice() {
        int first = add(0, 42, 0);
        assertTrue(merger.shouldEmitAddEntity(first));
        int second = add(1, 7, 160);
        assertEquals(first, second);
        assertFalse(merger.shouldEmitAddEntity(second));
        assertEquals(first, ids.remap(1, 7));
    }
    @Test void silenceDoesNotForgetLivingEntityAtTick302() {
        int first = add(0, 42, 0);
        assertTrue(merger.shouldEmitAddEntity(first));
        merger.purge(302);
        int second = add(1, 7, 302);
        assertEquals(first, second);
        assertFalse(merger.shouldEmitAddEntity(second));
        assertEquals(first, ids.remap(0, 42));
    }
    @Test void oneObserverLeavingDoesNotDespawnButLastRemovalDoes() {
        int first = add(0, 42, 0);
        assertTrue(merger.shouldEmitAddEntity(first));
        add(1, 7, 160);
        assertEquals(-1, merger.removeEntity(0, 42, 170));
        assertFalse(ids.contains(0, 42));
        assertEquals(first, ids.remap(1, 7));
        assertEquals(first, merger.removeEntity(1, 7, 180));
        assertEquals(0, ids.size());
        int respawn = add(0, 43, 200);
        assertEquals(first, respawn); // Stable UUID identity; a fresh Add is still mandatory below.
        assertTrue(merger.shouldEmitAddEntity(respawn));
    }
    @Test void observerCanReturnWithoutDuplicateSpawn() {
        int first = add(0, 42, 0);
        assertTrue(merger.shouldEmitAddEntity(first));
        add(1, 7, 10);
        assertEquals(-1, merger.removeEntity(1, 7, 20));
        assertEquals(first, add(1, 8, 40));
        assertFalse(merger.shouldEmitAddEntity(first));
        assertEquals(-1, merger.removeEntity(0, 42, 50));
        assertEquals(first, merger.removeEntity(1, 8, 60));
    }
    @Test void repeatedObservationIsNotAnExtraObserver() {
        int first = add(0, 42, 0);
        assertTrue(merger.shouldEmitAddEntity(first));
        add(0, 42, 10);
        assertEquals(first, merger.removeEntity(0, 42, 20));
    }
    @Test void endedRecordingDoesNotVetoLaterDespawn() {
        int first = add(0, 42, 0);
        assertTrue(merger.shouldEmitAddEntity(first));
        add(1, 7, 10);
        merger.setSourceEndTick(1, 100);
        assertEquals(first, merger.removeEntity(0, 42, 101));
        assertEquals(0, ids.size());
    }
    @Test void nearbyDifferentUuidsMustNeverFuse() {
        int first = add(0, 42, 0);
        int other = merger.registerAddEntity(1, 7, UUID.randomUUID(), "minecraft:slime", 0, 0, 64, 0);
        assertNotEquals(first, other);
    }
    @Test void endingSourceDetachesObserversAndReclaimsOnlyUnsharedEntities() {
        int shared = add(0, 42, 0);
        assertTrue(merger.shouldEmitAddEntity(shared));
        add(1, 7, 0);
        for (int i = 0; i < 1000; i++) {
            int id = merger.registerAddEntity(0, 1000 + i, UUID.randomUUID(),
                    "minecraft:slime", 0, 0, 64, 0);
            assertTrue(merger.shouldEmitAddEntity(id));
        }
        assertEquals(1000, merger.endSource(0).size());
        assertEquals(1, ids.size());
        assertEquals(shared, ids.remap(1, 7));
        assertEquals(java.util.List.of(shared), merger.endSource(1));
        assertEquals(0, ids.size());
        assertTrue(merger.endSource(1).isEmpty());
    }
    @Test void unresolvedReservationsAreReleasedOnRemoveAndSourceEnd() {
        int orphan = ids.assign(0, 7);
        int retained = ids.assign(1, 7);
        assertEquals(-1, merger.removeEntity(0, 7, 1));
        assertFalse(ids.contains(0, 7));
        assertNotEquals(orphan, ids.assign(0, 7));
        ids.assign(0, 8);
        assertTrue(merger.endSource(0).isEmpty());
        assertFalse(ids.contains(0, 7));
        assertFalse(ids.contains(0, 8));
        assertEquals(retained, ids.remap(1, 7));
    }
    @Test void preallocatedReferenceCannotBeSilentlyReassigned() {
        int existing = add(0, 42, 0);
        int premature = ids.assign(1, 7);
        assertNotEquals(existing, premature);
        assertThrows(IllegalStateException.class, () -> add(1, 7, 10));
        assertEquals(premature, ids.remap(1, 7));
        assertEquals(existing, ids.remap(0, 42));
    }
    @Test void syntheticAndRealPlayerShareIdentityInBothOrders() {
        for (boolean syntheticFirst : new boolean[]{true, false}) {
            IdRemapper localIds = new IdRemapper();
            EntityMerger m = new EntityMerger(new SourcePovTracker(2), localIds);
            int firstSource = syntheticFirst ? 1 : 0;
            int firstLocal = syntheticFirst ? -1 : 42;
            int secondSource = syntheticFirst ? 0 : 1;
            int secondLocal = syntheticFirst ? 42 : -1;
            int first = m.registerAddEntity(firstSource, firstLocal, uuid,
                    "minecraft:player", 0, 0, 0, 0);
            assertTrue(m.shouldEmitAddEntity(first));
            int second = m.registerAddEntity(secondSource, secondLocal, uuid,
                    "minecraft:player", 10, 0, 0, 0);
            assertEquals(first, second);
            assertFalse(m.shouldEmitAddEntity(second));
        }
    }
}
