# CobblePulse-Battles

Addon de **CobblePulse** pour **Fabric 1.21.1** et **Cobblemon 1.8.4**, qui remplace CobblePulse Battletower :

- **Tour de Combat** : 50 étages, Boss du Jour, Battle Points (BP) et boutique, succès, éditeur graphique de dresseurs ;
- **Ranked** : files classée et amicale, en simple et en double, avec Elo, rangs, saisons, missions quotidiennes et hebdomadaires, webhook Discord et multi-serveur (Velocity + Redis) ;
- **Dresseurs du monde** : des PNJ dresseurs posés n'importe où hors de la Tour, avec récompenses et revanche ;
- **Écrans** : tous les menus sont des écrans client (hub `/battles`), avec des menus coffre pour un joueur qui n'aurait pas le mod ;
- **Éditeur de PNJ** : un objet du staff pour éditer, poser et lister les PNJ.

## Installation

| Côté | Requis | Recommandé |
|---|---|---|
| Serveur | Fabric Loader ≥ 0.16, Fabric API, Fabric Language Kotlin, Cobblemon 1.8.4, **cobblepulse-battles.jar** | CobblePulse (tiers partagés), Mega Showdown 1.3.3+1.8.4 (Méga/Téra), LuckPerms, Text Placeholder API |
| Client | **cobblepulse-battles.jar** (obligatoire : objet Éditeur de PNJ, écrans, éditeur) | — |

