package fr.cobblepulse.battletower.tower;

import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.core.Json;
import fr.cobblepulse.battletower.data.FloorEntry;
import fr.cobblepulse.battletower.data.MonSpec;
import fr.cobblepulse.battletower.data.TowerData;
import fr.cobblepulse.battletower.data.TrainerSpec;
import fr.cobblepulse.battletower.storage.DataService;
import fr.cobblepulse.battletower.storage.JsonStorage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** data/floors.json (etages, coordonnees, PNJ, equipes) et data/bosses.json (Boss du Jour). */
public final class TowerConfig {
    private static TowerData data = new TowerData();
    private static TowerData.Bosses bosses = new TowerData.Bosses();

    private TowerConfig() {}

    public static TowerData data() {
        return data;
    }

    public static TowerData.Bosses bosses() {
        return bosses;
    }

    private static Path floorsFile() {
        return Cfg.dataDir().resolve("floors.json");
    }

    private static Path bossesFile() {
        return Cfg.dataDir().resolve("bosses.json");
    }

    public static void load() {
        try {
            Path f = floorsFile();
            if (Files.exists(f)) {
                TowerData d = Json.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), TowerData.class);
                if (d != null) data = d;
            } else {
                data = new TowerData();
            }
        } catch (Exception e) {
            Battletower.LOG.error("[Battletower] floors.json illisible ({}) : configuration conservee en memoire, fichier non ecrase", e.toString());
            return;
        }
        if (data.floors == null) data.floors = new ArrayList<>();
        if (data.shops == null) data.shops = new ArrayList<>();
        // Un etage par numero, de 1 a N
        int n = Cfg.floors();
        for (int i = 1; i <= n; i++) {
            if (floor(i) == null) data.floors.add(new FloorEntry(i));
        }
        data.floors.sort(Comparator.comparingInt(fe -> fe.floor));
        for (FloorEntry fe : data.floors) {
            if (fe.trainer == null) fe.trainer = new TrainerSpec();
            fe.trainer.normalize();
        }
        try {
            Path b = bossesFile();
            if (Files.exists(b)) {
                TowerData.Bosses bs = Json.GSON.fromJson(Files.readString(b, StandardCharsets.UTF_8), TowerData.Bosses.class);
                if (bs != null && bs.bosses != null) bosses = bs;
            } else {
                bosses = new TowerData.Bosses();
                bosses.bosses.put("boss_exemple", exampleBoss());
            }
        } catch (Exception e) {
            Battletower.LOG.error("[Battletower] bosses.json illisible : {}", e.toString());
        }
        for (TrainerSpec t : bosses.bosses.values()) t.normalize();
        saveFloors();
        saveBosses();
    }

    private static TrainerSpec exampleBoss() {
        TrainerSpec t = new TrainerSpec();
        t.name = "Maitre de la Tour";
        t.title = "Boss";
        t.ai = "EXTREME";
        t.skin = "builtin:champion";
        t.levelScale = 50;
        MonSpec m = new MonSpec();
        m.species = "garchomp";
        m.level = 50;
        m.nature = "jolly";
        m.ability = "roughskin";
        m.heldItem = "cobblemon:life_orb";
        m.evs.put("atk", 252);
        m.evs.put("spe", 252);
        m.evs.put("hp", 4);
        m.moves.add(new MonSpec.MoveSpec("earthquake"));
        m.moves.add(new MonSpec.MoveSpec("dragonclaw"));
        m.moves.add(new MonSpec.MoveSpec("swordsdance"));
        m.moves.add(new MonSpec.MoveSpec("stoneedge"));
        m.teraType = "steel";
        m.allowTera = true;
        t.team.add(m);
        return t;
    }

    public static FloorEntry floor(int n) {
        for (FloorEntry fe : data.floors) if (fe.floor == n) return fe;
        return null;
    }

    public static List<FloorEntry> floors() {
        return data.floors;
    }

    public static TrainerSpec boss(String id) {
        return id == null ? null : bosses.bosses.get(id.toLowerCase(Locale.ROOT));
    }

    public static void saveFloors() {
        String json = Json.GSON.toJson(data);
        Path f = floorsFile();
        write(f, json);
    }

    public static void saveBosses() {
        String json = Json.GSON.toJson(bosses);
        write(bossesFile(), json);
    }

    private static void write(Path f, String json) {
        if (DataService.storage() == null) {
            try {
                JsonStorage.writeAtomic(f, json);
            } catch (Exception e) {
                Battletower.LOG.error("[Battletower] Ecriture de {} impossible : {}", f, e.toString());
            }
            return;
        }
        DataService.async(s -> {
            JsonStorage.writeAtomic(f, json);
            return null;
        });
    }
}
