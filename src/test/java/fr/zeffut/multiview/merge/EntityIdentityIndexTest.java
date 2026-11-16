package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EntityIdentityIndexTest {
    private final UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");
    @Test void anticipatesSharedUuidInsteadOfPublishingAConflictingReservation() {
        EntityIdentityIndex index = new EntityIdentityIndex();
        index.add(0, 7, 0, a); index.add(1, 33, 320, a);
        IdRemapper ids = new IdRemapper();
        EntityMerger merger = new EntityMerger(new SourcePovTracker(2), ids);
        int identity = merger.reserveIdentity(index.resolve(1, 33, 0));
        ids.assignExisting(1, 33, identity); // Earlier reference already written.
        assertEquals(identity, merger.registerAddEntity(0, 7, a, "minecraft:slime", 0, 0, 64, 0));
        assertTrue(merger.shouldEmitAddEntity(identity));
        assertEquals(identity, merger.registerAddEntity(1, 33, a, "minecraft:slime", 320, 0, 64, 0));
        assertFalse(merger.shouldEmitAddEntity(identity));
    }
    @Test void reversedSourceOrderDoesNotCreateTwoLiveIdentities() {
        EntityIdentityIndex index = new EntityIdentityIndex();
        index.add(1, 7, 0, a); index.add(0, 33, 320, a);
        IdRemapper ids = new IdRemapper();
        EntityMerger merger = new EntityMerger(new SourcePovTracker(2), ids);
        int identity = merger.reserveIdentity(index.resolve(0, 33, 0));
        ids.assignExisting(0, 33, identity);
        assertEquals(identity, merger.registerAddEntity(1, 7, a, "minecraft:slime", 0, 0, 64, 0));
        assertTrue(merger.shouldEmitAddEntity(identity));
        assertEquals(identity, merger.registerAddEntity(0, 33, a, "minecraft:slime", 320, 0, 64, 0));
        assertFalse(merger.shouldEmitAddEntity(identity));
    }
    @Test void localIdReuseResolvesTheCorrectLifetime() {
        EntityIdentityIndex index = new EntityIdentityIndex();
        index.add(0, 7, 0, a); index.remove(0, 7, 50); index.add(0, 7, 100, b);
        assertEquals(a, index.resolve(0, 7, 20));
        assertThrows(EntityIdentityIndex.ResolutionFailure.class, () -> index.resolve(0, 7, 70));
        assertEquals(b, index.resolve(0, 7, 120));
    }
    @Test void neverSpawnedReferencesAreIsolatedRatherThanGuessedAcrossSources() {
        EntityIdentityIndex index = new EntityIdentityIndex(); index.add(1, 7, 0, a);
        assertNull(index.resolve(0, 7, 0));
        index.remove(1, 7, 10);
        assertNull(index.resolve(1, 7, 11));
    }
    @Test void ambiguousSameTickReuseIsRejectedBeforeAnyPacketIsWritten() {
        EntityIdentityIndex index = new EntityIdentityIndex(); index.add(0, 7, 0, a);
        assertThrows(EntityIdentityIndex.ResolutionFailure.class, () -> index.add(0, 7, 0, b));
    }
}
