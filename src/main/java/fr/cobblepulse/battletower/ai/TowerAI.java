package fr.cobblepulse.battletower.ai;

import com.cobblemon.mod.common.api.battles.interpreter.BattleContext;
import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.battles.model.ai.BattleAI;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.battles.PassActionResponse;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.SwitchActionResponse;
import com.cobblemon.mod.common.battles.Targetable;
import com.cobblemon.mod.common.battles.interpreter.ContextManager;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.ai.Model.AMon;
import fr.cobblepulse.battletower.ai.Model.AMove;
import fr.cobblepulse.battletower.ai.Model.ASide;
import fr.cobblepulse.battletower.ai.Model.AState;
import fr.cobblepulse.battletower.ai.Model.Decision;
import fr.cobblepulse.battletower.battle.FieldModifiers;
import fr.cobblepulse.battletower.battle.TrainerFactory;
import fr.cobblepulse.battletower.data.MonSpec;
import fr.cobblepulse.battletower.data.TrainerSpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Adaptateur Cobblemon de l'IA de la tour : construit une vue loyale du combat, consulte le {@link Planner} et
 * traduit la decision en reponse Showdown (attaque + cible + Mega/Tera, ou switch).
 */
public final class TowerAI implements BattleAI {
    /** Pokemon adverses ayant terastallise (alimente par l'evenement Cobblemon). */
    public static final Map<UUID, String> TERASTALLIZED = new java.util.concurrent.ConcurrentHashMap<>();

    private final Planner planner;
    private final TrainerSpec spec;
    private final List<MonSpec> teamSpecs;
    private final Map<UUID, Set<String>> revealedMoves = new HashMap<>();
    private final Set<UUID> seenFoes = new LinkedHashSet<>();
    private final Map<UUID, Integer> activeSince = new HashMap<>();
    private final Set<UUID> aiTerastallized = new HashSet<>();
    private final Set<String> reservedSwitch = new HashSet<>();
    private boolean aiGimmickUsed;
    private int reservedTurn = -1;
    private int enforcedTurn = -1;
    private String lastDecision = "";

    public TowerAI(TrainerSpec spec, long seed) {
        this.spec = spec;
        this.teamSpecs = new ArrayList<>(spec.team);
        Planner.Level lvl = switch (spec.aiLevel()) {
            case EASY -> Planner.Level.EASY;
            case MEDIUM -> Planner.Level.MEDIUM;
            case HARD -> Planner.Level.HARD;
            case EXTREME -> Planner.Level.EXTREME;
        };
        long budget = fr.cobblepulse.battletower.config.Cfg.tower.l("ai.extreme_budget_ms", 40);
        int depth = fr.cobblepulse.battletower.config.Cfg.tower.i("ai.extreme_depth", 2);
        this.planner = new Planner(lvl, seed, budget, depth);
    }

    public String lastDecision() {
        return lastDecision;
    }

    @Override
    public ShowdownActionResponse choose(ActiveBattlePokemon active, PokemonBattle battle, BattleSide aiSide, ShowdownMoveset moveset,
                                         boolean forceSwitch) {
        try {
            int turn = battle.getTurn();
            if (turn != reservedTurn) {
                reservedTurn = turn;
                reservedSwitch.clear();
            }
            if (turn != enforcedTurn) {
                enforcedTurn = turn;
                FieldModifiers.enforce(battle, spec);
            }
            trackReveals(battle, aiSide);
            AState state = buildState(battle, aiSide, moveset, active);
            int slot = indexOfActive(aiSide, active);
            boolean trapped = moveset != null && moveset.getTrapped();
            Decision d = planner.decide(state, Math.max(0, slot), forceSwitch || moveset == null, trapped, reservedSwitch);
            ShowdownActionResponse r = toResponse(d, active, battle, aiSide, moveset, forceSwitch);
            if (r != null && r.isValid(active, moveset, forceSwitch)) {
                if (r instanceof SwitchActionResponse sw) reservedSwitch.add(sw.getNewPokemonId().toString());
                if (r instanceof MoveActionResponse mr && mr.getGimmickID() != null) {
                    aiGimmickUsed = true;
                    if (ShowdownMoveset.Gimmick.TERASTALLIZATION.getId().equals(mr.getGimmickID()) && active.getBattlePokemon() != null) {
                        aiTerastallized.add(active.getBattlePokemon().getUuid());
                    }
                }
                lastDecision = d.toString();
                return r;
            }
            Battletower.LOG.debug("[Battletower] Decision IA invalide ({}), repli", d);
        } catch (Throwable t) {
            Battletower.LOG.warn("[Battletower] Erreur de l'IA ({}) : {}", spec.ai, t.toString());
        }
        return fallback(active, aiSide, moveset, forceSwitch);
    }

