# Flashback Merger — Cahier des charges

> **Pour Claude Code :** ce document est la spec de référence du projet. Lis-le en entier avant d'écrire la moindre ligne de code. Toute décision d'architecture non couverte ici doit être discutée avec l'utilisateur avant implémentation. Mets à jour ce document au fil du projet.

---

## 1. Vision

**Addon Fabric pour le mod [Flashback](https://modrinth.com/mod/flashback)** permettant à l'utilisateur de sélectionner N fichiers `.flashback` enregistrés par différents joueurs d'une même session Minecraft, et de les fusionner en **un seul fichier `.flashback` unifié** qui contient l'union de toutes les informations observées.

Le résultat est un replay qui se comporte comme si un **"observateur omniscient"** avait enregistré la session avec une render distance illimitée : tous les chunks explorés par au moins un joueur sont chargés, tous les joueurs sont présents et bougent selon leurs enregistrements respectifs, toutes les entités et événements observés par au moins un joueur sont là.

Une fois le fichier ouvert dans Flashback, l'utilisateur utilise les fonctionnalités natives de Flashback (caméra libre, spectate d'un joueur) pour naviguer dans la scène sans aucune UI spéciale à créer.

### Nom du projet
Nom de travail : **`flashback-merger`** (à ajuster avant publication).

### Public cible
- Créateurs de contenu : events PvP, SMP multi-joueurs, tournois, machinimas
- Staffs de serveurs : review anti-cheat multi-angles
- Cinematographers : caméra libre dans une scène multi-joueurs sans perte d'info

---

## 2. Contexte technique essentiel

### 2.1 Le mod Flashback
- **Repo** : https://github.com/Moulberry/Flashback
- **Auteur** : Moulberry
- **Licence** : Custom, restrictive. Citation textuelle : *"Copyright 2024 Moulberry. Do not reupload or redistribute. Flashback currently does not accept outside contributions"*.
- **Wiki explorable** : https://deepwiki.com/Moulberry/Flashback (indexation DeepWiki du code source — très utile pour naviguer l'arborescence `src/main/java/com/moulberry/flashback/`)
- **Loader** : Fabric uniquement
- **Versions MC supportées** : 1.21 → 1.21.11 (au moment de la rédaction)
- **Stockage des replays** : dossier `.minecraft/replay/`

