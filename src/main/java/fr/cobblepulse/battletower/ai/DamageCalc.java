package fr.cobblepulse.battletower.ai;

import fr.cobblepulse.battletower.ai.Model.AField;
import fr.cobblepulse.battletower.ai.Model.AMon;
import fr.cobblepulse.battletower.ai.Model.AMove;
import fr.cobblepulse.battletower.ai.Model.ASide;

import java.util.Set;

/**
 * Moteur de calcul de degats (formule des generations 5 a 9) : niveau, statistiques et boosts, IV/EV et nature
 * (via les statistiques), STAB et Tera, efficacite (Inverse compris), meteo, terrain, ecrans, brulure, objets et
 * talents courants. Retourne des degats en PV.
 */
public final class DamageCalc {
    private static final Set<String> CONTACT_FREE_SPECIAL_PHYS = Set.of("psyshock", "psystrike", "secretsword");
    private static final Set<String> GROUND_HALVED_BY_GRASSY = Set.of("earthquake", "bulldoze", "magnitude");

    private DamageCalc() {}

    public record Range(double min, double avg, double max, double effectiveness) {
        public static final Range ZERO = new Range(0, 0, 0, 0);
    }

    public static double stageMult(int stage) {
        stage = Math.max(-6, Math.min(6, stage));
        return stage >= 0 ? (2.0 + stage) / 2.0 : 2.0 / (2.0 - stage);
    }

    /** Type effectif d'une capacite (Ball'Meteo, Tera Blast, Judgment non gere). */
    public static String moveType(AMon a, AMove m, AField f) {
        if ("weatherball".equals(m.id)) {
            return switch (f.weather) {
                case "sun" -> "fire";
                case "rain" -> "water";
                case "sand" -> "rock";
                case "snow" -> "ice";
                default -> "normal";
            };
        }
        if ("terrainpulse".equals(m.id) && a.grounded(f.gravity ? "gravity" : "")) {
            return switch (f.terrain) {
                case "electric" -> "electric";
                case "grassy" -> "grass";
                case "psychic" -> "psychic";
                case "misty" -> "fairy";
                default -> "normal";
            };
        }
        if ("terablast".equals(m.id) && a.terastallized && !a.teraType.isEmpty() && !"stellar".equals(a.teraType)) return a.teraType;
        return m.type;
    }

    /** Efficacite totale (types + immunites de talents/objets connus). */
    public static double effectiveness(AMon a, AMon d, AMove m, AField f) {
        String type = moveType(a, m, f);
        double eff = TypeChart.multiplier(type, d.effectiveTypes(), f.inverse);
        if ("freezedry".equals(m.id) && d.effectiveTypes().contains("water")) eff *= f.inverse ? 0.25 : 4;
        if ("thousandarrows".equals(m.id) && d.effectiveTypes().contains("flying") && eff == 0) eff = 1;
        boolean moldBreaker = a.hasAbility("moldbreaker") || a.hasAbility("teravolt") || a.hasAbility("turboblaze");
        if (!moldBreaker) {
            if ("ground".equals(type) && !"thousandarrows".equals(m.id) && !d.grounded(f.gravity ? "gravity" : "")) return 0;
            if ("fire".equals(type) && (d.hasAbility("flashfire") || d.hasAbility("wellbakedbody"))) return 0;
            if ("water".equals(type) && (d.hasAbility("waterabsorb") || d.hasAbility("stormdrain") || d.hasAbility("dryskin"))) return 0;
            if ("electric".equals(type) && (d.hasAbility("voltabsorb") || d.hasAbility("lightningrod") || d.hasAbility("motordrive"))) return 0;
            if ("grass".equals(type) && d.hasAbility("sapsipper")) return 0;
            if ("ground".equals(type) && d.hasAbility("eartheater")) return 0;
            if (d.hasAbility("wonderguard") && eff <= 1) return 0;
            if (d.hasAbility("bulletproof") && Set.of("shadowball", "sludgebomb", "focusblast", "energyball", "aurasphere", "seedbomb",
                    "weatherball", "electroball", "gyroball", "pyroball", "mudbomb", "zapcannon").contains(m.id)) return 0;
            if (d.hasAbility("soundproof") && Set.of("boomburst", "hypervoice", "bugbuzz", "overdrive", "sparklingaria", "torchsong",
                    "clangingscales", "disarmingvoice", "snarl", "uproar", "echoedvoice").contains(m.id)) return 0;
        }
        if ("psychic".equals(f.terrain) && m.priority > 0 && d.grounded(f.gravity ? "gravity" : "") && !m.isStatus()) return 0;
        return eff;
    }

