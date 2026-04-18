# MultiView — Phase 0 : Setup Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Initialiser un mod Fabric MultiView vide qui (1) se charge dans Minecraft et logge son nom, (2) build un jar propre, (3) a la structure, le tooling de test et la CI prêts pour les phases suivantes.

**Architecture:** Mod Fabric standalone écrit en Java 21, conçu comme addon totalement indépendant de Flashback (pas de fork, pas de code Moulberry redistribué). Flashback sera déclaré en `modCompileOnly` plus tard (Task 9) pour qu'on puisse compiler contre son API sans le redistribuer. Cible MC : la **plus ancienne version supportée par Flashback** (à vérifier en Task 1) pour maximiser la compat jusqu'à 1.21.11. Structure de packages : `fr.zeffut.multiview`.

**Tech Stack:** Java 21 (Temurin), Gradle 8.x, Fabric Loom, Fabric API, JUnit 5, GitHub Actions. Pas encore de Mixin, pas encore de logique métier.

---

## File Structure (livrable de cette phase)

```
MultiView/
├── .github/workflows/build.yml          # CI (Task 12)
├── .gitignore                           # Task 2
├── README.md                            # Task 13
├── SPEC.md                              # existe déjà — mis à jour en Task 1 & 13
├── build.gradle                         # Task 3
├── gradle.properties                    # Task 3
├── settings.gradle                      # Task 3
├── gradle/wrapper/gradle-wrapper.properties  # Task 4
├── gradle/wrapper/gradle-wrapper.jar    # Task 4
├── gradlew                              # Task 4
├── gradlew.bat                          # Task 4
├── docs/superpowers/plans/              # contient ce plan
└── src/
    ├── main/
    │   ├── java/fr/zeffut/multiview/
    │   │   └── MultiViewMod.java        # Task 6 — ModInitializer
    │   └── resources/
    │       ├── fabric.mod.json          # Task 5
    │       └── multiview.mixins.json    # Task 5 (config vide pour plus tard)
    └── test/
        └── java/fr/zeffut/multiview/
            └── SmokeTest.java           # Task 11 — test trivial
```

**Responsabilités par fichier :**
- `build.gradle` / `gradle.properties` / `settings.gradle` : configuration Loom + versions + dépendances
- `fabric.mod.json` : manifeste Fabric (id, entrypoints, dépendances runtime)
- `multiview.mixins.json` : config Mixin vide (placeholder pour Phase 5)
- `MultiViewMod.java` : point d'entrée `ModInitializer`, logue au démarrage
- `SmokeTest.java` : vérifie que JUnit 5 tourne et que la classe mod est instanciable
- `.github/workflows/build.yml` : CI qui lance `./gradlew build` à chaque push
- `README.md` : description + statut + lien vers SPEC.md

---

## Task 1 : Vérifier la matrice de versions Flashback et trancher la cible MC

**Files:**
- Modify: `SPEC.md` (section 2.4 — remplacer "1.21.4 pour commencer" par la version décidée)

- [ ] **Step 1 : Récupérer la liste des versions Flashback sur Modrinth**

Utiliser WebFetch sur `https://modrinth.com/mod/flashback/versions` avec le prompt suivant :
> "Liste toutes les versions du mod Flashback avec pour chacune : numéro de version, versions Minecraft supportées, loader. Identifie la version Flashback la plus ancienne qui cible MC 1.21 ou 1.21.1, et la plus récente qui cible une 1.21.x."

Noter :
- Version Flashback cible = la plus ancienne qui fonctionne sur MC 1.21 ou 1.21.1
- Version MC cible = celle qu'elle déclare
- Vérifier que la même source Flashback (ou une plus récente compatible binaire) supporte aussi 1.21.11

- [ ] **Step 2 : Vérifier la dispo Fabric Loom pour cette MC**

WebFetch sur `https://fabricmc.net/develop/` :
> "Pour Minecraft [version décidée au step 1], donne-moi : la version recommandée de Fabric Loader, de Fabric API, de Yarn mappings, et de Fabric Loom."

Noter les quatre versions.

- [ ] **Step 3 : Documenter la décision dans SPEC.md**

Ouvrir `SPEC.md`. Dans la section **2.4 Stack technique fixée**, remplacer la ligne :
> `**Minecraft** : 1.21.4 pour commencer (aligné avec la version la plus stable de Flashback au moment de la rédaction)`

