package fr.zeffut.multiview.update;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import fr.zeffut.multiview.telemetry.*;

/** Separate JVM entry point: deliberately never calls applyAll. */
public final class RunExitProbe {
    public static void main(String[] args) throws Exception {
        System.setProperty("multiview.telemetry", "false");
        Path root = Path.of(args[0]);
        boolean configOff = args[1].equals("config-off");
        boolean blockedScan = args[1].equals("blocked-scan");
        if (configOff) {
            Path file = root.resolve("telemetry.json");
            Files.writeString(file, "{\"enabled\":false,\"settings\":{\"auto_update\":\"false\"}}");
            Telemetry.initForTest(TelemetryConfig.load(file), new TelemetryContext(Map.of(), "test"), new NoopSink());
        } else System.setProperty("autoupdate.enabled", blockedScan ? "true" : args[1]);
        CountDownLatch requested = new CountDownLatch(1);
        java.net.ServerSocket server = null;
        if (blockedScan) {
            server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
            System.setProperty("autoupdate.api", "http://127.0.0.1:" + server.getLocalPort());
            final var listening = server;
            Thread receiver = new Thread(() -> {
                try (var socket = listening.accept()) {
                    socket.getInputStream().read();
                    requested.countDown();
                    new CountDownLatch(1).await(); // deliberately never answer the scan
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            receiver.setDaemon(true);
            receiver.start();
        }
        UpdateService service = new UpdateService(root.resolve("mods"), root.resolve(".autoupdate"),
                "fabric", "26.3", "lifecycle-test");
        if (configOff) Telemetry.shutdown(); // config must not silently revert to default-on
        var run = UpdateService.class.getDeclaredMethod("run");
        run.setAccessible(true);
        if (blockedScan) {
            Files.writeString(root.resolve("mods/unrelated.jar"), "unrelated");
            Thread worker = new Thread(() -> {
                try { run.invoke(service); } catch (Exception e) { throw new RuntimeException(e); }
            });
            worker.setDaemon(true);
            worker.start();
            if (!requested.await(10, TimeUnit.SECONDS)) throw new AssertionError("scan did not reach loopback");
            // Normal JVM shutdown while the production run() daemon is still in HTTP I/O.
            System.exit(0);
        } else run.invoke(service);
    }
}
