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
            fr.zeffut.multiview.telemetry.command.TelemetryCommand.register(dispatcher);
        });

        String uiCapability;
        try {
            Class<?> mergeUi = Class.forName("fr.zeffut.multiview.ui.MergeUi");
            mergeUi.getDeclaredMethod("register").invoke(null);
            uiCapability = detectUiVariant();
        } catch (Throwable t) {
            LOGGER.warn("MultiView UI disabled on this MC version "
                    + "({}: {}). Use /mv merge via chat instead.",
                    t.getClass().getSimpleName(),
                    t.getMessage() != null ? t.getMessage() : "(no message)");
            uiCapability = "disabled";
        }

        initTelemetry(uiCapability);
    }

    /** "modern" or "classic" depending on which MergeUi source root was compiled in. */
    private static String detectUiVariant() {
        try {
            Class<?> mergeUi = Class.forName("fr.zeffut.multiview.ui.MergeUi");
            Object v = mergeUi.getDeclaredField("UI_VARIANT").get(null);
            return String.valueOf(v);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private void initTelemetry(String uiCapability) {
        try {
            java.nio.file.Path configFile = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve("multiview-telemetry.json");
            boolean devEnv = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .isDevelopmentEnvironment();
            fr.zeffut.multiview.telemetry.Telemetry.init(configFile, uiCapability, devEnv);

            boolean flashbackPresent = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .isModLoaded("flashback");
            fr.zeffut.multiview.telemetry.Telemetry.capture(
                    fr.zeffut.multiview.telemetry.EventNames.EVT_MOD_LOADED,
                    java.util.Map.of("flashback_present", flashbackPresent));

            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
                    .register(client -> fr.zeffut.multiview.telemetry.Telemetry.shutdown());

            // Periodic session heartbeat.
            fr.zeffut.multiview.telemetry.HeartbeatScheduler.start();
        } catch (Throwable t) {
            LOGGER.warn("[MultiView] telemetry setup failed: {}", t.getMessage());
        }
    }
}
