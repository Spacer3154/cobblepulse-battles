package fr.cobblepulse.battletower.config;

import fr.cobblepulse.battletower.Battletower;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Acces centralise aux fichiers de config/cobblepulse_battletower/. */
public final class Cfg {
    public static final List<String> DEFAULT_TIERS = List.of("ag", "uber", "ou", "uu", "ru", "pu", "zu", "monotype", "nfe", "lc");

    public static TomlFile storage;
    public static TomlFile tower;
    public static TomlFile rewards;
    public static TomlFile daily;
    public static TomlFile shop;
    public static TomlFile achievements;
    public static TomlFile messages;
    public static final Map<String, TierRules> TIERS = new LinkedHashMap<>();
    /** Dossier d'ou viennent les tiers (CobblePulse si present, sinon Battletower). */
    public static String tiersSource = "";

    private Cfg() {}

    public static Path root() {
        return FabricLoader.getInstance().getConfigDir().resolve("cobblepulse_battletower");
    }

    public static Path dataDir() {
        return root().resolve("data");
    }

    public static Path skinsDir() {
        return root().resolve("skins");
    }

    public static Path cobblePulseRoot() {
        return FabricLoader.getInstance().getConfigDir().resolve("cobblepulse");
    }

    public static void loadAll() {
        Path root = root();
        storage = TomlFile.load(root, "storage.toml");
        tower = TomlFile.load(root, "tower.toml");
        rewards = TomlFile.load(root, "rewards.toml");
        daily = TomlFile.load(root, "daily_boss.toml");
        shop = TomlFile.load(root, "shop.toml");
        achievements = TomlFile.load(root, "achievements.toml");
        messages = TomlFile.load(root, "messages.toml");
        try {
            Files.createDirectories(skinsDir());
            Files.createDirectories(dataDir().resolve("players"));
        } catch (Exception e) {
            Battletower.LOG.warn("[Battletower] Creation des dossiers impossible : {}", e.toString());
        }
        loadTiers(root);
    }

    public static int floors() {
        return Math.max(1, Math.min(200, tower.i("floors", 50)));
    }

    /**
     * Tiers : ceux de CobblePulse (config/cobblepulse/tournament_tiers) s'ils existent, pour partager les memes banlists ;
     * sinon les fichiers de config/cobblepulse_battletower/tiers.
     */
    private static void loadTiers(Path root) {
        TIERS.clear();
        Map<String, TomlFile> files = new LinkedHashMap<>();
        Path cp = cobblePulseRoot().resolve("tournament_tiers");
        boolean shared = Files.isDirectory(cp);
        if (shared) {
            tiersSource = "config/cobblepulse/tournament_tiers";
            listToml(cp, files);
        } else {
            tiersSource = "config/cobblepulse_battletower/tiers";
            for (String t : DEFAULT_TIERS) files.put(t, TomlFile.load(root, "tiers/" + t + ".toml"));
            listToml(root.resolve("tiers"), files);
        }
        if (files.isEmpty()) {
            for (String t : DEFAULT_TIERS) files.put(t, TomlFile.load(root, "tiers/" + t + ".toml"));
        }
        for (Map.Entry<String, TomlFile> e : files.entrySet()) TIERS.put(e.getKey(), TierRules.from(e.getKey(), e.getValue()));
        for (TierRules r : TIERS.values()) r.resolveInheritance(TIERS);
        Battletower.LOG.info("[Battletower] {} tiers charges depuis {}", TIERS.size(), tiersSource);
    }

    private static void listToml(Path dir, Map<String, TomlFile> files) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".toml")).sorted().forEach(p -> {
                String id = p.getFileName().toString().replace(".toml", "").toLowerCase(Locale.ROOT);
                if (!files.containsKey(id)) files.put(id, TomlFile.loadPlain(p));
            });
        } catch (Exception e) {
            Battletower.LOG.warn("[Battletower] Lecture de {} impossible : {}", dir, e.toString());
        }
    }

    public static TierRules tier(String id) {
        if (id == null || id.isBlank()) return null;
        return TIERS.get(id.toLowerCase(Locale.ROOT).trim());
    }
}
