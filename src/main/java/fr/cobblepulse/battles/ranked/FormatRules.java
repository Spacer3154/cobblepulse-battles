package fr.cobblepulse.battles.ranked;

import fr.cobblepulse.battletower.config.TomlFile;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Regles d'un format (simple / double) du preset de la saison en cours. */
public final class FormatRules {
    public final String id;
    public final boolean enabled;
    public final String name;
    public final boolean doubles;
    public final int teamVisible;
    public final int teamSize;
    public final int level;
    public final int turnTimer;
    public final int matchMinutes;
    public final boolean mega, tera, z, dynamax;
    public final boolean speciesClause, ohkoClause, evasionClause, sleepClause, limitSleepMoves;
    public final int itemClause;
    public final int maxRestricted;
    public final String tier;
    public final String special;
    public final int dexMin, dexMax;
    public final Set<String> bannedSpecies = new LinkedHashSet<>();
    public final Set<String> bannedMoves = new LinkedHashSet<>();
    public final Set<String> bannedAbilities = new LinkedHashSet<>();
    public final Set<String> bannedItems = new LinkedHashSet<>();
    // matchmaking
    public final boolean eloRange;
    public final int initialRange, expansionDelay, expansionStep, immediateRange;
    public final double maxMultiplier;

    FormatRules(String id, TomlFile t) {
        this.id = id;
        this.doubles = id.equals("double");
        enabled = t.b("actif", true);
        name = t.str("nom", doubles ? "Double" : "Simple");
        teamVisible = clamp(t.i("equipe_visible", 6), 1, 6);
        teamSize = clamp(t.i("equipe_choisie", doubles ? 4 : 3), doubles ? 2 : 1, 6);
        level = clamp(t.i("niveau", 50), 0, 100);
        turnTimer = Math.max(0, t.i("minuteur_tour", 90));
        matchMinutes = Math.max(0, t.i("duree_match", 15));
        mega = t.b("mega", true);
        tera = t.b("tera", false);
        z = t.b("z", true);
        dynamax = t.b("dynamax", false);
        speciesClause = t.b("clause_espece", true);
        itemClause = Math.max(0, t.i("clause_objet", 1));
        ohkoClause = t.b("clause_ohko", true);
        evasionClause = t.b("clause_esquive", true);
        sleepClause = t.b("clause_sommeil", true);
        limitSleepMoves = t.b("limite_capacites_sommeil", false);
        maxRestricted = t.i("max_restreints", 1);
        tier = t.str("tier", "").trim().toLowerCase(Locale.ROOT);
        special = t.str("format_special", "aucun").trim().toLowerCase(Locale.ROOT);
        dexMin = t.i("dex_min", 1);
        dexMax = t.i("dex_max", 1025);
        for (String s : t.list("especes_interdites")) bannedSpecies.add(norm(s));
        for (String s : t.list("capacites_interdites")) bannedMoves.add(norm(s));
        for (String s : t.list("talents_interdits")) bannedAbilities.add(norm(s));
        for (String s : t.list("objets_interdits")) bannedItems.add(s.trim().toLowerCase(Locale.ROOT));
        eloRange = t.b("matchmaking.ecart_elo", true);
        initialRange = Math.max(0, t.i("matchmaking.ecart_initial", 200));
        expansionDelay = Math.max(1, t.i("matchmaking.delai_elargissement", 30));
        expansionStep = Math.max(0, t.i("matchmaking.pas_elargissement", 50));
        maxMultiplier = Math.max(1.0, t.d("matchmaking.multiplicateur_max", 3.0));
        immediateRange = Math.max(0, t.i("matchmaking.ecart_immediat", 100));
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Ecart d'Elo accepte apres {@code waitedSec} secondes de file. */
    public int rangeAfter(long waitedSec) {
        if (!eloRange) return Integer.MAX_VALUE;
        long steps = waitedSec < expansionDelay ? 0 : (waitedSec / expansionDelay);
        double r = initialRange + steps * (double) expansionStep;
        return (int) Math.min(r, initialRange * maxMultiplier);
    }
}
