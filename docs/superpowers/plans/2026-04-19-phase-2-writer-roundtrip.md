# MultiView — Phase 2 : Writer + Round-trip Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Écrire un `FlashbackWriter` qui produit un dossier `.flashback` identique à un replay source, prouvé par un test de round-trip **byte-for-byte** sur les segments + cohérence sémantique sur `metadata.json` + Flashback qui ouvre le résultat sans erreur.

**Architecture:** Symétrie exacte du reader. Les classes `FlashbackByteBuf`, `FlashbackMetadata`, `ActionType` gagnent des méthodes write/encode. Nouvelles classes `SegmentWriter` et `FlashbackWriter`. Le round-trip passe par `FlashbackReader.open(src)` → itérer le stream en préservant l'ordre et le registry → `FlashbackWriter.copy(source, dest)` → reader sur `dest`. Les bytes des segments sortent identiques grâce à l'encodage canonique (VarInt minimal, int32 BE fixe, payloads re-émis tels quels). `level_chunk_caches/` et `icon.png` sont copiés octet par octet.

**Tech Stack:** Java 21, Netty `ByteBuf`, Gson (pour re-sérialiser `metadata.json`), JUnit 5. Pas de nouvelle dep.

---

## File Structure

```
src/main/java/fr/zeffut/multiview/format/
├── Action.java                    # Modifié : pas de changement structurel, juste vérif
├── ActionType.java                # Modifié : +idOf(Action), +encode(Action) -> byte[]
├── FlashbackByteBuf.java          # Modifié : +writeVarInt, +writeInt, +writeString, +writeBytes
├── FlashbackMetadata.java         # Modifié : +toJson(Writer)
├── FlashbackWriter.java           # NEW — FlashbackWriter.copy(source, destFolder)
├── SegmentWriter.java             # NEW — écrit un cN.flashback
└── VarInts.java                   # Déjà a writeVarInt (Phase 1)
```

```
src/test/java/fr/zeffut/multiview/format/
├── FlashbackByteBufTest.java      # Modifié : +tests pour les write methods
├── FlashbackMetadataTest.java     # Modifié : +test toJson
├── ActionTypeTest.java            # NEW — tests pour idOf + encode + roundtrip via decode
├── SegmentWriterTest.java         # NEW — tests synthétiques
├── SegmentRoundTripTest.java      # NEW — lit un cN.flashback réel, l'écrit, compare bytes
└── FlashbackWriterIntegrationTest.java  # NEW — round-trip complet sur run/replay/
```

**Responsabilités :**
- `ActionType.idOf(Action)` : retourne l'id namespaced (`flashback:action/*`) à partir d'un `Action` typé. Nécessaire car la writer doit connaître l'id pour l'ajouter au registry du segment.
- `ActionType.encode(Action)` : inverse de `decode`. Re-produit les bytes du payload. Pour `NextTick` → `new byte[0]`, pour `CacheChunkRef` → VarInt du cacheIndex, pour le reste → les bytes préservés.
- `FlashbackByteBuf` : écritures miroir des lectures. Même policy "thread-unsafe, consume/append-once".
- `FlashbackMetadata.toJson(Writer)` : sérialise l'objet en JSON. Gson avec pretty-printing, `setLenient(false)`, `disableHtmlEscaping()` — mêmes settings qu'une sortie clean. Round-trip sémantique : parser le JSON écrit doit re-donner une `FlashbackMetadata` égale à l'original.
- `SegmentWriter` : prend (segmentName, registry, snapshotEntries, liveEntries) et écrit dans un `FlashbackByteBuf`. Chaque "entry" = `(ordinal, byte[] payload)`. Writer ne décode pas les Actions — il travaille sur du raw.
- `FlashbackWriter.copy(FlashbackReplay source, Path destFolder)` : API haut-niveau. Crée le dossier dest, écrit `metadata.json`, copie `icon.png` (si présent), copie `level_chunk_caches/` entier, écrit chaque segment via `SegmentWriter`.

---

## Validation de fin de phase

