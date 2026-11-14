package fr.cobblepulse.battletower.ai;

import fr.cobblepulse.battletower.ai.DamageCalc.Range;
import fr.cobblepulse.battletower.ai.Model.AField;
import fr.cobblepulse.battletower.ai.Model.AMon;
import fr.cobblepulse.battletower.ai.Model.AMove;
import fr.cobblepulse.battletower.ai.Model.ASide;
import fr.cobblepulse.battletower.ai.Model.AState;
import fr.cobblepulse.battletower.ai.Model.Decision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Cerveau des dresseurs de la tour. Quatre paliers cumulatifs :
 * <ul>
 *   <li>EASY : table des types parfaite, capacites STAB/super efficaces, placement au premier tour, switch simple sur double faiblesse.</li>
 *   <li>MEDIUM : calcul de degats exact (KO-range), priorite pour achever, pivots, Mega/Tera offensive du sweeper.</li>
 *   <li>HARD : hazards (pose/retrait), statuts cibles, preservation de la win-condition, Tera defensive anticipee.</li>
 *   <li>EXTREME : recherche minimax sur plusieurs tours (switchs adverses, double-switch, residuels), gimmick garde en joker.</li>
 * </ul>
 */
public final class Planner {
    public enum Level { EASY, MEDIUM, HARD, EXTREME }

    private final Level level;
    private final Random rng;
    private final long budgetMs;
    private final int depth;
    private long deadline;
    /** Pokemon cle de l'IA (win-condition), calcule au premier tour. */
    private String winConId;
    private String sweeperId;

    public Planner(Level level, long seed, long budgetMs, int depth) {
        this.level = level;
        this.rng = new Random(seed);
        this.budgetMs = Math.max(5, budgetMs);
        this.depth = Math.max(1, Math.min(3, depth));
    }

    public Level level() {
        return level;
    }

    private boolean atLeast(Level l) {
        return level.ordinal() >= l.ordinal();
    }

    // ------------------------------------------------------------------ Entree

    /**
     * @param slot          index du Pokemon actif de l'IA dans s.ai.active
     * @param forceSwitch   remplacement force (K.O. ou pivot)
     * @param trapped       ne peut pas switcher
     * @param reservedSwitch identifiants deja choisis comme remplacants ce tour (combat double)
     */
    public Decision decide(AState s, int slot, boolean forceSwitch, boolean trapped, Set<String> reservedSwitch) {
        deadline = System.currentTimeMillis() + budgetMs;
        if (winConId == null) identifyRoles(s.ai);
        AMon self = slot < s.ai.active.size() ? s.ai.active.get(slot) : null;
        if (forceSwitch || self == null || self.fainted()) {
            Decision d = bestSwitch(s, self, reservedSwitch, true);
            return d != null ? d : new Decision();
        }
        List<AMove> usable = usableMoves(self);
        if (usable.isEmpty()) return new Decision();
        if (s.field.doubles || level != Level.EXTREME) {
            return heuristic(s, slot, self, usable, trapped, reservedSwitch);
        }
        try {
            Decision d = search(s, slot, self, usable, trapped, reservedSwitch);
            if (d != null) return d;
        } catch (RuntimeException ignored) {
            // repli sur l'heuristique
        }
        return heuristic(s, slot, self, usable, trapped, reservedSwitch);
    }

    private static List<AMove> usableMoves(AMon m) {
        List<AMove> out = new ArrayList<>();
        for (AMove mv : m.moves) if (!mv.disabled && mv.pp > 0 && !MoveFacts.NEVER_USE.contains(mv.id) && !mv.assumed) out.add(mv);
        return out;
    }

    /** Win-condition : le Pokemon au plus fort potentiel offensif (bonus pour les capacites de boost). */
    private void identifyRoles(ASide side) {
        double best = -1;
        double bestSweep = -1;
        for (AMon m : side.team) {
            double off = Math.max(m.atk, m.spa) * 1.0 + m.spe * 0.6;
            boolean setup = false;
            for (AMove mv : m.moves) if (MoveFacts.SETUP.containsKey(mv.id)) setup = true;
            double wc = off * (setup ? 1.5 : 1.0) * (m.canMega || m.canTera ? 1.15 : 1.0);
            if (wc > best) {
                best = wc;
                winConId = m.id;
            }
            double sw = off * (m.canMega || m.canTera ? 1.5 : 1.0);
            if (sw > bestSweep) {
                bestSweep = sw;
                sweeperId = m.id;
            }
        }
        if (winConId == null) winConId = "";
        if (sweeperId == null) sweeperId = "";
    }

    // ------------------------------------------------------------------ Outils

    private static List<AMon> foes(AState s) {
        List<AMon> out = new ArrayList<>();
        for (AMon m : s.foe.active) if (m != null && !m.fainted()) out.add(m);
        return out;
    }

    private static Range dmg(AState s, AMon a, AMon d, AMove m, boolean aiAttacks) {
        return DamageCalc.damage(a, d, m, s.field, aiAttacks ? s.ai : s.foe, aiAttacks ? s.foe : s.ai);
    }

