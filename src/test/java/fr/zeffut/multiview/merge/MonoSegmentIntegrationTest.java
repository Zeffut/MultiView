package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class MonoSegmentIntegrationTest {
    private static final String MARK = "test:action/marker";
    private static final List<String> REGISTRY = List.of(ActionType.NEXT_TICK, MARK);
    @Test void mergesTwoMultisegmentSourcesWithoutChangingLiveChronology(@TempDir Path tmp) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Path a = source(tmp.resolve("a"), 0), b = source(tmp.resolve("b"), 1);
        Path out = tmp.resolve("out");
        MergeReport report = MergeOrchestrator.run(new MergeOptions(List.of(a,b), out, Map.of(), false), s -> {});
        assertEquals(12002, report.mergedTotalTicks);
        try (FileSystem zip = FileSystems.newFileSystem(Path.of(out + ".zip"))) {
            FlashbackReplay replay = FlashbackReader.open(zip.getPath("/"));
            assertEquals(1, replay.segmentPaths().size());
            assertEquals(12002, replay.metadata().chunks().get("c0.flashback").duration());
            List<PacketEntry> entries;
            try (Stream<PacketEntry> stream = FlashbackReader.stream(replay)) { entries = stream.toList(); }
            assertTrue(entries.stream().noneMatch(e -> e.inSnapshot() && e.action() instanceof Action.NextTick));
            assertEquals(12002, entries.stream().filter(e -> !e.inSnapshot() && e.action() instanceof Action.NextTick).count());
            List<String> actual = entries.stream().filter(e -> e.action() instanceof Action.Unknown && !e.inSnapshot())
                    .map(e -> e.tick()+":"+((Action.Unknown)e.action()).payload()[0]).toList();
            List<String> expected = new ArrayList<>();
            for (int tick=0; tick<12002; tick++) { expected.add(tick+":0"); expected.add(tick+":1"); }
            assertEquals(expected, actual);
        }
    }
    @Test void realCorpusPrototype() throws Exception {
        String root = System.getenv("MULTIVIEW_PROTOTYPE_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null);
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Path dir = Path.of(root);
        Path a = dir.resolve("MULTIPOV-PovAlpha.zip"), b = dir.resolve("MULTIPOV-PovBeta.zip");
        Path out = dir.resolve("PROTOTYPE-MONOSEGMENT");
        MergeReport report = MergeOrchestrator.run(new MergeOptions(List.of(a,b), out, Map.of(), true), System.out::println);
        assertTrue(report.errors.isEmpty(), report.errors.toString());
        try (FileSystem zip = FileSystems.newFileSystem(Path.of(out + ".zip"))) {
            FlashbackReplay replay = FlashbackReader.open(zip.getPath("/"));
            assertEquals(1, replay.segmentPaths().size());
            assertEquals(report.mergedTotalTicks, replay.metadata().chunks().get("c0.flashback").duration());
            try (Stream<PacketEntry> stream = FlashbackReader.stream(replay)) {
                assertEquals(report.mergedTotalTicks, stream.filter(e -> !e.inSnapshot() && e.action() instanceof Action.NextTick).count());
            }
        }
    }
    @Test void realCorpusLongDisjointPrototype() throws Exception {
        String root = System.getenv("MULTIVIEW_PROTOTYPE_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null);
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Path dir = Path.of(root);
        Path a = dir.resolve("MULTIPOV-PovAlpha.zip"), b = dir.resolve("MULTIPOV-PovBeta.zip");
        Path out = dir.resolve("PROTOTYPE-MONOSEGMENT-LONG");
        MergeReport report = MergeOrchestrator.run(new MergeOptions(List.of(a,b), out,
                Map.of(b.getFileName().toString(), 8000), true), System.out::println);
        assertTrue(report.errors.isEmpty(), report.errors.toString());
        assertTrue(report.mergedTotalTicks > 8000, "Original ZIP filename override must apply");
        try (FileSystem zip = FileSystems.newFileSystem(Path.of(out + ".zip"))) {
            FlashbackReplay replay = FlashbackReader.open(zip.getPath("/"));
            assertEquals(1, replay.segmentPaths().size());
            assertEquals(report.mergedTotalTicks, replay.metadata().chunks().get("c0.flashback").duration());
            int playerInfoCount = PlayerInfoCodecCorpusTest.validateReplay(replay);
            System.out.println("PIU codec validated merged long: " + playerInfoCount);
            assertTrue(playerInfoCount > 0);
            try (Stream<PacketEntry> stream = FlashbackReader.stream(replay)) {
                assertEquals(report.mergedTotalTicks, stream.filter(e -> !e.inSnapshot() && e.action() instanceof Action.NextTick).count());
            }
        }
    }
    private static Path source(Path dir, int id) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("metadata.json"), """
            {"uuid":"00000000-0000-0000-0000-00000000000%d", "name":"2026-01-01T00_00_00",
             "version_string":"26.2", "data_version":4903, "protocol_version":776, "total_ticks":12002,
             "chunks":{"c0.flashback":{"duration":6001},"c1.flashback":{"duration":6001}}}
            """.formatted(id+1));
        for (int part=0;part<2;part++) {
            SegmentWriter w = new SegmentWriter("c"+part+".flashback", REGISTRY);
            w.endSnapshot(); w.openStreamingFile(dir.resolve("c"+part+".flashback"));
            for (int i=0;i<6001;i++) { w.writeLiveAction(1,new byte[]{(byte)id}); w.writeLiveAction(0,new byte[0]); }
            w.finishStreaming();
        }
        return dir;
    }
}
