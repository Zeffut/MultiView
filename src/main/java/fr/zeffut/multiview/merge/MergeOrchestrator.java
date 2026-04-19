package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.FlashbackReader;
import fr.zeffut.multiview.format.FlashbackReplay;
import fr.zeffut.multiview.format.PacketEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Orchestre le merge de N replays en 1 sortie. Streaming k-way merge.
 *
 * <p>Invocation typique (depuis MergeCommand) :
 * <pre>MergeOrchestrator.run(options, progressCallback);</pre>
 *
 * @implNote Non thread-safe. Use a fresh instance per merge.
 */
public final class MergeOrchestrator {

    private MergeOrchestrator() {}

    /**
     * @param options paramètres de merge (sources, destination, overrides)
     * @param progress callback(phaseName) pour feedback UI
     * @return MergeReport final
     */
    public static MergeReport run(MergeOptions options, Consumer<String> progress)
            throws IOException {
        MergeReport report = new MergeReport();

        // 1. Open sources
        progress.accept("Ouverture des sources...");
        List<FlashbackReplay> replays = new ArrayList<>();
        for (Path src : options.sources()) {
            replays.add(FlashbackReader.open(src));
        }

        // 2. Find anchors + align
        progress.accept("Alignement temporel...");
        PacketIdProvider idProvider = PacketIdProvider.minecraftRuntime();
        List<TimelineAligner.Source> alignSources = new ArrayList<>();
        for (int i = 0; i < replays.size(); i++) {
            FlashbackReplay r = replays.get(i);
            Optional<TimelineAligner.SetTimeAnchor> anchor;
            try {
                anchor = TimelineAligner.findSetTimeAnchor(r, idProvider);
            } catch (Throwable t) {
                anchor = Optional.empty();
                report.warn("Source " + r.folder().getFileName()
                        + " : SetTime detection failed (" + t.getClass().getSimpleName()
                        + "), fallback metadata.name");
            }
            alignSources.add(new TimelineAligner.Source(
                    r.folder().getFileName().toString(),
                    anchor,
                    r.metadata().name(),
                    r.metadata().totalTicks()));
        }
        TimelineAligner.AlignmentResult alignment = TimelineAligner.alignAll(
                alignSources, options.tickOverrides());
        report.alignmentStrategy = alignment.strategy();

        for (int i = 0; i < replays.size(); i++) {
            MergeReport.SourceInfo si = new MergeReport.SourceInfo();
            si.folder = replays.get(i).folder().getFileName().toString();
            si.uuid = replays.get(i).metadata().uuid();
            si.totalTicks = replays.get(i).metadata().totalTicks();
            si.tickOffset = alignment.tickOffsets()[i];
            report.sources.add(si);
        }
        report.mergedTotalTicks = alignment.mergedTotalTicks();

        // 3. Primary source (largest totalTicks)
        int primaryIdx = 0;
        int primaryLen = replays.get(0).metadata().totalTicks();
        for (int i = 1; i < replays.size(); i++) {
            if (replays.get(i).metadata().totalTicks() > primaryLen) {
                primaryIdx = i;
                primaryLen = replays.get(i).metadata().totalTicks();
            }
        }

        // 4. Build context + mergers
        MergeContext ctx = new MergeContext(replays, alignment.tickOffsets(),
                alignment.mergedStartTick(), primaryIdx, report);
        SourcePovTracker povTracker = new SourcePovTracker(replays.size());
        IdRemapper idRemapper = new IdRemapper();
        EntityMerger entityMerger = new EntityMerger(povTracker, idRemapper);
        WorldStateMerger worldMerger = new WorldStateMerger();
        GlobalDeduper globalDeduper = new GlobalDeduper();
        CacheRemapper cacheRemapper = new CacheRemapper();
        PacketClassifier classifier = new PacketClassifier(
                GamePacketDispatch.buildOrFallback(report));

        // 5. Atomic staging
        Path destFinal = options.destination();
        Path destTmp = destFinal.resolveSibling("." + destFinal.getFileName() + ".tmp");
        Files.createDirectories(destTmp);

        // 6. Concat caches
        progress.accept("Remapping caches...");
        List<Path> cacheDirs = new ArrayList<>();
        for (FlashbackReplay r : replays) {
            cacheDirs.add(r.folder().resolve("level_chunk_caches"));
        }
        cacheRemapper.concat(cacheDirs, destTmp.resolve("level_chunk_caches"));
        report.stats.chunkCachesConcatenated = cacheRemapper.concatenatedCount();

        // 7. Init EgoRouter (registry initiale = la même que nos segments source)
        // TODO Task 16: extract real registry from the first segment reader.
        List<String> egoRegistry = List.of("minecraft:game_packet");
        EgoRouter egoRouter = new EgoRouter(destTmp.resolve("ego"), egoRegistry);

        // 8. Stream merge (Task 16)
        progress.accept("Streaming merge...");
        streamMerge(ctx, classifier, worldMerger, entityMerger, idRemapper,
                egoRouter, globalDeduper, cacheRemapper, povTracker, destTmp, progress);

        // 9. TODO Task 17: finalize metadata + atomic rename
        progress.accept("Merge terminé.");
        return report;
    }

    private static void streamMerge(MergeContext ctx, PacketClassifier classifier,
                                    WorldStateMerger worldMerger, EntityMerger entityMerger,
                                    IdRemapper idRemapper, EgoRouter egoRouter,
                                    GlobalDeduper globalDeduper, CacheRemapper cacheRemapper,
                                    SourcePovTracker povTracker, Path destTmp,
                                    Consumer<String> progress) throws IOException {
        throw new UnsupportedOperationException("Task 16 implémente streamMerge");
    }
}
