# MultiView

Addon Fabric pour [Flashback](https://modrinth.com/mod/flashback). Fusionne plusieurs replays `.flashback` enregistrés par différents joueurs d'une même session Minecraft en **un seul replay unifié** — "observateur omniscient" qui contient l'union des chunks, entités et événements observés par au moins un des POV.

## Statut

Phase 3 complète et validée visuellement. Pipeline de merge bout-en-bout fonctionnel, testé sur 2 POV réels (~2h30 de gameplay multi-joueurs).

**Ce qui marche** :
- Merge de N replays via la commande client `/mv merge <source1> <source2> <output>`
- Alignement temporel précis via `ClientboundSetTimePacket` (fallback metadata.name)
- 2 joueurs enregistreurs visibles simultanément dans le replay mergé
- Chunks des deux zones chargés (remap flat-entry des `level_chunk_caches/`)
- Snapshot c0 initialisé depuis le POV principal (client démarre dans un monde non-vide)
- Output en `.zip` reconnu par Flashback (scanné depuis `<gameDir>/flashback/replays/`)
- Atomic rollback en cas d'erreur

**Ce qui est à faire** (dette Phase 4+) : dedup entités (pas de doublons zombies), LWW sur blocs (pas de flicker), UI custom dans Flashback, polish + release Modrinth.

## Stack

- Java 21 (Temurin)
- Gradle 9.4.1 (wrapper) + Fabric Loom 1.16.1
- Minecraft 1.21.11
- Flashback 0.39.4 : consommé en `modCompileOnly` depuis `libs/` (non redistribué — licence Moulberry restrictive)

## Build

```bash
./gradlew build
```

## Dev client

```bash
./gradlew runClient
```

Pour le dev local avec Flashback :
1. Télécharge `Flashback-0.39.4-for-MC1.21.11.jar` depuis https://modrinth.com/mod/flashback
2. Place-le dans `libs/` (compile-time) et `run/mods/` (runtime)
3. Les deux dossiers sont gitignored — **jamais committer le jar**
4. Note : la lib `lattice` bundlée dans Flashback 0.39.4 a un bug d'apply de mixin sur Yarn 1.21.11+build.4. Procédure de patch dev-only dans [`SPEC.md`](SPEC.md) §10 (suppression de `MixinDropdownWidgetEntry` du jar local — non redistribué).

## Utilisation

1. Place tes replays sources dans `<gameDir>/flashback/replays/` (ils peuvent être en `.zip` ou en dossier)
2. Dans Minecraft, ouvre un monde local (pour activer le chat)
3. Tape `/mv merge "<source1>" "<source2>" <output-name>`
4. Attend le message `[MultiView] Done → /.../<output-name>.zip`
5. Ouvre le fichier `.zip` produit depuis l'écran "Select Replay" de Flashback

## Documentation

- [`SPEC.md`](SPEC.md) : spec complète + journal de développement
- [`docs/superpowers/specs/`](docs/superpowers/specs/) : design docs par phase
- [`docs/superpowers/plans/`](docs/superpowers/plans/) : plans d'implémentation par phase

## Licence

MIT — voir [`LICENSE`](LICENSE).

*Flashback reste sous sa licence Moulberry restrictive. MultiView est un addon indépendant qui ne redistribue aucun code de Flashback.*
