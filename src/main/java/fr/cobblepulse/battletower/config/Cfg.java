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

    public static TomlFile general;
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
        return FabricLoader.getInstance().getConfigDir().resolve("cobblepulse_battles");
    }

    /** Ancien dossier de Battletower 1.x, deplace une fois vers {@link #root()}. */
    public static Path legacyRoot() {
        return FabricLoader.getInstance().getConfigDir().resolve("cobblepulse_battletower");
    }

    /**
     * Migration Battletower -> CobblePulse-Battles : le dossier config/cobblepulse_battletower (reglages, donnees
     * JSON/SQLite, skins) devient config/cobblepulse_battles. Rien n'est ecrase si le nouveau dossier existe deja.
     */
    private static void migrateLegacy() {
        Path old = legacyRoot();
        Path now = root();
        if (!Files.isDirectory(old) || Files.exists(now)) return;
        try {
            Files.move(old, now);
            Battletower.LOG.info("[Battles] Configuration de Battletower deplacee : {} -> {}", old, now);
        } catch (Exception moveFailed) {
            try (var walk = Files.walk(old)) {
                for (Path src : (Iterable<Path>) walk::iterator) {
                    Path dst = now.resolve(old.relativize(src).toString());
                    if (Files.isDirectory(src)) Files.createDirectories(dst);
                    else Files.copy(src, dst);
                }
                Files.writeString(old.resolve("DEPLACE_VERS_cobblepulse_battles.txt"),
                        "Ces fichiers ont ete copies dans config/cobblepulse_battles (CobblePulse-Battles). Ce dossier n'est plus lu.\n");
                Battletower.LOG.info("[Battles] Configuration de Battletower copiee vers {}", now);
            } catch (Exception e) {
                Battletower.LOG.error("[Battles] Migration de {} impossible : {}", old, e.toString());
            }
        }
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
        migrateLegacy();
        Path root = root();
        general = TomlFile.load(root, "general.toml");
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
            tiersSource = "config/cobblepulse_battles/tiers";
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
