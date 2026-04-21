package fr.zeffut.multiview.ui;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.screen.ReplaySummary;
import com.moulberry.flashback.screen.select_replay.ReplaySelectionEntry;
import com.moulberry.flashback.screen.select_replay.ReplaySelectionList;
import com.moulberry.flashback.screen.select_replay.SelectReplayScreen;
import fr.zeffut.multiview.MultiViewMod;
import fr.zeffut.multiview.merge.MergeOptions;
import fr.zeffut.multiview.merge.MergeOrchestrator;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Integrates a per-replay checkbox merge UI into Flashback's {@code SelectReplayScreen}.
 * <p>
 * Uses Fabric's {@link ScreenEvents#AFTER_INIT} to inject a "Merge N Replays" button
 * and register after-render / mouse-click hooks without modifying Flashback sources.
 *
 * <h2>UX Flow</h2>
 * <ol>
 *   <li>Every replay row shows a small checkbox at its leftmost edge.</li>
 *   <li>Clicking the checkbox toggles selection independently of single-replay
 *       actions (Open / Edit / Delete).</li>
 *   <li>A "Merge N Replays" button in the top-right is enabled when ≥ 2 boxes are checked.</li>
 *   <li>Clicking "Merge N Replays" opens {@link MergeProgressScreen} and starts the merge.</li>
 * </ol>
 *
 * <h2>Checkbox rendering</h2>
 * Rendered via {@link ScreenEvents#afterRender} — no mixin required.
 *
 * <h2>Click interception</h2>
 * {@link ScreenMouseEvents#allowMouseClick} returns {@code false} when the click
 * lands on a checkbox rectangle, so Flashback's row selection is left undisturbed.
 */
public final class MergeUi {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "multiview-merge-ui");
        t.setDaemon(true);
        return t;
    });

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** Width/height of each checkbox square (pixels, in GUI scale). */
    private static final int CB_SIZE = 10;
    /** Horizontal inset from the row's left edge. */
    private static final int CB_INSET_X = 2;

    private MergeUi() {}

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Registers the ScreenEvents listener. Call once from
     * {@link fr.zeffut.multiview.MultiViewMod#onInitializeClient()}.
     */
    public static void register() {
        ScreenEvents.AFTER_INIT.register(MergeUi::onAfterInit);
        MultiViewMod.LOGGER.info("[MultiView] MergeUi registered (Phase 5 — per-replay checkboxes).");
    }

    // -------------------------------------------------------------------------
    // Per-screen state
    // -------------------------------------------------------------------------

    /**
     * Mutable state associated with one instance of {@link SelectReplayScreen}.
     * Created when the screen is initialised and discarded when it closes.
     */
    private static final class SelectionState {
        /**
         * Replay paths that are currently checked (by the user via checkbox).
         * Using Path as key because ReplaySummary doesn't override equals/hashCode
         * in a stable way across list reloads.
         */
        final Set<Path> checkedPaths = new LinkedHashSet<>();

        ButtonWidget mergeButton = null;
    }

    // -------------------------------------------------------------------------
    // ScreenEvents.AFTER_INIT handler
    // -------------------------------------------------------------------------

    private static void onAfterInit(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof SelectReplayScreen srs)) return;

        SelectionState state = new SelectionState();

        // --- Layout constants ------------------------------------------------
        int btnH = 20;
        int mergeW = 130;
        int margin = 4;
        int mergeX = scaledWidth - mergeW - margin;
        int mergeY = margin;

        // Merge button — initially disabled
        ButtonWidget mergeBtn = ButtonWidget.builder(
                        Text.translatable("multiview.button.merge_selected"),
                        btn -> startMerge(state, srs, client))
                .dimensions(mergeX, mergeY, mergeW, btnH)
                .build();
        mergeBtn.active = false;
        state.mergeButton = mergeBtn;

        Screens.getButtons(screen).add(mergeBtn);

        // Draw checkboxes after the screen has rendered its normal content
        ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, delta) ->
                drawCheckboxes(state, srs, context, mouseX, mouseY));

        // Intercept mouse clicks on checkboxes
        ScreenMouseEvents.allowMouseClick(screen).register((s, clickContext) ->
                handleMouseClick(state, srs, clickContext.x(), clickContext.y(), clickContext.button()));

        // Clean up when the screen is removed
        ScreenEvents.remove(screen).register(s -> state.checkedPaths.clear());
    }

    // -------------------------------------------------------------------------
    // Checkbox rendering
    // -------------------------------------------------------------------------

    private static void drawCheckboxes(SelectionState state, SelectReplayScreen srs,
                                        DrawContext context, int mouseX, int mouseY) {
        ReplaySelectionList list = getSelectionList(srs);
        if (list == null) return;

        List<ReplaySelectionEntry> children = list.children();
        for (int i = 0; i < children.size(); i++) {
            ReplaySelectionEntry entry = children.get(i);
            if (!(entry instanceof ReplaySelectionEntry.ReplayListEntry rle)) continue;

            ReplaySummary summary = getSummaryFromEntry(rle);
            if (summary == null) continue;

            int rowTop = list.getRowTop(i);
            int rowBottom = list.getRowBottom(i);

            // Skip entries that are fully outside the visible area
            if (rowBottom < list.getY() || rowTop > list.getBottom()) continue;

            int cbX = list.getRowLeft() + list.getRowWidth() - CB_SIZE - CB_INSET_X;
            int cbY = rowTop + (rowBottom - rowTop) / 2 - CB_SIZE / 2;

            boolean checked = state.checkedPaths.contains(summary.getPath());
            boolean hovered = mouseX >= cbX && mouseX <= cbX + CB_SIZE
                    && mouseY >= cbY && mouseY <= cbY + CB_SIZE;

            drawCheckbox(context, cbX, cbY, checked, hovered);
        }
    }

    /**
     * Draws a single checkbox square at (x, y) with size {@link #CB_SIZE}.
     * <ul>
     *   <li>Checked: solid green fill with white border.</li>
     *   <li>Unchecked: dark fill with white border; lighter border on hover.</li>
     * </ul>
     */
    private static void drawCheckbox(DrawContext context, int x, int y, boolean checked, boolean hovered) {
        // Border
        int borderColor = hovered ? 0xFFFFFFFF : 0xFFAAAAAA;
        context.fill(x - 1, y - 1, x + CB_SIZE + 1, y + CB_SIZE + 1, borderColor);

        if (checked) {
            // Green fill
            context.fill(x, y, x + CB_SIZE, y + CB_SIZE, 0xFF4CAF50);
            // Small check mark — two small bright rectangles forming a tick
            context.fill(x + 1, y + CB_SIZE / 2, x + CB_SIZE / 3 + 1, y + CB_SIZE - 1, 0xFFFFFFFF);
            context.fill(x + CB_SIZE / 3, y + CB_SIZE / 3, x + CB_SIZE - 1, y + CB_SIZE / 2 + 1, 0xFFFFFFFF);
        } else {
            // Dark grey fill
            context.fill(x, y, x + CB_SIZE, y + CB_SIZE, 0xFF2A2A2A);
        }
    }

    // -------------------------------------------------------------------------
    // Mouse click interception
    // -------------------------------------------------------------------------

    /**
     * Called by {@code ScreenMouseEvents.allowMouseClick}. Returns {@code false}
     * (blocking the event) when the click hits a checkbox, toggling its state.
     * Returns {@code true} to allow normal Flashback handling otherwise.
     */
    private static boolean handleMouseClick(SelectionState state, SelectReplayScreen srs,
                                             double mouseX, double mouseY, int button) {
        if (button != 0) return true; // only left-click

        ReplaySelectionList list = getSelectionList(srs);
        if (list == null) return true;

        List<ReplaySelectionEntry> children = list.children();
        for (int i = 0; i < children.size(); i++) {
            ReplaySelectionEntry entry = children.get(i);
            if (!(entry instanceof ReplaySelectionEntry.ReplayListEntry rle)) continue;

            int rowTop = list.getRowTop(i);
            int rowBottom = list.getRowBottom(i);

            // Skip entries outside the visible clip area
            if (rowBottom < list.getY() || rowTop > list.getBottom()) continue;

            int cbX = list.getRowLeft() + list.getRowWidth() - CB_SIZE - CB_INSET_X;
            int cbY = rowTop + (rowBottom - rowTop) / 2 - CB_SIZE / 2;

            if (mouseX >= cbX - 1 && mouseX <= cbX + CB_SIZE + 1
                    && mouseY >= cbY - 1 && mouseY <= cbY + CB_SIZE + 1) {
                // Hit! Toggle this entry
                ReplaySummary summary = getSummaryFromEntry(rle);
                if (summary == null) return true;

                Path path = summary.getPath();
                if (state.checkedPaths.contains(path)) {
                    state.checkedPaths.remove(path);
                } else {
                    state.checkedPaths.add(path);
                }
                updateMergeButton(state);
                return false; // consume — don't pass to Flashback row selection
            }
        }
        return true; // not on a checkbox, allow normal handling
    }

    // -------------------------------------------------------------------------
    // Merge button
    // -------------------------------------------------------------------------

    private static void updateMergeButton(SelectionState state) {
        if (state.mergeButton == null) return;
        int n = state.checkedPaths.size();
        state.mergeButton.active = n >= 2;
        if (n >= 2) {
            state.mergeButton.setMessage(
                    Text.translatable("multiview.button.merge_selected.count", n));
        } else {
            state.mergeButton.setMessage(Text.translatable("multiview.button.merge_selected"));
        }
    }

    // -------------------------------------------------------------------------
    // Start merge
    // -------------------------------------------------------------------------

    private static void startMerge(SelectionState state, SelectReplayScreen parentScreen,
                                   MinecraftClient client) {
        if (state.checkedPaths.size() < 2) return;

        List<Path> sourcePaths = new ArrayList<>(state.checkedPaths);

        // Output name: first source name + timestamp
        String firstName = sourcePaths.get(0).getFileName().toString();
        // Strip zip extension if present
        if (firstName.endsWith(".zip")) {
            firstName = firstName.substring(0, firstName.length() - 4);
        }
        String ts = LocalDateTime.now().format(TS_FMT);
        String outputName = sanitizeName(firstName) + "_merged_" + ts;

        Path replayRoot = Flashback.getReplayFolder();
        Path destPath = replayRoot.resolve(outputName);

        MergeOptions options = new MergeOptions(sourcePaths, destPath, Map.of(), false);

        // Open progress screen
        MergeProgressScreen progressScreen = new MergeProgressScreen(parentScreen);
        client.setScreen(progressScreen);

        // Reset state
        state.checkedPaths.clear();
        if (state.mergeButton != null) {
            state.mergeButton.active = false;
            state.mergeButton.setMessage(Text.translatable("multiview.button.merge_selected"));
        }

        // Run merge in background
        EXECUTOR.submit(() -> {
            try {
                MergeOrchestrator.run(options, phase ->
                        client.execute(() -> progressScreen.setPhase(phase)));
                client.execute(progressScreen::onMergeSuccess);
            } catch (Throwable t) {
                MultiViewMod.LOGGER.error("[MultiView] Merge failed", t);
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                client.execute(() -> progressScreen.onMergeError(msg));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Retrieves the {@link ReplaySelectionList} from a {@link SelectReplayScreen}
     * via reflection (the field is private in Flashback).
     */
    static ReplaySelectionList getSelectionList(SelectReplayScreen screen) {
        try {
            java.lang.reflect.Field f = SelectReplayScreen.class.getDeclaredField("list");
            f.setAccessible(true);
            return (ReplaySelectionList) f.get(screen);
        } catch (Exception e) {
            MultiViewMod.LOGGER.debug("[MultiView] Could not access ReplaySelectionList field: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Reads the {@code summary} field from a {@link ReplaySelectionEntry.ReplayListEntry}
     * via reflection (the field has package-private access in Flashback).
     */
    private static ReplaySummary getSummaryFromEntry(ReplaySelectionEntry.ReplayListEntry entry) {
        try {
            java.lang.reflect.Field f = ReplaySelectionEntry.ReplayListEntry.class.getDeclaredField("summary");
            f.setAccessible(true);
            return (ReplaySummary) f.get(entry);
        } catch (Exception e) {
            MultiViewMod.LOGGER.debug("[MultiView] Could not read summary field: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Strips characters that are unsafe for file names, replacing them with '_'.
     */
    private static String sanitizeName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_")
                   .replaceAll("\\s+", "_")
                   .replaceAll("_{2,}", "_");
    }
}
