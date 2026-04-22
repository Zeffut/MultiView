# Changelog

Toutes les modifications notables apparaissent ici. Format basé sur [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/).

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
