package fr.cobblepulse.battletower.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vue d'un combat du point de vue de l'IA (independante de Cobblemon, donc testable hors du jeu).
 * L'IA est loyale : pour l'equipe adverse, elle ne connait que ce qu'un joueur verrait (espece, niveau, % de PV,
 * statut, boosts, capacites/talent/objet deja reveles) ; les statistiques adverses sont estimees.
 */
public final class Model {
    private Model() {}

    public static final class AMove {
        public String id = "";
        public String name = "";
        public String type = "normal";
        /** physical, special, status */
        public String category = "status";
        public double power;
        /** Precision en % (<= 0 : ne rate jamais). */
        public double accuracy = 100;
        public int priority;
        public String target = "normal";
        public int pp = 10;
        public boolean disabled;
        /** Capacite supposee (non revelee) : utilisee seulement pour estimer la menace adverse. */
        public boolean assumed;

        public boolean isStatus() {
            return "status".equals(category) || power <= 0 && !MoveFacts.LEVEL_DAMAGE.contains(id) && !MoveFacts.HALF_HP.contains(id);
        }

        public boolean physical() {
            return "physical".equals(category);
        }

        public boolean spread() {
            return "allAdjacentFoes".equalsIgnoreCase(target) || "allAdjacent".equalsIgnoreCase(target) || "all".equalsIgnoreCase(target)
                    || "foeSide".equalsIgnoreCase(target);
        }

        public double hitChance() {
            return accuracy <= 0 || accuracy > 100 ? 1.0 : accuracy / 100.0;
        }

        public AMove copy() {
            AMove m = new AMove();
            m.id = id;
            m.name = name;
            m.type = type;
            m.category = category;
            m.power = power;
            m.accuracy = accuracy;
            m.priority = priority;
            m.target = target;
            m.pp = pp;
            m.disabled = disabled;
            m.assumed = assumed;
            return m;
        }
    }

    public static final class AMon {
        public String id = "";
        public String species = "";
        public Set<String> types = new LinkedHashSet<>();
        public int level = 50;
        public int hp = 100;
        public int maxHp = 100;
        public int atk = 100;
        public int def = 100;
        public int spa = 100;
        public int spd = 100;
        public int spe = 100;
        public Map<String, Integer> boosts = new HashMap<>();
        /** "", brn, par, psn, tox, slp, frz */
        public String status = "";
        /** Talent connu ("" si inconnu). */
        public String ability = "";
        public Set<String> possibleAbilities = new LinkedHashSet<>();
        /** Objet connu ("" si inconnu ou aucun). */
        public String item = "";
        public boolean itemKnown;
        public List<AMove> moves = new ArrayList<>();
        public boolean active;
        public String teraType = "";
        public boolean terastallized;
        /** L'IA peut terastalliser / mega-evoluer ce Pokemon (equipe de l'IA uniquement). */
        public boolean canTera;
        public boolean canMega;
        public boolean nfe;
        public boolean statsExact;
        public int turnsActive;
        public boolean confused;
        public boolean protectedLastTurn;
        /** Pokemon adverse jamais vu (rien n'est connu de lui). */
        public boolean hidden;

        public boolean fainted() {
            return hp <= 0;
        }

        public double hpFrac() {
            return maxHp <= 0 ? 0 : Math.max(0, (double) hp / maxHp);
        }

        public int boost(String stat) {
            Integer v = boosts.get(stat);
            return v == null ? 0 : v;
        }

        public boolean hasAbility(String a) {
            if (!ability.isEmpty()) return ability.equals(a);
            return possibleAbilities.size() == 1 && possibleAbilities.contains(a);
        }

        public boolean hasItem(String i) {
            return item.equals(i);
        }

        public Set<String> effectiveTypes() {
            if (terastallized && !teraType.isEmpty() && !"stellar".equals(teraType)) return Set.of(teraType);
            return types;
        }

        public boolean grounded(String fieldGravity) {
            if ("gravity".equals(fieldGravity)) return true;
            if (hasItem("airballoon")) return false;
            if (hasAbility("levitate")) return false;
            return !effectiveTypes().contains("flying");
        }