    // ------------------------------------------------------------------ Suivi (informations visibles uniquement)

    private void trackReveals(PokemonBattle battle, BattleSide aiSide) {
        BattleSide foeSide = aiSide.getOppositeSide();
        for (ActiveBattlePokemon a : foeSide.getActivePokemon()) {
            BattlePokemon bp = a.getBattlePokemon();
            if (bp != null) {
                seenFoes.add(bp.getUuid());
            }
        }
        for (ActiveBattlePokemon a : aiSide.getActivePokemon()) {
            BattlePokemon bp = a.getBattlePokemon();
            if (bp != null) activeSince.putIfAbsent(bp.getUuid(), battle.getTurn());
        }
        try {
            for (Map.Entry<UUID, BattleMessage> e : battle.getMajorBattleActions().entrySet()) {
                BattleMessage msg = e.getValue();
                if (msg == null || !"move".equals(msg.getId())) continue;
                MoveTemplate t = msg.moveAt(1);
                if (t != null) revealedMoves.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(t.getName());
            }
        } catch (Throwable ignored) {
            // informations non disponibles : l'IA se rabat sur les capacites STAB supposees
        }
        // Pokemon qui ne sont plus actifs : on reinitialise leur compteur de presence
        Set<UUID> activeIds = new HashSet<>();
        for (ActiveBattlePokemon a : aiSide.getActivePokemon()) if (a.getBattlePokemon() != null) activeIds.add(a.getBattlePokemon().getUuid());
        activeSince.keySet().retainAll(activeIds);
    }

    // ------------------------------------------------------------------ Construction de l'etat

    private AState buildState(PokemonBattle battle, BattleSide aiSide, ShowdownMoveset moveset, ActiveBattlePokemon current) {
        AState s = new AState();
        s.field.inverse = spec.inverse;
        s.field.doubles = aiSide.getActivePokemon().size() > 1 || spec.doubles();
        s.field.turn = battle.getTurn();
        readField(battle, s);
        BattleSide foeSide = aiSide.getOppositeSide();
        // Cote IA
        Map<UUID, AMon> aiMons = new HashMap<>();
        for (BattleActor actor : aiSide.getActors()) {
            List<BattlePokemon> list = actor.getPokemonList();
            for (int i = 0; i < list.size(); i++) {
                BattlePokemon bp = list.get(i);
                MonSpec ms = i < teamSpecs.size() ? teamSpecs.get(i) : null;
                AMon m = ownMon(bp, ms);
                aiMons.put(bp.getUuid(), m);
                s.ai.team.add(m);
            }
        }
        for (ActiveBattlePokemon a : aiSide.getActivePokemon()) {
            BattlePokemon bp = a.getBattlePokemon();
            AMon m = bp == null ? null : aiMons.get(bp.getUuid());
            if (m == null) continue;
            m.active = true;
            Integer since = activeSince.get(bp.getUuid());
            m.turnsActive = since == null ? 0 : Math.max(0, battle.getTurn() - since);
            if (a == current && moveset != null) applyMoveset(m, moveset);
            s.ai.active.add(m);
        }
        s.ai.gimmickUsed = aiGimmickUsed;
        // Cote adverse (loyal)
        Map<UUID, AMon> foeMons = new HashMap<>();
        for (BattleActor actor : foeSide.getActors()) {
            for (BattlePokemon bp : actor.getPokemonList()) {
                AMon m = seenFoes.contains(bp.getUuid()) ? foeMon(bp) : hiddenMon(bp);
                foeMons.put(bp.getUuid(), m);
                s.foe.team.add(m);
            }
        }
        for (ActiveBattlePokemon a : foeSide.getActivePokemon()) {
            BattlePokemon bp = a.getBattlePokemon();
            AMon m = bp == null ? null : foeMons.get(bp.getUuid());
            if (m == null) continue;
            m.active = true;
            s.foe.active.add(m);
        }
        readSide(aiSide.getContextManager(), s.ai);
        readSide(foeSide.getContextManager(), s.foe);
        return s;
    }

