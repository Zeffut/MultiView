package fr.zeffut.multiview.merge;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimelineAligner {

    private static final Pattern TS_PATTERN = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2})[:_](\\d{2})[:_](\\d{2})");

    /**
     * Extrait un timestamp du format "YYYY-MM-DDTHH:mm:ss" (ou avec _ au lieu de :)
     * dans `name`, retourne l'epoch × 20 (ticks), ou -1 si introuvable.
     */
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