    /** Capacites adverses connues + capacites STAB supposees pour estimer la menace (IA loyale). */
    static List<AMove> threatMoves(AMon foe) {
        List<AMove> out = new ArrayList<>();
        Set<String> coveredTypes = new java.util.HashSet<>();
        for (AMove m : foe.moves) {
            out.add(m);
            if (!m.isStatus()) coveredTypes.add(m.type);
        }
        if (out.size() < 4) {
            for (String t : foe.types) {
                if (coveredTypes.contains(t)) continue;
                AMove a = new AMove();
                a.id = "assumed_" + t;
                a.name = "STAB " + t;
                a.type = t;
                a.category = foe.atk >= foe.spa ? "physical" : "special";
                a.power = 80;
                a.accuracy = 100;
                a.assumed = true;
                out.add(a);
            }
        }
        return out;
    }

    /** Meilleure menace d'un adversaire sur un de nos Pokemon : degats moyens et capacite. */
    private record Threat(double avg, double max, AMove move, int priority) {}

    private static Threat threat(AState s, AMon foe, AMon target) {
        Threat best = new Threat(0, 0, null, 0);
        for (AMove m : threatMoves(foe)) {
            Range r = DamageCalc.damage(foe, target, m, s.field, s.foe, s.ai);
            double v = r.avg() * m.hitChance();
            if (v > best.avg) best = new Threat(v, r.max(), m, DamageCalc.priority(foe, m, s.field));
        }
        return best;
    }

    private static boolean movesFirst(AState s, AMon self, AMove mine, AMon foe, int foePriority) {
        int mp = DamageCalc.priority(self, mine, s.field);
        if (mp != foePriority) return mp > foePriority;
        return DamageCalc.faster(self, s.ai, foe, s.foe, s.field);
    }

    private static int targetIndex(AState s, AMon foe) {
        return Math.max(0, s.foe.active.indexOf(foe));
    }

    private static boolean redundantSetup(AMon self, AMove m, AState s) {
        Map<String, Integer> boosts = MoveFacts.SETUP.get(m.id);
        if (boosts != null) {
            for (Map.Entry<String, Integer> e : boosts.entrySet()) {
                if (e.getValue() > 0 && self.boost(e.getKey()) < 2) return false;
            }
            return true;
        }
        if ("stealthrock".equals(m.id)) return s.foe.stealthRock;
        if ("spikes".equals(m.id)) return s.foe.spikes >= 3;
        if ("toxicspikes".equals(m.id)) return s.foe.toxicSpikes >= 2;
        if ("stickyweb".equals(m.id)) return s.foe.stickyWeb;
        if ("reflect".equals(m.id)) return s.ai.reflect || s.ai.auroraVeil;
        if ("lightscreen".equals(m.id)) return s.ai.lightScreen || s.ai.auroraVeil;
        if ("auroraveil".equals(m.id)) return s.ai.auroraVeil || !"snow".equals(s.field.weather);
        return false;
    }

    private static boolean isPlacement(AMove m) {
        return MoveFacts.SETUP.containsKey(m.id) || MoveFacts.HAZARDS.contains(m.id) && m.isStatus() || MoveFacts.SCREENS.contains(m.id);
    }

    /** Le statut peut-il etre inflige a la cible ? */
    static boolean canStatus(String status, AMon target, AField f) {
        if (!target.status.isEmpty()) return false;
        Set<String> t = target.effectiveTypes();
        if ("misty".equals(f.terrain) && target.grounded(f.gravity ? "gravity" : "")) return false;
        return switch (status) {
            case "brn" -> !t.contains("fire") && !target.hasAbility("waterveil") && !target.hasAbility("thermalexchange") && !target.hasAbility("waterbubble");
            case "par" -> !t.contains("electric") && !target.hasAbility("limber");
            case "psn", "tox" -> !t.contains("poison") && !t.contains("steel") && !target.hasAbility("immunity");
            case "slp" -> !("electric".equals(f.terrain) && target.grounded("")) && !target.hasAbility("insomnia")
                    && !target.hasAbility("vitalspirit") && !target.hasAbility("sweetveil");
            case "frz" -> !t.contains("ice");
            case "confusion" -> !target.confused && !target.hasAbility("owntempo");
            default -> true;
        };
    }

    // ------------------------------------------------------------------ Heuristique (EASY a HARD, et combats doubles)

    private Decision heuristic(AState s, int slot, AMon self, List<AMove> usable, boolean trapped, Set<String> reserved) {
        List<AMon> foes = foes(s);
        if (foes.isEmpty()) return Decision.move(usable.get(0).id, -1, null, 0, "aucune cible");
        // Menace immediate sur notre Pokemon
        Threat worst = new Threat(0, 0, null, 0);
        AMon worstFoe = foes.get(0);
        for (AMon f : foes) {
            Threat t = threat(s, f, self);
            if (t.avg > worst.avg) {
                worst = t;
                worstFoe = f;
            }
        }
        boolean koThreat = worst.avg >= self.hp;
        boolean foeFirst = worst.move != null && !movesFirst(s, self, usable.get(0), worstFoe, worst.priority);

        // 1) Switchs defensifs
        if (!trapped && !s.ai.bench().isEmpty()) {
            Decision sw = considerSwitch(s, self, foes, worst, worstFoe, koThreat, foeFirst, usable, reserved);
            if (sw != null) return sw;
        }

        Decision best = null;
        for (AMove m : usable) {
            for (AMon target : m.isStatus() || foes.size() == 1 ? List.of(foes.get(0)) : foes) {
                Decision d = scoreMove(s, slot, self, m, target, foes, worst, koThreat, foeFirst);
                if (d == null) continue;
                if (best == null || d.score > best.score) best = d;
            }
        }
        if (best == null) best = Decision.move(usable.get(0).id, -1, null, 0, "repli");
        applyGimmick(s, self, best, foes, worst);
        return best;
    }

