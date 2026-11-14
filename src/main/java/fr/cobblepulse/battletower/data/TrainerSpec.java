package fr.cobblepulse.battletower.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Dresseur PNJ configurable (etage ou Boss du Jour). */
public final class TrainerSpec {
    public enum Ai { EASY, MEDIUM, HARD, EXTREME }

    public String name = "Dresseur";
    public String title = "";
    /** builtin:<nom> (skin embarque), server:<fichier.png> (dossier skins/), player:<pseudo> (skin Mojang). */
    public String skin = "builtin:ace_trainer_m";
    /** "singles" (1v1) ou "doubles" (2v2). */
    public String format = "singles";
    public String tier = "ag";
    public String ai = "EASY";
    /** 0 = desactive, sinon niveau impose a tous les Pokemon le temps du combat. */
    public int levelScale;
    public boolean inverse;
    /** none, sun, rain, sand, snow, fog */
    public String weather = "none";
    /** none, electric, grassy, psychic, misty */
    public String terrain = "none";
    public String introMessage = "";
    public String defeatMessage = "";
    public List<MonSpec> team = new ArrayList<>();

    public Ai aiLevel() {
        try {
            return Ai.valueOf(ai == null ? "EASY" : ai.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Ai.EASY;
        }
    }

    public boolean doubles() {
        return "doubles".equalsIgnoreCase(format) || "duo".equalsIgnoreCase(format) || "2v2".equalsIgnoreCase(format);
    }

    public void normalize() {
        if (name == null || name.isBlank()) name = "Dresseur";
        if (title == null) title = "";
        if (skin == null || skin.isBlank()) skin = "builtin:ace_trainer_m";
        format = doubles() ? "doubles" : "singles";
        if (tier == null || tier.isBlank()) tier = "ag";
        tier = tier.toLowerCase(Locale.ROOT).trim();
        ai = aiLevel().name();
        if (levelScale < 0 || levelScale > 100) levelScale = 0;
        weather = norm(weather, List.of("none", "sun", "rain", "sand", "snow", "fog"));
        terrain = norm(terrain, List.of("none", "electric", "grassy", "psychic", "misty"));
        if (introMessage == null) introMessage = "";
        if (defeatMessage == null) defeatMessage = "";
        if (team == null) team = new ArrayList<>();
        team.removeIf(m -> m == null || m.species == null || m.species.isBlank());
        while (team.size() > 6) team.remove(team.size() - 1);
        for (MonSpec m : team) m.normalize();
    }

    private static String norm(String v, List<String> allowed) {
        String s = v == null ? "none" : v.toLowerCase(Locale.ROOT).trim();
        return allowed.contains(s) ? s : "none";
    }

    public String display() {
        return title == null || title.isBlank() ? name : title + " " + name;
    }
}
