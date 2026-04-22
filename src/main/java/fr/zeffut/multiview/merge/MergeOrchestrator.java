package fr.zeffut.multiview.merge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import fr.zeffut.multiview.format.Action;
import fr.zeffut.multiview.format.ActionType;
import fr.zeffut.multiview.format.FlashbackByteBuf;
import fr.zeffut.multiview.format.FlashbackMetadata;
import fr.zeffut.multiview.format.FlashbackReader;
import fr.zeffut.multiview.format.FlashbackReplay;
import fr.zeffut.multiview.format.PacketEntry;
import fr.zeffut.multiview.format.SegmentReader;
import fr.zeffut.multiview.format.SegmentWriter;
import fr.zeffut.multiview.format.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.PlayPackets;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.registry.DynamicRegistryManager;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Orchestre le merge de N replays en 1 sortie. Streaming k-way merge.
 *
 * <p>Invocation typique (depuis MergeCommand) :
 * <pre>MergeOrchestrator.run(options, progressCallback);</pre>
 *
 * @implNote Non thread-safe. Use a fresh instance per merge.
 */
public final class MergeOrchestrator {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(MergeOrchestrator.class.getName());

    private MergeOrchestrator() {}

    /**
     * @param options paramètres de merge (sources, destination, overrides)
     * @param progress callback(phaseName) pour feedback UI
     * @return MergeReport final
     */
    public static MergeReport run(MergeOptions options, Consumer<String> progress)
            throws IOException {
        MergeReport report = new MergeReport();

        // destTmp declared before try so the catch block can clean it up
        Path destTmp = null;
        List<Path> tempExtractDirs = new ArrayList<>();

        try {
            // Check destination before opening sources
            // The actual output is destFinal + ".zip"; destFinal is used only as base name.
            Path destFinal = options.destination();
            Path destZipCheck = destFinal.resolveSibling(destFinal.getFileName() + ".zip");
            if (Files.exists(destZipCheck)) {
                if (options.force()) {
                    Files.delete(destZipCheck);
                } else {
                    throw new IOException("Destination already exists: " + destZipCheck
                            + ". Use --force (not yet exposed via CLI).");
                }
            }

            // 1. Open sources — extract zips to temp folders since FlashbackReader reads folders
            progress.accept("Ouverture des sources...");
            List<FlashbackReplay> replays = new ArrayList<>();
            for (Path src : options.sources()) {
                Path sourceToOpen = src;
                if (Files.isRegularFile(src) && src.getFileName().toString().endsWith(".zip")) {
                    Path tempDir = Files.createTempDirectory("multiview-source-");
                    tempExtractDirs.add(tempDir);
                    try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                            Files.newInputStream(src))) {
                        java.util.zip.ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            Path out = tempDir.resolve(entry.getName());
                            if (entry.isDirectory()) {
                                Files.createDirectories(out);
                            } else {
                                Files.createDirectories(out.getParent());
                                Files.copy(zis, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    }
                    sourceToOpen = tempDir;
                }
                replays.add(FlashbackReader.open(sourceToOpen));
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

            // 3. Primary source: the one that STARTS FIRST (smallest tickOffset).
            // Rationale: primary provides the initial snapshot at tick 0 of the merged
            // timeline. If primary had a non-zero tickOffset, the client would render
            // primary's snapshot but see only secondary packets until primary "joined" —
            // causing visual glitches (stuck camera, wrong chunks) during the early ticks.
            // Ties broken by largest totalTicks.
            int primaryIdx = 0;
            int primaryOffset = alignment.tickOffsets()[0];
            int primaryLen = replays.get(0).metadata().totalTicks();
            for (int i = 1; i < replays.size(); i++) {
                int offset = alignment.tickOffsets()[i];
                int len = replays.get(i).metadata().totalTicks();
                if (offset < primaryOffset
                        || (offset == primaryOffset && len > primaryLen)) {
                    primaryIdx = i;
                    primaryOffset = offset;
                    primaryLen = len;
                }
            }

            // 4. Build context + mergers
            MergeContext ctx = new MergeContext(replays, alignment.tickOffsets(),
                    alignment.mergedStartTick(), primaryIdx, report);
            SourcePovTracker povTracker = new SourcePovTracker(replays.size());
            IdRemapper idRemapper = new IdRemapper();
            EntityMerger entityMerger = new EntityMerger(povTracker, idRemapper);
            EntityPacketRewriter entityRewriter = new EntityPacketRewriter(
                    entityMerger, idRemapper, povTracker, replays.size());
            WorldStateMerger worldMerger = new WorldStateMerger();
            WorldPacketRewriter worldRewriter = new WorldPacketRewriter(worldMerger);
            GlobalDeduper globalDeduper = new GlobalDeduper();
            CacheRemapper cacheRemapper = new CacheRemapper();
            PacketClassifier classifier = new PacketClassifier(
                    GamePacketDispatch.buildOrFallback(report));
            SecondaryPlayerSynthesizer secondarySynth = new SecondaryPlayerSynthesizer(idRemapper);

            // 5. Atomic staging
            destTmp = destFinal.resolveSibling("." + destFinal.getFileName() + ".tmp");
            Files.createDirectories(destTmp);

            // 6. Copy caches from ALL sources into merged level_chunk_caches/, renumbering files
            // sequentially. Primary's files stay at their original indices (0, 1, …).
            // Secondary sources' files follow, offset by the number of files from prior sources.
            progress.accept("Copying caches from all sources...");
            Path destCacheDir = destTmp.resolve("level_chunk_caches");
            Files.createDirectories(destCacheDir);
            int[] fileOffset = new int[replays.size()];
            int nextGlobalFileIdx = 0;

            // Copy order: primary first, then others in sourceIdx order (preserves primary indices)
            List<Integer> copyOrder = new ArrayList<>();
            copyOrder.add(primaryIdx);
            for (int i = 0; i < replays.size(); i++) {
                if (i != primaryIdx) copyOrder.add(i);
            }

            for (int i : copyOrder) {
                fileOffset[i] = nextGlobalFileIdx;
                Path srcCacheDir = replays.get(i).folder().resolve("level_chunk_caches");
                if (!Files.isDirectory(srcCacheDir)) continue;
                List<Path> cacheFiles;
                try (var entries = Files.list(srcCacheDir)) {
                    cacheFiles = entries
                            .filter(p -> {
                                try { Integer.parseInt(p.getFileName().toString()); return true; }
                                catch (NumberFormatException e) { return false; }
                            })
                            .sorted(Comparator.comparingInt(p -> Integer.parseInt(p.getFileName().toString())))
                            .collect(Collectors.toList());
                }
                for (Path file : cacheFiles) {
                    int localFileIdx = Integer.parseInt(file.getFileName().toString());
                    int globalFileIdx = fileOffset[i] + localFileIdx;
                    Files.copy(file, destCacheDir.resolve(Integer.toString(globalFileIdx)));
                    nextGlobalFileIdx = Math.max(nextGlobalFileIdx, globalFileIdx + 1);
                }
            }
            report.stats.chunkCachesConcatenated = nextGlobalFileIdx;

            // 7. Stream merge
            progress.accept("Streaming merge...");
            Map<String, Integer> segmentDurations = streamMerge(
                    ctx, classifier, worldMerger, worldRewriter, entityMerger, entityRewriter,
                    idRemapper, globalDeduper, cacheRemapper, povTracker, secondarySynth,
                    fileOffset, destTmp, progress);

            // Step A: Write merged metadata.json
            progress.accept("Écriture metadata.json...");
            FlashbackMetadata primaryMeta = replays.get(primaryIdx).metadata();
            FlashbackMetadata mergedMeta = buildMergedMetadata(
                    primaryMeta, replays, alignment.mergedTotalTicks(),
                    destFinal.getFileName().toString(), segmentDurations,
                    alignment.tickOffsets());
            try (var w = Files.newBufferedWriter(destTmp.resolve("metadata.json"))) {
                mergedMeta.toJson(w);
            }

            // Step B: Copy icon.png from primary source
            Path iconSrc = replays.get(primaryIdx).folder().resolve("icon.png");
            if (Files.exists(iconSrc)) {
                Files.copy(iconSrc, destTmp.resolve("icon.png"));
            }

            // Step C: Write merge-report.json
            try (var w = Files.newBufferedWriter(destTmp.resolve("merge-report.json"))) {
                new GsonBuilder().setPrettyPrinting().create().toJson(report, w);
            }

            // Step D: Package .tmp/ → destFinal.zip
            Path destZip = destFinal.resolveSibling(destFinal.getFileName() + ".zip");
            if (Files.exists(destZip)) {
                if (options.force()) {
                    Files.delete(destZip);
                } else {
                    throw new IOException("Destination already exists: " + destZip
                            + ". Pass --force to overwrite.");
                }
            }
            progress.accept("Packaging zip...");
            zipDirectory(destTmp, destZip);
            // Zip succeeded — clean up tmp dir
            deleteRecursively(destTmp);
            destTmp = null; // don't delete on catch

            // Clean up any zip-extracted source temp dirs
            for (Path td : tempExtractDirs) {
                try { deleteRecursively(td); } catch (IOException ignore) {}
            }

            progress.accept("Merge terminé → " + destZip.getFileName());
            return report;

        } catch (IOException | RuntimeException e) {
            report.error(e.getClass().getSimpleName() + ": " + e.getMessage());
            if (destTmp != null && Files.exists(destTmp)) {
                deleteRecursively(destTmp);
            }
            // Rollback partial zip if it exists
            Path destZip = options.destination().resolveSibling(
                    options.destination().getFileName() + ".zip");
            try {
                Files.deleteIfExists(destZip);
            } catch (IOException ignore) {}
            // Clean up zip-extracted source temp dirs
            for (Path td : tempExtractDirs) {
                try { deleteRecursively(td); } catch (IOException ignore) {}
            }
            throw e;
        }
    }

    /** Deletes a directory tree recursively, ignoring individual deletion errors. */
    private static void deleteRecursively(Path path) throws IOException {
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignore) {}
                    });
        }
    }

    /**
     * Packages the contents of {@code srcDir} into a zip archive at {@code destZip}.
     * <p>
     * Compression rules:
     * <ul>
     *   <li>{@code c0.flashback} (and any {@code *.flashback}) — STORED (already dense binary)</li>
     *   <li>Everything else — DEFLATED</li>
     * </ul>
     * Internal paths are relative to {@code srcDir} (entries at zip root).
     * Empty directories are added as entries ending with {@code /}.
     * Files are streamed to avoid loading large binaries in memory.
     */
    private static void zipDirectory(Path srcDir, Path destZip) throws IOException {
        byte[] buf = new byte[65536];
        try (OutputStream fos = Files.newOutputStream(destZip);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            try (var walk = Files.walk(srcDir)) {
                // Sort for deterministic order; directories first via natural path order
                var paths = walk.sorted().toList();
                for (Path p : paths) {
                    if (p.equals(srcDir)) continue; // skip root itself

                    String entryName = srcDir.relativize(p).toString().replace('\\', '/');
                    if (Files.isDirectory(p)) {
                        // Add directory entry (must end with '/')
                        ZipEntry dirEntry = new ZipEntry(entryName + "/");
                        zos.putNextEntry(dirEntry);
                        zos.closeEntry();
                    } else {
                        boolean stored = entryName.endsWith(".flashback");
                        ZipEntry entry = new ZipEntry(entryName);
                        if (stored) {
                            // STORED requires size + crc set upfront
                            long size = Files.size(p);
                            entry.setMethod(ZipEntry.STORED);
                            entry.setSize(size);
                            entry.setCompressedSize(size);
                            // Compute CRC32 in a first pass
                            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                            try (var in = Files.newInputStream(p)) {
                                int n;
                                while ((n = in.read(buf)) != -1) crc.update(buf, 0, n);
                            }
                            entry.setCrc(crc.getValue());
                            zos.putNextEntry(entry);
                            try (var in = Files.newInputStream(p)) {
                                int n;
                                while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
                            }
                        } else {
                            entry.setMethod(ZipEntry.DEFLATED);
                            zos.putNextEntry(entry);
                            try (var in = Files.newInputStream(p)) {
                                int n;
                                while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
                            }
                        }
                        zos.closeEntry();
                    }
                }
            }
        }
    }

    /**
     * Builds a merged {@link FlashbackMetadata} by constructing a JSON object and
     * deserializing it via {@link FlashbackMetadata#fromJson(java.io.Reader)}.
     * <p>
     * Fields sourced from primary replay metadata; chunks aggregated from the provided
     * segment-duration map (insertion-ordered); markers and customNamespacesForRegistries
     * start empty.
     *
     * @param segmentDurations ordered map of segment filename → duration in ticks
     */
    private static FlashbackMetadata buildMergedMetadata(
            FlashbackMetadata primary,
            List<FlashbackReplay> replays,
            int mergedTotalTicks,
            String destName,
            Map<String, Integer> segmentDurations,
            int[] tickOffsets) {

        Map<String, Object> chunksMap = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : segmentDurations.entrySet()) {
            Map<String, Object> segMap = new LinkedHashMap<>();
            segMap.put("duration", e.getValue());
            segMap.put("forcePlaySnapshot", false);
            chunksMap.put(e.getKey(), segMap);
        }

        // Aggregate markers from all sources, offsetting each by the source's tickOffset
        // so they land on the merged timeline. Tick collisions are resolved by appending
        // a small disambiguation (the first one wins per tick, later ones shifted +1).
        JsonObject markersJson = new JsonObject();
        java.util.Set<Integer> usedTicks = new java.util.HashSet<>();
        for (int i = 0; i < replays.size(); i++) {
            int offset = tickOffsets[i];
            for (var me : replays.get(i).metadata().markers().entrySet()) {
                int tick = me.getKey() + offset;
                while (usedTicks.contains(tick)) tick++; // disambiguate collisions
                usedTicks.add(tick);
                JsonObject markerObj = new JsonObject();
                markerObj.addProperty("colour", me.getValue().colour());
                markerObj.addProperty("description", me.getValue().description());
                markersJson.add(Integer.toString(tick), markerObj);
            }
        }

        // Build JSON representation using Gson's JsonObject to avoid reflection
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                .setPrettyPrinting().disableHtmlEscaping().create();

        JsonObject obj = new JsonObject();
        obj.addProperty("uuid", UUID.randomUUID().toString());
        obj.addProperty("name", destName);
        obj.addProperty("version_string", primary.versionString());
        obj.addProperty("world_name", primary.worldName());
        obj.addProperty("data_version", primary.dataVersion());
        obj.addProperty("protocol_version", primary.protocolVersion());
        obj.addProperty("bobby_world_name", primary.bobbyWorldName());
        obj.addProperty("total_ticks", mergedTotalTicks);
        obj.add("markers", markersJson);
        obj.add("customNamespacesForRegistries", new JsonObject()); // required by Flashback to list the replay
        obj.add("chunks", gson.toJsonTree(chunksMap));

        String json = gson.toJson(obj);
        return FlashbackMetadata.fromJson(new StringReader(json));
    }

    /** Number of ticks per output segment (~5 min of gameplay at 20 ticks/s). */
    private static final int SEGMENT_TICKS = 6000;

    /** CRC32C content hash for dedup comparisons. */
    private static long contentHash(byte[] payload) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(payload, 0, payload.length);
        return crc.getValue();
    }

    /**
     * Performs the streaming k-way merge and writes cN.flashback segment files to destTmp.
     *
     * @return ordered map of segment filename → duration in ticks (insertion order = c0, c1, …)
     */
    private static Map<String, Integer> streamMerge(
                                    MergeContext ctx, PacketClassifier classifier,
                                    WorldStateMerger worldMerger, WorldPacketRewriter worldRewriter,
                                    EntityMerger entityMerger,
                                    EntityPacketRewriter entityRewriter, IdRemapper idRemapper,
                                    GlobalDeduper globalDeduper, CacheRemapper cacheRemapper,
                                    SourcePovTracker povTracker,
                                    SecondaryPlayerSynthesizer secondarySynth,
                                    int[] fileOffset, Path destTmp,
                                    Consumer<String> progress) throws IOException {

        // Segment durations: insertion-ordered, populated as each segment is closed.
        Map<String, Integer> segmentDurations = new LinkedHashMap<>();

        // 1. Extract registry from primary source's first segment → main stream registry
        List<String> mainRegistry = extractRegistryFromFirstSegment(
                ctx.sources.get(ctx.primarySourceIdx));

        // Helper to open a new segment writer with the proper snapshot content.
        // c0 gets the full primary snapshot; subsequent segments get an empty snapshot
        // (sequential playback from c0 snapshot is sufficient; random-seek to mid-segment
        // is a known limitation).
        SegmentWriter currentWriter = new SegmentWriter("c0.flashback", mainRegistry);

        // Copy snapshot section of primary source's first segment into our snapshot.
        // Flashback requires init packets (dimension setup, registries, local player,
        // initial chunks, etc.) to be present in the snapshot; without them the world
        // opens empty.
        int snapshotActionsCopied = 0;
        List<Path> primarySegments = ctx.sources.get(ctx.primarySourceIdx).segmentPaths();
        if (!primarySegments.isEmpty()) {
            Path firstSeg = primarySegments.get(0);
            byte[] firstSegBytes = Files.readAllBytes(firstSeg);
            FlashbackByteBuf firstSegBuf = new FlashbackByteBuf(
                    io.netty.buffer.Unpooled.wrappedBuffer(firstSegBytes));
            SegmentReader snapshotReader = new SegmentReader(
                    firstSeg.getFileName().toString(), firstSegBuf);
            while (snapshotReader.hasNext() && snapshotReader.isPeekInSnapshot()) {
                SegmentReader.RawAction raw = snapshotReader.nextRaw();
                currentWriter.writeSnapshotAction(raw.ordinal(), raw.payload());
                snapshotActionsCopied++;
            }
        }
        progress.accept(String.format("Transferred %d snapshot actions from primary source's first segment",
                snapshotActionsCopied));
        currentWriter.endSnapshot();

        // Open streaming file for c0.flashback — avoids accumulating the entire live stream
        // in a single in-memory ByteBuf (which would hit Netty's Integer.MAX_VALUE array limit
        // for long replays). Live actions are written directly to disk from this point on.
        int currentSegmentIdx = 0;
        int currentSegmentStart = 0;
        currentWriter.openStreamingFile(destTmp.resolve("c0.flashback"));

        int nextTickOrdinal    = mainRegistry.indexOf(ActionType.NEXT_TICK);
        int gamePacketOrdinal  = mainRegistry.indexOf(ActionType.GAME_PACKET);
        int cacheRefOrdinal    = mainRegistry.indexOf(ActionType.CACHE_CHUNK);

        // -------------------------------------------------------------------------
        // 0.2.2: secondary POV always-visible synthesis state
        // -------------------------------------------------------------------------
        // For each secondary source, we emit one PlayerInfoUpdate(ADD_PLAYER) + AddEntity
        // on the first CreateLocalPlayer we observe. Subsequent accurate_player_position
        // payloads from that source are rewritten to target the fake entity ID.
        // `secondarySpawned[sourceIdx]` : spawn packets already emitted
        // `secondaryFakeEntityId[sourceIdx]` : global entity ID allocated for the fake player
        // `secondaryLocalEntityId[sourceIdx]` : real local entity ID (discovered from first
        //                                      accurate_player_position payload), registered
        //                                      with the IdRemapper so subsequent entity-tracking
        //                                      packets referencing this ID are remapped.
        boolean[] secondarySpawned = new boolean[ctx.sources.size()];
        int[] secondaryFakeEntityId = new int[ctx.sources.size()];
        int[] secondaryLocalEntityId = new int[ctx.sources.size()];
        java.util.Arrays.fill(secondaryFakeEntityId, -1);
        java.util.Arrays.fill(secondaryLocalEntityId, -1);

        // Numeric ID of PLAYER_POSITION packet, for translating secondary EGO teleports
        int idPlayerPosition = -1;
        int idForgetLevelChunk = -1;
        int idRemoveEntities = -1;
        int idLevelChunkWithLight = -1;
        try {
            idPlayerPosition = GamePacketDispatch.findId(PlayPackets.PLAYER_POSITION);
            idForgetLevelChunk = GamePacketDispatch.findId(PlayPackets.FORGET_LEVEL_CHUNK);
            idRemoveEntities = GamePacketDispatch.findId(PlayPackets.REMOVE_ENTITIES);
            idLevelChunkWithLight = GamePacketDispatch.findId(PlayPackets.LEVEL_CHUNK_WITH_LIGHT);
        } catch (Throwable t) {
            LOG.warning("MergeOrchestrator: could not resolve special packet ids: "
                    + t.getClass().getSimpleName());
        }
        // Track which chunks have already been emitted, keyed by content hash.
        // Prevents multiple POVs from overwriting the same chunk state on playback.
        java.util.Set<Long> emittedChunkHashes = new java.util.HashSet<>();

        // 2. One cursor per source, priority-queue ordered by (tickAbs, sourceIdx)
        record SourceCursor(int sourceIdx, Iterator<PacketEntry> it, PacketEntry head) {}

        List<Stream<PacketEntry>> streams = new ArrayList<>();
        PriorityQueue<SourceCursor> pq = new PriorityQueue<>(
                Comparator.comparingInt((SourceCursor c) ->
                        ctx.toAbsTick(c.sourceIdx(), c.head().tick()))
                        .thenComparingInt(SourceCursor::sourceIdx));

        try {
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
            boolean warnedOutOfOrder = false;

            while (!pq.isEmpty()) {
                SourceCursor cur = pq.poll();
                int tickAbs = ctx.toAbsTick(cur.sourceIdx(), cur.head().tick());

                // Skip snapshot actions — they were already written into our snapshot section
                // above (for the primary source) or should not be duplicated in the live stream.
                // Do this BEFORE intercalating NextTick actions: snapshot entries have tick==baseTick
                // (typically 0) which would otherwise trigger spurious NextTick intercalation.
                if (cur.head().inSnapshot()) {
                    // Advance cursor and continue without emitting
                    if (cur.it().hasNext()) {
                        pq.add(new SourceCursor(cur.sourceIdx(), cur.it(), cur.it().next()));
                    }
                    processed++;
                    continue;
                }

                // Check for out-of-order packets (clock desync between sources or bad offset)
                if (tickAbs < tickAbsEmitted) {
                    if (!warnedOutOfOrder) {
                        ctx.report.warn(String.format(
                                "Out-of-order packet at tick %d (already emitted tick %d) from source %d",
                                tickAbs, tickAbsEmitted, cur.sourceIdx()));
                        warnedOutOfOrder = true;
                    }
                    ctx.report.stats.outOfOrderPackets++;
                }

                // Segment boundary check: if we've accumulated SEGMENT_TICKS ticks in the current
                // segment, close it and open the next one before intercalating more NextTicks.
                // tickAbsEmitted tracks global ticks; segment duration = tickAbsEmitted - currentSegmentStart.
                // We check against tickAbs (the incoming absolute tick) so we roll over before
                // emitting NextTicks that would belong to the new segment.
                if (tickAbsEmitted - currentSegmentStart >= SEGMENT_TICKS) {
                    int segDuration = tickAbsEmitted - currentSegmentStart;
                    String segName = "c" + currentSegmentIdx + ".flashback";
                    currentWriter.finishStreaming();
                    segmentDurations.put(segName, segDuration);

                    // Open next segment; seed snapshot from primary source's segment
                    // covering this tick boundary so Flashback seek doesn't show a void world.
                    currentSegmentIdx++;
                    currentSegmentStart = tickAbsEmitted;
                    String nextSegName = "c" + currentSegmentIdx + ".flashback";
                    currentWriter = new SegmentWriter(nextSegName, mainRegistry);
                    copyPrimarySnapshotForTick(ctx.sources.get(ctx.primarySourceIdx),
                            currentWriter, currentSegmentStart, mainRegistry, ctx.report);
                    currentWriter.endSnapshot();
                    currentWriter.openStreamingFile(destTmp.resolve(nextSegName));
                }

                // Intercalate NextTick actions up to tickAbs
                while (tickAbsEmitted < tickAbs) {
                    tickAbsEmitted++;
                    if (nextTickOrdinal >= 0) {
                        currentWriter.writeLiveAction(nextTickOrdinal, new byte[0]);
                    }
                    // Check segment boundary mid-intercalation as well: if we just crossed
                    // a SEGMENT_TICKS boundary while filling in missing ticks, roll over.
                    if (tickAbsEmitted - currentSegmentStart >= SEGMENT_TICKS && tickAbsEmitted < tickAbs) {
                        int segDuration = tickAbsEmitted - currentSegmentStart;
                        String segName = "c" + currentSegmentIdx + ".flashback";
                        currentWriter.finishStreaming();
                        segmentDurations.put(segName, segDuration);

                        currentSegmentIdx++;
                        currentSegmentStart = tickAbsEmitted;
                        String nextSegName = "c" + currentSegmentIdx + ".flashback";
                        currentWriter = new SegmentWriter(nextSegName, mainRegistry);
                        copyPrimarySnapshotForTick(ctx.sources.get(ctx.primarySourceIdx),
                                currentWriter, currentSegmentStart, mainRegistry, ctx.report);
                        currentWriter.endSnapshot();
                        currentWriter.openStreamingFile(destTmp.resolve(nextSegName));
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
                            writeActionToMain(currentWriter, mainRegistry, cur.head().action(), ctx.report);
                        }
                    }
                    case LOCAL_PLAYER -> {
                        // Flashback has a SINGLE local-player slot. Primary's CreateLocalPlayer
                        // and accurate_player_position_optional are emitted as-is.
                        //
                        // 0.2.2: secondary POVs are made visible as regular player entities:
                        //   1. On first CreateLocalPlayer from a secondary source, synthesize
                        //      PlayerInfoUpdate(ADD_PLAYER) + AddEntity(PLAYER type) packets
                        //      so the client knows about this player and has an entity to
                        //      position.
                        //   2. For subsequent accurate_player_position_optional actions from
                        //      that source, rewrite the encoded entityId to target the fake
                        //      global ID — Flashback's AccurateEntityPositionHandler will then
                        //      apply the position/angle to the fake entity every tick.
                        if (cur.head().action() instanceof Action.CreatePlayer cp) {
                            entityRewriter.recordLocalPlayerUuid(cur.sourceIdx(), cp.bytes());
                            if (cur.sourceIdx() == ctx.primarySourceIdx) {
                                writeActionToMain(currentWriter, mainRegistry,
                                        cur.head().action(), ctx.report);
                            } else if (!secondarySpawned[cur.sourceIdx()]
                                    && secondarySynth.isAvailable()
                                    && gamePacketOrdinal >= 0) {
                                // Synthesize PlayerInfoUpdate(ADD_PLAYER) + AddEntity(PLAYER)
                                UUID uuid = SecondaryPlayerSynthesizer.extractUuid(cp.bytes());
                                com.mojang.authlib.GameProfile profile =
                                        SecondaryPlayerSynthesizer.extractGameProfile(cp.bytes());
                                byte[] infoPayload = secondarySynth.synthesizePlayerInfoUpdate(profile);
                                byte[] addEntityPayload = secondarySynth.synthesizeAddEntity(
                                        cur.sourceIdx(), uuid != null ? uuid : profile.id());
                                int fakeId = secondarySynth.getFakeEntityId(cur.sourceIdx());
                                if (infoPayload != null && addEntityPayload != null && fakeId >= 0) {
                                    currentWriter.writeLiveAction(gamePacketOrdinal, infoPayload);
                                    currentWriter.writeLiveAction(gamePacketOrdinal, addEntityPayload);
                                    secondarySpawned[cur.sourceIdx()] = true;
                                    secondaryFakeEntityId[cur.sourceIdx()] = fakeId;
                                } else {
                                    LOG.warning("MergeOrchestrator: secondary player synthesis "
                                            + "failed for source " + cur.sourceIdx()
                                            + " — player will remain invisible.");
                                }
                            }
                        } else if (cur.head().action() instanceof Action.Unknown u
                                && ("flashback:action/accurate_player_position_optional".equals(u.id())
                                    || "flashback:action/accurate_player_position".equals(u.id()))) {
                            if (cur.sourceIdx() == ctx.primarySourceIdx) {
                                writeActionToMain(currentWriter, mainRegistry,
                                        cur.head().action(), ctx.report);
                            } else if (secondarySpawned[cur.sourceIdx()]
                                    && secondaryFakeEntityId[cur.sourceIdx()] >= 0) {
                                // Register the real local entity ID ↔ fake global ID mapping
                                // on first sight so subsequent entity packets from this source
                                // that reference the secondary's local-player ID get remapped
                                // through the standard IdRemapper path.
                                int localEid = SecondaryPlayerSynthesizer
                                        .readAccuratePositionEntityId(u.payload());
                                if (localEid >= 0 && secondaryLocalEntityId[cur.sourceIdx()] < 0) {
                                    secondaryLocalEntityId[cur.sourceIdx()] = localEid;
                                    idRemapper.assignExisting(cur.sourceIdx(), localEid,
                                            secondaryFakeEntityId[cur.sourceIdx()]);
                                }
                                byte[] rewritten = SecondaryPlayerSynthesizer
                                        .rewriteAccuratePositionEntityId(
                                                u.payload(),
                                                secondaryFakeEntityId[cur.sourceIdx()]);
                                writeActionToMain(currentWriter, mainRegistry,
                                        new Action.Unknown(u.id(), rewritten), ctx.report);
                            }
                            // else: secondary player not yet spawned (CreateLocalPlayer not seen)
                            // — drop silently, the client has no entity to position yet.
                        } else {
                            // Other LOCAL_PLAYER actions (future-proofing): primary only.
                            if (cur.sourceIdx() == ctx.primarySourceIdx) {
                                writeActionToMain(currentWriter, mainRegistry,
                                        cur.head().action(), ctx.report);
                            }
                        }
                    }
                    case WORLD -> {
                        // Filters applied:
                        //   1. FORGET_LEVEL_CHUNK from secondary: drop (primary-only).
                        //   2. LEVEL_CHUNK_WITH_LIGHT: dedup by content hash across all
                        //      sources — avoids multiple POVs overwriting the same chunk
                        //      with competing states.
                        if (cur.head().action() instanceof Action.GamePacket gp) {
                            int pid = PacketClassifier.readPacketId(gp.bytes());
                            if (cur.sourceIdx() != ctx.primarySourceIdx
                                    && idForgetLevelChunk >= 0
                                    && pid == idForgetLevelChunk) {
                                // drop secondary unload
                            } else if (idLevelChunkWithLight >= 0
                                    && pid == idLevelChunkWithLight) {
                                long hash = contentHash(gp.bytes());
                                if (emittedChunkHashes.add(hash)) {
                                    writeActionToMain(currentWriter, mainRegistry,
                                            cur.head().action(), ctx.report);
                                }
                                // else drop duplicate chunk from another source
                            } else {
                                writeActionToMain(currentWriter, mainRegistry,
                                        cur.head().action(), ctx.report);
                            }
                        } else {
                            writeActionToMain(currentWriter, mainRegistry, cur.head().action(), ctx.report);
                        }
                    }
                    case ENTITY -> {
                        // Phase 4.D (re-enabled): decode entity packets, remap source-local
                        // entity IDs to merged global IDs via EntityMerger/IdRemapper.
                        //
                        // REMOVE_ENTITIES from secondary sources is still dropped (primary-only)
                        // — another POV may still see the entity. With N POVs, any source that
                        // temporarily loses sight of a mob/player triggers a despawn that the
                        // merged stream would propagate, making entities flicker in and out.
                        // This filter runs BEFORE the rewrite so we do not waste work on
                        // packets we will drop anyway.
                        if (cur.head().action() instanceof Action.GamePacket gp) {
                            int pid = PacketClassifier.readPacketId(gp.bytes());
                            if (cur.sourceIdx() != ctx.primarySourceIdx
                                    && idRemoveEntities >= 0
                                    && pid == idRemoveEntities) {
                                // drop secondary despawns
                            } else {
                                byte[] rewritten = entityRewriter.rewrite(
                                        cur.sourceIdx(), tickAbs, gp.bytes());
                                if (gamePacketOrdinal >= 0) {
                                    currentWriter.writeLiveAction(gamePacketOrdinal, rewritten);
                                }
                            }
                        } else if (cur.head().action() instanceof Action.MoveEntities me) {
                            // MoveEntities: update POV tracker, then passthrough.
                            // TODO Phase 4.E: remap entity IDs within MoveEntities payload.
                            entityRewriter.processMoveEntities(
                                    cur.sourceIdx(), tickAbs, me.bytes());
                            writeActionToMain(currentWriter, mainRegistry, cur.head().action(), ctx.report);
                        } else {
                            writeActionToMain(currentWriter, mainRegistry, cur.head().action(), ctx.report);
                        }
                    }
                    case PASSTHROUGH -> {
                        writeActionToMain(currentWriter, mainRegistry, cur.head().action(), ctx.report);
                    }
                    case EGO -> {
                        // Primary only: routes HUD packets to main stream (Flashback handles natively).
                        // Secondary EGOs are generally dropped (they'd corrupt primary's HUD state).
                        //
                        // Exception (0.2.2): secondary's PLAYER_POSITION is translated to a
                        // TeleportEntity packet targeting the secondary's fake player entity,
                        // so large teleports / respawns show up on the fake entity.
                        // Regular movement is already covered by the per-tick rewrite of
                        // accurate_player_position_optional above.
                        if (cur.sourceIdx() == ctx.primarySourceIdx) {
                            writeActionToMain(currentWriter, mainRegistry, cur.head().action(), ctx.report);
                        } else if (secondarySpawned[cur.sourceIdx()]
                                && secondaryFakeEntityId[cur.sourceIdx()] >= 0
                                && idPlayerPosition >= 0
                                && cur.head().action() instanceof Action.GamePacket gp
                                && PacketClassifier.readPacketId(gp.bytes()) == idPlayerPosition) {
                            byte[] teleport = tryTranslatePlayerPositionToTeleport(
                                    gp.bytes(), secondaryFakeEntityId[cur.sourceIdx()],
                                    secondarySynth);
                            if (teleport != null && gamePacketOrdinal >= 0) {
                                currentWriter.writeLiveAction(gamePacketOrdinal, teleport);
                            }
                            // else: drop silently — per-tick accurate_player_position_optional
                            // will sync the position on the next tick.
                        }
                        // else: drop secondary EGO.
                    }
                    case GLOBAL -> {
                        if (cur.head().action() instanceof Action.GamePacket gp) {
                            int pid = PacketClassifier.readPacketId(gp.bytes());
                            if (globalDeduper.shouldEmit(pid, tickAbs, gp.bytes())) {
                                if (gamePacketOrdinal >= 0) {
                                    currentWriter.writeLiveAction(gamePacketOrdinal, gp.bytes());
                                }
                            } else {
                                ctx.report.stats.globalPacketsDeduped++;
                            }
                        }
                        // Non-GamePacket GLOBAL (unlikely but safe to passthrough)
                        else {
                            writeActionToMain(currentWriter, mainRegistry, cur.head().action(), ctx.report);
                        }
                    }
                    case CACHE_REF -> {
                        // Remap the cache index from source-local space to merged global space.
                        // fileOffset[sourceIdx] holds the number of cache files before this source's
                        // files in the merged level_chunk_caches/ directory.
                        if (cur.head().action() instanceof Action.CacheChunkRef ref
                                && cacheRefOrdinal >= 0) {
                            int localIdx     = ref.cacheIndex();
                            int localFileIdx = localIdx / 10000;
                            int localEntry   = localIdx % 10000;
                            int globalFileIdx = fileOffset[cur.sourceIdx()] + localFileIdx;
                            int globalIdx     = globalFileIdx * 10000 + localEntry;
                            byte[] newPayload = writeVarIntToBytes(globalIdx);
                            currentWriter.writeLiveAction(cacheRefOrdinal, newPayload);
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

            // Close the last (or only) segment
            String lastSegName = "c" + currentSegmentIdx + ".flashback";
            int lastSegDuration = tickAbsEmitted - currentSegmentStart;
            currentWriter.finishStreaming();
            segmentDurations.put(lastSegName, lastSegDuration);

            // Update entity merger stats
            ctx.report.stats.entitiesMergedByUuid       = entityMerger.uuidMergedCount();
            ctx.report.stats.entitiesMergedByHeuristic  = entityMerger.heuristicMergedCount();
            ctx.report.stats.entitiesAmbiguousMerged    = entityMerger.ambiguousMergedCount();
            // Update block LWW stats
            ctx.report.stats.blocksLwwConflicts   = worldMerger.lwwConflicts();
            ctx.report.stats.blocksLwwOverwrites  = worldMerger.lwwOverwrites();
        } finally {
            // 3. Close all source streams — guaranteed on any exception path
            for (Stream<PacketEntry> s : streams) {
                try {
                    s.close();
                } catch (Exception ignore) {
                }
            }
        }

        return segmentDurations;
    }

    /**
     * Copies snapshot actions from the primary source segment whose cumulative tick range
     * contains {@code absTick} into {@code dest}.
     *
     * <p>Primary source segments are matched by iterating {@code primary.metadata().chunks()}
     * and accumulating their durations. The first segment whose range [segStart, segStart+duration)
     * contains {@code absTick} is opened. If {@code absTick} falls beyond all primary segments
     * (primary ended before this point), we use the last primary segment as a best-effort
     * approximation.
     *
     * <p>Registry mismatch between the primary segment and {@code destRegistry} is handled by
     * mapping each action's ordinal through the primary segment's own registry to obtain the
     * type-id string, then looking it up in {@code destRegistry}. Actions with no mapping are
     * silently dropped (their count is tracked in the report).
     *
     * @param primary       the primary source replay
     * @param dest          the new output segment writer (snapshot not yet closed)
     * @param absTick       the absolute merged tick at which the new segment starts
     * @param destRegistry  the merged output registry
     * @param report        merge report for tracking dropped snapshot actions
     */
    private static void copyPrimarySnapshotForTick(
            FlashbackReplay primary,
            SegmentWriter dest,
            int absTick,
            List<String> destRegistry,
            MergeReport report) {

        List<Path> segments = primary.segmentPaths();
        if (segments.isEmpty()) return;

        // Walk primary's chunks metadata to find which segment covers absTick.
        // metadata().chunks() returns an insertion-ordered map of segmentName → ChunkInfo.
        int segStart = 0;
        int targetSegmentIndex = segments.size() - 1; // fallback: last segment
        int chunkIdx = 0;
        for (Map.Entry<String, FlashbackMetadata.ChunkInfo> entry
                : primary.metadata().chunks().entrySet()) {
            int duration = entry.getValue().duration();
            if (absTick < segStart + duration) {
                // absTick falls within this segment's range
                targetSegmentIndex = chunkIdx;
                break;
            }
            segStart += duration;
            chunkIdx++;
            // If chunkIdx exceeds segments.size(), the loop exits and we use the last segment
            if (chunkIdx >= segments.size()) {
                targetSegmentIndex = segments.size() - 1;
                break;
            }
        }

        Path segPath = segments.get(targetSegmentIndex);
        int copiedCount = 0;
        int droppedCount = 0;
        try {
            byte[] segBytes = Files.readAllBytes(segPath);
            FlashbackByteBuf segBuf = new FlashbackByteBuf(
                    io.netty.buffer.Unpooled.wrappedBuffer(segBytes));
            SegmentReader reader = new SegmentReader(segPath.getFileName().toString(), segBuf);
            List<String> primaryRegistry = reader.registry();

            while (reader.hasNext() && reader.isPeekInSnapshot()) {
                SegmentReader.RawAction raw = reader.nextRaw();
                // Map ordinal from primary segment's registry to dest registry via type-id string
                String typeId = (raw.ordinal() >= 0 && raw.ordinal() < primaryRegistry.size())
                        ? primaryRegistry.get(raw.ordinal())
                        : null;
                if (typeId == null) {
                    droppedCount++;
                    continue;
                }
                int destOrdinal = destRegistry.indexOf(typeId);
                if (destOrdinal < 0) {
                    // Type not present in merged registry — drop silently
                    droppedCount++;
                    continue;
                }
                dest.writeSnapshotAction(destOrdinal, raw.payload());
                copiedCount++;
            }
        } catch (IOException e) {
            report.warn("copyPrimarySnapshotForTick: failed to read segment "
                    + segPath.getFileName() + " for absTick=" + absTick
                    + " (" + e.getMessage() + "). Snapshot will be empty for this segment.");
            return;
        }

        if (droppedCount > 0) {
            report.warn("copyPrimarySnapshotForTick: dropped " + droppedCount
                    + " snapshot actions (registry mismatch) for absTick=" + absTick
                    + " (segment " + segPath.getFileName() + ", copied=" + copiedCount + ")");
        }
        // Capture into final locals for use in lambda (compiler requires effectively-final)
        final int finalCopied = copiedCount;
        final int finalDropped = droppedCount;
        final int finalSegIdx = targetSegmentIndex;
        LOG.fine(() -> "copyPrimarySnapshotForTick: absTick=" + absTick
                + " → segment[" + finalSegIdx + "]=" + segPath.getFileName()
                + " copied=" + finalCopied + " dropped=" + finalDropped);
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
     * Attempts to translate a {@code PlayerPositionLookS2CPacket} payload into a
     * {@code TeleportEntity} (EntityPositionS2CPacket) payload targeting
     * {@code fakeEntityId}.
     *
     * <p>PLAYER_POSITION uses relative-position flags that describe which components
     * are relative to the current position vs. absolute.  In the merge pipeline we
     * do not have the client's previous position, so any packet with relative flags
     * cannot be safely converted — we fall through and let the per-tick
     * {@code accurate_player_position_optional} sync handle the next frame.
     *
     * <p>Returns {@code null} on decode failure, fallback mode, or if relative flags
     * are set — callers should silently drop the packet in that case.
     */
    private static byte[] tryTranslatePlayerPositionToTeleport(
            byte[] playerPositionPayload, int fakeEntityId,
            SecondaryPlayerSynthesizer synth) {
        if (!synth.isAvailable() || fakeEntityId < 0) return null;
        try {
            io.netty.buffer.ByteBuf raw =
                    io.netty.buffer.Unpooled.wrappedBuffer(playerPositionPayload);
            VarInts.readVarInt(raw); // skip packetId
            PacketByteBuf pbuf = new PacketByteBuf(raw);
            PlayerPositionLookS2CPacket decoded = PlayerPositionLookS2CPacket.CODEC.decode(pbuf);
            // Skip if any relative flag is set — absolute position unknown.
            if (!decoded.relatives().isEmpty()) return null;
            net.minecraft.entity.EntityPosition pos = decoded.change();
            net.minecraft.util.math.Vec3d v = pos.position();
            return synth.synthesizeTeleport(fakeEntityId,
                    v.x, v.y, v.z,
                    pos.yaw(), pos.pitch());
        } catch (Throwable t) {
            // Decode failure is non-fatal; caller will drop.
            return null;
        }
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
