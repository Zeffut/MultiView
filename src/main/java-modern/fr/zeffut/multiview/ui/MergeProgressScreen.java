package fr.zeffut.multiview.ui;

import com.moulberry.flashback.screen.select_replay.ReplaySelectionList;
import com.moulberry.flashback.screen.select_replay.SelectReplayScreen;
import fr.zeffut.multiview.MultiViewMod;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Merge progress screen for Minecraft 26.1+.
 * <p>
 * Mirrors the classic implementation but uses the post-26.1 rendering pipeline:
 * we override {@link Screen#extractRenderState(GuiGraphicsExtractor, int, int, float)}
 * instead of {@code render(GuiGraphics, ...)}, and we use
 * {@code context.text(font, str, x, y, color)} with manual centering (via
 * {@code font.width()}) instead of {@code drawCenteredString}.
 */
public class MergeProgressScreen extends Screen {

    private final Screen previousScreen;
    private final AtomicReference<String> currentPhase = new AtomicReference<>("Initialisation...");
    private volatile boolean done = false;
    private volatile String errorMessage = null;
    private volatile java.util.concurrent.Future<?> mergeFuture = null;
    private Button cancelButton = null;

    public void attachMergeFuture(java.util.concurrent.Future<?> future) {
        this.mergeFuture = future;
    }

    private int tickCount = 0;
    private volatile double progress = -1.0;

    private static final java.util.regex.Pattern PROGRESS_PATTERN =
            java.util.regex.Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    public MergeProgressScreen(Screen previousScreen) {
        super(Component.translatable("multiview.merge_progress.title"));
        this.previousScreen = previousScreen;
    }

    @Override
    protected void init() {
        int btnW = 100;
        int gap = 8;
        int totalW = btnW * 2 + gap;
        int leftX = this.width / 2 - totalW / 2;
        int btnY = this.height / 2 + 40;

        Button cancelBtn = Button.builder(
                        Component.translatable("gui.cancel"),
                        btn -> {
                            fr.zeffut.multiview.telemetry.Telemetry.capture(
                                    fr.zeffut.multiview.telemetry.EventNames.EVT_MERGE_CANCELLED,
                                    java.util.Map.of("trigger", "ui", "phase", this.currentPhase.get()));
                            java.util.concurrent.Future<?> f = this.mergeFuture;
                            if (f != null) f.cancel(true);
                            this.errorMessage = "Cancelled by user.";
                            btn.active = false;
                        })
                .bounds(leftX, btnY, btnW, 20)
                .build();
        cancelBtn.active = true;
        this.cancelButton = cancelBtn;
        this.addRenderableWidget(cancelBtn);

        Button backBtn = Button.builder(
                        Component.translatable("gui.back"),
                        btn -> {
                            if (this.minecraft != null) {
                                this.minecraft.setScreen(this.previousScreen);
                            }
                        })
                .bounds(leftX + btnW + gap, btnY, btnW, 20)
                .build();
        backBtn.active = false;
        this.addRenderableWidget(backBtn);
    }

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

    public void onMergeSuccess() { this.done = true; }

    public void onMergeError(String message) { this.errorMessage = message; }

    @Override
    public void tick() {
        tickCount++;
        if (done && this.minecraft != null) {
            if (this.previousScreen instanceof SelectReplayScreen srs) {
                reloadReplayList(srs);
            }
            this.minecraft.setScreen(this.previousScreen);
        }
        if ((done || errorMessage != null) && this.children() != null) {
            for (var child : this.children()) {
                if (child instanceof Button btn) {
                    if (btn == this.cancelButton) {
                        btn.active = false;
                    } else {
                        btn.active = true;
                    }
                }
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int barW = 300;
        int barH = 14;
        int barY = centerY + 5;

        drawCentered(context,
                Component.translatable("multiview.merge_progress.title"),
                centerX, centerY - 60, 0xFFFFFF);

        String phase = currentPhase.get();
        int phaseY = centerY - 30;
        if (errorMessage != null) {
            drawCentered(context, Component.literal(errorMessage), centerX, phaseY, 0xFFFF5555);
        } else if (done) {
            drawCentered(context, Component.translatable("multiview.merge_progress.done"),
                    centerX, phaseY, 0xFF55FF55);
        } else {
            int dots = (tickCount / 10) % 4;
            String dotStr = ".".repeat(dots) + " ".repeat(3 - dots);
            // Static phases come as translation keys ("multiview.merge_progress.phase.*");
            // dynamic phases (with %d formatting) come as pre-formatted literals.
            Component phaseComponent = phase.startsWith("multiview.")
                    ? Component.translatable(phase)
                    : Component.literal(phase);
            drawCentered(context,
                    Component.literal("").append(phaseComponent).append(dotStr),
                    centerX, phaseY, 0xFFCCCCCC);
        }

        if (errorMessage == null && !done) {
            int barX = centerX - barW / 2;
            context.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFFFFFFFF);
            context.fill(barX, barY, barX + barW, barY + barH, 0xFF404040);
            if (progress >= 0.0) {
                int fillW = (int) (barW * progress);
                context.fill(barX, barY, barX + fillW, barY + barH, 0xFF4CAF50);
                String pct = String.format("%.1f%%", progress * 100.0);
                drawCentered(context, Component.literal(pct), centerX, barY + barH + 4, 0xFFFFFF);
            } else {
                int pulseW = 60;
                int pulseX = barX + (int) ((barW - pulseW) * ((tickCount % 60) / 60.0));
                context.fill(pulseX, barY, pulseX + pulseW, barY + barH, 0xFF4CAF50);
            }
        }
    }

    /**
     * 26.1 has no {@code drawCenteredString}; we center manually using the font's
     * pixel width and call {@link GuiGraphicsExtractor#text(net.minecraft.client.gui.Font, String, int, int, int)}.
     */
    private void drawCentered(GuiGraphicsExtractor context, Component text, int centerX, int y, int color) {
        String s = text.getString();
        int w = this.font.width(s);
        context.text(this.font, s, centerX - w / 2, y, color);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return done || errorMessage != null;
    }

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
