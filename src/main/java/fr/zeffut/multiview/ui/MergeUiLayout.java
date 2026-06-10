package fr.zeffut.multiview.ui;

/**
 * Pure layout math for the merge UI, deliberately free of any Minecraft rendering type so it can
 * be unit-tested without a game client. Both the {@code java-classic} and {@code java-modern}
 * {@code MergeUi} variants call into this, so the tested logic IS the rendered logic.
 */
public final class MergeUiLayout {
    private MergeUiLayout() {}

    /** Outer margin from the screen edge (px, GUI scale). */
    public static final int MARGIN = 4;
    /** Merge button height (px). */
    public static final int BUTTON_H = 20;
    /** Merge button max width (px). */
    public static final int BUTTON_MAX_W = 130;
    /** Merge button min width on narrow windows (px). */
    public static final int BUTTON_MIN_W = 72;
    /**
     * Top of Flashback's centred control row (search box + sort) in SelectReplayScreen
     * (confirmed from Flashback 0.39.4 bytecode). The merge button's bottom must stay at or
     * above this so it never overlaps that row at any window size.
     */
    public static final int CONTROL_ROW_TOP = 22;
    /** Horizontal clearance kept right of screen centre so the button never hits the centred title. */
    public static final int TITLE_HALF_CLEARANCE = 60;

    /**
     * Merge button bounds for a given GUI-scaled screen width: top-right corner, sitting above
     * Flashback's control row, width-responsive (shrinks on narrow windows) and clear of the
     * centred title.
     *
     * @return {@code [x, y, width, height]}
     */
    public static int[] mergeButtonBounds(int scaledWidth) {
        int y = Math.max(1, CONTROL_ROW_TOP - BUTTON_H);          // bottom = y + H ≤ CONTROL_ROW_TOP
        int avail = scaledWidth - MARGIN - (scaledWidth / 2 + TITLE_HALF_CLEARANCE);
        int w = Math.max(BUTTON_MIN_W, Math.min(BUTTON_MAX_W, avail));
        int x = scaledWidth - w - MARGIN;
        return new int[] { x, y, w, BUTTON_H };
    }

    /**
     * True only when the whole checkbox is inside the list's vertical viewport. Flashback
     * scissor-clips its rows but our render pass is not clipped, so this prevents a
     * partially-scrolled row's checkbox from bleeding over the list's top/bottom fade overlays.
     */
    public static boolean checkboxVisible(int cbY, int cbSize, int listTop, int listBottom) {
        return cbY >= listTop && cbY + cbSize <= listBottom;
    }
}
