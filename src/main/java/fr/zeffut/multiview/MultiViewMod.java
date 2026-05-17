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
        // Phase 5: register the merge UI via reflection. We ship one of two
        // implementations of fr.zeffut.multiview.ui.MergeUi depending on the
        // target MC version (see build.gradle and the java-classic /
        // java-modern source roots). Loading the class is enough to detect
        // whether the runtime can render the UI — if the rendering classes it
        // imports don't exist, Class.forName will fail and we fall back to
        // chat-only.
        try {
            Class<?> mergeUi = Class.forName("fr.zeffut.multiview.ui.MergeUi");
            mergeUi.getDeclaredMethod("register").invoke(null);
        } catch (Throwable t) {
            LOGGER.warn("MultiView UI disabled on this MC version "
                    + "({}: {}). Use /mv merge via chat instead.",
                    t.getClass().getSimpleName(),
                    t.getMessage() != null ? t.getMessage() : "(no message)");
        }
    }
}
