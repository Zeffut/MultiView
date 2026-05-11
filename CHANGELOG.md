# Changelog

Toutes les modifications notables apparaissent ici. Format basé sur [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/).

## [0.3.0] — 2026-05-11

Dedup massif des packets cross-source. Outil de test autonome.

### Dedup au niveau fichier

- **PlayerInfoUpdate** (PIU) : dedup UUID-level via `PlayerListS2CPacket.CODEC.decode()`. Drop tout PIU dont chaque entrée UUID est déjà annoncée. Pre-scan de la snapshot du primary pour pré-enregistrer les UUIDs. Fallback heuristique si Bootstrap MC indisponible (unit tests).
- **SystemChat** : drop secondary unconditionally (clock skew cross-source défait le strict `(pid, tickAbs, content)` dedup de GlobalDeduper). Dedup primary par **string content** extrait via `GameMessageS2CPacket.CODEC.decode().getString()` (catches le "même chat logique, bytes différents" causé par hover/signature timestamps).
- **MoveEntity / TeleportEntity / RotateHead** : dedup cross-source via content-hash après remap entité. Réduit les bursts d'activité physique de 90%+.
- **cN snapshots** : `copyPrimarySnapshotForTick` filtre les PIU(ADD_PLAYER) — MC tab list persiste à travers les segment boundaries, le re-emit créerait un re-fire chat à chaque boundary.

### Multi-world

- `WorldPacketRewriter` accepte une dimension key par source (initialisée depuis `metadata.world_name`). Plus de collisions cross-world dans LWW. Limitation : dim-tracking mid-recording (RESPAWN parsing) toujours non implémenté.

### Outils

- **`TestHarness`** : entrypoint client Fabric, déclenché par `.multiview-test.json` dans gameDir. Auto-merge → auto-open replay → unpause + tickRate 200/s (10× speedup) → capture chat events → écrit `.multiview-test-result.json` (verdict + stats) → quit MC. Permet d'itérer sans intervention utilisateur. Watchdog 20min safety. Voir `src/main/java/fr/zeffut/multiview/test/TestHarness.java`.
- **`MergeInspector --deep`** : breakdown par packetId sur les bursts, top 5 packetIds par segment, busy ticks > 500 actions.

### Fuites mémoire corrigées

- `IdRemapper` : ajout de `removeByGlobalId(Set)` appelé en cascade depuis `EntityMerger.purge`. Le mapping ne croît plus sans bornes sur replays multi-heures.
- `EntityMerger.purge` purge en cascade `states` + `uuidIndex` + `idRemapper.mapping` (depuis 0.2.5 + 0.3).

### Limitations connues