### 2.2 Implications légales
- **Ne PAS forker Flashback**, ne PAS copier-coller de code source Moulberry
- **Ne PAS redistribuer** le jar Flashback ou des morceaux de son code avec notre addon
- **Ce qu'on PEUT faire légalement** :
  - Développer un mod Fabric **totalement indépendant** qui lit/écrit le **format de fichier** `.flashback` (un format n'est pas du code)
  - Déclarer Flashback en `modCompileOnly` dans Gradle (compilation contre l'API au runtime, sans redistribution)
  - Utiliser Mixins pour s'injecter dans Flashback au runtime côté utilisateur
  - Lire la doc DeepWiki et les noms de classes publiques pour comprendre les points d'entrée
- **Réflexe de sécurité** : si un doute subsiste, contacter Moulberry sur Discord (https://discord.gg/flashbacktool) avant publication.

### 2.3 Le format `.flashback`
- **Confirmé** : c'est un fichier ZIP (cf. docs ServerReplay)
- **NON documenté publiquement** par Moulberry → reverse-engineering requis
- **Rosetta Stone n°1** : [ServerReplay](https://github.com/senseiwells/ServerReplay) par `senseiwells`
  - Mod open source qui écrit le format Flashback côté serveur
  - Branche à cibler : `1.21.11` (la plus récente)
  - Langage : Kotlin
  - DeepWiki : https://deepwiki.com/senseiwells/ServerReplay
- **Rosetta Stone n°2** : la lib `arcade-replay` de CasualChampionships (dépendance de ServerReplay) : https://github.com/CasualChampionships/Arcade — contient l'implémentation bas niveau de l'encodage Flashback
- **Méthode complémentaire** : décompiler le jar officiel de Flashback avec un outil comme [Vineflower](https://vineflower.org/) ou l'ouvrir dans IntelliJ → analyse strictement **en lecture** pour comprendre le format. Aucun code décompilé ne doit finir dans le projet.

### 2.4 Stack technique fixée
- **Langage** : Java 21
- **Loader** : Fabric (version aligné avec Flashback cible)
- **Minecraft** : 1.21.11 (version cible — le mod est publié pour cette version précise)
- **Flashback cible (modCompileOnly)** : 0.39.4
- **Fabric Loader** : 0.19.2
- **Fabric API** : 0.141.3+1.21.11
- **Yarn mappings** : 1.21.11+build.4
- **Fabric Loom** : 1.16.1
- **Build** : Gradle 8.x + Fabric Loom
- **Dépendances** :
  - `fabric-loader` et `fabric-api`
  - `flashback` en `modCompileOnly` (non redistribué)
  - `junit 5` pour les tests
- **Java runtime** : JDK 21 (Temurin recommandé)

---

## 3. Sémantique de la fusion

### 3.1 Principe fondateur
> **Zéro perte d'information.** Un replay fusionné doit contenir tout ce qui est présent dans au moins un des replays sources. Si une donnée n'existe dans aucun replay source, elle n'existe pas dans le replay fusionné.

### 3.2 Gestion temporelle
- **Fenêtre temporelle du replay fusionné** = `[min(starts), max(ends)]`
- Si un replay couvre `[T1, T2]` et qu'un autre couvre `[T3, T4]` sans chevauchement, le replay fusionné va de `min(T1,T3)` à `max(T2,T4)` — les périodes où aucun replay ne couvre sont des **trous** (pas d'info, donc rien à afficher ou état figé selon le type de données)
- Tous les timestamps internes des packets sont recalés sur une timeline absolue commune
- **Synchronisation** : idéalement basée sur les horodatages d'enregistrement présents dans les métadonnées Flashback. Si absents ou peu fiables, fallback sur un alignement manuel par l'utilisateur dans l'UI.

### 3.3 Règles de résolution des conflits

#### 3.3.1 Blocs (block updates)
- **Règle** : "dernière observation gagne"
- Maintenir un registre `Map<BlockPos, (timestamp, blockState, sourceReplay)>`
- À chaque block update d'un replay source, comparer le timestamp avec l'existant :
  - Si plus récent → remplacer
  - Si plus ancien → ignorer (on a déjà une info plus fraîche)
- Si un chunk est d'abord chargé par le replay A puis par le replay B à un moment différent, on interprète les deux chargements comme des "snapshots" à leurs timestamps respectifs

#### 3.3.2 Entités (mobs, items droppés, projectiles, autres joueurs)
- **Problème** : un même zombie serveur peut avoir différents entity IDs côté client dans chaque replay
- **Stratégie de déduplication** :
  1. Si on a accès à un UUID serveur cohérent (cas des joueurs, golems nommés…) → c'est la clé primaire
  2. Sinon, heuristique :
     - Même type d'entité
     - Positions observées cohérentes (distance < seuil, ex. 2 blocs) aux mêmes timestamps ou proche
     - Si deux entités observées sont candidates au matching → on fusionne leurs timelines
  3. En cas d'ambiguïté non résolue → on garde les deux (doublon plutôt que perte d'info)
- **Registre** : `Map<EntityIdentifier, MergedEntityTimeline>`
- **Génération** : produire de nouveaux entity IDs globaux pour le replay fusionné, remapper tous les packets entrants

#### 3.3.3 Packets "égocentriques" (propres à un joueur)
- Les replays `.flashback` contiennent des packets qui ne concernent que le joueur qui a enregistré : santé (`ClientboundSetHealthPacket`), XP, inventaire (`ClientboundContainerSetContentPacket`), action bar, chat privé, sons positionnels près de lui…
- **Règle** : ces packets sont conservés **attachés au joueur d'origine** dans la structure du replay fusionné
- Dans Flashback, quand l'utilisateur spectate le joueur X, il voit l'état d'inventaire/santé/etc. de ce joueur comme dans son replay original
- Pour implémenter ça, il faudra probablement étendre la structure du fichier `.flashback` avec des "tracks par joueur" — **ce point nécessite du reverse-engineering approfondi** avant de pouvoir décider de la stratégie finale

#### 3.3.4 Packets "globaux" (monde, météo, heure, événements serveur)
- Exemples : `ClientboundSetTimePacket`, explosions, sons de monde, particules globales
- **Règle** : on dédoublonne par `(type, timestamp, contenu)`. Si deux replays voient la même explosion au même moment, on ne la joue qu'une fois.

#### 3.3.5 Chat et messages
- Les messages `system` sont globaux → dédoublonnés
- Les messages privés sont égocentriques → attachés au joueur d'origine

### 3.4 Chunks
- **Règle** : union. Tous les chunks chargés par au moins un replay source sont présents dans le replay fusionné.
- Pour un même chunk chargé par plusieurs replays à des moments différents, on garde toutes les versions successives dans la timeline (c'est le cas général du "block updates" appliqué à l'échelle du chunk)
- **Warning performance** : la taille du replay final peut exploser. Prévoir des logs informatifs sur la taille prévue avant de lancer le merge.

---

## 4. Architecture du code

### 4.1 Structure du projet
```
flashback-merger/
├── build.gradle
├── gradle.properties
├── settings.gradle
├── src/main/java/fr/zeffut/flashbackmerger/
│   ├── FlashbackMergerMod.java           # ModInitializer
│   ├── format/                           # I/O du format .flashback
│   │   ├── FlashbackReader.java
│   │   ├── FlashbackWriter.java
│   │   ├── FlashbackMetadata.java
│   │   ├── PacketEntry.java              # un packet + timestamp + métadonnées
│   │   └── README.md                     # doc du format reverse-engineered
│   ├── merge/                            # Logique de fusion
│   │   ├── MergeOrchestrator.java        # entry point du merge
│   │   ├── TimelineAligner.java          # synchro temporelle
│   │   ├── WorldStateMerger.java         # blocs, chunks
│   │   ├── EntityMerger.java             # déduplication + timeline fusion
│   │   ├── PacketClassifier.java         # égocentrique vs global vs monde
│   │   ├── IdRemapper.java               # remapping entity/player IDs
│   │   └── MergeReport.java              # stats, warnings, logs
│   ├── ui/                               # Intégration UI dans Flashback
│   │   ├── ReplayListScreenMixin.java    # ajout multi-select + bouton "Merge"
│   │   ├── MergeConfigScreen.java        # fenêtre de config pré-merge
│   │   └── MergeProgressScreen.java      # progression du merge (peut être long)
│   └── util/
│       ├── FlashbackPaths.java           # localisation des fichiers
│       └── Logging.java
├── src/main/resources/
│   ├── fabric.mod.json
│   ├── flashback-merger.mixins.json
│   └── assets/flashback-merger/
│       └── lang/fr_fr.json               # i18n (français + anglais)
│       └── lang/en_us.json
└── src/test/java/...                     # tests unitaires + integration
```

### 4.2 Pipeline de merge (haut niveau)
```
[replay1.flashback, replay2.flashback, ..., replayN.flashback]
       │
       ▼
┌─────────────────────┐
│   FlashbackReader   │   → lit chaque fichier → List<PacketEntry> + Metadata
└─────────────────────┘
       │
       ▼
┌─────────────────────┐
│  TimelineAligner    │   → recale tous les packets sur une timeline absolue
└─────────────────────┘
       │
       ▼
┌─────────────────────┐
│  PacketClassifier   │   → classe chaque packet : global / égocentrique / monde
└─────────────────────┘
       │
       ▼
┌──────────┬──────────┬─────────────┐
│WorldState│  Entity  │   Ego-      │
│ Merger   │  Merger  │   centric   │   (traitement en parallèle)
│ (blocs)  │ (mobs…)  │  (préservé) │
└──────────┴──────────┴─────────────┘
       │
       ▼
┌─────────────────────┐
│    IdRemapper       │   → attribue des IDs globaux cohérents
└─────────────────────┘
       │
       ▼
┌─────────────────────┐
│  FlashbackWriter    │   → écrit merged.flashback
└─────────────────────┘
       │
       ▼
      [output.flashback]
```

### 4.3 Intégration UI dans Flashback
- **Cible** : l'écran de sélection de replay (classe à identifier par reverse-engineering, probablement dans `com.moulberry.flashback.editor.ui.` ou un écran Vanilla custom)
- **Stratégie** : Mixin sur la classe de l'écran pour :
  - Ajouter un mode "multi-select" (Shift-click / Ctrl-click)
  - Ajouter un bouton "Merge Selected" qui n'apparaît que si ≥ 2 replays sélectionnés
- **Fallback plan B** : si l'injection dans l'UI de Flashback s'avère trop fragile / bloquante, on ajoute une commande `/flashback-merge <replay1> <replay2> …` et une UI simple accessible depuis ModMenu. **L'utilisateur final doit toujours pouvoir lancer un merge, même en mode dégradé.**

### 4.4 Feedback utilisateur pendant le merge
Un merge peut durer plusieurs minutes pour de gros replays. Prévoir :
- Barre de progression (pourcentage + phase en cours)
- Log en temps réel des warnings (ex: "3 entities non dédupliquées", "gap temporel de 12s entre replay A et B")
- Possibilité d'annuler
- Rapport final (`MergeReport`) : taille avant/après, nombre de packets, conflits résolus, warnings

---

## 5. Roadmap par phases

### Phase 0 — Setup (1-2 jours)
- [ ] Init du projet Fabric avec Loom (`fabric.mod.json`, `build.gradle`, Mixin config)
- [ ] Git init, `.gitignore`, README minimal
- [ ] Hello-world : le mod se charge dans Minecraft et logge son nom
- [ ] Flashback installé en `modCompileOnly` + runtime dev
- [ ] CI GitHub Actions (build + tests) basique

### Phase 1 — Reverse-engineering du format `.flashback` (3-5 jours)
**Critique et bloquant pour tout le reste.**
- [ ] Générer 3 replays de test :
  - `tiny.flashback` : 10 secondes de gameplay solo statique (regarder le sol dans un flat world)
  - `walking.flashback` : 30 secondes de déplacement
  - `interactive.flashback` : 1 minute avec blocs cassés/posés, mobs, inventaire
- [ ] `unzip` chaque fichier, cartographier :
  - Liste des fichiers internes
  - Format de chacun (JSON ? NBT ? binaire custom ? packets sérialisés via Minecraft codecs ?)
  - Dépendances entre fichiers (index ? manifest ?)
- [ ] Cross-checker avec :
  - Code source de ServerReplay (`src/main/kotlin/me/senseiwells/replay/...`)
  - Code source de `arcade-replay`
  - Décompilation du jar Flashback (lecture seule)
- [ ] Rédiger **`src/main/java/fr/zeffut/flashbackmerger/format/README.md`** : doc complète du format, section par section
- [ ] Implémenter `FlashbackMetadata` (lecture seule)
- [ ] Implémenter `FlashbackReader` (lecture d'un fichier → stream de `PacketEntry`)
- [ ] **Test de validation** : lire tiny.flashback, logger tous les packets, visuellement cohérent

### Phase 2 — Writer + round-trip (2-3 jours)
- [ ] Implémenter `FlashbackWriter`
- [ ] **Test de non-régression critique** : `read(X) → write(Y); X === Y` (byte-for-byte ou sémantiquement équivalent)
- [ ] Ouvrir le fichier `Y` dans Flashback → doit se comporter comme l'original
- [ ] Si ça marche pas → retourner en Phase 1, le format est mal compris

### Phase 3 — Merge de 2 replays triviaux (5-7 jours)
- [ ] Enregistrer 2 replays de 30s dans la **même session** (2 comptes, 2 instances Minecraft, monde local ou petit serveur test) avec zones qui se chevauchent
- [ ] Implémenter `TimelineAligner` + `PacketClassifier` (version minimale)
- [ ] Implémenter `WorldStateMerger` et `EntityMerger` (règles de la section 3)
- [ ] Implémenter `IdRemapper`
- [ ] Implémenter `MergeOrchestrator` en ligne de commande (pas d'UI encore) : `java -jar ... merge replay_A.flashback replay_B.flashback -o merged.flashback`
- [ ] **Test d'acceptation** : ouvrir merged.flashback dans Flashback, vérifier que :
  - Les deux joueurs sont visibles et bougent comme prévu
  - Les chunks des deux zones sont chargés
  - Pas de crash, pas d'incohérence visuelle majeure

### Phase 4 — Merge de N replays complexes (3-5 jours)
- [ ] Test avec 4-5 replays simultanés
- [ ] Optimisations mémoire (streaming plutôt que tout charger en RAM)
- [ ] Gestion des cas limites : replays vides, replays corrompus, replays de versions MC différentes (refuser avec message clair)
- [ ] Génération du `MergeReport`

### Phase 5 — Intégration UI (4-6 jours)
- [ ] Identifier la classe de l'écran "Replay List" de Flashback
- [ ] Mixin : multi-select + bouton Merge
- [ ] `MergeConfigScreen` : nom de sortie, options
- [ ] `MergeProgressScreen` : feedback en live
- [ ] **Plan B si l'intégration UI Flashback est trop fragile** : commande `/flashback-merge` + écran accessible via ModMenu

### Phase 6 — Polish & release (3-4 jours)
- [ ] I18n (fr + en minimum)
- [ ] README utilisateur avec captures / GIFs
- [ ] Page Modrinth : description, screenshots, changelog
- [ ] Tests utilisateurs sur Discord Flashback (avec accord de Moulberry si possible)
- [ ] Version 0.1.0

### Estimation totale : 20-30 jours de dev à temps plein. À toi de caler ça sur tes disponibilités entre ton BUT et le reste.

---

## 6. Tests et validation

### 6.1 Tests unitaires (JUnit 5)
- `FlashbackReaderTest` : parsing de fichiers fixtures
- `FlashbackWriterTest` : round-trip + ouverture dans Flashback
- `TimelineAlignerTest` : recalage correct de packets d'offsets différents
- `EntityMergerTest` : dédup basée sur UUID, dédup heuristique, cas ambigus
- `WorldStateMergerTest` : règle "dernière observation gagne" sur block updates

### 6.2 Tests d'intégration
Dossier `src/test/resources/replays/` avec des `.flashback` générés à la main, petits (< 1 MB) :
- `fixture_solo_static.flashback`
- `fixture_solo_walking.flashback`
- `fixture_two_players_overlap.flashback`
- `fixture_two_players_disjoint.flashback`

### 6.3 Tests manuels
Checklist à exécuter avant chaque release :
- [ ] Merge 2 replays triviaux → ouverture OK dans Flashback
- [ ] Merge 5 replays complexes → ouverture OK, pas de crash
- [ ] Merge de replays avec chevauchement partiel → warnings corrects
- [ ] Merge de replays sans chevauchement → trou temporel gérable
- [ ] UI : sélection multiple, bouton Merge, progress bar

---

## 7. Questions ouvertes à trancher en cours de route

Ces questions nécessitent d'avoir avancé un peu dans le reverse-engineering avant d'être résolues :

1. **Le format Flashback utilise-t-il un index pour le random access** ou est-ce un stream linéaire ? Impact énorme sur les perfs du merge.
2. **Les packets sont-ils compressés** (zstd, lz4, gzip) à l'intérieur du zip ? Si oui, comment ?
3. **Y a-t-il un concept de "tracks" par joueur** ou tout est mélangé dans un seul flux ? Ça change tout pour gérer les packets égocentriques.
4. **Comment Flashback gère-t-il les entity IDs en lecture** ? Faut-il les remapper au chargement ou est-ce fait par Flashback lui-même ?
5. **Est-ce que Flashback accepte des replays avec plusieurs "vrais joueurs"** (pas juste un + entités) dans son état actuel ? Cruciale : si non, il faudra probablement un Mixin côté playback pour étendre le comportement.
6. **Licence finale du projet** : MIT ? Apache 2.0 ? GPL ? (Recommandation : **MIT**, pour maximiser la portée et permettre à d'autres devs de contribuer.)

---

## 8. Conventions de dev

- **Style de code** : Google Java Style Guide
- **Nommage** : classes en PascalCase, packages en lowercase sans underscores
- **Commits** : Conventional Commits (`feat:`, `fix:`, `refactor:`, `docs:`, `test:`…)
- **Branches** : `main` (stable) + `dev` (intégration) + `feature/*`
- **Javadoc** : obligatoire sur toutes les classes publiques et méthodes exposées au reste du mod
- **Logging** : SLF4J via Fabric logger, niveaux :
  - DEBUG : détails pour reverse-engineering
  - INFO : étapes du merge
  - WARN : conflits résolus avec perte potentielle d'info (rare vu la règle "zéro perte")
  - ERROR : cas bloquants

---

## 9. Ressources et liens

### Repos clés
- Flashback : https://github.com/Moulberry/Flashback
- Flashback DeepWiki : https://deepwiki.com/Moulberry/Flashback
- ServerReplay : https://github.com/senseiwells/ServerReplay
- ServerReplay DeepWiki : https://deepwiki.com/senseiwells/ServerReplay
- Arcade (lib avec l'encodage Flashback) : https://github.com/CasualChampionships/Arcade

### Docs techniques
- Fabric Wiki : https://fabricmc.net/wiki/
- Fabric Loom : https://docs.gradle.org/current/userguide/userguide.html
- Mixin (SpongePowered) : https://github.com/SpongePowered/Mixin/wiki
- Minecraft Protocol : https://wiki.vg/Protocol (pour comprendre les packets côté Minecraft)

### Communauté
- Discord Flashback : https://discord.gg/flashbacktool (pour poser des questions à Moulberry et à la communauté — utile pour valider certaines questions ouvertes)
- Discord Fabric : https://discord.gg/v6v4pMv

---

## 10. Notes et journal de bord

> Claude Code : tiens à jour cette section au fur et à mesure du projet. Notes d'implémentation, décisions d'archi, obstacles rencontrés, solutions adoptées.

### [Date] — Découverte initiale
- Le format `.flashback` est un ZIP, confirmé via docs ServerReplay.
- Licence Flashback = restrictive, donc addon strictement séparé, aucun code de Flashback redistribué.

---

**Fin du document. Version : 0.1 (initial spec).**
