# Changelog

Toutes les modifications notables apparaissent ici. Format basé sur [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/).

## [0.2.0] — 2026-04-22

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