    private Decision considerSwitch(AState s, AMon self, List<AMon> foes, Threat worst, AMon worstFoe, boolean koThreat,
                                    boolean foeFirst, List<AMove> usable, Set<String> reserved) {
        // EASY : double faiblesse evidente face aux types STAB adverses
        String quadType = null;
        for (AMon f : foes) {
            for (String t : f.types) {
                if (TypeChart.multiplier(t, self.effectiveTypes(), s.field.inverse) >= 4) quadType = t;
            }
        }
        if (quadType != null && self.turnsActive > 0) {
            AMon resist = null;
            for (AMon b : s.ai.bench()) {
                if (reserved.contains(b.id)) continue;
                if (TypeChart.multiplier(quadType, b.effectiveTypes(), s.field.inverse) <= 0.5) {
                    resist = b;
                    break;
                }
            }
            if (resist != null) return Decision.swap(resist.id, 50, "double faiblesse " + quadType);
        }
        if (!atLeast(Level.MEDIUM) || worst.move == null) return null;
        // Peut-on mettre K.O. avant d'etre mis K.O. ?
        boolean canKoFirst = false;
        for (AMove m : usable) {
            Range r = dmg(s, self, worstFoe, m, true);
            if (r.min() >= worstFoe.hp && movesFirst(s, self, m, worstFoe, worst.priority)) canKoFirst = true;
        }
        if (canKoFirst) return null;
        boolean isWinCon = self.id.equals(winConId);
        boolean shouldLeave = koThreat && foeFirst && (atLeast(Level.HARD) && isWinCon || self.turnsActive > 0 && worst.avg >= self.hp * 1.2);
        if (!shouldLeave) return null;
        Decision d = bestSwitch(s, self, reserved, false);
        if (d == null) return null;
        AMon in = byId(s.ai, d.switchToId);
        if (in == null) return null;
        Threat onIn = threat(s, worstFoe, in);
        if (onIn.avg >= in.hp * 0.6) return null; // pas de contre fiable : on reste
        d.reason = isWinCon ? "preserve la win-condition" : "evite le K.O.";
        return d;
    }

