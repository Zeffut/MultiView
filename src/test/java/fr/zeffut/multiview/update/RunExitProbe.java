package fr.zeffut.multiview.update;

import java.nio.file.Path;

/** Separate JVM entry point: deliberately never calls applyAll. */
public final class RunExitProbe {
    public static void main(String[] args) throws Exception {
        System.setProperty("multiview.telemetry", "false");
        System.setProperty("autoupdate.enabled", args[1]);
        Path root = Path.of(args[0]);
        UpdateService service = new UpdateService(root.resolve("mods"), root.resolve(".autoupdate"),
                "fabric", "26.3", "lifecycle-test");
        var run = UpdateService.class.getDeclaredMethod("run");
        run.setAccessible(true);
        run.invoke(service);
    }
}
