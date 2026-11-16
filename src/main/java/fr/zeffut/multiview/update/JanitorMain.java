package fr.zeffut.multiview.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Standalone shutdown helper. Only same-path atomic replacements are permitted;
 * historical cross-name jobs require manual intervention. Staging is retained for reconciliation.
 * Both checked SHA-512 v1 and legacy triple argument formats are accepted. */
public final class JanitorMain {

    public static void main(String[] args) throws Exception {
        boolean checked = args.length > 0 && "--sha512-v1".equals(args[0]);
        int first = checked ? 2 : 1, width = checked ? 4 : 3;
        if (args.length < first + width || (args.length - first) % width != 0) return;
        long parentPid = Long.parseLong(args[checked ? 1 : 0]);

        // Wait (up to 2 min) for the game process to exit and release its file locks.
        ProcessHandle.of(parentPid).ifPresent(handle -> {
            try {
                handle.onExit().get(2, java.util.concurrent.TimeUnit.MINUTES);
            } catch (Exception ignored) {
                // proceed anyway; deletes below retry on their own
            }
        });

        for (int i = first; i + 2 < args.length; i += width) {
            Path old = "-".equals(args[i]) ? null : Path.of(args[i]);
            Path staged = "-".equals(args[i + 1]) ? null : Path.of(args[i + 1]);
            Path target = "-".equals(args[i + 2]) ? null : Path.of(args[i + 2]);
            String expected = checked ? args[i + 3] : null;

            // Never publish a second filename, including through legacy invocations.
            if (old == null || target == null || !old.toAbsolutePath().normalize()
                    .equals(target.toAbsolutePath().normalize())) {
                System.err.println("[AutoUpdate] cross-name job requires manual update; preserved");
                continue;
            }
            if (staged == null || !Files.isRegularFile(staged)) continue;
            try {
                if (checked) install(staged, target, expected);
                else install(staged, target); // Legacy protocol has no original expected hash.
            } catch (Exception failure) {
                System.err.println("[AutoUpdate] atomic replacement failed; preserved: " + failure);
            }
        }
    }

    static void install(Path staged, Path target) throws Exception {
        install(staged, target, java.util.HexFormat.of().formatHex(hash(staged)));
    }

    static void install(Path staged, Path target, String expected) throws Exception {
        if (expected == null || !expected.equals(java.util.HexFormat.of().formatHex(hash(staged))))
            throw new java.io.IOException("Staged updater checksum mismatch");
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(),
                ".autoupdate-", ".tmp");
        try {
            Files.copy(staged, temporary, StandardCopyOption.REPLACE_EXISTING);
            try (var channel = java.nio.channels.FileChannel.open(temporary,
                    java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (!expected.equals(java.util.HexFormat.of().formatHex(hash(temporary)))) {
                throw new java.io.IOException("Updater copy integrity mismatch");
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] hash(Path path) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-512");
        try (var in = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return digest.digest();
    }

    private JanitorMain() {}
}