Par :
```
**Minecraft** : <version décidée> (plus ancienne supportée par Flashback — maximise la compat jusqu'à 1.21.11)
**Flashback cible (modCompileOnly)** : <version Flashback décidée>
**Fabric Loader** : <version>
**Fabric API** : <version>
**Yarn mappings** : <version>
**Fabric Loom** : <version>
```

- [ ] **Step 4 : Commit**

```bash
git init   # si pas encore fait — voir Task 2
git add SPEC.md
git commit -m "docs: lock Minecraft and Flashback target versions"
```

Note : si `git init` n'a pas encore tourné, reporter le commit après la Task 2.

---

## Task 2 : Git init + .gitignore

**Files:**
- Create: `.gitignore`

- [ ] **Step 1 : Initialiser le repo git**

```bash
cd /Users/zeffut/Desktop/Projets/MultiView
git init -b main
```

- [ ] **Step 2 : Créer `.gitignore`**

Contenu exact à écrire :
```gitignore
# Gradle
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
out/

# IntelliJ
.idea/
*.iml
*.iws
*.ipr

# VS Code
.vscode/

# Eclipse
.classpath
.project
.settings/
bin/

# Loom
.loom-cache/
run/

# OS
.DS_Store
Thumbs.db

# Logs
*.log

# Local secrets
local.properties
*.env
```

- [ ] **Step 3 : Premier commit**

```bash
git add .gitignore SPEC.md docs/
git commit -m "chore: initial repo setup with gitignore and spec"
```

---

## Task 3 : Squelette Gradle (build.gradle, settings.gradle, gradle.properties)

**Files:**
- Create: `settings.gradle`
- Create: `gradle.properties`
- Create: `build.gradle`

Pré-requis : les versions de la Task 1 sont décidées. Dans les snippets ci-dessous, remplacer les `<…>` par les valeurs réelles. Pour illustrer : si Task 1 conclut MC 1.21.1 + Loader 0.16.10 + API 0.116.4+1.21.1 + Yarn 1.21.1+build.3 + Loom 1.7-SNAPSHOT, c'est ces valeurs qu'on met.

- [ ] **Step 1 : Écrire `settings.gradle`**

```groovy
pluginManagement {
    repositories {
        maven { url = 'https://maven.fabricmc.net/' }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = 'multiview'
```

- [ ] **Step 2 : Écrire `gradle.properties`**

```properties
# Done to increase the memory available to gradle.
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true

# Fabric Properties — valeurs décidées en Task 1
minecraft_version=<MC>
yarn_mappings=<yarn>
loader_version=<loader>
fabric_version=<api>
loom_version=<loom>

# Flashback (modCompileOnly — ajouté en Task 9)
flashback_version=<flashback>

# Mod Properties
mod_version=0.1.0-SNAPSHOT
maven_group=fr.zeffut
archives_base_name=multiview
```

- [ ] **Step 3 : Écrire `build.gradle`**

```groovy
plugins {
    id 'fabric-loom' version "${loom_version}"
    id 'maven-publish'
}

version = project.mod_version
group = project.maven_group

base {
    archivesName = project.archives_base_name
}

repositories {
    // Flashback Maven — à confirmer/ajuster en Task 9
    // maven { url = 'https://maven.moulberry.com/' }
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings "net.fabricmc:yarn:${project.yarn_mappings}:v2"
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"

    // Flashback : ajouté en Task 9 une fois le coordonné Maven confirmé
    // modCompileOnly "com.moulberry:flashback:${project.flashback_version}"

    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

processResources {
    inputs.property "version", project.version
    filesMatching("fabric.mod.json") {
        expand "version": inputs.properties.version
    }
}

tasks.withType(JavaCompile).configureEach {
    it.options.release = 21
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

test {
    useJUnitPlatform()
}

jar {
    from("LICENSE") {
        rename { "${it}_${project.archives_base_name}" }
    }
}
```

- [ ] **Step 4 : Commit**

```bash
git add build.gradle settings.gradle gradle.properties
git commit -m "build: add initial gradle configuration"
```

---

## Task 4 : Gradle Wrapper

**Files:**
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`

- [ ] **Step 1 : Vérifier qu'un Gradle 8.x est dispo localement**

Run: `gradle --version`
Expected: Gradle 8.x (n'importe quel .x suffit). Si absent :
- macOS : `brew install gradle`
- Sinon télécharger depuis `https://gradle.org/releases/`

- [ ] **Step 2 : Générer le wrapper**

Run: `gradle wrapper --gradle-version 8.10`
Expected: les 4 fichiers apparaissent sous `gradle/wrapper/` + `gradlew` et `gradlew.bat` à la racine.