    public static Range damage(AMon a, AMon d, AMove m, AField f, ASide atkSide, ASide defSide) {
        if (a == null || d == null || m == null || m.isStatus()) return Range.ZERO;
        double eff = effectiveness(a, d, m, f);
        if (eff == 0) return new Range(0, 0, 0, 0);
        if (MoveFacts.LEVEL_DAMAGE.contains(m.id)) {
            double v = Math.min(d.hp, a.level);
            return new Range(v, v, v, eff);
        }
        if (MoveFacts.HALF_HP.contains(m.id)) {
            double v = Math.max(1, Math.floor(d.hp / 2.0));
            return new Range(v, v, v, eff);
        }
        if (MoveFacts.OHKO.contains(m.id)) {
            double v = d.level > a.level ? 0 : d.hp;
            return new Range(v, v, v, eff);
        }
        String type = moveType(a, m, f);
        double power = basePower(a, d, m, f);
        if (power <= 0) return new Range(0, 0, 0, eff);
        boolean physical = m.physical();
        double atkStat;
        int atkStage;
        if ("bodypress".equals(m.id)) {
            atkStat = a.def;
            atkStage = a.boost("def");
        } else if ("foulplay".equals(m.id)) {
            atkStat = d.atk;
            atkStage = d.boost("atk");
        } else if (physical) {
            atkStat = a.atk;
            atkStage = a.boost("atk");
        } else {
            atkStat = a.spa;
            atkStage = a.boost("spa");
        }
        if (d.hasAbility("unaware")) atkStage = 0;
        double atk = atkStat * stageMult(atkStage);
        boolean targetsDef = physical || CONTACT_FREE_SPECIAL_PHYS.contains(m.id);
        double defStat = targetsDef ? d.def : d.spd;
        int defStage = targetsDef ? d.boost("def") : d.boost("spd");
        if (a.hasAbility("unaware")) defStage = 0;
        double def = defStat * stageMult(defStage);
        // Modificateurs de statistiques
        if (physical && (a.hasAbility("hugepower") || a.hasAbility("purepower"))) atk *= 2;
        if (physical && a.hasAbility("hustle")) atk *= 1.5;
        if (physical && a.hasAbility("guts") && !a.status.isEmpty()) atk *= 1.5;
        if (!physical && a.hasAbility("solarpower") && "sun".equals(f.weather)) atk *= 1.5;
        if (physical && a.hasItem("choiceband")) atk *= 1.5;
        if (!physical && a.hasItem("choicespecs")) atk *= 1.5;
        if (a.hasAbility("gorillatactics") && physical) atk *= 1.5;
        if (!targetsDef && d.hasItem("assaultvest")) def *= 1.5;
        if (d.nfe && d.hasItem("eviolite")) def *= 1.5;
        if (targetsDef && d.hasAbility("furcoat")) def *= 2;
        if (!targetsDef && "sand".equals(f.weather) && d.effectiveTypes().contains("rock")) def *= 1.5;
        if (targetsDef && "snow".equals(f.weather) && d.effectiveTypes().contains("ice")) def *= 1.5;
        def = Math.max(1, def);
        double base = Math.floor(Math.floor(Math.floor(2.0 * a.level / 5.0 + 2) * power * atk / def) / 50.0) + 2;
        double mod = 1;
        if (f.doubles && m.spread()) mod *= 0.75;
        if ("rain".equals(f.weather)) {
            if ("water".equals(type)) mod *= 1.5;
            else if ("fire".equals(type) && !"hydrosteam".equals(m.id)) mod *= 0.5;
        } else if ("sun".equals(f.weather)) {
            if ("fire".equals(type) || "hydrosteam".equals(m.id)) mod *= 1.5;
            else if ("water".equals(type)) mod *= 0.5;
        }
        // STAB
        boolean origStab = a.types.contains(type);
        boolean teraStab = a.terastallized && type.equals(a.teraType);
        double stab = 1;
        if (origStab || teraStab) stab = a.hasAbility("adaptability") ? 2 : 1.5;
        if (origStab && teraStab) stab = a.hasAbility("adaptability") ? 2.25 : 2;
        mod *= stab * eff;
        if (physical && "brn".equals(a.status) && !a.hasAbility("guts") && !"facade".equals(m.id)) mod *= 0.5;
        // Ecrans
        boolean screen = defSide != null && (defSide.auroraVeil || (physical ? defSide.reflect : defSide.lightScreen));
        if (screen && !"brickbreak".equals(m.id) && !"psychicfangs".equals(m.id)) mod *= f.doubles ? 2732.0 / 4096 : 0.5;
        // Terrain
        boolean aGround = a.grounded(f.gravity ? "gravity" : "");
        boolean dGround = d.grounded(f.gravity ? "gravity" : "");
        if (aGround) {
            if ("electric".equals(f.terrain) && "electric".equals(type)) mod *= 1.3;
            if ("grassy".equals(f.terrain) && "grass".equals(type)) mod *= 1.3;
            if ("psychic".equals(f.terrain) && "psychic".equals(type)) mod *= 1.3;
        }
        if (dGround && "misty".equals(f.terrain) && "dragon".equals(type)) mod *= 0.5;
        if (dGround && "grassy".equals(f.terrain) && GROUND_HALVED_BY_GRASSY.contains(m.id)) mod *= 0.5;
        // Objets / talents
        if (a.hasItem("lifeorb")) mod *= 1.3;
        if (a.hasItem("expertbelt") && eff > 1) mod *= 1.2;
        if (a.hasAbility("tintedlens") && eff < 1) mod *= 2;
        if (a.hasAbility("sniper")) mod *= 1.0;
        if (d.hasAbility("multiscale") || d.hasAbility("shadowshield")) {
            if (d.hp >= d.maxHp) mod *= 0.5;
        }
        if ((d.hasAbility("filter") || d.hasAbility("solidrock") || d.hasAbility("prismarmor")) && eff > 1) mod *= 0.75;
        if (d.hasAbility("thickfat") && ("fire".equals(type) || "ice".equals(type))) mod *= 0.5;
        if (d.hasAbility("heatproof") && "fire".equals(type)) mod *= 0.5;
        if (d.hasAbility("purifyingsalt") && "ghost".equals(type)) mod *= 0.5;
        if (d.hasAbility("icescales") && !physical) mod *= 0.5;
        if (d.hasAbility("waterbubble") && "fire".equals(type)) mod *= 0.5;
        if (a.hasAbility("waterbubble") && "water".equals(type)) mod *= 2;
        if (a.hasAbility("steelworker") && "steel".equals(type)) mod *= 1.5;
        if (a.hasAbility("transistor") && "electric".equals(type)) mod *= 1.3;
        if (a.hasAbility("dragonsmaw") && "dragon".equals(type)) mod *= 1.5;
        double hits = MoveFacts.MULTI_HIT.getOrDefault(m.id, 1.0);
        if (hits > 1 && a.hasAbility("skilllink")) hits = "tripleaxel".equals(m.id) ? 3 : Math.max(hits, 5);
        double max = Math.floor(base * mod) * hits;
        double min = Math.floor(base * mod * 0.85) * hits;
        double avg = (min + max) / 2;
        if (d.hasItem("focussash") && d.hp >= d.maxHp && hits <= 1) {
            min = Math.min(min, d.hp - 1);
            avg = Math.min(avg, d.hp - 1);
            max = Math.min(max, d.hp - 1);
        }
        if (d.hasAbility("sturdy") && d.hp >= d.maxHp && hits <= 1) {
            min = Math.min(min, d.hp - 1);
            avg = Math.min(avg, d.hp - 1);
            max = Math.min(max, d.hp - 1);
        }
        return new Range(Math.max(1, min), Math.max(1, avg), Math.max(1, max), eff);
    }

