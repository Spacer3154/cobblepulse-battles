# CobblePulse Battletower

Addon de **CobblePulse** pour **Fabric 1.21.1** et **Cobblemon 1.8.4** : une Tour de Combat de 50 étages, un Boss du Jour, une économie de Battle Points (BP) avec boutique, des Quêtes & Succès et un éditeur graphique de dresseurs.

## Installation

| Côté | Requis | Recommandé |
|---|---|---|
| Serveur | Fabric Loader ≥ 0.16, Fabric API, Fabric Language Kotlin, Cobblemon 1.8.4, **cobblepulse-battletower.jar** | CobblePulse (tiers partagés), Mega Showdown 1.3.3+1.8.4 (Méga/Téra), LuckPerms, Text Placeholder API |
| Client | **cobblepulse-battletower.jar** (éditeur graphique et galerie de skins) | — |

Les PNJ et leurs skins s'affichent pour tous les joueurs via Cobblemon. Le mod côté client sert à l'éditeur des administrateurs ; il peut être inclus dans le modpack sans effet de bord.

1. Déposer le `.jar` dans `mods/` (serveur et modpack client).
2. Démarrer une fois : les fichiers sont créés dans `config/cobblepulse_battletower/`.
3. Mise en route (voir plus bas), puis `/bt admin reload` après toute modification manuelle.

SQLite, MySQL (HikariCP) et NightConfig (TOML) sont embarqués dans le jar.

## Fichiers (`config/cobblepulse_battletower/`)

| Fichier | Contenu |
|---|---|
| `storage.toml` | `mode = "json"` (défaut), `"mysql"` ou `"sqlite"` ; `share_cobblepulse = true` reprend la base de CobblePulse |
| `tower.toml` | nombre d'étages, cooldown (24 h), compte à rebours, PC sur l'étage, rayon de sortie, déconnexions (60 s), combats (copies, soins, sac interdit), annonces, budget de l'IA Extrême |
| `rewards.toml` | BP par plage d'étages, récompense finale, paliers, multiplicateur VIP |
| `daily_boss.toml` | rotation (`cycle` ou `weekday`), fuseau horaire, récompenses par boss |
| `shop.toml` | articles de la boutique (items, commandes console `%player%`, prix, limites) |
| `achievements.toml` | succès (`reach_floor`, `no_ko_streak`, `daily_boss_distinct`, `monotype_floors`…) |
| `messages.toml` | tous les textes |
| `data/floors.json` | les 50 étages : arrivée, PNJ, dresseur et équipe (modifiable via FileZilla) |
| `data/bosses.json` | dresseurs du Boss du Jour |
| `data/players/[uuid].json` | records, BP, cooldowns, succès (mode JSON) |
| `skins/` | skins PNG ajoutés par l'administrateur (`nom_slim.png` = bras fins) |
| `tiers/` | banlists des tiers, utilisées seulement si CobblePulse est absent (sinon `config/cobblepulse/tournament_tiers/`) |

En MySQL/SQLite : tables `cp_bt_players`, `cp_bt_achievements`, `cp_bt_daily_logs` et `cp_bt_kv`.

## Mise en route