    private Decision scoreMove(AState s, int slot, AMon self, AMove m, AMon target, List<AMon> foes, Threat worst,
                               boolean koThreat, boolean foeFirst) {
        int ti = foes.size() > 1 ? targetIndex(s, target) : -1;
        double eff = DamageCalc.effectiveness(self, target, m, s.field);
        // ---- Capacites de statut
        if (m.isStatus()) {
            if (isPlacement(m)) {
                if (redundantSetup(self, m, s)) return Decision.move(m.id, ti, null, -5, "inutile");
                boolean firstTurn = self.turnsActive == 0;
                if (koThreat) return Decision.move(m.id, ti, null, -2, "menace de K.O.");
                if (MoveFacts.HAZARDS.contains(m.id)) {
                    if (atLeast(Level.HARD) && s.foe.alive() < 2) return Decision.move(m.id, ti, null, 0, "trop peu d'adversaires");
                    return Decision.move(m.id, ti, null, firstTurn ? 120 : atLeast(Level.HARD) ? 70 : 15, "pose de hazard");
                }
                if (MoveFacts.SCREENS.contains(m.id)) return Decision.move(m.id, ti, null, firstTurn ? 110 : 20, "ecran");
                // boost : plus interessant si on survit a au moins deux coups
                double safety = worst.avg <= 0 ? 3 : self.hp / worst.avg;
                double sc = firstTurn ? 115 : (atLeast(Level.MEDIUM) && safety >= 2.5 ? 60 : 10);
                return Decision.move(m.id, ti, null, sc, "placement");
            }
            if (MoveFacts.HAZARD_REMOVAL.contains(m.id) && m.isStatus()) {
                boolean ownHazards = s.ai.stealthRock || s.ai.spikes > 0 || s.ai.toxicSpikes > 0 || s.ai.stickyWeb;
                if (atLeast(Level.HARD) && ownHazards && s.ai.alive() >= 2) return Decision.move(m.id, ti, null, 90, "retrait des hazards");
                return Decision.move(m.id, ti, null, -1, "pas de hazards");
            }
            String st = MoveFacts.STATUS.get(m.id);
            if (st != null) {
                boolean typeImmune = "thunderwave".equals(m.id) && TypeChart.multiplier("electric", target.effectiveTypes(), s.field.inverse) == 0;
                if (typeImmune || !canStatus(st, target, s.field)) return Decision.move(m.id, ti, null, -10, "statut impossible");
                double sc = 8;
                if (atLeast(Level.HARD)) {
                    boolean physicalFoe = target.atk > target.spa * 1.1;
                    boolean fasterFoe = DamageCalc.speed(target, s.foe, s.field) > DamageCalc.speed(self, s.ai, s.field);
                    if ("brn".equals(st) && physicalFoe) sc = 85;
                    else if ("par".equals(st) && fasterFoe) sc = 80;
                    else if ("tox".equals(st) && target.def + target.spd > target.atk + target.spa) sc = 70;
                    else if ("slp".equals(st)) sc = 75;
                    else sc = 35;
                    if (koThreat && foeFirst) sc -= 60;
                }
                return Decision.move(m.id, ti, null, sc * m.hitChance(), "statut " + st);
            }
            if (MoveFacts.RECOVERY.contains(m.id)) {
                double missing = 1 - self.hpFrac();
                double sc = missing >= 0.5 ? 60 : missing >= 0.3 ? 25 : -5;
                if (atLeast(Level.MEDIUM) && worst.avg >= self.maxHp * 0.5) sc -= 30; // le soin ne suit pas
                return Decision.move(m.id, ti, null, sc, "soin");
            }
            if (MoveFacts.PROTECT.contains(m.id)) {
                double sc = self.protectedLastTurn ? -20 : (atLeast(Level.HARD) && ("tox".equals(target.status) || "brn".equals(target.status)) ? 30 : 2);
                return Decision.move(m.id, ti, null, sc, "abri");
            }
            if (MoveFacts.TRICK_ROOM.contains(m.id)) {
                boolean slower = DamageCalc.speed(self, s.ai, s.field) < DamageCalc.speed(target, s.foe, s.field);
                return Decision.move(m.id, ti, null, !s.field.trickRoom && slower ? 70 : -10, "distorsion");
            }
            if (MoveFacts.TAILWIND.contains(m.id)) return Decision.move(m.id, ti, null, s.ai.tailwind ? -10 : 55, "vent arriere");
            String w = MoveFacts.WEATHER_MOVES.get(m.id);
            if (w != null) return Decision.move(m.id, ti, null, w.equals(s.field.weather) ? -10 : 25, "meteo");
            return Decision.move(m.id, ti, null, 3, "statut");
        }
        // ---- Capacites offensives
        if (eff == 0) return Decision.move(m.id, ti, null, -1000, "immunite");
        if (!atLeast(Level.MEDIUM)) {
            boolean stab = self.types.contains(DamageCalc.moveType(self, m, s.field));
            double p = DamageCalc.basePower(self, target, m, s.field) * (stab ? 1.5 : 1) * eff * m.hitChance()
                    * MoveFacts.MULTI_HIT.getOrDefault(m.id, 1.0);
            if (MoveFacts.LEVEL_DAMAGE.contains(m.id)) p = self.level * 1.2;
            if (MoveFacts.SELF_KO.contains(m.id)) p *= 0.3;
            if (MoveFacts.RECHARGE.contains(m.id)) p *= 0.6;
            return Decision.move(m.id, ti, null, p / 2.0 + rng.nextDouble(), "puissance x STAB x efficacite");
        }
        Range r = dmg(s, self, target, m, true);
        double avg = r.avg() * m.hitChance();
        double frac = Math.min(1.0, avg / Math.max(1, target.hp));
        int koTurns = avg <= 0 ? 99 : (int) Math.ceil(target.hp / avg);
        double sc = frac * 100;
        if (r.min() >= target.hp) sc += 120 * m.hitChance();
        else if (r.avg() >= target.hp) sc += 80 * m.hitChance();
        else if (koTurns == 2) sc += 25;
        int prio = DamageCalc.priority(self, m, s.field);
        // Priorite pour achever un Pokemon affaibli plus rapide ou menacant
        if (prio > 0 && r.avg() >= target.hp && (foeFirst || koThreat)) sc += 150;
        if ("suckerpunch".equals(m.id) && worst.move != null && worst.move.isStatus()) sc -= 80;
        if ("fakeout".equals(m.id)) sc += self.turnsActive == 0 ? 40 : -200;
        // Pivots : garder le momentum quand le duel est defavorable
        if (MoveFacts.PIVOTS.contains(m.id) && !s.ai.bench().isEmpty()) {
            boolean badMatchup = koThreat || worst.avg >= self.hp * 0.5 || frac < 0.3;
            if (badMatchup) sc += 45;
        }
        // Recul, sacrifice, recharge
        double recoil = MoveFacts.RECOIL.getOrDefault(m.id, 0.0) * Math.min(avg, target.hp);
        if (self.hasItem("lifeorb") && !self.hasAbility("magicguard")) recoil += self.maxHp * 0.1;
        if (recoil > 0) sc -= 30 * Math.min(1, recoil / Math.max(1, self.hp));
        if (MoveFacts.DRAIN.containsKey(m.id)) sc += 10 * (1 - self.hpFrac());
        if (MoveFacts.SELF_KO.contains(m.id)) sc -= self.hpFrac() > 0.35 ? 70 : 10;
        if (MoveFacts.RECHARGE.contains(m.id) && r.avg() < target.hp) sc -= 30;
        if (s.field.doubles && m.spread()) {
            for (AMon ally : s.ai.active) {
                if (ally == self || ally.fainted()) continue;
                if (("allAdjacent".equalsIgnoreCase(m.target) || "all".equalsIgnoreCase(m.target))
                        && DamageCalc.effectiveness(self, ally, m, s.field) > 0) sc -= 60;
            }
            int hitFoes = 0;
            for (AMon f : foes) if (DamageCalc.effectiveness(self, f, m, s.field) > 0) hitFoes++;
            sc *= hitFoes >= 2 ? 1.3 : 1;
        }
        if (OHKO(m)) sc = m.hitChance() * 100;
        return Decision.move(m.id, ti, null, sc + rng.nextDouble() * 2, koTurns <= 1 ? "OHKO" : koTurns == 2 ? "2HKO" : "degats");
    }

    private static boolean OHKO(AMove m) {
        return MoveFacts.OHKO.contains(m.id);
    }

