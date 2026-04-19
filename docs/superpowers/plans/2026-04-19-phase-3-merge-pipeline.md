# Phase 3 — Merge pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implémenter le pipeline de merge zéro-perte de N replays `.flashback` en un seul replay unifié, déclenchable via `/mv merge <A> <B> [<C>...] -o <name>`.

**Architecture:** Streaming k-way merge. N `FlashbackReader.stream()` alimentent un `PriorityQueue` trié par tick absolu. Chaque `PacketEntry` est classifié (WORLD / ENTITY / EGO / GLOBAL / CACHE_REF / LOCAL_PLAYER / PASSTHROUGH) puis routé vers le merger approprié. Les sorties sont écrites en streaming dans un dossier `<merged>/` contenant le stream principal + `ego/<uuid>.flashback` + `level_chunk_caches/` + `metadata.json` + `merge-report.json`. Design complet : [`docs/superpowers/specs/2026-04-19-phase-3-merge-design.md`](../specs/2026-04-19-phase-3-merge-design.md).

**Tech Stack:** Java 21, Fabric 0.141.3+1.21.11, Loom 1.16.1, JUnit 5, Netty ByteBuf, Gson. Réutilise tout l'acquis Phase 1/2 (`FlashbackReader`, `FlashbackWriter`, `Action` sealed, `SegmentWriter`/`SegmentReader`, `PacketEntry`).

**Contexte pour l'engineer qui exécute ce plan** :
- Le projet est un mod Fabric côté client. Les tests unitaires tournent hors JVM Minecraft (mais les classes MC sont sur le classpath de test via Loom).
- Certains tests nécessitent le runtime Minecraft chargé (registres, codecs). Ils sont balisés `@EnabledIf("replaysAvailable")` ou équivalent et skippés en CI.
- Les POV réels sont dans `run/replay/2026-02-20T23_25_15` et `run/replay/Sénat_empirenapo2026-02-20T23_20_16`. Ne pas les modifier.
- Commits : Conventional Commits, scope `merge` ou `feat/fix/test/docs`.
- Tests : `./gradlew test`. Lancement Minecraft : `./gradlew runClient`.

---

## Ordre d'exécution

```
Task 1  Scaffolding (Category, MergeReport, MergeContext, MergeOptions, stubs)
Task 2  Packet codec spike (preuve qu'on peut decode/modify/re-encode entity IDs)
Task 3  IdRemapper
Task 4  SourcePovTracker
Task 5  GlobalDeduper
Task 6  CacheRemapper
Task 7  WorldStateMerger
Task 8  EntityMerger
Task 9  EgoRouter
Task 10 TimelineAligner (metadata.name fallback seulement)
Task 11 TimelineAligner (ClientboundSetTimePacket ancre)
Task 12 TimelineAligner (CLI overrides + cascade complète)
Task 13 PacketClassifier (Action types sealed)
Task 14 PacketClassifier (table de dispatch GamePacket, MC-runtime)
Task 15 MergeOrchestrator (pipeline core)
Task 16 MergeOrchestrator (output writer)
Task 17 MergeOrchestrator (atomic rollback)
Task 18 /mv merge command (Brigadier)
Task 19 Integration test sur 2 POV HIKA
Task 20 Validation manuelle + journal + tag
```

---

## Task 1 : Scaffolding du package merge

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/merge/Category.java`
- Create: `src/main/java/fr/zeffut/multiview/merge/MergeReport.java`
- Create: `src/main/java/fr/zeffut/multiview/merge/MergeOptions.java`
- Create: `src/main/java/fr/zeffut/multiview/merge/MergeContext.java`

- [ ] **Step 1 : Créer `Category.java`**

```java
package fr.zeffut.multiview.merge;

/** Catégorie de dispatch déterminée par {@link PacketClassifier}. */
public enum Category {
    TICK,          // NextTick action, rejoué recalé
    CONFIG,        // ConfigurationPacket, dedup par hash
    LOCAL_PLAYER,  // CreateLocalPlayer, traité par MergeOrchestrator
    WORLD,         // blocs + chunks, vers WorldStateMerger
    ENTITY,        // AddEntity/RemoveEntities/Move/etc., vers EntityMerger
    EGO,           // santé/XP/inventaire, vers EgoRouter (attaché au sourceUuid)
    GLOBAL,        // time/chat/son/explosion, vers GlobalDeduper
    CACHE_REF,     // CacheChunkRef, vers CacheRemapper
    PASSTHROUGH    // inconnu, rejoué tel quel
}
```

- [ ] **Step 2 : Créer `MergeOptions.java`**

```java
package fr.zeffut.multiview.merge;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Options de merge parsées depuis /mv merge.
 *
 * @param sources   chemins absolus des dossiers replay sources (≥ 2)
 * @param destination chemin absolu du dossier de sortie
 * @param tickOverrides offset en ticks à ajouter à une source, clé = nom du dossier
 * @param force     si true, écrase une destination existante
 */
public record MergeOptions(
        List<Path> sources,
        Path destination,
        Map<String, Integer> tickOverrides,
        boolean force
) {
}
```

- [ ] **Step 3 : Créer `MergeReport.java`**

```java
package fr.zeffut.multiview.merge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rapport stats + warnings d'un merge. Sérialisé en merge-report.json via Gson. */
public final class MergeReport {
    public String version = "0.1.0";
    public List<SourceInfo> sources = new ArrayList<>();
    public int mergedTotalTicks;
    public String alignmentStrategy; // "setTimePacket" | "metadataName" | "cliOverride"
    public Stats stats = new Stats();
    public List<String> warnings = new ArrayList<>();
    public List<String> errors = new ArrayList<>();

    public static final class SourceInfo {
        public String folder;
        public String uuid;
        public int totalTicks;
        public int tickOffset;
    }

    public static final class Stats {
        public int entitiesMergedByUuid;
        public int entitiesMergedByHeuristic;
        public int entitiesAmbiguousMerged;
        public int blocksLwwConflicts;
        public int blocksLwwOverwrites;
        public int globalPacketsDeduped;
        public List<String> egoTracks = new ArrayList<>();
        public int chunkCachesConcatenated;
        public Map<String, Integer> passthroughPackets = new LinkedHashMap<>();
    }

    public void warn(String msg) { warnings.add(msg); }
    public void error(String msg) { errors.add(msg); }
}
```

- [ ] **Step 4 : Créer `MergeContext.java`** (holder injecté dans tous les mergers)

```java
package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.FlashbackReplay;
import java.util.List;

/**
 * État partagé du pipeline de merge. Instancié une fois par MergeOrchestrator.
 * Les mergers reçoivent ce contexte en constructor et lisent/écrivent dessus.
 */
public final class MergeContext {
    public final List<FlashbackReplay> sources;
    public final int[] tickOffsets;              // tickAbs = tickLocal + tickOffsets[sourceIdx]
    public final int mergedStartTick;            // tick 0 du merged = min(tickOffsets)
    public final int primarySourceIdx;           // index de la source la plus longue
    public final MergeReport report;

    public MergeContext(List<FlashbackReplay> sources, int[] tickOffsets,
                        int mergedStartTick, int primarySourceIdx, MergeReport report) {
        this.sources = sources;
        this.tickOffsets = tickOffsets;
        this.mergedStartTick = mergedStartTick;
        this.primarySourceIdx = primarySourceIdx;
        this.report = report;
    }

    public int toAbsTick(int sourceIdx, int tickLocal) {
        return tickOffsets[sourceIdx] + tickLocal - mergedStartTick;
    }
}
```

- [ ] **Step 5 : Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/
git commit -m "feat(merge): scaffolding — Category, MergeReport, MergeContext, MergeOptions"
```

---

## Task 2 : Packet codec spike (RISK MITIGATION)

Avant d'aller plus loin, on prouve qu'on peut décoder un `GamePacket` payload, lire l'entity ID qu'il contient, modifier cet ID, et ré-encoder le payload. Sans ça, tout l'IdRemapper est cassé. C'est un **spike** — on écrit du code de test pour valider la faisabilité, puis on fige la technique.

**Files:**
- Create: `src/test/java/fr/zeffut/multiview/merge/PacketCodecSpikeTest.java`

**Context sur les GamePackets Flashback** :
- Le payload d'une action `GamePacket` est la sérialisation d'un `Packet<ClientGamePacketListener>` Minecraft.
- L'ordre wire : `VarInt packetId, byte[] packetBody` (le packetId peut ne pas être présent selon l'encoding Flashback — à vérifier dans `Action.java` et `SegmentReader.nextRaw()`).
- Pour décoder : `RegistryFriendlyByteBuf` + `ClientGamePacketListener`'s stream codec.

- [ ] **Step 1 : Explorer comment un `GamePacket` est encodé par Flashback**

Lire :
- `src/main/java/fr/zeffut/multiview/format/Action.java`
- `src/main/java/fr/zeffut/multiview/format/ActionType.java:GAME_PACKET` → fonction de decode/encode
- Référence : `/tmp/arcade-ref/` (si encore présent) — `FlashbackAction.kt` et comment Arcade encode `GamePacket`.

Si `/tmp/arcade-ref/` n'existe plus, fetch via :
```bash
gh api repos/CasualChampionships/Arcade/contents/arcade-replay/src/main/kotlin/net/casual/arcade/replay/recorder/flashback/FlashbackChunkedWriter.kt | jq -r '.content' | base64 -d > /tmp/arcade-flashback-chunked.kt
gh api repos/CasualChampionships/Arcade/contents/arcade-replay/src/main/kotlin/net/casual/arcade/replay/recorder/flashback/action/FlashbackAction.kt | jq -r '.content' | base64 -d > /tmp/arcade-flashback-action.kt
```

Écrire un commentaire-conclusion dans ce test : "Le GamePacket payload est ENCODÉ ainsi : [description]".

- [ ] **Step 2 : Écrire le test de spike**

```java
package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.FlashbackReader;
import fr.zeffut.multiview.format.FlashbackReplay;
import fr.zeffut.multiview.format.PacketEntry;
import fr.zeffut.multiview.format.Action;
import io.netty.buffer.ByteBufAllocator;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike : prouve qu'on peut decode/modifier/re-encode un GamePacket
 * contenant un entity ID. Conditionné sur la présence d'un replay réel.
 */
class PacketCodecSpikeTest {

    static boolean replaysAvailable() {
        return Files.isDirectory(Path.of("run/replay/2026-02-20T23_25_15"));
    }

    @Test
    @EnabledIf("replaysAvailable")
    void canDecodeAndReEncodeMoveEntityPacket() throws Exception {
        FlashbackReplay replay = FlashbackReader.open(Path.of("run/replay/2026-02-20T23_25_15"));

        // Trouver le premier GamePacket qui porte un ClientboundMoveEntityPacket.
        Optional<byte[]> maybePayload = FlashbackReader.stream(replay)
                .filter(e -> e.action() instanceof Action.GamePacket)
                .map(e -> ((Action.GamePacket) e.action()).payload())
                .findFirst();

        assertTrue(maybePayload.isPresent(), "Au moins un GamePacket attendu dans un replay réel");
        byte[] payload = maybePayload.get();
        System.out.println("[SPIKE] Premier GamePacket payload = " + payload.length + " bytes");
        System.out.println("[SPIKE] Premier bytes (hex) : " + toHex(payload, Math.min(16, payload.length)));

        // À COMPLÉTER : essayer de décoder le payload comme un packet Minecraft concret.
        // Pour l'instant, on se contente de loguer. L'engineer qui exécute Task 2 documente
        // ici la stratégie de décodage / ré-encodage retenue.
    }

    private static String toHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(String.format("%02x ", bytes[i]));
        return sb.toString();
    }
}
```

- [ ] **Step 3 : Lancer le test**

Run: `./gradlew test --tests PacketCodecSpikeTest`
Expected: PASS, affiche les bytes du premier GamePacket. Si les replays ne sont pas présents, SKIP.

- [ ] **Step 4 : Documenter la stratégie de remapping**

Sur la base de l'observation Step 3 + lecture du code Arcade, l'engineer doit **écrire dans ce test** une section de commentaire expliquant :
- Est-ce que le payload commence par le packetId VarInt, ou est-ce juste le corps du packet ?
- Quel `RegistryFriendlyByteBuf` / registry access utiliser pour décoder ?
- Comment re-encoder après modification ?

