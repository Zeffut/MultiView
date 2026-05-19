package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.MultiViewMod;
import fr.zeffut.multiview.format.FlashbackReader;
import fr.zeffut.multiview.format.FlashbackReplay;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pre-merge guard: verifies that the selected replays' recorded windows overlap on the
 * monotonic server gameTime axis.
 *
 * <p>Without overlap, merging produces well-known artefacts (entities frozen mid-air,
 * default rotations, equipment desync) because the two recordings cover disjoint slices
 * of server time and there are no shared anchor packets to reconcile entity state across
 * the gap. This validator is meant to power a hard UI block before such merges are
 * attempted.
 *
 * <p>Pure logic — no Minecraft client API references — so it stays testable from
 * plain JVM tests and runs identically on both the classic and modern UI variants.
 */
public final class OverlapValidator {

    private OverlapValidator() {}

    /** Per-replay first-segment size cap when probing for the SetTime anchor (OOM guard). */
    private static final long MAX_PROBE_SEGMENT_BYTES = 256L * 1024L * 1024L; // 256 MB

    public enum Status {
        /** All pairs share at least one gameTime tick. */
        OK,
        /** At least one pair has zero overlap on the gameTime axis. */
        NO_OVERLAP,
        /** A replay is missing a {@code ClientboundSetTimePacket} anchor — overlap undetermined. */
        UNKNOWN
    }

    public record Result(Status status, List<Path> offendingPair) {
        public static Result ok() { return new Result(Status.OK, List.of()); }
        public static Result noOverlap(Path a, Path b) {
            return new Result(Status.NO_OVERLAP, List.of(a, b));
        }
        public static Result unknown() { return new Result(Status.UNKNOWN, List.of()); }
    }

    private record Interval(Path path, long start, long end) {}

    /**
     * Probes each replay for its gameTime anchor + duration, then checks every pair for
     * a non-empty intersection on the gameTime axis.
     *
     * @param selected     replay folders the user wants to merge
     * @param idProvider   runtime packet-id mapping (must be the same instance the merge
     *                     would use, so that {@code ClientboundSetTimePacket} resolves to
     *                     the right id for the running MC version)
     * @return {@link Result#ok()} when every pair overlaps,
     *         {@link Result#noOverlap(Path, Path)} for the first disjoint pair we find,
     *         or {@link Result#unknown()} if any replay has no detectable SetTime anchor.
     */
    public static Result validate(List<Path> selected, PacketIdProvider idProvider) {
        if (selected == null || selected.size() < 2) return Result.ok();

        MultiViewMod.LOGGER.debug("[OverlapValidator] probing {} replays with setTimeId={}",
                selected.size(), idProvider.setTimePacketId());

        List<Interval> intervals = new ArrayList<>(selected.size());
        for (Path p : selected) {
            FileSystem zipFs = null;
            try {
                // Flashback stores replays as .zip files; FlashbackReader expects a folder.
                // Mount the zip as a NIO FileSystem so Files.isRegularFile / readAllBytes
                // work transparently on entries inside it.
                Path replayRoot;
                if (Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".zip")) {
                    zipFs = FileSystems.newFileSystem(p);
                    replayRoot = zipFs.getPath("/");
                } else {
                    replayRoot = p;
                }

                FlashbackReplay replay = FlashbackReader.open(replayRoot);
                // OOM guard: refuse to probe replays whose first segment is unreasonably
                // large. `findSetTimeAnchor` reads the segment fully into memory and we
                // only need the first ~1200 ticks of packet data; anything beyond
                // MAX_PROBE_SEGMENT_BYTES is either a recording artefact or a hostile file.
                if (!replay.segmentPaths().isEmpty()) {
                    long size = Files.size(replay.segmentPaths().get(0));
                    if (size > MAX_PROBE_SEGMENT_BYTES) {
                        MultiViewMod.LOGGER.warn("[OverlapValidator] {}: first segment is {} bytes (limit {}); returning UNKNOWN",
                                p.getFileName(), size, MAX_PROBE_SEGMENT_BYTES);
                        return Result.unknown();
                    }
                }
                Optional<TimelineAligner.SetTimeAnchor> anchor =
                        TimelineAligner.findSetTimeAnchor(replay, idProvider);
                if (anchor.isEmpty()) {
                    MultiViewMod.LOGGER.warn("[OverlapValidator] {}: no SetTime anchor in first 1200 ticks, falling back to UNKNOWN",
                            p.getFileName());
                    return Result.unknown();
                }

                long start = anchor.get().gameTime() - anchor.get().tickLocal();
                long end = start + replay.metadata().totalTicks();
                MultiViewMod.LOGGER.debug("[OverlapValidator] {}: interval=[{}, {}] ({} ticks)",
                        p.getFileName(), start, end, replay.metadata().totalTicks());
                intervals.add(new Interval(p, start, end));
            } catch (IOException e) {
                MultiViewMod.LOGGER.warn("[OverlapValidator] {}: IOException ({}), falling back to UNKNOWN",
                        p.getFileName(), e.getMessage());
                return Result.unknown();
            } finally {
                if (zipFs != null) {
                    try { zipFs.close(); } catch (IOException ignore) {}
                }
            }
        }

        for (int i = 0; i < intervals.size(); i++) {
            for (int j = i + 1; j < intervals.size(); j++) {
                Interval a = intervals.get(i);
                Interval b = intervals.get(j);
                long lo = Math.max(a.start(), b.start());
                long hi = Math.min(a.end(), b.end());
                if (lo >= hi) {
                    MultiViewMod.LOGGER.info("[OverlapValidator] blocking merge: {} and {} have disjoint gameTime ranges",
                            a.path().getFileName(), b.path().getFileName());
                    return Result.noOverlap(a.path(), b.path());
                }
            }
        }
        return Result.ok();
    }
}
