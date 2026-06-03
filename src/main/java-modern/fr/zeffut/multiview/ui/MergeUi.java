package fr.zeffut.multiview.ui;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.screen.ReplaySummary;
import com.moulberry.flashback.screen.select_replay.ReplaySelectionEntry;
import com.moulberry.flashback.screen.select_replay.ReplaySelectionList;
import com.moulberry.flashback.screen.select_replay.SelectReplayScreen;
import fr.zeffut.multiview.MultiViewMod;
import fr.zeffut.multiview.merge.MergeOptions;
import fr.zeffut.multiview.merge.MergeOrchestrator;
import fr.zeffut.multiview.merge.OverlapValidator;
import fr.zeffut.multiview.merge.PacketIdProvider;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Merge-replay UI for Minecraft 26.1+.
 * <p>
 * Mirrors the 1.21.x implementation in {@code src/main/java-classic} but targets the
 * post-26.1 rendering pipeline:
 * <ul>
 *   <li>{@code GuiGraphics} → {@code GuiGraphicsExtractor}</li>
 *   <li>{@code Screen.render} → {@code Screen.extractRenderState}</li>
 *   <li>{@code ScreenEvents.afterRender} → {@code ScreenEvents.afterExtract}</li>
 *   <li>{@code drawCenteredString} → manual centering via {@code font.width()} + {@code text(...)}</li>
 *   <li>{@code Screens.getButtons} → reflection on {@code Screen.addRenderableWidget}</li>
 * </ul>
 * Only one of the two variants (classic or modern) is compiled at a time; build.gradle
 * picks the source root from the {@code no_intermediate} property.
 */
public final class MergeUi {