Recommandation attendue : on utilise `GameProtocols.CLIENTBOUND` (ou l'équivalent 1.21.11) pour obtenir un `StreamCodec<RegistryFriendlyByteBuf, Packet<?>>` qui décode n'importe quel packet clientbound ; on reconstruit le packet modifié ; on re-encode via le même codec.

Si cette approche **ne marche pas** (le payload n'est pas un packet complet mais une forme custom Flashback), documenter le fallback : manipulation binaire directe (VarInt + champs fixes).

- [ ] **Step 5 : Commit**

```bash
git add src/test/java/fr/zeffut/multiview/merge/PacketCodecSpikeTest.java
git commit -m "test(merge): spike GamePacket decode/re-encode — documente la stratégie"
```

---

## Task 3 : IdRemapper

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/merge/IdRemapper.java`
- Create: `src/test/java/fr/zeffut/multiview/merge/IdRemapperTest.java`

- [ ] **Step 1 : Écrire les tests**

```java
package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdRemapperTest {

    @Test
    void mapThenRemapReturnsGlobalId() {
        IdRemapper remapper = new IdRemapper();
        int globalId = remapper.assign(0, 42);
        assertEquals(globalId, remapper.remap(0, 42));
    }

    @Test
    void differentSourcesGetDistinctGlobalIds() {
        IdRemapper remapper = new IdRemapper();
        int g1 = remapper.assign(0, 42);
        int g2 = remapper.assign(1, 42);
        assertNotEquals(g1, g2);
    }

    @Test
    void sameSourceAndLocalIdMappedTwiceKeepsSameGlobalId() {
        IdRemapper remapper = new IdRemapper();
        int g1 = remapper.assign(0, 42);
        int g2 = remapper.assign(0, 42);
        assertEquals(g1, g2);
    }

    @Test
    void remapOfUnmappedThrows() {
        IdRemapper remapper = new IdRemapper();
        assertThrows(IllegalStateException.class, () -> remapper.remap(0, 42));
    }

    @Test
    void globalIdsStartAbove100Million() {
        IdRemapper remapper = new IdRemapper();
        assertTrue(remapper.assign(0, 1) >= 100_000_000,
                "globalIds doivent commencer >= 100M pour éviter collision avec IDs locaux");
    }

    @Test
    void assignToExistingGlobalId() {
        IdRemapper remapper = new IdRemapper();
        int g = remapper.assign(0, 10);
        remapper.assignExisting(1, 99, g);
        assertEquals(g, remapper.remap(1, 99));
    }
}
```

- [ ] **Step 2 : Lancer les tests → doivent échouer**

Run: `./gradlew test --tests IdRemapperTest`
Expected: FAIL (classe inexistante).

- [ ] **Step 3 : Implémenter `IdRemapper`**

```java
package fr.zeffut.multiview.merge;

import java.util.HashMap;
import java.util.Map;

/**
 * Bijection (sourceIdx, localEntityId) → globalEntityId.
 * Les globalIds commencent à 100_000_000 pour éviter toute collision avec
 * des localIds qui traîneraient dans des payloads non remappés.
 */
public final class IdRemapper {

    private static final int GLOBAL_ID_BASE = 100_000_000;

    private final Map<Long, Integer> mapping = new HashMap<>();
    private int nextGlobalId = GLOBAL_ID_BASE;

    /** Assigne un nouveau globalId si absent, sinon retourne l'existant. */
    public int assign(int sourceIdx, int localId) {
        return mapping.computeIfAbsent(key(sourceIdx, localId), k -> nextGlobalId++);
    }

    /** Force l'association (sourceIdx, localId) → globalId existant. */
    public void assignExisting(int sourceIdx, int localId, int globalId) {
        mapping.put(key(sourceIdx, localId), globalId);
    }

    /** Lookup. Lance IllegalStateException si absent. */
    public int remap(int sourceIdx, int localId) {
        Integer g = mapping.get(key(sourceIdx, localId));
        if (g == null) {
            throw new IllegalStateException(
                    "IdRemapper: (" + sourceIdx + ", " + localId + ") non assigné");
        }
        return g;
    }

    public boolean contains(int sourceIdx, int localId) {
        return mapping.containsKey(key(sourceIdx, localId));
    }

    private static long key(int sourceIdx, int localId) {
        return ((long) sourceIdx << 32) | (localId & 0xFFFFFFFFL);
    }
}
```

- [ ] **Step 4 : Lancer les tests → doivent passer**

Run: `./gradlew test --tests IdRemapperTest`
Expected: PASS (6 tests).

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/IdRemapper.java src/test/java/fr/zeffut/multiview/merge/IdRemapperTest.java
git commit -m "feat(merge): IdRemapper — bijection (sourceIdx, localId) → globalId"
```

---

## Task 4 : SourcePovTracker

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/merge/SourcePovTracker.java`
- Create: `src/test/java/fr/zeffut/multiview/merge/SourcePovTrackerTest.java`

- [ ] **Step 1 : Écrire les tests**

```java
package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourcePovTrackerTest {

    @Test
    void positionAtReturnsNaNBeforeAnyUpdate() {
        SourcePovTracker t = new SourcePovTracker(2);
        assertTrue(Double.isNaN(t.positionAt(0, 100).x()),
                "Avant tout update, la position est NaN");
    }

    @Test
    void positionAtReturnsLatestKnownPositionBeforeOrEqualTick() {
        SourcePovTracker t = new SourcePovTracker(2);
        t.update(0, 10, 1.0, 2.0, 3.0);
        t.update(0, 20, 5.0, 6.0, 7.0);

        SourcePovTracker.Vec3 p15 = t.positionAt(0, 15);
        assertEquals(1.0, p15.x(), 0.0001);
        assertEquals(2.0, p15.y(), 0.0001);
        assertEquals(3.0, p15.z(), 0.0001);

        SourcePovTracker.Vec3 p25 = t.positionAt(0, 25);
        assertEquals(5.0, p25.x(), 0.0001);
    }

    @Test
    void sourcesAreIndependent() {
        SourcePovTracker t = new SourcePovTracker(2);
        t.update(0, 10, 1.0, 2.0, 3.0);
        t.update(1, 10, 100.0, 200.0, 300.0);

        assertEquals(1.0, t.positionAt(0, 10).x(), 0.0001);
        assertEquals(100.0, t.positionAt(1, 10).x(), 0.0001);
    }

    @Test
    void distanceReturnsInfiniteIfUnknown() {
        SourcePovTracker t = new SourcePovTracker(1);
        double d = t.distanceTo(0, 100, 1.0, 2.0, 3.0);
        assertTrue(Double.isInfinite(d));
    }

    @Test
    void distanceComputesEuclidean() {
        SourcePovTracker t = new SourcePovTracker(1);
        t.update(0, 10, 0.0, 0.0, 0.0);
        double d = t.distanceTo(0, 10, 3.0, 4.0, 0.0);
        assertEquals(5.0, d, 0.0001);
    }
}
```

- [ ] **Step 2 : Test fail**

Run: `./gradlew test --tests SourcePovTrackerTest`
Expected: FAIL (classe inexistante).

- [ ] **Step 3 : Implémenter `SourcePovTracker`**

```java
package fr.zeffut.multiview.merge;

import java.util.TreeMap;

/**
 * Position du POV enregistreur de chaque source à un tick absolu donné.
 * Alimenté par MergeOrchestrator à chaque update de position du local player.
 */
public final class SourcePovTracker {

    public record Vec3(double x, double y, double z) {
        public static final Vec3 UNKNOWN = new Vec3(Double.NaN, Double.NaN, Double.NaN);
    }

    private final TreeMap<Integer, Vec3>[] perSource;

    @SuppressWarnings("unchecked")
    public SourcePovTracker(int sourceCount) {
        this.perSource = new TreeMap[sourceCount];
        for (int i = 0; i < sourceCount; i++) {
            this.perSource[i] = new TreeMap<>();
        }
    }

    public void update(int sourceIdx, int tickAbs, double x, double y, double z) {
        perSource[sourceIdx].put(tickAbs, new Vec3(x, y, z));
    }

    /** Dernière position connue au tick ≤ `tickAbs`. UNKNOWN si aucune. */
    public Vec3 positionAt(int sourceIdx, int tickAbs) {
        var entry = perSource[sourceIdx].floorEntry(tickAbs);
        return entry == null ? Vec3.UNKNOWN : entry.getValue();
    }

    /** Distance euclidienne du POV source au point (x,y,z) au tick. Infini si inconnue. */
    public double distanceTo(int sourceIdx, int tickAbs, double x, double y, double z) {
        Vec3 p = positionAt(sourceIdx, tickAbs);
        if (Double.isNaN(p.x())) return Double.POSITIVE_INFINITY;
        double dx = p.x() - x, dy = p.y() - y, dz = p.z() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
```

- [ ] **Step 4 : Test pass**

Run: `./gradlew test --tests SourcePovTrackerTest`
Expected: PASS (5 tests).

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/SourcePovTracker.java src/test/java/fr/zeffut/multiview/merge/SourcePovTrackerTest.java
git commit -m "feat(merge): SourcePovTracker — position du POV enregistreur par tick"
```

---

## Task 5 : GlobalDeduper

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/merge/GlobalDeduper.java`
- Create: `src/test/java/fr/zeffut/multiview/merge/GlobalDeduperTest.java`

- [ ] **Step 1 : Écrire les tests**

```java
package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlobalDeduperTest {

    @Test
    void firstEmissionReturnsTrue() {
        GlobalDeduper d = new GlobalDeduper();
        assertTrue(d.shouldEmit(0x42, 100, new byte[]{1, 2, 3}));
    }

    @Test
    void duplicateAtSameTickReturnsFalse() {
        GlobalDeduper d = new GlobalDeduper();
        d.shouldEmit(0x42, 100, new byte[]{1, 2, 3});
        assertFalse(d.shouldEmit(0x42, 100, new byte[]{1, 2, 3}));
    }

    @Test
    void sameContentDifferentTickIsNotDedup() {
        GlobalDeduper d = new GlobalDeduper();
        d.shouldEmit(0x42, 100, new byte[]{1, 2, 3});
        assertTrue(d.shouldEmit(0x42, 101, new byte[]{1, 2, 3}));
    }

    @Test
    void sameTickDifferentContentIsNotDedup() {
        GlobalDeduper d = new GlobalDeduper();
        d.shouldEmit(0x42, 100, new byte[]{1, 2, 3});
        assertTrue(d.shouldEmit(0x42, 100, new byte[]{4, 5, 6}));
    }

    @Test
    void purgeOldEntries() {
        GlobalDeduper d = new GlobalDeduper();
        d.shouldEmit(0x42, 100, new byte[]{1, 2, 3});
        d.purgeOlderThan(300); // garde tick ≥ 300, donc purge
        assertTrue(d.shouldEmit(0x42, 100, new byte[]{1, 2, 3}),
                "après purge, le même key réapparaît comme nouveau");
    }
}
```

- [ ] **Step 2 : Test fail**

Run: `./gradlew test --tests GlobalDeduperTest`
Expected: FAIL.

- [ ] **Step 3 : Implémenter `GlobalDeduper`**

```java
package fr.zeffut.multiview.merge;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.zip.CRC32C;

/**
 * Déduplication des packets globaux par (packetTypeId, tickAbs, contentHash).
 * Utilisé pour éviter d'émettre 2× le même packet GLOBAL (ex. ClientboundSetTimePacket)
 * observé par plusieurs sources au même tick.
 */
public final class GlobalDeduper {

    private record Entry(int packetTypeId, int tickAbs, long contentHash) {}

    private final Set<Entry> seen = new HashSet<>();

    public boolean shouldEmit(int packetTypeId, int tickAbs, byte[] payload) {
        Entry e = new Entry(packetTypeId, tickAbs, hash(payload));
        return seen.add(e);
    }

    /** Supprime les entrées dont tickAbs < threshold. À appeler périodiquement. */
    public void purgeOlderThan(int threshold) {
        Iterator<Entry> it = seen.iterator();
        while (it.hasNext()) {
            if (it.next().tickAbs < threshold) it.remove();
        }
    }

    public int size() { return seen.size(); }

    private static long hash(byte[] payload) {
        CRC32C crc = new CRC32C();
        crc.update(payload, 0, payload.length);
        return crc.getValue();
    }
}
```

- [ ] **Step 4 : Test pass**

Run: `./gradlew test --tests GlobalDeduperTest`
Expected: PASS (5 tests).

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/GlobalDeduper.java src/test/java/fr/zeffut/multiview/merge/GlobalDeduperTest.java
git commit -m "feat(merge): GlobalDeduper — dedup par (type, tick, hash) + purge"
```

---

## Task 6 : CacheRemapper

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/merge/CacheRemapper.java`
- Create: `src/test/java/fr/zeffut/multiview/merge/CacheRemapperTest.java`

- [ ] **Step 1 : Écrire les tests** (utilisent un `@TempDir` JUnit)

```java
package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CacheRemapperTest {

    @Test
    void concatTwoSourcesProducesCorrectMapping(@TempDir Path tmp) throws Exception {
        Path srcA = tmp.resolve("A/level_chunk_caches");
        Path srcB = tmp.resolve("B/level_chunk_caches");
        Files.createDirectories(srcA);
        Files.createDirectories(srcB);
        Files.writeString(srcA.resolve("0"), "A0");
        Files.writeString(srcA.resolve("1"), "A1");
        Files.writeString(srcB.resolve("0"), "B0");

        Path dest = tmp.resolve("merged/level_chunk_caches");
        CacheRemapper remapper = new CacheRemapper();
        remapper.concat(List.of(srcA, srcB), dest);

        assertEquals("A0", Files.readString(dest.resolve("0")));
        assertEquals("A1", Files.readString(dest.resolve("1")));
        assertEquals("B0", Files.readString(dest.resolve("2")));

        assertEquals(0, remapper.remap(0, 0));
        assertEquals(1, remapper.remap(0, 1));
        assertEquals(2, remapper.remap(1, 0));
    }

    @Test
    void missingCacheDirIsSkipped(@TempDir Path tmp) throws Exception {
        Path srcA = tmp.resolve("A/level_chunk_caches");
        Files.createDirectories(srcA);
        Files.writeString(srcA.resolve("0"), "A0");

        Path srcB = tmp.resolve("B/level_chunk_caches"); // n'existe pas

        Path dest = tmp.resolve("merged/level_chunk_caches");
        CacheRemapper remapper = new CacheRemapper();
        remapper.concat(List.of(srcA, srcB), dest);

        assertEquals("A0", Files.readString(dest.resolve("0")));
        assertEquals(0, remapper.remap(0, 0));
        assertFalse(remapper.contains(1, 0));
    }

    @Test
    void remapOfUnknownThrows() {
        CacheRemapper remapper = new CacheRemapper();
        assertThrows(IllegalStateException.class, () -> remapper.remap(0, 0));
    }
}
```

- [ ] **Step 2 : Test fail**

Run: `./gradlew test --tests CacheRemapperTest`
Expected: FAIL.

- [ ] **Step 3 : Implémenter `CacheRemapper`**

```java
package fr.zeffut.multiview.merge;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Concatène les level_chunk_caches des N sources dans un dossier de sortie
 * et fournit le remapping (sourceIdx, localCacheIdx) → globalCacheIdx.
 *
 * Les caches sont renumérotés de 0 à N-1 dans l'ordre des sources.
 */
public final class CacheRemapper {

    private final Map<Long, Integer> mapping = new HashMap<>();
    private int concatCount = 0;

    /**
     * @param sourceCacheDirs  chemin absolu de chaque level_chunk_caches/ source
     * @param destCacheDir     chemin du level_chunk_caches/ de sortie (créé si absent)
     */
    public void concat(List<Path> sourceCacheDirs, Path destCacheDir) throws IOException {
        Files.createDirectories(destCacheDir);
        int nextGlobal = 0;
        for (int sourceIdx = 0; sourceIdx < sourceCacheDirs.size(); sourceIdx++) {
            Path src = sourceCacheDirs.get(sourceIdx);
            if (!Files.isDirectory(src)) continue;

            // Trier par nom numérique (0, 1, 2, ..., 10, 11, ...)
            TreeMap<Integer, Path> indexed = new TreeMap<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) {
                for (Path p : ds) {
                    try {
                        indexed.put(Integer.parseInt(p.getFileName().toString()), p);
                    } catch (NumberFormatException ignore) {
                        // fichier non-numérique, on ignore
                    }
                }
            }

            for (Map.Entry<Integer, Path> e : indexed.entrySet()) {
                int localIdx = e.getKey();
                int globalIdx = nextGlobal++;
                Files.copy(e.getValue(), destCacheDir.resolve(Integer.toString(globalIdx)),
                        StandardCopyOption.REPLACE_EXISTING);
                mapping.put(key(sourceIdx, localIdx), globalIdx);
                concatCount++;
            }
        }
    }

    public int remap(int sourceIdx, int localIdx) {
        Integer g = mapping.get(key(sourceIdx, localIdx));
        if (g == null) {
            throw new IllegalStateException(
                    "CacheRemapper: (" + sourceIdx + ", " + localIdx + ") inconnu");
        }
        return g;
    }

    public boolean contains(int sourceIdx, int localIdx) {
        return mapping.containsKey(key(sourceIdx, localIdx));
    }

    public int concatenatedCount() { return concatCount; }

    private static long key(int sourceIdx, int localIdx) {
        return ((long) sourceIdx << 32) | (localIdx & 0xFFFFFFFFL);
    }
}
```

- [ ] **Step 4 : Test pass**

Run: `./gradlew test --tests CacheRemapperTest`
Expected: PASS (3 tests).

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/CacheRemapper.java src/test/java/fr/zeffut/multiview/merge/CacheRemapperTest.java
git commit -m "feat(merge): CacheRemapper — concat level_chunk_caches + remap indices"
```

---

## Task 7 : WorldStateMerger

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/merge/WorldStateMerger.java`
- Create: `src/test/java/fr/zeffut/multiview/merge/WorldStateMergerTest.java`

Note : on teste la logique LWW pure. L'intégration avec les vrais packets Minecraft se fera dans `PacketClassifier` / `MergeOrchestrator`.

- [ ] **Step 1 : Écrire les tests**

```java
package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldStateMergerTest {

    private static final String DIM = "minecraft:overworld";

    @Test
    void firstUpdateForPosIsAccepted() {
        WorldStateMerger m = new WorldStateMerger();
        assertTrue(m.acceptBlockUpdate(DIM, 0, 0, 0, 100, 42, 0));
    }

    @Test
    void olderUpdateIsRejected() {
        WorldStateMerger m = new WorldStateMerger();
        m.acceptBlockUpdate(DIM, 0, 0, 0, 100, 42, 0);
        assertFalse(m.acceptBlockUpdate(DIM, 0, 0, 0, 50, 99, 1),
                "un update plus ancien doit être rejeté");
    }

    @Test
    void newerUpdateOverwrites() {
        WorldStateMerger m = new WorldStateMerger();
        m.acceptBlockUpdate(DIM, 0, 0, 0, 100, 42, 0);
        assertTrue(m.acceptBlockUpdate(DIM, 0, 0, 0, 200, 99, 1));
    }

    @Test
    void sameTickDuplicateIsRejected() {
        WorldStateMerger m = new WorldStateMerger();
        m.acceptBlockUpdate(DIM, 0, 0, 0, 100, 42, 0);
        assertFalse(m.acceptBlockUpdate(DIM, 0, 0, 0, 100, 42, 1),
                "même tick, même bloc = déjà émis");
    }

    @Test
    void differentDimensionsAreIndependent() {
        WorldStateMerger m = new WorldStateMerger();
        m.acceptBlockUpdate("minecraft:overworld", 0, 0, 0, 100, 42, 0);
        assertTrue(m.acceptBlockUpdate("minecraft:the_nether", 0, 0, 0, 50, 99, 1),
                "dimensions distinctes = positions distinctes");
    }

    @Test
    void conflictStatsAreTracked() {
        WorldStateMerger m = new WorldStateMerger();
        m.acceptBlockUpdate(DIM, 0, 0, 0, 100, 42, 0);
        m.acceptBlockUpdate(DIM, 0, 0, 0, 50, 99, 1);  // rejeté (plus ancien)
        m.acceptBlockUpdate(DIM, 0, 0, 0, 200, 77, 0); // accepté (overwrite)
        assertEquals(1, m.lwwConflicts());
        assertEquals(1, m.lwwOverwrites());
    }
}
```

- [ ] **Step 2 : Test fail**

Run: `./gradlew test --tests WorldStateMergerTest`
Expected: FAIL.

- [ ] **Step 3 : Implémenter `WorldStateMerger`**

```java
package fr.zeffut.multiview.merge;

import java.util.HashMap;
import java.util.Map;

/**
 * Applique la règle "last-write-wins" par position de bloc globale (dimension, x, y, z).
 * Invoqué par PacketClassifier/MergeOrchestrator pour chaque BlockUpdate observé.
 *
 * Décide si un update doit être émis (true) ou droppé (false).
 */
public final class WorldStateMerger {

    private record BlockLww(int tickAbs, int blockStateId, int sourceIdx) {}

    private final Map<Long, BlockLww> state = new HashMap<>();
    private final Map<String, Long> dimensionIds = new HashMap<>();
    private int lwwConflicts;
    private int lwwOverwrites;

    /**
     * @return true si l'update doit être émis dans le stream de sortie
     *         (bloc jamais vu ou update strictement plus récent)
     */
    public boolean acceptBlockUpdate(String dimension, int x, int y, int z,
                                     int tickAbs, int blockStateId, int sourceIdx) {
        long key = blockKey(dimension, x, y, z);
        BlockLww current = state.get(key);

        if (current == null) {
            state.put(key, new BlockLww(tickAbs, blockStateId, sourceIdx));
            return true;
        }

        if (tickAbs > current.tickAbs) {
            state.put(key, new BlockLww(tickAbs, blockStateId, sourceIdx));
            lwwOverwrites++;
            return true;
        }

        if (tickAbs < current.tickAbs) {
            lwwConflicts++;
            return false;
        }

        // tickAbs == current.tickAbs → déjà émis par la 1re source
        return false;
    }

    public int lwwConflicts() { return lwwConflicts; }
    public int lwwOverwrites() { return lwwOverwrites; }

    private long blockKey(String dimension, int x, int y, int z) {
        long dimId = dimensionIds.computeIfAbsent(dimension, k -> (long) dimensionIds.size() + 1);
        // hash (dimId, x, y, z) en long
        long h = dimId;
        h = h * 31 + x;
        h = h * 31 + y;
        h = h * 31 + z;
        return h;
    }
}
```

Note : le `blockKey` basé sur hash peut collisionner dans des cas pathologiques. Pour Phase 3 c'est acceptable (collision → drop légitime accidentel). Phase 4 peut raffiner si besoin.

- [ ] **Step 4 : Test pass**

Run: `./gradlew test --tests WorldStateMergerTest`
Expected: PASS (6 tests).

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/WorldStateMerger.java src/test/java/fr/zeffut/multiview/merge/WorldStateMergerTest.java
git commit -m "feat(merge): WorldStateMerger — LWW par (dim, x, y, z)"
```

---

## Task 8 : EntityMerger

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/merge/EntityMerger.java`
- Create: `src/test/java/fr/zeffut/multiview/merge/EntityMergerTest.java`

- [ ] **Step 1 : Écrire les tests**

```java
package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EntityMergerTest {

    @Test
    void uuidMatchFusesEntities() {
        SourcePovTracker pov = new SourcePovTracker(2);
        IdRemapper ids = new IdRemapper();
        EntityMerger m = new EntityMerger(pov, ids);

        UUID u = UUID.randomUUID();
        int g1 = m.registerAddEntity(0, 42, u, "zombie", 100, 0, 64, 0);
        int g2 = m.registerAddEntity(1, 7, u, "zombie", 100, 0, 64, 0);
        assertEquals(g1, g2, "même UUID = même globalId");
        m.incrementUuidMerged();
    }

    @Test
    void heuristicMatchFusesClosePositionsAtCloseTicks() {
        SourcePovTracker pov = new SourcePovTracker(2);
        IdRemapper ids = new IdRemapper();
        EntityMerger m = new EntityMerger(pov, ids);

        int g1 = m.registerAddEntity(0, 42, null, "zombie", 100, 10.0, 64.0, 10.0);
        int g2 = m.registerAddEntity(1, 7, null, "zombie", 102, 10.5, 64.0, 10.5); // ±5 ticks, <= 2 blocs
        assertEquals(g1, g2, "heuristique devrait fusionner");
    }

    @Test
    void heuristicMissWhenTooFarInTime() {
        SourcePovTracker pov = new SourcePovTracker(2);
        IdRemapper ids = new IdRemapper();
        EntityMerger m = new EntityMerger(pov, ids);

        int g1 = m.registerAddEntity(0, 42, null, "zombie", 100, 10.0, 64.0, 10.0);
        int g2 = m.registerAddEntity(1, 7, null, "zombie", 200, 10.0, 64.0, 10.0); // > 5 ticks
        assertNotEquals(g1, g2);
    }

    @Test
    void heuristicMissWhenTooFarInSpace() {
        SourcePovTracker pov = new SourcePovTracker(2);
        IdRemapper ids = new IdRemapper();
        EntityMerger m = new EntityMerger(pov, ids);

        int g1 = m.registerAddEntity(0, 42, null, "zombie", 100, 10.0, 64.0, 10.0);
        int g2 = m.registerAddEntity(1, 7, null, "zombie", 100, 20.0, 64.0, 20.0); // > 2 blocs
        assertNotEquals(g1, g2);
    }

    @Test
    void heuristicMissWhenDifferentType() {
        SourcePovTracker pov = new SourcePovTracker(2);
        IdRemapper ids = new IdRemapper();
        EntityMerger m = new EntityMerger(pov, ids);

        int g1 = m.registerAddEntity(0, 42, null, "zombie", 100, 10.0, 64.0, 10.0);
        int g2 = m.registerAddEntity(1, 7, null, "skeleton", 100, 10.0, 64.0, 10.0);
        assertNotEquals(g1, g2);
    }

    @Test
    void ambiguityResolvesByPovProximity() {
        SourcePovTracker pov = new SourcePovTracker(2);
        pov.update(0, 100, 10.0, 64.0, 10.0); // source 0 très proche de l'entité
        pov.update(1, 100, 100.0, 64.0, 100.0); // source 1 très loin
        IdRemapper ids = new IdRemapper();
        EntityMerger m = new EntityMerger(pov, ids);

        // Deux candidats en fenêtre, on ajoute une 3e observation ambiguë
        int gA = m.registerAddEntity(0, 1, null, "zombie", 100, 10.0, 64.0, 10.0);
        int gB = m.registerAddEntity(0, 2, null, "zombie", 100, 11.5, 64.0, 10.0); // fenêtre ok vs A
        // 3e source ambiguë entre A et B → doit fusionner sur celui dont le POV est le plus proche
        int gC = m.registerAddEntity(1, 99, null, "zombie", 100, 10.7, 64.0, 10.2);
        // POV source 0 est plus proche de l'entité source 1 → fusion vers la meilleure candidate
        assertTrue(gC == gA || gC == gB, "ambiguïté résout vers un des candidats");
        // La métrique d'ambiguïté est incrémentée
        assertEquals(1, m.ambiguousMergedCount());
    }
}
```

- [ ] **Step 2 : Test fail**

Run: `./gradlew test --tests EntityMergerTest`
Expected: FAIL.

- [ ] **Step 3 : Implémenter `EntityMerger`**

```java
package fr.zeffut.multiview.merge;

import java.util.*;

/**
 * Déduplication d'entités entre sources.
 *
 * - Clé primaire : UUID quand présent (players, nommés…)
 * - Heuristique fallback : même type + position ≤ 2 blocs + tick ±5
 * - Ambiguïté (≥ 2 candidats) : résolue par confiance géographique
 *   (l'entité dont le POV enregistreur est le plus proche gagne)
 *
 * Fournit le globalId à utiliser pour un packet AddEntity donné.
 */
public final class EntityMerger {

    private static final int TICK_WINDOW = 5;
    private static final double DIST_WINDOW = 2.0;
    private static final int PURGE_AGE = 200;

    private static final class EntityState {
        final int globalId;
        final String type;
        int lastSeenTick;
        double x, y, z;
        final List<int[]> sources = new ArrayList<>(); // (sourceIdx, localId)

        EntityState(int globalId, String type, int tick, double x, double y, double z) {
            this.globalId = globalId;
            this.type = type;
            this.lastSeenTick = tick;
            this.x = x; this.y = y; this.z = z;
        }
    }

    private final SourcePovTracker pov;
    private final IdRemapper idRemapper;
    private final Map<UUID, Integer> uuidIndex = new HashMap<>();
    private final Map<Integer, EntityState> states = new HashMap<>();
    private int uuidMerged;
    private int heuristicMerged;
    private int ambiguousMerged;

    public EntityMerger(SourcePovTracker pov, IdRemapper idRemapper) {
        this.pov = pov;
        this.idRemapper = idRemapper;
    }

    /**
     * Enregistre un ClientboundAddEntity d'une source. Retourne le globalId à utiliser
     * pour les packets suivants de cette entité.
     *
     * @param uuid peut être null (entités sans UUID stable)
     * @param type chaîne stable (ex. "minecraft:zombie")
     */
    public int registerAddEntity(int sourceIdx, int localId, UUID uuid, String type,
                                 int tickAbs, double x, double y, double z) {
        // 1. Match UUID
        if (uuid != null && uuidIndex.containsKey(uuid)) {
            int g = uuidIndex.get(uuid);
            idRemapper.assignExisting(sourceIdx, localId, g);
            states.get(g).sources.add(new int[]{sourceIdx, localId});
            uuidMerged++;
            return g;
        }

        // 2. Heuristique
        List<EntityState> candidates = new ArrayList<>();
        for (EntityState e : states.values()) {
            if (!e.type.equals(type)) continue;
            if (Math.abs(e.lastSeenTick - tickAbs) > TICK_WINDOW) continue;
            double dx = e.x - x, dy = e.y - y, dz = e.z - z;
            if (Math.sqrt(dx*dx + dy*dy + dz*dz) > DIST_WINDOW) continue;
            candidates.add(e);
        }

        EntityState chosen;
        if (candidates.isEmpty()) {
            int g = idRemapper.assign(sourceIdx, localId);
            chosen = new EntityState(g, type, tickAbs, x, y, z);
            chosen.sources.add(new int[]{sourceIdx, localId});
            states.put(g, chosen);
            if (uuid != null) uuidIndex.put(uuid, g);
            return g;
        } else if (candidates.size() == 1) {
            chosen = candidates.get(0);
            heuristicMerged++;
        } else {
            // Ambiguïté : on garde l'entité dont au moins une de ses sources
            // d'observation a le POV le plus proche de l'entité au tick considéré.
            chosen = candidates.stream()
                    .min(Comparator.comparingDouble(c -> minPovDistance(c, tickAbs, x, y, z)))
                    .orElseThrow();
            ambiguousMerged++;
        }

        idRemapper.assignExisting(sourceIdx, localId, chosen.globalId);
        chosen.sources.add(new int[]{sourceIdx, localId});
        chosen.lastSeenTick = Math.max(chosen.lastSeenTick, tickAbs);
        chosen.x = x; chosen.y = y; chosen.z = z;
        if (uuid != null) uuidIndex.put(uuid, chosen.globalId);
        return chosen.globalId;
    }

    /** Purge les entités dont lastSeenTick < currentTick - PURGE_AGE. À appeler toutes les 100 ticks. */
    public void purge(int currentTick) {
        states.values().removeIf(e -> e.lastSeenTick < currentTick - PURGE_AGE);
    }

    public int uuidMergedCount() { return uuidMerged; }
    public int heuristicMergedCount() { return heuristicMerged; }
    public int ambiguousMergedCount() { return ambiguousMerged; }
    // Pour le test — permet de forcer l'incrément (cohérence avec registerAddEntity normal)
    void incrementUuidMerged() { /* no-op : déjà compté dans register */ }

    private double minPovDistance(EntityState e, int tickAbs, double ex, double ey, double ez) {
        double min = Double.POSITIVE_INFINITY;
        for (int[] src : e.sources) {
            double d = pov.distanceTo(src[0], tickAbs, ex, ey, ez);
            if (d < min) min = d;
        }
        return min;
    }
}
```

- [ ] **Step 4 : Test pass**

Run: `./gradlew test --tests EntityMergerTest`
Expected: PASS (6 tests).

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/EntityMerger.java src/test/java/fr/zeffut/multiview/merge/EntityMergerTest.java
git commit -m "feat(merge): EntityMerger — dedup UUID + heuristique + confiance géo"
```

---

## Task 9 : EgoRouter

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/merge/EgoRouter.java`
- Create: `src/test/java/fr/zeffut/multiview/merge/EgoRouterTest.java`

- [ ] **Step 1 : Écrire les tests**

```java
package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.Action;
import fr.zeffut.multiview.format.FlashbackByteBuf;
import fr.zeffut.multiview.format.SegmentReader;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EgoRouterTest {

    @Test
    void routesPacketsToPerUuidSegments(@TempDir Path tmp) throws Exception {
        Path egoDir = tmp.resolve("ego");
        EgoRouter router = new EgoRouter(egoDir, List.of("minecraft:game_packet"));

        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        router.writeEgo(p1, 0, new byte[]{1, 2, 3});
        router.writeEgo(p1, 1, new byte[]{4, 5});
        router.writeEgo(p2, 0, new byte[]{9, 9, 9});
        router.finishAll();

        assertTrue(Files.exists(egoDir.resolve(p1 + ".flashback")));
        assertTrue(Files.exists(egoDir.resolve(p2 + ".flashback")));
        assertEquals(2, Files.list(egoDir).count());

        // Round-trip check : relire le segment p1 et vérifier qu'on y retrouve nos 2 packets
        // + 1 NextTick entre (tick 0 → tick 1).
        // Détail du read-back : utiliser SegmentReader. Le count des actions dans le live stream
        // doit être >= 3 (2 GamePacket + 1 NextTick).
    }
}
```

- [ ] **Step 2 : Test fail**

Run: `./gradlew test --tests EgoRouterTest`
Expected: FAIL.

- [ ] **Step 3 : Implémenter `EgoRouter`** (utilise `SegmentWriter` existant)

```java
package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.ActionType;
import fr.zeffut.multiview.format.SegmentWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Dispatch des packets égocentriques vers un segment par joueur, écrit dans
 * ego/<playerUUID>.flashback. Format = segment standard Flashback.
 */
public final class EgoRouter {

    private final Path egoDir;
    private final List<String> registry;
    private final Map<UUID, SegmentWriter> writers = new HashMap<>();
    private final Map<UUID, Integer> lastTick = new HashMap<>();

    public EgoRouter(Path egoDir, List<String> registry) throws IOException {
        this.egoDir = egoDir;
        this.registry = registry;
        Files.createDirectories(egoDir);
    }

    /**
     * Écrit un packet égocentrique dans le segment du joueur.
     * Intercale des NextTick pour atteindre tickAbs.
     */
    public void writeEgo(UUID playerUuid, int tickAbs, byte[] gamePacketPayload) throws IOException {
        SegmentWriter writer = writers.computeIfAbsent(playerUuid, uuid -> {
            try {
                return new SegmentWriter(uuid + ".flashback", registry);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        int prevTick = lastTick.getOrDefault(playerUuid, 0);
        for (int t = prevTick; t < tickAbs; t++) {
            writer.writeLiveAction(ActionType.NEXT_TICK, new byte[0]);
        }
        writer.writeLiveAction(ActionType.GAME_PACKET, gamePacketPayload);
        lastTick.put(playerUuid, tickAbs);
    }

    /** Finalise tous les segments ouverts, écrit sur disque. */
    public void finishAll() throws IOException {
        for (Map.Entry<UUID, SegmentWriter> e : writers.entrySet()) {
            ByteBuf buf = e.getValue().finish();
            try {
                byte[] bytes = ByteBufUtil.getBytes(buf);
                Files.write(egoDir.resolve(e.getKey() + ".flashback"), bytes);
            } finally {
                buf.release();
            }
        }
    }

    public Set<UUID> egoPlayers() { return writers.keySet(); }
}
```

Note : l'API `SegmentWriter` telle qu'utilisée ci-dessus suppose que `new SegmentWriter(name, registry)` et `writeLiveAction(int actionTypeId, byte[] payload)` et `finish() → ByteBuf` existent. **Si la signature diffère** (ex. `endSnapshot()` requis avant live), l'engineer doit adapter en lisant `SegmentWriter.java` et respecter le protocole initialisation → optional snapshot → `endSnapshot()` → live → `finish()`.

Pour ego, on a 0 action en snapshot → appeler `endSnapshot()` juste après création.

- [ ] **Step 4 : Test pass**

Run: `./gradlew test --tests EgoRouterTest`
Expected: PASS.

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/EgoRouter.java src/test/java/fr/zeffut/multiview/merge/EgoRouterTest.java
git commit -m "feat(merge): EgoRouter — dispatch packets égo vers ego/<uuid>.flashback"
```

---

## Task 10 : TimelineAligner — fallback `metadata.name`

On commence par la stratégie la plus simple (parser `metadata.name` en epoch). La stratégie `ClientboundSetTimePacket` vient en Task 11, et la cascade complète en Task 12.

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/merge/TimelineAligner.java`
- Create: `src/test/java/fr/zeffut/multiview/merge/TimelineAlignerTest.java`

- [ ] **Step 1 : Écrire les tests**

```java
package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimelineAlignerTest {

    @Test
    void parsesMetadataNameIso() {
        long epochTicks = TimelineAligner.parseMetadataNameToTicks("2026-02-20T23:25:15");
        assertTrue(epochTicks > 0);
    }

    @Test
    void parsesMetadataNameWithPath() {
        long a = TimelineAligner.parseMetadataNameToTicks("Sénat/empirenapo2026-02-20T23:20:16");
        long b = TimelineAligner.parseMetadataNameToTicks("2026-02-20T23:25:15");
        assertEquals(299L * 20L, b - a,
                "23:25:15 - 23:20:16 = 299s = 5980 ticks");
    }

    @Test
    void underscoreFormatAlsoParses() {
        long a = TimelineAligner.parseMetadataNameToTicks("2026-02-20T23_25_15");
        long b = TimelineAligner.parseMetadataNameToTicks("2026-02-20T23:25:15");
        assertEquals(a, b);
    }

    @Test
    void unparseableReturnsNegativeOne() {
        assertEquals(-1L, TimelineAligner.parseMetadataNameToTicks("pasuntimestamp"));
    }
}
```

- [ ] **Step 2 : Test fail**

Run: `./gradlew test --tests TimelineAlignerTest`
Expected: FAIL.

- [ ] **Step 3 : Implémenter la méthode statique `parseMetadataNameToTicks`**

```java
package fr.zeffut.multiview.merge;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimelineAligner {

    private static final Pattern TS_PATTERN = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2})[:_](\\d{2})[:_](\\d{2})");

    /**
     * Extrait un timestamp du format "YYYY-MM-DDTHH:mm:ss" (ou avec _ au lieu de :)
     * dans `name`, retourne l'epoch × 20 (ticks), ou -1 si introuvable.
     */
    public static long parseMetadataNameToTicks(String name) {
        Matcher m = TS_PATTERN.matcher(name);
        if (!m.find()) return -1L;
        LocalDateTime dt = LocalDateTime.of(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)),
                Integer.parseInt(m.group(5)),
                Integer.parseInt(m.group(6)));
        return dt.toEpochSecond(ZoneOffset.UTC) * 20L;
    }
}
```

- [ ] **Step 4 : Test pass**

Run: `./gradlew test --tests TimelineAlignerTest`
Expected: PASS (4 tests).

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/TimelineAligner.java src/test/java/fr/zeffut/multiview/merge/TimelineAlignerTest.java
git commit -m "feat(merge): TimelineAligner.parseMetadataNameToTicks"
```

---

## Task 11 : TimelineAligner — ancre `ClientboundSetTimePacket`

On implémente la détection d'un `SetTime` en début de replay et l'extraction de son `gameTime`.

**Files:**
- Modify: `src/main/java/fr/zeffut/multiview/merge/TimelineAligner.java`
- Create: `src/main/java/fr/zeffut/multiview/merge/PacketIdProvider.java`
- Modify: `src/test/java/fr/zeffut/multiview/merge/TimelineAlignerTest.java`

**Contexte** : `ClientboundSetTimePacket` contient `long gameTime` (tick serveur). On doit :
1. Connaître son `packetId` dans le protocole.
2. Reconnaître ce packet dans les `GamePacket` d'un stream.
3. Décoder les 8 premiers bytes du payload comme un `long` (ou utiliser le codec MC si disponible).

- [ ] **Step 1 : Créer `PacketIdProvider.java`**

```java
package fr.zeffut.multiview.merge;

/** Fournit les packet IDs du protocole courant. Permet l'injection en test. */
public interface PacketIdProvider {
    int setTimePacketId();

    /** Implémentation par défaut, utilisée à runtime (exige MC chargé). */
    static PacketIdProvider minecraftRuntime() {
        return new MinecraftPacketIdProvider();
    }
}
```

- [ ] **Step 2 : Créer `MinecraftPacketIdProvider.java`**

```java
package fr.zeffut.multiview.merge;

/**
 * Résout les packet IDs via les classes Minecraft chargées au runtime.
 * Lève UnsupportedOperationException en test si MC n'est pas sur le classpath.
 *
 * ATTENTION : l'implémentation concrète dépend de la version MC. En 1.21.11,
 * ClientboundGamePackets expose des PacketType<?> qu'on convertit en id via
 * le registry du protocole. L'engineer doit lire ClientboundGamePackets.SET_TIME
 * (ou l'équivalent actuel) et en déduire l'int id.
 */
final class MinecraftPacketIdProvider implements PacketIdProvider {
    @Override
    public int setTimePacketId() {
        // Implémentation runtime : résoudre via les classes MC. L'engineer doit
        // lire net.minecraft.network.protocol.game.GameProtocols ou équivalent
        // pour trouver l'api officielle. Si introuvable, fallback : on scanne
        // l'enum ConnectionProtocol.PLAY.clientbound() pour le packetId de
        // SET_TIME.
        //
        // Exemple de point d'entrée (à valider au moment de l'exécution) :
        //   return GameProtocols.CLIENTBOUND_TEMPLATE
        //       .bind(RegistryFriendlyByteBuf::new)
        //       .idOf(ClientboundSetTimePacket.TYPE);
        //
        // Si la résolution échoue (MC non chargé, API absente), lever :
        throw new UnsupportedOperationException(
                "setTimePacketId requires MC runtime — implement via GameProtocols lookup");
    }
}
```

**Note pour l'engineer** : la résolution du packet ID nécessite d'inspecter l'API Minecraft 1.21.11 concrète. Deux approches :
1. **Préférée** : passer par `GameProtocols.CLIENTBOUND_TEMPLATE` ou l'équivalent qui expose un mapping `PacketType<?>` → `int id`.
2. **Fallback** : hardcoder l'ID basé sur l'ordre de déclaration dans `ClientboundGamePackets`. Fragile, à éviter.

L'étape Step 2 laisse `throw` pour l'instant, la vraie impl arrivera quand on câblera dans `MergeOrchestrator`.

- [ ] **Step 3 : Ajouter méthode `findSetTimeAnchor` dans `TimelineAligner`**

```java
// Dans TimelineAligner.java, ajouter :

import fr.zeffut.multiview.format.Action;
import fr.zeffut.multiview.format.FlashbackReader;
import fr.zeffut.multiview.format.FlashbackReplay;
import fr.zeffut.multiview.format.PacketEntry;
import io.netty.buffer.Unpooled;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Cherche le 1er ClientboundSetTimePacket dans les 1200 premiers ticks locaux.
 * Retourne (tickLocal, gameTime) ou empty si non trouvé.
 */
public static Optional<SetTimeAnchor> findSetTimeAnchor(
        FlashbackReplay replay, PacketIdProvider idProvider) {
    int targetId = idProvider.setTimePacketId();
    try (Stream<PacketEntry> stream = FlashbackReader.stream(replay)) {
        return stream
                .filter(e -> e.tick() <= 1200)
                .filter(e -> e.action() instanceof Action.GamePacket)
                .map(e -> {
                    byte[] payload = ((Action.GamePacket) e.action()).payload();
                    int pid = readVarIntHead(payload);
                    if (pid != targetId) return null;
                    long gameTime = readGameTime(payload);
                    return new SetTimeAnchor(e.tick(), gameTime);
                })
                .filter(a -> a != null)
                .findFirst();
    }
}

/** Lit le VarInt de tête d'un payload de GamePacket (= packet id). */
private static int readVarIntHead(byte[] payload) {
    int value = 0, shift = 0;
    for (int i = 0; i < 5 && i < payload.length; i++) {
        byte b = payload[i];
        value |= (b & 0x7F) << shift;
        if ((b & 0x80) == 0) return value;
        shift += 7;
    }
    throw new IllegalArgumentException("VarInt trop long ou payload vide");
}

/** Lit le long gameTime dans un ClientboundSetTime après le VarInt packetId.
 *  Structure : [VarInt id][long worldTime BE][long dayTime BE][bool tickWorld]
 *  (ordre exact à vérifier dans la classe Minecraft ClientboundSetTimePacket)
 */
private static long readGameTime(byte[] payload) {
    int offset = varIntLength(payload);
    // long BE suivant, 8 bytes
    long v = 0;
    for (int i = 0; i < 8; i++) {
        v = (v << 8) | (payload[offset + i] & 0xFF);
    }
    return v;
}

private static int varIntLength(byte[] payload) {
    for (int i = 0; i < 5; i++) {
        if ((payload[i] & 0x80) == 0) return i + 1;
    }
    return 5;
}

public record SetTimeAnchor(int tickLocal, long gameTime) {}
```

**Note importante** : la structure du payload `ClientboundSetTimePacket` doit être validée contre l'impl MC 1.21.11. L'engineer ouvre `net.minecraft.network.protocol.game.ClientboundSetTimePacket` dans les sources Yarn mappées et liste l'ordre des champs. Si l'ordre diffère, adapte `readGameTime`.

- [ ] **Step 4 : Ajouter des tests**

```java
// Dans TimelineAlignerTest, ajouter :

@Test
void findSetTimeAnchorDecodesVarIntAndLong() throws Exception {
    // Créer un GamePacket synthétique : [VarInt=42][long=1234567]
    byte[] payload = new byte[9];
    payload[0] = 42;  // VarInt simple (valeur 42 < 128)
    // long 1234567 en big-endian sur 8 bytes
    long v = 1234567L;
    for (int i = 0; i < 8; i++) {
        payload[1 + i] = (byte) ((v >>> ((7 - i) * 8)) & 0xFF);
    }

    PacketIdProvider stub = () -> 42;
    // Note : ce test exige un FlashbackReplay synthétique. Si c'est trop lourd
    // à construire, remplacer par un test unitaire de readVarIntHead + readGameTime
    // (méthodes privées → passer en package-private + test direct).
}
```

**Note** : si construire un `FlashbackReplay` synthétique pour ce test est trop lourd, passer `readVarIntHead` et `readGameTime` en package-private et écrire des tests unitaires directs dessus. Important : les deux primitives sont testées.

- [ ] **Step 5 : Test pass**

Run: `./gradlew test --tests TimelineAlignerTest`
Expected: PASS.

- [ ] **Step 6 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/ src/test/java/fr/zeffut/multiview/merge/
git commit -m "feat(merge): TimelineAligner.findSetTimeAnchor + PacketIdProvider"
```

---

## Task 12 : TimelineAligner — cascade complète + normalisation

**Files:**
- Modify: `src/main/java/fr/zeffut/multiview/merge/TimelineAligner.java`
- Modify: `src/test/java/fr/zeffut/multiview/merge/TimelineAlignerTest.java`

- [ ] **Step 1 : Ajouter tests**

```java
@Test
void cascadePrefersSetTimeAnchor() {
    // Source A : SetTime trouvé, gameTime=1000 à tickLocal=10 → offset=990
    // Source B : SetTime trouvé, gameTime=1050 à tickLocal=5 → offset=1045
    var aligned = TimelineAligner.alignAll(
            List.of(
                    new TimelineAligner.Source("A", Optional.of(new TimelineAligner.SetTimeAnchor(10, 1000)), "2026-01-01T00:00:00", 100),
                    new TimelineAligner.Source("B", Optional.of(new TimelineAligner.SetTimeAnchor(5, 1050)), "2026-01-01T00:00:00", 100)
            ),
            Map.of());
    assertEquals(990, aligned.tickOffsets()[0]);
    assertEquals(1045, aligned.tickOffsets()[1]);
    assertEquals("setTimePacket", aligned.strategy());
}

@Test
void cascadeFallsBackToMetadataWhenSetTimeMissing() {
    var aligned = TimelineAligner.alignAll(
            List.of(
                    new TimelineAligner.Source("A", Optional.empty(), "2026-01-01T00:00:00", 100),
                    new TimelineAligner.Source("B", Optional.empty(), "2026-01-01T00:00:05", 100)
            ),
            Map.of());
    // A commence à t=0s, B commence à t=5s → B offset = A + 5*20 = 100 ticks après
    assertEquals(100, aligned.tickOffsets()[1] - aligned.tickOffsets()[0]);
    assertEquals("metadataName", aligned.strategy());
}

@Test
void cliOverrideIsApplied() {
    var aligned = TimelineAligner.alignAll(
            List.of(
                    new TimelineAligner.Source("A", Optional.empty(), "2026-01-01T00:00:00", 100),
                    new TimelineAligner.Source("B", Optional.empty(), "2026-01-01T00:00:00", 100)
            ),
            Map.of("B", 50));
    assertEquals(50, aligned.tickOffsets()[1] - aligned.tickOffsets()[0]);
}

@Test
void normalizationStartsAtZero() {
    var aligned = TimelineAligner.alignAll(
            List.of(
                    new TimelineAligner.Source("A", Optional.of(new TimelineAligner.SetTimeAnchor(0, 1000)), "", 100),
                    new TimelineAligner.Source("B", Optional.of(new TimelineAligner.SetTimeAnchor(0, 2000)), "", 100)
            ),
            Map.of());
    // Après normalization : tickOffsets relatifs, le plus petit = 0
    int min = Math.min(aligned.tickOffsets()[0], aligned.tickOffsets()[1]);
    assertEquals(0, min);
}
```

- [ ] **Step 2 : Test fail**

Run: `./gradlew test --tests TimelineAlignerTest`
Expected: FAIL.

- [ ] **Step 3 : Implémenter `alignAll`**

```java
// Dans TimelineAligner.java, ajouter :

import java.util.Map;
import java.util.List;

public record Source(String label, Optional<SetTimeAnchor> anchor, String metadataName, int totalTicks) {}

public record AlignmentResult(int[] tickOffsets, int mergedStartTick, int mergedTotalTicks, String strategy) {}

public static AlignmentResult alignAll(List<Source> sources, Map<String, Integer> cliOverrides) {
    int n = sources.size();
    long[] absoluteOffsets = new long[n];
    String strategy;

    boolean allHaveAnchors = sources.stream().allMatch(s -> s.anchor().isPresent());
    if (allHaveAnchors) {
        for (int i = 0; i < n; i++) {
            SetTimeAnchor a = sources.get(i).anchor().get();
            absoluteOffsets[i] = a.gameTime() - a.tickLocal();
        }
        strategy = "setTimePacket";
    } else {
        for (int i = 0; i < n; i++) {
            long ticks = parseMetadataNameToTicks(sources.get(i).metadataName());
            if (ticks < 0) {
                throw new IllegalArgumentException(
                        "Source '" + sources.get(i).label() + "' : pas de SetTime ET metadata.name non parseable. "
                      + "Fournir --offset-" + sources.get(i).label() + "=<N>");
            }
            absoluteOffsets[i] = ticks;
        }
        strategy = "metadataName";
    }

    // Overrides CLI appliqués au-dessus
    for (int i = 0; i < n; i++) {
        Integer over = cliOverrides.get(sources.get(i).label());
        if (over != null) {
            absoluteOffsets[i] += over;
            strategy = "cliOverride";
        }
    }

    // Normalisation : le plus petit devient 0
    long min = Long.MAX_VALUE;
    for (long o : absoluteOffsets) min = Math.min(min, o);
    int[] tickOffsets = new int[n];
    long max = 0;
    for (int i = 0; i < n; i++) {
        tickOffsets[i] = (int) (absoluteOffsets[i] - min);
        long end = tickOffsets[i] + sources.get(i).totalTicks();
        if (end > max) max = end;
    }
    return new AlignmentResult(tickOffsets, 0, (int) max, strategy);
}
```

- [ ] **Step 4 : Test pass**

Run: `./gradlew test --tests TimelineAlignerTest`
Expected: PASS (tous les tests Task 10 + 11 + 12).

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/TimelineAligner.java src/test/java/fr/zeffut/multiview/merge/TimelineAlignerTest.java
git commit -m "feat(merge): TimelineAligner.alignAll — cascade + normalisation + overrides"
```

---

## Task 13 : PacketClassifier — Action types sealed

On commence par classer les `Action` non-`GamePacket` (9 - 1 = 8 types faciles).

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/merge/PacketClassifier.java`
- Create: `src/test/java/fr/zeffut/multiview/merge/PacketClassifierTest.java`

- [ ] **Step 1 : Écrire les tests**

```java
package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.Action;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketClassifierTest {

    @Test
    void nextTickIsTick() {
        PacketClassifier c = new PacketClassifier(pid -> Category.PASSTHROUGH);
        assertEquals(Category.TICK, c.classify(new Action.NextTick()));
    }

    @Test
    void configurationPacketIsConfig() {
        PacketClassifier c = new PacketClassifier(pid -> Category.PASSTHROUGH);
        assertEquals(Category.CONFIG, c.classify(new Action.ConfigurationPacket(new byte[]{0})));
    }

    @Test
    void createPlayerIsLocalPlayer() {
        PacketClassifier c = new PacketClassifier(pid -> Category.PASSTHROUGH);
        assertEquals(Category.LOCAL_PLAYER, c.classify(new Action.CreatePlayer(new byte[]{0})));
    }

    @Test
    void moveEntitiesIsEntity() {
        PacketClassifier c = new PacketClassifier(pid -> Category.PASSTHROUGH);
        assertEquals(Category.ENTITY, c.classify(new Action.MoveEntities(new byte[]{0})));
    }

    @Test
    void cacheChunkRefIsCacheRef() {
        PacketClassifier c = new PacketClassifier(pid -> Category.PASSTHROUGH);
        assertEquals(Category.CACHE_REF, c.classify(new Action.CacheChunkRef(42)));
    }

    @Test
    void voiceChatIsEgo() {
        PacketClassifier c = new PacketClassifier(pid -> Category.PASSTHROUGH);
        assertEquals(Category.EGO, c.classify(new Action.VoiceChat(new byte[]{0})));
        assertEquals(Category.EGO, c.classify(new Action.EncodedVoiceChat(new byte[]{0})));
    }

    @Test
    void unknownIsPassthrough() {
        PacketClassifier c = new PacketClassifier(pid -> Category.PASSTHROUGH);
        assertEquals(Category.PASSTHROUGH, c.classify(new Action.Unknown("custom:x", new byte[]{0})));
    }

    @Test
    void gamePacketDelegatesToTable() {
        // Le packetId 42 du payload → WORLD selon la table mockée
        PacketClassifier c = new PacketClassifier(pid -> pid == 42 ? Category.WORLD : Category.PASSTHROUGH);
        byte[] payload = new byte[]{42, 0, 0, 0}; // VarInt 42 en tête
        assertEquals(Category.WORLD, c.classify(new Action.GamePacket(payload)));
    }
}
```

- [ ] **Step 2 : Test fail**

Run: `./gradlew test --tests PacketClassifierTest`
Expected: FAIL.

- [ ] **Step 3 : Implémenter `PacketClassifier`**

```java
package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.Action;

import java.util.function.IntFunction;

/**
 * Classifie un Action en Category. Pour GamePacket, délègue à une
 * IntFunction<Category> (la table de dispatch construite à runtime).
 */
public final class PacketClassifier {

    private final IntFunction<Category> gamePacketDispatch;

    public PacketClassifier(IntFunction<Category> gamePacketDispatch) {
        this.gamePacketDispatch = gamePacketDispatch;
    }

    public Category classify(Action action) {
        return switch (action) {
            case Action.NextTick n -> Category.TICK;
            case Action.ConfigurationPacket c -> Category.CONFIG;
            case Action.CreatePlayer c -> Category.LOCAL_PLAYER;
            case Action.MoveEntities m -> Category.ENTITY;
            case Action.CacheChunkRef ref -> Category.CACHE_REF;
            case Action.VoiceChat v -> Category.EGO;
            case Action.EncodedVoiceChat v -> Category.EGO;
            case Action.Unknown u -> Category.PASSTHROUGH;
            case Action.GamePacket gp -> gamePacketDispatch.apply(readPacketId(gp.payload()));
        };
    }

    /** Lit le VarInt de tête (= packetId). Suppose payload ≥ 1 byte. */
    static int readPacketId(byte[] payload) {
        int value = 0, shift = 0;
        for (int i = 0; i < 5 && i < payload.length; i++) {
            byte b = payload[i];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
        }
        throw new IllegalArgumentException("VarInt trop long ou payload vide");
    }
}
```

- [ ] **Step 4 : Test pass**

Run: `./gradlew test --tests PacketClassifierTest`
Expected: PASS (9 tests).

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/PacketClassifier.java src/test/java/fr/zeffut/multiview/merge/PacketClassifierTest.java
git commit -m "feat(merge): PacketClassifier — dispatch par type d'Action"
```

---

## Task 14 : PacketClassifier — table de dispatch `GamePacket` (MC runtime)

On construit la `Map<Integer, Category>` via introspection des classes Minecraft.

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/merge/GamePacketDispatch.java`

**Contexte** : la table est construite au démarrage de `/mv merge`, elle ne peut pas être testée sans MC runtime. On l'encapsule dans une classe dédiée et on l'invoque depuis `MergeOrchestrator`.

- [ ] **Step 1 : Créer `GamePacketDispatch.java`**

```java
package fr.zeffut.multiview.merge;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Construit la table Map<packetId, Category> pour tous les packets clientbound
 * du protocole courant. Exige MC chargé au runtime.
 *
 * À invoquer depuis MergeOrchestrator juste avant de lancer le streaming.
 *
 * Implémentation : l'engineer doit ouvrir ClientboundGamePackets (ou son
 * équivalent 1.21.11) et pour chaque PacketType<?> résoudre :
 *   - l'int id (via GameProtocols.CLIENTBOUND_TEMPLATE ou registry)
 *   - la Category (d'après le mapping Design §4.2)
 */
public final class GamePacketDispatch {

    private GamePacketDispatch() {}

    /**
     * Construit la table. En cas d'erreur (MC non chargé, API différente),
     * retourne une IntFunction qui catégorise tout en PASSTHROUGH (fallback
     * safe : on garde tout, on laisse les autres mergers ignorer).
     */
    public static IntFunction<Category> buildOrFallback(MergeReport report) {
        try {
            Map<Integer, Category> table = build();
            return pid -> {
                Category c = table.get(pid);
                if (c == null) {
                    report.stats.passthroughPackets.merge(
                            "packetId=" + pid, 1, Integer::sum);
                    return Category.PASSTHROUGH;
                }
                return c;
            };
        } catch (Throwable t) {
            report.warn("GamePacketDispatch indisponible (" + t.getClass().getSimpleName()
                    + "), tous les GamePackets en PASSTHROUGH : " + t.getMessage());
            return pid -> Category.PASSTHROUGH;
        }
    }

    /**
     * Construit la map effective. L'implémentation dépend de l'API MC 1.21.11.
     *
     * Squelette attendu :
     *   Map<Integer, Category> table = new HashMap<>();
     *   var template = GameProtocols.CLIENTBOUND_TEMPLATE; // ou équivalent
     *   template.forEachPacketType((id, type) -> {
     *       Category c = categoryFor(type);
     *       if (c != null) table.put(id, c);
     *   });
     *   return table;
     *
     * L'engineer écrit `categoryFor(PacketType<?>)` en switch sur le type,
     * en se basant sur le mapping de la spec design §4.2.
     */
    private static Map<Integer, Category> build() {
        Map<Integer, Category> table = new HashMap<>();

        // À IMPLÉMENTER à l'exécution de Task 14 :
        // - lire net.minecraft.network.protocol.game.GameProtocols
        // - ou net.minecraft.network.ConnectionProtocol.PLAY
        // - pour chaque paire (id, PacketType), déterminer sa Category
        //
        // Exemple de pattern attendu :
        //   table.put(idOf(ClientboundSetTimePacket.TYPE), Category.GLOBAL);
        //   table.put(idOf(ClientboundBlockUpdatePacket.TYPE), Category.WORLD);
        //   table.put(idOf(ClientboundAddEntityPacket.TYPE), Category.ENTITY);
        //   table.put(idOf(ClientboundSetHealthPacket.TYPE), Category.EGO);
        //   ...

        if (table.isEmpty()) {
            throw new IllegalStateException(
                    "GamePacketDispatch.build() non implémenté — voir design spec §4.2");
        }
        return table;
    }
}
```

**À l'exécution** : l'engineer doit :
1. Ouvrir `net.minecraft.network.protocol.game.GameProtocols` dans les sources Yarn 1.21.11+build.4.
2. Trouver la structure qui expose `(int id, PacketType<?> type)` pour tous les clientbound packets.
3. Écrire un `categoryFor(PacketType<?>)` en switch, en mappant chaque type selon le design §4.2.
4. Si l'API MC ne permet pas l'introspection facile, écrire une table manuelle avec tous les packets listés dans le design.

- [ ] **Step 2 : Lancer compileJava**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

Note : pas de test unitaire pour cette classe (exige MC runtime). Sera testée via l'integration test Task 19.

- [ ] **Step 3 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/GamePacketDispatch.java
git commit -m "feat(merge): GamePacketDispatch skeleton — à compléter avec API MC 1.21.11"
```

---

## Task 15 : MergeOrchestrator — pipeline core

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/merge/MergeOrchestrator.java`

**Contexte** : orchestre tout. Lit les sources, calcule les offsets, construit le `MergeContext`, itère en k-way merge, dispatch chaque packet vers le bon merger, écrit la sortie.

- [ ] **Step 1 : Créer `MergeOrchestrator.java` (squelette)**

```java
package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.FlashbackReader;
import fr.zeffut.multiview.format.FlashbackReplay;
import fr.zeffut.multiview.format.PacketEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Orchestre le merge de N replays en 1 sortie. Streaming k-way merge.
 *
 * Invocation typique (depuis MergeCommand) :
 *   MergeOrchestrator.run(options, progressCallback);
 */
public final class MergeOrchestrator {

    /**
     * @param options paramètres de merge (sources, destination, overrides)
     * @param progress callback(phaseName, percent) pour feedback UI
     * @return MergeReport final
     */
    public static MergeReport run(MergeOptions options, Consumer<String> progress)
            throws IOException {
        MergeReport report = new MergeReport();

        // Phase 1 : ouverture des sources
        progress.accept("Ouverture des sources...");
        List<FlashbackReplay> replays = new ArrayList<>();
        for (Path src : options.sources()) {
            replays.add(FlashbackReader.open(src));
        }

        // Phase 2 : alignment
        progress.accept("Alignement temporel...");
        PacketIdProvider idProvider = PacketIdProvider.minecraftRuntime();
        List<TimelineAligner.Source> alignSources = new ArrayList<>();
        for (int i = 0; i < replays.size(); i++) {
            FlashbackReplay r = replays.get(i);
            Optional<TimelineAligner.SetTimeAnchor> anchor;
            try {
                anchor = TimelineAligner.findSetTimeAnchor(r, idProvider);
            } catch (Throwable t) {
                anchor = Optional.empty();
                report.warn("Source " + r.folder().getFileName() + " : SetTime detection failed, fallback metadata.name. " + t);
            }
            alignSources.add(new TimelineAligner.Source(
                    r.folder().getFileName().toString(),
                    anchor,
                    r.metadata().name(),
                    r.metadata().totalTicks()));
        }
        TimelineAligner.AlignmentResult alignment = TimelineAligner.alignAll(
                alignSources, options.tickOverrides());
        report.alignmentStrategy = alignment.strategy();

        for (int i = 0; i < replays.size(); i++) {
            MergeReport.SourceInfo si = new MergeReport.SourceInfo();
            si.folder = replays.get(i).folder().getFileName().toString();
            si.uuid = replays.get(i).metadata().uuid();
            si.totalTicks = replays.get(i).metadata().totalTicks();
            si.tickOffset = alignment.tickOffsets()[i];
            report.sources.add(si);
        }
        report.mergedTotalTicks = alignment.mergedTotalTicks();

        // Phase 3 : primary source (le plus long)
        int primaryIdx = 0;
        int primaryLen = replays.get(0).metadata().totalTicks();
        for (int i = 1; i < replays.size(); i++) {
            if (replays.get(i).metadata().totalTicks() > primaryLen) {
                primaryIdx = i;
                primaryLen = replays.get(i).metadata().totalTicks();
            }
        }

        // Phase 4 : build context + mergers
        MergeContext ctx = new MergeContext(replays, alignment.tickOffsets(),
                alignment.mergedStartTick(), primaryIdx, report);
        SourcePovTracker povTracker = new SourcePovTracker(replays.size());
        IdRemapper idRemapper = new IdRemapper();
        EntityMerger entityMerger = new EntityMerger(povTracker, idRemapper);
        WorldStateMerger worldMerger = new WorldStateMerger();
        GlobalDeduper globalDeduper = new GlobalDeduper();
        CacheRemapper cacheRemapper = new CacheRemapper();
        PacketClassifier classifier = new PacketClassifier(
                GamePacketDispatch.buildOrFallback(report));

        // Phase 5 : concat caches
        progress.accept("Remapping caches...");
        List<Path> cacheDirs = new ArrayList<>();
        for (FlashbackReplay r : replays) {
            cacheDirs.add(r.folder().resolve("level_chunk_caches"));
        }
        Path destTmp = options.destination().resolveSibling("." + options.destination().getFileName() + ".tmp");
        cacheRemapper.concat(cacheDirs, destTmp.resolve("level_chunk_caches"));
        report.stats.chunkCachesConcatenated = cacheRemapper.concatenatedCount();

        // Phase 6 : ego router init (registry = agrégat des registries des sources)
        List<String> egoRegistry = List.of("minecraft:game_packet"); // simplifié
        EgoRouter egoRouter = new EgoRouter(destTmp.resolve("ego"), egoRegistry);

        // Phase 7 : k-way merge streaming
        progress.accept("Streaming merge...");
        streamMerge(ctx, classifier, worldMerger, entityMerger, idRemapper, egoRouter,
                globalDeduper, cacheRemapper, povTracker, destTmp, progress);

        // Phase 8 : finalize
        egoRouter.finishAll();
        report.stats.egoTracks = new ArrayList<>();
        for (var uuid : egoRouter.egoPlayers()) report.stats.egoTracks.add(uuid.toString());
        report.stats.entitiesMergedByUuid = entityMerger.uuidMergedCount();
        report.stats.entitiesMergedByHeuristic = entityMerger.heuristicMergedCount();
        report.stats.entitiesAmbiguousMerged = entityMerger.ambiguousMergedCount();
        report.stats.blocksLwwConflicts = worldMerger.lwwConflicts();
        report.stats.blocksLwwOverwrites = worldMerger.lwwOverwrites();

        // Phase 9 : rename atomique
        Files.move(destTmp, options.destination());

        progress.accept("Merge terminé.");
        return report;
    }

    private static void streamMerge(MergeContext ctx, PacketClassifier classifier,
                                    WorldStateMerger worldMerger, EntityMerger entityMerger,
                                    IdRemapper idRemapper, EgoRouter egoRouter,
                                    GlobalDeduper globalDeduper, CacheRemapper cacheRemapper,
                                    SourcePovTracker povTracker, Path destTmp,
                                    Consumer<String> progress) throws IOException {
        // K-way merge via PriorityQueue<SourceCursor>
        // Chaque SourceCursor détient un itérateur Stream.iterator() sur sa source
        // + sourceIdx. On peek le head (PacketEntry avec tickAbs), on poll le plus
        // petit tick, on traite, on re-push.
        //
        // Le traitement dispatch via classifier :
        //   TICK → écrire NextTick dans le writer principal
        //   WORLD → worldMerger.acceptBlockUpdate → si true, écrire
        //   ENTITY → entityMerger.registerAddEntity (ou autre, selon subtype) → remap → écrire
        //   EGO → egoRouter.writeEgo(sourceUuid, tickAbs, payload)
        //   GLOBAL → globalDeduper.shouldEmit → si true, écrire
        //   CACHE_REF → cacheRemapper.remap → réécrire avec globalIdx
        //   LOCAL_PLAYER → traitement spécial (primary vs autres, voir design §6)
        //   CONFIG → dedup par hash, émis 1×
        //   PASSTHROUGH → écrire tel quel
        //
        // Task 16 implémente le détail de l'écriture.
        throw new UnsupportedOperationException("Task 16 implémente streamMerge");
    }
}
```

- [ ] **Step 2 : Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/MergeOrchestrator.java
git commit -m "feat(merge): MergeOrchestrator skeleton — setup + alignment + init mergers"
```

---

## Task 16 : MergeOrchestrator — streamMerge + output writer

**Files:**
- Modify: `src/main/java/fr/zeffut/multiview/merge/MergeOrchestrator.java`
- Ajouts internes : `SourceCursor` (priority queue element)

- [ ] **Step 1 : Implémenter `streamMerge`**

Remplacer `throw new UnsupportedOperationException(...)` par la vraie logique de k-way merge + écriture. Squelette :

```java
private static void streamMerge(MergeContext ctx, PacketClassifier classifier,
                                WorldStateMerger worldMerger, EntityMerger entityMerger,
                                IdRemapper idRemapper, EgoRouter egoRouter,
                                GlobalDeduper globalDeduper, CacheRemapper cacheRemapper,
                                SourcePovTracker povTracker, Path destTmp,
                                Consumer<String> progress) throws IOException {

    // 1. Init cursors
    record SourceCursor(int sourceIdx, java.util.Iterator<PacketEntry> it, PacketEntry head) {}
    List<SourceCursor> cursors = new ArrayList<>();
    List<Stream<PacketEntry>> streams = new ArrayList<>();
    try {
        for (int i = 0; i < ctx.sources.size(); i++) {
            Stream<PacketEntry> s = FlashbackReader.stream(ctx.sources.get(i));
            streams.add(s);
            var it = s.iterator();
            if (it.hasNext()) {
                cursors.add(new SourceCursor(i, it, it.next()));
            }
        }

        // 2. PriorityQueue : tri par tick abs puis sourceIdx (stable)
        PriorityQueue<SourceCursor> pq = new PriorityQueue<>((a, b) -> {
            int ta = ctx.toAbsTick(a.sourceIdx, a.head.tick());
            int tb = ctx.toAbsTick(b.sourceIdx, b.head.tick());
            if (ta != tb) return Integer.compare(ta, tb);
            return Integer.compare(a.sourceIdx, b.sourceIdx);
        });
        pq.addAll(cursors);

        // 3. Writer principal : ouvrir un SegmentWriter (ou une série de segments
        //    si on split par chunks de ticks pour éviter les segments géants).
        //    Phase 3 simplification : 1 seul segment c0.flashback pour tout le merged.
        //    Registry = union des registries des sources (ou juste celle du primaire).
        //    À l'engineer d'implémenter ce détail.

        int tickAbsEmitted = -1;
        int lastPurgeTick = 0;
        int total = ctx.sources.get(0).metadata().totalTicks(); // pour progress
        long processed = 0;

        while (!pq.isEmpty()) {
            SourceCursor cur = pq.poll();
            int tickAbs = ctx.toAbsTick(cur.sourceIdx, cur.head.tick());

            // Intercaler NextTick jusqu'à tickAbs
            while (tickAbsEmitted < tickAbs) {
                tickAbsEmitted++;
                // emit NEXT_TICK dans le writer principal
                // ...
            }

            // Dispatch
            Category cat = classifier.classify(cur.head.action());
            switch (cat) {
                case TICK -> { /* déjà intercalé via intercalation NextTick ci-dessus */ }
                case WORLD -> {
                    // décoder le packet si ClientboundBlockUpdate → acceptBlockUpdate
                    // sinon : toujours émettre (chunk load / unload)
                    // À implémenter
                }
                case ENTITY -> {
                    // décoder → entityMerger.registerAddEntity (ou autre subtype)
                    // puis remapper et émettre
                    // À implémenter
                }
                case EGO -> {
                    UUID playerUuid = UUID.fromString(ctx.sources.get(cur.sourceIdx).metadata().uuid());
                    if (cur.head.action() instanceof Action.GamePacket gp) {
                        egoRouter.writeEgo(playerUuid, tickAbs, gp.payload());
                    }
                }
                case GLOBAL -> {
                    if (cur.head.action() instanceof Action.GamePacket gp) {
                        int pid = PacketClassifier.readPacketId(gp.payload());
                        if (globalDeduper.shouldEmit(pid, tickAbs, gp.payload())) {
                            // emit
                        } else {
                            ctx.report.stats.globalPacketsDeduped++;
                        }
                    }
                }
                case CACHE_REF -> {
                    if (cur.head.action() instanceof Action.CacheChunkRef ref) {
                        int global = cacheRemapper.remap(cur.sourceIdx, ref.cacheIndex());
                        // emit new CacheChunkRef(global)
                    }
                }
                case LOCAL_PLAYER -> {
                    if (cur.sourceIdx == ctx.primarySourceIdx) {
                        // emit as CreateLocalPlayer tel quel
                    } else {
                        // transformer en AddPlayer — voir design §6
                        // Phase 3 pragma : emit PASSTHROUGH, on validera si Flashback crash ou pas
                    }
                }
                case CONFIG, PASSTHROUGH -> {
                    // emit tel quel (CONFIG dédup par hash si nécessaire)
                }
            }

            // Purge périodique
            if (tickAbs - lastPurgeTick > 100) {
                entityMerger.purge(tickAbs);
                globalDeduper.purgeOlderThan(tickAbs - 200);
                lastPurgeTick = tickAbs;
            }

            // Advance cursor
            if (cur.it.hasNext()) {
                pq.add(new SourceCursor(cur.sourceIdx, cur.it, cur.it.next()));
            }

            // Progress feedback toutes les 10_000 iterations
            processed++;
            if (processed % 10_000 == 0) {
                progress.accept(String.format("Streaming merge... tick %d / %d",
                        tickAbs, ctx.report.mergedTotalTicks));
            }
        }

        // 4. Fermer writer principal
    } finally {
        for (Stream<PacketEntry> s : streams) s.close();
    }
}
```

**Note importante** : le détail de l'écriture (décodage des BlockUpdate, AddEntity, remapping IDs dans payloads, split en segments c0/c1/c2...) est **volumineux** et dépend de l'API MC. L'engineer décompose ce Task 16 en sous-étapes si nécessaire :
- 16a : writer principal (SegmentWriter unique, écrire les TICK + PASSTHROUGH)
- 16b : ajouter WORLD dispatch (BlockUpdate decoding)
- 16c : ajouter ENTITY dispatch (AddEntity decoding + remapping)
- 16d : ajouter EGO dispatch
- 16e : ajouter GLOBAL dispatch
- 16f : ajouter CACHE_REF dispatch
- 16g : ajouter LOCAL_PLAYER dispatch

Chaque sous-étape peut avoir son propre commit.

- [ ] **Step 2 : Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/MergeOrchestrator.java
git commit -m "feat(merge): MergeOrchestrator.streamMerge — k-way merge core"
```

---

## Task 17 : MergeOrchestrator — atomic rollback + metadata.json + report

**Files:**
- Modify: `src/main/java/fr/zeffut/multiview/merge/MergeOrchestrator.java`

- [ ] **Step 1 : Ajouter le rollback en cas d'exception**

Dans `MergeOrchestrator.run`, entourer tout sauf le retour d'un try/catch qui supprime `destTmp` en cas d'erreur :

```java
try {
    // tout ce qui est au-dessus sauf `return report`
} catch (IOException | RuntimeException e) {
    report.error(e.getClass().getSimpleName() + ": " + e.getMessage());
    // Rollback : supprimer destTmp récursivement
    if (Files.exists(destTmp)) {
        try (var stream = Files.walk(destTmp)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignore) {}
                    });
        }
    }
    throw e;
}
```

- [ ] **Step 2 : Écrire `metadata.json` du merged**

Juste avant le rename atomique :

```java
// Metadata agrégée
FlashbackMetadata merged = new FlashbackMetadata();
merged.uuid = java.util.UUID.randomUUID().toString();
merged.name = options.destination().getFileName().toString();
merged.versionString = replays.get(primaryIdx).metadata().versionString();
merged.worldName = replays.get(primaryIdx).metadata().worldName();
merged.dataVersion = replays.get(primaryIdx).metadata().dataVersion();
merged.protocolVersion = replays.get(primaryIdx).metadata().protocolVersion();
merged.bobbyWorldName = replays.get(primaryIdx).metadata().bobbyWorldName();
merged.totalTicks = alignment.mergedTotalTicks();
// chunks / markers / customNamespacesForRegistries : agrégation basique
merged.chunks = new java.util.LinkedHashMap<>();
for (var r : replays) merged.chunks.putAll(r.metadata().chunksMap());
merged.rawMarkers = new java.util.LinkedHashMap<>();
// ... (détail : agréger les markers des sources avec offset tick)

