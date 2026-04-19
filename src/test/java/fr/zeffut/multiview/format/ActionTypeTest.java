package fr.zeffut.multiview.format;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ActionTypeTest {

    @Test
    void idOfEachKnownVariant() {
        assertEquals(ActionType.NEXT_TICK,          ActionType.idOf(new Action.NextTick()));
        assertEquals(ActionType.CONFIGURATION,      ActionType.idOf(new Action.ConfigurationPacket(new byte[0])));
        assertEquals(ActionType.GAME_PACKET,        ActionType.idOf(new Action.GamePacket(new byte[0])));
        assertEquals(ActionType.CREATE_PLAYER,      ActionType.idOf(new Action.CreatePlayer(new byte[0])));
        assertEquals(ActionType.MOVE_ENTITIES,      ActionType.idOf(new Action.MoveEntities(new byte[0])));
        assertEquals(ActionType.CACHE_CHUNK,        ActionType.idOf(new Action.CacheChunkRef(42)));
        assertEquals(ActionType.VOICE_CHAT,         ActionType.idOf(new Action.VoiceChat(new byte[0])));
        assertEquals(ActionType.ENCODED_VOICE_CHAT, ActionType.idOf(new Action.EncodedVoiceChat(new byte[0])));
    }

    @Test
    void idOfUnknownReturnsItsOwnId() {
        Action.Unknown u = new Action.Unknown("custom:mod/foo", new byte[] { 0x01 });
        assertEquals("custom:mod/foo", ActionType.idOf(u));
    }

    @Test
    void encodeNextTickIsEmpty() {
        assertEquals(0, ActionType.encode(new Action.NextTick()).length);
    }

    @Test
    void encodeOpaqueVariantsPreserveBytes() {
        byte[] payload = new byte[] { 0x01, 0x02, 0x03, 0x04 };
        assertArrayEquals(payload, ActionType.encode(new Action.GamePacket(payload)));
        assertArrayEquals(payload, ActionType.encode(new Action.ConfigurationPacket(payload)));
        assertArrayEquals(payload, ActionType.encode(new Action.CreatePlayer(payload)));
        assertArrayEquals(payload, ActionType.encode(new Action.MoveEntities(payload)));
        assertArrayEquals(payload, ActionType.encode(new Action.VoiceChat(payload)));
        assertArrayEquals(payload, ActionType.encode(new Action.EncodedVoiceChat(payload)));
        assertArrayEquals(payload, ActionType.encode(new Action.Unknown("x:y", payload)));
    }

    @Test
    void encodeCacheChunkRefWritesVarInt() {
        byte[] encoded = ActionType.encode(new Action.CacheChunkRef(300));
        // 300 en VarInt = 0xAC 0x02
        assertArrayEquals(new byte[] { (byte) 0xAC, 0x02 }, encoded);
    }

    @Test
    void encodeDecodeRoundTripForCacheChunkRef() {
        Action.CacheChunkRef original = new Action.CacheChunkRef(12345);
        byte[] bytes = ActionType.encode(original);
        Action decoded = ActionType.decode(ActionType.CACHE_CHUNK, bytes);
        Action.CacheChunkRef r = assertInstanceOf(Action.CacheChunkRef.class, decoded);
        assertEquals(12345, r.cacheIndex());
    }
}
