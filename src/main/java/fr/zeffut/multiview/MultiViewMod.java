package fr.zeffut.multiview;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MultiViewMod implements ClientModInitializer {
    public static final String MOD_ID = "multiview";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("MultiView loaded — addon pour Flashback, merge de replays multi-joueurs.");
    }
}
