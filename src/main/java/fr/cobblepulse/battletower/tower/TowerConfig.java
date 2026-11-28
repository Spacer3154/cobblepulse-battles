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
    /** Identifiant de la tour principale (tower.toml + data/floors.json). */
    public static final String MAIN = "principale";
    private static TowerData data = new TowerData();
    /** Tours secondaires (data/tours/<id>.json), plus petites ou plus grandes que la principale. */
    private static final java.util.Map<String, TowerData> EXTRA = new java.util.LinkedHashMap<>();
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
        data.id = MAIN;
        for (FloorEntry fe : data.floors) {
            if (fe.trainer == null) fe.trainer = new TrainerSpec();
            fe.trainer.normalize();
            fe.tower = MAIN;
        }
        loadExtra();
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

    private static Path towersDir() {
        return Cfg.dataDir().resolve("tours");
    }

    private static void loadExtra() {
        EXTRA.clear();
        Path dir = towersDir();
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(x -> x.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    TowerData t = Json.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), TowerData.class);
                    if (t == null) continue;
                    String file = f.getFileName().toString();
                    t.id = normalizeId(file.substring(0, file.length() - 5));
                    if (t.id.isEmpty() || t.id.equals(MAIN)) continue;
                    prepare(t);
                    EXTRA.put(t.id, t);
                } catch (Exception e) {
                    Battletower.LOG.error("[Battletower] Tour {} illisible : {}", f.getFileName(), e.toString());
                }
            }
        } catch (Exception e) {
            Battletower.LOG.error("[Battletower] Dossier des tours illisible : {}", e.toString());
        }
        if (!EXTRA.isEmpty()) Battletower.LOG.info("[Battletower] {} tour(s) secondaire(s) : {}", EXTRA.size(), String.join(", ", EXTRA.keySet()));
    }

    /** Complete une tour secondaire : un etage par numero de 1 a N, dresseurs normalises. */
    private static void prepare(TowerData t) {
        if (t.floors == null) t.floors = new ArrayList<>();
        if (t.shops == null) t.shops = new ArrayList<>();
        if (t.finalCommands == null) t.finalCommands = new ArrayList<>();
        if (t.name == null) t.name = "";
        if (t.icon == null) t.icon = "";
        if (t.permission == null) t.permission = "";
        t.size = Math.max(1, Math.min(200, t.size <= 0 ? 10 : t.size));
        for (int i = 1; i <= t.size; i++) {
            if (floor(t, i) == null) t.floors.add(new FloorEntry(i));
        }
        t.floors.sort(Comparator.comparingInt(fe -> fe.floor));
        for (FloorEntry fe : t.floors) {
            if (fe.trainer == null) fe.trainer = new TrainerSpec();
            fe.trainer.normalize();
            fe.tower = t.id;
        }
    }

    public static String normalizeId(String raw) {
        if (raw == null) return "";
        String s = java.text.Normalizer.normalize(raw.trim().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.replaceAll("[^a-z0-9_-]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    // ------------------------------------------------------------------ Tours

    public static boolean isMain(TowerData t) {
        return t == null || t == data || MAIN.equals(t.id);
    }

    public static boolean isMain(String id) {
        return id == null || id.isBlank() || MAIN.equals(id);
    }

    /** Tour par identifiant (vide ou "principale" = tour principale), null si inconnue. */
    public static TowerData tower(String id) {
        if (isMain(id)) return data;
        return EXTRA.get(normalizeId(id));
    }

    /** Toutes les tours : la principale d'abord, puis les secondaires (ordre, puis nombre d'etages). */
    public static List<TowerData> towers() {
        List<TowerData> out = new ArrayList<>();
        out.add(data);
        List<TowerData> extra = new ArrayList<>(EXTRA.values());
        extra.sort(Comparator.comparingInt((TowerData t) -> t.order).thenComparingInt(t -> t.size).thenComparing(t -> t.id));
        out.addAll(extra);
        return out;
    }

    public static List<TowerData> extraTowers() {
        return towers().subList(1, EXTRA.size() + 1);
    }

    /** Nombre d'etages a gravir. */
    public static int size(TowerData t) {
        return isMain(t) ? TowerManager.totalFloors() : Math.max(1, Math.min(200, t.size));
    }

    public static String name(TowerData t) {
        if (t != null && t.name != null && !t.name.isBlank()) return t.name;
        return isMain(t) ? Cfg.tower.str("name", "Tour de Combat") : t.id;
    }

    public static FloorEntry floor(TowerData t, int n) {
        if (t == null) return null;
        for (FloorEntry fe : t.floors) if (fe.floor == n) return fe;
        return null;
    }

    public static FloorEntry floor(String tower, int n) {
        return floor(tower(tower), n);
    }

    public static void save(TowerData t) {
        if (isMain(t)) {
            saveFloors();
            return;
        }
        write(towersDir().resolve(t.id + ".json"), Json.GSON.toJson(t));
    }

    /** Enregistre la tour de cet etage. */
    public static void saveFloor(FloorEntry fe) {
        TowerData t = fe == null ? null : tower(fe.tower);
        save(t == null ? data : t);
    }

    /** Nouvelle tour secondaire (null si l'identifiant est pris ou invalide). */
    public static TowerData create(String rawId, String name, int size) {
        String id = normalizeId(rawId);
        if (id.isEmpty() || isMain(id) || EXTRA.containsKey(id)) return null;
        TowerData t = new TowerData();
        t.id = id;
        t.name = name == null ? "" : name;
        t.size = size;
        t.order = EXTRA.size() + 1;
        prepare(t);
        EXTRA.put(id, t);
        save(t);
        return t;
    }

    /** Change le nombre d'etages (les etages au-dela sont gardes dans le fichier mais ignores). */
    public static void resize(TowerData t, int size) {
        if (isMain(t)) return;
        t.size = size;
        prepare(t);
        save(t);
    }

    /** Supprime une tour secondaire : son fichier est renomme en .supprimee (recuperable). */
    public static boolean delete(String id) {
        TowerData t = EXTRA.remove(normalizeId(id));
        if (t == null) return false;
        Path f = towersDir().resolve(t.id + ".json");
        try {
            if (Files.exists(f)) Files.move(f, towersDir().resolve(t.id + ".json.supprimee"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Battletower.LOG.error("[Battletower] Suppression de la tour {} : {}", t.id, e.toString());
        }
        return true;
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
        try {
            Files.createDirectories(f.getParent());
        } catch (Exception ignored) {
        }
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