    private void applyMoveset(AMon m, ShowdownMoveset ms) {
        List<AMove> moves = new ArrayList<>();
        for (InBattleMove ibm : ms.getMoves()) {
            MoveTemplate t = Moves.getByName(ibm.getId());
            AMove mv = t != null ? toMove(t) : new AMove();
            mv.id = ibm.getId();
            mv.pp = ibm.getPp();
            mv.disabled = ibm.getDisabled() || !ibm.canBeUsed();
            moves.add(mv);
        }
        if (!moves.isEmpty()) m.moves = moves;
        m.canTera = m.canTera && ms.getCanTerastallize() != null;
        m.canMega = m.canMega && ms.getCanMegaEvo();
    }

    private AMon ownMon(BattlePokemon bp, MonSpec ms) {
        Pokemon pk = bp.getEffectedPokemon();
        AMon m = baseMon(bp, pk);
        m.statsExact = true;
        m.hp = bp.getHealth();
        m.maxHp = Math.max(1, bp.getMaxHealth());
        m.atk = pk.getAttack();
        m.def = pk.getDefence();
        m.spa = pk.getSpecialAttack();
        m.spd = pk.getSpecialDefence();
        m.spe = pk.getSpeed();
        try {
            m.ability = MoveFacts.norm(pk.getAbility().getName());
        } catch (Throwable ignored) {
        }
        ItemStack held = pk.heldItem();
        m.itemKnown = true;
        m.item = held == null || held.isEmpty() ? "" : MoveFacts.norm(BuiltInRegistries.ITEM.getKey(held.getItem()).getPath());
        for (Move mv : pk.getMoveSet().getMoves()) {
            AMove a = toMove(mv.getTemplate());
            a.pp = mv.getCurrentPp();
            m.moves.add(a);
        }
        try {
            if (pk.getTeraType() != null) m.teraType = MoveFacts.norm(pk.getTeraType().getName());
        } catch (Throwable ignored) {
        }
        m.terastallized = aiTerastallized.contains(bp.getUuid());
        m.canTera = ms == null || ms.allowTera;
        m.canMega = ms == null || ms.allowMega;
        if (ms != null && !ms.allowTera) m.canTera = false;
        return m;
    }

    private AMon foeMon(BattlePokemon bp) {
        Pokemon pk = bp.getEffectedPokemon();
        AMon m = baseMon(bp, pk);
        FormData form = pk.getForm();
        Map<Stat, Integer> base = form.getBaseStats();
        int lvl = pk.getLevel();
        m.maxHp = DamageCalc.estimateStat(base.getOrDefault(Stats.HP, 80), lvl, true);
        double frac = bp.getMaxHealth() <= 0 ? 0 : (double) bp.getHealth() / bp.getMaxHealth();
        m.hp = (int) Math.round(frac * m.maxHp);
        m.atk = DamageCalc.estimateStat(base.getOrDefault(Stats.ATTACK, 80), lvl, false);
        m.def = DamageCalc.estimateStat(base.getOrDefault(Stats.DEFENCE, 80), lvl, false);
        m.spa = DamageCalc.estimateStat(base.getOrDefault(Stats.SPECIAL_ATTACK, 80), lvl, false);
        m.spd = DamageCalc.estimateStat(base.getOrDefault(Stats.SPECIAL_DEFENCE, 80), lvl, false);
        m.spe = DamageCalc.estimateStat(base.getOrDefault(Stats.SPEED, 80), lvl, false);
        m.possibleAbilities.addAll(TrainerFactory.possibleAbilities(form));
        m.itemKnown = false;
        Set<String> revealed = revealedMoves.getOrDefault(bp.getUuid(), Set.of());
        for (String name : revealed) {
            MoveTemplate t = Moves.getByName(MoveFacts.norm(name));
            if (t != null) m.moves.add(toMove(t));
        }
        String tera = TERASTALLIZED.get(bp.getUuid());
        if (tera != null) {
            m.terastallized = true;
            m.teraType = tera;
        }
        return m;
    }

    /** Pokemon adverse encore jamais vu : compte dans le materiel restant mais ne revele rien. */
    private static AMon hiddenMon(BattlePokemon bp) {
        AMon m = new AMon();
        m.id = bp.getUuid().toString();
        m.species = "?";
        m.hidden = true;
        m.maxHp = 100;
        m.hp = bp.getHealth() <= 0 ? 0 : 100;
        return m;
    }