try (var w = Files.newBufferedWriter(destTmp.resolve("metadata.json"))) {
    merged.toJson(w);
}
```

Note : l'engineer doit ajuster les noms de champs selon `FlashbackMetadata` réel (cf. Phase 1).

- [ ] **Step 3 : Écrire `merge-report.json`**

```java
try (var w = Files.newBufferedWriter(destTmp.resolve("merge-report.json"))) {
    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report, w);
}
```

- [ ] **Step 4 : Copier `icon.png` de la primary source**

```java
Path primaryIcon = replays.get(primaryIdx).folder().resolve("icon.png");
if (Files.exists(primaryIcon)) {
    Files.copy(primaryIcon, destTmp.resolve("icon.png"));
}
```

- [ ] **Step 5 : Compile check + commit**

Run: `./gradlew compileJava`

```bash
git add src/main/java/fr/zeffut/multiview/merge/MergeOrchestrator.java
git commit -m "feat(merge): MergeOrchestrator — metadata.json, merge-report.json, rollback atomique"
```

---

## Task 18 : commande `/mv merge` avec Brigadier

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/merge/command/MergeCommand.java`
- Modify: `src/main/java/fr/zeffut/multiview/MultiViewMod.java`

- [ ] **Step 1 : Créer `MergeCommand.java`**