    /** Mega-Evolution / Teracristal selon le palier. */
    private void applyGimmick(AState s, AMon self, Decision d, List<AMon> foes, Threat worst) {
        if (d.kind != Decision.Kind.MOVE || s.ai.gimmickUsed) return;
        if (self.canMega) {
            // La Mega-Evolution est toujours benefique et propre a ce Pokemon (une seule Mega-Gemme utile) : immediate.
            d.gimmick = "mega";
            return;
        }
        if (!self.canTera || self.teraType.isEmpty() || !atLeast(Level.MEDIUM)) return;
        AMove mv = byMove(self, d.moveId);
        // HARD+ : Tera defensive anticipee si l'attaque adverse la plus probable devient resistee
        boolean koFirst = false;
        if (mv != null && !mv.isStatus() && d.target < 10) {
            AMon tgt = d.target >= 0 && d.target < s.foe.active.size() ? s.foe.active.get(d.target) : (foes.isEmpty() ? null : foes.get(0));
            if (tgt != null && dmg(s, self, tgt, mv, true).min() >= tgt.hp && movesFirst(s, self, mv, tgt, worst.priority)) koFirst = true;
        }
        if (atLeast(Level.HARD) && !koFirst && worst.move != null && worst.avg >= self.hp * 0.6) {
            double now = TypeChart.multiplier(worst.move.type, self.effectiveTypes(), s.field.inverse);
            double after = TypeChart.multiplier(worst.move.type, Set.of(self.teraType), s.field.inverse);
            if (after <= 0.5 && after < now) {
                d.gimmick = "tera";
                d.reason += " + Tera defensive (" + self.teraType + ")";
                return;
            }
        }
        // MEDIUM : Tera offensive du sweeper principal des le premier tour
        if (level == Level.MEDIUM || level == Level.HARD) {
            boolean sweeper = self.id.equals(sweeperId);
            boolean offensive = mv != null && !mv.isStatus() && self.teraType.equals(DamageCalc.moveType(self, mv, s.field));
            boolean attacking = mv != null && !mv.isStatus();
            if (sweeper && attacking && (self.turnsActive == 0 || offensive)) {
                d.gimmick = "tera";
                d.reason += " + Tera du sweeper";
            }
        }
    }

    private static AMove byMove(AMon m, String id) {
        for (AMove mv : m.moves) if (mv.id.equals(id)) return mv;
        return null;
    }

    private static AMon byId(ASide side, String id) {
        for (AMon m : side.team) if (m.id.equals(id)) return m;
        return null;
    }

    // ------------------------------------------------------------------ Remplacants

    /** Meilleur remplacant : degats infliges aux adversaires actifs moins degats recus (hazards compris). */
    public Decision bestSwitch(AState s, AMon leaving, Set<String> reserved, boolean forced) {
        List<AMon> foes = foes(s);
        Decision best = null;
        for (AMon b : s.ai.bench()) {
            if (reserved.contains(b.id) || b == leaving) continue;
            double score = switchScore(s, b, foes);
            if (best == null || score > best.score) best = Decision.swap(b.id, score, forced ? "remplacement" : "switch");
        }
        return best;
    }

    private double switchScore(AState s, AMon in, List<AMon> foes) {
        double score = in.hpFrac() * 20;
        double hazard = hazardDamage(s.ai, in, s.field) / Math.max(1, in.maxHp);
        score -= hazard * 40;
        for (AMon f : foes) {
            double out = 0;
            for (AMove m : usableMoves(in)) {
                if (m.isStatus()) continue;
                Range r = DamageCalc.damage(in, f, m, s.field, s.ai, s.foe);
                out = Math.max(out, Math.min(1, r.avg() * m.hitChance() / Math.max(1, f.hp)));
            }
            Threat t = threat(s, f, in);
            double taken = Math.min(1.5, t.avg / Math.max(1, in.hp));
            boolean faster = DamageCalc.faster(in, s.ai, f, s.foe, s.field);
            if (!atLeast(Level.MEDIUM)) {
                // EASY : uniquement les types
                double resist = 0;
                for (String ty : f.types) resist += TypeChart.multiplier(ty, in.effectiveTypes(), s.field.inverse);
                score += (2 - resist) * 10;
            } else {
                score += out * (faster ? 45 : 30) - taken * (faster ? 30 : 45);
                if (atLeast(Level.HARD) && in.id.equals(winConId) && taken >= 0.8) score -= 25; // ne pas exposer la win-condition
            }
        }
        return score + rng.nextDouble();
    }

    static double hazardDamage(ASide side, AMon in, AField f) {
        if (in.hasItem("heavydutyboots") || in.hasAbility("magicguard")) return 0;
        double d = 0;
        if (side.stealthRock) d += in.maxHp * TypeChart.multiplier("rock", in.effectiveTypes(), f.inverse) / 8.0;
        if (side.spikes > 0 && in.grounded(f.gravity ? "gravity" : "")) d += in.maxHp * (side.spikes == 1 ? 1 / 8.0 : side.spikes == 2 ? 1 / 6.0 : 1 / 4.0);
        return d;
    }

    // ------------------------------------------------------------------ Recherche (EXTREME, simple uniquement)

    private record Action(boolean ai, Decision d, AMove move, AMon switchTo) {}