- **Doublons "X joined the game" affichés dans le chat MC** : causés par **Flashback's playback** (broadcast à chaque viewer/fake-player). Le fichier mergé est propre (1 chat par event), c'est l'affichage qui double. Hors scope MultiView.
- **Bursts résiduels** sur événements physiques massifs (combat, explosions) malgré le dedup MoveEntity — non-MoveEntity packets (SET_ENTITY_DATA, ENTITY_EVENT) restent en passthrough.
- **Multi-dimension mid-recording** : tracking par RESPAWN packet non implémenté (limitation à l'initial world).
- **MergeOrchestrator** : 1300+ lignes (refactor architectural reporté).

### Stats (validation harness autonome, 90s de lecture sur 4 POV)

- 312k packets dédupés
- 18k entités fusionnées par UUID
- 0 crash, 0 erreur

[0.3.0]: https://github.com/Zeffut/MultiView/releases/tag/v0.3.0

## [0.2.5] — 2026-05-06

Audit de qualité — durcissement de la sécurité, fiabilité du rollback, instrumentation. Aucun changement du pipeline merge lui-même : la logique de dedup d'entités v0.2.0 est conservée (testée fonctionnelle en runtime). Les issues entity-dedup multi-POV restent comme limitations connues.

### Sécurité

- **Zip-slip** : extraction des sources `.zip` valide désormais que les entrées ne sortent pas du dossier temporaire.
- **Zip-bomb** : limite de 5 GB sur la taille décompressée d'un source `.zip`.
- **`SegmentReader`** : validation `payloadSize`, `registry count`, `id length`, `snapshotSize` — refuse les valeurs négatives ou aberrantes (évite OOM sur segment corrompu).
- **`FlashbackByteBuf`** : `readBytes` / `readString` valident la longueur (négatif ou > readableBytes → throw clair).

### Robustesse

- **Rollback atomique** : le merge écrit dans `.part` puis `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`. Un crash en cours de zipping ne détruit plus l'ancien zip de destination.
- **`SegmentWriter`** implémente `AutoCloseable` ; `streamMerge` le ferme dans un `finally`. Plus de fuite de FileChannel/buffer Netty sur l'erreur.
- **`WorldStateMerger.blockKey`** : record `(dimId, x, y, z)` → bijection garantie (le bit-packing précédent collisionnait silencieusement sur coords arbitraires).
- **NextTick sanity bound** : abort propre si un packet remonte un tick aberrant (évite l'intercalation infinie de NextTicks).
- **Validation `≥ 2 sources`** dès le début de `MergeOrchestrator.run`.
- **`TimelineAligner.varIntLength` / `readGameTime`** : retournent -1 sur payload tronqué au lieu de throw.
- **`FlashbackMetadata.fromJson`** throw `IllegalArgumentException` sur metadata vide/invalide.

### Observabilité

- Migration `java.util.logging` → SLF4J : `MergeOrchestrator`, `EntityPacketRewriter`, `WorldPacketRewriter`, `SecondaryPlayerSynthesizer`, `MergeCommand`. Les warnings remontent dans `latest.log`.
- `MergeCommand` : `t.printStackTrace()` → `LOG.error(...)`.

### Mémoire

- Lecture du snapshot du primary via `FileChannel.map` (au lieu de `Files.readAllBytes`) — évite de charger un segment de plusieurs centaines de Mo en heap.

### Build / packaging

- `fabric.mod.json` : `flashback` passe de `suggests` à `depends ">=0.39.0 <0.40"`.
- `minecraft` resserré à `">=1.21.11 <1.22"` ; `fabric-api ">=0.141.0"`.
- Référence vide à `multiview.mixins.json` retirée (aucune mixin n'est écrite).
- Champ `contact.issues` ajouté.

### Outils

- Nouveau `fr.zeffut.multiview.tools.MergeInspector` — diagnostic standalone qui scanne un `.flashback` mergé et reporte gaps de ticks, bursts par packetId, lifecycle entité, anomalies de segments. Usage : `java -cp ... fr.zeffut.multiview.tools.MergeInspector <merged.zip> [--deep]`.

### Cleanup

- `CacheRemapper` (et son test) supprimé : code mort.
- Tableau dead `secondaryLocalEntityId[]` remplacé par un `idRemapper.contains()` direct.

### Limitations connues (inchangées par cette release)

- **Doublons "joined the game" dans le chat** : les `PlayerInfoUpdate(ADD_PLAYER)` ne sont pas dédupliqués cross-source. MC les ignore poliment (`Ignoring player info update for unknown player`), pas de crash.
- **Bursts d'activité physique** : un événement intense observé par tous les POV (combat, explosion, edits massifs) peut causer un freeze visuel court (~2300 actions/tick). Cause-racine = pas de dedup `MOVE_ENTITY` cross-source — plan pour 0.3.

## [0.2.0] — 2026-05-06

Deuxième version. Correctifs majeurs sur la fusion multi-POV.

### Corrections critiques

- **Chunks manquants fixés** (`801bcf0`) : `PlayerRespawnS2CPacket` et `GameJoinS2CPacket` routés en `EGO` primary-only. Avant, les sources secondaires traînaient le client dans leur dimension, faisant silencieusement dropper les chunks du primary par Flashback.
- **ENTITY dedup réactivé** (`e77bc2d`) : `EntityPacketRewriter` utilise maintenant un `DynamicRegistryManager.ImmutableImpl(List.of(Registries.ENTITY_TYPE))` au runtime. Plus de doublons d'entités.
- **Secondary POV toujours visibles** (`12fdfee`) : `SecondaryPlayerSynthesizer` synthétise `PlayerInfoUpdate(ADD_PLAYER)` + `AddEntity(PLAYER)` pour chaque POV secondaire. Leur position est mise à jour via `accurate_player_position` et `PLAYER_POSITION` retraduits en `TeleportEntity`.
- **WORLD LWW réactivé** (`ae4dabd`) : `WorldPacketRewriter` gère maintenant les `BlockUpdate` et `SectionBlocksUpdate` via `WorldStateMerger` pour arbitrage last-write-wins. Fin des flickers sur blocs cassés/reposés.

### Autres améliorations

- `SET_CHUNK_CACHE_CENTER`, `RADIUS`, `SIMULATION_DISTANCE` classifiés EGO (primary-only).
- Flashback action `accurate_player_position_optional` routée en `LOCAL_PLAYER` (primary-only).
- Markers (`Changed Dimension`, etc.) agrégés depuis toutes les sources dans le replay fusionné.
- Output splits en segments de 6000 ticks avec snapshot riche dans chaque `cN.flashback`.
- `FORGET_LEVEL_CHUNK` et `REMOVE_ENTITIES` filtrés primary-only (plus de flickers de chunks/entités observés par plusieurs POV).
- `LEVEL_CHUNK_WITH_LIGHT` dedupliqué par hash de contenu.

### UI

- Cases à cocher individuelles sur chaque replay (remplace le toggle multi-select).
- Progress bar avec pourcentage + phase text dans le `MergeProgressScreen`.
- Liste de replays auto-refresh après un merge.
- Noms des merges raccourcis : `merged_<timestamp>.zip`.

### Limitations connues restantes

- Fusion à 4+ POV : fonctionnelle avec de légères imperfections visuelles possibles (chunks observés par plusieurs POV avec versions conflictuelles).
- Dimensions multiples : la caméra suit uniquement les changements de dim du primary. Les POV secondaires dans une autre dimension apparaissent à la mauvaise position (pas de tracker de dimension par source).

### Tests : 127 verts (121 Phase 0.1 + 6 nouveaux pour SecondaryPlayerSynthesizer).

[0.2.5]: https://github.com/Zeffut/MultiView/releases/tag/v0.2.5
[0.2.0]: https://github.com/Zeffut/MultiView/releases/tag/v0.2.0

## [0.1.0] — 2026-04-22

Première version publique. Fusion de N replays Flashback en un seul replay unifié.

### Ajouts

- **Commande client** `/mv inspect <replayName>` — affiche metadata et histogramme d'actions d'un replay.
- **Pipeline de merge** (streaming k-way, support N sources natif) :
  - Alignement temporel via `ClientboundSetTimePacket` (fallback `metadata.name`).
  - Découpage output en segments de 6000 ticks (évite la limite array 2 GB de Java).
  - Snapshot riche dans chaque `cN.flashback` (corrige le bug d'écran vide au seeking).
  - Concaténation + remap des `level_chunk_caches/` flat-entry.
  - Aggregation des markers (`Changed Dimension`, etc.) depuis toutes les sources.
  - Rollback atomique en cas d'erreur.
- **UI Flashback** (sans mixin dans Flashback) :
  - Case à cocher sur chaque ligne de replay dans le Select Replay screen.
  - Bouton "Merge N Replays" activé dès 2 replays sélectionnés.
  - Écran de progression avec barre et phase texte.
  - Rafraîchissement automatique de la liste après un merge.
- **Compatibilité** : Flashback 0.39.4, Fabric Loader 0.19.2, Minecraft 1.21.11 (Yarn `1.21.11+build.4`).
- **i18n** : français + anglais.
- **Commande `/mv merge`** (déprecée) — accessible depuis le chat, avertit l'utilisateur d'utiliser le bouton UI.

### Limitations connues (dette technique explicite)

- Fusion à 2 POV : stable et validée visuellement.
- Fusion à 4+ POV : certains chunks peuvent apparaître en patchwork quand plusieurs POV voient la même zone avec des versions conflictuelles (packets `LEVEL_CHUNK_WITH_LIGHT` dedup par hash limite le problème sans le résoudre).
- POV secondaires visibles uniquement quand le primary POV les observe (pas de `AddPlayer` transform — contrainte du `localPlayerId` unique de Flashback).
- LWW sur blocs (WORLD) et dedup entités (ENTITY) en **passthrough** Phase 4.D/E rollback — re-activation prévue en 0.2 avec registry access correct.
- Lattice mixin patch manuel requis en dev (`libs/` jar patché pour retirer `MixinDropdownWidgetEntry`).

### Build

- Java 21, Gradle 9.4.1, Fabric Loom 1.16.1.
- CI-ready : tests JUnit 5 sans dépendance MC runtime (intégration skippable via `@EnabledIf`).

[0.1.0]: https://github.com/Zeffut/MultiView/releases/tag/v0.1.0