    public static final String UI_VARIANT = "modern";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "multiview-merge-ui");
        t.setDaemon(true);
        return t;
    });

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final int CB_SIZE = 10;
    private static final int CB_INSET_X = 2;

    private MergeUi() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register(MergeUi::onAfterInit);
        MultiViewMod.LOGGER.info("[MultiView] MergeUi registered (Phase 5 — per-replay checkboxes, MC 26.1+ variant).");
    }

    private static final class SelectionState {
        final Set<Path> checkedPaths = new LinkedHashSet<>();
        Button mergeButton = null;
    }

    private static void onAfterInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof SelectReplayScreen srs)) return;

        SelectionState state = new SelectionState();

        int btnH = 20;
        int mergeW = 130;
        int margin = 4;
        int mergeX = scaledWidth - mergeW - margin;
        int mergeY = margin;

        Button mergeBtn = Button.builder(
                        Component.translatable("multiview.button.merge_selected"),
                        btn -> startMerge(state, srs, client))
                .bounds(mergeX, mergeY, mergeW, btnH)
                .build();
        mergeBtn.active = false;
        state.mergeButton = mergeBtn;

        addWidgetToScreen(screen, mergeBtn);

        fr.zeffut.multiview.telemetry.Telemetry.capture(
                fr.zeffut.multiview.telemetry.EventNames.EVT_UI_OPENED);

        // Draw checkboxes during the extract-render-state phase (post-26.1 pipeline).
        ScreenEvents.afterExtract(screen).register((s, context, mouseX, mouseY, delta) ->
                drawCheckboxes(state, srs, context, mouseX, mouseY));

        // Mouse click interception — the 26.1 callback receives a MouseButtonEvent
        // whose accessors are .x() / .y() / .button(), matching the classic call.
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
                handleMouseClick(state, srs, event.x(), event.y(), event.button()));

        ScreenEvents.remove(screen).register(s -> state.checkedPaths.clear());
    }

    /**
     * Adds a renderable widget to an arbitrary {@link Screen} instance.
     *
     * <p>{@code Screen.addRenderableWidget} is protected and its exact parameter type has
     * shifted across 26.1.x patches (sometimes {@code GuiEventListener}, sometimes the more
     * specific {@code AbstractWidget} / {@code Renderable}). Rather than pin to one name,
     * we iterate every declared method on {@code Screen} and pick the first that accepts
     * our {@link Button} via {@code isAssignableFrom}.
     */
    private static void addWidgetToScreen(Screen screen, Button button) {
        for (java.lang.reflect.Method m : Screen.class.getDeclaredMethods()) {
            if (!"addRenderableWidget".equals(m.getName())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 1) continue;
            if (!params[0].isAssignableFrom(button.getClass())) continue;
            try {
                m.setAccessible(true);
                m.invoke(screen, button);
                return;
            } catch (Throwable t) {
                MultiViewMod.LOGGER.warn("[MultiView] addRenderableWidget invoke failed ({}): {}",
                        m, t.getClass().getSimpleName() + " " + t.getMessage());
                return;
            }
        }
        MultiViewMod.LOGGER.warn("[MultiView] Could not find any Screen.addRenderableWidget overload accepting {} — merge button disabled.",
                button.getClass().getName());
    }

    private static void drawCheckboxes(SelectionState state, SelectReplayScreen srs,
                                        GuiGraphicsExtractor context, int mouseX, int mouseY) {
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

            if (rowBottom < list.getY() || rowTop > list.getBottom()) continue;

            int cbX = list.getRowLeft() + list.getRowWidth() - CB_SIZE - CB_INSET_X;
            int cbY = rowTop + (rowBottom - rowTop) / 2 - CB_SIZE / 2;

            boolean checked = state.checkedPaths.contains(summary.getPath());
            boolean hovered = mouseX >= cbX && mouseX <= cbX + CB_SIZE
                    && mouseY >= cbY && mouseY <= cbY + CB_SIZE;

            drawCheckbox(context, cbX, cbY, checked, hovered);
        }
    }

    private static void drawCheckbox(GuiGraphicsExtractor context, int x, int y, boolean checked, boolean hovered) {
        int borderColor = hovered ? 0xFFFFFFFF : 0xFFAAAAAA;
        context.fill(x - 1, y - 1, x + CB_SIZE + 1, y + CB_SIZE + 1, borderColor);

        if (checked) {
            context.fill(x, y, x + CB_SIZE, y + CB_SIZE, 0xFF4CAF50);
        } else {
            context.fill(x, y, x + CB_SIZE, y + CB_SIZE, 0xFF2A2A2A);
        }
    }

    private static boolean handleMouseClick(SelectionState state, SelectReplayScreen srs,
                                             double mouseX, double mouseY, int button) {
        if (button != 0) return true;

        ReplaySelectionList list = getSelectionList(srs);
        if (list == null) return true;

        List<ReplaySelectionEntry> children = list.children();
        for (int i = 0; i < children.size(); i++) {
            ReplaySelectionEntry entry = children.get(i);
            if (!(entry instanceof ReplaySelectionEntry.ReplayListEntry rle)) continue;

            int rowTop = list.getRowTop(i);
            int rowBottom = list.getRowBottom(i);

            if (rowBottom < list.getY() || rowTop > list.getBottom()) continue;

            int cbX = list.getRowLeft() + list.getRowWidth() - CB_SIZE - CB_INSET_X;
            int cbY = rowTop + (rowBottom - rowTop) / 2 - CB_SIZE / 2;

            if (mouseX >= cbX - 1 && mouseX <= cbX + CB_SIZE + 1
                    && mouseY >= cbY - 1 && mouseY <= cbY + CB_SIZE + 1) {
                ReplaySummary summary = getSummaryFromEntry(rle);
                if (summary == null) return true;

                Path path = summary.getPath();
                if (state.checkedPaths.contains(path)) {
                    state.checkedPaths.remove(path);
                } else {
                    state.checkedPaths.add(path);
                }
                updateMergeButton(state);
                return false;
            }
        }
        return true;
    }

    /**
     * Cache of overlap-validation results, keyed by the immutable Set of selected paths.
     * Validation reads ~1200 ticks from each replay file, so we memoise to keep checkbox
     * toggles responsive when the user revisits a previously-validated selection.
     */
    private static final Map<Set<Path>, OverlapValidator.Result> VALIDATION_CACHE = new HashMap<>();

    private static void updateMergeButton(SelectionState state) {
        if (state.mergeButton == null) return;
        int n = state.checkedPaths.size();

        fr.zeffut.multiview.telemetry.Telemetry.capture(
                fr.zeffut.multiview.telemetry.EventNames.EVT_UI_FILE_SELECTED,
                java.util.Map.of("selected_count", n));

        if (n < 2) {
            state.mergeButton.active = false;
            state.mergeButton.setMessage(Component.translatable("multiview.button.merge_selected"));
            state.mergeButton.setTooltip(null);
            return;
        }

        Set<Path> snapshot = Set.copyOf(state.checkedPaths);
        OverlapValidator.Result result = VALIDATION_CACHE.computeIfAbsent(snapshot, s ->
                OverlapValidator.validate(new ArrayList<>(s), PacketIdProvider.minecraftRuntime()));

        Component countMsg = Component.translatable("multiview.button.merge_selected.count", n);

        if (result.status() == OverlapValidator.Status.NO_OVERLAP) {
            fr.zeffut.multiview.telemetry.Telemetry.capture(
                    fr.zeffut.multiview.telemetry.EventNames.EVT_OVERLAP_VALIDATION_FAILED,
                    java.util.Map.of("selected_count", n));
            state.mergeButton.active = false;
            state.mergeButton.setMessage(countMsg);
            state.mergeButton.setTooltip(Tooltip.create(
                    Component.translatable("multiview.button.merge_selected.no_overlap")));
        } else {
            // OK or UNKNOWN — both allow the merge to proceed. UNKNOWN means we couldn't
            // probe a SetTime anchor in one of the replays; rather than blocking what we
            // can't verify, we let the user try (in line with the product philosophy).
            state.mergeButton.active = true;
            state.mergeButton.setMessage(countMsg);
            state.mergeButton.setTooltip(null);
        }
    }

    private static void startMerge(SelectionState state, SelectReplayScreen parentScreen,
                                   Minecraft client) {
        if (state.checkedPaths.size() < 2) return;
        List<Path> sourcePaths = new ArrayList<>(state.checkedPaths);

        String ts = LocalDateTime.now().format(TS_FMT);
        String outputName = "merged_" + ts;

        Path replayRoot = Flashback.getReplayFolder();
        Path destPath = replayRoot.resolve(outputName);

        MergeOptions options = new MergeOptions(sourcePaths, destPath, Map.of(), false);

        MergeProgressScreen progressScreen = new MergeProgressScreen(parentScreen);
        client.setScreen(progressScreen);

        state.checkedPaths.clear();
        if (state.mergeButton != null) {
            state.mergeButton.active = false;
            state.mergeButton.setMessage(Component.translatable("multiview.button.merge_selected"));
        }

        fr.zeffut.multiview.telemetry.Telemetry.capture(
                fr.zeffut.multiview.telemetry.EventNames.EVT_UI_MERGE_CLICKED,
                java.util.Map.of("trigger", "ui"));

        java.util.concurrent.Future<?> future = EXECUTOR.submit(() -> {
            try {
                MergeOrchestrator.run(options, phase ->
                        client.execute(() -> {
                            if (client.screen == progressScreen) progressScreen.setPhase(phase);
                        }));
                client.execute(() -> {
                    if (client.screen == progressScreen) progressScreen.onMergeSuccess();
                });
            } catch (Throwable t) {
                MultiViewMod.LOGGER.error("[MultiView] Merge failed", t);
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                client.execute(() -> {
                    if (client.screen == progressScreen) progressScreen.onMergeError(msg);
                });
            }
        });
        progressScreen.attachMergeFuture(future);
    }

    static ReplaySelectionList getSelectionList(SelectReplayScreen screen) {
        try {
            java.lang.reflect.Field f = SelectReplayScreen.class.getDeclaredField("list");
            f.setAccessible(true);
            return (ReplaySelectionList) f.get(screen);
        } catch (NoSuchFieldException nsf) {
            MultiViewMod.LOGGER.warn("[MultiView] SelectReplayScreen.list field not found ({}). "
                    + "Flashback may have changed its internal layout — UI disabled.",
                    nsf.getMessage());
            return null;
        } catch (Exception e) {
            MultiViewMod.LOGGER.warn("[MultiView] Could not access ReplaySelectionList field: {}",
                    e.getMessage());
            return null;
        }
    }

    private static ReplaySummary getSummaryFromEntry(ReplaySelectionEntry.ReplayListEntry entry) {
        try {
            java.lang.reflect.Field f = ReplaySelectionEntry.ReplayListEntry.class.getDeclaredField("summary");
            f.setAccessible(true);
            return (ReplaySummary) f.get(entry);
        } catch (NoSuchFieldException nsf) {
            MultiViewMod.LOGGER.warn("[MultiView] ReplayListEntry.summary field not found ({}). "
                    + "Flashback may have changed its internal layout.", nsf.getMessage());
            return null;
        } catch (Exception e) {
            MultiViewMod.LOGGER.warn("[MultiView] Could not read summary field: {}", e.getMessage());
            return null;
        }
    }
}
