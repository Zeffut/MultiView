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

    @Test void cleanupOnlyPresenceMatrixAndRestarts() throws Exception {
        for (boolean explicitNull : new boolean[]{false, true}) {
            for (boolean hasOld : new boolean[]{false, true}) {
                for (boolean hasTarget : new boolean[]{false, true}) {
                    Files.deleteIfExists(old()); Files.deleteIfExists(target());
                    if (hasOld) jar(old(), "usable old");
                    if (hasTarget) jar(target(), "usable target");
                    persist(false, explicitNull);
                    for (int restart = 0; restart < 3; restart++) {
                        UpdateService s = service(); reconcile(s); reconcile(s);
                        assertEquals(hasOld && hasTarget && restart == 0 ? 1 : 0, pending().size());
                        apply(s); apply(s);
                        assertEquals(hasTarget, Files.exists(target()));
                        if (hasTarget) assertEquals("usable target", Files.readString(target()));
                        assertEquals(hasOld && !hasTarget, Files.exists(old()));
                    }
                }
            }
        }
    }
    @Test void partialSwapPersistsCleanupAndSurvivesRestart() throws Exception {
        jar(old(), "old"); jar(target(), "new"); persist(true, false);
        reconcile(service());
        assertEquals(1, pending().size());
        assertFalse(pending().get(0).getAsJsonObject().has("staged"));
        UpdateService restarted = service(); reconcile(restarted); apply(restarted); apply(restarted);
        assertFalse(Files.exists(old())); assertEquals("new", Files.readString(target()));
        reconcile(service()); assertEquals(0, pending().size());
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
        jar(old(), "old"); jar(staged(), "new"); persist(true, false);
        reconcile(service()); // crash before shutdown: pending must survive a real reload
        UpdateService s = service(); reconcile(s); reconcile(s);
        assertEquals(1, pending().size()); apply(s); apply(s);
        assertFalse(Files.exists(old())); assertFalse(Files.exists(staged()));
        assertEquals("new", Files.readString(target()));
        reconcile(service()); assertEquals(0, pending().size());
    }
    @Test void crashBetweenDeleteAndMoveRecoversStagedJar() throws Exception {
        jar(staged(), "new"); persist(true, false);
        UpdateService s = service(); reconcile(s); assertEquals(1, pending().size()); apply(s);
        assertEquals("new", Files.readString(target()));
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
    @Test void blockedDeletionRemainsPendingUntilNextSession() throws Exception {
        // Deterministic real filesystem deletion failure on Linux, NOT a claimed Windows lock test.
        Files.createDirectories(old()); jar(old().resolve("blocker"), "occupied");
        jar(target(), "new"); persist(false, false);
        UpdateService s = service(); reconcile(s); apply(s);
        assertEquals("new", Files.readString(target())); assertEquals(1, pending().size());
        Files.delete(old().resolve("blocker")); Files.delete(old()); jar(old(), "old");
        UpdateService restarted = service(); reconcile(restarted); apply(restarted);
        assertFalse(Files.exists(old())); assertEquals("new", Files.readString(target()));
    }
    @Test void janitorCleanupDoesNotDeleteOnlyRemainingJar() throws Exception {
        jar(old(), "old");
        JanitorMain.main(new String[]{"0", old().toString(), "-", target().toString()});
        assertEquals("old", Files.readString(old()));
    }
    @Test void janitorCleanupIsIdempotent() throws Exception {
        jar(old(), "old"); jar(target(), "new");
        String[] args = {"0", old().toString(), "-", target().toString()};
        JanitorMain.main(args); JanitorMain.main(args);
        assertFalse(Files.exists(old())); assertEquals("new", Files.readString(target()));
    }
}
