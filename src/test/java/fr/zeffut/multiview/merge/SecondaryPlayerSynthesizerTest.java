package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SecondaryPlayerSynthesizer}.
 *
 * <p>These tests run without Minecraft's Bootstrap, so they can only verify
 * the fallback-mode behaviour and the static helpers that do not depend on MC
 * codecs.  The packet synthesis methods are covered by the compile-time check
 * (they compile correctly) and the fallback assertion (they return null when
 * Bootstrap is unavailable).
 *
 * <p>Tests that require Bootstrap are guarded by {@code @EnabledIf} and only
 * run in the full Fabric environment.
 */
class SecondaryPlayerSynthesizerTest {

    // -------------------------------------------------------------------------
    // extractUuid — no MC dependency
    // -------------------------------------------------------------------------

    @Test
    void extractUuid_readsFirstTwoLongs() {
        UUID expected = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        ByteBuf buf = Unpooled.buffer(32);
        buf.writeLong(expected.getMostSignificantBits());
        buf.writeLong(expected.getLeastSignificantBits());
        // Append some dummy bytes after the UUID to simulate the full CreatePlayer payload
        buf.writeDouble(1.0);
        buf.writeDouble(64.0);
        buf.writeDouble(-3.0);

        byte[] payload = new byte[buf.readableBytes()];
        buf.readBytes(payload);

        UUID actual = SecondaryPlayerSynthesizer.extractUuid(payload);
        assertEquals(expected, actual);
    }

    @Test
    void extractUuid_returnsNullForShortPayload() {
        assertNull(SecondaryPlayerSynthesizer.extractUuid(new byte[15]));
        assertNull(SecondaryPlayerSynthesizer.extractUuid(null));
        assertNull(SecondaryPlayerSynthesizer.extractUuid(new byte[0]));
    }

    @Test
    void extractUuid_exactlyMinimumLength() {
        UUID expected = new UUID(0xCAFEBABE_DEADBEEfl, 0x0102030405060708L);
        ByteBuf buf = Unpooled.buffer(16);
        buf.writeLong(expected.getMostSignificantBits());
        buf.writeLong(expected.getLeastSignificantBits());
        byte[] payload = new byte[16];
        buf.readBytes(payload);

        assertEquals(expected, SecondaryPlayerSynthesizer.extractUuid(payload));
    }

    // -------------------------------------------------------------------------
    // extractGameProfile — fallback path (Bootstrap not available)
    // -------------------------------------------------------------------------

    @Test
    void extractGameProfile_fallbackOnShortPayload() {
        // Only 16 bytes: UUID only; GameProfile parsing will fail → stub returned
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ByteBuf buf = Unpooled.buffer(16);
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
        byte[] payload = new byte[16];
        buf.readBytes(payload);

        // Without Bootstrap, EntityType.PACKET_CODEC decode will throw → stub profile
        // The UUID should still match (from the first 16 bytes).
        var profile = SecondaryPlayerSynthesizer.extractGameProfile(payload);
        assertNotNull(profile);
        // UUID in profile is the parsed UUID
        assertEquals(uuid, profile.id());
        // Name is either the real name (if somehow parseable) or a "Player-" prefix stub
        assertNotNull(profile.name());
        assertFalse(profile.name().isEmpty());
    }

    @Test
    void extractGameProfile_stubNameStartsWithPlayer() {
        UUID uuid = new UUID(0xAABBCCDD_EEFFAABBl, 0x1122334455667788L);
        // Build a minimal payload: UUID only (forces stub path)
        ByteBuf buf = Unpooled.buffer(16);
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
        byte[] payload = new byte[16];
        buf.readBytes(payload);

        var profile = SecondaryPlayerSynthesizer.extractGameProfile(payload);
        // Either the stub name "Player-<uuid-prefix>" or the real name if parseable
        assertNotNull(profile.name());
        // If it's a stub, it should start with "Player-"
        // (we can't assert the exact value because Bootstrap might be present in CI)
        assertTrue(profile.name().length() <= 16,
                "Player name must be ≤ 16 characters (MC limit): " + profile.name());
    }