- [ ] **Step 3 : Vérifier que le wrapper fonctionne**

Run: `./gradlew --version`
Expected: Affichage de la version Gradle 8.10, sans erreur.

- [ ] **Step 4 : Commit**

```bash
git add gradlew gradlew.bat gradle/
git commit -m "build: add gradle wrapper 8.10"
```

---

## Task 5 : Manifeste Fabric (`fabric.mod.json`) + config Mixin vide

**Files:**
- Create: `src/main/resources/fabric.mod.json`
- Create: `src/main/resources/multiview.mixins.json`

- [ ] **Step 1 : Créer l'arborescence**

```bash
mkdir -p src/main/resources
mkdir -p src/main/java/fr/zeffut/multiview
mkdir -p src/test/java/fr/zeffut/multiview
```

- [ ] **Step 2 : Écrire `src/main/resources/fabric.mod.json`**

```json
{
  "schemaVersion": 1,
  "id": "multiview",
  "version": "${version}",
  "name": "MultiView",
  "description": "Fusionne plusieurs replays Flashback d'une même session en un seul replay unifié.",
  "authors": [
    "zeffut"
  ],
  "contact": {
    "homepage": "https://github.com/zeffut/MultiView",
    "sources": "https://github.com/zeffut/MultiView"
  },
  "license": "MIT",
  "environment": "client",
  "entrypoints": {
    "client": [
      "fr.zeffut.multiview.MultiViewMod"
    ]
  },
  "mixins": [
    "multiview.mixins.json"
  ],
  "depends": {
    "fabricloader": ">=0.15.0",
    "fabric-api": "*",
    "minecraft": "~1.21",
    "java": ">=21"
  },
  "suggests": {
    "flashback": "*"
  }
}
```

Note sur les versions de `depends` : ajuster si Task 1 a décidé une MC différente. `~1.21` couvre 1.21.x par défaut.

- [ ] **Step 3 : Écrire `src/main/resources/multiview.mixins.json`**

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "fr.zeffut.multiview.mixin",
  "compatibilityLevel": "JAVA_21",
  "client": [],
  "injectors": {
    "defaultRequire": 1
  }
}
```

- [ ] **Step 4 : Commit**

```bash
git add src/main/resources/
git commit -m "feat: add fabric mod manifest and empty mixin config"
```

---

## Task 6 : Entrypoint `MultiViewMod.java`

**Files:**
- Create: `src/main/java/fr/zeffut/multiview/MultiViewMod.java`

- [ ] **Step 1 : Écrire le fichier**

```java
package fr.zeffut.multiview;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MultiViewMod implements ClientModInitializer {
    public static final String MOD_ID = "multiview";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("MultiView loaded — addon pour Flashback, merge de replays multi-joueurs.");
    }
}
```

- [ ] **Step 2 : Commit**

```bash
git add src/main/java/fr/zeffut/multiview/MultiViewMod.java
git commit -m "feat: add client entrypoint with startup log"
```

---

## Task 7 : Build doit passer

**Files:** aucun — c'est une validation.

- [ ] **Step 1 : Builder**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. Un jar est produit dans `build/libs/multiview-0.1.0-SNAPSHOT.jar`.

Si échec :
- Vérifier les versions dans `gradle.properties` contre les valeurs collectées en Task 1
- Vérifier que Java 21 est bien utilisé (`./gradlew --version` doit afficher JVM 21)
- Si erreur de download de deps : vérifier connexion réseau + dépôts dans `build.gradle`

- [ ] **Step 2 : Vérifier le contenu du jar**

Run: `unzip -l build/libs/multiview-*.jar`
Expected: contient `fabric.mod.json`, `multiview.mixins.json`, `fr/zeffut/multiview/MultiViewMod.class`.

---

## Task 8 : Lancer en dev + vérifier le log

**Files:** aucun — c'est une validation.

- [ ] **Step 1 : Lancer le client dev**

Run: `./gradlew runClient`
Expected: une instance Minecraft se lance. Dans la console, voir la ligne :
```
[...] [Client thread/INFO] (multiview) MultiView loaded — addon pour Flashback, merge de replays multi-joueurs.
```

- [ ] **Step 2 : Fermer proprement**

Fermer la fenêtre Minecraft. Vérifier qu'aucune exception fatale n'est apparue dans la console.

- [ ] **Step 3 : Commit tags runtime generated si nécessaire**

```bash
git status
```
Si des fichiers générés apparaissent hors `run/` et `.gradle/` (qui sont gitignorés), les examiner avant de commit. Normalement rien à commiter ici.

---

## Task 9 : Ajouter Flashback en `modCompileOnly`

**Files:**
- Modify: `build.gradle` (section `repositories` et `dependencies`)
- Modify: `gradle.properties` (vérifier que `flashback_version` est bien défini)

- [ ] **Step 1 : Identifier le coordonné Maven de Flashback**

Deux cas :
- **Cas A** — Maven public disponible : WebFetch sur `https://modrinth.com/mod/flashback/versions`, trouver dans la description ou la page d'une version le Maven repo officiel. Si présent, noter l'URL du repo et le `group:artifact:version`.
- **Cas B** — Pas de Maven public : le jar doit être téléchargé manuellement depuis Modrinth et consommé en `modCompileOnly files('libs/flashback-X.Y.Z.jar')`. Créer un dossier `libs/` et y placer le jar. **Ne PAS commit le jar** (ajouter `libs/` au `.gitignore`).

