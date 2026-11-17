package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.*;
import io.netty.buffer.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.*;
import com.mojang.authlib.GameProfile;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class PlayerInfoCodecCorpusTest {
    private static ClientboundPlayerInfoUpdatePacket decode(byte[] bytes) {
        ByteBuf raw = Unpooled.wrappedBuffer(bytes);
        VarInts.readVarInt(raw);
        var packet = ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY));
        assertEquals(0, raw.readableBytes(), "trailing packet bytes");
        return packet;
    }
    @Test void synthesizedPacketRoundTripsRealCodec() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var profile = new GameProfile(UUID.fromString("821de807-6dc7-4d1b-b512-0476aabbccdd"), "PovBeta");
        var synth = new SecondaryPlayerSynthesizer(new IdRemapper());
        assertTrue(synth.isAvailable());
        byte[] payload = assertNotNullBytes(synth.synthesizePlayerInfoUpdate(profile));
        var decoded = decode(payload);
        assertEquals(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER), decoded.actions());
        assertEquals(profile, decoded.entries().getFirst().profile());
        ByteBuf raw = Unpooled.buffer();
        ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.encode(new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY), decoded);
        ByteBuf original = Unpooled.wrappedBuffer(payload); VarInts.readVarInt(original);
        assertEquals(original, raw);
    }
    private static byte[] assertNotNullBytes(byte[] data) { assertNotNull(data); return data; }
    @Test void capturedRealPacketRegression() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        byte[] broken = HexFormat.of().parseHex("4601010e15d0520f7b388a835cffbbeb798c820e15d0520f7b388a835cffbbeb798c820f506c617965722d306531356430353200");
        Exception ex = assertThrows(Exception.class, () -> decode(broken));
        assertTrue(ex.toString().contains("1922 elements exceeded max size of: 16"));
        UUID uuid = UUID.fromString("0e15d052-0f7b-388a-835c-ffbbeb798c82");
        byte[] fixed = new SecondaryPlayerSynthesizer(new IdRemapper()).synthesizePlayerInfoUpdate(new GameProfile(uuid, "Player-0e15d052"));
        byte[] expected = HexFormat.of().parseHex("4601010e15d0520f7b388a835cffbbeb798c820f506c617965722d306531356430353200");
        // Packet IDs differ between supported Minecraft protocols. The captured
        // regression fixes the body; validate the current ID independently.
        ByteBuf actualBody = Unpooled.wrappedBuffer(fixed);
        try {
            assertEquals(GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE), VarInts.readVarInt(actualBody));
            byte[] body = new byte[actualBody.readableBytes()];
            actualBody.readBytes(body);
            assertArrayEquals(Arrays.copyOfRange(expected, 1, expected.length), body);
        } finally { actualBody.release(); }
        assertEquals(uuid, decode(fixed).entries().getFirst().profileId());
    }
    @Test void realCreatePlayersRoundTripThroughSynthesizedPiu() throws Exception {
        String root = System.getenv("MULTIVIEW_PROTOTYPE_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null);
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var synth = new SecondaryPlayerSynthesizer(new IdRemapper());
        for (String name : List.of("PovAlpha", "PovBeta")) {
            try (FileSystem fs = FileSystems.newFileSystem(Path.of(root).resolve("MULTIPOV-" + name + ".zip"));
                 Stream<PacketEntry> stream = FlashbackReader.stream(FlashbackReader.open(fs.getPath("/")))) {
                var cp = stream.map(PacketEntry::action).filter(Action.CreatePlayer.class::isInstance)
                        .map(Action.CreatePlayer.class::cast).findFirst().orElseThrow();
                var profile = SecondaryPlayerSynthesizer.extractGameProfile(cp.bytes());
                UUID expectedId = UUID.fromString(name.equals("PovAlpha")
                        ? "8677ed65-16a7-38af-8ac2-b9525ca6a734" : "0e15d052-0f7b-388a-835c-ffbbeb798c82");
                assertEquals(name, profile.name());
                assertEquals(expectedId, profile.id());
                assertEquals(expectedId, SecondaryPlayerSynthesizer.extractUuid(cp.bytes()));
                assertTrue(profile.properties().isEmpty(), "captured offline profiles contain no properties");
                var decoded = decode(assertNotNullBytes(synth.synthesizePlayerInfoUpdate(profile))).entries().getFirst().profile();
                assertEquals(profile.id(), decoded.id());
                assertEquals(profile.name(), decoded.name());
                assertEquals(profile.properties(), decoded.properties());
            }
        }
    }
    @Test void inspectCapturedBrokenReplay() throws Exception {
        String root = System.getenv("MULTIVIEW_PROTOTYPE_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null);
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Path captured = Path.of(root).resolve("../../../multipov-local/player-info-before-fix.zip").normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(captured));
        int id = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE);
        try (FileSystem fs = FileSystems.newFileSystem(captured); Stream<PacketEntry> stream = FlashbackReader.stream(FlashbackReader.open(fs.getPath("/")))) {
            for (Iterator<PacketEntry> it = stream.iterator(); it.hasNext();) {
                PacketEntry e = it.next();
                if (!(e.action() instanceof Action.GamePacket gp)) continue;
                ByteBuf raw = Unpooled.wrappedBuffer(gp.bytes());
                if (VarInts.readVarInt(raw) != id) continue;
                try { decode(gp.bytes()); } catch (Throwable t) {
                    System.out.println("BROKEN PIU tick="+e.tick()+" snapshot="+e.inSnapshot()+" bytes="+HexFormat.of().formatHex(gp.bytes())+" error="+t);
                }
            }
        }
    }
    static int validateReplay(FlashbackReplay replay) throws Exception {
        int id = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE);
        int count = 0;
        try (Stream<PacketEntry> stream = FlashbackReader.stream(replay)) {
            for (Iterator<PacketEntry> it = stream.iterator(); it.hasNext();) {
                PacketEntry e = it.next();
                if (!(e.action() instanceof Action.GamePacket gp)) continue;
                ByteBuf raw = Unpooled.wrappedBuffer(gp.bytes());
                if (VarInts.readVarInt(raw) != id) continue;
                try { decode(gp.bytes()); } catch (Throwable t) { fail("tick="+e.tick()+" bytes="+HexFormat.of().formatHex(gp.bytes())+" error="+t); }
                if (HexFormat.of().formatHex(gp.bytes()).contains("0e15d0520f7b388a835cffbbeb798c82"))
                    System.out.println("MERGED PIU tick="+e.tick()+" bytes="+HexFormat.of().formatHex(gp.bytes()));
                count++;
            }
        }
        return count;
    }
    @Test void actualSourceAndMergedPacketsDecode() throws Exception {
        String root = System.getenv("MULTIVIEW_PROTOTYPE_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null);
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        int id = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE);
        for (String name : List.of("MULTIPOV-PovAlpha.zip", "MULTIPOV-PovBeta.zip")) {
            int count = 0;
            try (FileSystem fs = FileSystems.newFileSystem(Path.of(root, name)); Stream<PacketEntry> stream = FlashbackReader.stream(FlashbackReader.open(fs.getPath("/")))) {
                for (Iterator<PacketEntry> it = stream.iterator(); it.hasNext();) {
                    PacketEntry e = it.next();
                    if (!(e.action() instanceof Action.GamePacket gp)) continue;
                    ByteBuf raw = Unpooled.wrappedBuffer(gp.bytes());
                    if (VarInts.readVarInt(raw) != id) continue;
                    try { decode(gp.bytes()); } catch (Throwable t) { fail(name+" tick="+e.tick()+" snapshot="+e.inSnapshot()+" bytes="+HexFormat.of().formatHex(gp.bytes())+" error="+t); }
                    System.out.println("SOURCE PIU " + name + " tick=" + e.tick() + " bytes=" + HexFormat.of().formatHex(gp.bytes()));
                    count++;
                }
            }
            System.out.println("PIU codec validated " + name + ": " + count);
            assertTrue(count > 0, name);
        }
    }
}
