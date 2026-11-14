package fr.cobblepulse.battletower.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Table des types (generation 9), avec prise en charge du Combat Inverse. Types inconnus = neutres. */
public final class TypeChart {
    public static final List<String> TYPES = List.of("normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison",
            "ground", "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy");
    private static final Map<String, Map<String, Double>> CHART = new HashMap<>();

    static {
        eff("normal", 0.5, "rock", "steel");
        eff("normal", 0, "ghost");
        eff("fire", 2, "grass", "ice", "bug", "steel");
        eff("fire", 0.5, "fire", "water", "rock", "dragon");
        eff("water", 2, "fire", "ground", "rock");
        eff("water", 0.5, "water", "grass", "dragon");
        eff("electric", 2, "water", "flying");
        eff("electric", 0.5, "electric", "grass", "dragon");
        eff("electric", 0, "ground");
        eff("grass", 2, "water", "ground", "rock");
        eff("grass", 0.5, "fire", "grass", "poison", "flying", "bug", "dragon", "steel");
        eff("ice", 2, "grass", "ground", "flying", "dragon");
        eff("ice", 0.5, "fire", "water", "ice", "steel");
        eff("fighting", 2, "normal", "ice", "rock", "dark", "steel");
        eff("fighting", 0.5, "poison", "flying", "psychic", "bug", "fairy");
        eff("fighting", 0, "ghost");
        eff("poison", 2, "grass", "fairy");
        eff("poison", 0.5, "poison", "ground", "rock", "ghost");
        eff("poison", 0, "steel");
        eff("ground", 2, "fire", "electric", "poison", "rock", "steel");
        eff("ground", 0.5, "grass", "bug");
        eff("ground", 0, "flying");
        eff("flying", 2, "grass", "fighting", "bug");
        eff("flying", 0.5, "electric", "rock", "steel");
        eff("psychic", 2, "fighting", "poison");
        eff("psychic", 0.5, "psychic", "steel");
        eff("psychic", 0, "dark");
        eff("bug", 2, "grass", "psychic", "dark");
        eff("bug", 0.5, "fire", "fighting", "poison", "flying", "ghost", "steel", "fairy");
        eff("rock", 2, "fire", "ice", "flying", "bug");
        eff("rock", 0.5, "fighting", "ground", "steel");
        eff("ghost", 2, "psychic", "ghost");
        eff("ghost", 0.5, "dark");
        eff("ghost", 0, "normal");
        eff("dragon", 2, "dragon");
        eff("dragon", 0.5, "steel");
        eff("dragon", 0, "fairy");
        eff("dark", 2, "psychic", "ghost");
        eff("dark", 0.5, "fighting", "dark", "fairy");
        eff("steel", 2, "ice", "rock", "fairy");
        eff("steel", 0.5, "fire", "water", "electric", "steel");
        eff("fairy", 2, "fighting", "dragon", "dark");
        eff("fairy", 0.5, "fire", "poison", "steel");
    }

    private TypeChart() {}

    private static void eff(String atk, double mult, String... defs) {
        Map<String, Double> m = CHART.computeIfAbsent(atk, k -> new HashMap<>());
        for (String d : defs) m.put(d, mult);
    }

    /** Multiplicateur d'un type offensif contre un type defensif. */
    public static double single(String atk, String def, boolean inverse) {
        if (atk == null || def == null) return 1;
        Double v = CHART.getOrDefault(atk.toLowerCase(Locale.ROOT), Map.of()).get(def.toLowerCase(Locale.ROOT));
        double m = v == null ? 1 : v;
        if (!inverse) return m;
        if (m == 1) return 1;
        return m > 1 ? 0.5 : 2; // Inverse : faiblesses <-> resistances, immunites -> faiblesses
    }

    public static double multiplier(String atk, Iterable<String> defTypes, boolean inverse) {
        double m = 1;
        for (String d : defTypes) m *= single(atk, d, inverse);
        return m;
    }

    public static String fr(String type) {
        if (type == null) return "?";
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "normal" -> "Normal";
            case "fire" -> "Feu";
            case "water" -> "Eau";
            case "electric" -> "Electrik";
            case "grass" -> "Plante";
            case "ice" -> "Glace";
            case "fighting" -> "Combat";
            case "poison" -> "Poison";
            case "ground" -> "Sol";
            case "flying" -> "Vol";
            case "psychic" -> "Psy";
            case "bug" -> "Insecte";
            case "rock" -> "Roche";
            case "ghost" -> "Spectre";
            case "dragon" -> "Dragon";
            case "dark" -> "Tenebres";
            case "steel" -> "Acier";
            case "fairy" -> "Fee";
            case "stellar" -> "Stellaire";
            default -> type;
        };
    }
}
