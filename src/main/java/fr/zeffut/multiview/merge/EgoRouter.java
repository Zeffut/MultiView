package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.ActionType;
import fr.zeffut.multiview.format.SegmentWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Dispatch des packets égocentriques vers un segment par joueur, écrit dans
 * ego/&lt;playerUUID&gt;.flashback. Format = segment standard Flashback.
 *
 * <p>La registry passée au constructeur doit contenir les identifiants d'actions
 * nécessaires. Les ordinals sont dérivés de l'index dans cette liste, conformément
 * à l'API de {@link SegmentWriter#writeLiveAction(int, byte[])}.
 *
 * @implNote Non thread-safe. Appelé depuis le pipeline single-threaded.
 */
public final class EgoRouter {

    private final Path egoDir;
    private final List<String> registry;
    private final int nextTickOrdinal;
    private final int gamePacketOrdinal;
    private final Map<UUID, SegmentWriter> writers = new HashMap<>();
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
        // Fall back to ordinal 0 when the standard GAME_PACKET id is absent:
        // callers may use a custom string id placed first in the registry.
        int gpIdx = registry.indexOf(ActionType.GAME_PACKET);
        this.gamePacketOrdinal = gpIdx >= 0 ? gpIdx : 0;
        // NEXT_TICK is optional: if absent, we skip inter-tick padding
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
        SegmentWriter writer = getOrCreateWriter(playerUuid);

        // Intercaler les NEXT_TICK manquants si l'action est connue de la registry
        if (nextTickOrdinal >= 0) {
            int prevTick = lastTick.getOrDefault(playerUuid, 0);
            for (int t = prevTick; t < tickAbs; t++) {
                writer.writeLiveAction(nextTickOrdinal, new byte[0]);
            }
        }

        writer.writeLiveAction(gamePacketOrdinal, gamePacketPayload);
        lastTick.put(playerUuid, tickAbs);
    }

    /**
     * Finalise tous les segments ouverts et les écrit dans {@code egoDir}.
     * Chaque fichier est nommé {@code <playerUUID>.flashback}.
     */
    public void finishAll() throws IOException {
        for (Map.Entry<UUID, SegmentWriter> e : writers.entrySet()) {
            ByteBuf buf = e.getValue().finish();
            try {
                byte[] bytes = ByteBufUtil.getBytes(buf);
                Files.write(egoDir.resolve(e.getKey() + ".flashback"), bytes);
            } finally {
                buf.release();
            }
        }
    }

    /**
     * Retourne l'ensemble des UUIDs pour lesquels un segment a été ouvert.
     * Valide après {@link #finishAll()}.
     */
    public Set<UUID> egoPlayers() {
        return Collections.unmodifiableSet(writers.keySet());
    }

    // --- private ---

    private SegmentWriter getOrCreateWriter(UUID playerUuid) {
        SegmentWriter existing = writers.get(playerUuid);
        if (existing != null) {
            return existing;
        }
        SegmentWriter w = new SegmentWriter(playerUuid + ".flashback", registry);
        // Phase 3 ego: pas de snapshot — on passe directement en live stream
        w.endSnapshot();
        writers.put(playerUuid, w);
        return w;
    }
}
