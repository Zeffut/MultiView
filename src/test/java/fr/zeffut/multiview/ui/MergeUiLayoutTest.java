package fr.zeffut.multiview.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic checks for the two merge-UI graphical bugs:
 *  1. the merge button overlapping Flashback's control row / running off-screen, and
 *  2. checkboxes bleeding over the list's top/bottom overlays.
 *
 * These assert the invariants across a wide range of window sizes and scroll positions; the
 * pre-fix code (button at y=4 height 20 → bottom 24 > 22; row-based checkbox cull) fails them.
 */
class MergeUiLayoutTest {

    // Representative GUI-scaled widths from tiny (high GUI scale, small window) to 4K.
    private static final int[] WIDTHS = {200, 256, 320, 360, 427, 480, 640, 854, 1024, 1280, 1920, 2560, 3840};

    @Test
    void buttonNeverOverlapsFlashbackControlRow() {
        for (int w : WIDTHS) {
            int[] b = MergeUiLayout.mergeButtonBounds(w);
            int bottom = b[1] + b[3];
            assertTrue(bottom <= MergeUiLayout.CONTROL_ROW_TOP,
                    "width=" + w + ": button bottom " + bottom + " must stay ≤ control row top "
                            + MergeUiLayout.CONTROL_ROW_TOP);
            assertTrue(b[1] >= 0, "width=" + w + ": button y must be on-screen");
        }
    }

    @Test
    void buttonStaysOnScreen() {
        for (int w : WIDTHS) {
            int[] b = MergeUiLayout.mergeButtonBounds(w);
            int x = b[0], width = b[2];
            assertTrue(x >= 0, "width=" + w + ": button x " + x + " off the left edge");
            assertTrue(x + width <= w, "width=" + w + ": button right " + (x + width) + " past screen " + w);
        }
    }

    @Test
    void buttonKeptInRightHalfClearOfCentredTitle() {
        // For any realistic width the button's left edge stays right of centre, so it can't
        // collide with the centred screen title.
        for (int w : WIDTHS) {
            if (w < 152) continue; // below this the min-width floor wins; not a real window size
            int[] b = MergeUiLayout.mergeButtonBounds(w);
            assertTrue(b[0] >= w / 2,
                    "width=" + w + ": button left " + b[0] + " crosses centre " + (w / 2));
        }
    }

    @Test
    void buttonWidthIsResponsiveAndClamped() {
        for (int w : WIDTHS) {
            int width = MergeUiLayout.mergeButtonBounds(w)[2];
            assertTrue(width >= MergeUiLayout.BUTTON_MIN_W && width <= MergeUiLayout.BUTTON_MAX_W,
                    "width=" + w + ": button width " + width + " out of ["
                            + MergeUiLayout.BUTTON_MIN_W + ", " + MergeUiLayout.BUTTON_MAX_W + "]");
        }
        // Wide window → full width; narrow window → shrunk.
        assertEquals(MergeUiLayout.BUTTON_MAX_W, MergeUiLayout.mergeButtonBounds(3840)[2]);
        assertTrue(MergeUiLayout.mergeButtonBounds(340)[2] < MergeUiLayout.BUTTON_MAX_W,
                "narrow window should shrink the button");
    }

    @Test
    void checkboxVisibleOnlyWhenFullyInsideViewport() {
        int top = 40, bottom = 200, size = 10;
        // Fully inside
        assertTrue(MergeUiLayout.checkboxVisible(100, size, top, bottom));
        // Flush against the top / bottom edges (still fully inside)
        assertTrue(MergeUiLayout.checkboxVisible(top, size, top, bottom));
        assertTrue(MergeUiLayout.checkboxVisible(bottom - size, size, top, bottom));
        // One pixel past the top → hidden (the old row-based cull wrongly drew these)
        assertFalse(MergeUiLayout.checkboxVisible(top - 1, size, top, bottom));
        // One pixel past the bottom → hidden
        assertFalse(MergeUiLayout.checkboxVisible(bottom - size + 1, size, top, bottom));
        // Far above / below
        assertFalse(MergeUiLayout.checkboxVisible(0, size, top, bottom));
        assertFalse(MergeUiLayout.checkboxVisible(bottom + 50, size, top, bottom));
    }
}
