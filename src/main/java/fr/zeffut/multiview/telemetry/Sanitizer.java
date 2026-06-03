package fr.zeffut.multiview.telemetry;

import java.util.regex.Pattern;

/**
 * Central PII redaction. Telemetry NEVER sends player names/UUIDs, world/server names,
 * full file paths, or replay contents. These helpers enforce that at the boundary.
 */
public final class Sanitizer {
    private Sanitizer() {}

    // Matches unix (/Users/.., /home/..) and windows (C:\Users\..) absolute paths.
    private static final Pattern PATH = Pattern.compile(
            "(?:[A-Za-z]:\\\\|/)(?:[^\\s\"']*[\\\\/])?[^\\s\"']*");

    /** Last path segment of a unix or windows path; "" for null. */
    public static String basename(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /** Replace any absolute path in a free-text message with "<path>". */
    public static String redactMessage(String message) {
        if (message == null) return "";
        return PATH.matcher(message).replaceAll("<path>");
    }

    /**
     * Compact, path-redacted exception summary: "ExceptionType: redactedMessage"
     * plus up to {@code maxFrames} stack frames (class.method only, no file paths).
     */
    public static String stackSummary(Throwable t, int maxFrames) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(redactMessage(t.getMessage()));
        StackTraceElement[] frames = t.getStackTrace();
        int n = Math.min(maxFrames, frames.length);
        for (int i = 0; i < n; i++) {
            StackTraceElement f = frames[i];
            sb.append('\n').append(f.getClassName()).append('.').append(f.getMethodName());
        }
        return sb.toString();
    }
}