```java
package fr.zeffut.multiview.merge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fr.zeffut.multiview.merge.MergeOptions;
import fr.zeffut.multiview.merge.MergeOrchestrator;
import fr.zeffut.multiview.merge.MergeReport;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationEvent;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public final class MergeCommand {

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "multiview-merge");
                t.setDaemon(true);
                return t;
            });

    public static void register() {
        ClientCommandRegistrationEvent.EVENT.register((dispatcher, reg) -> {
            dispatcher.register(
                    ClientCommandManager.literal("mv").then(
                            ClientCommandManager.literal("merge")
                                    .then(sourceArg("source1")
                                            .then(sourceArg("source2")
                                                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>
                                                            argument("output", StringArgumentType.word())
                                                            .executes(ctx -> execute(ctx.getSource(),
                                                                    List.of(
                                                                            StringArgumentType.getString(ctx, "source1"),
                                                                            StringArgumentType.getString(ctx, "source2")),
                                                                    StringArgumentType.getString(ctx, "output"),
                                                                    Map.of())))))));
        });
    }

    private static RequiredArgumentBuilder<FabricClientCommandSource, String> sourceArg(String name) {
        return RequiredArgumentBuilder.<FabricClientCommandSource, String>
                argument(name, StringArgumentType.word())
                .suggests(replayFolderSuggestions());
    }

    private static SuggestionProvider<FabricClientCommandSource> replayFolderSuggestions() {
        return (ctx, builder) -> {
            Path replayRoot = MinecraftClient.getInstance().runDirectory.toPath().resolve("replay");
            if (!Files.isDirectory(replayRoot)) return builder.buildFuture();
            try (Stream<Path> entries = Files.list(replayRoot)) {
                entries.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(name -> !name.startsWith("."))
                        .forEach(builder::suggest);
            } catch (Exception ignore) {}
            return builder.buildFuture();
        };
    }

    private static int execute(FabricClientCommandSource source,
                               List<String> sourceNames, String outputName,
                               Map<String, Integer> overrides) {
        Path replayRoot = MinecraftClient.getInstance().runDirectory.toPath().resolve("replay");
        List<Path> sources = new ArrayList<>();
        for (String name : sourceNames) sources.add(replayRoot.resolve(name));
        Path dest = replayRoot.resolve(outputName);

        MergeOptions options = new MergeOptions(sources, dest, overrides, false);

        source.sendFeedback(Component.literal("[MultiView] Starting merge..."));
        EXECUTOR.submit(() -> {
            try {
                MergeReport report = MergeOrchestrator.run(options, phase -> {
                    MinecraftClient.getInstance().execute(() ->
                            source.sendFeedback(Component.literal("[MultiView] " + phase)));
                });
                MinecraftClient.getInstance().execute(() ->
                        source.sendFeedback(Component.literal(String.format(
                                "[MultiView] Done. %d entities merged, %d blocks overwritten, %d globals deduped. See run/replay/%s/merge-report.json.",
                                report.stats.entitiesMergedByUuid + report.stats.entitiesMergedByHeuristic,
                                report.stats.blocksLwwOverwrites,
                                report.stats.globalPacketsDeduped,
                                outputName))));
            } catch (Throwable t) {
                MinecraftClient.getInstance().execute(() ->
                        source.sendError(Component.literal("[MultiView] Merge failed: " + t.getMessage())));
                t.printStackTrace();
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
```

