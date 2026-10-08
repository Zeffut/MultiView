package fr.zeffut.multiview.merge;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Generated metadata-only replay fixtures: smoke of the real opening/alignment rejection path. */
class TimelineAlignmentDiagnosticTest {
    @BeforeAll
    static void initMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void zipRejectionNamesOriginalReplayNotExtractionDirectory(@TempDir Path tmp) throws Exception {
        Path valid = metadataZip(tmp.resolve("known-start.zip"), "2026-01-01T00:00:00");
        Path invalid = metadataZip(tmp.resolve("Jour_4_Romani_.zip"), "custom-name");
        Path destination = tmp.resolve("merged");
        var error = assertThrows(IllegalArgumentException.class, () -> MergeOrchestrator.run(
                new MergeOptions(List.of(valid, invalid), destination, Map.of(), false), phase -> {}));
        assertTrue(error.getMessage().startsWith("Source '" + invalid + "' :"), error.getMessage());
        assertTrue(error.getMessage().contains("metadata.name='custom-name'"));
        assertFalse(error.getMessage().contains("multiview-source-"));
        assertFalse(Files.exists(tmp.resolve("merged.zip")));
        assertFalse(Files.exists(tmp.resolve("merged.zip.part")));
    }

    private static Path metadataZip(Path zip, String name) throws Exception {
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("metadata.json"));
            out.write(("{\"name\":\"" + name + "\",\"total_ticks\":100,\"chunks\":{}}")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }
}
