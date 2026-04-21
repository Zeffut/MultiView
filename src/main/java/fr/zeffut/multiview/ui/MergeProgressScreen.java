package fr.zeffut.multiview.ui;

import com.moulberry.flashback.screen.select_replay.ReplaySelectionList;
import com.moulberry.flashback.screen.select_replay.SelectReplayScreen;
import fr.zeffut.multiview.MultiViewMod;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Screen shown while MergeOrchestrator runs in the background.
 * <p>
 * Phase text is updated thread-safely via {@link #setPhase(String)}.
 * Cancellation is NOT supported in Phase 5 (Phase 6 can add it).
 */
public class MergeProgressScreen extends Screen {

    private final Screen previousScreen;
    private final AtomicReference<String> currentPhase = new AtomicReference<>("Initialisation...");
    /** Set to true by the background thread when the merge completes successfully. */
    private volatile boolean done = false;
    /** Set by the background thread if the merge fails. Null while running. */
    private volatile String errorMessage = null;

    /** Dots animation counter (ticks). */
    private int tickCount = 0;

    /** Extracted from phase strings matching "tick N / M". 0..1, -1 if unknown. */
    private volatile double progress = -1.0;

    private static final java.util.regex.Pattern PROGRESS_PATTERN =
            java.util.regex.Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    public MergeProgressScreen(Screen previousScreen) {
        super(Text.translatable("multiview.merge_progress.title"));
        this.previousScreen = previousScreen;
    }

    @Override
    protected void init() {
        // No close button — the screen closes automatically on completion.
        // A "Back" button is added only as an escape hatch if the merge is done/failed.
        // We always show it but it becomes active only after done/error.
        int btnW = 120;
        int btnX = this.width / 2 - btnW / 2;
        int btnY = this.height / 2 + 40;

        ButtonWidget backBtn = ButtonWidget.builder(
                        Text.translatable("gui.back"),
                        btn -> this.client.setScreen(this.previousScreen))
                .dimensions(btnX, btnY, btnW, 20)
                .build();
        backBtn.active = false;
        this.addDrawableChild(backBtn);
    }

    /**
     * Called from the background merge thread (or the main thread via {@code execute()}).
     * Thread-safe.
     */
    public void setPhase(String phase) {
        currentPhase.set(phase);
        java.util.regex.Matcher m = PROGRESS_PATTERN.matcher(phase);
        if (m.find()) {
            try {
                long cur = Long.parseLong(m.group(1));
                long total = Long.parseLong(m.group(2));
                if (total > 0) progress = Math.min(1.0, (double) cur / total);
            } catch (NumberFormatException ignore) {}
        }
    }

    /**
     * Called from the background thread when the merge completes successfully.
     * Triggers auto-close on the next render tick.
     */
    public void onMergeSuccess() {
        this.done = true;
    }

    /**
     * Called from the background thread when the merge fails.
     * Shows the error and activates the Back button.
     */
    public void onMergeError(String message) {
        this.errorMessage = message;
        // Activate back button — we need to do this on the render thread
    }

    @Override
    public void tick() {
        tickCount++;
        if (done && this.client != null) {
            // Reload the replay list so the newly-created merge zip is visible immediately
            if (this.previousScreen instanceof SelectReplayScreen srs) {
                reloadReplayList(srs);
            }
            // Return to the select-replay screen (or null if there was none)
            this.client.setScreen(this.previousScreen);
        }
        // Activate back button once done or errored
        if ((done || errorMessage != null) && this.children() != null) {
            for (var child : this.children()) {
                if (child instanceof ButtonWidget btn) {
                    btn.active = true;
                }
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // super.render already calls renderBackground internally; calling it
        // again triggered "Can only blur once per frame" in 1.21.x.
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Layout (vertically stacked, centered):
        //   Title       — centerY - 40
        //   Phase text  — centerY - 15
        //   Progress bar— centerY + 5
        //   Percentage  — centerY + 25
        int barW = 300;
        int barH = 14;
        int barY = centerY + 5;

        // Title
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("multiview.merge_progress.title"),
                centerX, centerY - 40, 0xFFFFFF);

        // Phase text with animated dots — dedicated row with its own background
        String phase = currentPhase.get();
        int phaseY = centerY - 15;
        if (errorMessage != null) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(errorMessage),
                    centerX, phaseY, 0xFFFF5555);
        } else if (done) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("multiview.merge_progress.done"),
                    centerX, phaseY, 0xFF55FF55);
        } else {
            int dots = (tickCount / 10) % 4;
            String dotStr = ".".repeat(dots) + " ".repeat(3 - dots);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(phase + dotStr),
                    centerX, phaseY, 0xFFCCCCCC);
        }

        // Progress bar
        if (errorMessage == null && !done) {
            int barX = centerX - barW / 2;
            // Border
            context.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFFFFFFFF);
            // Background
            context.fill(barX, barY, barX + barW, barY + barH, 0xFF404040);
            // Fill (if known) or indeterminate pulse
            if (progress >= 0.0) {
                int fillW = (int) (barW * progress);
                context.fill(barX, barY, barX + fillW, barY + barH, 0xFF4CAF50);
                String pct = String.format("%.1f%%", progress * 100.0);
                context.drawCenteredTextWithShadow(this.textRenderer,
                        Text.literal(pct), centerX, barY + barH + 4, 0xFFFFFF);
            } else {
                int pulseW = 60;
                int pulseX = barX + (int) ((barW - pulseW) * ((tickCount % 60) / 60.0));
                context.fill(pulseX, barY, pulseX + pulseW, barY + barH, 0xFF4CAF50);
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Prevent accidental close during merge
        return done || errorMessage != null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Triggers Flashback's replay-list refresh on the given {@link SelectReplayScreen}
     * so the newly-created merge zip appears immediately without a manual Back + re-enter.
     * <p>
     * {@code ReplaySelectionList.reloadReplayList()} is public, but the {@code list}
     * field on {@link SelectReplayScreen} is private — accessed via reflection.
     */
    private static void reloadReplayList(SelectReplayScreen srs) {
        try {
            ReplaySelectionList list = MergeUi.getSelectionList(srs);
            if (list != null) {
                list.reloadReplayList();
                MultiViewMod.LOGGER.debug("[MultiView] Triggered reloadReplayList() on SelectReplayScreen.");
            }
        } catch (Throwable t) {
            MultiViewMod.LOGGER.warn("[MultiView] Could not reload replay list: {}", t.getMessage());
        }
    }
}