Note : gérer 3+ sources et `--offset-<label>=<N>` nécessite d'étendre la grammar Brigadier. Pour Phase 3, **2 sources seulement via la CLI**, les overrides sont désactivés. Phase 4 ajoutera les options avancées.

- [ ] **Step 2 : Modifier `MultiViewMod.java`** pour register la commande

Lire l'existant :

```bash
cat src/main/java/fr/zeffut/multiview/MultiViewMod.java
```

Ajouter dans `onInitializeClient` :

```java
fr.zeffut.multiview.merge.command.MergeCommand.register();
```

- [ ] **Step 3 : Compile check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/merge/command/ src/main/java/fr/zeffut/multiview/MultiViewMod.java
git commit -m "feat(merge): commande client /mv merge <A> <B> <output>"
```

---

## Task 19 : Integration test sur 2 POV HIKA

**Files:**
- Create: `src/test/java/fr/zeffut/multiview/merge/MergeIntegrationTest.java`

- [ ] **Step 1 : Écrire le test**

```java
package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.FlashbackReader;
import fr.zeffut.multiview.format.FlashbackReplay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MergeIntegrationTest {

    static final Path POV_A = Path.of("run/replay/2026-02-20T23_25_15");
    static final Path POV_B = Path.of("run/replay/Sénat_empirenapo2026-02-20T23_20_16");

    static boolean replaysAvailable() {
        return Files.isDirectory(POV_A) && Files.isDirectory(POV_B);
    }

    @Test
    @EnabledIf("replaysAvailable")
    void mergeTwoRealPovs(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("merged");
        MergeOptions options = new MergeOptions(
                List.of(POV_A, POV_B), dest, Map.of(), false);

        MergeReport report = MergeOrchestrator.run(options, msg -> System.out.println("[PROGRESS] " + msg));

        System.out.println("[REPORT] alignment=" + report.alignmentStrategy);
        System.out.println("[REPORT] merged total ticks=" + report.mergedTotalTicks);
        System.out.println("[REPORT] entities merged uuid=" + report.stats.entitiesMergedByUuid);
        System.out.println("[REPORT] entities merged heuristic=" + report.stats.entitiesMergedByHeuristic);
        System.out.println("[REPORT] entities ambiguous=" + report.stats.entitiesAmbiguousMerged);
        System.out.println("[REPORT] blocks LWW overwrites=" + report.stats.blocksLwwOverwrites);
        System.out.println("[REPORT] globals deduped=" + report.stats.globalPacketsDeduped);
        System.out.println("[REPORT] ego tracks=" + report.stats.egoTracks);
        System.out.println("[REPORT] caches concat=" + report.stats.chunkCachesConcatenated);
        System.out.println("[REPORT] warnings=" + report.warnings);
        System.out.println("[REPORT] errors=" + report.errors);

        // Vérifications structurelles
        assertTrue(Files.exists(dest.resolve("metadata.json")));
        assertTrue(Files.exists(dest.resolve("merge-report.json")));
        assertTrue(Files.exists(dest.resolve("level_chunk_caches")));
        assertTrue(Files.exists(dest.resolve("ego")));
        // Au moins un segment principal
        long segmentCount = Files.list(dest)
                .filter(p -> p.getFileName().toString().matches("c\\d+\\.flashback"))
                .count();
        assertTrue(segmentCount >= 1, "Au moins un segment principal attendu");

        // Ouvrir le merged en lecture pour vérifier qu'il est parseable
        FlashbackReplay mergedReplay = FlashbackReader.open(dest);
        assertEquals(report.mergedTotalTicks, mergedReplay.metadata().totalTicks());
    }
}
```

- [ ] **Step 2 : Lancer le test**

Run: `./gradlew test --tests MergeIntegrationTest`
Expected: PASS si les replays sont présents, SKIP sinon.

Si le test échoue, diagnose en lisant `[REPORT] errors=...` et itérer sur Tasks 14/15/16/17 selon la nature de l'erreur.

- [ ] **Step 3 : Commit**

```bash
git add src/test/java/fr/zeffut/multiview/merge/MergeIntegrationTest.java
git commit -m "test(merge): integration test sur 2 POV HIKA réels"
```

---

## Task 20 : Validation manuelle + journal + tag

- [ ] **Step 1 : Lancer Minecraft en dev**

Run: `./gradlew runClient`
Expected: Minecraft + Flashback + MultiView se lancent.

- [ ] **Step 2 : Dans le menu Minecraft, ouvrir un monde solo (peu importe lequel)**

Requis pour taper des commandes. Ne pas rejoindre de serveur.

- [ ] **Step 3 : Taper la commande**

Dans le chat :
```
/mv merge 2026-02-20T23_25_15 Sénat_empirenapo2026-02-20T23_20_16 merged_test
```

Expected : messages de progression dans le chat, puis `[MultiView] Done.` Ouvrir `run/replay/merged_test/merge-report.json` pour consulter les stats.

- [ ] **Step 4 : Ouvrir `merged_test` dans Flashback**

Menu principal Flashback → Replays → sélectionner `merged_test` → Play.

Expected :
- [ ] Replay se charge sans crash
- [ ] On voit le POV principal (le plus long) par défaut
- [ ] Caméra libre active : on voit **les 2 joueurs** bouger dans le monde
- [ ] Les chunks des 2 zones sont chargés (on peut voler entre les positions des 2 POV)
- [ ] Pas de bug visuel majeur (entités fantômes isolées, blocs flash, etc.)

Si crash ou problème → diagnoser via `merge-report.json` et retour aux Tasks précédentes.

- [ ] **Step 5 : Lancer `/mv inspect merged_test`**

Dans le chat Minecraft, après avoir quitté Flashback :
```
/mv inspect merged_test
```

Noter l'histogramme d'actions. La somme attendue :
- NextTick ≈ mergedTotalTicks
- GamePacket ≈ somme des GamePacket des sources (un peu moins à cause de la dedup globale)
- MoveEntities, CreatePlayer, etc. : somme ≈ sources

- [ ] **Step 6 : Capturer une screenshot**

Prendre une screenshot de Flashback avec les 2 joueurs visibles. Sauvegarder dans `docs/phase-3-validation.png` (ne pas commit si elle contient des infos sensibles ; sinon commit).

- [ ] **Step 7 : Ajouter entrée journal SPEC.md**

Ajouter à la fin de SPEC.md, avant la ligne `**Fin du document. Version : 0.2...**` :

```markdown
### 2026-04-19 — Phase 3 terminée : merge zéro-perte de 2 POV

