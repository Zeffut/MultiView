package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.FlashbackReader;
import fr.zeffut.multiview.format.FlashbackReplay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MergeIntegrationTest {

    static final Path POV_A = Path.of("run/replay/2026-02-20T23_25_15");
    static final Path POV_B = Path.of("run/replay/Sénat_empirenapo2026-02-20T23_20_16");

    static boolean replaysAvailable() {
        return Files.isDirectory(POV_A) && Files.isDirectory(POV_B);
    }

    @Test
    @EnabledIf("replaysAvailable")
    void mergeTwoRealPovs(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("merged");
        MergeOptions options = new MergeOptions(
                List.of(POV_A, POV_B), dest, Map.of(), false);

        MergeReport report = MergeOrchestrator.run(options, msg -> System.out.println("[PROGRESS] " + msg));

        System.out.println("[REPORT] alignment=" + report.alignmentStrategy);
        System.out.println("[REPORT] merged total ticks=" + report.mergedTotalTicks);
        System.out.println("[REPORT] sources=" + report.sources.size());
        for (MergeReport.SourceInfo si : report.sources) {
            System.out.println("[REPORT]   " + si.folder + " ticks=" + si.totalTicks + " offset=" + si.tickOffset);
        }
        System.out.println("[REPORT] entities merged uuid=" + report.stats.entitiesMergedByUuid);
        System.out.println("[REPORT] entities merged heuristic=" + report.stats.entitiesMergedByHeuristic);
        System.out.println("[REPORT] entities ambiguous=" + report.stats.entitiesAmbiguousMerged);
        System.out.println("[REPORT] blocks LWW overwrites=" + report.stats.blocksLwwOverwrites);
        System.out.println("[REPORT] blocks LWW conflicts=" + report.stats.blocksLwwConflicts);
        System.out.println("[REPORT] globals deduped=" + report.stats.globalPacketsDeduped);
        System.out.println("[REPORT] ego tracks=" + report.stats.egoTracks);
        System.out.println("[REPORT] caches concat=" + report.stats.chunkCachesConcatenated);
        System.out.println("[REPORT] passthrough packets=" + report.stats.passthroughPackets);
        System.out.println("[REPORT] warnings=" + report.warnings);
        System.out.println("[REPORT] errors=" + report.errors);

        // Structural checks
        assertTrue(Files.exists(dest.resolve("metadata.json")));
        assertTrue(Files.exists(dest.resolve("merge-report.json")));
        assertTrue(Files.exists(dest.resolve("level_chunk_caches")));
        assertTrue(Files.exists(dest.resolve("ego")));
        // At least one main segment
        long segmentCount;
        try (var stream = Files.list(dest)) {
            segmentCount = stream
                    .filter(p -> p.getFileName().toString().matches("c\\d+\\.flashback"))
                    .count();
        }
        assertTrue(segmentCount >= 1, "Au moins un segment principal attendu");

        // Re-open and verify metadata
        FlashbackReplay mergedReplay = FlashbackReader.open(dest);
        assertEquals(report.mergedTotalTicks, mergedReplay.metadata().totalTicks());
    }
}
