package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.Action;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketClassifierTest {

    @Test
    void nextTickIsTick() {
        PacketClassifier c = new PacketClassifier(pid -> Category.PASSTHROUGH);
        assertEquals(Category.TICK, c.classify(new Action.NextTick()));
    }

    @Test
    void configurationPacketIsConfig() {
        PacketClassifier c = new PacketClassifier(pid -> Category.PASSTHROUGH);
        assertEquals(Category.CONFIG, c.classify(new Action.ConfigurationPacket(new byte[]{0})));
    }

    @Test
    void createPlayerIsLocalPlayer() {
        PacketClassifier c = new PacketClassifier(pid -> Category.PASSTHROUGH);
        assertEquals(Category.LOCAL_PLAYER, c.classify(new Action.CreatePlayer(new byte[]{0})));
    }

    @Test
    void moveEntitiesIsEntity() {
        PacketClassifier c = new PacketClassifier(pid -> Category.PASSTHROUGH);
        assertEquals(Category.ENTITY, c.classify(new Action.MoveEntities(new byte[]{0})));
    }

    @Test
    void cacheChunkRefIsCacheRef() {
        PacketClassifier c = new PacketClassifier(pid -> Category.PASSTHROUGH);
        assertEquals(Category.CACHE_REF, c.classify(new Action.CacheChunkRef(42)));
    }

    @Test
    void voiceChatIsEgo() {
        PacketClassifier c = new PacketClassifier(pid -> Category.PASSTHROUGH);
        assertEquals(Category.EGO, c.classify(new Action.VoiceChat(new byte[]{0})));
        assertEquals(Category.EGO, c.classify(new Action.EncodedVoiceChat(new byte[]{0})));
    }

    @Test
    void unknownIsPassthrough() {
        PacketClassifier c = new PacketClassifier(pid -> Category.PASSTHROUGH);
        assertEquals(Category.PASSTHROUGH, c.classify(new Action.Unknown("custom:x", new byte[]{0})));
    }

    @Test
    void gamePacketDelegatesToTable() {
        PacketClassifier c = new PacketClassifier(pid -> pid == 42 ? Category.WORLD : Category.PASSTHROUGH);
        byte[] payload = new byte[]{42, 0, 0, 0};
        assertEquals(Category.WORLD, c.classify(new Action.GamePacket(payload)));
    }
}
