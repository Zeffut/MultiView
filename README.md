# MultiView

Addon Fabric pour [Flashback](https://modrinth.com/mod/flashback). Fusionne plusieurs fichiers `.flashback` enregistrés par différents joueurs d'une même session Minecraft en **un seul replay unifié** qui contient l'union de toutes les informations observées.

**Statut : Phase 0 — setup. Pas encore fonctionnel.**

## Stack
- Java 21 (Temurin)
- Gradle 9.4.1 (wrapper) + Fabric Loom 1.16.1
- Minecraft 1.21.11
- Flashback 0.39.4 : consommé en `modCompileOnly` depuis `libs/` (non redistribué — licence restrictive)

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
2. Place-le dans `libs/` (build-time) ET dans `run/mods/` (runtime)
3. Les deux dossiers sont dans `.gitignore` — jamais committer le jar

## Specification

La spec complète du projet est dans [`SPEC.md`](SPEC.md).

## Licence

MIT (à confirmer en Phase 6).
