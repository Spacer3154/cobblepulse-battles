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
    /** Dresseur hors Tour : recompenses et revanche (null pour les dresseurs de la Tour). */
    public WorldOptions world;

    /** Reglages d'un dresseur pose dans le monde (CobblePulse-Battles). */
    public static final class WorldOptions {
        /** normal : equipe reelle du joueur (EXP, PV et objets conserves) ; exhibition : copie soignee, sans gain. */
        public String mode = "normal";
        /** Heures avant une revanche apres une victoire du joueur (-1 = jamais, 0 = immediate). */
        public int rematchHours = 24;
        /** Minutes d'attente apres une defaite du joueur (0 = immediate). */
        public int lossCooldownMinutes = 0;
        /** Recompenses aussi lors des revanches gagnees. */
        public boolean rematchRewards = false;
        /** Battle Points de la Tour donnes a la victoire. */
        public int bp = 0;
        /** Objets donnes ("cobblemon:rare_candy 3"). */
        public List<String> items = new ArrayList<>();
        /** Commandes console (%player%). */
        public List<String> commands = new ArrayList<>();
        /** Phrase du dresseur quand il gagne. */
        public String winMessage = "";

        public void normalize() {
            mode = "exhibition".equalsIgnoreCase(mode) ? "exhibition" : "normal";
            if (rematchHours < -1) rematchHours = -1;
            if (lossCooldownMinutes < 0) lossCooldownMinutes = 0;
            if (bp < 0) bp = 0;
            if (items == null) items = new ArrayList<>();
            if (commands == null) commands = new ArrayList<>();
            items.removeIf(i -> i == null || i.isBlank());
            commands.removeIf(c -> c == null || c.isBlank());
            if (winMessage == null) winMessage = "";
        }
    }

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
        if (world != null) world.normalize();
    }

    private static String norm(String v, List<String> allowed) {
        String s = v == null ? "none" : v.toLowerCase(Locale.ROOT).trim();
        return allowed.contains(s) ? s : "none";
    }

    public String display() {
        return title == null || title.isBlank() ? name : title + " " + name;
    }
}
