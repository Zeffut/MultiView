package fr.zeffut.multiview;

import fr.zeffut.multiview.inspect.InspectCommand;
import fr.zeffut.multiview.merge.command.MergeCommand;
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
        // Phase 5: register merge UI via reflection. The fr.zeffut.multiview.ui
        // package references MC client APIs (GuiGraphics, Screens.afterRender,
        // Screens.getButtons) that changed between 1.21.x and 26.1+, so we either
        // ship the ui package (1.21.x builds) or we ship without it (26.1+ builds
        // where the package is excluded from compilation entirely). Reflection
        // keeps MultiViewMod itself portable.
        boolean uiAvailable = false;
        try {
            Class.forName("net.minecraft.client.gui.GuiGraphics");
            Class.forName("fr.zeffut.multiview.ui.MergeUi");
            uiAvailable = true;
        } catch (Throwable t) {
            LOGGER.warn("MultiView UI disabled on this MC version "
                    + "({}). Use /mv merge via chat instead.",
                    t.getClass().getSimpleName());
        }
        if (uiAvailable) {
            try {
                Class<?> mergeUi = Class.forName("fr.zeffut.multiview.ui.MergeUi");
                mergeUi.getDeclaredMethod("register").invoke(null);
            } catch (Throwable t) {
                LOGGER.warn("MultiView UI registration failed ({}). "
                        + "Use /mv merge via chat instead.", t.getClass().getSimpleName());
            }
        }
    }
}