- [ ] **Step 2 : Mettre à jour `build.gradle` selon le cas**

**Cas A** — ajouter dans `repositories` :
```groovy
maven {
    url = '<url maven flashback>'
    name = 'Flashback'
}
```
Et décommenter dans `dependencies` :
```groovy
modCompileOnly "com.moulberry:flashback:${project.flashback_version}"
```

**Cas B** — ajouter dans `dependencies` :
```groovy
modCompileOnly files('libs/flashback-' + project.flashback_version + '.jar')
```

Et dans `.gitignore`, ajouter :
```
libs/
```

- [ ] **Step 3 : Re-build**

Run: `./gradlew build --refresh-dependencies`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4 : Commit**

```bash
git add build.gradle gradle.properties .gitignore
git commit -m "build: add flashback as modCompileOnly dependency"
```

---

## Task 10 : Flashback installé en runtime dev (pour tester l'intégration plus tard)

**Files:** aucun — c'est du setup local.

- [ ] **Step 1 : Télécharger le jar Flashback correspondant à la MC cible**

Depuis Modrinth, télécharger le jar qui correspond exactement à la `minecraft_version` de Task 1.

- [ ] **Step 2 : Le placer dans `run/mods/`**

```bash
mkdir -p run/mods
cp ~/Downloads/flashback-<version>.jar run/mods/
```

`run/` est déjà gitignoré, donc aucun risque de redistribuer le jar.

- [ ] **Step 3 : Re-lancer le client dev**

Run: `./gradlew runClient`
Expected:
- Le mod MultiView se charge et logue sa ligne.
- Flashback se charge aussi (apparaît dans le menu mods).
- Aucun conflit.

---

## Task 11 : Test smoke JUnit 5

**Files:**
- Create: `src/test/java/fr/zeffut/multiview/SmokeTest.java`

- [ ] **Step 1 : Écrire le test qui doit passer**

```java
package fr.zeffut.multiview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SmokeTest {

    @Test
    void modIdIsCorrect() {
        assertEquals("multiview", MultiViewMod.MOD_ID);
    }

    @Test
    void loggerIsInitialized() {
        assertNotNull(MultiViewMod.LOGGER);
    }
}
```

- [ ] **Step 2 : Lancer les tests — ils doivent passer**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, 2 tests exécutés, 0 échec. Rapport HTML sous `build/reports/tests/test/index.html`.

- [ ] **Step 3 : Sanity check — casser volontairement puis réparer**

Temporairement, dans `SmokeTest.java`, remplacer `"multiview"` par `"broken"`. Rerun : doit échouer. Restaurer `"multiview"`. Rerun : doit repasser. Cette étape prouve que les tests sont vraiment exécutés.

- [ ] **Step 4 : Commit**

```bash
git add src/test/java/fr/zeffut/multiview/SmokeTest.java
git commit -m "test: add smoke test for mod constants"
```

---

## Task 12 : CI GitHub Actions

**Files:**
- Create: `.github/workflows/build.yml`

- [ ] **Step 1 : Écrire le workflow**

```yaml
name: build

on:
  push:
    branches: [main, dev]
  pull_request:
    branches: [main, dev]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'

      - name: Grant execute permission to gradlew
        run: chmod +x ./gradlew

      - name: Build
        run: ./gradlew build --no-daemon

      - name: Test
        run: ./gradlew test --no-daemon

      - name: Upload build artifacts
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: multiview-jar
          path: build/libs/*.jar
```

- [ ] **Step 2 : Commit**

```bash
git add .github/workflows/build.yml
git commit -m "ci: add github actions workflow for build and test"
```

