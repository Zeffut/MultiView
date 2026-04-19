package fr.zeffut.multiview.format;

import io.netty.buffer.Unpooled;
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

    /** Inverse de decode : retourne l'id namespaced pour un Action typé. */
    public static String idOf(Action action) {
        return switch (action) {
            case Action.NextTick nt           -> NEXT_TICK;
            case Action.ConfigurationPacket c -> CONFIGURATION;
            case Action.GamePacket g          -> GAME_PACKET;
            case Action.CreatePlayer p        -> CREATE_PLAYER;
            case Action.MoveEntities m        -> MOVE_ENTITIES;
            case Action.CacheChunkRef r       -> CACHE_CHUNK;
            case Action.VoiceChat v           -> VOICE_CHAT;
            case Action.EncodedVoiceChat e    -> ENCODED_VOICE_CHAT;
            case Action.Unknown u             -> u.id();
        };
    }

    /** Inverse de decode : encode l'Action en bytes pour écriture en segment. */
    public static byte[] encode(Action action) {
        return switch (action) {
            case Action.NextTick nt           -> new byte[0];
            case Action.ConfigurationPacket c -> c.bytes();
            case Action.GamePacket g          -> g.bytes();
            case Action.CreatePlayer p        -> p.bytes();
            case Action.MoveEntities m        -> m.bytes();
            case Action.CacheChunkRef r       -> encodeCacheChunkRef(r);
            case Action.VoiceChat v           -> v.bytes();
            case Action.EncodedVoiceChat e    -> e.bytes();
            case Action.Unknown u             -> u.payload();
        };
    }

    private static byte[] encodeCacheChunkRef(Action.CacheChunkRef r) {
        io.netty.buffer.ByteBuf tmp = Unpooled.buffer();
        VarInts.writeVarInt(tmp, r.cacheIndex());
        byte[] out = new byte[tmp.readableBytes()];
        tmp.readBytes(out);
        return out;
    }
}