    private Decision search(AState s, int slot, AMon self, List<AMove> usable, boolean trapped, Set<String> reserved) {
        AMon foe = foes(s).isEmpty() ? null : foes(s).get(0);
        if (foe == null) return null;
        List<Action> mine = aiActions(s, self, usable, trapped, reserved);
        List<Action> theirs = foeActions(s, foe);
        if (mine.isEmpty() || theirs.isEmpty()) return null;
        Decision best = null;
        double bestVal = -1e9;
        for (Action a : mine) {
            double worst = 1e9;
            double sum = 0;
            int n = 0;
            for (Action o : theirs) {
                if (System.currentTimeMillis() > deadline && best != null) break;
                AState next = simulate(s, self.id, foe.id, a, o);
                double v = depth >= 2 && System.currentTimeMillis() < deadline ? lookahead(next) : evaluate(next);
                worst = Math.min(worst, v);
                sum += v;
                n++;
            }
            if (n == 0) continue;
            // Prudence : 60 % pire cas (prediction de la meilleure reponse adverse) + 40 % moyenne
            double val = 0.6 * worst + 0.4 * (sum / n);
            if (a.d.gimmick != null) val -= gimmickKeepValue(s);
            if (val > bestVal) {
                bestVal = val;
                best = a.d;
            }
        }
        if (best != null) {
            best.score = bestVal;
            best.reason = (best.reason == null ? "" : best.reason) + " [minimax " + String.format(java.util.Locale.ROOT, "%.2f", bestVal) + "]";
        }
        return best;
    }

    /** Valeur de garder son gimmick en reserve (joker) : diminue quand il reste peu de Pokemon. */
    private static double gimmickKeepValue(AState s) {
        return s.ai.alive() <= 2 ? 0.02 : 0.12;
    }

    private List<Action> aiActions(AState s, AMon self, List<AMove> usable, boolean trapped, Set<String> reserved) {
        List<Action> out = new ArrayList<>();
        List<Decision> scored = new ArrayList<>();
        AMon foe = foes(s).get(0);
        Threat worst = threat(s, foe, self);
        boolean koThreat = worst.avg >= self.hp;
        boolean foeFirst = worst.move != null && !movesFirst(s, self, usable.get(0), foe, worst.priority);
        for (AMove m : usable) {
            Decision d = scoreMove(s, 0, self, m, foe, List.of(foe), worst, koThreat, foeFirst);
            if (d != null && d.score > -500) scored.add(d);
        }
        scored.sort(Comparator.comparingDouble((Decision d) -> -d.score));
        for (Decision d : scored.subList(0, Math.min(4, scored.size()))) {
            AMove m = byMove(self, d.moveId);
            if (!s.ai.gimmickUsed && self.canMega) {
                // Mega : jamais gardee en reserve (elle ne sert qu'a ce Pokemon)
                out.add(new Action(true, Decision.move(d.moveId, d.target, "mega", d.score, d.reason + " + Mega"), m, null));
                continue;
            }
            out.add(new Action(true, d, m, null));
            if (!s.ai.gimmickUsed && self.canTera && !self.teraType.isEmpty()) {
                Decision g = Decision.move(d.moveId, d.target, "tera", d.score, d.reason + " + Tera");
                out.add(new Action(true, g, m, null));
            }
        }
        if (!trapped) {
            for (AMon b : s.ai.bench()) {
                if (reserved.contains(b.id)) continue;
                out.add(new Action(true, Decision.swap(b.id, 0, "switch (prediction)"), null, b));
            }
        }
        return out;
    }

    private static List<Action> foeActions(AState s, AMon foe) {
        List<Action> out = new ArrayList<>();
        for (AMove m : threatMoves(foe)) out.add(new Action(false, null, m, null));
        for (AMon b : s.foe.bench()) if (!b.hidden) out.add(new Action(false, null, null, b));
        return out;
    }

    /** Un tour supplementaire : chaque camp joue sa meilleure reponse gloutonne. */
    private double lookahead(AState st) {
        AMon a = st.ai.active.isEmpty() ? null : st.ai.active.get(0);
        AMon f = st.foe.active.isEmpty() ? null : st.foe.active.get(0);
        if (a == null || f == null || a.fainted() || f.fainted()) return evaluate(st);
        AMove ma = greedyMove(st, a, f, true);
        AMove mf = greedyMove(st, f, a, false);
        if (ma == null || mf == null) return evaluate(st);
        AState next = simulate(st, a.id, f.id, new Action(true, Decision.move(ma.id, -1, null, 0, ""), ma, null),
                new Action(false, null, mf, null));
        return 0.35 * evaluate(st) + 0.65 * evaluate(next);
    }

    private static AMove greedyMove(AState s, AMon user, AMon target, boolean ai) {
        AMove best = null;
        double bv = -1;
        for (AMove m : ai ? usableMoves(user) : threatMoves(user)) {
            if (m.isStatus()) continue;
            Range r = DamageCalc.damage(user, target, m, s.field, ai ? s.ai : s.foe, ai ? s.foe : s.ai);
            double v = r.avg() * m.hitChance();
            if (v > bv) {
                bv = v;
                best = m;
            }
        }
        return best;
    }