        public AMon copy() {
            AMon m = new AMon();
            m.id = id;
            m.species = species;
            m.types = new LinkedHashSet<>(types);
            m.level = level;
            m.hp = hp;
            m.maxHp = maxHp;
            m.atk = atk;
            m.def = def;
            m.spa = spa;
            m.spd = spd;
            m.spe = spe;
            m.boosts = new HashMap<>(boosts);
            m.status = status;
            m.ability = ability;
            m.possibleAbilities = new LinkedHashSet<>(possibleAbilities);
            m.item = item;
            m.itemKnown = itemKnown;
            m.moves = new ArrayList<>();
            for (AMove mv : moves) m.moves.add(mv.copy());
            m.active = active;
            m.teraType = teraType;
            m.terastallized = terastallized;
            m.canTera = canTera;
            m.canMega = canMega;
            m.nfe = nfe;
            m.statsExact = statsExact;
            m.turnsActive = turnsActive;
            m.confused = confused;
            m.protectedLastTurn = protectedLastTurn;
            m.hidden = hidden;
            return m;
        }

        @Override
        public String toString() {
            return species + "(" + Math.round(hpFrac() * 100) + "%)";
        }
    }

    public static final class ASide {
        public List<AMon> team = new ArrayList<>();
        /** Pokemon actifs (meme objets que dans team). */
        public List<AMon> active = new ArrayList<>();
        public boolean stealthRock;
        public int spikes;
        public int toxicSpikes;
        public boolean stickyWeb;
        public boolean reflect;
        public boolean lightScreen;
        public boolean auroraVeil;
        public boolean tailwind;
        public boolean gimmickUsed;

        public int alive() {
            int n = 0;
            for (AMon m : team) if (!m.fainted()) n++;
            return n;
        }

        public List<AMon> bench() {
            List<AMon> out = new ArrayList<>();
            for (AMon m : team) if (!m.fainted() && !active.contains(m)) out.add(m);
            return out;
        }

        public ASide copy() {
            ASide s = new ASide();
            Map<AMon, AMon> map = new HashMap<>();
            for (AMon m : team) {
                AMon c = m.copy();
                map.put(m, c);
                s.team.add(c);
            }
            for (AMon m : active) s.active.add(map.getOrDefault(m, m.copy()));
            s.stealthRock = stealthRock;
            s.spikes = spikes;
            s.toxicSpikes = toxicSpikes;
            s.stickyWeb = stickyWeb;
            s.reflect = reflect;
            s.lightScreen = lightScreen;
            s.auroraVeil = auroraVeil;
            s.tailwind = tailwind;
            s.gimmickUsed = gimmickUsed;
            return s;
        }
    }

    public static final class AField {
        /** none, sun, rain, sand, snow, fog */
        public String weather = "none";
        /** none, electric, grassy, psychic, misty */
        public String terrain = "none";
        public boolean trickRoom;
        public boolean gravity;
        public boolean inverse;
        public boolean doubles;
        public int turn;

        public AField copy() {
            AField f = new AField();
            f.weather = weather;
            f.terrain = terrain;
            f.trickRoom = trickRoom;
            f.gravity = gravity;
            f.inverse = inverse;
            f.doubles = doubles;
            f.turn = turn;
            return f;
        }
    }

    /** Etat complet : cote de l'IA (ai) et cote du joueur (foe). */
    public static final class AState {
        public ASide ai = new ASide();
        public ASide foe = new ASide();
        public AField field = new AField();

        public AState copy() {
            AState s = new AState();
            s.ai = ai.copy();
            s.foe = foe.copy();
            s.field = field.copy();
            return s;
        }
    }

    /** Decision d'un Pokemon actif de l'IA. */
    public static final class Decision {
        public enum Kind { MOVE, SWITCH, PASS }

        public Kind kind = Kind.PASS;
        public String moveId;
        /** Cible : index du Pokemon actif adverse (0/1) ; -1 = aucune / automatique ; 10+i = allie i. */
        public int target = -1;
        public String switchToId;
        /** null, "mega" ou "tera". */
        public String gimmick;
        public double score;
        public String reason = "";

        public static Decision move(String id, int target, String gimmick, double score, String reason) {
            Decision d = new Decision();
            d.kind = Kind.MOVE;
            d.moveId = id;
            d.target = target;
            d.gimmick = gimmick;
            d.score = score;
            d.reason = reason;
            return d;
        }

        public static Decision swap(String id, double score, String reason) {
            Decision d = new Decision();
            d.kind = Kind.SWITCH;
            d.switchToId = id;
            d.score = score;
            d.reason = reason;
            return d;
        }

        @Override
        public String toString() {
            return kind == Kind.SWITCH ? "switch->" + switchToId + " (" + reason + ")"
                    : kind == Kind.MOVE ? moveId + (gimmick != null ? "+" + gimmick : "") + " t" + target + " (" + reason + ")" : "pass";
        }
    }
}
