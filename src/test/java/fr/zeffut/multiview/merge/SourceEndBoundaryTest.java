package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SourceEndBoundaryTest {
    @Test void sharedEntitySurvivesShortSourceThenDisappearsAtFinalBoundary() {
        IdRemapper ids = new IdRemapper();
        EntityMerger merger = new EntityMerger(new SourcePovTracker(2), ids);
        UUID uuid = UUID.randomUUID();
        int global = merger.registerAddEntity(0, 42, uuid, "minecraft:slime", 0, 0, 64, 0);
        assertTrue(merger.shouldEmitAddEntity(global));
        assertEquals(global, merger.registerAddEntity(1, 7, uuid, "minecraft:slime", 0, 0, 64, 0));
        merger.setSourceEndTick(0, 100);
        merger.setSourceEndTick(1, 200);
        assertEquals(List.of(), merger.endSourcesThrough(99));
        assertEquals(List.of(), merger.endSourcesThrough(100));
        assertFalse(ids.contains(0, 42));
        assertTrue(ids.contains(1, 7));
        assertEquals(List.of(global), merger.endSourcesThrough(200));
        assertEquals(0, ids.size());
        assertEquals(List.of(), merger.endSourcesThrough(201));
    }
    @Test void sourceEndDespawnsDuringEmptyGapAndSameUuidCanRespawn() {
        IdRemapper ids = new IdRemapper();
        EntityMerger merger = new EntityMerger(new SourcePovTracker(2), ids);
        UUID uuid = UUID.randomUUID();
        int first = merger.registerAddEntity(0, 42, uuid, "minecraft:slime", 0, 0, 64, 0);
        assertTrue(merger.shouldEmitAddEntity(first));
        merger.setSourceEndTick(0, 100);
        assertEquals(List.of(first), merger.endSourcesThrough(100));
        assertEquals(0, ids.size());
        int next = merger.registerAddEntity(1, 42, uuid, "minecraft:slime", 400, 0, 64, 0);
        assertEquals(first, next); // Reuse is safe only after the preceding Remove was emitted.
        assertTrue(merger.shouldEmitAddEntity(next));
    }
}
