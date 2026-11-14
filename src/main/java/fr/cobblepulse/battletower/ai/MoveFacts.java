package fr.cobblepulse.battletower.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Connaissances tactiques sur les capacites (identifiants Showdown). */
public final class MoveFacts {
    private MoveFacts() {}

    /** Boosts infliges a soi-meme : stat -> etages (atk, def, spa, spd, spe). */
    public static final Map<String, Map<String, Integer>> SETUP = new HashMap<>();

    static {
        setup("swordsdance", "atk", 2);
        setup("nastyplot", "spa", 2);
        setup("dragondance", "atk", 1, "spe", 1);
        setup("calmmind", "spa", 1, "spd", 1);
        setup("bulkup", "atk", 1, "def", 1);
        setup("quiverdance", "spa", 1, "spd", 1, "spe", 1);
        setup("shellsmash", "atk", 2, "spa", 2, "spe", 2, "def", -1, "spd", -1);
        setup("shiftgear", "atk", 1, "spe", 2);
        setup("agility", "spe", 2);
        setup("rockpolish", "spe", 2);
        setup("autotomize", "spe", 2);
        setup("irondefense", "def", 2);
        setup("amnesia", "spd", 2);
        setup("cosmicpower", "def", 1, "spd", 1);
        setup("coil", "atk", 1, "def", 1);
        setup("curse", "atk", 1, "def", 1, "spe", -1);
        setup("growth", "atk", 1, "spa", 1);
        setup("workup", "atk", 1, "spa", 1);
        setup("honeclaws", "atk", 1);
        setup("howl", "atk", 1);
        setup("tailglow", "spa", 3);
        setup("geomancy", "spa", 2, "spd", 2, "spe", 2);
        setup("victorydance", "atk", 1, "def", 1, "spe", 1);
        setup("tidyup", "atk", 1, "spe", 1);
        setup("filletaway", "atk", 2, "spa", 2, "spe", 2);
        setup("bellydrum", "atk", 6);
        setup("noretreat", "atk", 1, "def", 1, "spa", 1, "spd", 1, "spe", 1);
        setup("clangoroussoul", "atk", 1, "def", 1, "spa", 1, "spd", 1, "spe", 1);
        setup("takeheart", "spa", 1, "spd", 1);
        setup("acidarmor", "def", 2);
        setup("barrier", "def", 2);
        setup("defendorder", "def", 1, "spd", 1);
        setup("stockpile", "def", 1, "spd", 1);
    }