    private static AMon baseMon(BattlePokemon bp, Pokemon pk) {
        AMon m = new AMon();
        m.id = bp.getUuid().toString();
        m.species = pk.getSpecies().getName();
        for (ElementalType t : pk.getTypes()) m.types.add(t.getName().toLowerCase(Locale.ROOT));
        m.level = pk.getLevel();
        try {
            for (Map.Entry<Stat, Integer> e : bp.getStatChanges().entrySet()) {
                String key = statKey(e.getKey());
                if (key != null && e.getValue() != null) m.boosts.put(key, e.getValue());
            }
        } catch (Throwable ignored) {
        }
        m.status = statusOf(pk);
        try {
            m.nfe = pk.getForm().getEvolutions() != null && !pk.getForm().getEvolutions().isEmpty();
        } catch (Throwable ignored) {
        }
        return m;
    }

    static String statKey(Stat s) {
        if (s == Stats.ATTACK) return "atk";
        if (s == Stats.DEFENCE) return "def";
        if (s == Stats.SPECIAL_ATTACK) return "spa";
        if (s == Stats.SPECIAL_DEFENCE) return "spd";
        if (s == Stats.SPEED) return "spe";
        if (s == Stats.ACCURACY) return "accuracy";
        if (s == Stats.EVASION) return "evasion";
        return null;
    }

    static String statusOf(Pokemon pk) {
        try {
            var c = pk.getStatus();
            if (c == null) return "";
            var st = c.getStatus();
            if (st == null) return "";
            String n = MoveFacts.norm(st.getShowdownName());
            return switch (n) {
                case "brn", "par", "psn", "tox", "slp", "frz" -> n;
                case "burn" -> "brn";
                case "paralysis" -> "par";
                case "poison" -> "psn";
                case "poisonbadly", "badlypoisoned" -> "tox";
                case "sleep" -> "slp";
                case "frozen", "freeze" -> "frz";
                default -> n;
            };
        } catch (Throwable t) {
            return "";
        }
    }

    static AMove toMove(MoveTemplate t) {
        AMove m = new AMove();
        m.id = MoveFacts.norm(t.getName());
        m.name = t.getName();
        m.type = t.getElementalType().getName().toLowerCase(Locale.ROOT);
        m.category = t.getDamageCategory().getName().toLowerCase(Locale.ROOT);
        m.power = t.getPower();
        m.accuracy = t.getAccuracy();
        m.priority = t.getPriority();
        m.target = t.getTarget() == null ? "normal" : t.getTarget().name();
        m.pp = t.getPp();
        return m;
    }

    private static void readField(PokemonBattle battle, AState s) {
        ContextManager cm = battle.getContextManager();
        for (String id : ids(cm, BattleContext.Type.WEATHER)) {
            if (id.contains("rain") || id.contains("primordial")) s.field.weather = "rain";
            else if (id.contains("sun") || id.contains("desolate")) s.field.weather = "sun";
            else if (id.contains("sand")) s.field.weather = "sand";
            else if (id.contains("hail") || id.contains("snow")) s.field.weather = "snow";
            else if (id.contains("fog")) s.field.weather = "fog";
        }
        for (String id : ids(cm, BattleContext.Type.TERRAIN)) {
            if (id.contains("electric")) s.field.terrain = "electric";
            else if (id.contains("grassy")) s.field.terrain = "grassy";
            else if (id.contains("psychic")) s.field.terrain = "psychic";
            else if (id.contains("misty")) s.field.terrain = "misty";
        }
        for (String id : ids(cm, BattleContext.Type.ROOM)) if (id.contains("trickroom")) s.field.trickRoom = true;
        if (!ids(cm, BattleContext.Type.GRAVITY).isEmpty()) s.field.gravity = true;
    }

    private static void readSide(ContextManager cm, ASide side) {
        for (String id : ids(cm, BattleContext.Type.HAZARD)) {
            if (id.contains("stealthrock")) side.stealthRock = true;
            else if (id.contains("toxicspikes")) side.toxicSpikes++;
            else if (id.contains("spikes")) side.spikes++;
            else if (id.contains("stickyweb")) side.stickyWeb = true;
        }
        for (String id : ids(cm, BattleContext.Type.SCREEN)) {
            if (id.contains("reflect")) side.reflect = true;
            else if (id.contains("lightscreen")) side.lightScreen = true;
            else if (id.contains("auroraveil")) side.auroraVeil = true;
        }
        if (!ids(cm, BattleContext.Type.TAILWIND).isEmpty()) side.tailwind = true;
    }