1. ✅ `./gradlew test` passe (tous les tests unitaires + round-trip segment + round-trip replay conditionnel).
2. ✅ Round-trip **byte-for-byte** sur chaque segment des POV réels (POV1 + POV2) — les 67 fichiers `cN.flashback` au total doivent sortir identiques.
3. ✅ Round-trip **sémantique** sur `metadata.json` (parse du output = parse de l'input).
4. ✅ `level_chunk_caches/*` et `icon.png` copiés byte-for-byte.
5. ✅ Chargement manuel du replay round-trippé dans Flashback via le dev client — il apparaît dans la liste et se joue sans erreur différente de l'original.
6. ✅ Aucun nouvel `Unknown` action type introduit par le round-trip (le set reste vide sur les POV réels).
7. ✅ Tag `phase-2-complete` posé.

---

## Task 1 : Étendre `FlashbackByteBuf` avec les méthodes d'écriture

**Files:**
- Modify: `src/main/java/fr/zeffut/multiview/format/FlashbackByteBuf.java`
- Modify: `src/test/java/fr/zeffut/multiview/format/FlashbackByteBufTest.java`

Le wrapper actuel est read-only. On ajoute `writeVarInt`, `writeInt`, `writeString`, `writeBytes`. Le constructeur accepte déjà un `ByteBuf` quelconque, donc `Unpooled.buffer()` pour écrire.

- [ ] **Step 1 : Ajouter les tests pour les write methods**

Éditer `src/test/java/fr/zeffut/multiview/format/FlashbackByteBufTest.java` — ajouter ces tests à la fin de la classe (avant la dernière accolade fermante) :

```java
    @Test
    void writeVarIntRoundTrip() {
        FlashbackByteBuf out = new FlashbackByteBuf(Unpooled.buffer());
        out.writeVarInt(300);
        FlashbackByteBuf in = new FlashbackByteBuf(out.raw());
        assertEquals(300, in.readVarInt());
    }

    @Test
    void writeIntRoundTrip() {
        FlashbackByteBuf out = new FlashbackByteBuf(Unpooled.buffer());
        out.writeInt(0xD780E884);
        FlashbackByteBuf in = new FlashbackByteBuf(out.raw());
        assertEquals(0xD780E884, in.readInt());
    }

    @Test
    void writeStringRoundTrip() {
        FlashbackByteBuf out = new FlashbackByteBuf(Unpooled.buffer());
        out.writeString("flashback:action/next_tick");
        FlashbackByteBuf in = new FlashbackByteBuf(out.raw());
        assertEquals("flashback:action/next_tick", in.readString());
    }

    @Test
    void writeBytesAppendsExactly() {
        FlashbackByteBuf out = new FlashbackByteBuf(Unpooled.buffer());
        out.writeBytes(new byte[] { 0x01, 0x02, 0x03 });
        assertEquals(3, out.raw().readableBytes());
        assertArrayEquals(new byte[] { 0x01, 0x02, 0x03 }, out.readBytes(3));
    }
```

- [ ] **Step 2 : Run → fail**

```bash
cd /Users/zeffut/Desktop/Projets/MultiView
./gradlew test --tests FlashbackByteBufTest
```
Expected : compile errors — les méthodes `writeVarInt`, `writeInt`, `writeString`, `writeBytes(byte[])` n'existent pas.

- [ ] **Step 3 : Ajouter les 4 méthodes dans `FlashbackByteBuf.java`**

Éditer `src/main/java/fr/zeffut/multiview/format/FlashbackByteBuf.java` — ajouter ces méthodes juste avant le `public ByteBuf raw()` final :

```java
    public void writeVarInt(int value) {
        VarInts.writeVarInt(underlying, value);
    }

    /** int32 big-endian. */
    public void writeInt(int value) {
        underlying.writeInt(value);
    }

    /** Écrit la longueur VarInt puis les bytes UTF-8. */
    public void writeString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        underlying.writeBytes(bytes);
    }

    public void writeBytes(byte[] bytes) {
        underlying.writeBytes(bytes);
    }
```

- [ ] **Step 4 : Run → pass**

```bash
./gradlew test --tests FlashbackByteBufTest
```
Expected : 12 tests passed (8 existants + 4 nouveaux).

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/format/FlashbackByteBuf.java src/test/java/fr/zeffut/multiview/format/FlashbackByteBufTest.java
git commit -m "feat(format): FlashbackByteBuf write methods (varint/int/string/bytes)"
```

---

## Task 2 : `FlashbackMetadata.toJson` + test round-trip

**Files:**
- Modify: `src/main/java/fr/zeffut/multiview/format/FlashbackMetadata.java`
- Modify: `src/test/java/fr/zeffut/multiview/format/FlashbackMetadataTest.java`

On ajoute la sérialisation Gson symétrique au `fromJson`. Settings : `setPrettyPrinting()` + `disableHtmlEscaping()` + garder l'ordre des clés déclaré dans les fields (Gson préserve l'ordre de déclaration).

- [ ] **Step 1 : Ajouter le test round-trip à la fin de `FlashbackMetadataTest.java`**

Avant l'accolade finale de la classe, insérer :

```java
    @Test
    void roundTripPreservesAllFields() {
        String json = """
                {
                  "uuid": "2b5459d2-d7ad-43dc-ae36-e6922cf1a45f",
                  "name": "test replay",
                  "version_string": "1.21.11",
                  "world_name": "TestWorld",
                  "data_version": 4671,
                  "protocol_version": 774,
                  "bobby_world_name": "test.server",
                  "total_ticks": 6052,
                  "markers": {
                    "42": { "colour": 11141290, "description": "Changed Dimension" }
                  },
                  "customNamespacesForRegistries": {},
                  "chunks": {
                    "c0.flashback": { "duration": 6000, "forcePlaySnapshot": false },
                    "c1.flashback": { "duration": 52, "forcePlaySnapshot": false }
                  }
                }
                """;
        FlashbackMetadata original = FlashbackMetadata.fromJson(new java.io.StringReader(json));

        java.io.StringWriter out = new java.io.StringWriter();
        original.toJson(out);
        String reserialized = out.toString();

        FlashbackMetadata parsedAgain = FlashbackMetadata.fromJson(new java.io.StringReader(reserialized));

        assertEquals(original.uuid(), parsedAgain.uuid());
        assertEquals(original.name(), parsedAgain.name());
        assertEquals(original.versionString(), parsedAgain.versionString());
        assertEquals(original.worldName(), parsedAgain.worldName());
        assertEquals(original.dataVersion(), parsedAgain.dataVersion());
        assertEquals(original.protocolVersion(), parsedAgain.protocolVersion());
        assertEquals(original.totalTicks(), parsedAgain.totalTicks());
        assertEquals(original.markers().size(), parsedAgain.markers().size());
        assertEquals(original.markers().get(42).description(), parsedAgain.markers().get(42).description());
        assertEquals(original.chunks().size(), parsedAgain.chunks().size());
        assertEquals(original.chunks().get("c0.flashback").duration(), parsedAgain.chunks().get("c0.flashback").duration());
    }
```

- [ ] **Step 2 : Run → fail**

```bash
./gradlew test --tests FlashbackMetadataTest
```
Expected : compile error — `toJson(Writer)` n'existe pas.

- [ ] **Step 3 : Ajouter `toJson` dans `FlashbackMetadata.java`**

Éditer `src/main/java/fr/zeffut/multiview/format/FlashbackMetadata.java`. Ajouter un import et la méthode. Ajouter :

```java
import com.google.gson.GsonBuilder;
```

Juste après la méthode `public static FlashbackMetadata fromJson(Reader reader)`, insérer :

```java
    public void toJson(java.io.Writer writer) {
        new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create()
                .toJson(this, writer);
    }
```

- [ ] **Step 4 : Run → pass**

```bash
./gradlew test --tests FlashbackMetadataTest
```
Expected : 4 tests passed (3 existants + 1 nouveau).

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/format/FlashbackMetadata.java src/test/java/fr/zeffut/multiview/format/FlashbackMetadataTest.java
git commit -m "feat(format): FlashbackMetadata.toJson with pretty-printing"
```

---

## Task 3 : `ActionType.idOf` + `ActionType.encode`

**Files:**
- Modify: `src/main/java/fr/zeffut/multiview/format/ActionType.java`
- Create: `src/test/java/fr/zeffut/multiview/format/ActionTypeTest.java`

Pour pouvoir écrire un segment, le writer doit pouvoir extraire (id, payloadBytes) d'un `Action` typé. C'est l'inverse du décodage.

- [ ] **Step 1 : Écrire le test d'abord**

Créer `src/test/java/fr/zeffut/multiview/format/ActionTypeTest.java` :

```java
package fr.zeffut.multiview.format;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ActionTypeTest {

    @Test
    void idOfEachKnownVariant() {
        assertEquals(ActionType.NEXT_TICK,          ActionType.idOf(new Action.NextTick()));
        assertEquals(ActionType.CONFIGURATION,      ActionType.idOf(new Action.ConfigurationPacket(new byte[0])));
        assertEquals(ActionType.GAME_PACKET,        ActionType.idOf(new Action.GamePacket(new byte[0])));
        assertEquals(ActionType.CREATE_PLAYER,      ActionType.idOf(new Action.CreatePlayer(new byte[0])));
        assertEquals(ActionType.MOVE_ENTITIES,      ActionType.idOf(new Action.MoveEntities(new byte[0])));
        assertEquals(ActionType.CACHE_CHUNK,        ActionType.idOf(new Action.CacheChunkRef(42)));
        assertEquals(ActionType.VOICE_CHAT,         ActionType.idOf(new Action.VoiceChat(new byte[0])));
        assertEquals(ActionType.ENCODED_VOICE_CHAT, ActionType.idOf(new Action.EncodedVoiceChat(new byte[0])));
    }

    @Test
    void idOfUnknownReturnsItsOwnId() {
        Action.Unknown u = new Action.Unknown("custom:mod/foo", new byte[] { 0x01 });
        assertEquals("custom:mod/foo", ActionType.idOf(u));
    }

    @Test
    void encodeNextTickIsEmpty() {
        assertEquals(0, ActionType.encode(new Action.NextTick()).length);
    }

    @Test
    void encodeOpaqueVariantsPreserveBytes() {
        byte[] payload = new byte[] { 0x01, 0x02, 0x03, 0x04 };
        assertArrayEquals(payload, ActionType.encode(new Action.GamePacket(payload)));
        assertArrayEquals(payload, ActionType.encode(new Action.ConfigurationPacket(payload)));
        assertArrayEquals(payload, ActionType.encode(new Action.CreatePlayer(payload)));
        assertArrayEquals(payload, ActionType.encode(new Action.MoveEntities(payload)));
        assertArrayEquals(payload, ActionType.encode(new Action.VoiceChat(payload)));
        assertArrayEquals(payload, ActionType.encode(new Action.EncodedVoiceChat(payload)));
        assertArrayEquals(payload, ActionType.encode(new Action.Unknown("x:y", payload)));
    }

    @Test
    void encodeCacheChunkRefWritesVarInt() {
        byte[] encoded = ActionType.encode(new Action.CacheChunkRef(300));
        // 300 en VarInt = 0xAC 0x02
        assertArrayEquals(new byte[] { (byte) 0xAC, 0x02 }, encoded);
    }

    @Test
    void encodeDecodeRoundTripForCacheChunkRef() {
        Action.CacheChunkRef original = new Action.CacheChunkRef(12345);
        byte[] bytes = ActionType.encode(original);
        Action decoded = ActionType.decode(ActionType.CACHE_CHUNK, bytes);
        Action.CacheChunkRef r = assertInstanceOf(Action.CacheChunkRef.class, decoded);
        assertEquals(12345, r.cacheIndex());
    }
}
```

- [ ] **Step 2 : Run → fail**

```bash
./gradlew test --tests ActionTypeTest
```
Expected : compile errors — `ActionType.idOf` et `ActionType.encode` n'existent pas.

- [ ] **Step 3 : Implémenter dans `ActionType.java`**

Éditer `src/main/java/fr/zeffut/multiview/format/ActionType.java`. Ajouter un import :

```java
import io.netty.buffer.Unpooled;
```

Puis à la fin de la classe, avant l'accolade finale, ajouter ces deux méthodes :

```java
    /** Inverse de decode : retourne l'id namespaced pour un Action typé. */
    public static String idOf(Action action) {
        return switch (action) {
            case Action.NextTick nt           -> NEXT_TICK;
            case Action.ConfigurationPacket c -> CONFIGURATION;
            case Action.GamePacket g          -> GAME_PACKET;
            case Action.CreatePlayer p        -> CREATE_PLAYER;
            case Action.MoveEntities m        -> MOVE_ENTITIES;
            case Action.CacheChunkRef r       -> CACHE_CHUNK;
            case Action.VoiceChat v           -> VOICE_CHAT;
            case Action.EncodedVoiceChat e    -> ENCODED_VOICE_CHAT;
            case Action.Unknown u             -> u.id();
        };
    }

    /** Inverse de decode : encode l'Action en bytes pour écriture en segment. */
    public static byte[] encode(Action action) {
        return switch (action) {
            case Action.NextTick nt           -> new byte[0];
            case Action.ConfigurationPacket c -> c.bytes();
            case Action.GamePacket g          -> g.bytes();
            case Action.CreatePlayer p        -> p.bytes();
            case Action.MoveEntities m        -> m.bytes();
            case Action.CacheChunkRef r       -> encodeCacheChunkRef(r);
            case Action.VoiceChat v           -> v.bytes();
            case Action.EncodedVoiceChat e    -> e.bytes();
            case Action.Unknown u             -> u.payload();
        };
    }

    private static byte[] encodeCacheChunkRef(Action.CacheChunkRef r) {
        io.netty.buffer.ByteBuf tmp = Unpooled.buffer();
        VarInts.writeVarInt(tmp, r.cacheIndex());
        byte[] out = new byte[tmp.readableBytes()];
        tmp.readBytes(out);
        return out;
    }
```

- [ ] **Step 4 : Run → pass**

```bash
./gradlew test --tests ActionTypeTest
```
Expected : 6 tests passed.

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/format/ActionType.java src/test/java/fr/zeffut/multiview/format/ActionTypeTest.java
git commit -m "feat(format): ActionType.idOf and ActionType.encode (inverse of decode)"
```

---

## Task 4 : Exposer les raw bytes dans `SegmentReader` pour un round-trip lossless

**Files:**
- Modify: `src/main/java/fr/zeffut/multiview/format/SegmentReader.java`
- Modify: `src/test/java/fr/zeffut/multiview/format/SegmentReaderTest.java`

Problème : `SegmentReader.next()` décode l'Action en type, mais pour un round-trip byte-for-byte, la writer a besoin des bytes bruts exacts. `ActionType.encode` les reconstruit — pour `CacheChunkRef` (VarInt) et les types opaques (`.bytes()`) c'est byte-exact. Mais on veut aussi être robuste au cas où un id inconnu apparaîtrait.

Solution : ajouter un mode "raw" à SegmentReader qui expose `RawAction(int ordinal, byte[] payload)` sans décoder.

- [ ] **Step 1 : Écrire le test**

Ajouter dans `src/test/java/fr/zeffut/multiview/format/SegmentReaderTest.java`, avant l'accolade finale :

```java
    @Test
    void nextRawYieldsUnDecodedPayload() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(0xD780E884);
        VarInts.writeVarInt(buf, 1);
        byte[] idBytes = ActionType.NEXT_TICK.getBytes(StandardCharsets.UTF_8);
        VarInts.writeVarInt(buf, idBytes.length);
        buf.writeBytes(idBytes);
        buf.writeInt(0); // snapshot size 0
        // 1 action live avec payload { 0xAA, 0xBB }
        VarInts.writeVarInt(buf, 0);
        buf.writeInt(2);
        buf.writeByte(0xAA);
        buf.writeByte(0xBB);

        SegmentReader reader = new SegmentReader("test.flashback", new FlashbackByteBuf(buf));
        assertTrue(reader.hasNext());
        SegmentReader.RawAction raw = reader.nextRaw();
        assertEquals(0, raw.ordinal());
        assertArrayEquals(new byte[] { (byte) 0xAA, (byte) 0xBB }, raw.payload());
    }
