package fr.cobblepulse.battletower.battle;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.storage.party.NPCPartyStore;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleRules;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.BattleStartError;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.ErroredBattleStart;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.npc.NPCBattleActor;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.ai.MoveFacts;
import fr.cobblepulse.battletower.ai.TowerAI;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.core.Scheduler;
import fr.cobblepulse.battletower.data.TrainerSpec;
import kotlin.Unit;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Lance les combats de la tour (joueur contre PNJ) avec l'IA de la tour, le format, le nivellement et les
 * modificateurs du dresseur, puis notifie l'issue.
 */
public final class TowerBattle {
    /** Issue d'un combat de la tour. */
    public record Outcome(UUID battleId, UUID player, boolean won, boolean aborted, int turns, boolean playerFainted, String reason) {}

    public static final class Live {
        public final UUID battleId;
        public final UUID player;
        public final UUID npc;
        public final TrainerSpec spec;
        public final TowerAI ai;
        public final Consumer<Outcome> onEnd;
        public boolean playerFainted;
        public boolean finished;
        long goneSince;

        Live(UUID battleId, UUID player, UUID npc, TrainerSpec spec, TowerAI ai, Consumer<Outcome> onEnd) {
            this.battleId = battleId;
            this.player = player;
            this.npc = npc;
            this.spec = spec;
            this.ai = ai;
            this.onEnd = onEnd;
        }
    }

    private static final Map<UUID, Live> LIVE = new HashMap<>();
    /** Observateur de fin de combat (auto-test : journal Showdown, decisions de l'IA). */
    public static java.util.function.BiConsumer<PokemonBattle, Live> observer;
    private static final Set<UUID> PROTECTED_OWNERS = new LinkedHashSet<>();

    private TowerBattle() {}

