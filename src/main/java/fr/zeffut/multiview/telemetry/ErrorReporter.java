package fr.zeffut.multiview.telemetry;

import java.util.Map;

/** Reports a handled MultiView error to telemetry, fully sanitized. */
public final class ErrorReporter {
    private ErrorReporter() {}

    public static void report(String where, Throwable t) {
        if (t == null) return;
        Telemetry.capture(EventNames.EVT_MOD_ERROR, Map.of(
                "where", where,
                "error_type", t.getClass().getName(),
                "error_message", Sanitizer.redactMessage(String.valueOf(t.getMessage())),
                "stack", Sanitizer.stackSummary(t, 8)));
    }
}
