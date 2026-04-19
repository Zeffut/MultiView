package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.ActionType;
import fr.zeffut.multiview.format.SegmentReader;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Dispatch des packets égocentriques vers un segment par joueur, écrit dans
 * ego/&lt;playerUUID&gt;.flashback. Format = segment standard Flashback.
 *
 * <p>Phase 3 : les actions sont streamées directement vers un FileOutputStream
 * (header fixe + snapshotSize=0 écrits au départ, live stream appendé au fur et
 * à mesure). Évite d'accumuler l'intégralité du replay en RAM.
 *
 * @implNote Non thread-safe. Appelé depuis le pipeline single-threaded.
 */
public final class EgoRouter {

    private final Path egoDir;
    private final List<String> registry;
    private final int nextTickOrdinal;
    private final int gamePacketOrdinal;
    private final Map<UUID, OutputStream> streams = new HashMap<>();
    private final Map<UUID, Integer> lastTick = new HashMap<>();

    /**
     * @param egoDir   répertoire de destination (créé si inexistant)
     * @param registry liste des identifiants d'actions pour la registry du segment.
     *                 Doit contenir {@link ActionType#NEXT_TICK} et
     *                 {@link ActionType#GAME_PACKET}.
     * @throws IOException si la création du répertoire échoue
     */
    public EgoRouter(Path egoDir, List<String> registry) throws IOException {
        this.egoDir = egoDir;
        this.registry = List.copyOf(registry);
        Files.createDirectories(egoDir);

        this.nextTickOrdinal = registry.indexOf(ActionType.NEXT_TICK);
        int gpIdx = registry.indexOf(ActionType.GAME_PACKET);
        this.gamePacketOrdinal = gpIdx >= 0 ? gpIdx : 0;
    }

    /**
     * Écrit un GamePacket égocentrique pour {@code playerUuid} au tick absolu {@code tickAbs}.
     * Des actions NEXT_TICK sont intercalées pour combler l'écart depuis le dernier tick écrit
     * (si NEXT_TICK est présent dans la registry).
     *
     * @param playerUuid         UUID du joueur POV
     * @param tickAbs            numéro de tick absolu (0-based)
     * @param gamePacketPayload  payload brut du packet
     */
    public void writeEgo(UUID playerUuid, int tickAbs, byte[] gamePacketPayload) throws IOException {
        OutputStream out = getOrCreateStream(playerUuid);

        // Intercaler les NEXT_TICK manquants si l'action est connue de la registry
        if (nextTickOrdinal >= 0) {
            int prevTick = lastTick.getOrDefault(playerUuid, 0);
            byte[] emptyNextTick = encodeAction(nextTickOrdinal, new byte[0]);
            for (int t = prevTick; t < tickAbs; t++) {
                out.write(emptyNextTick);
            }
        }

        out.write(encodeAction(gamePacketOrdinal, gamePacketPayload));
        lastTick.put(playerUuid, tickAbs);
    }

    /**
     * Finalise tous les segments ouverts (flush + close).
     */
    public void finishAll() throws IOException {
        for (OutputStream out : streams.values()) {
            out.flush();
            out.close();
        }
    }

    /**
     * Retourne l'ensemble des UUIDs pour lesquels un segment a été ouvert.
     * Valide après {@link #finishAll()}.
     */
    public Set<UUID> egoPlayers() {
        return Collections.unmodifiableSet(streams.keySet());
    }

    // --- private ---

    private OutputStream getOrCreateStream(UUID playerUuid) throws IOException {
        OutputStream existing = streams.get(playerUuid);
        if (existing != null) {
            return existing;
        }
        Path file = egoDir.resolve(playerUuid + ".flashback");
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 256 * 1024);
        // Write header: magic(4) + registry + snapshotSize(4=0)
        writeHeader(out);
        streams.put(playerUuid, out);
        return out;
    }

    /**
     * Writes the segment header to {@code out}:
     * magic (4 bytes BE) + registry (VarInt count + entries) + snapshotSize (4 bytes BE = 0).
     */
    private void writeHeader(OutputStream out) throws IOException {
        // magic — big-endian int32
        writeInt32BE(out, SegmentReader.MAGIC);
        // registry count — VarInt
        writeVarInt(out, registry.size());
        for (String id : registry) {
            byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
            writeVarInt(out, idBytes.length);
            out.write(idBytes);
        }
        // snapshotSize = 0 — big-endian int32
        writeInt32BE(out, 0);
    }

    /**
     * Encodes one action: VarInt ordinal + int32 payloadSize + payload bytes.
     */
    private static byte[] encodeAction(int ordinal, byte[] payload) {
        // worst case VarInt for ordinal: 5 bytes; int32: 4 bytes; payload
        byte[] varIntBuf = encodeVarInt(ordinal);
        byte[] out = new byte[varIntBuf.length + 4 + payload.length];
        System.arraycopy(varIntBuf, 0, out, 0, varIntBuf.length);
        int pos = varIntBuf.length;
        // payload length — big-endian int32
        out[pos]     = (byte) (payload.length >>> 24);
        out[pos + 1] = (byte) (payload.length >>> 16);
        out[pos + 2] = (byte) (payload.length >>> 8);
        out[pos + 3] = (byte)  payload.length;
        System.arraycopy(payload, 0, out, pos + 4, payload.length);
        return out;
    }

    private static byte[] encodeVarInt(int value) {
        byte[] tmp = new byte[5];
        int i = 0;
        while ((value & ~0x7F) != 0) {
            tmp[i++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        tmp[i++] = (byte) value;
        return Arrays.copyOf(tmp, i);
    }

    private static void writeInt32BE(OutputStream out, int value) throws IOException {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>>  8) & 0xFF);
        out.write( value         & 0xFF);
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
