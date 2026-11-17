package fr.zeffut.multiview.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic local jars/state only; never replays or live Modrinth/PostHog traffic. */
class UpdateServiceLifecycleTest {
    @TempDir Path dir;
    Path old() { return dir.resolve("old.jar"); }
    Path target() { return dir.resolve("new.jar"); }
    Path staged() { return dir.resolve("staging/new.jar"); }
    Path state() { return dir.resolve("work/state.json"); }
    UpdateService service() { return new UpdateService(dir, dir.resolve("work"), "fabric", "test", "test"); }
    void jar(Path path, String contents) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }
    void persist(boolean stage, boolean explicitNull) throws Exception {
        JsonObject p = new JsonObject();
        p.addProperty("project_id", "synthetic");
        p.addProperty("slug", "synthetic");
        p.addProperty("from", "old");
        p.addProperty("to", "new");
        p.addProperty("old", old().toString());
        p.addProperty("target", target().toString());
        p.addProperty("sha512", java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-512").digest("new".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        if (stage) p.addProperty("staged", staged().toString());
        else if (explicitNull) p.add("staged", com.google.gson.JsonNull.INSTANCE);
        JsonArray a = new JsonArray(); a.add(p);
        JsonObject root = new JsonObject(); root.add("pending", a);
        jar(state(), root.toString());
    }
    JsonArray pending() throws Exception {
        return JsonParser.parseString(Files.readString(state())).getAsJsonObject().getAsJsonArray("pending");
    }
    void invoke(UpdateService service, String method) throws Exception {
        var m = UpdateService.class.getDeclaredMethod(method); m.setAccessible(true);
        try { m.invoke(service); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }
    void reconcile(UpdateService service) throws Exception { invoke(service, "reconcilePreviousSession"); }
    void apply(UpdateService service) throws Exception { invoke(service, "applyAll"); }

    void samePathState() throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(state())).getAsJsonObject();
        root.getAsJsonArray("pending").get(0).getAsJsonObject().addProperty("target", old().toString());
        Files.writeString(state(), root.toString());
    }
    @Test void historicalCrossNameNeverPublishesAcrossRestartsOrHelperProtocols() throws Exception {
        jar(old(), "old"); jar(staged(), "new"); persist(true, false);
        JsonObject root = JsonParser.parseString(Files.readString(state())).getAsJsonObject();
        root.getAsJsonArray("pending").get(0).getAsJsonObject().remove("sha512");
        Files.writeString(state(), root.toString());
        for (int i = 0; i < 2; i++) {
            UpdateService s = service(); reconcile(s); apply(s);
            assertEquals(1, pending().size());
            assertEquals("old", Files.readString(old())); assertEquals("new", Files.readString(staged()));
            assertFalse(Files.exists(target()));
        }
        String hash = pending().get(0).getAsJsonObject().get("sha512").getAsString();
        JanitorMain.main(new String[]{"0", old().toString(), staged().toString(), target().toString()});
        JanitorMain.main(new String[]{"--sha512-v1", "0", old().toString(), staged().toString(), target().toString(), hash});
        assertEquals("old", Files.readString(old())); assertEquals("new", Files.readString(staged()));
        assertFalse(Files.exists(target())); assertEquals(1, pending().size());
    }
    @Test void cleanupOnlyPresenceMatrixAndRestarts() throws Exception {
        for (boolean explicitNull : new boolean[]{false, true}) {
            for (boolean hasOld : new boolean[]{false, true}) {
                for (boolean hasTarget : new boolean[]{false, true}) {
                    Files.deleteIfExists(old()); Files.deleteIfExists(target());
                    if (hasOld) jar(old(), "usable old");
                    if (hasTarget) jar(target(), "new");
                    persist(false, explicitNull);
                    for (int restart = 0; restart < 3; restart++) {
                        UpdateService s = service(); reconcile(s); reconcile(s);
                        assertEquals(hasOld || !hasTarget ? 1 : 0, pending().size());
                        apply(s); apply(s);
                        assertEquals(hasTarget, Files.exists(target()));
                        if (hasTarget) assertEquals("new", Files.readString(target()));
                        assertEquals(hasOld, Files.exists(old()));
                    }
                }
            }
        }
    }
    @Test void partialCrossNameSwapIsPreservedAcrossRestart() throws Exception {
        jar(old(), "old"); jar(target(), "new"); persist(true, false);
        reconcile(service());
        assertEquals(1, pending().size());
        assertTrue(pending().get(0).getAsJsonObject().has("staged"));
        UpdateService restarted = service(); reconcile(restarted); apply(restarted); apply(restarted);
        assertEquals("old", Files.readString(old())); assertEquals("new", Files.readString(target()));
        reconcile(service()); assertEquals(1, pending().size());
    }
    @Test void targetDisappearingAfterReconciliationPreservesOnlyUsableJar() throws Exception {
        jar(old(), "old"); jar(target(), "new"); persist(false, false);
        UpdateService s = service(); reconcile(s); Files.delete(target()); apply(s);
        assertEquals("old", Files.readString(old()));
    }
    @Test void sameOldAndTargetMustNotDeleteTarget() throws Exception {
        jar(old(), "only jar"); persist(false, false);
        JsonObject root = JsonParser.parseString(Files.readString(state())).getAsJsonObject();
        root.getAsJsonArray("pending").get(0).getAsJsonObject().addProperty("target", old().toString());
        Files.writeString(state(), root.toString());
        UpdateService s = service(); reconcile(s); apply(s);
        assertEquals("only jar", Files.readString(old()));
    }
    @Test void normalSwapCrashBeforeShutdownAndRepeatedShutdown() throws Exception {
        jar(old(), "old"); jar(staged(), "new"); persist(true, false); samePathState();
        reconcile(service()); // crash before shutdown: pending must survive a real reload
        UpdateService s = service(); reconcile(s); reconcile(s);
        assertEquals(1, pending().size()); apply(s); apply(s);
        assertEquals("new", Files.readString(old())); assertTrue(Files.exists(staged()));
        assertFalse(Files.exists(target()));
        reconcile(service()); assertEquals(0, pending().size());
        assertFalse(Files.exists(staged()));
    }
    @Test void historicalMissingOldDoesNotPublishStagedJar() throws Exception {
        jar(staged(), "new"); persist(true, false);
        UpdateService s = service(); reconcile(s); assertEquals(1, pending().size()); apply(s);
        assertFalse(Files.exists(target())); assertEquals("new", Files.readString(staged()));
    }
    @Test void appliedSwapRemovesStagingLeftoverOnly() throws Exception {
        jar(target(), "new"); jar(staged(), "new"); persist(true, false);
        UpdateService s = service(); reconcile(s); apply(s);
        assertEquals("new", Files.readString(target())); assertFalse(Files.exists(staged()));
        assertEquals(0, pending().size());
    }
    @Test void unavailableStagedJarDoesNotDeleteOldOnRepeatedShutdown() throws Exception {
        jar(old(), "old"); jar(staged(), "new"); persist(true, false);
        UpdateService s = service(); reconcile(s); Files.delete(staged()); apply(s); apply(s);
        assertEquals("old", Files.readString(old()));
    }
    @Test void crossNameDuplicateRemainsPendingUntilManualRepair() throws Exception {
        // Deterministic real filesystem deletion failure on Linux, NOT a claimed Windows lock test.
        Files.createDirectories(old()); jar(old().resolve("blocker"), "occupied");
        jar(target(), "new"); persist(false, false);
        UpdateService s = service(); reconcile(s); apply(s);
        assertEquals("new", Files.readString(target())); assertEquals(1, pending().size());
        Files.delete(old().resolve("blocker")); Files.delete(old()); jar(old(), "old");
        UpdateService restarted = service(); reconcile(restarted); apply(restarted);
        assertEquals("old", Files.readString(old())); assertEquals("new", Files.readString(target()));
    }
    Object newPending() throws Exception {
        Class<?> type = Class.forName(UpdateService.class.getName() + "$Pending");
        var ctor = type.getDeclaredConstructor(String.class, String.class, String.class,
                String.class, String.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance("synthetic", "synthetic", "old", "new",
                old().toString(), staged().toString(), old().toString());
    }
    void register(UpdateService s) throws Exception {
        Object p = newPending();
        var method = UpdateService.class.getDeclaredMethod("registerPending", p.getClass());
        method.setAccessible(true);
        try { method.invoke(s, p); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }
    @Test void registeredJobIsPersistedWithHashBeforeShutdown() throws Exception {
        jar(old(), "old"); jar(staged(), "new");
        UpdateService s = service(); register(s);
        assertTrue(pending().get(0).getAsJsonObject().has("sha512"));
        UpdateService restarted = service(); reconcile(restarted); apply(restarted);
        assertEquals("new", Files.readString(old()));
        assertEquals(old().toString(), pending().get(0).getAsJsonObject().get("target").getAsString());
    }
    @Test void failedJournalDoesNotExposeJobToShutdown() throws Exception {
        jar(old(), "old"); jar(staged(), "new"); jar(dir.resolve("work"), "not a directory");
        UpdateService s = service();
        assertThrows(IllegalStateException.class, () -> register(s));
        apply(s);
        assertEquals("old", Files.readString(old()));
        assertEquals("new", Files.readString(staged()));
        assertFalse(Files.exists(target()));
    }
    @Test void stagingAfterShutdownIsRejectedWithoutTouchingOldJar() throws Exception {
        jar(old(), "old"); jar(staged(), "new");
        UpdateService s = service(); apply(s);
        assertThrows(IllegalStateException.class, () -> register(s));
        assertEquals("old", Files.readString(old()));
    }
    @Test void truncatedTargetNeverDiscardsCompleteStaging() throws Exception {
        jar(old(), "old"); jar(target(), "n"); jar(staged(), "new"); persist(true, false);
        UpdateService s = service(); reconcile(s);
        assertEquals("new", Files.readString(staged()));
        assertEquals(1, pending().size()); apply(s);
        assertEquals("n", Files.readString(target()));
        reconcile(service()); assertTrue(Files.exists(staged()));
    }
    @Test void legacyCrossNameStageUpgradesHashWithoutReplacingTarget() throws Exception {
        jar(old(), "old"); jar(target(), "n"); jar(staged(), "new"); persist(true, false);
        JsonObject root = JsonParser.parseString(Files.readString(state())).getAsJsonObject();
        root.getAsJsonArray("pending").get(0).getAsJsonObject().remove("sha512");
        Files.writeString(state(), root.toString());
        UpdateService s = service(); reconcile(s);
        assertTrue(pending().get(0).getAsJsonObject().has("sha512")); apply(s);
        assertEquals("n", Files.readString(target())); assertEquals("old", Files.readString(old()));
    }
    @Test void janitorAtomicInstallKeepsRecoverySource() throws Exception {
        jar(old(), "old"); jar(target(), "n"); jar(staged(), "new");
        JanitorMain.main(new String[]{"0", old().toString(), staged().toString(), old().toString()});
        assertEquals("new", Files.readString(old()));
        assertEquals("new", Files.readString(staged()));
    }
    @Test void corruptStagingCannotReplaceOldJar() throws Exception {
        jar(old(), "old"); jar(staged(), "new");
        UpdateService s = service(); register(s);
        jar(staged(), "corrupt"); apply(s);
        assertEquals("old", Files.readString(old()));
        assertFalse(Files.exists(target()));
    }
    @Test void helperRunsFromIndependentJarWithChecksumProtocol() throws Exception {
        jar(old(), "old"); jar(staged(), "new");
        UpdateService s = service(); register(s);
        String expected = pending().get(0).getAsJsonObject().get("sha512").getAsString();
        var method = UpdateService.class.getDeclaredMethod("janitorClasspath", java.util.List.class);
        method.setAccessible(true);
        String helper = (String) method.invoke(s, java.util.List.of());
        assertNotNull(helper);
        // JVM classpath contains only helper JAR: no mod, no Gson, no new staged code.
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", helper, JanitorMain.class.getName(), "--sha512-v1", "0",
                old().toString(), staged().toString(), old().toString(), expected)
                .redirectErrorStream(true).redirectOutput(dir.resolve("helper.log").toFile()).start();
        assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, child.exitValue());
        assertEquals("new", Files.readString(old()));
        assertFalse(Files.exists(target()));
        assertEquals("new", Files.readString(staged()));
    }
    @Test void checkedJanitorRejectsCorruptStagedBeforeOldDeletion() throws Exception {
        jar(old(), "old"); jar(staged(), "new"); persist(true, false);
        String expected = pending().get(0).getAsJsonObject().get("sha512").getAsString();
        jar(staged(), "corrupt");
        JanitorMain.main(new String[]{"--sha512-v1", "0", old().toString(),
                staged().toString(), old().toString(), expected});
        assertEquals("old", Files.readString(old()));
        assertFalse(Files.exists(target()));
    }
    @Test void shutdownWinsConcurrentRegistrationWithoutUnjournaledSwap() throws Exception {
        jar(old(), "old"); jar(staged(), "new"); UpdateService s = service();
        var ready = new java.util.concurrent.CountDownLatch(1);
        var result = new java.util.concurrent.CompletableFuture<Throwable>();
        Thread worker;
        synchronized (s) {
            worker = new Thread(() -> {
                ready.countDown();
                try { register(s); result.complete(null); }
                catch (Throwable e) { result.complete(e); }
            });
            worker.start(); assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            apply(s); // reentrant: registration cannot enter the monitor before shutdown.
        }
        assertInstanceOf(IllegalStateException.class,
                result.get(5, java.util.concurrent.TimeUnit.SECONDS));
        worker.join();
        assertEquals("old", Files.readString(old()));
        assertFalse(Files.exists(target()));
    }
    @Test void janitorCleanupDoesNotDeleteOnlyRemainingJar() throws Exception {
        jar(old(), "old");
        JanitorMain.main(new String[]{"0", old().toString(), "-", target().toString()});
        assertEquals("old", Files.readString(old()));
    }
    @Test void janitorRefusesCrossNameCleanupIdempotently() throws Exception {
        jar(old(), "old"); jar(target(), "new");
        String[] args = {"0", old().toString(), "-", target().toString()};
        JanitorMain.main(args); JanitorMain.main(args);
        assertEquals("old", Files.readString(old())); assertEquals("new", Files.readString(target()));
    }
}
