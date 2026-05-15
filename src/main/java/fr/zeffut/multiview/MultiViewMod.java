package fr.zeffut.multiview;

import fr.zeffut.multiview.inspect.InspectCommand;
import fr.zeffut.multiview.merge.command.MergeCommand;
import fr.zeffut.multiview.ui.MergeUi;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MultiViewMod implements ClientModInitializer {
    public static final String MOD_ID = "multiview";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("MultiView loaded — addon pour Flashback, merge de replays multi-joueurs.");
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            InspectCommand.register(dispatcher);
            MergeCommand.register(dispatcher);
        });
        // Phase 5: register merge UI buttons in Flashback's SelectReplayScreen.
        // Probe the rendering API up-front: if GuiGraphics is missing (renamed in
        // MC 26.1+), skip UI registration entirely. Otherwise the AFTER_INIT
        // listener would crash later when it tries to draw checkboxes.
        boolean uiAvailable = false;
        try {
            Class.forName("net.minecraft.client.gui.GuiGraphics");
            uiAvailable = true;
        } catch (Throwable t) {
            LOGGER.warn("MultiView UI disabled on this MC version "
                    + "(GuiGraphics not found). Use /mv merge via chat instead.");
        }
        if (uiAvailable) {
            try {
                MergeUi.register();
            } catch (Throwable t) {
                LOGGER.warn("MultiView UI registration failed ({}). "
                        + "Use /mv merge via chat instead.", t.getClass().getSimpleName());
            }
        }
    }
}