    private static List<String> ids(ContextManager cm, BattleContext.Type type) {
        List<String> out = new ArrayList<>();
        if (cm == null) return out;
        try {
            Collection<BattleContext> c = cm.get(type);
            if (c != null) for (BattleContext bc : c) out.add(MoveFacts.norm(bc.getId()));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static int indexOfActive(BattleSide side, ActiveBattlePokemon a) {
        List<ActiveBattlePokemon> list = side.getActivePokemon();
        for (int i = 0; i < list.size(); i++) if (list.get(i) == a) return i;
        return 0;
    }

    // ------------------------------------------------------------------ Reponses

    private ShowdownActionResponse toResponse(Decision d, ActiveBattlePokemon active, PokemonBattle battle, BattleSide aiSide,
                                              ShowdownMoveset ms, boolean forceSwitch) {
        if (d.kind == Decision.Kind.SWITCH && d.switchToId != null) return new SwitchActionResponse(UUID.fromString(d.switchToId));
        if (d.kind != Decision.Kind.MOVE || ms == null) return null;
        InBattleMove chosen = null;
        for (InBattleMove ibm : ms.getMoves()) {
            if (MoveFacts.norm(ibm.getId()).equals(d.moveId)) chosen = ibm;
        }
        if (chosen == null) return null;
        String targetPnx = null;
        List<Targetable> targets = chosen.getTargets(active);
        if (targets != null && !targets.isEmpty()) {
            Targetable pick = null;
            List<ActiveBattlePokemon> foes = aiSide.getOppositeSide().getActivePokemon();
            if (d.target >= 0 && d.target < foes.size()) {
                ActiveBattlePokemon want = foes.get(d.target);
                for (Targetable t : targets) if (t == want) pick = t;
            }
            if (pick == null) {
                for (Targetable t : targets) {
                    if (t instanceof ActiveBattlePokemon abp && !abp.isAllied(active) && abp.isAlive()) {
                        pick = t;
                        break;
                    }
                }
            }
            if (pick == null) pick = targets.get(0);
            targetPnx = pick.getPNX();
        }
        String gimmick = null;
        if ("mega".equals(d.gimmick) && ms.getCanMegaEvo()) gimmick = ShowdownMoveset.Gimmick.MEGA_EVOLUTION.getId();
        if ("tera".equals(d.gimmick) && ms.getCanTerastallize() != null) gimmick = ShowdownMoveset.Gimmick.TERASTALLIZATION.getId();
        return new MoveActionResponse(chosen.getId(), targetPnx, gimmick);
    }

    private ShowdownActionResponse fallback(ActiveBattlePokemon active, BattleSide aiSide, ShowdownMoveset ms, boolean forceSwitch) {
        try {
            if (forceSwitch || ms == null) {
                for (BattleActor actor : aiSide.getActors()) {
                    for (BattlePokemon bp : actor.getPokemonList()) {
                        if (bp.getHealth() <= 0 || reservedSwitch.contains(bp.getUuid().toString())) continue;
                        boolean isActive = false;
                        for (ActiveBattlePokemon a : aiSide.getActivePokemon()) if (a.getBattlePokemon() == bp) isActive = true;
                        if (isActive) continue;
                        SwitchActionResponse r = new SwitchActionResponse(bp.getUuid());
                        if (r.isValid(active, ms, forceSwitch)) {
                            reservedSwitch.add(bp.getUuid().toString());
                            return r;
                        }
                    }
                }
                return PassActionResponse.INSTANCE;
            }
            for (InBattleMove ibm : ms.getMoves()) {
                if (!ibm.canBeUsed()) continue;
                String target = null;
                List<Targetable> t = ibm.getTargets(active);
                if (t != null && !t.isEmpty()) target = t.get(0).getPNX();
                MoveActionResponse r = new MoveActionResponse(ibm.getId(), target, null);
                if (r.isValid(active, ms, false)) return r;
            }
        } catch (Throwable t) {
            Battletower.LOG.warn("[Battletower] Repli de l'IA impossible : {}", t.toString());
        }
        return PassActionResponse.INSTANCE;
    }
}