1. **Retirer `cobblepulse-battletower-*.jar`** (et CobbleRanked s'il est installé : les deux mods sont incompatibles), puis déposer `cobblepulse-battles-2.0.0.jar` dans `mods/` (serveur et modpack).
2. Au premier démarrage, `config/cobblepulse_battletower/` est déplacé tel quel vers `config/cobblepulse_battles/` (étages, boss, skins, joueurs, réglages). Le mod se déclare aussi comme `cobblepulse_battletower`, donc CobblePulse Utils (tuile du hub) et le Battlepass (BP, quêtes de la Tour) continuent de fonctionner.
3. `/bt admin reload` (Tour) ou `/ranked admin recharger` (Ranked) après une modification manuelle.

SQLite, MySQL (HikariCP), Redis (Jedis) et NightConfig (TOML) sont embarqués dans le jar.

## Fichiers (`config/cobblepulse_battles/`)

| Fichier | Contenu |
|---|---|
| `general.toml` | `client_screens` (écrans client ou menus coffre), fuseau horaire |
| `storage.toml` | `mode = "json"` (défaut), `"mysql"` ou `"sqlite"` ; `share_cobblepulse = true` reprend la base de CobblePulse |
| `tower.toml`, `rewards.toml`, `daily_boss.toml`, `shop.toml`, `achievements.toml` | Tour de Combat (voir plus bas) |
| `messages.toml` | tous les textes (sections `[hub]`, `[ranked]`, `[trainer]`, `[tool]` comprises) |
| `data/floors.json`, `data/bosses.json` | étages et Boss du Jour |
| `dresseurs.json` | dresseurs du monde (position, PNJ, équipe, récompenses) |
| `ranked/ranked.toml` | minuteurs, délais, adversaires récents, pénalités de fuite, commandes de récompense par match, arènes |
| `ranked/elo.toml` | Elo (formule de Pokémon Showdown ou K personnalisé), Elo de départ et plancher, gain maximal par jour, rangs |
| `ranked/formats.toml` | presets de format : simple/double, nombre de Pokémon vus/choisis, niveau imposé, minuteur de tour, durée max, Méga/Téra/Z/Dynamax, clauses, restreints, interdits, matchmaking |
| `ranked/saisons.toml` | calendrier des saisons, preset par saison, reset de fin de saison, archive, annonces |
| `ranked/recompenses.toml` | récompenses de rang (une fois par saison) et de fin de saison (par place) |
| `ranked/missions.toml` | missions quotidiennes et hebdomadaires (victoire, partie, série, K.O.) |
| `ranked/restrictions.toml` | interdits pendant la file et pendant le match (PC, blocs, objets, commandes…) |
| `ranked/reseau.toml` | multi-serveur (Redis) et webhook Discord |
| `ranked/arenes.json` | arènes (2 positions + sortie facultative) |
| `skins/`, `tiers/` | skins PNG (`nom_slim.png` = bras fins) et banlists des tiers |

En MySQL/SQLite : tables `cp_bt_players`, `cp_bt_achievements`, `cp_bt_daily_logs`, `cp_bt_kv`, `cp_bt_rk_profiles` et `cp_bt_rk_matches`.

## Hub et écrans

`/battles` (ou `/combats`) ouvre le hub : Tour, Ranked, Dresseurs, Classements et, pour le staff, Administration. Avec le mod côté client, chaque menu est un écran en cartes ; `client_screens = false` dans `general.toml` revient aux menus coffre.

## Éditeur de PNJ (staff)

Objet `cobblepulse_battles:editeur_pnj`, obtenu par `/dresseur outil`, le menu Administration du hub, la liste des dresseurs ou l'onglet « Opérateur » du mode créatif.

- clic droit sur un PNJ du mod : son éditeur s'ouvre (étage, Boss du Jour, dresseur du monde ; panneau admin pour le marchand) ;
- clic droit sur un bloc : nouveau dresseur posé sur ce bloc, tourné vers vous (identifiant demandé, puis éditeur) ;
- clic droit dans le vide : liste des dresseurs.

Réservé à `cobblepulse.battles.trainers.admin` ou aux administrateurs de la Tour ; un autre joueur qui le tient ne peut rien faire.

## Ranked

- **Files** : `/ranked` (écran), `/ranked file <simple|double> [classe|amical]`, `/amical [format]`, `/ranked quitter`. La file est partagée par format et par mode ; l'écart d'Elo accepté s'élargit avec l'attente et les adversaires récents sont évités quand la file est assez grande.
- **Match trouvé** : écran de confirmation (17 s). Un refus ou une absence de réponse donne un délai de 30 s ; l'adversaire retourne en file.
- **Sélection** : les 6 Pokémon de l'adversaire sont visibles ; on choisit 3 (simple) ou 4 (double) Pokémon dans l'ordre, le premier mène. Le temps écoulé déclenche une sélection automatique.
- **Combat** : arène libre si elle existe (sinon sur place, les joueurs sont écartés de 6 blocs), compte à rebours, niveau imposé (50 par défaut). Les Pokémon sont des copies : aucun gain d'EXP, d'EV ou d'objet, et l'équipe du joueur n'est jamais modifiée. Le sac est interdit. Minuteur de tour : choix automatique, puis forfait au 3ᵉ tour expiré. Durée maximale : départage de Showdown (Pokémon restants, puis PV), nul possible.
- **Elo et rangs** : Poké Ball, Super Ball (1200), Hyper Ball (1400), Master Ball (1600), Ultra Ball (1800), Mémoire Ball (2000). Une montée de rang est annoncée et récompensée une fois par saison.
- **Fuites** : déconnexion ou abandon en classé. À partir de la 3ᵉ fuite, la file classée est bloquée (5, 15 puis 60 min, ×1,5 pour un abandon précoce). Une fuite est oubliée toutes les 24 h.
- **Saisons** : à la fin d'une saison, le top est archivé, les récompenses de place sont données (à la connexion si le joueur est absent) et l'Elo est ramené vers 1000 (facteur 0,5 par défaut).
- **Missions** : 3 quotidiennes et 2 hebdomadaires tirées par joueur, à réclamer dans l'écran Missions.
- **Discord** : webhook pour les montées de rang, les fins de saison et les résultats classés (`reseau.toml` → `[discord]`).
- **Arènes** : `/ranked admin arene creer <nom>` (position 1 = votre position), puis `/ranked admin arene pos2|sortie <nom>`, ou l'écran Administration de la Ranked (positions, sortie, activation, téléportation).

### Multi-serveur (réseau Velocity)

1. Tous les serveurs utilisent la **même base MySQL** (`storage.toml`) et le **même Redis** (`reseau.toml` → `[redis]`).
2. Sur chaque serveur : `[multi_serveur] actif = true`, `id_serveur` = nom du serveur dans `velocity.toml`, `serveur_combat` = serveur qui joue les matchs (il fait aussi le matchmaking et les changements de saison).
3. Transfert : `methode_transfert = "velocity"` (activer `bungee-plugin-message-channel = true` dans `velocity.toml`) ou `"transfert"` (paquet vanilla vers `[multi_serveur.adresses]`, `accepts-transfers=true` sur le serveur cible).

Un joueur rejoint la file depuis n'importe quel serveur. Quand le serveur de combat trouve un match, l'équipe est transmise par Redis, les joueurs y sont envoyés, combattent, puis reviennent sur leur serveur d'origine. Les profils sont enregistrés immédiatement et relus par les autres serveurs ; une copie plus ancienne n'écrase jamais un résultat plus récent.

## Dresseurs du monde

`/dresseur creer <id>` (ou l'Éditeur de PNJ) pose un PNJ dresseur ; l'éditeur graphique est le même que celui de la Tour, avec un onglet **Récompenses** :

- mode **normal** (équipe réelle du joueur : EXP, PV et objets conservés) ou **exhibition** (copie soignée, sans gain) ;
- revanche après N heures (−1 = jamais), récompenses aussi en revanche ou non, attente après une défaite ;
- BP de la Tour, objets (`cobblemon:rare_candy 3`), commandes console (`%player%`), phrase de victoire du dresseur.

`/dresseurs` affiche la progression du joueur ; `/dresseur liste|editer|deplacer|supprimer|tp|reset <joueur> [id]` pour le staff.

## Tour de Combat

### Mise en route

1. `/bt admin setexit` : hall de retour (sinon le joueur revient là où il a lancé l'assaut).
2. Pour chaque étage N : se placer à l'arrivée puis `/bt npc settp N`, se placer où doit se tenir le dresseur puis `/bt npc spawn N`.
3. `/bt npc edit` en regardant le PNJ, `/bt npc edit N` ou l'Éditeur de PNJ : nom, tier, IA, format, niveau imposé, combat inversé, météo, terrain, équipe complète et skin. Le bouton **Importer Showdown** accepte un export du teambuilder de Pokémon Showdown.
4. Boss du Jour : `/bt boss create <id>`, `/bt boss settp`, `/bt boss spawn`, puis ajouter l'id dans `daily_boss.toml`.
5. Boutique : `/bt shop spawn`.

Le panneau `/bt admin` regroupe tout cela en menus (état des 50 étages, téléportation, ouverture/fermeture, rechargement).

### Règles de la tour

- 50 étages d'une seule traite. Le tier est défini par étage. Le joueur peut ouvrir son PC sur l'étage avant d'engager le combat, et son équipe est vérifiée à chaque lancement.
- Les combats se font avec des **copies** des Pokémon du joueur : PV, PP, statuts et objets (Baies, Ceinture Force…) sont restitués. Aucun gain d'EXP ni d'EV. Le sac est interdit.
- Victoire : BP de l'étage, soin, montée automatique. Étage 50 : récompense finale, temps enregistré, annonce globale avec son.
- Défaite, abandon ou sortie de la tour : retour au hall et cooldown de 24 h. Le record d'étage est conservé.
- File d'attente : si le dresseur d'un étage est déjà en combat, le joueur suivant attend (le chrono est en pause) et le combat démarre automatiquement.
- Déconnexion : pendant un combat, c'est une défaite. Hors combat, le joueur a 60 s pour revenir. Si le serveur redémarre ou plante, la série est conservée et reprend à l'étage en cours.

### Fakemons (datapacks)

Les espèces ajoutées par datapack sont prises en charge partout (éditeur, import Showdown, combats, tiers, IA) :

- dans l'éditeur, le champ Espèce accepte l'identifiant complet (`monpack:volt_mon`), le nom de fichier (`volt_mon`) ou le nom affiché (`Volt Mon`) ; la touche Tab propose aussi les fakemons ;
- à l'import Showdown, un nom composé (`Volt-Mon`) est reconnu comme une espèce et non comme une forme ;
- un tier peut bannir un fakemon par son nom (`voltmon`) ou son identifiant (`monpack:volt_mon`) ; sinon il est autorisé ;
- l'IA lit ses statistiques et ses types dans Cobblemon, comme pour n'importe quel Pokémon.

Les capacités et talents inédits d'un fakemon doivent exister côté Showdown (c'est le rôle du datapack ou du mod qui les ajoute). Le datapack doit aussi être installé sur le serveur, et le pack de ressources (modèles) sur les clients, comme pour Cobblemon.

### IA (loyale : elle ne voit que ce qu'un joueur verrait)

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
| `/battles`, `/combats` (hub) | — |
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
| `/ranked`, `/ranked file …`, `quitter`, `accepter`, `refuser`, `top`, `profil`, `missions`, `historique`, `rangs` | `cobblepulse.battles.ranked.use` |
| `/amical`, `/ranked file <format> amical` | `cobblepulse.battles.ranked.casual` |
| `/ranked admin` (arènes, `saison info/changer`, `elo definir/ajouter`, `bannir`, `debannir`, `fin`, `reset`, `recharger`) | `cobblepulse.battles.ranked.admin` |
| `/dresseurs`, combattre un dresseur du monde | `cobblepulse.battles.trainers.use` |
| `/dresseur …`, `/dresseur outil`, Éditeur de PNJ | `cobblepulse.battles.trainers.admin` |

`cobblepulse.battletower.admin.*` donne tous les droits de la Tour. Sans LuckPerms : joueurs autorisés, staff = opérateurs niveau 2, admin = niveau 3.

## Placeholders (Text Placeholder API)

`%cobblepulse_bt:top_1_name%` … `top_10_*` (`name`, `floor`, `completions`, `wins`, `time`), `%cobblepulse_bt:player_rank%`, `player_floor`, `player_bp`, `player_completions`, `player_wins`, `player_best_time`, `player_cooldown`, `player_current_floor`, `player_daily_status`, `daily_boss_name`, `tower_status`, `runs`.

## Développement

Le code compile et se teste sur GitHub Actions (`.github/workflows/build.yml`) :

- **serveur** : un vrai serveur Fabric démarre avec Cobblemon 1.8.4 et Mega Showdown, en JSON, MySQL et SQLite. Des joueurs factices pilotés par un bot jouent contre l'IA de la Tour (ascension, file d'attente, défaite, Boss du Jour, Téra, Méga, fakemon de datapack, reprise après redémarrage). Ils jouent aussi des matchs de Ranked passés par les écrans (file, confirmation, sélection, arène, Elo, rang, missions, Discord, retour), puis le minuteur de tour, une déconnexion, un nul à la durée maximale et un refus. Suivent les dresseurs du monde (récompenses, revanche, exhibition, délai après défaite), l'Éditeur de PNJ, un changement de saison, la migration de l'ancienne configuration et la persistance après redémarrage ;
- **multi-serveur** : deux serveurs (lobby et combat) avec Redis et MySQL. La file est faite sur le lobby, le match sur le serveur de combat avec l'équipe transmise par Redis. On vérifie le transfert, l'Elo, le retour, le profil relu par le lobby et le webhook Discord reçu en HTTP ;
- **client** : un client de production sous xvfb capture l'éditeur, puis se connecte à un serveur de test qui lui ouvre chaque écran (hub, Ranked, sélection, dresseurs, Éditeur de PNJ en main).

Les résultats sont publiés sur les branches `ci-output` et `ci-output-multi`. Les skins embarqués sont générés par `tools/gen_skins.py`.