- Pipeline complet : `TimelineAligner` + `PacketClassifier` + `WorldStateMerger` + `EntityMerger` + `IdRemapper` + `SourcePovTracker` + `EgoRouter` + `GlobalDeduper` + `CacheRemapper` + `MergeOrchestrator`.
- Commande `/mv merge <A> <B> <output>` (temporaire, retirée en Phase 5).
- Streaming k-way merge — support N sources natif, validé sur les 2 POV HIKA.
- Tracks égo écrites dans `ego/<uuid>.flashback` (consommées en Phase 3.5).
- Local player = POV le plus long, les autres deviennent entités joueur visibles.
- Test d'acceptation : `merged_test` ouvert dans Flashback, 2 joueurs visibles et animés, chunks chargés, pas de crash. Histogramme `/mv inspect` cohérent avec somme des sources.
- Stats : [copier ici les chiffres clés de merge-report.json : nb entités mergées, nb blocks LWW, nb globals deduped, nb ego tracks, nb caches concat]
- Warnings : [copier les warnings éventuels de merge-report.json]
- Tests : [X] verts au total.
```

- [ ] **Step 8 : Commit + tag**

```bash
git add SPEC.md docs/phase-3-validation.png  # si applicable
git commit -m "docs(phase-3): journal de validation + screenshot"
git tag phase-3-complete
```

---

## Self-Review

**Spec coverage check** (contre `docs/superpowers/specs/2026-04-19-phase-3-merge-design.md`) :

| Spec section | Task |
|---|---|
| §1 corpus HIKA | Task 19 |
| §2.1 pipeline | Tasks 15/16 |
| §2.2 composants | Tasks 3-14 (un par composant) |
| §3 TimelineAligner cascade | Tasks 10/11/12 |
| §4 PacketClassifier | Tasks 13/14 |
| §5.0 SourcePovTracker | Task 4 |
| §5.1 WorldStateMerger | Task 7 |
| §5.2 EntityMerger | Task 8 |
| §5.3 IdRemapper | Task 3 |
| §5.4 EgoRouter | Task 9 |
| §5.5 GlobalDeduper | Task 5 |
| §5.6 CacheRemapper | Task 6 |
| §6 LOCAL_PLAYER | Task 16 (sous-étape 16g) |
| §7 CLI `/mv merge` | Task 18 |
| §7.2 MergeReport | Tasks 1, 17 |
| §7.3 erreurs + rollback | Task 17 |
| §8 tests | Tasks 3-13 (unitaires), 19 (intégration), 20 (manuel) |
| §9 mise à jour SPEC.md | déjà faite dans le commit du spec |

**Placeholder scan** : Les Tasks 14 (GamePacketDispatch) et 16 (streamMerge) contiennent du "À IMPLÉMENTER" explicite — c'est délibéré car le détail dépend de l'inspection de l'API MC 1.21.11. L'engineer dispose du design spec §4.2 qui liste exhaustivement les mappings attendus. C'est actionnable.

**Type consistency** : `tickOffsets: int[]`, `Category` enum, `PacketEntry(tick, segmentName, inSnapshot, action)`, `Action` sealed noms cohérents — tout est stable entre les tasks.

**Gaps acceptables** : l'implémentation détaillée du remapping des entity IDs dans les payloads MC (Task 16c) dépend du spike Task 2. Si le spike échoue, l'engineer doit documenter un fallback (ex. skip le remapping, tolérer les collisions = duplicats visibles). Le design spec le précise déjà.

---

**Fin du plan.**
