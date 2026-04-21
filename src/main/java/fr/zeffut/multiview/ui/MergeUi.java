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
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Integrates a multi-select merge UI into Flashback's {@code SelectReplayScreen}.
 * <p>
 * Uses Fabric's {@link ScreenEvents#AFTER_INIT} to inject buttons without
 * modifying Flashback sources or using mixins.
 *
 * <h2>UX Flow (Option 1 — explicit toggle)</h2>
 * <ol>
 *   <li>A "Multi-select" toggle button is added at the bottom of the screen.</li>
 *   <li>When toggled on, a per-screen {@link SelectionState} is created and attached.</li>
 *   <li>Shift-click on a replay entry adds it to the selection set.</li>
 *   <li>A "Merge Selected" button is enabled once ≥ 2 replays are selected.</li>
 *   <li>Clicking "Merge Selected" opens {@link MergeProgressScreen} and starts the merge.</li>
 * </ol>
 *
 * <h2>Selection mechanism</h2>
 * Fabric's {@link ScreenEvents#afterTick} hook is used to poll Flashback's
 * {@link ReplaySelectionList#getReplayListEntry()} on each tick while multi-select mode
 * is active. When a new entry is "touched" (its summary differs from the last seen
 * selection), we add it to our set if shift is held, or start a new set if not.
 * <p>
 * This polling approach avoids any mixin into Flashback's entry class.
 */
public final class MergeUi {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "multiview-merge-ui");
        t.setDaemon(true);
        return t;
    });

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

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
        MultiViewMod.LOGGER.info("[MultiView] MergeUi registered (Phase 5).");
    }

    // -------------------------------------------------------------------------
    // Per-screen state
    // -------------------------------------------------------------------------

    /**
     * Mutable state associated with one instance of {@link SelectReplayScreen}.
     * Created when the screen is initialised and discarded when it closes.
     */
    private static final class SelectionState {
        /** Replays currently in the multi-select set (insertion order). */
        final LinkedHashSet<ReplaySummary> selected = new LinkedHashSet<>();
        /** Whether multi-select mode is currently active. */
        boolean multiSelectOn = false;
        /** The last summary returned by getReplayListEntry() on the previous tick. */
        ReplaySummary lastSeen = null;

        ButtonWidget toggleButton = null;
        ButtonWidget mergeButton = null;
    }

    // -------------------------------------------------------------------------
    // ScreenEvents.AFTER_INIT handler
    // -------------------------------------------------------------------------

    private static void onAfterInit(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof SelectReplayScreen srs)) return;

        SelectionState state = new SelectionState();

        // --- Layout constants ------------------------------------------------
        // Place our buttons in the top-right corner of the screen, out of the way
        // of Flashback's native title/search/sort row (top-left) and its
        // Open/Edit/Delete/Back button rows (bottom).
        int btnH = 20;
        int toggleW = 110;
        int mergeW = 120;
        int gap = 4;
        int margin = 4;

        int btnY = margin;
        // Two stacked rows on the right: toggle on top, merge below.
        int startX = scaledWidth - toggleW - margin;
        int mergeX = scaledWidth - mergeW - margin;
        int mergeY = btnY + btnH + gap;

        // Toggle button
        ButtonWidget toggleBtn = ButtonWidget.builder(
                        Text.translatable("multiview.button.multiselect"),
                        btn -> toggleMultiSelect(state, btn, srs))
                .dimensions(startX, btnY, toggleW, btnH)
                .build();
        state.toggleButton = toggleBtn;

        // Merge button — initially disabled
        ButtonWidget mergeBtn = ButtonWidget.builder(
                        Text.translatable("multiview.button.merge_selected"),
                        btn -> startMerge(state, srs, client))
                .dimensions(mergeX, mergeY, mergeW, btnH)
                .build();
        mergeBtn.active = false;
        state.mergeButton = mergeBtn;

        Screens.getButtons(screen).add(toggleBtn);
        Screens.getButtons(screen).add(mergeBtn);

        // Poll for selection changes after each tick
        ScreenEvents.afterTick(screen).register(s -> onTick(state, srs));

        // Clean up when the screen is removed
        ScreenEvents.remove(screen).register(s -> {
            state.selected.clear();
            state.multiSelectOn = false;
        });
    }

    // -------------------------------------------------------------------------
    // Toggle multi-select mode
    // -------------------------------------------------------------------------

    private static void toggleMultiSelect(SelectionState state, ButtonWidget btn, SelectReplayScreen screen) {
        state.multiSelectOn = !state.multiSelectOn;
        if (!state.multiSelectOn) {
            // Clear selection when disabling
            state.selected.clear();
            state.lastSeen = null;
            updateMergeButton(state);
            btn.setMessage(Text.translatable("multiview.button.multiselect"));
        } else {
            btn.setMessage(Text.translatable("multiview.button.multiselect.on"));
        }
    }

    // -------------------------------------------------------------------------
    // Per-tick polling for selection
    // -------------------------------------------------------------------------

    private static void onTick(SelectionState state, SelectReplayScreen screen) {
        if (!state.multiSelectOn) return;

        // Access the ReplaySelectionList via the public field
        ReplaySelectionList list = getSelectionList(screen);
        if (list == null) return;

        ReplaySelectionEntry.ReplayListEntry entry = list.getReplayListEntry();
        if (entry == null) return;

        ReplaySummary summary = getSummaryFromEntry(entry);
        if (summary == null) return;

        // Detect a newly selected entry (different from what we saw last tick)
        if (summary != state.lastSeen) {
            state.lastSeen = summary;
            handleEntrySelected(state, summary, screen);
            updateMergeButton(state);
        }
    }

    /**
     * Called when the user selects a replay entry in Flashback's list while
     * multi-select mode is active.
     * <p>
     * If Shift is held: add to set (toggle — add if absent, remove if present).
     * Otherwise: replace the selection set with just this entry.
     */
    private static void handleEntrySelected(SelectionState state, ReplaySummary summary,
                                             SelectReplayScreen screen) {
        boolean shiftHeld = isShiftHeld();
        if (shiftHeld) {
            // Toggle
            if (state.selected.contains(summary)) {
                state.selected.remove(summary);
            } else {
                state.selected.add(summary);
            }
        } else {
            // Single click: start fresh selection with this entry
            state.selected.clear();
            state.selected.add(summary);
        }
    }

    // -------------------------------------------------------------------------
    // Merge button visibility
    // -------------------------------------------------------------------------

    private static void updateMergeButton(SelectionState state) {
        if (state.mergeButton == null) return;
        state.mergeButton.active = state.multiSelectOn && state.selected.size() >= 2;
        // Update label to show count
        if (state.selected.size() >= 2) {
            state.mergeButton.setMessage(
                    Text.translatable("multiview.button.merge_selected.count", state.selected.size()));
        } else {
            state.mergeButton.setMessage(Text.translatable("multiview.button.merge_selected"));
        }
    }

    // -------------------------------------------------------------------------
    // Start merge
    // -------------------------------------------------------------------------

    private static void startMerge(SelectionState state, SelectReplayScreen parentScreen,
                                   MinecraftClient client) {
        if (state.selected.size() < 2) return;

        List<ReplaySummary> toMerge = new ArrayList<>(state.selected);

        // Build source paths — ReplaySummary.getPath() gives the replay folder/zip
        List<Path> sourcePaths = new ArrayList<>();
        for (ReplaySummary rs : toMerge) {
            sourcePaths.add(rs.getPath());
        }

        // Output name: first source name + timestamp
        String firstName = toMerge.get(0).getReplayName();
        if (firstName == null || firstName.isBlank()) {
            firstName = toMerge.get(0).getPath().getFileName().toString();
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
        state.selected.clear();
        state.multiSelectOn = false;
        if (state.toggleButton != null) {
            state.toggleButton.setMessage(Text.translatable("multiview.button.multiselect"));
        }
        if (state.mergeButton != null) {
            state.mergeButton.active = false;
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
    private static ReplaySelectionList getSelectionList(SelectReplayScreen screen) {
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
     * Returns {@code true} if either Shift key is currently held.
     * Uses LWJGL GLFW directly since MC 1.21 removed {@code Screen.hasShiftDown()}.
     */
    private static boolean isShiftHeld() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return false;
        Window window = mc.getWindow();
        if (window == null) return false;
        return InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
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
