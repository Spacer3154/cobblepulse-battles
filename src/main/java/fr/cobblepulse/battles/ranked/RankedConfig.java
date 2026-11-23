package fr.cobblepulse.battles.ranked;

import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.config.TomlFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fichiers de configuration de la Ranked (config/cobblepulse_battles/ranked/). */
public final class RankedConfig {
    public static TomlFile general, elo, formats, seasons, rewards, missions, restrictions, network;
    public static final List<Rank> RANKS = new ArrayList<>();
    public static final Set<String> RESTRICTED = new LinkedHashSet<>();
    public static final Set<String> OHKO = new LinkedHashSet<>();
    public static final Set<String> EVASION = new LinkedHashSet<>();
    public static final Set<String> SLEEP = new LinkedHashSet<>();
    public static final List<String> FORMATS = List.of("simple", "double");

    public record Rank(String id, String name, int minElo, String icon) {}

    private RankedConfig() {}

    public static void load() {
        Path root = Cfg.root();
        general = TomlFile.load(root, "ranked/ranked.toml");
        elo = TomlFile.load(root, "ranked/elo.toml");
        formats = TomlFile.load(root, "ranked/formats.toml");
        seasons = TomlFile.load(root, "ranked/saisons.toml");
        rewards = TomlFile.load(root, "ranked/recompenses.toml");
        missions = TomlFile.load(root, "ranked/missions.toml");
        restrictions = TomlFile.load(root, "ranked/restrictions.toml");
        network = TomlFile.load(root, "ranked/reseau.toml");
        RANKS.clear();
        for (TomlFile t : elo.tables("rang")) {
            RANKS.add(new Rank(t.str("id", "rang").toLowerCase(Locale.ROOT), t.str("nom", t.str("id", "?")), t.i("elo_min", 0),
                    t.str("icone", "cobblemon:poke_ball")));
        }
        if (RANKS.isEmpty()) RANKS.add(new Rank("pokeball", "&cPoké Ball", 0, "cobblemon:poke_ball"));
        RANKS.sort(Comparator.comparingInt(Rank::minElo));
        fill(RESTRICTED, formats.list("restreints"));
        fill(OHKO, formats.list("capacites_ohko"));
        fill(EVASION, formats.list("capacites_esquive"));
        fill(SLEEP, formats.list("capacites_sommeil"));
    }

    private static void fill(Set<String> set, List<String> values) {
        set.clear();
        for (String s : values) set.add(FormatRules.norm(s));
    }

    public static boolean enabled() {
        return general != null && general.b("actif", true);
    }

    public static Rank rankFor(int elo) {
        Rank best = RANKS.get(0);
        for (Rank r : RANKS) if (elo >= r.minElo()) best = r;
        return best;
    }

    public static Rank rank(String id) {
        for (Rank r : RANKS) if (r.id().equalsIgnoreCase(id)) return r;
        return null;
    }

    public static int startElo() {
        return elo.i("elo_depart", 1000);
    }

    public static int floorElo() {
        return elo.i("elo_plancher", 1000);
    }

    /** Regles du format pour le preset de la saison en cours (preset "default" hors saison). */
    public static FormatRules rules(String format) {
        String f = normalizeFormat(format);
        if (f == null) return null;
        String preset = Seasons.presetId();
        if (!formats.has(preset + "." + f)) preset = "default";
        return new FormatRules(f, formats.sub(preset + "." + f));
    }

    /** simple / double (accepte aussi singles, doubles, s, d). */
    public static String normalizeFormat(String s) {
        if (s == null) return null;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "simple", "singles", "single", "s", "solo" -> "simple";
            case "double", "doubles", "d", "duo" -> "double";
            default -> null;
        };
    }

    public static String presetName(String preset) {
        return formats.str(preset + ".nom", preset);
    }
}
