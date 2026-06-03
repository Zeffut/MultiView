package fr.zeffut.multiview.telemetry;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Fires {@code session_heartbeat} every 15 min while the game runs. Daemon thread. */
public final class HeartbeatScheduler {
    private static final ScheduledExecutorService EXEC =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "multiview-telemetry-heartbeat");
                t.setDaemon(true);
                return t;
            });
    private static final AtomicInteger COUNT = new AtomicInteger();
    private static volatile boolean started = false;

    private HeartbeatScheduler() {}

    public static synchronized void start() {
        if (started) return;
        started = true;
        EXEC.scheduleAtFixedRate(() ->
                Telemetry.capture(EventNames.EVT_SESSION_HEARTBEAT,
                        Map.of("heartbeat_index", COUNT.incrementAndGet())),
                15, 15, TimeUnit.MINUTES);
    }
}
