package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for the owner-ID field, not a claim of in-game projectile validation. */
class ProjectileSpawnDataTest {
    @Test void rawOwnerIdIsRemappedWithoutAddingOne() {
        assertEquals(100_000_042, EntityPacketRewriter.rewriteProjectileOwner(
                "minecraft:arrow", 9, 42, id -> true, id -> id + 100_000_000,
                id -> true, id -> id + 100_000_000));
    }
    @Test void absentProjectileOwnerStaysZeroAndDoesNotAllocateAnIdentity() {
        assertEquals(0, EntityPacketRewriter.rewriteProjectileOwner(
                "minecraft:snowball", 9, 0, id -> fail("Unexpected lookup"),
                id -> fail("Unexpected lookup"), id -> fail("Unexpected lookup"),
                id -> fail("No-owner sentinel must not allocate")));
    }
    @Test void nonOwnerSpawnDataIsNeverReinterpretedAsAnEntityId() {
        for (String type : new String[] {"minecraft:falling_block", "minecraft:item_frame",
                "minecraft:glow_item_frame", "minecraft:painting", "minecraft:warden", "other:custom"}) {
            assertEquals(42, EntityPacketRewriter.rewriteProjectileOwner(type, 9, 42,
                    id -> fail("Unexpected lookup for " + type), id -> fail("Unexpected lookup"),
                    id -> fail("Unexpected lookup"), id -> fail("Unexpected remap")), type);
        }
    }
    @Test void fishingHookRequiresAnAlreadyEmittedPlayerAndUsesItsRawGlobalId() {
        assertEquals(100_000_042, EntityPacketRewriter.rewriteProjectileOwner(
                "minecraft:fishing_bobber", 9, 42, id -> id == 42,
                id -> 100_000_042, id -> id == 100_000_042,
                id -> fail("Owner was already emitted; no reservation is necessary")));
    }
    @Test void futureOwnerReservationIsNotMistakenForAnAlreadySpawnedPlayer() {
        AtomicBoolean remapped = new AtomicBoolean();
        assertThrows(IllegalStateException.class, () -> EntityPacketRewriter.rewriteProjectileOwner(
                "minecraft:fishing_bobber", 9, 42, id -> true,
                id -> 100_000_042, id -> false,
                id -> { remapped.set(true); return 100_000_042; }));
        assertFalse(remapped.get());
    }
    @Test void missingHookOwnerAndSelfIdFallbackFailExplicitly() {
        for (int owner : new int[] {0, 9, 42}) {
            assertThrows(IllegalStateException.class, () -> EntityPacketRewriter.rewriteProjectileOwner(
                    "minecraft:fishing_bobber", 9, owner, id -> false,
                    id -> fail("Cannot resolve a missing mapping"), id -> false,
                    id -> fail("Must not publish a guessed owner")));
        }
    }
    @Test void emittedPlayerPredicateReflectsLifecycleRatherThanOnlyAllocation() {
        IdRemapper ids = new IdRemapper();
        EntityMerger merger = new EntityMerger(new SourcePovTracker(1), ids);
        java.util.UUID uuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000042");
        int global = merger.reserveIdentity(uuid);
        assertFalse(merger.hasEmittedPlayer(global));
        assertEquals(global, merger.registerAddEntity(0, 42, uuid, "minecraft:player", 0, 0, 64, 0));
        assertFalse(merger.hasEmittedPlayer(global));
        assertTrue(merger.shouldEmitAddEntity(global));
        assertTrue(merger.hasEmittedPlayer(global));
        assertEquals(global, merger.removeEntity(0, 42, 10));
        assertFalse(merger.hasEmittedPlayer(global));
    }
}