    public static void register() {
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, e -> {
            safe(() -> onVictory(e.getBattle(), e.getWinners()));
            return Unit.INSTANCE;
        });
        CobblemonEvents.BATTLE_FLED.subscribe(Priority.NORMAL, e -> {
            safe(() -> {
                Live l = LIVE.get(e.getBattle().getBattleId());
                if (l != null) finish(l, false, false, e.getBattle().getTurn(), "fuite");
            });
            return Unit.INSTANCE;
        });
        CobblemonEvents.BATTLE_FAINTED.subscribe(Priority.NORMAL, e -> {
            safe(() -> {
                Live l = LIVE.get(e.getBattle().getBattleId());
                if (l == null) return;
                BattlePokemon killed = e.getKilled();
                if (killed != null && killed.actor instanceof PlayerBattleActor) l.playerFainted = true;
            });
            return Unit.INSTANCE;
        });
        CobblemonEvents.TERASTALLIZATION.subscribe(Priority.NORMAL, e -> {
            safe(() -> {
                if (!LIVE.containsKey(e.getBattle().getBattleId())) return;
                if (e.getPokemon() != null && e.getTeraType() != null) {
                    TowerAI.TERASTALLIZED.put(e.getPokemon().getUuid(), MoveFacts.norm(e.getTeraType().getName()));
                }
            });
            return Unit.INSTANCE;
        });
        // Aucun gain d'EXP ni d'EV pendant les combats de la tour
        CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.subscribe(Priority.HIGHEST, e -> {
            safe(() -> {
                UUID owner = e.getPokemon().getOwnerUUID();
                if (owner != null && PROTECTED_OWNERS.contains(owner)) e.cancel();
            });
            return Unit.INSTANCE;
        });
        CobblemonEvents.EV_GAINED_EVENT_PRE.subscribe(Priority.HIGHEST, e -> {
            safe(() -> {
                UUID owner = e.getPokemon().getOwnerUUID();
                if (owner != null && PROTECTED_OWNERS.contains(owner)) e.cancel();
            });
            return Unit.INSTANCE;
        });
        Scheduler.repeat(20, 20, TowerBattle::tick);
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            Battletower.LOG.error("[Battletower] Erreur d'evenement de combat", t);
        }
    }

    public static Live live(UUID battleId) {
        return battleId == null ? null : LIVE.get(battleId);
    }

    public static Live liveFor(UUID player) {
        for (Live l : LIVE.values()) if (l.player.equals(player)) return l;
        return null;
    }

    public static boolean npcBusy(UUID npc) {
        for (Live l : LIVE.values()) if (l.npc.equals(npc)) return true;
        return false;
    }

    /** Format Showdown du dresseur : simple/double, Combat Inverse, nivellement, sac interdit. */
    public static BattleFormat format(TrainerSpec spec) {
        BattleFormat base = spec.doubles() ? BattleFormat.Companion.getGEN_9_DOUBLES() : BattleFormat.Companion.getGEN_9_SINGLES();
        Set<String> rules = new LinkedHashSet<>(base.getRuleSet());
        if (spec.inverse) rules.add("Inverse Mod");
        if (Cfg.tower.b("battle.block_bag_items", true)) rules.add(BattleRules.BAG_CLAUSE);
        return base.copy(base.getMod(), base.getBattleType(), rules, base.getGen(), Math.max(0, spec.levelScale));
    }

    /**
     * Lance le combat. @return message d'erreur, ou null si le combat a demarre.
     */
    public static String start(ServerPlayer player, NPCEntity npc, TrainerSpec spec, Consumer<Outcome> onEnd) {
        try {
            if (CobblemonBridge.isInBattle(player)) return "Vous etes deja en combat.";
            if (npcBusy(npc.getUUID())) return "Ce dresseur est deja en combat.";
            spec.normalize();
            List<String> errors = new ArrayList<>();
            List<Pokemon> npcTeam = TrainerFactory.team(spec, errors);
            TrainerFactory.logErrors(spec.display(), errors);
            if (npcTeam.isEmpty()) return "Le dresseur n'a aucun Pokemon valide (configuration a corriger par un administrateur).";
            BattleFormat fmt = format(spec);
            int level = Math.max(0, spec.levelScale);
            boolean clone = Cfg.tower.b("battle.clone_parties", true) || level > 0;
            // Equipe du joueur (copies : PV, PP, statuts et objets restitues, aucun gain)
            PlayerPartyStore party = CobblemonBridge.party(player);
            List<BattlePokemon> playerTeam = party.toBattleTeam(clone, true, null);
            if (level > 0) {
                for (BattlePokemon bp : playerTeam) {
                    bp.getEffectedPokemon().setLevel(level);
                    bp.getEffectedPokemon().heal();
                }
            }
            if (playerTeam.isEmpty() || playerTeam.stream().allMatch(bp -> bp.getHealth() <= 0)) return "Votre equipe ne peut pas combattre.";
            // Equipe du PNJ
            NPCPartyStore npcParty = new NPCPartyStore(npc);
            for (Pokemon pk : npcTeam) npcParty.add(pk);
            npc.setParty(npcParty);
            List<BattlePokemon> npcBattleTeam = npcParty.toBattleTeam(true, true, null);
            if (level > 0) {
                for (BattlePokemon bp : npcBattleTeam) {
                    bp.getEffectedPokemon().setLevel(level);
                    bp.getEffectedPokemon().heal();
                }
            }
            TowerAI ai = new TowerAI(spec, System.nanoTime() ^ player.getUUID().getMostSignificantBits());
            PlayerBattleActor pa = new PlayerBattleActor(player.getUUID(), playerTeam);
            NPCBattleActor na = new NPCBattleActor(npc, npcBattleTeam, 5, ai);
            BattleStartResult r = BattleRegistry.startBattle(fmt, new BattleSide(pa), new BattleSide(na), false);
            if (r instanceof SuccessfulBattleStart ok) {
                PokemonBattle b = ok.getBattle();
                Live l = new Live(b.getBattleId(), player.getUUID(), npc.getUUID(), spec, ai, onEnd);
                LIVE.put(b.getBattleId(), l);
                PROTECTED_OWNERS.add(player.getUUID());
                return null;
            }
            if (r instanceof ErroredBattleStart err) {
                List<String> msgs = new ArrayList<>();
                for (BattleStartError e : err.getGeneralErrors()) msgs.add(describe(e, player));
                err.getParticipantErrors().values().forEach(set -> set.forEach(e -> msgs.add(describe(e, player))));
                return msgs.isEmpty() ? "Combat refuse par Cobblemon." : String.join(", ", msgs);
            }
            return "Resultat de combat inconnu.";
        } catch (Throwable t) {
            Battletower.LOG.error("[Battletower] Lancement du combat impossible", t);
            return t.getClass().getSimpleName() + " : " + t.getMessage();
        }
    }

    private static String describe(BattleStartError e, ServerPlayer p) {
        try {
            return e.getMessageFor(p).getString();
        } catch (Throwable t) {
            return String.valueOf(e);
        }
    }

    private static void onVictory(PokemonBattle battle, List<BattleActor> winners) {
        Live l = LIVE.get(battle.getBattleId());
        if (l == null) return;
        boolean won = false;
        if (winners != null) {
            for (BattleActor a : winners) {
                for (UUID u : a.getPlayerUUIDs()) if (u.equals(l.player)) won = true;
            }
        }
        boolean finalWon = won;
        int turns = battle.getTurn();
        if (observer != null) {
            try {
                observer.accept(battle, l);
            } catch (Throwable t) {
                Battletower.LOG.warn("[Battletower] Observateur de combat : {}", t.toString());
            }
        }
        // Laisse Cobblemon terminer le combat avant de teleporter/soigner
        Scheduler.next(() -> finish(l, finalWon, false, turns, finalWon ? "victoire" : "defaite"));
    }

    /** Termine (ou annule) le suivi d'un combat. */
    public static void finish(Live l, boolean won, boolean aborted, int turns, String reason) {
        if (l.finished) return;
        l.finished = true;
        LIVE.remove(l.battleId);
        if (liveFor(l.player) == null) PROTECTED_OWNERS.remove(l.player);
        if (LIVE.isEmpty()) TowerAI.TERASTALLIZED.clear();
        if (CobblemonBridge.battleActive(l.battleId)) CobblemonBridge.stopBattle(l.battleId);
        try {
            l.onEnd.accept(new Outcome(l.battleId, l.player, won, aborted, turns, l.playerFainted, reason));
        } catch (Throwable t) {
            Battletower.LOG.error("[Battletower] Erreur de fin de combat", t);
        }
    }

    /** Combat disparu sans vainqueur (arret, erreur) : annule apres 3 s. */
    private static void tick() {
        long now = System.currentTimeMillis();
        for (Live l : new ArrayList<>(LIVE.values())) {
            if (CobblemonBridge.battleActive(l.battleId)) {
                l.goneSince = 0;
                continue;
            }
            if (l.goneSince == 0) l.goneSince = now;
            else if (now - l.goneSince > 3000) finish(l, false, true, 0, "interrompu");
        }
    }

    /** Abandon force (deconnexion, disqualification) : le combat est arrete et compte comme une defaite. */
    public static void forfeit(UUID player, String reason) {
        Live l = liveFor(player);
        if (l != null) finish(l, false, false, 0, reason);
    }

    public static void clear() {
        LIVE.clear();
        PROTECTED_OWNERS.clear();
        TowerAI.TERASTALLIZED.clear();
    }
}
