package fr.zeffut.multiview.inspect;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.zeffut.multiview.MultiViewMod;
import fr.zeffut.multiview.format.Action;
import fr.zeffut.multiview.format.FlashbackReader;
import fr.zeffut.multiview.format.FlashbackReplay;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class InspectCommand {

    private InspectCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("mv")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("inspect")
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("replayName", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "replayName");
                                    return inspect(ctx.getSource(), name);
                                }))));
    }

    private static int inspect(FabricClientCommandSource src, String replayName) {
        Path replayFolder = MinecraftClient.getInstance().runDirectory.toPath()
                .resolve("replay").resolve(replayName);
        try {
            FlashbackReplay replay = FlashbackReader.open(replayFolder);
            src.sendFeedback(Text.literal("UUID: " + replay.metadata().uuid()));
            src.sendFeedback(Text.literal("World: " + replay.metadata().worldName()));
            src.sendFeedback(Text.literal("MC: " + replay.metadata().versionString()
                    + " (protocol " + replay.metadata().protocolVersion() + ")"));
            src.sendFeedback(Text.literal("Total ticks (metadata): " + replay.metadata().totalTicks()));
            src.sendFeedback(Text.literal("Segments: " + replay.segmentPaths().size()));
            src.sendFeedback(Text.literal("Markers: " + replay.metadata().markers().size()));

            Map<String, Integer> actionHistogram = new HashMap<>();
            int[] stats = { 0, 0, 0 }; // [entriesCount, maxTick, snapshotCount]
            FlashbackReader.stream(replay).forEach(entry -> {
                stats[0]++;
                if (entry.tick() > stats[1]) stats[1] = entry.tick();
                if (entry.inSnapshot()) stats[2]++;
                String bucket = switch (entry.action()) {
                    case Action.NextTick nt          -> "NextTick";
                    case Action.ConfigurationPacket c -> "ConfigurationPacket";
                    case Action.GamePacket g          -> "GamePacket";
                    case Action.CreatePlayer p        -> "CreatePlayer";
                    case Action.MoveEntities m        -> "MoveEntities";
                    case Action.CacheChunkRef r       -> "CacheChunkRef";
                    case Action.VoiceChat v           -> "VoiceChat";
                    case Action.EncodedVoiceChat e    -> "EncodedVoiceChat";
                    case Action.Unknown u             -> "Unknown:" + u.id();
                };
                actionHistogram.merge(bucket, 1, Integer::sum);
            });
            src.sendFeedback(Text.literal("Entries decoded: " + stats[0] + " | max tick seen: " + stats[1]));
            src.sendFeedback(Text.literal("Snapshot entries: " + stats[2]
                    + " / live: " + (stats[0] - stats[2])));
            MultiViewMod.LOGGER.info("[inspect] {} — histogram: {}", replayName, actionHistogram);
            return Command.SINGLE_SUCCESS;
        } catch (IOException e) {
            MultiViewMod.LOGGER.error("Failed to inspect replay {}", replayName, e);
            src.sendError(Text.literal("Failed: " + e.getMessage()));
            return 0;
        }
    }
}
