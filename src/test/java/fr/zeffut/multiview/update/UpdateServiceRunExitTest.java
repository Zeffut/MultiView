package fr.zeffut.multiview.update;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Real run -> normal JVM exit, not direct reconciliation/applyAll test calls. */
class UpdateServiceRunExitTest {
    @TempDir Path dir;

    @Test void cleanupOnlyResumesWithoutNewJars() throws Exception { exercise("true", false); }
    @Test void stagedSwapResumesWithoutNewJars() throws Exception { exercise("true", true); }
    @Test void disabledUpdaterDoesNotSwap() throws Exception { exercise("false", false); }
    @Test void persistedDisabledSurvivesTelemetryShutdown() throws Exception { exercise("config-off", false); }
    @Test void recoveredWorkIsArmedBeforeBlockingNetworkScan() throws Exception { exercise("blocked-scan", false); }

    private void exercise(String mode, boolean staged) throws Exception {
        boolean enabled = !mode.equals("false") && !mode.equals("config-off");
        Path mods = Files.createDirectories(dir.resolve("mods"));
        Path work = Files.createDirectories(dir.resolve(".autoupdate"));
        Path old = mods.resolve("old.jar"), target = mods.resolve("target.jar"), stage = work.resolve("stage.jar");
        Files.writeString(old, "old-usable");
        if (staged) Files.writeString(stage, "target-usable");
        else Files.writeString(target, "target-usable");
        Map<String, Object> pending = new HashMap<>(Map.of("project_id", "test", "slug", "test", "from", "1", "to", "2",
                "old", old.toString(), "target", target.toString()));
        pending.put("staged", staged ? stage.toString() : null);
        Files.writeString(work.resolve("state.json"), new Gson().toJson(Map.of("pending", List.of(pending))));
        String cp = List.of(RunExitProbe.class, UpdateService.class, Gson.class, Logger.class).stream()
                .map(c -> { try { return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
                    catch (Exception e) { throw new RuntimeException(e); } })
                .distinct().collect(Collectors.joining(java.io.File.pathSeparator));
        for (int attempt = 0; attempt < 3; attempt++) {
            Path log = dir.resolve("jvm-" + attempt + ".log");
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", cp, RunExitProbe.class.getName(), dir.toString(), mode)
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean exited = child.waitFor(30, TimeUnit.SECONDS);
            if (!exited) child.destroyForcibly();
            assertTrue(exited, "child timed out");
            assertEquals(0, child.exitValue(), Files.readString(log));
            assertEquals(!enabled, Files.exists(old), Files.readString(log));
            assertEquals("target-usable", Files.readString(target));
        }
    }
}