    static double basePower(AMon a, AMon d, AMove m, AField f) {
        double p = m.power;
        switch (m.id) {
            case "knockoff" -> {
                if (!d.itemKnown || !d.item.isEmpty()) p *= 1.5;
            }
            case "facade" -> {
                if (!a.status.isEmpty()) p *= 2;
            }
            case "hex", "infernalparade", "bittermalice" -> {
                if (!d.status.isEmpty()) p *= 2;
            }
            case "venoshock", "barbbarrage" -> {
                if ("psn".equals(d.status) || "tox".equals(d.status)) p *= 2;
            }
            case "brine" -> {
                if (d.hpFrac() <= 0.5) p *= 2;
            }
            case "acrobatics" -> {
                if (a.itemKnown && a.item.isEmpty()) p *= 2;
            }
            case "weatherball" -> {
                if (!"none".equals(f.weather) && !"fog".equals(f.weather)) p = 100;
            }
            case "terrainpulse" -> {
                if (!"none".equals(f.terrain)) p = 100;
            }
            case "eruption", "waterspout", "dragonenergy" -> p = Math.max(1, Math.floor(150 * a.hpFrac()));
            case "reversal", "flail" -> {
                double r = a.hpFrac();
                p = r < 0.0417 ? 200 : r < 0.1042 ? 150 : r < 0.2083 ? 100 : r < 0.3542 ? 80 : r < 0.6875 ? 40 : 20;
            }
            case "gyroball" -> p = Math.min(150, Math.floor(25.0 * effSpeedRaw(d) / Math.max(1, effSpeedRaw(a))) + 1);
            case "electroball" -> {
                double r = effSpeedRaw(a) / Math.max(1, effSpeedRaw(d));
                p = r >= 4 ? 150 : r >= 3 ? 120 : r >= 2 ? 80 : r >= 1 ? 60 : 40;
            }
            case "heavyslam", "heatcrash", "grassknot", "lowkick" -> p = p > 0 ? p : 80;
            case "lastrespects" -> p = 50;
            case "storedpower", "powertrip" -> {
                int pos = 0;
                for (int v : a.boosts.values()) if (v > 0) pos += v;
                p = 20 + 20 * pos;
            }
            default -> {
            }
        }
        if (a.hasAbility("technician") && p <= 60) p *= 1.5;
        if (a.hasAbility("sheerforce") && m.power > 0) p *= 1.3;
        if (a.hasAbility("toughclaws") && m.physical()) p *= 1.3;
        if (a.hasAbility("ironfist") && Set.of("drainpunch", "machpunch", "icepunch", "firepunch", "thunderpunch", "bulletpunch",
                "shadowpunch", "focuspunch", "hammerarm", "meteormash", "poweruppunch", "jetpunch", "ragefist", "headlongrush").contains(m.id)) p *= 1.2;
        if (a.hasAbility("strongjaw") && Set.of("crunch", "psychicfangs", "firefang", "icefang", "thunderfang", "poisonfang",
                "bite", "jawlock", "fishiousrend").contains(m.id)) p *= 1.5;
        if (a.hasAbility("sharpness") && Set.of("aerialace", "aircutter", "airslash", "aquacutter", "behemothblade", "bitterblade",
                "ceaselessedge", "crosspoison", "cut", "furycutter", "kowtowcleave", "leafblade", "nightslash", "populationbomb",
                "psyblade", "psychocut", "razorleaf", "razorshell", "sacredsword", "slash", "solarblade", "stoneaxe", "tachyoncutter",
                "xscissor").contains(m.id)) p *= 1.5;
        if (a.hasAbility("megalauncher") && Set.of("aurasphere", "darkpulse", "dragonpulse", "waterpulse", "originpulse", "terrainpulse").contains(m.id)) p *= 1.5;
        return p;
    }

