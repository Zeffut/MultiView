# MultiView — Phase 1 : Format Reader Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implémenter un lecteur du format `.flashback` (dossier non-zippé) qui parse `metadata.json` et décode le flux binaire des segments `cN.flashback` en un `Stream<PacketEntry>` exploité ensuite par les phases suivantes. Phase **read-only** — aucune écriture.

**Architecture:** On s'appuie sur **Arcade Replay** (`CasualChampionships/Arcade`, branche `1.21.11`, licence OSS) comme Rosetta Stone pour comprendre le binaire. On lit les classes Kotlin du module `arcade-replay/.../io/writer/flashback/*` pour comprendre le flow d'écriture et on écrit notre propre décodeur Java à partir de ces specs. Zéro ligne copiée depuis Flashback (Moulberry, licence restrictive). Les deux POV réels déjà présents dans `run/replay/` servent de validation bout-en-bout ; les tests unitaires utilisent des byte-arrays synthétiques (pas de fixtures pesantes dans git).

**Tech Stack:** Java 21, Gson (bundled in Minecraft, pas de nouvelle dep), `io.netty.buffer.ByteBuf` pour lire les segments (Netty bundled Fabric), JUnit 5, SLF4J.

---

## Références Rosetta Stone

Tous les liens pointent sur la branche `1.21.11` pour cohérence avec notre cible MC.

- `arcade-replay/src/main/kotlin/net/casual/arcade/replay/io/FlashbackIO.kt` — helpers bas niveau (VarInt, string encoding, etc.)
- `arcade-replay/src/main/kotlin/net/casual/arcade/replay/io/ReplayFormat.kt` — définitions de structure
- `arcade-replay/src/main/kotlin/net/casual/arcade/replay/io/writer/flashback/FlashbackWriter.kt` — écrivain haut niveau (orchestre chunks + metadata)
- `arcade-replay/src/main/kotlin/net/casual/arcade/replay/io/writer/flashback/FlashbackChunkedWriter.kt` — écriture d'un segment `cN.flashback`
- `arcade-replay/src/main/kotlin/net/casual/arcade/replay/io/writer/flashback/ChunkPacketIdentity.kt` — encodage des packets Minecraft chunk
- `arcade-replay/src/main/kotlin/net/casual/arcade/replay/io/writer/flashback/EntityMovement.kt` — encodage des actions de mouvement

**Règle d'or :** lire, comprendre, **réécrire en Java**. Jamais coller de Kotlin dans notre repo. La licence d'Arcade est permissive (vérifier `LICENSE` dans le repo) mais mieux vaut éviter la contamination : on écrit notre propre code à partir de la spec inférée.

**Ne PAS toucher** : le repo `Moulberry/Flashback`. Même pas pour le consulter — on a suffisamment d'infos via Arcade.

---

## File Structure

```
src/main/java/fr/zeffut/multiview/format/
├── FlashbackMetadata.java         # POJO Gson : uuid, name, version_string, total_ticks, chunks, markers
├── FlashbackReplay.java           # Représente un replay complet (metadata + accès segments)
├── FlashbackReader.java           # API publique : Path -> FlashbackReplay; FlashbackReplay -> Stream<PacketEntry>
├── SegmentReader.java             # Décode un cN.flashback en stream d'Action
├── Action.java                    # Sealed interface / hiérarchie des actions décodées
├── ActionType.java                # Registry des types d'action connus (NextTick, Packet, Marker, etc.)
├── PacketEntry.java               # (tick absolu, source segmentId, Action)
├── FlashbackByteBuf.java          # Wrapper ByteBuf avec helpers VarInt / string / NBT
├── VarInts.java                   # Read/write VarInt (encodage MC standard)
└── README.md                      # Spec complète du format reverse-engineered
```

```
src/main/java/fr/zeffut/multiview/inspect/
└── InspectCommand.java            # Commande Fabric /mv inspect <replayName>
```

```
src/test/java/fr/zeffut/multiview/format/
├── VarIntsTest.java
├── FlashbackByteBufTest.java
├── FlashbackMetadataTest.java
├── SegmentReaderTest.java
└── FlashbackReaderIntegrationTest.java   # gated: ne tourne que si run/replay/ contient un POV
```