    private static void setup(String id, Object... kv) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        SETUP.put(id, m);
    }

    public static final Set<String> HAZARDS = Set.of("stealthrock", "spikes", "toxicspikes", "stickyweb", "stoneaxe", "ceaselessedge");
    public static final Set<String> HAZARD_REMOVAL = Set.of("defog", "rapidspin", "mortalspin", "tidyup", "courtchange");
    public static final Set<String> RAPID_SPIN_LIKE = Set.of("rapidspin", "mortalspin", "tidyup");
    public static final Set<String> PIVOTS = Set.of("uturn", "voltswitch", "flipturn", "partingshot", "teleport", "chillyreception", "shedtail");
    public static final Set<String> SCREENS = Set.of("reflect", "lightscreen", "auroraveil");
    public static final Set<String> RECOVERY = Set.of("recover", "roost", "softboiled", "milkdrink", "slackoff", "synthesis", "moonlight",
            "morningsun", "shoreup", "wish", "healorder", "strengthsap", "lifedew", "junglehealing", "lunarblessing");
    public static final Set<String> PROTECT = Set.of("protect", "detect", "kingsshield", "spikyshield", "banefulbunker", "silktrap",
            "obstruct", "burningbulwark", "maxguard");
    public static final Set<String> PRIORITY_KILL = Set.of("suckerpunch", "extremespeed", "iceshard", "aquajet", "machpunch", "bulletpunch",
            "shadowsneak", "quickattack", "vacuumwave", "accelerock", "jetpunch", "grassyglide", "firstimpression", "thunderclap", "watershuriken");
    public static final Set<String> TRICK_ROOM = Set.of("trickroom");
    public static final Set<String> TAILWIND = Set.of("tailwind");
    public static final Set<String> OHKO = Set.of("fissure", "guillotine", "horndrill", "sheercold");
    public static final Set<String> SELF_KO = Set.of("explosion", "selfdestruct", "memento", "finalgambit", "healingwish", "lunardance", "mistyexplosion");
    public static final Set<String> RECHARGE = Set.of("hyperbeam", "gigaimpact", "rockwrecker", "blastburn", "frenzyplant", "hydrocannon",
            "roaroftime", "prismaticlaser", "eternabeam", "meteorassault");
    /** Capacites de statut : id -> statut inflige (brn, par, psn, tox, slp, frz, confusion). */
    public static final Map<String, String> STATUS = Map.ofEntries(
            Map.entry("willowisp", "brn"), Map.entry("thunderwave", "par"), Map.entry("stunspore", "par"), Map.entry("glare", "par"),
            Map.entry("nuzzle", "par"), Map.entry("toxic", "tox"), Map.entry("poisonpowder", "psn"), Map.entry("toxicthread", "psn"),
            Map.entry("spore", "slp"), Map.entry("sleeppowder", "slp"), Map.entry("hypnosis", "slp"), Map.entry("yawn", "slp"),
            Map.entry("lovelykiss", "slp"), Map.entry("sing", "slp"), Map.entry("darkvoid", "slp"), Map.entry("grasswhistle", "slp"),
            Map.entry("confuseray", "confusion"), Map.entry("swagger", "confusion"), Map.entry("supersonic", "confusion"));
    /** Fraction de recul des degats infliges. */
    public static final Map<String, Double> RECOIL = Map.ofEntries(
            Map.entry("bravebird", 1 / 3.0), Map.entry("flareblitz", 1 / 3.0), Map.entry("woodhammer", 1 / 3.0), Map.entry("doubleedge", 1 / 3.0),
            Map.entry("volttackle", 1 / 3.0), Map.entry("headsmash", 0.5), Map.entry("wavecrash", 1 / 3.0), Map.entry("takedown", 0.25),
            Map.entry("submission", 0.25), Map.entry("wildcharge", 0.25), Map.entry("headcharge", 0.25), Map.entry("lightofruin", 0.5),
            Map.entry("chloroblast", 0.5), Map.entry("steelbeam", 0.5));
    /** Fraction de soin des degats infliges. */
    public static final Map<String, Double> DRAIN = Map.ofEntries(
            Map.entry("drainpunch", 0.5), Map.entry("gigadrain", 0.5), Map.entry("hornleech", 0.5), Map.entry("leechlife", 0.5),
            Map.entry("drainingkiss", 0.75), Map.entry("paraboliccharge", 0.5), Map.entry("oblivionwing", 0.75), Map.entry("bitterblade", 0.5),
            Map.entry("matchagotcha", 0.5), Map.entry("absorb", 0.5), Map.entry("megadrain", 0.5));
    /** Nombre moyen de coups des capacites multi-coups. */
    public static final Map<String, Double> MULTI_HIT = Map.ofEntries(
            Map.entry("bulletseed", 3.1), Map.entry("iciclespear", 3.1), Map.entry("rockblast", 3.1), Map.entry("pinmissile", 3.1),
            Map.entry("tailslap", 3.1), Map.entry("scaleshot", 3.1), Map.entry("watershuriken", 3.1), Map.entry("armthrust", 3.1),
            Map.entry("furyswipes", 3.1), Map.entry("doublehit", 2.0), Map.entry("dualwingbeat", 2.0), Map.entry("doublekick", 2.0),
            Map.entry("dragondarts", 2.0), Map.entry("geargrind", 2.0), Map.entry("twinbeam", 2.0), Map.entry("bonemerang", 2.0),
            Map.entry("tripleaxel", 2.7), Map.entry("surgingstrikes", 3.0), Map.entry("populationbomb", 6.0), Map.entry("tachyoncutter", 2.0));
    /** Degats fixes egaux au niveau. */
    public static final Set<String> LEVEL_DAMAGE = Set.of("seismictoss", "nightshade");
    public static final Set<String> HALF_HP = Set.of("superfang", "naturesmadness", "ruination");
    /** Capacites ignorant les capacites de protection de type (utilisees pour eviter les mauvais choix). */
    public static final Set<String> NEVER_USE = Set.of("struggle", "splash", "celebrate", "holdhands", "happyhour");
    public static final Map<String, String> WEATHER_MOVES = Map.of("raindance", "rain", "sunnyday", "sun", "sandstorm", "sand",
            "snowscape", "snow", "hail", "snow", "chillyreception", "snow");

    public static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