    /** Simule un tour (switchs, ordre priorite/vitesse, degats moyens, recul, soins, statuts, residuels). */
    AState simulate(AState base, String aiId, String foeId, Action a, Action o) {
        AState s = base.copy();
        AMon me = byId(s.ai, aiId);
        AMon foe = byId(s.foe, foeId);
        if (me == null || foe == null) return s;
        // Switchs (toujours en premier)
        if (a.switchTo != null) me = doSwitch(s, s.ai, me, byId(s.ai, a.switchTo.id));
        if (o.switchTo != null) foe = doSwitch(s, s.foe, foe, byId(s.foe, o.switchTo.id));
        // Gimmick
        if (a.d != null && "tera".equals(a.d.gimmick) && me.canTera) {
            me.terastallized = true;
            s.ai.gimmickUsed = true;
        } else if (a.d != null && "mega".equals(a.d.gimmick)) {
            s.ai.gimmickUsed = true;
            me.atk = (int) (me.atk * 1.15);
            me.spa = (int) (me.spa * 1.15);
            me.def = (int) (me.def * 1.1);
            me.spd = (int) (me.spd * 1.1);
        }
        AMove mm = a.move == null ? null : byMove(me, a.move.id);
        if (mm == null && a.move != null) mm = a.move;
        AMove fm = o.move;
        boolean meFirst;
        if (mm == null) meFirst = false;
        else if (fm == null) meFirst = true;
        else {
            int pa = DamageCalc.priority(me, mm, s.field);
            int pf = DamageCalc.priority(foe, fm, s.field);
            meFirst = pa != pf ? pa > pf : DamageCalc.faster(me, s.ai, foe, s.foe, s.field);
        }
        boolean meProtect = mm != null && MoveFacts.PROTECT.contains(mm.id);
        boolean foeProtect = fm != null && MoveFacts.PROTECT.contains(fm.id);
        if (meFirst) {
            if (mm != null) useMove(s, me, foe, mm, true, foeProtect);
            if (fm != null && !foe.fainted() && o.switchTo == null) useMove(s, foe, me, fm, false, meProtect);
        } else {
            if (fm != null && o.switchTo == null) useMove(s, foe, me, fm, false, meProtect);
            if (mm != null && !me.fainted()) useMove(s, me, foe, mm, true, foeProtect);
        }
        residual(s, s.ai, me);
        residual(s, s.foe, foe);
        return s;
    }

    private AMon doSwitch(AState s, ASide side, AMon out, AMon in) {
        if (in == null) return out;
        int idx = side.active.indexOf(out);
        if (idx >= 0) side.active.set(idx, in);
        else side.active.add(in);
        out.boosts.clear();
        in.turnsActive = 0;
        in.hp -= (int) hazardDamage(side, in, s.field);
        if (side.toxicSpikes > 0 && in.grounded("") && canStatus("psn", in, s.field)) in.status = side.toxicSpikes >= 2 ? "tox" : "psn";
        if (side.stickyWeb && in.grounded("")) in.boosts.merge("spe", -1, Integer::sum);
        return in;
    }

    private static void useMove(AState s, AMon user, AMon target, AMove m, boolean ai, boolean targetProtected) {
        ASide us = ai ? s.ai : s.foe;
        ASide them = ai ? s.foe : s.ai;
        if (MoveFacts.PROTECT.contains(m.id)) return;
        if ("slp".equals(user.status) || "frz".equals(user.status)) return;
        double act = "par".equals(user.status) ? 0.75 : 1.0;
        if (m.isStatus()) {
            Map<String, Integer> boosts = MoveFacts.SETUP.get(m.id);
            if (boosts != null) {
                for (Map.Entry<String, Integer> e : boosts.entrySet()) user.boosts.merge(e.getKey(), e.getValue(), (x, y) -> Math.max(-6, Math.min(6, x + y)));
                if ("bellydrum".equals(m.id)) user.hp -= user.maxHp / 2;
                return;
            }
            if (targetProtected) return;
            switch (m.id) {
                case "stealthrock" -> them.stealthRock = true;
                case "spikes" -> them.spikes = Math.min(3, them.spikes + 1);
                case "toxicspikes" -> them.toxicSpikes = Math.min(2, them.toxicSpikes + 1);
                case "stickyweb" -> them.stickyWeb = true;
                case "reflect" -> us.reflect = true;
                case "lightscreen" -> us.lightScreen = true;
                case "auroraveil" -> us.auroraVeil = true;
                case "tailwind" -> us.tailwind = true;
                case "trickroom" -> s.field.trickRoom = !s.field.trickRoom;
                case "defog" -> {
                    us.stealthRock = false;
                    us.spikes = 0;
                    us.toxicSpikes = 0;
                    us.stickyWeb = false;
                    them.reflect = them.lightScreen = them.auroraVeil = false;
                }
                default -> {
                    if (MoveFacts.RECOVERY.contains(m.id)) {
                        user.hp = Math.min(user.maxHp, user.hp + user.maxHp / 2);
                        return;
                    }
                    String st = MoveFacts.STATUS.get(m.id);
                    if (st != null && !"confusion".equals(st) && canStatus(st, target, s.field) && m.hitChance() * act >= 0.7) {
                        if (!("thunderwave".equals(m.id) && DamageCalc.effectiveness(user, target, m, s.field) == 0)) target.status = st;
                    }
                }
            }
            return;
        }
        if (targetProtected) return;
        Range r = DamageCalc.damage(user, target, m, s.field, us, them);
        double expected = r.avg() * m.hitChance() * act;
        int dealt = (int) Math.min(target.hp, Math.round(expected));
        target.hp -= dealt;
        if (MoveFacts.RAPID_SPIN_LIKE.contains(m.id)) {
            us.stealthRock = false;
            us.spikes = 0;
            us.toxicSpikes = 0;
            us.stickyWeb = false;
        }
        double recoil = MoveFacts.RECOIL.getOrDefault(m.id, 0.0) * dealt;
        if (user.hasItem("lifeorb") && !user.hasAbility("magicguard") && dealt > 0) recoil += user.maxHp / 10.0;
        if (!user.hasAbility("rockhead") && !user.hasAbility("magicguard")) user.hp -= (int) Math.round(recoil);
        double drain = MoveFacts.DRAIN.getOrDefault(m.id, 0.0) * dealt;
        user.hp = (int) Math.min(user.maxHp, user.hp + drain);
        if (MoveFacts.SELF_KO.contains(m.id)) user.hp = 0;
        if ("closecombat".equals(m.id)) {
            user.boosts.merge("def", -1, Integer::sum);
            user.boosts.merge("spd", -1, Integer::sum);
        }
        if (Set.of("dracometeor", "overheat", "leafstorm", "fleurcannon", "psychoboost", "makeitrain").contains(m.id)) {
            user.boosts.merge("spa", "makeitrain".equals(m.id) ? -1 : -2, Integer::sum);
        }
    }

