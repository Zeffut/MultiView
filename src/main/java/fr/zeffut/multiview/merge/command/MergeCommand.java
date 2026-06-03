package fr.zeffut.multiview.merge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fr.zeffut.multiview.merge.MergeOptions;
import fr.zeffut.multiview.merge.MergeOrchestrator;
import fr.zeffut.multiview.merge.MergeReport;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public final class MergeCommand {

    private static final Logger LOG = LoggerFactory.getLogger(MergeCommand.class);

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "multiview-merge");
                t.setDaemon(true);
                return t;
            });

    private MergeCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("mv").then(
                        LiteralArgumentBuilder.<FabricClientCommandSource>literal("merge")
                                .then(replayArg("source1")
                                        .then(replayArg("source2")
                                                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>
                                                        argument("output", StringArgumentType.word())
                                                        .executes(ctx -> execute(ctx.getSource(),
                                                                List.of(
                                                                        StringArgumentType.getString(ctx, "source1"),
                                                                        StringArgumentType.getString(ctx, "source2")),
                                                                StringArgumentType.getString(ctx, "output"))))))));
    }

    private static RequiredArgumentBuilder<FabricClientCommandSource, String> replayArg(String name) {
        // string() accepts quoted strings for Unicode folder names (e.g. "Sénat_...")
        return RequiredArgumentBuilder.<FabricClientCommandSource, String>
                argument(name, StringArgumentType.string())
                .suggests(replayFolderSuggestions());
    }

    private static SuggestionProvider<FabricClientCommandSource> replayFolderSuggestions() {
        return (ctx, builder) -> {
            Path replayRoot = com.moulberry.flashback.Flashback.getReplayFolder();
            if (!Files.isDirectory(replayRoot)) return builder.buildFuture();
            try (Stream<Path> entries = Files.list(replayRoot)) {
                entries.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(name -> !name.startsWith("."))
                        .forEach(builder::suggest);
            } catch (Exception ignore) { /* no suggestions on error */ }
            return builder.buildFuture();
        };
    }

    private static int execute(FabricClientCommandSource source,
                               List<String> sourceNames, String outputName) {
        Path replayRoot = com.moulberry.flashback.Flashback.getReplayFolder();
        Path replayRootReal;
        try {
            replayRootReal = replayRoot.toRealPath();
        } catch (java.io.IOException e) {
            source.sendError(Component.literal("[MultiView] Cannot resolve replay folder: " + e.getMessage()));
            return 0;
        }
        List<Path> sources = new ArrayList<>();
        for (String name : sourceNames) {
            Path resolved = replayRoot.resolve(name);
            try {
                Path real = resolved.toRealPath();
                if (!real.startsWith(replayRootReal)) {
                    source.sendError(Component.literal(
                            "[MultiView] Refused: source path escapes the replay folder: " + name));
                    return 0;
                }
                resolved = real;
            } catch (java.io.IOException e) {
                source.sendError(Component.literal("[MultiView] Source not found: " + name));
                return 0;
            }
            sources.add(resolved);
        }
        // Destination must stay inside the replay folder; it may not exist yet, so
        // validate the parent's real path instead of the destination itself.
        Path dest = replayRoot.resolve(outputName).normalize();
        try {
            Path destParentReal = dest.getParent().toRealPath();
            if (!destParentReal.startsWith(replayRootReal)) {
                source.sendError(Component.literal(
                        "[MultiView] Refused: destination escapes the replay folder: " + outputName));
                return 0;
            }
        } catch (java.io.IOException e) {
            source.sendError(Component.literal("[MultiView] Invalid destination: " + e.getMessage()));
            return 0;
        }

        MergeOptions options = new MergeOptions(sources, dest, Map.of(), false);

        source.sendFeedback(Component.literal(
                "[MultiView] \u26a0 /mv merge is deprecated — use the Merge button in the Select Replay screen (Phase 5)."));
        source.sendFeedback(Component.literal("[MultiView] Starting merge of " + sourceNames.size() + " sources..."));

        final long startNanos = System.nanoTime();
        final java.util.Map<String, Object> startProps = new java.util.HashMap<>();
        startProps.put("source_count", sources.size());
        startProps.put("input_bytes_total", totalSize(sources));
        startProps.put("force", options.force());
        startProps.put("tick_overrides", options.tickOverrides().size());
        startProps.put("trigger", "command");
        fr.zeffut.multiview.telemetry.Telemetry.capture(
                fr.zeffut.multiview.telemetry.EventNames.EVT_MERGE_STARTED, startProps);

        EXECUTOR.submit(() -> {
            final long[] phaseStart = { System.nanoTime() };
            final String[] phaseName = { null };
            java.util.function.Consumer<String> progress = phase -> {
                long now = System.nanoTime();
                if (phaseName[0] != null) {
                    fr.zeffut.multiview.telemetry.Telemetry.capture(
                            fr.zeffut.multiview.telemetry.EventNames.EVT_MERGE_PHASE_COMPLETED,
                            java.util.Map.of(
                                    "phase", phaseName[0],
                                    "duration_ms", (now - phaseStart[0]) / 1_000_000L));
                }
                phaseName[0] = phase;
                phaseStart[0] = now;
                Minecraft.getInstance().execute(() ->
                        source.sendFeedback(Component.literal("[MultiView] " + phase)));
            };

            try {
                MergeReport report = MergeOrchestrator.run(options, progress);
                if (phaseName[0] != null) {
                    fr.zeffut.multiview.telemetry.Telemetry.capture(
                            fr.zeffut.multiview.telemetry.EventNames.EVT_MERGE_PHASE_COMPLETED,
                            java.util.Map.of("phase", phaseName[0],
                                    "duration_ms", (System.nanoTime() - phaseStart[0]) / 1_000_000L));
                }
                Path destZip = dest.resolveSibling(dest.getFileName() + ".zip");
                long outBytes = java.nio.file.Files.exists(destZip)
                        ? java.nio.file.Files.size(destZip) : -1L;

                java.util.Map<String, Object> done = new java.util.HashMap<>();
                done.put("duration_ms", (System.nanoTime() - startNanos) / 1_000_000L);
                done.put("output_bytes", outBytes);
                done.put("source_count", sources.size());
                done.put("entities_merged_uuid", report.stats.entitiesMergedByUuid);
                done.put("entities_merged_heuristic", report.stats.entitiesMergedByHeuristic);
                done.put("entities_ambiguous", report.stats.entitiesAmbiguousMerged);
                done.put("blocks_overwrites", report.stats.blocksLwwOverwrites);
                done.put("blocks_conflicts", report.stats.blocksLwwConflicts);
                done.put("globals_deduped", report.stats.globalPacketsDeduped);
                done.put("merged_total_ticks", report.mergedTotalTicks);
                done.put("alignment_strategy", String.valueOf(report.alignmentStrategy));
                done.put("warnings", report.warnings.size());
                fr.zeffut.multiview.telemetry.Telemetry.capture(
                        fr.zeffut.multiview.telemetry.EventNames.EVT_MERGE_COMPLETED, done);

                Minecraft.getInstance().execute(() ->
                        source.sendFeedback(Component.literal(String.format(
                                "[MultiView] Done → %s | %d entities merged, %d blocks overwritten, %d globals deduped.",
                                destZip.toAbsolutePath(),
                                report.stats.entitiesMergedByUuid + report.stats.entitiesMergedByHeuristic,
                                report.stats.blocksLwwOverwrites,
                                report.stats.globalPacketsDeduped))));
            } catch (Throwable t) {
                fr.zeffut.multiview.telemetry.Telemetry.capture(
                        fr.zeffut.multiview.telemetry.EventNames.EVT_MERGE_FAILED,
                        java.util.Map.of(
                                "phase", String.valueOf(phaseName[0]),
                                "error_type", t.getClass().getSimpleName(),
                                "error_message",
                                    fr.zeffut.multiview.telemetry.Sanitizer.redactMessage(
                                        String.valueOf(t.getMessage())),
                                "duration_ms", (System.nanoTime() - startNanos) / 1_000_000L,
                                "source_count", sources.size()));
                LOG.error("[MultiView] Merge failed", t);
                Minecraft.getInstance().execute(() ->
                        source.sendError(Component.literal("[MultiView] Merge failed: " + t.getMessage())));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Sum of source sizes (file size for zips, recursive for folders); -1 on error. */
    private static long totalSize(List<Path> sources) {
        try {
            long total = 0;
            for (Path s : sources) {
                if (Files.isRegularFile(s)) {
                    total += Files.size(s);
                } else if (Files.isDirectory(s)) {
                    try (Stream<Path> w = Files.walk(s)) {
                        total += w.filter(Files::isRegularFile).mapToLong(p -> {
                            try { return Files.size(p); } catch (Exception e) { return 0L; }
                        }).sum();
                    }
                }
            }
            return total;
        } catch (Exception e) {
            return -1L;
        }
    }
}
