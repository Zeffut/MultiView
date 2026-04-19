package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.Action;
import fr.zeffut.multiview.format.FlashbackReader;
import fr.zeffut.multiview.format.FlashbackReplay;
import fr.zeffut.multiview.format.PacketEntry;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class TimelineAligner {

    private static final Pattern TS_PATTERN = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2})[:_](\\d{2})[:_](\\d{2})");

    /**
     * Extrait un timestamp du format "YYYY-MM-DDTHH:mm:ss" (ou avec _ au lieu de :)
     * dans `name`, retourne l'epoch × 20 (ticks), ou -1 si introuvable.
     */
    public record SetTimeAnchor(int tickLocal, long gameTime) {}

    /**
     * Cherche le 1er ClientboundSetTimePacket dans les 1200 premiers ticks locaux.
     * Retourne (tickLocal, gameTime) ou empty si non trouvé.
     */
    public static Optional<SetTimeAnchor> findSetTimeAnchor(
            FlashbackReplay replay, PacketIdProvider idProvider) {
        int targetId = idProvider.setTimePacketId();
        try (Stream<PacketEntry> stream = FlashbackReader.stream(replay)) {
            return stream
                    .filter(e -> e.tick() <= 1200)
                    .filter(e -> e.action() instanceof Action.GamePacket)
                    .map(e -> {
                        byte[] payload = ((Action.GamePacket) e.action()).bytes();
                        int pid = readVarIntHead(payload);
                        if (pid != targetId) return null;
                        long gameTime = readGameTime(payload);
                        return new SetTimeAnchor(e.tick(), gameTime);
                    })
                    .filter(a -> a != null)
                    .findFirst();
        }
    }

    /** Package-private for tests. Reads VarInt at payload offset 0. */
    static int readVarIntHead(byte[] payload) {
        int value = 0, shift = 0;
        for (int i = 0; i < 5 && i < payload.length; i++) {
            byte b = payload[i];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
        }
        throw new IllegalArgumentException("VarInt trop long ou payload vide");
    }

    /** Reads the long gameTime in a ClientboundSetTime after the VarInt packetId. */
    static long readGameTime(byte[] payload) {
        int offset = varIntLength(payload);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (payload[offset + i] & 0xFF);
        }
        return v;
    }

    static int varIntLength(byte[] payload) {
        for (int i = 0; i < 5; i++) {
            if ((payload[i] & 0x80) == 0) return i + 1;
        }
        return 5;
    }

    public record Source(String label, Optional<SetTimeAnchor> anchor, String metadataName, int totalTicks) {}

    public record AlignmentResult(int[] tickOffsets, int mergedStartTick, int mergedTotalTicks, String strategy) {}

    public static AlignmentResult alignAll(List<Source> sources, Map<String, Integer> cliOverrides) {
        int n = sources.size();
        long[] absoluteOffsets = new long[n];
        String strategy;

        boolean allHaveAnchors = sources.stream().allMatch(s -> s.anchor().isPresent());
        if (allHaveAnchors) {
            for (int i = 0; i < n; i++) {
                SetTimeAnchor a = sources.get(i).anchor().get();
                absoluteOffsets[i] = a.gameTime() - a.tickLocal();
            }
            strategy = "setTimePacket";
        } else {
            for (int i = 0; i < n; i++) {
                long ticks = parseMetadataNameToTicks(sources.get(i).metadataName());
                if (ticks < 0) {
                    throw new IllegalArgumentException(
                            "Source '" + sources.get(i).label() + "' : pas de SetTime ET metadata.name non parseable. "
                          + "Fournir --offset-" + sources.get(i).label() + "=<N>");
                }
                absoluteOffsets[i] = ticks;
            }
            strategy = "metadataName";
        }

        // Overrides CLI appliqués au-dessus
        for (int i = 0; i < n; i++) {
            Integer over = cliOverrides.get(sources.get(i).label());
            if (over != null) {
                absoluteOffsets[i] += over;
                strategy = "cliOverride";
            }
        }

        // Normalisation : le plus petit devient 0
        long min = Long.MAX_VALUE;
        for (long o : absoluteOffsets) min = Math.min(min, o);
        int[] tickOffsets = new int[n];
        long max = 0;
        for (int i = 0; i < n; i++) {
            tickOffsets[i] = (int) (absoluteOffsets[i] - min);
            long end = tickOffsets[i] + sources.get(i).totalTicks();
            if (end > max) max = end;
        }
        return new AlignmentResult(tickOffsets, 0, (int) max, strategy);
    }

    public static long parseMetadataNameToTicks(String name) {
        Matcher m = TS_PATTERN.matcher(name);
        if (!m.find()) return -1L;
        LocalDateTime dt = LocalDateTime.of(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)),
                Integer.parseInt(m.group(5)),
                Integer.parseInt(m.group(6)));
        return dt.toEpochSecond(ZoneOffset.UTC) * 20L;
    }
}