1. `/bt admin setexit` : hall de retour (sinon le joueur revient là où il a lancé l'assaut).
2. Pour chaque étage N : se placer à l'arrivée puis `/bt npc settp N`, se placer où doit se tenir le dresseur puis `/bt npc spawn N`.
3. `/bt npc edit` en regardant le PNJ (ou `/bt npc edit N`) : nom, tier, IA, format, niveau imposé, combat inversé, météo, terrain, équipe complète et skin. Le bouton **Importer Showdown** accepte un export du teambuilder de Pokémon Showdown.
4. Boss du Jour : `/bt boss create <id>`, `/bt boss settp`, `/bt boss spawn`, puis ajouter l'id dans `daily_boss.toml`.
5. Boutique : `/bt shop spawn`.

Le panneau `/bt admin` regroupe tout cela en menus (état des 50 étages, téléportation, ouverture/fermeture, rechargement).

## Règles de la tour

- 50 étages d'une seule traite. Le tier est défini par étage. Le joueur peut ouvrir son PC sur l'étage avant d'engager le combat, et son équipe est vérifiée à chaque lancement.
- Les combats se font avec des **copies** des Pokémon du joueur : PV, PP, statuts et objets (Baies, Ceinture Force…) sont restitués. Aucun gain d'EXP ni d'EV. Le sac est interdit.
- Victoire : BP de l'étage, soin, montée automatique. Étage 50 : récompense finale, temps enregistré, annonce globale avec son.
- Défaite, abandon ou sortie de la tour : retour au hall et cooldown de 24 h. Le record d'étage est conservé.
- File d'attente : si le dresseur d'un étage est déjà en combat, le joueur suivant attend (le chrono est en pause) et le combat démarre automatiquement.
- Déconnexion : pendant un combat, c'est une défaite. Hors combat, le joueur a 60 s pour revenir. Si le serveur redémarre ou plante, la série est conservée et reprend à l'étage en cours.

## IA (loyale : elle ne voit que ce qu'un joueur verrait)

| Niveau | Comportement |
|---|---|
| Facile | table des types parfaite, capacités STAB/super efficaces, placement au premier tour (Danse-Lames, Piège de Roc, écrans…) si non menacée de K.O., switch sur double faiblesse |
| Moyen | + calcul de dégâts exact (IV/EV, nature, objets, météo, terrain, écrans, Inverse), K.O.-range, priorité pour achever, pivots, Méga/Téra offensive du sweeper |
| Difficile | + pose/retrait des hazards, statuts ciblés (Feu Follet, Cage-Éclair, Toxik), préservation de la win-condition, Téra défensive anticipée |
| Extrême | + recherche minimax sur 2 tours (prédiction des switchs, double-switch, dégâts résiduels au PV près), Téra gardée en joker, Méga immédiate |

Les statistiques adverses sont estimées (IV 31, EV moyens) ; les capacités adverses sont celles déjà révélées, complétées par des attaques STAB supposées. La recherche Extrême s'exécute sur le fil du serveur et reste bornée par `ai.extreme_budget_ms` (40 ms par décision par défaut, soit moins d'un tick) ; baissez cette valeur si le serveur est très chargé.

Météo et terrain permanents : ils sont posés au début du combat, avec une durée infinie, puis reposés au tour suivant si une attaque les a remplacés et que cet effet a pris fin. Le Brouillard Épais (absent de la 9e génération) est recréé : précision ×0,6. Ces effets passent par le moteur Showdown embarqué de Cobblemon (service Graal par défaut) ; avec le service Showdown « socket » de débogage, ils sont ignorés et un avertissement est écrit dans la console.

## Commandes et permissions

| Commande | Permission |
|---|---|
| `/battletower`, `/bt` (menu), `top`, `succes`, `start`, `engage`, `pc`, `abandon`, `boss` | `cobblepulse.battletower.use` |
| PNJ marchand (clic) | `cobblepulse.battletower.shop` |
| Bonus de BP | `cobblepulse.battletower.vip.bp_boost` |
| `/bt staff disqualify <joueurs>`, `/bt staff inspect <joueur>` | `cobblepulse.battletower.staff.disqualify` |
| `/bt staff resetcooldown <joueur>` | `cobblepulse.battletower.staff.resetcooldown` |
| `/bt admin toggle` | `cobblepulse.battletower.admin.toggle` |
| `/bt npc …`, `/bt admin setexit`, `/bt admin floors` | `cobblepulse.battletower.admin.npc` |
| `/bt boss create/edit/delete/settp/spawn/rotate/list` | `cobblepulse.battletower.admin.boss` |
| `/bt shop spawn/remove` | `cobblepulse.battletower.admin.shop` |
| `/bt admin reload` | `cobblepulse.battletower.admin.reload` |
| `/bt admin bp give/take/set <joueur> <montant>` | `cobblepulse.battletower.admin.bp` |

`cobblepulse.battletower.admin.*` donne tout. Sans LuckPerms : joueurs autorisés, staff = opérateurs niveau 2, admin = niveau 3.

## Placeholders (Text Placeholder API)

`%cobblepulse_bt:top_1_name%` … `top_10_*` (`name`, `floor`, `completions`, `wins`, `time`), `%cobblepulse_bt:player_rank%`, `player_floor`, `player_bp`, `player_completions`, `player_wins`, `player_best_time`, `player_cooldown`, `player_current_floor`, `player_daily_status`, `daily_boss_name`, `tower_status`, `runs`.

## Développement

Le code compile et se teste sur GitHub Actions (`.github/workflows/build.yml`). Un vrai serveur Fabric démarre avec Cobblemon 1.8.4 et Mega Showdown, en JSON, MySQL et SQLite. Des joueurs factices pilotés par un bot jouent alors contre l'IA de la tour : ascension complète, file d'attente, défaite et cooldown, Boss du Jour, Téra défensive, Méga en Extrême, reprise après redémarrage. Le client est lancé sous xvfb pour capturer l'éditeur. Les résultats sont publiés sur la branche `ci-output`. Les skins embarqués sont générés par `tools/gen_skins.py`.
