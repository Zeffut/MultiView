package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EncodedIdentityRegressionTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @Test void syntheticPlayerUsesRegisteredPlayerEntityType() {
        UUID uuid = UUID.randomUUID();
        var synth = new SecondaryPlayerSynthesizer(new IdRemapper());
        byte[] encoded = synth.synthesizeAddEntity(0, uuid);
        assertNotNull(encoded);
        ByteBuf raw = Unpooled.wrappedBuffer(encoded);
        try {
            assertEquals(GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_ADD_ENTITY), VarInts.readVarInt(raw));
            var packet = ClientboundAddEntityPacket.STREAM_CODEC.decode(new RegistryFriendlyByteBuf(raw,
                    RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
            assertEquals("minecraft:player", BuiltInRegistries.ENTITY_TYPE.getKey(packet.getType()).toString());
            assertEquals(uuid, packet.getUUID());
            assertEquals(0, raw.readableBytes());
        } finally { raw.release(); }
    }
    record Fixture(IdRemapper ids, EntityMerger merger, EntityPacketRewriter rewriter) {}
    private Fixture fixture() {
        IdRemapper ids = new IdRemapper(); SourcePovTracker pov = new SourcePovTracker(2);
        EntityMerger merger = new EntityMerger(pov, ids);
        return new Fixture(ids, merger, new EntityPacketRewriter(merger, ids, pov, 2));
    }
    private byte[] spawn(String name, int id, UUID uuid, int owner) {
        EntityType<?> type = null;
        for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
            if (BuiltInRegistries.ENTITY_TYPE.getKey(t).toString().equals("minecraft:" + name)) type = t;
        }
        assertNotNull(type, name);
        ByteBuf b = Unpooled.buffer();
        try {
            VarInts.writeVarInt(b, GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_ADD_ENTITY));
            ClientboundAddEntityPacket.STREAM_CODEC.encode(new RegistryFriendlyByteBuf(b,
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)),
                new ClientboundAddEntityPacket(id, uuid, 0, 64, 0, 0, 0, type, owner, Vec3.ZERO, 0));
            byte[] result = new byte[b.readableBytes()]; b.readBytes(result); return result;
        } finally { b.release(); }
    }
    private ClientboundAddEntityPacket decode(byte[] payload) {
        ByteBuf b = Unpooled.wrappedBuffer(payload);
        try {
            VarInts.readVarInt(b);
            var packet = ClientboundAddEntityPacket.STREAM_CODEC.decode(new RegistryFriendlyByteBuf(b,
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
            assertEquals(0, b.readableBytes()); return packet;
        } finally { b.release(); }
    }
    private byte[] motion(int id) {
        ByteBuf b = Unpooled.buffer();
        try { VarInts.writeVarInt(b, GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_SET_ENTITY_MOTION));
            VarInts.writeVarInt(b, id); b.writeZero(6); byte[] r = new byte[b.readableBytes()]; b.readBytes(r); return r;
        } finally { b.release(); }
    }
    private byte[] remove(int id) {
        ByteBuf b = Unpooled.buffer();
        try { VarInts.writeVarInt(b, GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_REMOVE_ENTITIES));
            VarInts.writeVarInt(b, 1); VarInts.writeVarInt(b, id); byte[] r = new byte[b.readableBytes()]; b.readBytes(r); return r;
        } finally { b.release(); }
    }
    private int referenceId(byte[] payload) {
        ByteBuf b = Unpooled.wrappedBuffer(payload);
        try { VarInts.readVarInt(b); return VarInts.readVarInt(b); } finally { b.release(); }
    }
    @Test void encodedProjectileBeforeOwnerFailsRatherThanReturningLocalIdPacket() {
        var f = fixture(); f.rewriter.beginIdentityIndex();
        byte[] owner = spawn("cow", 42, UUID.randomUUID(), 0);
        byte[] arrow = spawn("arrow", 9, UUID.randomUUID(), 42);
        f.rewriter.indexGamePacket(0, 10, arrow); f.rewriter.indexGamePacket(0, 20, owner);
        assertThrows(IllegalStateException.class, () -> f.rewriter.rewrite(0, 10, arrow));
        assertFalse(f.ids.contains(0, 9), "failed projectile must not register its identity");
    }
    @Test void encodedProjectileCanReferenceAnEmittedNonPlayerAndCanHaveNoOwner() {
        var f = fixture();
        int owner = decode(f.rewriter.rewrite(0, 0, spawn("cow", 42, UUID.randomUUID(), 0))).getId();
        assertEquals(owner, decode(f.rewriter.rewrite(0, 1, spawn("arrow", 9, UUID.randomUUID(), 42))).getData());
        assertEquals(0, decode(f.rewriter.rewrite(0, 2, spawn("arrow", 10, UUID.randomUUID(), 0))).getData());
    }
    @Test void staleEncodedReferenceCannotTargetFutureLifeAlreadyVisibleFromAnotherSource() {
        var f = fixture(); f.rewriter.beginIdentityIndex();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        byte[] old = spawn("cow", 7, a, 0), future = spawn("cow", 7, b, 0);
        byte[] other = spawn("cow", 8, b, 0), remove = remove(7);
        f.rewriter.indexGamePacket(0, 0, old); f.rewriter.indexGamePacket(0, 10, remove);
        f.rewriter.indexGamePacket(0, 30, future); f.rewriter.indexGamePacket(1, 0, other);
        int otherGlobal = decode(f.rewriter.rewrite(1, 0, other)).getId();
        f.rewriter.rewrite(0, 0, old); f.rewriter.rewrite(0, 10, remove);
        assertNotEquals(otherGlobal, referenceId(f.rewriter.rewrite(0, 20, motion(7))));
        assertNull(f.rewriter.rewrite(0, 30, future), "already emitted UUID is not spawned twice");
        assertEquals(otherGlobal, referenceId(f.rewriter.rewrite(0, 31, motion(7))));
    }
    @Test void rejectedOwnerOrderCannotProduceAFinalZip(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        var a = packetSource(tmp.resolve("a"), true);
        var b = packetSource(tmp.resolve("b"), false);
        var out = tmp.resolve("rejected");
        Exception failure = assertThrows(Exception.class, () -> MergeOrchestrator.run(
                new MergeOptions(java.util.List.of(a, b), out, java.util.Map.of(), true), s -> {}));
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        assertTrue(root.toString().contains("Projectile owner is not yet emitted"), root.toString());
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(out + ".zip")));
    }
    private java.nio.file.Path packetSource(java.nio.file.Path dir, boolean projectile) throws Exception {
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("metadata.json"), """
            {"uuid":"%s", "name":"2026-01-01T00_00_00", "version_string":"26.2",
             "data_version":4903, "protocol_version":776, "total_ticks":3,
             "chunks":{"c0.flashback":{"duration":3}}}
            """.formatted(UUID.randomUUID()));
        var writer = new fr.zeffut.multiview.format.SegmentWriter("c0.flashback",
                java.util.List.of(fr.zeffut.multiview.format.ActionType.NEXT_TICK,
                        fr.zeffut.multiview.format.ActionType.GAME_PACKET));
        writer.endSnapshot(); writer.openStreamingFile(dir.resolve("c0.flashback"));
        if (projectile) writer.writeLiveAction(1, spawn("arrow", 9, UUID.randomUUID(), 42));
        writer.writeLiveAction(0, new byte[0]);
        if (projectile) writer.writeLiveAction(1, spawn("cow", 42, UUID.randomUUID(), 0));
        writer.writeLiveAction(0, new byte[0]); writer.writeLiveAction(0, new byte[0]);
        writer.finishStreaming(); return dir;
    }
    @Test void encodedReferenceBeforeFirstSpawnResolvesSharedIdentity() {
        var f = fixture(); f.rewriter.beginIdentityIndex();
        UUID shared = UUID.randomUUID();
        byte[] first = spawn("cow", 8, shared, 0), later = spawn("cow", 7, shared, 0);
        f.rewriter.indexGamePacket(1, 0, first); f.rewriter.indexGamePacket(0, 30, later);
        int global = decode(f.rewriter.rewrite(1, 0, first)).getId();
        assertEquals(global, referenceId(f.rewriter.rewrite(0, 20, motion(7))));
        assertNull(f.rewriter.rewrite(0, 30, later));
    }
}
