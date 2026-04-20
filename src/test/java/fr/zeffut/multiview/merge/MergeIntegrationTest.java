package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

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

        // Output must be a zip file (no folder)
        Path destZip = dest.resolveSibling(dest.getFileName() + ".zip");
        assertFalse(Files.exists(dest), "Temp folder must not remain after merge");
        assertTrue(Files.exists(destZip), "Merged zip must exist at " + destZip);

        // Verify required zip entries
        try (ZipFile zf = new ZipFile(destZip.toFile())) {
            Set<String> entries = zf.stream()
                    .map(java.util.zip.ZipEntry::getName)
                    .collect(Collectors.toSet());

            assertTrue(entries.contains("metadata.json"), "metadata.json missing from zip");
            assertTrue(entries.contains("merge-report.json"), "merge-report.json missing from zip");
            assertTrue(entries.stream().anyMatch(e -> e.matches("c\\d+\\.flashback")),
                    "At least one *.flashback segment expected in zip");
            assertTrue(entries.stream().anyMatch(e -> e.startsWith("level_chunk_caches")),
                    "level_chunk_caches directory/entries expected in zip");

            // Verify metadata.json content is readable and has expected total ticks
            var metaEntry = zf.getEntry("metadata.json");
            assertNotNull(metaEntry);
            String metaJson;
            try (var in = zf.getInputStream(metaEntry)) {
                metaJson = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertTrue(metaJson.contains("\"total_ticks\""), "metadata.json must contain total_ticks field");
        }
    }
}
