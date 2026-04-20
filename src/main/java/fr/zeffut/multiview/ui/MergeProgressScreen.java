package fr.zeffut.multiview.ui;

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
            // Refresh the replay list by reopening the select screen
            if (this.previousScreen != null) {
                this.client.setScreen(this.previousScreen);
            } else {
                this.client.setScreen(null);
            }
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
        super.renderBackground(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Title
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("multiview.merge_progress.title"),
                centerX, centerY - 50, 0xFFFFFF);

        // Phase text with animated dots
        String phase = currentPhase.get();
        if (errorMessage != null) {
            // Show error in red
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("§c" + errorMessage),
                    centerX, centerY - 20, 0xFF5555);
        } else if (done) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("multiview.merge_progress.done"),
                    centerX, centerY - 20, 0x55FF55);
        } else {
            // Animated dots: 0, 1, 2, 3 dots cycling every 10 ticks
            int dots = (tickCount / 10) % 4;
            String dotStr = ".".repeat(dots);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(phase + dotStr),
                    centerX, centerY - 20, 0xAAAAAA);
        }

        // Spinner: simple rotating ascii spinner
        if (errorMessage == null && !done) {
            char[] spinner = {'|', '/', '-', '\\'};
            char spin = spinner[(tickCount / 5) % spinner.length];
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(String.valueOf(spin)),
                    centerX, centerY + 10, 0xFFFFFF);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Prevent accidental close during merge
        return done || errorMessage != null;
    }
}