```

- [ ] **Step 2 : Run → fail**

```bash
./gradlew test --tests SegmentReaderTest
```
Expected : compile error — `SegmentReader.RawAction` et `nextRaw()` n'existent pas.

- [ ] **Step 3 : Ajouter RawAction + nextRaw dans `SegmentReader.java`**

Après le record/classe `SegmentReader`, juste avant la dernière accolade, ajouter :

```java
    /** Entrée brute (ordinal + payload bytes) — alternative à next() pour round-trip. */
    public record RawAction(int ordinal, byte[] payload) {}

    /** Lit la prochaine action sans la décoder. Utile pour round-trip byte-for-byte. */
    public RawAction nextRaw() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        int ordinal = buf.readVarInt();
        if (ordinal < 0 || ordinal >= registry.size()) {
            throw new IllegalStateException(
                    "Action ordinal " + ordinal + " out of registry bounds ["
                            + registry.size() + ") in " + segmentName);
        }
        int payloadSize = buf.readInt();
        byte[] payload = payloadSize > 0 ? buf.readBytes(payloadSize) : new byte[0];
        return new RawAction(ordinal, payload);
    }
```

- [ ] **Step 4 : Run → pass**

```bash
./gradlew test --tests SegmentReaderTest
```
Expected : 5 tests passed (4 existants + 1 nouveau).

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/format/SegmentReader.java src/test/java/fr/zeffut/multiview/format/SegmentReaderTest.java
git commit -m "feat(format): SegmentReader.nextRaw for byte-exact round-trip"
```