    private static double effSpeedRaw(AMon m) {
        double s = m.spe * stageMult(m.boost("spe"));
        if ("par".equals(m.status)) s *= 0.5;
        return s;
    }

    /** Vitesse effective (boosts, paralysie, Vent Arriere, Mouchoir Choix, talents meteo). */
    public static double speed(AMon m, ASide side, AField f) {
        double s = m.spe * stageMult(m.boost("spe"));
        if ("par".equals(m.status) && !m.hasAbility("quickfeet")) s *= 0.5;
        if (side != null && side.tailwind) s *= 2;
        if (m.hasItem("choicescarf")) s *= 1.5;
        if (m.hasItem("ironball")) s *= 0.5;
        if (m.hasAbility("swiftswim") && "rain".equals(f.weather)) s *= 2;
        if (m.hasAbility("chlorophyll") && "sun".equals(f.weather)) s *= 2;
        if (m.hasAbility("sandrush") && "sand".equals(f.weather)) s *= 2;
        if (m.hasAbility("slushrush") && "snow".equals(f.weather)) s *= 2;
        if (m.hasAbility("surgesurfer") && "electric".equals(f.terrain)) s *= 2;
        if (m.hasAbility("quickfeet") && !m.status.isEmpty()) s *= 1.5;
        if (m.hasAbility("unburden") && m.itemKnown && m.item.isEmpty()) s *= 2;
        return s;
    }

    /** Priorite effective d'une capacite (Farceur, Ailes Bourrasque, Prioguerison...). */
    public static int priority(AMon user, AMove m, AField f) {
        int p = m.priority;
        if (m.isStatus() && user.hasAbility("prankster")) p += 1;
        if ("flying".equals(m.type) && user.hasAbility("galewings") && user.hp >= user.maxHp) p += 1;
        if (MoveFacts.RECOVERY.contains(m.id) && user.hasAbility("triage")) p += 3;
        if ("grassyglide".equals(m.id) && "grassy".equals(f.terrain)) p = Math.max(p, 1);
        return p;
    }

    /** true si a agit avant b (meme priorite) ; Distorsion inverse l'ordre. */
    public static boolean faster(AMon a, ASide as, AMon b, ASide bs, AField f) {
        double sa = speed(a, as, f);
        double sb = speed(b, bs, f);
        return f.trickRoom ? sa < sb : sa > sb;
    }

    /** Statistique estimee d'un Pokemon adverse (IV 31, EV 84, nature neutre). */
    public static int estimateStat(int base, int level, boolean hp) {
        double core = Math.floor((2.0 * base + 31 + 21) * level / 100.0);
        return (int) (hp ? core + level + 10 : core + 5);
    }
}
