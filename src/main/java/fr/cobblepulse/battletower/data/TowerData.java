package fr.cobblepulse.battletower.data;

import fr.cobblepulse.battletower.core.Loc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Contenu de data/floors.json : etages, hall de sortie, arene du Boss du Jour, PNJ boutique. */
public final class TowerData {
    /** Identifiant de la tour ("principale" = la grande tour de tower.toml, sinon data/tours/<id>.json). */
    public String id = "principale";
    /** Nom affiche (vide = nom par defaut). */
    public String name = "";
    /** Nombre d'etages d'une tour secondaire (la tour principale utilise tower.toml -> floors). */
    public int size;
    /** Objet affiche dans le choix de la tour (ex. minecraft:iron_sword). */
    public String icon = "";
    /** Cooldown apres une defaite, en heures (-1 = tower.toml -> defeat_cooldown_hours). */
    public int cooldownHours = -1;
    /** Multiplicateur des BP par etage (rewards.toml). */
    public double bpMultiplier = 1.0;
    /** Recompense de fin d'une tour secondaire : BP et commandes console (%player%). */
    public long finalBp;
    public List<String> finalCommands = new ArrayList<>();
    /** Permission necessaire pour entrer (vide = aucune). */
    public String permission = "";
    /** Ordre d'affichage dans le choix de la tour. */
    public int order;

    /** Point de sortie / hall (retour apres defaite, victoire finale ou abandon). */
    public Loc exit;
    public Loc bossArrival;
    public Loc bossNpcLocation;
    public String bossNpcUuid;
    public List<ShopNpc> shops = new ArrayList<>();
    public List<FloorEntry> floors = new ArrayList<>();

    public static final class ShopNpc {
        public String uuid;
        public Loc location;
    }

    /** Dresseurs du Boss du Jour (data/bosses.json). */
    public static final class Bosses {
        public Map<String, TrainerSpec> bosses = new LinkedHashMap<>();
    }
}
