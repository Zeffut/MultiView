# MultiView

Addon Fabric pour [Flashback](https://modrinth.com/mod/flashback) qui fusionne plusieurs replays `.flashback` enregistrés par différents joueurs d'une même session Minecraft en **un seul replay unifié** — "observateur omniscient" qui contient l'union des chunks, entités et événements observés par au moins un des POV.

Version **0.3.0** — dedup massif cross-source (PlayerInfoUpdate, SystemChat par contenu, MoveEntity), multi-world dim per source, outil de test autonome `TestHarness`. Voir `CHANGELOG.md`.

## Public cible

- **Créateurs de contenu** : events PvP, SMP multi-joueurs, tournois, machinimas.
- **Staffs de serveurs** : review anti-cheat multi-angles.
- **Cinematographers** : caméra libre dans une scène multi-joueurs sans perte d'info.

## Utilisation

1. Chaque joueur de la session enregistre son POV avec Flashback pendant la même période.
2. Récupère tous les `.zip` produits et place-les dans ton propre `<gameDir>/flashback/replays/`.
3. Ouvre Minecraft avec **Flashback + MultiView** installés.
4. Clique sur l'icône caméra (menu titre) → **Select Replay**.
5. Coche (case à droite de chaque ligne) les replays que tu veux fusionner — minimum 2.
6. Clique **Merge N Replays** (en haut à droite).
7. Attend la fin (écran de progression avec barre + texte).
8. Le replay fusionné `merged_<timestamp>.zip` apparaît automatiquement dans la liste.
9. Ouvre-le comme un replay normal → caméra libre pour explorer, Spectate Player pour suivre un enregistreur particulier.

## Ce qui marche (0.1.0)

- Fusion de **N replays** (validation visuelle à 2 POV propre, 4 POV avec quelques imperfections).
- **Alignement temporel automatique** au tick près via `ClientboundSetTimePacket` (avec fallback sur `metadata.name`).
- **Chunks de toutes les zones** présents dans le replay fusionné (primary + secondaires concaténés).
- **Markers de tous les POV** agrégés sur la timeline du replay fusionné (ex. "Changed Dimension").
- **Player list complète** dans Spectate (dedup des adds / removes entre sources).
- **UI intégrée** dans le Select Replay screen de Flashback — pas de commande à taper.
- **Rollback atomique** si le merge échoue (pas de fichier partiel).
- **i18n** : français + anglais.

## Limitations connues

- **POV secondaires visibles seulement quand le primary les regarde** : Flashback ne supporte qu'un seul local player. Le POV qui commence en premier devient le "primary" (caméra), les autres sont visibles comme entités joueur classiques.
- **Fusion à 4+ POV** : certains chunks peuvent apparaître en patchwork quand plusieurs POV voient la même zone avec des versions conflictuelles.
- **Pas encore de LWW sur blocs** ni de dedup d'entités — peut provoquer quelques flickers ou doublons visuels.
- Pour les détails techniques, voir [`CHANGELOG.md`](CHANGELOG.md) et [`SPEC.md`](SPEC.md).

## Prérequis

- Minecraft **1.21.11**
- Fabric Loader **0.19.2+**
- Fabric API **0.141.3+1.21.11**
- Flashback **0.39.4**

## Build (pour les développeurs)

```bash
./gradlew build
```

Le jar produit est dans `build/libs/`.

## Dev client

```bash
./gradlew runClient
```

Pour développer localement avec Flashback :

1. Télécharge `Flashback-0.39.4-for-MC1.21.11.jar` depuis [Modrinth](https://modrinth.com/mod/flashback).
2. Place-le dans `libs/` (compile-time) et `run/mods/` (runtime).
3. Les deux dossiers sont gitignored — **jamais committer le jar**.
4. **Patch lattice** : la lib `lattice` bundlée dans Flashback 0.39.4 a un bug de mixin sur Yarn 1.21.11+build.4 qui crash l'UI. Procédure de patch dev-only dans [`SPEC.md`](SPEC.md) §10.

## Documentation

- [`CHANGELOG.md`](CHANGELOG.md) — versions et changements.
- [`SPEC.md`](SPEC.md) — spec complète, journal de développement, dette technique.
- [`docs/superpowers/specs/`](docs/superpowers/specs/) — design docs par phase.
- [`docs/superpowers/plans/`](docs/superpowers/plans/) — plans d'implémentation par phase.

## Contribuer

Les issues + PRs sont bienvenus sur [GitHub](https://github.com/Zeffut/MultiView/issues).

## Licence

[MIT](LICENSE) — Zeffut, 2026.

*Flashback reste sous sa licence Moulberry restrictive. MultiView est un addon indépendant qui ne redistribue aucun code de Flashback.*