**Note :** La CI ne tournera vraiment qu'une fois le repo poussé sur GitHub. Ne pas bloquer la Phase 0 sur la création du repo distant — c'est à la discrétion du user.

---

## Task 13 : README minimal + mise à jour SPEC.md

**Files:**
- Create: `README.md`
- Modify: `SPEC.md` (section 10 — journal de bord)

- [ ] **Step 1 : Écrire `README.md`**

```markdown
# MultiView

Addon Fabric pour [Flashback](https://modrinth.com/mod/flashback). Fusionne plusieurs fichiers `.flashback` enregistrés par différents joueurs d'une même session Minecraft en **un seul replay unifié** qui contient l'union de toutes les informations observées.

**Statut : Phase 0 — setup. Pas encore fonctionnel.**

## Stack
- Java 21 (Temurin)
- Fabric Loom
- Minecraft : voir `gradle.properties`
- Flashback : consommé en `modCompileOnly`, non redistribué

## Build

```bash
./gradlew build
```

## Dev client

```bash
./gradlew runClient
```

## Specification

La spec complète du projet est dans [`SPEC.md`](SPEC.md).

## Licence

MIT (à confirmer en Phase 6).
```

- [ ] **Step 2 : Mettre à jour la section 10 de `SPEC.md`**

Remplacer la section 10 par :

```markdown
## 10. Notes et journal de bord

> Claude Code : tiens à jour cette section au fur et à mesure du projet. Notes d'implémentation, décisions d'archi, obstacles rencontrés, solutions adoptées.

### 2026-04-19 — Découverte initiale
- Le format `.flashback` est un ZIP, confirmé via docs ServerReplay.
- Licence Flashback = restrictive, donc addon strictement séparé, aucun code de Flashback redistribué.

### 2026-04-19 — Phase 0 terminée
- Nom du mod finalisé : **MultiView** (mod_id : `multiview`, package : `fr.zeffut.multiview`).
- Versions verrouillées : cf. section 2.4.
- Scaffolding Fabric posé : `fabric.mod.json`, `multiview.mixins.json` (config vide, placeholder Phase 5), entrypoint `MultiViewMod` qui logue au démarrage.
- Tests JUnit 5 + CI GitHub Actions en place.
- Flashback installé en `modCompileOnly` + en `run/mods/` pour dev — jamais redistribué.
```

- [ ] **Step 3 : Commit final de la Phase 0**

```bash
git add README.md SPEC.md
git commit -m "docs: add README and update journal for phase 0 completion"
```

- [ ] **Step 4 : Tag la fin de la phase**

```bash
git tag phase-0-complete
```

---

## Critère d'acceptation de la Phase 0

Tous les points suivants doivent être vrais avant de considérer la phase terminée :

1. `./gradlew build` passe sans warning bloquant
2. `./gradlew test` passe (2 tests smoke verts)
3. `./gradlew runClient` lance Minecraft, le mod MultiView se charge, la ligne de log apparaît, Flashback est aussi chargé, aucun crash
4. Le repo git contient au moins les commits listés dans les Tasks 2, 3, 4, 5, 6, 9, 11, 12, 13
5. La CI GitHub Actions est prête à tourner une fois le repo poussé
6. `SPEC.md` section 2.4 liste les versions exactes (MC, Flashback, Loader, API, Yarn, Loom)
7. Aucun jar Flashback n'est committé dans le repo (vérifier `git ls-files | grep -i flashback`)

---

## Self-review (faite par l'auteur du plan)

- **Couverture de la Phase 0 (SPEC.md §5 Phase 0)** : ✅ init projet (3,4,5,6), git init (2), hello-world (6,8), Flashback modCompileOnly (9), runtime dev (10), CI (12), + ajouts utiles (tests smoke, README, journal).
- **Placeholders** : les versions `<MC>`, `<yarn>`, etc. sont explicitement décidées en Task 1 avec commandes et critères précis — ce n'est pas un TODO, c'est une tâche concrète.
- **Cohérence des noms** : `multiview` (mod_id), `fr.zeffut.multiview` (package), `MultiViewMod` (classe) — cohérents dans fabric.mod.json, mixins.json, build.gradle, Java.
- **Blind spot assumé** : le coordonné Maven exact de Flashback n'est pas garanti public. La Task 9 prévoit explicitement le plan B (jar local + `libs/` gitignoré). Pas de faux espoir.
- **Frequent commits** : 1 commit par tâche logique, 10+ commits sur la phase. ✅
