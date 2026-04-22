# Modrinth release checklist

Prêt-à-coller pour publier sur [modrinth.com/mods/create](https://modrinth.com/mods/create).

---

## 1. Project basics

| Champ | Valeur |
|---|---|
| **Project name** | `MultiView` |
| **Project ID / slug** | `multiview` |
| **Project type** | `Mod` |
| **Summary (256 chars max)** | Fusionne plusieurs replays Flashback d'une même session en un seul replay unifié — "observateur omniscient" avec l'union des chunks, joueurs et événements observés par chaque POV. |
| **Environment** | Client-side |
| **License** | MIT |
| **Issues URL** | `https://github.com/Zeffut/MultiView/issues` |
| **Source URL** | `https://github.com/Zeffut/MultiView` |
| **Discord / Support** | (optionnel) |

## 2. Tags / categories

À cocher :
- `Utility`
- `Cursed` (fun)
- Client-side

## 3. Body / Long description

Copier-coller dans l'éditeur Modrinth (markdown supporté) :

---

# MultiView

**MultiView** est un addon Fabric pour [Flashback](https://modrinth.com/mod/flashback). Il prend **plusieurs replays** enregistrés par **différents joueurs d'une même session Minecraft** et les fusionne en **un seul replay unifié** qui contient l'union de toutes les informations observées.

Le résultat se comporte comme si un **observateur omniscient** avec render distance illimitée avait enregistré la session :
- Tous les chunks explorés par au moins un joueur sont chargés
- Tous les joueurs enregistreurs sont visibles simultanément
- Toutes les entités et événements observés par n'importe quel POV sont là
- Caméra libre pour explorer librement, Spectate Player pour suivre un enregistreur particulier

## ✨ Fonctionnalités

- **Fusion de N replays** via une interface intégrée dans Flashback (case à cocher sur chaque replay, bouton *Merge*)
- **Alignement temporel automatique au tick près** (via `ClientboundSetTimePacket`)
- **Chunks de toutes les zones** présents dans le replay fusionné
- **Tous les joueurs enregistreurs visibles** simultanément
- **Markers agrégés** (Changed Dimension, etc.)
- **Player list complète** dans Spectate
- **Écran de progression** avec barre et phase texte
- **Rollback atomique** si un merge échoue
- Disponible en **français** et **anglais**
- Ne redistribue aucun code de Flashback (légalement clean)

## 🎯 Public cible

- Créateurs de contenu : events PvP, SMP multi-joueurs, tournois, machinimas
- Staffs de serveurs : review anti-cheat multi-angles
- Cinematographers : caméra libre dans une scène multi-joueurs sans perte d'info

## 📋 Utilisation

1. Chaque joueur de ta session enregistre son POV avec Flashback.
2. Récupère les `.zip` produits et place-les dans ton `.minecraft/flashback/replays/`.
3. Ouvre Minecraft avec **Flashback + MultiView** installés.
4. Icône caméra → **Select Replay**.
5. Coche les cases à droite de chaque replay (minimum 2).
6. Clique **Merge N Replays** (en haut à droite).
7. Attend la fin de la fusion.
8. Le replay fusionné apparaît automatiquement → ouvre-le comme un replay normal.

## ⚠️ Limitations connues

- **POV secondaires** : visibles en tant qu'entités joueur. La caméra suit le POV qui a commencé à enregistrer en premier (le "primary").
- **Fusion à 4+ POV** : fonctionnelle mais quelques imperfections visuelles possibles (chunks qui flickent si plusieurs POV voient la même zone avec versions conflictuelles).
- **Dimensions multiples** : la caméra suit les changements de dimension du primary. Les POV secondaires dans une autre dimension n'apparaissent pas à leur position visible.

## 🔧 Prérequis

- Minecraft **1.21.11**
- Fabric Loader **0.19.2+**
- Fabric API **0.141.3+1.21.11**
- [Flashback](https://modrinth.com/mod/flashback) **0.39.4**

## 📝 Licence

MIT — libre d'utilisation, modification, redistribution. Code source sur [GitHub](https://github.com/Zeffut/MultiView).

*Flashback reste sous sa licence propriétaire Moulberry. MultiView est un addon totalement indépendant qui ne redistribue aucun code de Flashback.*

---

## 4. Version (à uploader plus tard)

| Champ | Valeur |
|---|---|
| **Version name** | `0.2.0` |
| **Version number** | `0.2.0` |
| **Release channel** | `Release` |
| **Game versions** | `1.21.11` |
| **Loaders** | `Fabric` |
| **Primary file** | `build/libs/multiview-0.2.0.jar` |
| **Additional files** | `build/libs/multiview-0.2.0-sources.jar` (optional) |

### Dependencies
- **Required** : `fabric-api` (version `0.141.3+1.21.11` ou +)
- **Required** : `flashback` (version `0.39.4`)

### Version changelog (coller depuis `CHANGELOG.md` entrée 0.2.0)

## 5. Captures d'écran nécessaires

À prendre **avant de publier** :

1. **UI Select Replay avec cases cochées** — montre les checkboxes MultiView à droite de chaque ligne + bouton "Merge N Replays" en haut à droite.
2. **Écran de progression** pendant un merge — barre + phase texte + pourcentage.
3. **Replay fusionné ouvert dans Flashback** — vue caméra libre avec au moins 2 joueurs visibles + markers sur la timeline.
4. **Spectate Player list** — la liste complète des joueurs avec skins.
5. (Optionnel) **Gallery** : 2-3 angles différents du replay fusionné pour montrer le côté "omniscient".

Taille recommandée : **1920×1080** ou **1280×720**. Format `.png`.

## 6. Gallery setup

Sur Modrinth, après la création :
- `Gallery` tab → upload screenshots
- Set 1 as **featured** (apparaît sur la card du mod)

## 7. Post-publication

- Share sur Discord Flashback : `https://discord.gg/flashbacktool`
- Share sur r/fabricmc
- Announce sur Twitter/BlueSky avec #Minecraft #Fabric #Flashback
- Mettre à jour le README du repo GitHub pour pointer vers la page Modrinth

---

**Ready-to-publish quand les tests 0.2 sont validés.**