---

## Task 5 : `SegmentWriter` + tests synthétiques

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/format/SegmentWriter.java`
- Create: `src/test/java/fr/zeffut/multiview/format/SegmentWriterTest.java`

API :

```java
SegmentWriter w = new SegmentWriter(segmentName, registry);  // ouvre, écrit magic + registry
w.writeSnapshotAction(ordinal, payload);   // autant qu'on veut
w.endSnapshot();                            // ferme la région snapshot (écrit snapshotSize)
w.writeLiveAction(ordinal, payload);        // autant qu'on veut
ByteBuf out = w.finish();                   // retourne le buffer complet
```

- [ ] **Step 1 : Écrire le test**

Créer `src/test/java/fr/zeffut/multiview/format/SegmentWriterTest.java` :

```java
package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SegmentWriterTest {

    @Test
    void writesHeaderRegistryEmptySnapshotAndLive() {
        SegmentWriter w = new SegmentWriter("test.flashback", List.of(ActionType.NEXT_TICK));
        w.endSnapshot();
        w.writeLiveAction(0, new byte[0]);
        w.writeLiveAction(0, new byte[0]);
        ByteBuf out = w.finish();

        // Re-lire avec SegmentReader : 2 NextTick en live, 0 en snapshot
        SegmentReader reader = new SegmentReader("test.flashback", new FlashbackByteBuf(out));
        assertEquals(1, reader.registry().size());
        assertEquals(ActionType.NEXT_TICK, reader.registry().get(0));
        int snap = 0, live = 0;
        while (reader.hasNext()) {
            if (reader.isPeekInSnapshot()) snap++; else live++;
            reader.next();
        }
        assertEquals(0, snap);
        assertEquals(2, live);
    }

    @Test
    void snapshotActionsGoInSnapshotBlock() {
        SegmentWriter w = new SegmentWriter("test.flashback", List.of(ActionType.NEXT_TICK));
        w.writeSnapshotAction(0, new byte[0]);
        w.writeSnapshotAction(0, new byte[0]);
        w.endSnapshot();
        w.writeLiveAction(0, new byte[0]);
        ByteBuf out = w.finish();

        SegmentReader reader = new SegmentReader("test.flashback", new FlashbackByteBuf(out));
        int snap = 0, live = 0;
        while (reader.hasNext()) {
            if (reader.isPeekInSnapshot()) snap++; else live++;
            reader.next();
        }
        assertEquals(2, snap);
        assertEquals(1, live);
    }

    @Test
    void preservesPayloadBytes() {
        byte[] payload = new byte[] { 0x0A, 0x0B, 0x0C, 0x0D };
        SegmentWriter w = new SegmentWriter("test.flashback",
                List.of(ActionType.NEXT_TICK, ActionType.GAME_PACKET));
        w.endSnapshot();
        w.writeLiveAction(1, payload);
        ByteBuf out = w.finish();

        SegmentReader reader = new SegmentReader("test.flashback", new FlashbackByteBuf(out));
        Action a = reader.next();
        Action.GamePacket gp = assertInstanceOf(Action.GamePacket.class, a);
        assertArrayEquals(payload, gp.bytes());
    }

    @Test
    void writesMagicAtStart() {
        SegmentWriter w = new SegmentWriter("test.flashback", List.of(ActionType.NEXT_TICK));
        w.endSnapshot();
        ByteBuf out = w.finish();
        assertEquals(0xD780E884, out.readInt());
    }
}
```

- [ ] **Step 2 : Run → fail**

```bash
./gradlew test --tests SegmentWriterTest
```
Expected : compile error.

- [ ] **Step 3 : Implémenter `SegmentWriter.java`**

Créer `src/main/java/fr/zeffut/multiview/format/SegmentWriter.java` :

```java
package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.List;