    /** Degats et soins de fin de tour (meteo, brulure, poison, Restes, Champ Herbu). */
    private static void residual(AState s, ASide side, AMon m) {
        if (m == null || m.fainted()) return;
        boolean guard = m.hasAbility("magicguard");
        Set<String> t = m.effectiveTypes();
        if (!guard && "sand".equals(s.field.weather) && !t.contains("rock") && !t.contains("ground") && !t.contains("steel")
                && !m.hasAbility("sandveil") && !m.hasAbility("sandrush") && !m.hasAbility("sandforce") && !m.hasAbility("overcoat")) {
            m.hp -= m.maxHp / 16;
        }
        if (!guard) {
            switch (m.status) {
                case "brn" -> m.hp -= m.maxHp / 16;
                case "psn" -> m.hp -= m.hasAbility("poisonheal") ? -m.maxHp / 8 : m.maxHp / 8;
                case "tox" -> m.hp -= m.hasAbility("poisonheal") ? -m.maxHp / 8 : m.maxHp * Math.min(15, m.turnsActive + 1) / 16;
                default -> {
                }
            }
        }
        if (m.hasItem("leftovers")) m.hp += m.maxHp / 16;
        if (m.hasItem("blacksludge")) m.hp += t.contains("poison") ? m.maxHp / 16 : -m.maxHp / 8;
        if ("grassy".equals(s.field.terrain) && m.grounded("")) m.hp += m.maxHp / 16;
        m.hp = Math.min(m.maxHp, m.hp);
        m.turnsActive++;
    }

    /** Evaluation statique d'une position (> 0 : avantage IA). */
    double evaluate(AState s) {
        double v = 0;
        v += sideValue(s, s.ai, true);
        v -= sideValue(s, s.foe, false);
        AMon a = s.ai.active.isEmpty() ? null : s.ai.active.get(0);
        AMon f = s.foe.active.isEmpty() ? null : s.foe.active.get(0);
        if (a != null && f != null && !a.fainted() && !f.fainted()) {
            AMove ma = greedyMove(s, a, f, true);
            AMove mf = greedyMove(s, f, a, false);
            double out = ma == null ? 0 : Math.min(1, DamageCalc.damage(a, f, ma, s.field, s.ai, s.foe).avg() * ma.hitChance() / Math.max(1, f.hp));
            double in = mf == null ? 0 : Math.min(1, DamageCalc.damage(f, a, mf, s.field, s.foe, s.ai).avg() * mf.hitChance() / Math.max(1, a.hp));
            boolean faster = DamageCalc.faster(a, s.ai, f, s.foe, s.field);
            v += 0.35 * (out - in);
            if (faster && out >= 1) v += 0.4;
            if (!faster && in >= 1) v -= 0.4;
        }
        return v;
    }

    private double sideValue(AState s, ASide side, boolean ai) {
        double v = 0;
        for (AMon m : side.team) {
            if (m.fainted()) continue;
            double w = ai && m.id.equals(winConId) ? 1.3 : 1.0;
            v += w * (0.6 + 0.4 * m.hpFrac());
            switch (m.status) {
                case "brn" -> v -= m.atk > m.spa ? 0.25 : 0.08;
                case "par" -> v -= 0.15;
                case "tox", "psn" -> v -= 0.12;
                case "slp", "frz" -> v -= 0.3;
                default -> {
                }
            }
            if (m.active) {
                int pos = 0;
                for (Map.Entry<String, Integer> e : m.boosts.entrySet()) {
                    if (e.getKey().equals("atk") || e.getKey().equals("spa") || e.getKey().equals("spe")) pos += e.getValue();
                    else pos += e.getValue() / 2;
                }
                v += 0.06 * pos;
            }
        }
        int alive = side.alive();
        double hazards = (side.stealthRock ? 0.06 : 0) + side.spikes * 0.04 + side.toxicSpikes * 0.03 + (side.stickyWeb ? 0.03 : 0);
        v -= hazards * Math.max(0, alive - 1);
        if (side.reflect || side.lightScreen || side.auroraVeil) v += 0.05;
        if (side.tailwind) v += 0.05;
        return v;
    }
}
