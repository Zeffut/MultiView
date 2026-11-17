package fr.zeffut.multiview.merge;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.google.common.collect.ImmutableMultimap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ProfileUpgradeDeduperTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @Test void completeSignedProfileMustNotBeHiddenByEarlierFallbackOrEmptyProfile() {
        UUID uuid = UUID.fromString("0e15d052-0000-0000-0000-000000000001");
        var synth = new SecondaryPlayerSynthesizer(new IdRemapper());
        var deduper = new PlayerInfoUpdateDeduper();
        var stub = new GameProfile(uuid, "Player-0e15d052");
        var named = new GameProfile(uuid, "PovBeta");
        var full = new GameProfile(uuid, "PovBeta", new PropertyMap(ImmutableMultimap.of(
                "textures", new Property("textures", "value", "signature"))));
        assertTrue(deduper.shouldEmitInfoUpdate(synth.synthesizePlayerInfoUpdate(stub)));
        assertTrue(deduper.shouldEmitInfoUpdate(synth.synthesizePlayerInfoUpdate(named)));
        assertTrue(deduper.shouldEmitInfoUpdate(synth.synthesizePlayerInfoUpdate(full)));
        assertFalse(deduper.shouldEmitInfoUpdate(synth.synthesizePlayerInfoUpdate(full)));
    }
    @Test void secondarySignedAnnouncementBeforeOrAfterCreateOverridesPrimaryEmptyProfile(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        UUID uuid = UUID.fromString("0e15d052-0000-0000-0000-000000000001");
        var plain = new GameProfile(uuid, "PovBeta");
        var signed = new GameProfile(uuid, "PovBeta", new PropertyMap(ImmutableMultimap.of(
                "textures", new Property("textures", "value", "signature"))));
        var unsigned = new GameProfile(uuid, "PovBeta", new PropertyMap(ImmutableMultimap.of(
                "textures", new Property("textures", "value"))));
        for (int variant = 0; variant < 4; variant++) {
            boolean before = (variant & 1) != 0;
            var initial = variant < 2 ? plain : unsigned;
            var root = tmp.resolve(Integer.toString(variant));
            var a = profileSource(root.resolve("a"), initial, signed, true, before);
            var b = profileSource(root.resolve("b"), plain, signed, false, before);
            var out = root.resolve("out");
            var report = MergeOrchestrator.run(new MergeOptions(java.util.List.of(a,b), out, java.util.Map.of(), true), s -> {});
            assertTrue(report.errors.isEmpty(), report.errors.toString());
            boolean found = false;
            try (var zip = java.nio.file.FileSystems.newFileSystem(java.nio.file.Path.of(out + ".zip"))) {
                var replay = fr.zeffut.multiview.format.FlashbackReader.open(zip.getPath("/"));
                try (var stream = fr.zeffut.multiview.format.FlashbackReader.stream(replay)) {
                    for (var e : stream.toList()) {
                        if (!(e.action() instanceof fr.zeffut.multiview.format.Action.GamePacket gp)) continue;
                        if (PacketClassifier.readPacketId(gp.bytes()) != GamePacketDispatch.findId(
                                net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE)) continue;
                        var raw = io.netty.buffer.Unpooled.wrappedBuffer(gp.bytes());
                        try {
                            fr.zeffut.multiview.format.VarInts.readVarInt(raw);
                            var piu = net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(
                                    new net.minecraft.network.RegistryFriendlyByteBuf(raw, net.minecraft.core.RegistryAccess.EMPTY));
                            for (var entry : piu.entries()) if (signed.equals(entry.profile())) found = true;
                        } finally { raw.release(); }
                    }
                }
            }
            assertTrue(found, "Signed profile must survive primary announcement, variant=" + variant);
        }
    }
    private java.nio.file.Path profileSource(java.nio.file.Path dir, GameProfile plain, GameProfile signed,
                                             boolean primary, boolean before) throws Exception {
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("metadata.json"), """
            {"uuid":"%s", "name":"2026-01-01T00_00_00", "version_string":"26.2",
             "data_version":4903, "protocol_version":776, "total_ticks":3,
             "chunks":{"c0.flashback":{"duration":3}}}
            """.formatted(UUID.randomUUID()));
        var reg = java.util.List.of(fr.zeffut.multiview.format.ActionType.NEXT_TICK,
                fr.zeffut.multiview.format.ActionType.GAME_PACKET, fr.zeffut.multiview.format.ActionType.CREATE_PLAYER);
        var w = new fr.zeffut.multiview.format.SegmentWriter("c0.flashback", reg);
        var synth = new SecondaryPlayerSynthesizer(new IdRemapper());
        if (primary) w.writeSnapshotAction(1, synth.synthesizePlayerInfoUpdate(plain));
        w.endSnapshot(); w.openStreamingFile(dir.resolve("c0.flashback"));
        if (!primary) {
            if (before) w.writeLiveAction(1, synth.synthesizePlayerInfoUpdate(signed));
            var raw = io.netty.buffer.Unpooled.buffer();
            byte[] create;
            try {
                var friendly = new net.minecraft.network.FriendlyByteBuf(raw);
                friendly.writeUUID(plain.id());
                raw.writeDouble(0).writeDouble(64).writeDouble(0);
                raw.writeFloat(0).writeFloat(0).writeFloat(0);
                raw.writeDouble(1).writeDouble(2).writeDouble(3);
                net.minecraft.network.codec.ByteBufCodecs.GAME_PROFILE.encode(raw, plain);
                friendly.writeVarInt(0);
                create = new byte[raw.readableBytes()]; raw.readBytes(create);
            } finally { raw.release(); }
            w.writeLiveAction(2, create);
            w.writeLiveAction(0, new byte[0]);
            if (!before) w.writeLiveAction(1, synth.synthesizePlayerInfoUpdate(signed));
        } else w.writeLiveAction(0, new byte[0]);
        w.writeLiveAction(0, new byte[0]); w.writeLiveAction(0, new byte[0]); w.finishStreaming();
        return dir;
    }
    @Test void realNameWithPlayerPrefixIsNotAutomaticallyAPlaceholder() {
        var synth = new SecondaryPlayerSynthesizer(new IdRemapper());
        var deduper = new PlayerInfoUpdateDeduper();
        var profile = new GameProfile(UUID.randomUUID(), "Player-Beta");
        byte[] payload = synth.synthesizePlayerInfoUpdate(profile);
        assertTrue(deduper.shouldEmitInfoUpdate(payload));
        assertFalse(deduper.shouldEmitInfoUpdate(payload));
    }
}