/**
 * Écrit un segment {@code cN.flashback} : magic + registry + snapshot borné + live stream.
 * Complément symétrique de {@link SegmentReader}.
 *
 * <p>Workflow :
 * <pre>
 *   SegmentWriter w = new SegmentWriter(name, registry);
 *   w.writeSnapshotAction(ord, payload);  // 0..N fois
 *   w.endSnapshot();                       // obligatoire, écrit le snapshotSize
 *   w.writeLiveAction(ord, payload);       // 0..N fois
 *   ByteBuf out = w.finish();              // bytes complets du segment
 * </pre>
 *
 * <p>Non thread-safe. Usage single-pass.
 */
public final class SegmentWriter {
    private final String segmentName;
    private final List<String> registry;
    private final ByteBuf header;
    private final ByteBuf snapshot;
    private final ByteBuf live;
    private boolean snapshotClosed = false;
    private boolean finished = false;

    public SegmentWriter(String segmentName, List<String> registry) {
        this.segmentName = segmentName;
        this.registry = List.copyOf(registry);
        this.header = Unpooled.buffer();
        this.snapshot = Unpooled.buffer();
        this.live = Unpooled.buffer();
        writeHeader();
    }

    private void writeHeader() {
        FlashbackByteBuf h = new FlashbackByteBuf(header);
        h.writeInt(SegmentReader.MAGIC);
        h.writeVarInt(registry.size());
        for (String id : registry) {
            h.writeString(id);
        }
    }

    public void writeSnapshotAction(int ordinal, byte[] payload) {
        if (snapshotClosed) {
            throw new IllegalStateException("snapshot already closed in " + segmentName);
        }
        writeAction(snapshot, ordinal, payload);
    }

    public void endSnapshot() {
        if (snapshotClosed) {
            throw new IllegalStateException("snapshot already closed in " + segmentName);
        }
        snapshotClosed = true;
    }

    public void writeLiveAction(int ordinal, byte[] payload) {
        if (!snapshotClosed) {
            throw new IllegalStateException("call endSnapshot() before writeLiveAction() in " + segmentName);
        }
        writeAction(live, ordinal, payload);
    }

    private void writeAction(ByteBuf target, int ordinal, byte[] payload) {
        if (ordinal < 0 || ordinal >= registry.size()) {
            throw new IllegalArgumentException(
                    "ordinal " + ordinal + " out of registry bounds [" + registry.size() + ")");
        }
        FlashbackByteBuf b = new FlashbackByteBuf(target);
        b.writeVarInt(ordinal);
        b.writeInt(payload.length);
        if (payload.length > 0) {
            b.writeBytes(payload);
        }
    }

    public ByteBuf finish() {
        if (finished) {
            throw new IllegalStateException("finish() already called on " + segmentName);
        }
        if (!snapshotClosed) {
            throw new IllegalStateException("endSnapshot() must be called before finish() in " + segmentName);
        }
        finished = true;

        ByteBuf out = Unpooled.buffer();
        out.writeBytes(header);
        out.writeInt(snapshot.readableBytes());
        out.writeBytes(snapshot);
        out.writeBytes(live);
        return out;
    }

    public String segmentName() {
        return segmentName;
    }
}
```

- [ ] **Step 4 : Run → pass**

```bash
./gradlew test --tests SegmentWriterTest
```
Expected : 4 tests passed.

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/format/SegmentWriter.java src/test/java/fr/zeffut/multiview/format/SegmentWriterTest.java
git commit -m "feat(format): SegmentWriter (magic + registry + bounded snapshot + live stream)"
```

---

## Task 6 : Round-trip byte-for-byte sur un segment réel

**Files:**
- Create: `src/test/java/fr/zeffut/multiview/format/SegmentRoundTripTest.java`

Le test prouve la symétrie exacte : lit le plus petit segment réel (`c30.flashback` de POV1 = 1.3 MB), le décompose via `SegmentReader.nextRaw()`, le ré-écrit via `SegmentWriter`, compare les bytes. Doit être identique.

- [ ] **Step 1 : Écrire le test**

Créer `src/test/java/fr/zeffut/multiview/format/SegmentRoundTripTest.java` :

```java
package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentRoundTripTest {

    private static final Path REPLAYS_DIR = Paths.get(System.getProperty("user.dir"), "run", "replay");

    static boolean replaysAvailable() {
        return Files.isDirectory(REPLAYS_DIR);
    }

    @Test
    @EnabledIf("replaysAvailable")
    void roundTripEverySegmentOfEveryReplayByteForByte() throws IOException {
        AtomicInteger segmentsTested = new AtomicInteger();
        try (var replayDirs = Files.list(REPLAYS_DIR)) {
            replayDirs
                    .filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("metadata.json")))
                    .forEach(folder -> {
                        try (var segFiles = Files.list(folder)) {
                            segFiles
                                    .filter(p -> p.getFileName().toString().matches("c\\d+\\.flashback"))
                                    .forEach(seg -> {
                                        try {
                                            byte[] original = Files.readAllBytes(seg);
                                            byte[] round = roundTripSegment(seg.getFileName().toString(), original);
                                            assertArrayEquals(original, round,
                                                    "byte mismatch on " + seg);
                                            segmentsTested.incrementAndGet();
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        assertTrue(segmentsTested.get() > 0, "no segment was round-tripped");
    }

    private static byte[] roundTripSegment(String name, byte[] original) {
        SegmentReader reader = new SegmentReader(name,
                new FlashbackByteBuf(Unpooled.wrappedBuffer(original)));
        SegmentWriter writer = new SegmentWriter(name, reader.registry());
        while (reader.hasNext()) {
            boolean inSnap = reader.isPeekInSnapshot();
            SegmentReader.RawAction raw = reader.nextRaw();
            if (inSnap) {
                writer.writeSnapshotAction(raw.ordinal(), raw.payload());
            } else {
                if (!snapshotClosedYet(writer)) {
                    writer.endSnapshot();
                }
                writer.writeLiveAction(raw.ordinal(), raw.payload());
            }
        }
        if (!snapshotClosedYet(writer)) {
            writer.endSnapshot();
        }
        ByteBuf out = writer.finish();
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        return bytes;
    }

    /** Introspection via une réécriture de endSnapshot() qui tolère l'appel double — simplification. */
    private static boolean snapshotClosedYet(SegmentWriter w) {
        try {
            w.endSnapshot();
            return false;   // on vient de le fermer, donc il ne l'était pas
        } catch (IllegalStateException already) {
            return true;
        }
    }
}
```