**Responsabilités :**
- `VarInts` : deux méthodes statiques `readVarInt(ByteBuf)` / `writeVarInt(ByteBuf, int)`. Protocole MC standard (7 bits par byte, MSB = continuation).
- `FlashbackByteBuf` : wrapper read-only autour de `ByteBuf` avec `readVarInt()`, `readString()`, `readNamespacedId()`, `readBytes(len)`. Juste ce dont les segments ont besoin.
- `FlashbackMetadata` : POJO Gson. Aucune logique, pur data.
- `Action` : interface scellée. Implémentations : `NextTickAction`, `PacketAction` (contient les bytes raw du packet MC), `ChunkCacheRefAction` (pointe vers `level_chunk_caches/`), `MoveEntitiesAction`, etc. Set exact déterminé par les noms vus dans les POV (`flashback:action/simple_voice_chat_sound_optional`, etc.) + ce qu'on lit dans `FlashbackChunkedWriter.kt`.
- `ActionType` : énumération ou `Map<String, ActionCodec>`. Le codec décode les bytes en `Action`.
- `SegmentReader` : prend un `Path` vers `cN.flashback`, itère action par action avec un `Iterator<Action>` streamable.
- `FlashbackReplay` : facade. `metadata()`, `segmentPaths()`, `stream()` (concatène les segments dans l'ordre et remonte le tick absolu courant).
- `PacketEntry` : record immutable `(int tick, String segmentName, Action action)`.
- `FlashbackReader` : point d'entrée. `FlashbackReader.open(Path folder)` → `FlashbackReplay`.
- `InspectCommand` : prend un nom de replay (dossier dans `run/replay/`), affiche un résumé dans le chat + console.
- `format/README.md` : spec du format écrite au fur et à mesure, sert de doc de référence pour Phase 2 (writer) et au-delà.

---

## Validation de fin de phase

- Un replay réel (les POV `2026-02-20T23_25_15` et `Sénat_empirenapo…` déjà dans `run/replay/`) peut être ouvert **sans exception**.
- Le lecteur rapporte un nombre total de ticks **cohérent avec `metadata.json`** (somme des `duration` des segments = `total_ticks`).
- Les `markers` de `metadata.json` tombent à des ticks où on voit effectivement une transition dans le stream d'actions.
- Tous les tests JUnit passent localement et en CI.
- `format/README.md` documente **tous** les types d'action rencontrés dans les deux POV.

---

## Task 1 : Lire Arcade pour cartographier le format segment

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/format/README.md` (squelette)

- [ ] **Step 1 : Fetch les 4 fichiers Kotlin clés via `gh api`**

Pour chaque fichier, télécharger son contenu dans un fichier local temporaire **hors du repo** (dans `/tmp/arcade-ref/`) pour lecture seule. Commande :

```bash
mkdir -p /tmp/arcade-ref
for f in \
  "arcade-replay/src/main/kotlin/net/casual/arcade/replay/io/FlashbackIO.kt" \
  "arcade-replay/src/main/kotlin/net/casual/arcade/replay/io/ReplayFormat.kt" \
  "arcade-replay/src/main/kotlin/net/casual/arcade/replay/io/writer/flashback/FlashbackWriter.kt" \
  "arcade-replay/src/main/kotlin/net/casual/arcade/replay/io/writer/flashback/FlashbackChunkedWriter.kt"; do
  name=$(basename "$f")
  gh api "repos/CasualChampionships/Arcade/contents/$f?ref=1.21.11" --jq '.content' | base64 -d > "/tmp/arcade-ref/$name"
done
ls -la /tmp/arcade-ref/
```

**Ne pas commit** `/tmp/arcade-ref/` — c'est hors du repo de toute façon.

- [ ] **Step 2 : Lire les 4 fichiers, extraire la spec binaire d'un segment**

Lire en entier (`Read` outil). Identifier :

1. **Magic number** d'un segment : les 4 premiers bytes observés `d7 80 e8 84` dans nos POV — est-ce un header fixe ? Si oui, documenter sa valeur.
2. **Structure d'une action** : taille (VarInt len), type (string namespaced `flashback:action/*` OU index VarInt ?), payload.
3. **Liste exhaustive des action types** écrits par Arcade. Chaque type avec sa structure de payload.
4. **Marqueurs tick** : comment Arcade distingue deux actions qui arrivent au même tick vs des ticks consécutifs ? (probable action spéciale `NextTick` ou champ `tickDelta`)
5. **Relation au `level_chunk_caches/`** : est-ce que les packets de chunk MC sont stockés dans les segments ou référencés dans le cache ?

- [ ] **Step 3 : Écrire le squelette de `format/README.md`**

Créer `src/main/java/fr/zeffut/multiview/format/README.md` :

```markdown
# Format `.flashback` — spec reverse-engineered

Version Flashback cible : **0.39.4** (MC 1.21.11).
Source de vérité pour le reverse : **CasualChampionships/Arcade** branche `1.21.11`, module `arcade-replay`.
Aucune ligne de code Moulberry/Flashback n'a été consultée ou copiée.

---

## 1. Layout disque

Un replay Flashback est un **dossier** (avant zip final). Structure :

```
<replay-name>/
├── metadata.json            # Voir §2
├── icon.png                 # Miniature cosmétique
├── level_chunk_caches/
│   └── 0                    # Cache binaire des chunks MC (voir §5)
├── c0.flashback             # Segment temporel 0 (voir §3)
├── c1.flashback
├── ...
└── cN.flashback             # Dernier segment
```

Le fichier `<replay-name>.flashback` (si présent) est un ZIP de ce dossier.

## 2. `metadata.json`

Schéma (observé sur MC 1.21.11, Flashback 0.39.4) :

| Champ | Type | Description |
|---|---|---|
| `uuid` | string | UUID v4 unique de l'enregistrement |
| `name` | string | Nom affiché dans l'UI Flashback |
| `version_string` | string | Version MC (ex: `1.21.11`) |
| `world_name` | string | Nom du monde/serveur |
| `data_version` | int | MC data version (ex: 4671) |
| `protocol_version` | int | Protocole MC (ex: 774) |
| `bobby_world_name` | string | Adresse serveur pour mod Bobby |
| `total_ticks` | int | Somme des `chunks[i].duration` |
| `markers` | map<tickString, {colour:int, description:string}> | Marqueurs timeline |
| `customNamespacesForRegistries` | object | Registres custom (mods) — vide en vanilla |
| `chunks` | map<filename, {duration:int, forcePlaySnapshot:bool}> | Un chunk = un segment temporel (typiquement 6000 ticks = 5 min) |

## 3. Segment `cN.flashback` (binaire)

**À documenter en Task 1–Task 7.** Brique de base : stream d'actions préfixées par longueur.

## 4. Types d'action

**À documenter en Task 6.**

## 5. `level_chunk_caches/`

**À documenter en Task 10 si pertinent pour la lecture.**
```

- [ ] **Step 4 : Commit**

```bash
cd /Users/zeffut/Desktop/Projets/MultiView
git add src/main/java/fr/zeffut/multiview/format/README.md
git commit -m "docs(format): initial flashback format spec skeleton"
```

- [ ] **Step 5 : Reporter les findings structurels dans le report du subagent**

Inclure dans le report DONE : liste exhaustive des action types écrits par Arcade (noms + structure payload), comment le tick avance entre deux actions, et un pseudo-code 5-10 lignes du décodeur segment à implémenter en Tasks 5-7.

---

## Task 2 : `VarInts` helper + tests

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/format/VarInts.java`
- Test: `src/test/java/fr/zeffut/multiview/format/VarIntsTest.java`

- [ ] **Step 1 : Écrire le test d'abord (TDD)**

`src/test/java/fr/zeffut/multiview/format/VarIntsTest.java` :

```java
package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VarIntsTest {

    @Test
    void readZero() {
        ByteBuf buf = Unpooled.wrappedBuffer(new byte[] { 0x00 });
        assertEquals(0, VarInts.readVarInt(buf));
    }

    @Test
    void readOneByteValues() {
        // VarInt 1 → 0x01
        assertEquals(1, VarInts.readVarInt(Unpooled.wrappedBuffer(new byte[] { 0x01 })));
        // VarInt 127 → 0x7F (dernier représentable sur 1 byte)
        assertEquals(127, VarInts.readVarInt(Unpooled.wrappedBuffer(new byte[] { 0x7F })));
    }

    @Test
    void readMultiByteValues() {
        // VarInt 128 → 0x80 0x01
        assertEquals(128, VarInts.readVarInt(Unpooled.wrappedBuffer(new byte[] { (byte) 0x80, 0x01 })));
        // VarInt 300 → 0xAC 0x02
        assertEquals(300, VarInts.readVarInt(Unpooled.wrappedBuffer(new byte[] { (byte) 0xAC, 0x02 })));
        // VarInt 2097151 → 0xFF 0xFF 0x7F
        assertEquals(2097151, VarInts.readVarInt(Unpooled.wrappedBuffer(new byte[] { (byte) 0xFF, (byte) 0xFF, 0x7F })));
    }

    @Test
    void readMaxPositive() {
        // Integer.MAX_VALUE = 2147483647 → 0xFF 0xFF 0xFF 0xFF 0x07
        assertEquals(Integer.MAX_VALUE, VarInts.readVarInt(
                Unpooled.wrappedBuffer(new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07 })));
    }

    @Test
    void readNegativeOne() {
        // -1 en VarInt 32-bit → 0xFF 0xFF 0xFF 0xFF 0x0F
        assertEquals(-1, VarInts.readVarInt(
                Unpooled.wrappedBuffer(new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F })));
    }

    @Test
    void rejectsOverflow() {
        // 6 bytes avec MSB positionné → VarInt invalide (>5 bytes)
        ByteBuf overflow = Unpooled.wrappedBuffer(new byte[] {
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x7F });
        assertThrows(IllegalArgumentException.class, () -> VarInts.readVarInt(overflow));
    }

    @Test
    void roundTrip() {
        int[] values = { 0, 1, 127, 128, 255, 256, 2097151, 2097152, Integer.MAX_VALUE, -1 };
        for (int v : values) {
            ByteBuf buf = Unpooled.buffer();
            VarInts.writeVarInt(buf, v);
            assertEquals(v, VarInts.readVarInt(buf), "roundtrip failed for " + v);
        }
    }
}
```

- [ ] **Step 2 : Run test — doit échouer (classe n'existe pas)**

```bash
cd /Users/zeffut/Desktop/Projets/MultiView
./gradlew test --tests VarIntsTest
```
Expected : compile error `cannot find symbol: class VarInts`.

- [ ] **Step 3 : Implémenter `VarInts.java`**

`src/main/java/fr/zeffut/multiview/format/VarInts.java` :

```java
package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;

public final class VarInts {
    private static final int MAX_VARINT_BYTES = 5;
    private static final int SEGMENT_BITS = 0x7F;
    private static final int CONTINUE_BIT = 0x80;

    private VarInts() {}

    public static int readVarInt(ByteBuf buf) {
        int value = 0;
        int position = 0;
        byte current;
        int bytesRead = 0;
        do {
            if (bytesRead >= MAX_VARINT_BYTES) {
                throw new IllegalArgumentException("VarInt too long (> " + MAX_VARINT_BYTES + " bytes)");
            }
            current = buf.readByte();
            value |= (current & SEGMENT_BITS) << position;
            position += 7;
            bytesRead++;
        } while ((current & CONTINUE_BIT) != 0);
        return value;
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~SEGMENT_BITS) != 0) {
            buf.writeByte((value & SEGMENT_BITS) | CONTINUE_BIT);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
```

- [ ] **Step 4 : Run test — doit passer**

```bash
./gradlew test --tests VarIntsTest
```
Expected : `BUILD SUCCESSFUL`, 7 tests passed.

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/format/VarInts.java src/test/java/fr/zeffut/multiview/format/VarIntsTest.java
git commit -m "feat(format): VarInt read/write helpers with roundtrip tests"
```

---

## Task 3 : `FlashbackByteBuf` wrapper + tests

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/format/FlashbackByteBuf.java`
- Test: `src/test/java/fr/zeffut/multiview/format/FlashbackByteBufTest.java`

Le wrapper masque `io.netty.buffer.ByteBuf` et expose exactement les opérations dont le reader a besoin. Implémentation minimale — pas de méthodes non utilisées.

**Note post-P1-1** : il nous faut `readInt()` (int32 big-endian) pour le `snapshotSize` et le `payloadSize` de chaque action, en plus du VarInt pour le `count` du registry et l'`actionId` des actions.

- [ ] **Step 1 : Test d'abord**

`src/test/java/fr/zeffut/multiview/format/FlashbackByteBufTest.java` :

```java
package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashbackByteBufTest {

    @Test
    void readsStringWithVarIntPrefix() {
        // "hello" (5 chars UTF-8) → VarInt(5)=0x05 + 5 bytes
        byte[] bytes = new byte[] { 0x05, 'h', 'e', 'l', 'l', 'o' };
        FlashbackByteBuf buf = new FlashbackByteBuf(Unpooled.wrappedBuffer(bytes));
        assertEquals("hello", buf.readString());
    }

    @Test
    void readsNamespacedId() {
        // "flashback:action/next_tick" (26 chars) → VarInt(26)=0x1A + 26 bytes
        String id = "flashback:action/next_tick";
        byte[] payload = id.getBytes(StandardCharsets.UTF_8);
        ByteBuf underlying = Unpooled.buffer();
        underlying.writeByte(payload.length);
        underlying.writeBytes(payload);
        FlashbackByteBuf buf = new FlashbackByteBuf(underlying);
        assertEquals(id, buf.readNamespacedId());
    }

    @Test
    void readsVarIntDelegatesToVarInts() {
        FlashbackByteBuf buf = new FlashbackByteBuf(
                Unpooled.wrappedBuffer(new byte[] { (byte) 0xAC, 0x02 }));
        assertEquals(300, buf.readVarInt());
    }

    @Test
    void readsRawBytes() {
        FlashbackByteBuf buf = new FlashbackByteBuf(
                Unpooled.wrappedBuffer(new byte[] { 0x01, 0x02, 0x03, 0x04 }));
        assertArrayEquals(new byte[] { 0x01, 0x02, 0x03 }, buf.readBytes(3));
    }

    @Test
    void isReadableReflectsUnderlying() {
        FlashbackByteBuf buf = new FlashbackByteBuf(Unpooled.wrappedBuffer(new byte[] { 0x42 }));
        assertTrue(buf.isReadable());
        buf.readBytes(1);
        assertFalse(buf.isReadable());
    }

    @Test
    void readsBigEndianInt() {
        // 0x12345678 big-endian → 12 34 56 78
        FlashbackByteBuf buf = new FlashbackByteBuf(
                Unpooled.wrappedBuffer(new byte[] { 0x12, 0x34, 0x56, 0x78 }));
        assertEquals(0x12345678, buf.readInt());
    }

    @Test
    void readsMagicNumber() {
        // Le magic Flashback 0xD780E884 lu en int32 BE
        FlashbackByteBuf buf = new FlashbackByteBuf(
                Unpooled.wrappedBuffer(new byte[] { (byte) 0xD7, (byte) 0x80, (byte) 0xE8, (byte) 0x84 }));
        assertEquals(0xD780E884, buf.readInt());
    }

    @Test
    void readerIndexReflectsPosition() {
        FlashbackByteBuf buf = new FlashbackByteBuf(
                Unpooled.wrappedBuffer(new byte[] { 0x01, 0x02, 0x03 }));
        assertEquals(0, buf.readerIndex());
        buf.readBytes(2);
        assertEquals(2, buf.readerIndex());
    }
}
```

- [ ] **Step 2 : Run → fail**

```bash
./gradlew test --tests FlashbackByteBufTest
```
Expected : compile error.

- [ ] **Step 3 : Implémenter**

`src/main/java/fr/zeffut/multiview/format/FlashbackByteBuf.java` :

```java
package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

public final class FlashbackByteBuf {
    private final ByteBuf underlying;

    public FlashbackByteBuf(ByteBuf underlying) {
        this.underlying = underlying;
    }

    public int readVarInt() {
        return VarInts.readVarInt(underlying);
    }

    /** int32 big-endian (Netty default). Utilisé pour le magic, snapshotSize et payloadSize. */
    public int readInt() {
        return underlying.readInt();
    }

    public String readString() {
        int length = readVarInt();
        byte[] bytes = new byte[length];
        underlying.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public String readNamespacedId() {
        return readString();
    }

    public byte[] readBytes(int length) {
        byte[] out = new byte[length];
        underlying.readBytes(out);
        return out;
    }

    public int readerIndex() {
        return underlying.readerIndex();
    }

    public boolean isReadable() {
        return underlying.isReadable();
    }

    public ByteBuf raw() {
        return underlying;
    }
}
```

- [ ] **Step 4 : Run → pass**

```bash
./gradlew test --tests FlashbackByteBufTest
```
Expected : 5 tests passed.

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/format/FlashbackByteBuf.java src/test/java/fr/zeffut/multiview/format/FlashbackByteBufTest.java
git commit -m "feat(format): FlashbackByteBuf wrapper for string/varint/raw reads"
```

---

## Task 4 : `FlashbackMetadata` POJO + Gson parsing + tests

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/format/FlashbackMetadata.java`
- Test: `src/test/java/fr/zeffut/multiview/format/FlashbackMetadataTest.java`

- [ ] **Step 1 : Test d'abord**

```java
package fr.zeffut.multiview.format;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FlashbackMetadataTest {

    @Test
    void parsesMinimalJson() {
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
                  "markers": {},
                  "customNamespacesForRegistries": {},
                  "chunks": {
                    "c0.flashback": { "duration": 6000, "forcePlaySnapshot": false },
                    "c1.flashback": { "duration": 52, "forcePlaySnapshot": false }
                  }
                }
                """;
        FlashbackMetadata meta = FlashbackMetadata.fromJson(new StringReader(json));
        assertEquals("2b5459d2-d7ad-43dc-ae36-e6922cf1a45f", meta.uuid());
        assertEquals("test replay", meta.name());
        assertEquals("1.21.11", meta.versionString());
        assertEquals("TestWorld", meta.worldName());
        assertEquals(4671, meta.dataVersion());
        assertEquals(774, meta.protocolVersion());
        assertEquals(6052, meta.totalTicks());
        assertEquals(2, meta.chunks().size());
        assertEquals(6000, meta.chunks().get("c0.flashback").duration());
        assertEquals(52, meta.chunks().get("c1.flashback").duration());
    }

    @Test
    void parsesMarkers() {
        String json = """
                {
                  "uuid": "u", "name": "n", "version_string": "1.21.11", "world_name": "w",
                  "data_version": 0, "protocol_version": 0, "bobby_world_name": "b",
                  "total_ticks": 100,
                  "markers": {
                    "42": { "colour": 11141290, "description": "Changed Dimension" }
                  },
                  "customNamespacesForRegistries": {},
                  "chunks": {}
                }
                """;
        FlashbackMetadata meta = FlashbackMetadata.fromJson(new StringReader(json));
        Map<Integer, FlashbackMetadata.Marker> markers = meta.markers();
        assertNotNull(markers.get(42));
        assertEquals("Changed Dimension", markers.get(42).description());
        assertEquals(11141290, markers.get(42).colour());
    }

    @Test
    void totalTicksMatchesSumOfChunkDurations() {
        String json = """
                {
                  "uuid": "u", "name": "n", "version_string": "1.21.11", "world_name": "w",
                  "data_version": 0, "protocol_version": 0, "bobby_world_name": "b",
                  "total_ticks": 6052, "markers": {}, "customNamespacesForRegistries": {},
                  "chunks": {
                    "c0.flashback": { "duration": 6000, "forcePlaySnapshot": false },
                    "c1.flashback": { "duration": 52, "forcePlaySnapshot": false }
                  }
                }
                """;
        FlashbackMetadata meta = FlashbackMetadata.fromJson(new StringReader(json));
        int sum = meta.chunks().values().stream().mapToInt(FlashbackMetadata.ChunkInfo::duration).sum();
        assertEquals(meta.totalTicks(), sum);
    }
}
```

- [ ] **Step 2 : Run → fail**

```bash
./gradlew test --tests FlashbackMetadataTest
```
Expected : compile error.

- [ ] **Step 3 : Implémenter**

`src/main/java/fr/zeffut/multiview/format/FlashbackMetadata.java` :

```java
package fr.zeffut.multiview.format;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.Reader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FlashbackMetadata {
    @SerializedName("uuid") private String uuid;
    @SerializedName("name") private String name;
    @SerializedName("version_string") private String versionString;
    @SerializedName("world_name") private String worldName;
    @SerializedName("data_version") private int dataVersion;
    @SerializedName("protocol_version") private int protocolVersion;
    @SerializedName("bobby_world_name") private String bobbyWorldName;
    @SerializedName("total_ticks") private int totalTicks;
    @SerializedName("markers") private Map<String, Marker> rawMarkers = new LinkedHashMap<>();
    @SerializedName("chunks") private Map<String, ChunkInfo> chunks = new LinkedHashMap<>();

    public static FlashbackMetadata fromJson(Reader reader) {
        return new Gson().fromJson(reader, FlashbackMetadata.class);
    }

    public String uuid() { return uuid; }
    public String name() { return name; }
    public String versionString() { return versionString; }
    public String worldName() { return worldName; }
    public int dataVersion() { return dataVersion; }
    public int protocolVersion() { return protocolVersion; }
    public String bobbyWorldName() { return bobbyWorldName; }
    public int totalTicks() { return totalTicks; }

    public Map<Integer, Marker> markers() {
        Map<Integer, Marker> out = new LinkedHashMap<>();
        for (Map.Entry<String, Marker> e : rawMarkers.entrySet()) {
            out.put(Integer.parseInt(e.getKey()), e.getValue());
        }
        return Collections.unmodifiableMap(out);
    }

    public Map<String, ChunkInfo> chunks() {
        return Collections.unmodifiableMap(chunks);
    }

    public static final class Marker {
        @SerializedName("colour") private int colour;
        @SerializedName("description") private String description;

        public int colour() { return colour; }
        public String description() { return description; }
    }

    public static final class ChunkInfo {
        @SerializedName("duration") private int duration;
        @SerializedName("forcePlaySnapshot") private boolean forcePlaySnapshot;

        public int duration() { return duration; }
        public boolean forcePlaySnapshot() { return forcePlaySnapshot; }
    }
}
```

Note : Gson est bundled avec Minecraft (`com.google.gson:gson`) et accessible via Fabric Loom en compile-time. Pas besoin de l'ajouter en `build.gradle`.

- [ ] **Step 4 : Run → pass**

```bash
./gradlew test --tests FlashbackMetadataTest
```
Expected : 3 tests passed.

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/format/FlashbackMetadata.java src/test/java/fr/zeffut/multiview/format/FlashbackMetadataTest.java
git commit -m "feat(format): parse metadata.json with Gson"
```

---

## Task 5 : `Action` hiérarchie + `ActionType` registry

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/format/Action.java`
- Create: `src/main/java/fr/zeffut/multiview/format/ActionType.java`

**YAGNI Phase 1** : on garde les payloads **opaques** (bytes bruts) pour tous les types sauf `NextTick` et `CacheChunkRef`. Le décodage fin des packets MC, du `GameProfile`, des entity movements, etc. viendra quand Phase 3 en aura besoin. Pour l'instant le reader n'a qu'à préserver l'info intégralement pour un round-trip propre.

- [ ] **Step 1 : Écrire `Action.java`**

```java
package fr.zeffut.multiview.format;

import io.netty.buffer.Unpooled;

/**
 * Union scellée des actions d'un segment .flashback. Les 8 variantes explicites
 * correspondent au catalogue identifié dans
 * {@code src/main/java/fr/zeffut/multiview/format/README.md §4}.
 * {@link Unknown} couvre les ids non répertoriés (forward-compat).
 */
public sealed interface Action permits
        Action.NextTick,
        Action.ConfigurationPacket,
        Action.GamePacket,
        Action.CreatePlayer,
        Action.MoveEntities,
        Action.CacheChunkRef,
        Action.VoiceChat,
        Action.EncodedVoiceChat,
        Action.Unknown {

    /** Avance le tick du replay de 1. Payload vide. */
    record NextTick() implements Action {}

    /** Payload = un packet MC clientbound protocole CONFIGURATION (opaque). */
    record ConfigurationPacket(byte[] bytes) implements Action {}

    /** Payload = un packet MC clientbound protocole PLAY (opaque). */
    record GamePacket(byte[] bytes) implements Action {}

    /** Création du joueur local au début d'un segment. Payload opaque en Phase 1. */
    record CreatePlayer(byte[] bytes) implements Action {}

    /** Batch de mouvements d'entités groupé par dimension. Payload opaque en Phase 1. */
    record MoveEntities(byte[] bytes) implements Action {}

    /**
     * Référence vers un chunk MC mis en cache dans {@code level_chunk_caches/}.
     * Décodé : payload = {@code VarInt cacheIndex}.
     */
    record CacheChunkRef(int cacheIndex) implements Action {}

    /** Payload Simple Voice Chat non-encoded. Opaque. */
    record VoiceChat(byte[] bytes) implements Action {}

    /** Payload Simple Voice Chat encoded (Arcade only, rare dans Flashback-native). */
    record EncodedVoiceChat(byte[] bytes) implements Action {}

    /** Id non catalogué — bytes préservés pour round-trip. */
    record Unknown(String id, byte[] payload) implements Action {}

    /** Helper pour décoder `CacheChunkRef` depuis ses bytes. */
    static CacheChunkRef decodeCacheChunkRef(byte[] payload) {
        int idx = VarInts.readVarInt(Unpooled.wrappedBuffer(payload));
        return new CacheChunkRef(idx);
    }
}
```

- [ ] **Step 2 : Écrire `ActionType.java`**

```java
package fr.zeffut.multiview.format;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Registry id → decoder. Le segment porte une table {@code ordinal -> id} dans
 * son header ; cette classe dit comment transformer un payload brut en {@link Action}
 * typée une fois l'id résolu.
 */
public final class ActionType {
    public static final String NEXT_TICK          = "flashback:action/next_tick";
    public static final String CONFIGURATION      = "flashback:action/configuration_packet";
    public static final String GAME_PACKET        = "flashback:action/game_packet";
    public static final String CREATE_PLAYER      = "flashback:action/create_local_player";
    public static final String MOVE_ENTITIES      = "flashback:action/move_entities";
    public static final String CACHE_CHUNK        = "flashback:action/level_chunk_cached";
    public static final String VOICE_CHAT         = "flashback:action/simple_voice_chat_sound_optional";
    public static final String ENCODED_VOICE_CHAT = "arcade_replay:action/encoded_simple_voice_chat_sound_optional";

    private static final Map<String, Function<byte[], Action>> CODECS = new ConcurrentHashMap<>();

    static {
        register(NEXT_TICK,          bytes -> new Action.NextTick());
        register(CONFIGURATION,      Action.ConfigurationPacket::new);
        register(GAME_PACKET,        Action.GamePacket::new);
        register(CREATE_PLAYER,      Action.CreatePlayer::new);
        register(MOVE_ENTITIES,      Action.MoveEntities::new);
        register(CACHE_CHUNK,        Action::decodeCacheChunkRef);
        register(VOICE_CHAT,         Action.VoiceChat::new);
        register(ENCODED_VOICE_CHAT, Action.EncodedVoiceChat::new);
    }

    private ActionType() {}

    public static void register(String id, Function<byte[], Action> codec) {
        CODECS.put(id, codec);
    }

    public static Action decode(String id, byte[] payload) {
        Function<byte[], Action> codec = CODECS.get(id);
        if (codec == null) {
            return new Action.Unknown(id, payload);
        }
        return codec.apply(payload);
    }

    public static boolean isKnown(String id) {
        return CODECS.containsKey(id);
    }
}
```

- [ ] **Step 3 : Compiler**

```bash
./gradlew compileJava
```
Expected : BUILD SUCCESSFUL. Tests via `SegmentReaderTest` (Task 7).

- [ ] **Step 4 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/format/Action.java src/main/java/fr/zeffut/multiview/format/ActionType.java
git commit -m "feat(format): sealed Action with 8 known variants + ActionType registry"
```

---

## Task 6 : `PacketEntry` record

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/format/PacketEntry.java`

- [ ] **Step 1 : Écrire**

```java
package fr.zeffut.multiview.format;

/**
 * Action horodatée en tick absolu (depuis le début du replay, tick 0 = première frame).
 *
 * @param tick          tick absolu dans la timeline du replay
 * @param segmentName   nom du segment source (ex. "c3.flashback") — utile pour debug
 * @param inSnapshot    true si l'action vient du bloc Snapshot (état initial du segment),
 *                      false si elle vient du live stream. Phase 3 a besoin de la distinction
 *                      pour ne pas doubler l'état au boundary de segment.
 * @param action        l'action décodée
 */
public record PacketEntry(int tick, String segmentName, boolean inSnapshot, Action action) {
}
```

- [ ] **Step 2 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/format/PacketEntry.java
git commit -m "feat(format): PacketEntry record for timed actions"
```

---

## Task 7 : `SegmentReader` — décode un `cN.flashback` complet (magic + registry + snapshot + live stream)

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/format/SegmentReader.java`
- Test: `src/test/java/fr/zeffut/multiview/format/SegmentReaderTest.java`

**Format** (voir `format/README.md §2` pour la spec complète validée contre Arcade) :

```
Segment := Magic(int32 BE = 0xD780E884)
           VarInt count
           count × [VarInt strLen, UTF-8 strLen bytes]   // registry
           int32 BE snapshotSize
           snapshotSize bytes de ActionStream            // snapshot
           ActionStream jusqu'à EOF                      // live

Action := VarInt actionId
          int32 BE payloadSize
          payloadSize bytes
```

`actionId` est un **ordinal** dans le registry local du segment.

Le `SegmentReader` lit le header dans son constructeur (eager) et expose ensuite un stream d'actions via un iterator qui marque chaque action comme `inSnapshot` ou non.

- [ ] **Step 1 : Test d'abord**

`src/test/java/fr/zeffut/multiview/format/SegmentReaderTest.java` :

```java
package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentReaderTest {

    /** Écrit un segment synthétique minimal dans un ByteBuf. */
    private static ByteBuf buildSegment(String[] registry, int snapshotActions, int liveActions) {
        ByteBuf buf = Unpooled.buffer();
        // Magic
        buf.writeInt(0xD780E884);
        // Registry
        VarInts.writeVarInt(buf, registry.length);
        for (String id : registry) {
            byte[] b = id.getBytes(StandardCharsets.UTF_8);
            VarInts.writeVarInt(buf, b.length);
            buf.writeBytes(b);
        }
        // Snapshot — on précalcule dans un buffer séparé pour obtenir sa taille
        ByteBuf snap = Unpooled.buffer();
        for (int i = 0; i < snapshotActions; i++) {
            VarInts.writeVarInt(snap, 0);   // ordinal 0 = NextTick
            snap.writeInt(0);                // payloadSize 0
        }
        buf.writeInt(snap.readableBytes());
        buf.writeBytes(snap);
        // Live
        for (int i = 0; i < liveActions; i++) {
            VarInts.writeVarInt(buf, 0);
            buf.writeInt(0);
        }
        return buf;
    }

    @Test
    void readsMagicRegistryAndEmptySnapshotEmptyLive() {
        ByteBuf buf = buildSegment(new String[] { ActionType.NEXT_TICK }, 0, 0);
        SegmentReader reader = new SegmentReader("test.flashback", new FlashbackByteBuf(buf));
        assertEquals(1, reader.registry().size());
        assertEquals(ActionType.NEXT_TICK, reader.registry().get(0));
        assertFalse(reader.hasNext());
    }

    @Test
    void rejectsBadMagic() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(0xDEADBEEF);
        assertThrows(IllegalArgumentException.class,
                () -> new SegmentReader("bad.flashback", new FlashbackByteBuf(buf)));
    }

    @Test
    void emitsSnapshotThenLiveWithInSnapshotFlag() {
        ByteBuf buf = buildSegment(new String[] { ActionType.NEXT_TICK }, 2, 3);
        SegmentReader reader = new SegmentReader("test.flashback", new FlashbackByteBuf(buf));

        List<Boolean> flags = new ArrayList<>();
        List<Action> actions = new ArrayList<>();
        while (reader.hasNext()) {
            boolean inSnap = reader.isPeekInSnapshot();
            Action a = reader.next();
            flags.add(inSnap);
            actions.add(a);
        }
        assertEquals(5, actions.size());
        assertEquals(List.of(true, true, false, false, false), flags);
        actions.forEach(a -> assertInstanceOf(Action.NextTick.class, a));
    }

    @Test
    void unknownOrdinalProducesUnknownAction() {
        // Un id non connu d'ActionType doit produire Action.Unknown
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(0xD780E884);
        VarInts.writeVarInt(buf, 1);
        byte[] idBytes = "custom:mod/foo".getBytes(StandardCharsets.UTF_8);
        VarInts.writeVarInt(buf, idBytes.length);
        buf.writeBytes(idBytes);
        buf.writeInt(0); // snapshot size 0
        // 1 action live avec payload { 0x42 }
        VarInts.writeVarInt(buf, 0);
        buf.writeInt(1);
        buf.writeByte(0x42);

        SegmentReader reader = new SegmentReader("test.flashback", new FlashbackByteBuf(buf));
        assertTrue(reader.hasNext());
        Action a = reader.next();
        Action.Unknown u = assertInstanceOf(Action.Unknown.class, a);
        assertEquals("custom:mod/foo", u.id());
        assertEquals(1, u.payload().length);
        assertEquals(0x42, u.payload()[0]);
    }
}
```

- [ ] **Step 2 : Run → fail**

```bash
./gradlew test --tests SegmentReaderTest
```
Expected : compile error.

- [ ] **Step 3 : Implémenter `SegmentReader.java`**

```java
package fr.zeffut.multiview.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Décode un segment {@code cN.flashback}. Le header (magic + registry + snapshot size)
 * est lu eagerly dans le constructeur ; les actions sont émises lazy via
 * {@link #hasNext()} / {@link #next()}.
 *
 * <p>Format (spec complète dans format/README.md §2) :
 * <pre>
 *   int32 BE magic == 0xD780E884
 *   VarInt registryCount
 *   registryCount × [VarInt len, UTF-8 bytes]
 *   int32 BE snapshotSize
 *   snapshotSize bytes d'actions (= snapshot)
 *   actions jusqu'à EOF (= live stream)
 *
 *   Action := VarInt actionOrdinal, int32 BE payloadSize, payloadSize bytes
 * </pre>
 *
 * <p>Non thread-safe. Consume-once.
 */
public final class SegmentReader implements Iterator<Action> {
    public static final int MAGIC = 0xD780E884;

    private final String segmentName;
    private final FlashbackByteBuf buf;
    private final List<String> registry;
    private final int snapshotEndIndex;

    public SegmentReader(String segmentName, FlashbackByteBuf buf) {
        this.segmentName = segmentName;
        this.buf = buf;

        int magic = buf.readInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                    "Invalid Flashback segment magic in " + segmentName
                            + ": expected 0x" + Integer.toHexString(MAGIC)
                            + " got 0x" + Integer.toHexString(magic));
        }

        int count = buf.readVarInt();
        List<String> reg = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int len = buf.readVarInt();
            byte[] idBytes = buf.readBytes(len);
            reg.add(new String(idBytes, StandardCharsets.UTF_8));
        }
        this.registry = Collections.unmodifiableList(reg);

        int snapshotSize = buf.readInt();
        this.snapshotEndIndex = buf.readerIndex() + snapshotSize;
    }

    public String segmentName() {
        return segmentName;
    }

    public List<String> registry() {
        return registry;
    }

    /**
     * True si la prochaine {@link #next()} va retourner une action du bloc Snapshot
     * (par opposition au live stream). À consulter AVANT d'appeler next().
     */
    public boolean isPeekInSnapshot() {
        return buf.readerIndex() < snapshotEndIndex;
    }

    @Override
    public boolean hasNext() {
        return buf.isReadable();
    }

    @Override
    public Action next() {
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
        String id = registry.get(ordinal);
        return ActionType.decode(id, payload);
    }
}
```

- [ ] **Step 4 : Run → pass**

```bash
./gradlew test --tests SegmentReaderTest
```
Expected : 4 tests passed.

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/format/SegmentReader.java src/test/java/fr/zeffut/multiview/format/SegmentReaderTest.java
git commit -m "feat(format): SegmentReader decodes magic + registry + snapshot + live stream"
```

---

## Task 8 : `FlashbackReplay` + `FlashbackReader`

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/format/FlashbackReplay.java`
- Create: `src/main/java/fr/zeffut/multiview/format/FlashbackReader.java`

- [ ] **Step 1 : `FlashbackReplay.java`**

```java
package fr.zeffut.multiview.format;

import java.nio.file.Path;
import java.util.List;

/**
 * Représente un replay Flashback ouvert. Contient la metadata et les chemins
 * des segments dans l'ordre déclaré par metadata.json.
 */
public record FlashbackReplay(Path folder, FlashbackMetadata metadata, List<Path> segmentPaths) {
}
```

- [ ] **Step 2 : `FlashbackReader.java`**

```java
package fr.zeffut.multiview.format;

import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class FlashbackReader {

    private FlashbackReader() {}

    /** Ouvre un replay depuis un dossier contenant metadata.json + cN.flashback. */
    public static FlashbackReplay open(Path folder) throws IOException {
        Path metadataPath = folder.resolve("metadata.json");
        if (!Files.isRegularFile(metadataPath)) {
            throw new IOException("metadata.json not found in " + folder);
        }
        FlashbackMetadata metadata;
        try (Reader r = Files.newBufferedReader(metadataPath)) {
            metadata = FlashbackMetadata.fromJson(r);
        }

        List<Path> segments = new ArrayList<>();
        for (String name : metadata.chunks().keySet()) {
            Path seg = folder.resolve(name);
            if (!Files.isRegularFile(seg)) {
                throw new IOException("segment " + name + " declared in metadata but missing from " + folder);
            }
            segments.add(seg);
        }
        return new FlashbackReplay(folder, metadata, List.copyOf(segments));
    }

    /**
     * Stream global d'entries sur tous les segments, ticks absolus remontés depuis le
     * début du replay. Les durées déclarées dans metadata servent à calculer les bornes
     * de tick attendues par segment — non utilisées pour le décodage, seulement le tick
     * reporté.
     */
    public static Stream<PacketEntry> stream(FlashbackReplay replay) {
        return replay.segmentPaths().stream().flatMap(segment -> streamSegment(replay, segment));
    }

    private static Stream<PacketEntry> streamSegment(FlashbackReplay replay, Path segment) {
        String segmentName = segment.getFileName().toString();
        int baseTick = baseTickFor(replay, segmentName);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(segment);
        } catch (IOException e) {
            throw new RuntimeException("failed to read " + segment, e);
        }
        FlashbackByteBuf buf = new FlashbackByteBuf(Unpooled.wrappedBuffer(bytes));
        SegmentReader reader = new SegmentReader(segmentName, buf);

        final int[] tick = { baseTick };
        Iterator<PacketEntry> it = new Iterator<>() {
            @Override public boolean hasNext() { return reader.hasNext(); }
            @Override public PacketEntry next() {
                boolean inSnap = reader.isPeekInSnapshot();
                Action a = reader.next();
                if (a instanceof Action.NextTick && !inSnap) {
                    tick[0]++;
                }
                return new PacketEntry(tick[0], segmentName, inSnap, a);
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    private static int baseTickFor(FlashbackReplay replay, String segmentName) {
        int base = 0;
        for (var entry : replay.metadata().chunks().entrySet()) {
            if (entry.getKey().equals(segmentName)) return base;
            base += entry.getValue().duration();
        }
        throw new IllegalArgumentException("segment " + segmentName + " not in metadata.chunks");
    }
}
```

- [ ] **Step 3 : Smoke-compile**

```bash
./gradlew compileJava
```
Expected : BUILD SUCCESSFUL. Tests d'intégration viennent en Task 10.

- [ ] **Step 4 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/format/FlashbackReplay.java src/main/java/fr/zeffut/multiview/format/FlashbackReader.java
git commit -m "feat(format): FlashbackReader loads replay folders and streams entries"
```

---

## Task 9 : Commande `/mv inspect <replayName>`

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/inspect/InspectCommand.java`
- Modify: `src/main/java/fr/zeffut/multiview/MultiViewMod.java` (enregistrer la commande)

- [ ] **Step 1 : Écrire `InspectCommand.java`**

```java
package fr.zeffut.multiview.inspect;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.zeffut.multiview.MultiViewMod;
import fr.zeffut.multiview.format.Action;
import fr.zeffut.multiview.format.FlashbackReader;
import fr.zeffut.multiview.format.FlashbackReplay;
import fr.zeffut.multiview.format.PacketEntry;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationEvent;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class InspectCommand {

    private InspectCommand() {}

    public static void register() {
        ClientCommandRegistrationEvent.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("mv")
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("inspect")
                            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("replayName", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String name = StringArgumentType.getString(ctx, "replayName");
                                        return inspect(ctx.getSource(), name);
                                    }))));
        });
    }

    private static int inspect(FabricClientCommandSource src, String replayName) {
        Path replayFolder = MinecraftClient.getInstance().runDirectory.toPath()
                .resolve("replay").resolve(replayName);
        try {
            FlashbackReplay replay = FlashbackReader.open(replayFolder);
            src.sendFeedback(Text.literal("UUID: " + replay.metadata().uuid()));
            src.sendFeedback(Text.literal("World: " + replay.metadata().worldName()));
            src.sendFeedback(Text.literal("MC: " + replay.metadata().versionString()
                    + " (protocol " + replay.metadata().protocolVersion() + ")"));
            src.sendFeedback(Text.literal("Total ticks (metadata): " + replay.metadata().totalTicks()));
            src.sendFeedback(Text.literal("Segments: " + replay.segmentPaths().size()));
            src.sendFeedback(Text.literal("Markers: " + replay.metadata().markers().size()));

            Map<String, Integer> actionHistogram = new HashMap<>();
            int[] stats = { 0, 0, 0 }; // [entriesCount, maxTick, snapshotCount]
            FlashbackReader.stream(replay).forEach(entry -> {
                stats[0]++;
                if (entry.tick() > stats[1]) stats[1] = entry.tick();
                if (entry.inSnapshot()) stats[2]++;
                String bucket = switch (entry.action()) {
                    case Action.NextTick nt          -> "NextTick";
                    case Action.ConfigurationPacket c -> "ConfigurationPacket";
                    case Action.GamePacket g          -> "GamePacket";
                    case Action.CreatePlayer p        -> "CreatePlayer";
                    case Action.MoveEntities m        -> "MoveEntities";
                    case Action.CacheChunkRef r       -> "CacheChunkRef";
                    case Action.VoiceChat v           -> "VoiceChat";
                    case Action.EncodedVoiceChat e    -> "EncodedVoiceChat";
                    case Action.Unknown u             -> "Unknown:" + u.id();
                };
                actionHistogram.merge(bucket, 1, Integer::sum);
            });
            src.sendFeedback(Text.literal("Snapshot entries: " + stats[2]
                    + " / live: " + (stats[0] - stats[2])));
            src.sendFeedback(Text.literal("Entries decoded: " + stats[0] + " | max tick seen: " + stats[1]));
            MultiViewMod.LOGGER.info("[inspect] {} — histogram: {}", replayName, actionHistogram);
            return Command.SINGLE_SUCCESS;
        } catch (IOException e) {
            MultiViewMod.LOGGER.error("Failed to inspect replay {}", replayName, e);
            src.sendError(Text.literal("Failed: " + e.getMessage()));
            return 0;
        }
    }
}
```

- [ ] **Step 2 : Wire la commande dans `MultiViewMod.java`**

Modifier `src/main/java/fr/zeffut/multiview/MultiViewMod.java` — remplacer son contenu par :

```java
package fr.zeffut.multiview;

import fr.zeffut.multiview.inspect.InspectCommand;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MultiViewMod implements ClientModInitializer {
    public static final String MOD_ID = "multiview";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("MultiView loaded — addon pour Flashback, merge de replays multi-joueurs.");
        InspectCommand.register();
    }
}
```

Le `SmokeTest` existant continue de passer : `MOD_ID` et `LOGGER` sont intacts.

- [ ] **Step 3 : Compile + tests existants passent**

```bash
./gradlew build
```
Expected : BUILD SUCCESSFUL. SmokeTest toujours vert.

- [ ] **Step 4 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/inspect/InspectCommand.java src/main/java/fr/zeffut/multiview/MultiViewMod.java
git commit -m "feat(inspect): add /mv inspect command for replay debugging"
```

---

## Task 10 : Validation bout-en-bout sur les POV réels

**Files:** aucun — validation manuelle + test d'intégration conditionnel.

- [ ] **Step 1 : Lancer le client dev**

```bash
./gradlew runClient 2>&1 | tee /tmp/multiview-inspect.log
```

En jeu, ouvrir le chat (`T`) et taper :
```
/mv inspect 2026-02-20T23_25_15
```

Expected dans le chat :
- UUID: 2b5459d2-d7ad-43dc-ae36-e6922cf1a45f
- World: HIKA CIVILIZATION
- MC: 1.21.11 (protocol 774)
- Total ticks (metadata): 184238
- Segments: 33
- Markers: 2
- Entries decoded: <N, à noter> | max tick seen: <≈ 184238, doit être proche>

- [ ] **Step 2 : Idem pour le 2e POV**

```
/mv inspect Sénat_empirenapo2026-02-20T23_20_16
```

Expected :
- UUID: b778ffea-9268-466f-be8a-e7044e6f85c7
- World: HIKA CIVILIZATION
- Total ticks (metadata): 170068
- Max tick seen proche de 170068

- [ ] **Step 3 : Fermer Minecraft. Vérifier le log d'histogramme**

```bash
grep "\[inspect\]" /tmp/multiview-inspect.log
```

Expected : deux lignes, une par POV inspecté, avec la répartition des types d'action. Si **beaucoup d'entrées `Unknown:*`** apparaissent, noter leurs ids → ça veut dire que `ActionType` manque des codecs. Pas bloquant pour Phase 1 (on décode quand même), mais à documenter dans Task 12.

- [ ] **Step 4 : Ajuster `ActionType` si nécessaire**

Pour chaque id `flashback:action/*` non reconnu vu dans l'histogramme :
- Si c'est un type trivial sans payload (comme `next_tick`) : ajouter un `register(...)` dans le static init.
- Si c'est un type avec payload (la grande majorité) : enregistrer comme `Packet` si le payload est un packet MC sérialisé, sinon laisser en `Unknown` et documenter dans `format/README.md §4`.

**Ne pas inventer** la sémantique d'un type — soit on l'a vu dans Arcade (Task 1), soit on l'annote `Unknown` avec sa signature. C'est OK d'avoir des `Unknown` à la fin de Phase 1 : Phase 2+ les raffinera.

- [ ] **Step 5 : Commit les raffinements si nécessaires**

```bash
git add src/main/java/fr/zeffut/multiview/format/ActionType.java src/main/java/fr/zeffut/multiview/format/README.md
git commit -m "feat(format): refine ActionType codecs based on real replay inspection"
```

Si aucun ajustement nécessaire, ne pas créer de commit vide.

---

## Task 11 : Test d'intégration conditionnel sur les POV

**Files:**
- Create: `src/test/java/fr/zeffut/multiview/format/FlashbackReaderIntegrationTest.java`

- [ ] **Step 1 : Écrire le test avec skip conditionnel**

```java
package fr.zeffut.multiview.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashbackReaderIntegrationTest {

    private static final Path REPLAYS_DIR = Paths.get(System.getProperty("user.dir"), "run", "replay");

    static boolean replaysAvailable() {
        return Files.isDirectory(REPLAYS_DIR)
                && hasAnyReplay(REPLAYS_DIR);
    }

    private static boolean hasAnyReplay(Path dir) {
        try {
            return Files.list(dir)
                    .filter(Files::isDirectory)
                    .anyMatch(d -> Files.isRegularFile(d.resolve("metadata.json")));
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    @EnabledIf("replaysAvailable")
    void openAllAvailableReplays() throws IOException {
        AtomicInteger count = new AtomicInteger();
        try (var stream = Files.list(REPLAYS_DIR)) {
            stream.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("metadata.json")))
                    .forEach(folder -> {
                        try {
                            FlashbackReplay replay = FlashbackReader.open(folder);
                            int sumDurations = replay.metadata().chunks().values().stream()
                                    .mapToInt(FlashbackMetadata.ChunkInfo::duration)
                                    .sum();
                            assertEquals(replay.metadata().totalTicks(), sumDurations,
                                    "total_ticks mismatch in " + folder);
                            long decoded = FlashbackReader.stream(replay).count();
                            assertTrue(decoded > 0, "no entries decoded for " + folder);
                            count.incrementAndGet();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        assertTrue(count.get() > 0, "no replays were inspected");
    }
}
```

- [ ] **Step 2 : Run le test**

```bash
./gradlew test --tests FlashbackReaderIntegrationTest
```

Expected si les POV sont dans `run/replay/` : 1 test passé. Sinon : test skipped (grâce à `@EnabledIf`). En CI (où `run/replay/` n'existe pas) : skipped, build reste vert.

- [ ] **Step 3 : Commit**

```bash
git add src/test/java/fr/zeffut/multiview/format/FlashbackReaderIntegrationTest.java
git commit -m "test(format): integration test against local replays (skipped if absent)"
```

---

## Task 12 : Compléter `format/README.md`

**Files:**
- Modify: `src/main/java/fr/zeffut/multiview/format/README.md`

- [ ] **Step 1 : Documenter tous les types d'action vus**

Ajouter au README la section §4 avec :
- Pour chaque id `flashback:action/*` rencontré dans les deux POV : son nom, est-ce qu'on a un codec dédié ou `Unknown`, la structure du payload si connue.
- Un tableau récapitulatif.

- [ ] **Step 2 : Documenter `level_chunk_caches/`**

Pour l'instant, notre reader **ne parse pas** `level_chunk_caches/` — c'est un cache de chunks MC référencé par les segments. Documenter dans §5 :
- Layout observé : un seul fichier `0` (186 MB pour POV1, ~similaire pour POV2)
- Théorie : cache partagé entre segments pour éviter de redonder la donnée chunk (voir Task 1 / Arcade `ChunkPacketIdentity.kt`)
- Scope Phase 1 : on note son existence, la Phase 2 (writer) devra savoir comment le produire.

- [ ] **Step 3 : Commit final + tag**

```bash
git add src/main/java/fr/zeffut/multiview/format/README.md
git commit -m "docs(format): complete reverse-engineered format documentation"
git tag phase-1-complete
```

- [ ] **Step 4 : Mettre à jour le journal dans `SPEC.md`**

Ajouter une entrée datée à la section 10 de `SPEC.md` :

```markdown
### 2026-04-19 — Phase 1 terminée : lecteur du format .flashback
- Format `.flashback` (dossier non zippé) documenté dans `src/main/java/fr/zeffut/multiview/format/README.md`.
- `metadata.json` parsé via Gson (`FlashbackMetadata`).
- Segments `cN.flashback` décodés en stream d'`Action` via `SegmentReader` (VarInt length-prefix + namespaced id + payload).
- API publique : `FlashbackReader.open(Path) -> FlashbackReplay`, `FlashbackReader.stream(replay) -> Stream<PacketEntry>`.
- Commande `/mv inspect <name>` valide sur les deux POV réels de test (HIKA Civilization, MC 1.21.11).
- `total_ticks` reporté par le reader match `metadata.total_ticks` : no-loss sur la timeline.
- Actions non reconnues conservées intactes en `Action.Unknown` (id + payload bytes) — prêt pour round-trip en Phase 2.
- Rosetta Stone exclusive : `CasualChampionships/Arcade` (arcade-replay). Zéro code Moulberry consulté.
```

```bash
git add SPEC.md
git commit -m "docs: update journal with phase 1 completion"
```

---

## Critères d'acceptation Phase 1

Tous verts avant de considérer la phase close :

1. ✅ `./gradlew build` + `./gradlew test` passent (unitaires + intégration conditionnelle)
2. ✅ CI GitHub Actions verte (integration test skipped, unitaires passent)
3. ✅ `/mv inspect 2026-02-20T23_25_15` dans le client dev retourne metadata + entries count sans erreur
4. ✅ `/mv inspect Sénat_empirenapo2026-02-20T23_20_16` idem
5. ✅ `max tick seen` ≈ `metadata.total_ticks` pour chaque POV (tolérance : exacte ou à ±1 près)
6. ✅ `format/README.md` documente metadata.json, structure binaire segment, liste des action types vus, état de `level_chunk_caches/`
7. ✅ Tag `phase-1-complete` posé
8. ✅ Aucun code Kotlin copié depuis Arcade ou Moulberry (inspection visuelle du diff final)

---

## Self-review (par l'auteur du plan)

- **Spec coverage (SPEC.md §5 Phase 1)** :
  - ✅ Générer 3 replays de test → remplacé par l'usage des 2 POV réels déjà fournis par l'utilisateur. Skippé la génération synthétique, justifié.
  - ✅ Unzip + cartographier → Task 1 + findings documentés dans format/README.md §1.
  - ✅ Cross-checker ServerReplay/arcade-replay → Task 1 utilise Arcade comme Rosetta Stone exclusive.
  - ✅ Décompilation Flashback → explicitement **évitée** (licence Moulberry).
  - ✅ Rédiger format/README.md → Task 1 squelette + Task 12 complétion.
  - ✅ FlashbackMetadata → Task 4.
  - ✅ FlashbackReader → Task 8.
  - ✅ Test de validation : lire un replay, logger tous les packets → Tasks 10-11.
- **Placeholder scan** : aucun TODO, aucun "à remplir". Les endroits où Task 1 peut modifier la liste des action types sont **explicitement conditionnels** et avec instructions précises (pas des placeholders paresseux).
- **Type consistency** : `FlashbackMetadata`, `FlashbackReplay`, `FlashbackReader`, `FlashbackByteBuf`, `VarInts`, `PacketEntry`, `Action`, `ActionType`, `SegmentReader`, `InspectCommand` — tous utilisés cohérents entre tasks. `chunks()` = map, `segmentPaths()` = list, `tick` partout = `int`.
- **Risque connu** : Task 7 suppose une structure "totalLen + idLen + id + payload" pour chaque action. Si Task 1 révèle que Flashback utilise un encodage différent (ex. id comme VarInt enum au lieu de string, ou payload pas inclus dans totalLen), Task 7 devra être adaptée avant implémentation. Le plan le signale explicitement.
- **Pas de hardcode du nombre d'actions par segment** : on itère jusqu'à `!buf.isReadable()`, robuste.
