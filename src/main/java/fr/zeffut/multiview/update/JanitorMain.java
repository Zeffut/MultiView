package fr.zeffut.multiview.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Tiny standalone helper process ("janitor"), spawned by {@link UpdateService} at game shutdown
 * when an old mod jar cannot be deleted in-process (Windows keeps loaded jars locked until the
 * JVM dies). No Minecraft, no mod-loader, no telemetry — plain JDK only.
 *
 * <p>Usage: {@code java -cp <autoupdate.jar> fr.zeffut.multiview.update.JanitorMain <parentPid>
 * (<oldJar> <stagedJar> <targetJar>)...} — waits for the parent game process to exit, then for
 * each triple: deletes the old jar (with retries), COPIES the staged jar to its target (copy, not
 * move: the staged jar may be this process's own classpath and thus locked), and best-effort
 * deletes the staged leftover. Empty arguments ("-") skip the corresponding action.
 */
public final class JanitorMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || (args.length - 1) % 3 != 0) return;
        long parentPid = Long.parseLong(args[0]);

        // Wait (up to 2 min) for the game process to exit and release its file locks.
        ProcessHandle.of(parentPid).ifPresent(handle -> {
            try {
                handle.onExit().get(2, java.util.concurrent.TimeUnit.MINUTES);
            } catch (Exception ignored) {
                // proceed anyway; deletes below retry on their own
            }
        });

        for (int i = 1; i + 2 < args.length; i += 3) {
            Path old = "-".equals(args[i]) ? null : Path.of(args[i]);
            Path staged = "-".equals(args[i + 1]) ? null : Path.of(args[i + 1]);
            Path target = "-".equals(args[i + 2]) ? null : Path.of(args[i + 2]);

            if (old != null && !deleteWithRetries(old)) {
                // Old jar still locked: leave the staged file in place so the next game run can
                // retry the swap, and DO NOT copy the new jar (avoids duplicate mod ids).
                continue;
            }
            if (staged != null && target != null && Files.exists(staged)) {
                try {
                    Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);
                    deleteWithRetries(staged); // best-effort; leftover is cleaned next run
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean deleteWithRetries(Path p) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                Files.deleteIfExists(p);
                return true;
            } catch (Exception e) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private JanitorMain() {}
}