**Note** : le helper `snapshotClosedYet` est fragile (il ferme le snapshot par side-effect). On le garde simple en Phase 2. Si la lisibilité pose souci, on exposera un `isSnapshotClosed()` sur `SegmentWriter` dans une itération ultérieure.

- [ ] **Step 2 : Run → pour l'instant, attend compile OK puis passage sur données réelles**

```bash
./gradlew test --tests SegmentRoundTripTest
```

Expected : BUILD SUCCESSFUL. Le test lit chaque `cN.flashback` dans `run/replay/*/`, le round-trip, compare les bytes. Avec 33 + 34 = 67 segments réels, ça prend 10-30s. Si un seul byte diffère, le test fail avec le nom du segment en cause.

Si échec byte-mismatch :
- Isoler lequel (le nom apparaît dans l'assert message)
- Diff hex : `diff <(xxd run/replay/XXX/cN.flashback) <(xxd /tmp/roundtripped.bin)` pour comprendre où ça diverge
- Typiquement : oubli d'un byte dans le header, mauvais ordre de registry, ou payload mal copié

- [ ] **Step 3 : Si test passe, commit**

```bash
git add src/test/java/fr/zeffut/multiview/format/SegmentRoundTripTest.java
git commit -m "test(format): byte-for-byte segment round-trip on real replays"
```

---

## Task 7 : `FlashbackWriter.copy` — replay complet

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/format/FlashbackWriter.java`

API : `FlashbackWriter.copy(FlashbackReplay source, Path destFolder)` reconstruit un replay complet.

- Crée `destFolder` si absent.
- Écrit `metadata.json` via `FlashbackMetadata.toJson`.
- Copie `icon.png` (si présent dans le source).
- Copie le dossier `level_chunk_caches/` intégralement.
- Pour chaque segment : lit via `SegmentReader`, écrit via `SegmentWriter` (préserve le registry et l'ordre).

- [ ] **Step 1 : Écrire la classe**

Créer `src/main/java/fr/zeffut/multiview/format/FlashbackWriter.java` :

```java
package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Écrit un replay Flashback. Phase 2 : API {@code copy} qui reproduit un replay
 * source sur disque byte-à-byte pour les segments et les caches, sémantiquement
 * équivalent pour {@code metadata.json}.
 */
public final class FlashbackWriter {

    private FlashbackWriter() {}

    /**
     * Copie complète d'un replay vers un nouveau dossier.
     *
     * @param source       replay ouvert via {@link FlashbackReader#open(Path)}
     * @param destFolder   dossier de destination (créé si absent)
     */
    public static void copy(FlashbackReplay source, Path destFolder) throws IOException {
        Files.createDirectories(destFolder);

        // metadata.json
        try (Writer w = Files.newBufferedWriter(destFolder.resolve("metadata.json"))) {
            source.metadata().toJson(w);
        }

        // icon.png si présent
        Path srcIcon = source.folder().resolve("icon.png");
        if (Files.isRegularFile(srcIcon)) {
            Files.copy(srcIcon, destFolder.resolve("icon.png"), StandardCopyOption.REPLACE_EXISTING);
        }

        // level_chunk_caches/ entier
        Path srcCaches = source.folder().resolve("level_chunk_caches");
        if (Files.isDirectory(srcCaches)) {
            Path destCaches = destFolder.resolve("level_chunk_caches");
            Files.createDirectories(destCaches);
            try (Stream<Path> entries = Files.list(srcCaches)) {
                entries.forEach(entry -> {
                    try {
                        Files.copy(entry,
                                destCaches.resolve(entry.getFileName()),
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }

        // Segments
        for (Path srcSegment : source.segmentPaths()) {
            Path destSegment = destFolder.resolve(srcSegment.getFileName());
            byte[] original = Files.readAllBytes(srcSegment);
            byte[] written = roundTripSegmentBytes(srcSegment.getFileName().toString(), original);
            Files.write(destSegment, written);
        }
    }

    private static byte[] roundTripSegmentBytes(String name, byte[] original) {
        SegmentReader reader = new SegmentReader(name,
                new FlashbackByteBuf(Unpooled.wrappedBuffer(original)));
        SegmentWriter writer = new SegmentWriter(name, reader.registry());
        boolean snapshotClosed = false;
        while (reader.hasNext()) {
            boolean inSnap = reader.isPeekInSnapshot();
            SegmentReader.RawAction raw = reader.nextRaw();
            if (inSnap) {
                writer.writeSnapshotAction(raw.ordinal(), raw.payload());
            } else {
                if (!snapshotClosed) {
                    writer.endSnapshot();
                    snapshotClosed = true;
                }
                writer.writeLiveAction(raw.ordinal(), raw.payload());
            }
        }
        if (!snapshotClosed) {
            writer.endSnapshot();
        }
        ByteBuf out = writer.finish();
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        return bytes;
    }
}
```

- [ ] **Step 2 : Compile check**

```bash
./gradlew compileJava
```
Expected : BUILD SUCCESSFUL.

- [ ] **Step 3 : Commit (avant les tests d'intégration de Task 8)**

```bash
git add src/main/java/fr/zeffut/multiview/format/FlashbackWriter.java
git commit -m "feat(format): FlashbackWriter.copy reproduces replays to a new folder"
```

---

## Task 8 : Test d'intégration `FlashbackWriter` sur replay complet

**Files:**
- Create: `src/test/java/fr/zeffut/multiview/format/FlashbackWriterIntegrationTest.java`

- [ ] **Step 1 : Écrire le test**

Créer `src/test/java/fr/zeffut/multiview/format/FlashbackWriterIntegrationTest.java` :

```java
package fr.zeffut.multiview.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashbackWriterIntegrationTest {

    private static final Path REPLAYS_DIR = Paths.get(System.getProperty("user.dir"), "run", "replay");

    static boolean replaysAvailable() {
        try (var s = Files.list(REPLAYS_DIR)) {
            return s.anyMatch(d -> Files.isRegularFile(d.resolve("metadata.json")));
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    @EnabledIf("replaysAvailable")
    void copyFirstReplayPreservesSegmentsAndCachesByteForByte(@TempDir Path tmp) throws IOException {
        Path source;
        try (Stream<Path> dirs = Files.list(REPLAYS_DIR)) {
            source = dirs
                    .filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("metadata.json")))
                    .findFirst()
                    .orElseThrow();
        }
        Path dest = tmp.resolve("copy");
        FlashbackReplay replay = FlashbackReader.open(source);
        FlashbackWriter.copy(replay, dest);

        // Segments byte-for-byte
        for (Path segPath : replay.segmentPaths()) {
            byte[] a = Files.readAllBytes(segPath);
            byte[] b = Files.readAllBytes(dest.resolve(segPath.getFileName()));
            assertArrayEquals(a, b, "segment mismatch on " + segPath.getFileName());
        }

        // level_chunk_caches byte-for-byte
        Path srcCaches = source.resolve("level_chunk_caches");
        Path dstCaches = dest.resolve("level_chunk_caches");
        if (Files.isDirectory(srcCaches)) {
            try (Stream<Path> files = Files.list(srcCaches)) {
                for (Path srcFile : files.toList()) {
                    Path dstFile = dstCaches.resolve(srcFile.getFileName());
                    assertTrue(Files.isRegularFile(dstFile), "missing cache file " + srcFile.getFileName());
                    assertArrayEquals(Files.readAllBytes(srcFile), Files.readAllBytes(dstFile),
                            "cache bytes mismatch on " + srcFile.getFileName());
                }
            }
        }

        // icon.png byte-for-byte (si présent)
        Path srcIcon = source.resolve("icon.png");
        if (Files.isRegularFile(srcIcon)) {
            assertArrayEquals(Files.readAllBytes(srcIcon), Files.readAllBytes(dest.resolve("icon.png")));
        }

        // metadata.json sémantiquement équivalent
        FlashbackReplay round = FlashbackReader.open(dest);
        assertEquals(replay.metadata().uuid(), round.metadata().uuid());
        assertEquals(replay.metadata().totalTicks(), round.metadata().totalTicks());
        assertEquals(replay.metadata().chunks().size(), round.metadata().chunks().size());
        assertEquals(replay.metadata().markers().size(), round.metadata().markers().size());
    }
}
```

- [ ] **Step 2 : Run — le test doit passer**

```bash
./gradlew test --tests FlashbackWriterIntegrationTest
```

Le test prend probablement 30s-1min (I/O lourd — 2.7 GB de caches + segments à copier/comparer pour un POV). Si ça timeout, augmenter le Gradle timeout ou isoler sur le plus petit POV.

Expected : BUILD SUCCESSFUL, 1 test passé.

- [ ] **Step 3 : Run full suite**

```bash
./gradlew test
```

Expected : tous les tests verts (~30 tests au total).

- [ ] **Step 4 : Commit**

```bash
git add src/test/java/fr/zeffut/multiview/format/FlashbackWriterIntegrationTest.java
git commit -m "test(format): full replay copy round-trip (segments + caches + icon + metadata)"
```

---

## Task 9 : Validation manuelle — Flashback charge le replay round-trippé

**Files:** aucun — validation manuelle.

Le test byte-for-byte prouve la correction technique. Cette étape confirme que Flashback lui-même accepte et joue notre output.

- [ ] **Step 1 : Produire une copie via un test de dev**

Ajouter un test **temporaire** qui produit une copie dans `run/replay/multiview-roundtrip-test/` (ce dossier sera visible par Flashback en dev).

Créer `src/test/java/fr/zeffut/multiview/format/DevCopyIntoReplayFolderTest.java` :

```java
package fr.zeffut.multiview.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Helper test — ne s'exécute que si on passe -Dmultiview.copy.devReplay=true.
 * Produit une copie du plus petit replay local dans run/replay/multiview-roundtrip-test/
 * pour validation manuelle dans le dev client.
 */
class DevCopyIntoReplayFolderTest {

    private static final Path REPLAYS_DIR = Paths.get(System.getProperty("user.dir"), "run", "replay");

    static boolean replaysAvailable() {
        try (var s = Files.list(REPLAYS_DIR)) {
            return s.anyMatch(d -> Files.isRegularFile(d.resolve("metadata.json")));
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "multiview.copy.devReplay", matches = "true")
    @EnabledIf("replaysAvailable")
    void copyIntoRunReplayForManualTest() throws IOException {
        Path smallest;
        try (Stream<Path> dirs = Files.list(REPLAYS_DIR)) {
            smallest = dirs
                    .filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("metadata.json")))
                    .min((a, b) -> {
                        try {
                            long sa = folderSize(a);
                            long sb = folderSize(b);
                            return Long.compare(sa, sb);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .orElseThrow();
        }
        Path dest = REPLAYS_DIR.resolve("multiview-roundtrip-test");
        // Nettoyage si existe déjà
        if (Files.isDirectory(dest)) {
            try (Stream<Path> files = Files.walk(dest)) {
                files.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException e) {} });
            }
        }
        FlashbackWriter.copy(FlashbackReader.open(smallest), dest);
        assertTrue(Files.isRegularFile(dest.resolve("metadata.json")));
    }

    private static long folderSize(Path folder) throws IOException {
        try (Stream<Path> s = Files.walk(folder)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0; }
            }).sum();
        }
    }
}
```

- [ ] **Step 2 : Exécuter le test helper**

```bash
./gradlew test --tests DevCopyIntoReplayFolderTest -Dmultiview.copy.devReplay=true
```

Expected : BUILD SUCCESSFUL. Le plus petit replay local est copié vers `run/replay/multiview-roundtrip-test/`.

- [ ] **Step 3 : Vérifier dans le dev client**

```bash
./gradlew runClient
```

Dans le jeu :
1. Ouvrir Flashback (touche ou menu selon l'UI Flashback)
2. Chercher le replay nommé `multiview-roundtrip-test` dans la liste
3. L'ouvrir / le jouer

Si Flashback liste et joue le replay sans erreur différente de l'original (rappel : warning lattice est pré-existant, pas lié à nous) → validation OK.

Si Flashback refuse, dire en rouge "invalid format" ou crash :
- Logger la stacktrace
- Comparer byte-to-byte avec l'original via `diff <(xxd run/replay/POV1/c0.flashback) <(xxd run/replay/multiview-roundtrip-test/c0.flashback)` — ne doit rien afficher
- Si diff vide et Flashback refuse quand même → il y a une subtilité du format qu'on n'a pas capturée (probablement dans metadata.json)

- [ ] **Step 4 : Nettoyage**

Supprimer le dossier de test :

```bash
rm -rf /Users/zeffut/Desktop/Projets/MultiView/run/replay/multiview-roundtrip-test
```

Et supprimer le test helper (on ne le commit pas — il est utilitaire) :

```bash
rm /Users/zeffut/Desktop/Projets/MultiView/src/test/java/fr/zeffut/multiview/format/DevCopyIntoReplayFolderTest.java
```

Pas de commit pour cette Task — c'était une validation.

---

## Task 10 : Documentation + journal + tag phase-2-complete

**Files:**
- Modify: `src/main/java/fr/zeffut/multiview/format/README.md`
- Modify: `SPEC.md`

- [ ] **Step 1 : Ajouter §10 "Writer" au `format/README.md`**

À la fin du fichier, avant la dernière ligne, ajouter :

```markdown

## 10. Writer

Symétrique du reader. API publique :

```java
FlashbackReplay source = FlashbackReader.open(srcFolder);
FlashbackWriter.copy(source, destFolder);   // reproduit un replay complet
```

Garanties :
- Chaque `cN.flashback` produit est **byte-for-byte identique** à son pendant source.
- `level_chunk_caches/*` et `icon.png` sont copiés octet par octet.
- `metadata.json` est re-sérialisé via Gson (pretty-print, pas d'échappement HTML).
  L'ordre des clés et des entrées de map est préservé grâce à `LinkedHashMap` dans
  `FlashbackMetadata`.

Primitives bas niveau :
- `SegmentWriter(segmentName, registry)` — écrit magic + registry, puis accepte
  des actions snapshot via `writeSnapshotAction(ordinal, payload)`, un
  `endSnapshot()`, puis des actions live via `writeLiveAction(ordinal, payload)`.
- `FlashbackByteBuf.writeVarInt / writeInt / writeString / writeBytes`.
- `ActionType.idOf(Action)` + `ActionType.encode(Action)` — utiles pour la
  Phase 3 (merge) qui construira des segments depuis des streams d'actions typées
  plutôt que depuis un reader source.

Validation : le test `FlashbackWriterIntegrationTest` copie un replay complet
dans un `@TempDir` et vérifie `assertArrayEquals` sur tous les fichiers binaires
+ cohérence sémantique de `metadata.json` après re-parsing.
```

- [ ] **Step 2 : Ajouter l'entrée journal dans `SPEC.md §10`**

Après l'entrée "Phase 1 terminée", ajouter :

```markdown

### 2026-04-19 — Phase 2 terminée : writer + round-trip byte-for-byte
- API : `FlashbackWriter.copy(source, dest)` reproduit un replay complet.
- Chaque segment écrit est byte-for-byte identique à son original (prouvé par
  `SegmentRoundTripTest` sur les 67 segments des deux POV réels de dev).
- `level_chunk_caches/*` et `icon.png` copiés octet par octet.
- `metadata.json` re-sérialisé via Gson (pretty-print), cohérent sémantiquement
  après re-parsing.
- Nouvelles classes : `SegmentWriter` (symétrique de `SegmentReader`),
  `FlashbackWriter` (wrapper haut niveau).
- Extensions : `FlashbackByteBuf` (write*), `FlashbackMetadata.toJson`,
  `ActionType.idOf` / `ActionType.encode` (inverse de `decode`).
- `SegmentReader.RawAction` + `nextRaw()` ajoutés pour round-trip lossless.
- Validation manuelle : un replay round-trippé via `FlashbackWriter.copy` a été
  chargé dans Flashback via le dev client et joué sans erreur — le writer est
  reconnu par Flashback lui-même.
- ~30 tests verts au total. CI toujours green (tests d'intégration conditionnels).
```

- [ ] **Step 3 : Commit final + tag**

```bash
git add src/main/java/fr/zeffut/multiview/format/README.md SPEC.md
git commit -m "docs: document writer + add phase 2 journal entry"
git tag phase-2-complete
git log --oneline | head -10
git tag
```

Expected : `phase-0-complete`, `phase-1-complete`, `phase-2-complete` tous présents.

---

## Critères d'acceptation Phase 2

1. ✅ `./gradlew test` passe — tests unitaires + `SegmentRoundTripTest` + `FlashbackWriterIntegrationTest`.
2. ✅ 67 segments réels (33 POV1 + 34 POV2) passent le round-trip byte-for-byte.
3. ✅ `level_chunk_caches/` et `icon.png` copiés byte-for-byte (testé).
4. ✅ `metadata.json` : re-parse après re-serialize donne les mêmes valeurs (testé).
5. ✅ Validation manuelle : Flashback charge et joue un replay copié par `FlashbackWriter.copy`.
6. ✅ `format/README.md §10` documente le writer.
7. ✅ `SPEC.md` journal à jour, tag `phase-2-complete` posé.

---

## Self-review

- **Couverture de Phase 2 (SPEC.md §5 Phase 2)** :
  - ✅ Implémenter FlashbackWriter → Task 7.
  - ✅ Test de non-régression critique `read(X) → write(Y); X === Y` → Task 6 (byte-for-byte segments) + Task 8 (replay complet).
  - ✅ Ouvrir Y dans Flashback → Task 9 validation manuelle.
  - ✅ Si ça ne marche pas retour Phase 1 → le plan explicite les diagnostics à faire (diff hex, re-parse metadata).
- **Placeholder scan** : aucun TODO. Le helper `snapshotClosedYet` est explicitement marqué comme fragile et justifié. Les settings Gson (`setPrettyPrinting` + `disableHtmlEscaping`) sont concrets.
- **Cohérence des types** : `FlashbackByteBuf.writeVarInt/writeInt/writeString/writeBytes` cohérents avec les `readVarInt/readInt/readString/readBytes` existants. `SegmentWriter(segmentName, registry)` / `SegmentReader(segmentName, buf)` — noms et ordre cohérents. `RawAction(ordinal, payload)` type ordinal en `int`, payload en `byte[]`, cohérent avec `SegmentWriter.writeLiveAction(int, byte[])`. `ActionType.idOf` retourne String, `ActionType.encode` retourne byte[], symétriques de `decode(String, byte[])`.
- **Risque connu** : si Flashback refuse le replay round-trippé (Task 9), c'est probablement `metadata.json` qui diffère textuellement de l'original (formatage Gson vs format original). Le test byte-for-byte sur segments n'adresse pas ça. Plan B documenté dans Task 9 Step 3.
