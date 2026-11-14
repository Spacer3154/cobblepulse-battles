package fr.cobblepulse.battletower.data;

import fr.cobblepulse.battletower.core.Loc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Contenu de data/floors.json : etages, hall de sortie, arene du Boss du Jour, PNJ boutique. */
public final class TowerData {
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
