package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.Action;
import fr.zeffut.multiview.format.ActionType;
import fr.zeffut.multiview.format.FlashbackByteBuf;
import fr.zeffut.multiview.format.FlashbackReader;
import fr.zeffut.multiview.format.FlashbackReplay;
import fr.zeffut.multiview.format.PacketEntry;
import fr.zeffut.multiview.format.SegmentReader;
import fr.zeffut.multiview.format.SegmentWriter;
import fr.zeffut.multiview.format.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

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

        // 7. Init EgoRouter — registry from primary source's first segment (Task 16)
        List<String> egoRegistry = extractRegistryFromFirstSegment(replays.get(primaryIdx));
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

        // 1. Extract registry from primary source's first segment → main stream registry
        List<String> mainRegistry = extractRegistryFromFirstSegment(
                ctx.sources.get(ctx.primarySourceIdx));
        SegmentWriter mainWriter = new SegmentWriter("c0.flashback", mainRegistry);
        // Phase 3: empty snapshot — all content goes to the live stream
        mainWriter.endSnapshot();

        int nextTickOrdinal    = mainRegistry.indexOf(ActionType.NEXT_TICK);
        int gamePacketOrdinal  = mainRegistry.indexOf(ActionType.GAME_PACKET);
        int cacheRefOrdinal    = mainRegistry.indexOf(ActionType.CACHE_CHUNK);

        // 2. One cursor per source, priority-queue ordered by (tickAbs, sourceIdx)
        record SourceCursor(int sourceIdx, Iterator<PacketEntry> it, PacketEntry head) {}

        List<Stream<PacketEntry>> streams = new ArrayList<>();
        PriorityQueue<SourceCursor> pq = new PriorityQueue<>(
                Comparator.comparingInt((SourceCursor c) ->
                        ctx.toAbsTick(c.sourceIdx(), c.head().tick()))
                        .thenComparingInt(SourceCursor::sourceIdx));

        for (int i = 0; i < ctx.sources.size(); i++) {
            FlashbackReplay replay = ctx.sources.get(i);
            Stream<PacketEntry> stream = FlashbackReader.stream(replay);
            streams.add(stream);
            Iterator<PacketEntry> it = stream.iterator();
            if (it.hasNext()) {
                pq.add(new SourceCursor(i, it, it.next()));
            }
        }

        int tickAbsEmitted = 0;
        long processed = 0;
        int lastPurge = 0;

        while (!pq.isEmpty()) {
            SourceCursor cur = pq.poll();
            int tickAbs = ctx.toAbsTick(cur.sourceIdx(), cur.head().tick());

            // Intercalate NextTick actions up to tickAbs
            while (tickAbsEmitted < tickAbs) {
                tickAbsEmitted++;
                if (nextTickOrdinal >= 0) {
                    mainWriter.writeLiveAction(nextTickOrdinal, new byte[0]);
                }
            }

            Category cat = classifier.classify(cur.head().action());
            switch (cat) {
                case TICK -> {
                    // NextTick already intercalated above; nothing more to emit
                }
                case CONFIG -> {
                    // Emit once across all sources — dedup by content hash (packetTypeId=-1)
                    byte[] payload = ActionType.encode(cur.head().action());
                    if (globalDeduper.shouldEmit(-1, tickAbs, payload)) {
                        writeActionToMain(mainWriter, mainRegistry, cur.head().action(), ctx.report);
                    }
                }
                case LOCAL_PLAYER -> {
                    // Only from the primary source; other sources dropped.
                    // Known limitation: no AddPlayer transform — deferred to Phase 4.
                    if (cur.sourceIdx() == ctx.primarySourceIdx) {
                        writeActionToMain(mainWriter, mainRegistry, cur.head().action(), ctx.report);
                    }
                }
                case WORLD -> {
                    // Passthrough — no LWW merge in Phase 3, deferred to Phase 4.
                    writeActionToMain(mainWriter, mainRegistry, cur.head().action(), ctx.report);
                }
                case ENTITY -> {
                    // Passthrough — no entity dedup in Phase 3, deferred to Phase 4.
                    writeActionToMain(mainWriter, mainRegistry, cur.head().action(), ctx.report);
                }
                case PASSTHROUGH -> {
                    writeActionToMain(mainWriter, mainRegistry, cur.head().action(), ctx.report);
                }
                case EGO -> {
                    // Route to per-player ego segment
                    byte[] payload = ActionType.encode(cur.head().action());
                    UUID playerUuid = UUID.fromString(
                            ctx.sources.get(cur.sourceIdx()).metadata().uuid());
                    egoRouter.writeEgo(playerUuid, tickAbs, payload);
                }
                case GLOBAL -> {
                    if (cur.head().action() instanceof Action.GamePacket gp) {
                        int pid = PacketClassifier.readPacketId(gp.bytes());
                        if (globalDeduper.shouldEmit(pid, tickAbs, gp.bytes())) {
                            if (gamePacketOrdinal >= 0) {
                                mainWriter.writeLiveAction(gamePacketOrdinal, gp.bytes());
                            }
                        } else {
                            ctx.report.stats.globalPacketsDeduped++;
                        }
                    }
                    // Non-GamePacket GLOBAL (unlikely but safe to passthrough)
                    else {
                        writeActionToMain(mainWriter, mainRegistry, cur.head().action(), ctx.report);
                    }
                }
                case CACHE_REF -> {
                    if (cur.head().action() instanceof Action.CacheChunkRef ref
                            && cacheRefOrdinal >= 0) {
                        int globalIdx = cacheRemapper.remap(cur.sourceIdx(), ref.cacheIndex());
                        byte[] newPayload = writeVarIntToBytes(globalIdx);
                        mainWriter.writeLiveAction(cacheRefOrdinal, newPayload);
                    }
                }
            }

            // Periodic purge
            if (tickAbs - lastPurge > 100) {
                entityMerger.purge(tickAbs);
                globalDeduper.purgeOlderThan(tickAbs - 200);
                lastPurge = tickAbs;
            }

            // Advance cursor
            if (cur.it().hasNext()) {
                pq.add(new SourceCursor(cur.sourceIdx(), cur.it(), cur.it().next()));
            }

            processed++;
            if (processed % 10_000 == 0) {
                progress.accept(String.format("Streaming merge... tick %d / %d",
                        tickAbs, ctx.report.mergedTotalTicks));
            }
        }

        // 3. Close all source streams
        for (var s : streams) {
            s.close();
        }

        // 4. Finish writer and write c0.flashback
        ByteBuf mainBytes = mainWriter.finish();
        try {
            Files.write(destTmp.resolve("c0.flashback"), ByteBufUtil.getBytes(mainBytes));
        } finally {
            mainBytes.release();
        }

        // 5. Finalize ego segments
        egoRouter.finishAll();
        ctx.report.stats.egoTracks = new ArrayList<>();
        for (var uuid : egoRouter.egoPlayers()) {
            ctx.report.stats.egoTracks.add(uuid.toString());
        }

        // Update entity merger stats
        ctx.report.stats.entitiesMergedByUuid       = entityMerger.uuidMergedCount();
        ctx.report.stats.entitiesMergedByHeuristic  = entityMerger.heuristicMergedCount();
        ctx.report.stats.entitiesAmbiguousMerged    = entityMerger.ambiguousMergedCount();
    }

    /**
     * Writes an Action to the main SegmentWriter by resolving its type id in the registry.
     * If the action's type id is absent from the registry, the action is skipped and a
     * warning counter is incremented.
     *
     * <p>Uses {@link ActionType#idOf(Action)} to obtain the canonical type string and
     * {@link ActionType#encode(Action)} to obtain the payload bytes.
     */
    private static void writeActionToMain(SegmentWriter writer, List<String> registry,
                                          Action action, MergeReport report) throws IOException {
        String typeId = ActionType.idOf(action);
        int ordinal = registry.indexOf(typeId);
        if (ordinal < 0) {
            // Unknown action type id — not present in the primary source's registry.
            // This can happen for action types introduced by other sources or mods.
            // Track in passthroughPackets map and skip.
            report.stats.passthroughPackets.merge(typeId, 1, Integer::sum);
            return;
        }
        byte[] payload = ActionType.encode(action);
        writer.writeLiveAction(ordinal, payload);
    }

    /**
     * Encodes {@code value} as a standard Minecraft VarInt (little-endian 7-bit groups).
     */
    private static byte[] writeVarIntToBytes(int value) {
        ByteBuf tmp = Unpooled.buffer(5);
        try {
            VarInts.writeVarInt(tmp, value);
            byte[] out = new byte[tmp.readableBytes()];
            tmp.readBytes(out);
            return out;
        } finally {
            tmp.release();
        }
    }

    /**
     * Reads the registry from the first segment of {@code replay}.
     * Returns the registry list as declared in the segment header.
     *
     * <p>If the replay has no segments (empty metadata.chunks), returns a minimal
     * static list containing the known action type ids.
     */
    private static List<String> extractRegistryFromFirstSegment(FlashbackReplay replay)
            throws IOException {
        List<Path> segments = replay.segmentPaths();
        if (segments.isEmpty()) {
            // Fallback: minimal registry for Phase 3 — covers all known action types
            return List.of(
                    ActionType.NEXT_TICK,
                    ActionType.CONFIGURATION,
                    ActionType.GAME_PACKET,
                    ActionType.CREATE_PLAYER,
                    ActionType.MOVE_ENTITIES,
                    ActionType.CACHE_CHUNK,
                    ActionType.VOICE_CHAT,
                    ActionType.ENCODED_VOICE_CHAT
            );
        }
        Path firstSegment = segments.get(0);
        byte[] bytes = Files.readAllBytes(firstSegment);
        FlashbackByteBuf buf = new FlashbackByteBuf(Unpooled.wrappedBuffer(bytes));
        SegmentReader reader = new SegmentReader(firstSegment.getFileName().toString(), buf);
        return reader.registry();
    }
}
