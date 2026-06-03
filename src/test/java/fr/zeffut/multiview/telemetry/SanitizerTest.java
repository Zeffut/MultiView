package fr.zeffut.multiview.telemetry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SanitizerTest {
    @Test
    void basenameStripsFullPath() {
        assertEquals("merged_2026.zip",
                Sanitizer.basename("/Users/alice/.minecraft/replay/merged_2026.zip"));
        assertEquals("merged_2026.zip",
                Sanitizer.basename("C:\\Users\\alice\\replay\\merged_2026.zip"));
        assertEquals("", Sanitizer.basename(null));
    }

    @Test
    void stackTraceIsTrimmedAndPathsStripped() {
        Exception e = new IllegalStateException("boom at /Users/alice/.minecraft/x");
        String s = Sanitizer.stackSummary(e, 5);
        assertFalse(s.contains("/Users/alice"), "absolute path leaked: " + s);
        assertTrue(s.contains("IllegalStateException"));
        // at most 5 frames + header → bounded length
        assertTrue(s.lines().count() <= 6, "stack not trimmed: " + s);
    }

    @Test
    void redactMessageRemovesUserHomePaths() {
        assertEquals("boom at <path>",
                Sanitizer.redactMessage("boom at /Users/alice/.minecraft/x"));
        assertEquals("boom at <path>",
                Sanitizer.redactMessage("boom at C:\\Users\\alice\\AppData\\x"));
        assertEquals("no path here", Sanitizer.redactMessage("no path here"));
    }
}
