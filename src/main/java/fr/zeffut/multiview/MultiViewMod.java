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
        // Wrapped in try/catch so the mod still loads on MC versions where the
        // GUI rendering API has changed (e.g. MC 26.1+ renamed GuiGraphics) —
        // users can still invoke /multiview merge via chat commands.
        try {
            MergeUi.register();
        } catch (Throwable t) {
            LOGGER.warn("MultiView UI disabled on this MC version "
                    + "(incompatible rendering API: " + t.getClass().getSimpleName()
                    + "). Use /multiview merge via chat instead.");
        }
    }
}