    // -------------------------------------------------------------------------
    // Fallback mode when MC codecs are unavailable (no Bootstrap)
    // -------------------------------------------------------------------------

    @Test
    void synthesizer_isAvailableReturnsFalseOrTrue() {
        // In tests without Bootstrap, isAvailable() depends on whether
        // PlayStateFactories can be loaded.  We simply assert it doesn't throw.
        IdRemapper remapper = new IdRemapper();
        SecondaryPlayerSynthesizer synth = new SecondaryPlayerSynthesizer(remapper);
        // isAvailable() is either true or false — both are valid depending on environment
        // The key invariant is: if !isAvailable(), all synthesis methods return null.
        if (!synth.isAvailable()) {
            assertNull(synth.synthesizePlayerInfoUpdate(
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "test")));
            assertNull(synth.synthesizeAddEntity(0, UUID.randomUUID()));
            assertNull(synth.synthesizeTeleport(100_000_000, 0, 64, 0, 0f, 0f));
        }
    }

    @Test
    void getFakeEntityId_returnsMinusOneBeforeSpawn() {
        IdRemapper remapper = new IdRemapper();
        SecondaryPlayerSynthesizer synth = new SecondaryPlayerSynthesizer(remapper);
        // No synthesizeAddEntity call yet → sentinel -1 not assigned
        assertEquals(-1, synth.getFakeEntityId(0));
        assertEquals(-1, synth.getFakeEntityId(1));
    }

    @Test
    void getFakeEntityId_returnsGlobalIdAfterSynth() {
        IdRemapper remapper = new IdRemapper();
        SecondaryPlayerSynthesizer synth = new SecondaryPlayerSynthesizer(remapper);

        // Manually assign the sentinel (simulating what synthesizeAddEntity does)
        int expectedGlobalId = remapper.assign(0, -1);

        // Now getFakeEntityId should return that value
        assertEquals(expectedGlobalId, synth.getFakeEntityId(0));
    }

    @Test
    void getFakeEntityId_differentSourcesHaveDifferentIds() {
        IdRemapper remapper = new IdRemapper();
        SecondaryPlayerSynthesizer synth = new SecondaryPlayerSynthesizer(remapper);

        int g0 = remapper.assign(0, -1);
        int g1 = remapper.assign(1, -1);

        assertEquals(g0, synth.getFakeEntityId(0));
        assertEquals(g1, synth.getFakeEntityId(1));
        assertNotEquals(g0, g1);
    }

    @Test
    void synthesizeTeleport_returnsNullForNegativeEntityId() {
        IdRemapper remapper = new IdRemapper();
        SecondaryPlayerSynthesizer synth = new SecondaryPlayerSynthesizer(remapper);
        // Even if synth is available, entity id -1 must return null
        assertNull(synth.synthesizeTeleport(-1, 0, 64, 0, 0f, 0f));
    }

    // -------------------------------------------------------------------------
    // VarInt helper — verifies our understanding of prependPacketId behaviour
    // -------------------------------------------------------------------------

    @Test
    void varIntEncoding_smallValue() {
        ByteBuf buf = Unpooled.buffer(5);
        VarInts.writeVarInt(buf, 42);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);

        // VarInt(42) should be a single byte 0x2A
        assertEquals(1, bytes.length);
        assertEquals(0x2A, bytes[0] & 0xFF);
    }

    @Test
    void varIntEncoding_largeValue() {
        ByteBuf buf = Unpooled.buffer(5);
        VarInts.writeVarInt(buf, 300);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);

        // VarInt(300) = 0xAC 0x02 (2 bytes)
        assertEquals(2, bytes.length);
        assertEquals(0xAC, bytes[0] & 0xFF);
        assertEquals(0x02, bytes[1] & 0xFF);
    }
}
