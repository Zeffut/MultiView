# Phase 3 — Merge zéro-perte de N replays (design)

> Spec de conception détaillée pour la Phase 3 de MultiView. À lire avant d'attaquer l'implémentation (plan exécutable dérivé à produire par la skill `superpowers:writing-plans`).

**Goal** : produire `merged.flashback` contenant l'union zéro-perte de N replays sources. Déclenchable par `/mv merge <A> <B> [<C>...] -o <name>`.

**Non-goal Phase 3** : le playback des tracks égocentriques. Le fichier de sortie contient déjà toute l'info égo (dans `ego/<uuid>.flashback`) mais Flashback vanilla les ignore. Phase 3.5 (distincte) ajoutera un Mixin sur le playback pour les consommer.

---

## 1. Décisions prises en brainstorming

| # | Décision | Choix |
|---|---|---|
| 1 | Corpus de test Phase 3 | 2 POV HIKA existants dans `run/replay/` (mêmes date, serveur, version MC) |
| 2 | Phasing `ego` | Phase 3 = écrit dans le ZIP. Phase 3.5 = Mixin playback |
| 3 | Alignement temporel | d = `ClientboundSetTimePacket` auto, fallback `metadata.name`, override CLI manuel |
| 4 | Interface | `/mv merge ...` in-game (à retirer Phase 5 au profit de l'UI Flashback) |
| 5 | Seuils dedup entités | fenêtre ±5 ticks, distance ≤ 2 blocs |
| 6 | Politique d'ambiguïté entités | **b** = fusion forcée par confiance géographique (au lieu de garder les deux, contrairement au SPEC §3.3.2 initial) |
| 7 | Mémoire | streaming k-way merge, pas de chargement global en RAM |
| 8 | Local player du merged | POV le plus long. Les autres deviennent `AddPlayer`. |
| 9 | Scope N | code pour N dès Phase 3, validation sur 2 POV |
| 10a | `level_chunk_caches` | concat des caches + remap des `CacheChunkRef` |
| 10b | Format égo | `ego/<playerUUID>.flashback` (segment standard) |

---

## 2. Architecture

### 2.1 Pipeline

```
N FlashbackReader.stream()  →  TimelineAligner (tick→tick absolu)
                                         │
                              PriorityQueue<PeekingIterator<PacketEntry>>
                              (ordre croissant tick absolu, tie-break par sourceIdx)
                                         │
                                         ▼
                              PacketClassifier
                              → WORLD | ENTITY | EGO_<uuid> | GLOBAL | CACHE_REF
                              | LOCAL_PLAYER | CONFIG | PASSTHROUGH
                                         │
                     ┌───────────┬─────────┼──────────┬────────────┐
                     ▼           ▼         ▼          ▼            ▼
                WorldState   EntityMerger EgoRouter Globaldedup IdRemapper
                Merger       (fenêtre    (dispatch  (HashSet<    (mappe
                (LWW sur     glissante    vers ego/  type,ts,    entity IDs
                BlockPos)    ±5 ticks)    <uuid>/) hash>→ 1x)   sources→
                                                                globaux)
                     │
                     ▼
             FlashbackWriter streaming
             ├── main stream : c0.flashback, c1.flashback, ...
             ├── ego/<uuid>.flashback : un SegmentWriter par joueur
             ├── level_chunk_caches/ : concat + remap
             └── metadata.json : total_ticks = max(end recalé) - min(start recalé)
```

### 2.2 Composants (nouveau package `fr.zeffut.multiview.merge/`)

- `MergeOrchestrator` — entry point. Assemble le pipeline, gère la progression et le `MergeReport`.
- `TimelineAligner` — calcule `tickOffset[sourceIdx]`. Algo §3.
- `PacketClassifier` — classifie un `PacketEntry` en catégorie. Algo §4.
- `SourcePovTracker` — pour chaque source, maintient la position du POV enregistreur (le joueur qui a enregistré le replay) à chaque tick. Utilisé par `EntityMerger` pour calculer la confiance géographique (distance POV → entité).
- `WorldStateMerger` — `Map<GlobalBlockPos, (tickAbs, blockState, sourceIdx)>`, last-write-wins. `GlobalBlockPos = (dimensionId, x, y, z)`.
- `EntityMerger` — dedup par UUID + heuristique (±5 ticks / ≤ 2 blocs), ambiguïté résolue par confiance géographique via `SourcePovTracker`.
- `IdRemapper` — `Map<(sourceIdx, localEntityId), globalEntityId>`, bijection.
- `EgoRouter` — un `SegmentWriter` par playerUUID dans `ego/<uuid>.flashback`.
- `GlobalDeduper` — `HashSet<(packetTypeId, tickAbs, contentHash)>`.
- `CacheRemapper` — concat caches, remap `CacheChunkRef`.
- `MergeReport` — stats + warnings, sérialisé en `merge-report.json`.

---

## 3. TimelineAligner (algorithme détaillé)

**Input** : N sources, chacune avec `metadata.name` + stream de packets à ticks locaux.
**Output** : `tickOffset[sourceIdx]` en ticks absolus tels que `tickAbs = tickLocal + tickOffset[sourceIdx]`.

### 3.1 Cascade de stratégies

1. **Priorité 1 — ancre serveur `ClientboundSetTimePacket`** :
   - Pré-scan de chaque source jusqu'au premier `SetTime` trouvé (borne : 60 secondes en tick local, soit 1200 ticks, pour éviter de scanner le replay entier).
   - `SetTime` contient `long gameTime` (tick serveur global). Envoyé par le serveur 1×/sec.
   - Pour chaque source i : `anchorServer[i] = gameTime_observed`, `anchorLocal[i] = tickLocal_at_which_observed`.
   - Offset en tick serveur absolu : `tickOffset[i] = anchorServer[i] - anchorLocal[i]`.
   - Précision : **au tick exact** si toutes les sources ont le même serveur source.

2. **Priorité 2 — fallback `metadata.name` parsé** :
   - Activé si une source n'a pas de `SetTime` dans ses 1200 premiers ticks locaux.
   - Parse `metadata.name` au format `"YYYY-MM-DDTHH:mm:ss"` (ou variantes avec chemins ; on extrait la sous-chaîne correspondant au regex `\d{4}-\d{2}-\d{2}T\d{2}[:_]\d{2}[:_]\d{2}`).
   - Convertit en epoch-seconds → ticks (×20).
   - Précision : ±20 ticks. **Warning logué** dans `MergeReport`.
   - Si le parse échoue aussi → ABORT avec message clair (l'utilisateur doit fournir `--offset-<label>`).

3. **Priorité 3 — override CLI** :
   - Flag `--offset-<sourceLabel>=<±N>` où `<sourceLabel>` est le nom du dossier.
   - Appliqué en dernier (après les priorités 1/2) : `tickOffset[label] += overrideValue`.

### 3.2 Normalisation

- `tickStartAbs = min(tickOffset[i])`. Devient le tick 0 du merged.
- `tickEndAbs = max(tickOffset[i] + totalTicks[i])`.
- `metadata.total_ticks = tickEndAbs - tickStartAbs`.
- Offset effectif appliqué à chaque PacketEntry d'une source i : `newTick = (tickOffset[i] - tickStartAbs) + tickLocal`.

### 3.3 Packet ID de `ClientboundSetTimePacket`

Résolution runtime via Minecraft (on tourne dans le JVM client via `/mv merge`) : `ClientboundGamePackets.SET_TIME` → `PacketType<?>` → lookup de l'ID dans le registry du protocole. Aucune valeur hardcodée. Pour les tests unitaires sans JVM Minecraft, le ID est fourni via une interface `PacketIdProvider` injectable.

### 3.4 Cas limite

- Aucun replay n'a de `SetTime` en début : tous tombent sur la priorité 2. Warning agrégé.
- Deux sources donnent un `gameTime` absurde (ex. serveur desynchronisé via `/time set`) : offsets incohérents mais le merge continue ; l'utilisateur verra que les 2 joueurs sont décalés et pourra corriger via `--offset-<label>`.

---

## 4. PacketClassifier

### 4.1 Dispatch par type d'Action (sealed `Action`)

| Action sealed | Catégorie |
|---|---|
| `NextTick` | `TICK` (rejoué tel quel, recalé via offset) |
| `ConfigurationPacket` | `CONFIG` (dedup par hash, émis 1× en snapshot) |
| `CreateLocalPlayer` | `LOCAL_PLAYER` — règle Section 6 |
| `MoveEntities` | `ENTITY` (action Flashback propre, batch de positions. Décodée, chaque entrée passe par `EntityMerger` + `IdRemapper`. Coexiste avec `GamePacket(ClientboundMoveEntity*)` vanilla — les deux encodages sont possibles selon la source, tous deux routés vers `EntityMerger`.) |
| `CacheChunkRef` | `CACHE_REF` (passe par `CacheRemapper`) |
| `VoiceChat` / `EncodedVoiceChat` | `EGO_<sourceUuid>` (Phase 3 pragma : tout en égo ; Phase 4 peut affiner) |
| `GamePacket` | **dispatch par packet ID**, §4.2 |
| `Unknown` | `PASSTHROUGH` + warn 1× par `id` |

### 4.2 Dispatch des `GamePacket` par packet ID

On décode le `packetId` (VarInt en tête du payload) et on consulte une `Map<Integer, Category>`. La map est construite au démarrage de `/mv merge` par introspection des constantes Minecraft (`ClientboundGamePackets`) :

| Packet Class | Catégorie |
|---|---|
| `ClientboundSetTimePacket` | `GLOBAL` |
| `ClientboundLevelChunkWithLightPacket` | `WORLD` (chunk load) |
| `ClientboundForgetLevelChunkPacket` | `WORLD` (chunk unload) |
| `ClientboundBlockUpdatePacket` | `WORLD` (LWW) |
| `ClientboundSectionBlocksUpdatePacket` | `WORLD` (batch, dépaqueté) |
| `ClientboundAddEntityPacket` | `ENTITY` (dedup + remap) |
| `ClientboundRemoveEntitiesPacket` | `ENTITY` |
| `ClientboundMoveEntityPosPacket` et variants (Rot, PosRot) | `ENTITY` |
| `ClientboundTeleportEntityPacket` | `ENTITY` |
| `ClientboundSetEntityDataPacket` | `ENTITY` |
| `ClientboundEntityEventPacket` | `ENTITY` |
| `ClientboundSetEntityMotionPacket` | `ENTITY` |
| `ClientboundSetHealthPacket` | `EGO_<sourceUuid>` |
| `ClientboundSetExperiencePacket` | `EGO_<sourceUuid>` |
| `ClientboundContainerSetContentPacket` | `EGO_<sourceUuid>` |
| `ClientboundContainerSetSlotPacket` | `EGO_<sourceUuid>` |
| `ClientboundOpenScreenPacket` / `ClientboundContainerClosePacket` | `EGO_<sourceUuid>` |
| `ClientboundSetCarriedItemPacket` | `EGO_<sourceUuid>` |
| `ClientboundPlayerAbilitiesPacket` | `EGO_<sourceUuid>` |
| `ClientboundSystemChatPacket` (type = system) | `GLOBAL` |
| `ClientboundPlayerChatPacket` (type = player) | `GLOBAL` |
| `ClientboundDisguisedChatPacket` | `GLOBAL` |
| `ClientboundExplodePacket` | `GLOBAL` |
| `ClientboundSoundPacket` / `ClientboundSoundEntityPacket` | `GLOBAL` (dedup par (type, tick, hash)) |
| `ClientboundLevelParticlesPacket` | `GLOBAL` |
| `ClientboundLevelEventPacket` | `GLOBAL` |
| Reste | `PASSTHROUGH` (warn 1× par `packetId` dans MergeReport) |

### 4.3 Construction de la table

```
static Map<Integer, Category> buildDispatchTable() {
    Map<Integer, Category> table = new HashMap<>();
    // introspection du registry protocole à MC runtime :
    register(table, ClientboundSetTimePacket.TYPE, Category.GLOBAL);
    register(table, ClientboundBlockUpdatePacket.TYPE, Category.WORLD);
    ...
    return table;
}
```

En tests, la table est construite manuellement (stub) pour éviter une dépendance MC runtime.

---

## 5. Mergers

### 5.0 SourcePovTracker (prérequis)

**Rôle** : pour chaque source, maintenir `povPosition[sourceIdx, tick] = (x, y, z)` — position du POV enregistreur au tick local donné.

**Alimentation** : pour chaque source, on connaît le local entity id du POV (fourni par `CreateLocalPlayer`). On l'observe via :
- `ClientboundPlayerPositionPacket` (téléport serveur → client) : position absolue.
- `ClientboundMoveEntity*` / `ClientboundTeleportEntityPacket` ciblant le local entity id : delta ou absolu.
- `MoveEntities` (action Flashback) : batch qui peut inclure le local entity.

**Interface** : `Vec3 positionAt(int sourceIdx, int tickAbs)` — retourne la dernière position connue au tick ≤ demandé. Mémoire bornée : on garde uniquement la dernière position par tick (une seule entrée écrasée à chaque mise à jour).

### 5.1 WorldStateMerger

**État** : `Map<GlobalBlockPos, BlockLww>` où `BlockLww = (tickAbs, blockState, sourceIdx)` et `GlobalBlockPos = (dimensionId, x, y, z)`.

**Règles** :
- `ClientboundBlockUpdatePacket` : lookup → comparer `tickAbs` :
  - `new > existing` → remplacer, émettre packet dans main stream, compter si existing existait.
  - `new < existing` → drop, compteur `blocksLwwConflicts++`.
  - `new == existing` → drop (même update observé 2×).
- `ClientboundSectionBlocksUpdatePacket` : dépaqueté en updates individuels, même logique. Le packet ré-émis est reconstruit à partir des updates retenus (peut devenir vide → drop).
- `ClientboundLevelChunkWithLightPacket` : chunk load. **Pas de dedup** au niveau chunk (Flashback gère multi-load). Les blocs du chunk initialisent/overwritent l'état LWW pour leurs positions.
- `ClientboundForgetLevelChunkPacket` : unload. Émis tel quel (Flashback gère).

**Dimension tracking** : on maintient `Map<sourceIdx, currentDimension>` via les `ClientboundRespawnPacket` / `ClientboundLoginPacket`. Le `GlobalBlockPos` inclut la dimension pour éviter de mélanger l'Overworld et le Nether.

### 5.2 EntityMerger

**État** :
- `Map<UUID, GlobalEntityId>` — index par UUID pour match direct.
- `Map<GlobalEntityId, EntityState>` où `EntityState = (lastSeenTick, lastPos, type, sources: List<(sourceIdx, localId)>)`.

**Calcul de la confiance géographique** : pour une observation d'une entité au tick `t` par la source `i`, la confiance = distance entre `povPosition[i, t]` (donnée par `SourcePovTracker`) et la position de l'entité. Plus la distance est petite, plus l'observation est fiable. Utilisé uniquement en cas d'ambiguïté ou de divergence entre sources.

**Sur `ClientboundAddEntityPacket` d'une source i** :

```
uuid = packet.uuid
if (uuid != null && uuidIndex.containsKey(uuid)) {
    // fusion par UUID
    globalId = uuidIndex.get(uuid)
    idRemapper.map(sourceIdx=i, localId=packet.id, globalId)
    // drop du packet (déjà émis par la 1re source)
    return
}

// heuristique
candidates = entities.values().filter(e ->
    e.type == packet.type
 && abs(e.lastSeenTick - tickAbs) <= 5
 && distance(e.lastPos, packet.pos) <= 2.0
)

if (candidates.isEmpty()) {
    // nouvelle entité globale
    globalId = nextGlobalId++
    if (uuid != null) uuidIndex.put(uuid, globalId)
    entities.put(globalId, new EntityState(...))
    idRemapper.map(i, packet.id, globalId)
    emit(packet with remapped id)
} else if (candidates.size() == 1) {
    // fusion unique
    globalId = candidates[0].globalId
    idRemapper.map(i, packet.id, globalId)
    // drop du packet
} else {
    // ambiguïté : confiance géographique
    best = candidates.minBy(c -> distanceFromPOV(c, tickAbs, sourceIdx=i))
    globalId = best.globalId
    idRemapper.map(i, packet.id, globalId)
    mergeReport.entitiesAmbiguousMerged++
    // drop du packet
}
```

**Sur `ClientboundRemoveEntitiesPacket`** : remap chaque local id → global id. Émis 1× même si vu par plusieurs sources (via GlobalDeduper sur `(RemoveEntities, tickAbs, sortedGlobalIds.hash)`).

**Sur packets de mouvement** (`Move*`, `Teleport*`, `SetEntityData`, `EntityEvent`, `SetEntityMotion`) : si deux sources décrivent la même entité globale au même tick avec des valeurs divergentes, confiance géographique — la source dont le POV est le plus proche de `lastPos` gagne, les autres sont droppées.

**Fenêtre glissante** : toutes les 100 ticks, purger les `EntityState` dont `lastSeenTick < currentTick - 200` (10 secondes). Libère la mémoire. Leur `uuid` reste dans `uuidIndex` (permanent).

**Note Phase 4** : la fenêtre 200 ticks peut être trop petite pour des entités statiques (frame). Ajuster si nécessaire. Pour Phase 3, on vise la validation sur 2 POV, 200 ticks suffit.

### 5.3 IdRemapper

```
class IdRemapper {
    private Map<Long, Integer> mapping = new HashMap<>(); // key = (sourceIdx << 32) | localId
    private int nextGlobalId = 0; // réservé : 0 exclus (conv. MC), commence à... on regarde MC conv.

    int map(int sourceIdx, int localId, int globalId) { ... }
    int remap(int sourceIdx, int localId) { ... }
    boolean contains(int sourceIdx, int localId) { ... }
}
```

Tout packet ENTITY transite par `IdRemapper.remap()` avant émission. Le remapping dans le payload utilise les codecs Minecraft (décode → modifie → ré-encode), pas de manipulation binaire manuelle.

**Collision** : `nextGlobalId` commence à un seuil haut (ex. 100_000_000) pour éviter toute collision avec les IDs locaux qui traînent dans les snapshots non-remappés (sécurité défensive).

### 5.4 EgoRouter

**État** : `Map<UUID, SegmentWriter>`.

**Init** : au premier packet `EGO_<uuid>` d'un joueur, créer un `SegmentWriter` pointant vers `ego/<uuid>.flashback`. Registry initiale : copie de celle du segment courant de la source.

**Sur un packet `EGO_<uuid>`** :
- Si besoin, intercaler des `NextTick` pour rattraper `tickAbs` du writer.
- Écrire l'action (live stream, pas snapshot) dans le `SegmentWriter` du joueur.

**Fin** : `finish()` sur chaque `SegmentWriter`, flush dans le ZIP.

### 5.5 GlobalDeduper

```
class GlobalDeduper {
    private Set<Long> seen = new HashSet<>();

    boolean shouldEmit(int packetTypeId, int tickAbs, byte[] payload) {
        long key = hash(packetTypeId, tickAbs, xxhash64(payload));
        return seen.add(key);
    }
}
```

Collision 64-bit improbable. En cas de collision théorique → un packet drop à tort (acceptable pour des sons / particules).

**Fenêtre glissante** : purge des clés dont `tickAbs < currentTick - 200` toutes les 100 ticks.

### 5.6 CacheRemapper

**Pré-merge** (avant streaming) : scan de tous les `level_chunk_caches/` des sources, concat des fichiers dans le dossier de sortie en renumérotant :
```
source_A/level_chunk_caches/0, 1, 2 → merged/level_chunk_caches/0, 1, 2
source_B/level_chunk_caches/0, 1    → merged/level_chunk_caches/3, 4
...
```
Mapping construit : `Map<(sourceIdx, localIdx), globalIdx>`.

**Stream** : chaque `CacheChunkRef(localIdx)` d'une source → réécrit en `CacheChunkRef(globalIdx)` via le mapping.

Pas de dédup au niveau contenu du cache (évite de devoir décompresser et comparer chunks).

---

## 6. Règle du local player (LOCAL_PLAYER)

**Au pré-merge**, on détermine `primarySourceIdx = argmax(totalTicks)`.

**Au streaming** :
- Packet `CreateLocalPlayer` de la source `primarySourceIdx` → émis tel quel en snapshot.
- Packet `CreateLocalPlayer` d'une autre source j → transformé en :
  1. `ClientboundPlayerInfoUpdatePacket` avec action `ADD_PLAYER` (en 1.21, le packet `AddPlayer` a été retiré et remplacé par `PlayerInfoUpdate` + `AddEntity`).
  2. `ClientboundAddEntityPacket` (entité de type `EntityType.PLAYER`) avec l'UUID du POV enregistreur.
  L'entité porte le même UUID que le POV enregistreur, pour que le dedup entité le reconnaisse si le joueur est par la suite observé par le POV principal.

**Conséquence visuelle** : quand on ouvre `merged.flashback` dans Flashback, le POV principal est le "local player" par défaut (spectaté au démarrage). Les autres POV sont des entités joueur classiques, visibles et se déplaçant selon leurs `MoveEntity*` originaux (via `EntityMerger`). La caméra libre permet de spectate n'importe lequel.

---

## 7. MergeOrchestrator + UX

### 7.1 Commande `/mv merge`

Via `ClientCommandRegistrationEvent` (même pattern que `/mv inspect`) :
```
/mv merge <A> <B> [<C>...] -o <name> [--offset-<label>=<N>]
```
Arguments :
- `<A> <B> ...` : noms de dossiers dans `run/replay/`, complétion Brigadier.
- `-o <name>` : nom du dossier de sortie.
- `--offset-<label>=<N>` : offset en ticks à ajouter à la source `<label>` (optionnel, répétable).

Exécution **async** sur un `ExecutorService.newSingleThreadExecutor()` dédié (ne bloque pas le tick client). Progression affichée dans le chat toutes les ~500 ms :
```
[MultiView] Merging 'A' + 'B' → 'merged'...
[MultiView] Phase 1/6: aligning timelines... done (offsets: A=0, B=+94)
[MultiView] Phase 2/6: remapping caches... done (5 caches concat)
[MultiView] Phase 3/6: streaming merge... 34% (tick 62134 / 180000)
...
[MultiView] Merge complete. 2 joueurs, 5 caches, 12 entités ambiguës. Ouvre run/replay/merged dans Flashback.
```

### 7.2 MergeReport

Sérialisé en `run/replay/<merged>/merge-report.json` :
```json
{
  "version": "0.1.0",
  "sources": [
    {"folder": "2026-02-20T23_25_15", "uuid": "2b54...", "totalTicks": 184238, "tickOffset": 0},
    {"folder": "Sénat_empirenapo...", "uuid": "b778...", "totalTicks": 170068, "tickOffset": 94}
  ],
  "mergedTotalTicks": 184332,
  "alignmentStrategy": "setTimePacket",
  "stats": {
    "entitiesMergedByUuid": 42,
    "entitiesMergedByHeuristic": 1411,
    "entitiesAmbiguousMerged": 12,
    "blocksLwwConflicts": 8,
    "blocksLwwOverwrites": 234,
    "globalPacketsDeduped": 234567,
    "egoTracks": ["ab12-...", "cd34-..."],
    "chunkCachesConcatenated": 5,
    "passthroughPackets": {"ClientboundSetCameraPacket": 12}
  },
  "warnings": ["..."],
  "errors": []
}
```

### 7.3 Erreurs

| Situation | Comportement |
|---|---|
| Version MC différente entre sources (`data_version` mismatch) | ABORT avec message clair |
| Monde différent (`world_name` mismatch) | Warning, continuer (cas valide : 2 serveurs techniques du même event) |
| Source corrompue (magic invalide, EOF prématuré) | ABORT, rollback |
| `metadata.name` non parseable ET pas de `SetTime` ET pas d'override CLI | ABORT, message indique le flag `--offset-<label>` |
| Packet type `PASSTHROUGH` inconnu | Warn 1× par type dans `MergeReport`, packet conservé |
| Dossier de sortie existe déjà | ABORT, sauf `--force` |
| OOM (fenêtre entités trop grande) | ABORT, suggère `--window-ticks=<smaller>` (Phase 4 : pour l'instant la fenêtre est hardcodée à 200) |

**Atomicité** : merge écrit dans `run/replay/.<name>.tmp/`, rename atomique en `run/replay/<name>/` à la fin. Erreur → `.tmp/` supprimé.

---

## 8. Tests

### 8.1 Unitaires (pas de dépendance MC runtime)

- `TimelineAlignerTest` : alignement via `SetTime` synthétique, fallback `metadata.name`, override manuel, cascade complète, cas dégénérés.
- `WorldStateMergerTest` : scénarios LWW (2 sources, 3 sources, même tick, tick croissant, dimension croisée).
- `EntityMergerTest` : dedup UUID, dedup heuristique (in/out fenêtre temporelle, in/out fenêtre distance), ambiguïté avec confiance géographique, purge fenêtre glissante.
- `IdRemapperTest` : bijection stable, pas de collision, `contains` cohérent.
- `GlobalDeduperTest` : même packet 2× → émis 1×, purge.
- `CacheRemapperTest` : 3 sources → indices remappés, mapping cohérent.
- `PacketClassifierTest` : dispatch de chaque catégorie (stubs, pas de MC runtime).

### 8.2 Intégration conditionnelle (`@EnabledIf("replaysAvailable")`)

- `MergeIntegrationTest.mergeTwoRealPovs` : merge les 2 POV HIKA → vérifie `merged.flashback` structure (ZIP valide, metadata cohérente, tracks égo présents). Histogramme `/mv inspect` sur merged vs somme des sources (dedup près).
- `MergeRoundTripTest` : merge 1 source avec elle-même dupliquée → résultat ≈ original (dedup quasi-complète).

### 8.3 Manuel

Checklist avant de taguer `phase-3-complete` :
- [ ] `/mv merge <A> <B> -o merged_test` termine sans erreur
- [ ] `merge-report.json` lisible, stats cohérentes
- [ ] Ouverture de `merged_test` dans Flashback : pas de crash
- [ ] 2 joueurs visibles dans le replay, déplacements cohérents
- [ ] Chunks des deux zones chargés
- [ ] `/mv inspect merged_test` affiche un histogramme ≈ somme des sources

---

## 9. Modifications au SPEC.md

À appliquer au moment de l'écriture du design :

- **§3.3.2** : remplacer la règle "En cas d'ambiguïté non résolue → on garde les deux" par "En cas d'ambiguïté → fusion forcée par confiance géographique (l'observation dont le POV enregistreur est le plus proche gagne)". Raison : priorité à la cohérence visuelle (pas de fantômes doublés) vs zéro-perte stricte.
- **§5 Phase 3** : reformulée pour refléter le scope "merge zéro-perte format", critère d'acceptation inchangé.
- **§5 Phase 3.5** (nouvelle) : "Mixin playback pour consommer les tracks égo. Reverse du playback Flashback, injection Mixin rejouant `ego/<uuid>.flashback` lors du spectate du joueur <uuid>."
- **§4.1** : ajuster le package à `fr.zeffut.multiview.merge/` et lister les nouveaux composants.

---

**Fin du document.**
