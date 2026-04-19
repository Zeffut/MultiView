package fr.zeffut.multiview.format;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Registry id → decoder. Le segment porte une table {@code ordinal -> id} dans
 * son header ; cette classe dit comment transformer un payload brut en {@link Action}
 * typée une fois l'id résolu.
 */
public final class ActionType {
    public static final String NEXT_TICK          = "flashback:action/next_tick";
    public static final String CONFIGURATION      = "flashback:action/configuration_packet";
    public static final String GAME_PACKET        = "flashback:action/game_packet";
    public static final String CREATE_PLAYER      = "flashback:action/create_local_player";
    public static final String MOVE_ENTITIES      = "flashback:action/move_entities";
    public static final String CACHE_CHUNK        = "flashback:action/level_chunk_cached";
    public static final String VOICE_CHAT         = "flashback:action/simple_voice_chat_sound_optional";
    public static final String ENCODED_VOICE_CHAT = "arcade_replay:action/encoded_simple_voice_chat_sound_optional";

    private static final Map<String, Function<byte[], Action>> CODECS = new ConcurrentHashMap<>();

    static {
        register(NEXT_TICK,          bytes -> new Action.NextTick());
        register(CONFIGURATION,      Action.ConfigurationPacket::new);
        register(GAME_PACKET,        Action.GamePacket::new);
        register(CREATE_PLAYER,      Action.CreatePlayer::new);
        register(MOVE_ENTITIES,      Action.MoveEntities::new);
        register(CACHE_CHUNK,        Action::decodeCacheChunkRef);
        register(VOICE_CHAT,         Action.VoiceChat::new);
        register(ENCODED_VOICE_CHAT, Action.EncodedVoiceChat::new);
    }

    private ActionType() {}

    public static void register(String id, Function<byte[], Action> codec) {
        CODECS.put(id, codec);
    }

    public static Action decode(String id, byte[] payload) {
        Function<byte[], Action> codec = CODECS.get(id);
        if (codec == null) {
            return new Action.Unknown(id, payload);
        }
        return codec.apply(payload);
    }

    public static boolean isKnown(String id) {
        return CODECS.containsKey(id);
    }
}
