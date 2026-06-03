package fr.zeffut.multiview.telemetry.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.zeffut.multiview.telemetry.Telemetry;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/** {@code /mv telemetry on|off|status} — runtime opt-out + transparency. */
public final class TelemetryCommand {
    private TelemetryCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("mv")
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("telemetry")
                                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on")
                                        .executes(c -> set(c.getSource(), true)))
                                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                                        .executes(c -> set(c.getSource(), false)))
                                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                                        .executes(c -> status(c.getSource())))));
    }

    private static int set(FabricClientCommandSource src, boolean enabled) {
        Telemetry.setEnabled(enabled);
        src.sendFeedback(Component.literal(
                "[MultiView] Telemetry " + (enabled ? "enabled" : "disabled") + "."));
        return Command.SINGLE_SUCCESS;
    }

    private static int status(FabricClientCommandSource src) {
        src.sendFeedback(Component.literal(String.format(
                "[MultiView] Telemetry: %s | anonymous id: %s",
                Telemetry.isEnabled() ? "ON" : "OFF", Telemetry.distinctId())));
        return Command.SINGLE_SUCCESS;
    }

    /** One-time chat notice on first run. Caller decides when to show + persist. */
    public static Component firstRunNotice() {
        return Component.literal("[MultiView] Sends anonymous usage stats to improve the mod. "
                + "Run \"/mv telemetry off\" to disable.");
    }
}
