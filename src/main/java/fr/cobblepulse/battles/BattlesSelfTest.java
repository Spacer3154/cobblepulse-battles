package fr.cobblepulse.battles;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.battles.hub.Hub;
import fr.cobblepulse.battles.ranked.Arenas;
import fr.cobblepulse.battles.ranked.Discord;
import fr.cobblepulse.battles.ranked.Elo;
import fr.cobblepulse.battles.ranked.FormatRules;
import fr.cobblepulse.battles.ranked.MatchManager;
import fr.cobblepulse.battles.ranked.Missions;
import fr.cobblepulse.battles.ranked.Ranked;
import fr.cobblepulse.battles.ranked.RankedConfig;
import fr.cobblepulse.battles.ranked.RankedMenus;
import fr.cobblepulse.battles.ranked.RankedProfile;
import fr.cobblepulse.battles.ranked.RankedQueue;
import fr.cobblepulse.battles.ranked.RankedStore;
import fr.cobblepulse.battles.ranked.Restrictions;
import fr.cobblepulse.battles.ranked.Seasons;
import fr.cobblepulse.battles.ranked.TeamRules;
import fr.cobblepulse.battles.trainers.TrainerMenus;
import fr.cobblepulse.battles.trainers.WorldTrainers;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.battle.CobblemonBridge;
import fr.cobblepulse.battletower.battle.TrainerFactory;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.core.Loc;
import fr.cobblepulse.battletower.core.Scheduler;
import fr.cobblepulse.battletower.data.MonSpec;
import fr.cobblepulse.battletower.data.PlayerData;
import fr.cobblepulse.battletower.data.TrainerSpec;
import fr.cobblepulse.battletower.gui.Gui;
import fr.cobblepulse.battletower.gui.Screens;
import fr.cobblepulse.battletower.net.Net;
import fr.cobblepulse.battletower.storage.DataService;
import fr.cobblepulse.battletower.storage.PlayerStore;
import fr.cobblepulse.battletower.tower.TowerManager;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static fr.cobblepulse.battletower.command.SelfTest.check;
import static fr.cobblepulse.battletower.command.SelfTest.fake;
import static fr.cobblepulse.battletower.command.SelfTest.mon;
import static fr.cobblepulse.battletower.command.SelfTest.party;
import static fr.cobblepulse.battletower.command.SelfTest.waitFor;

/** Auto-tests (CI) de CobblePulse-Battles : Ranked, dresseurs du monde, ecrans, saisons, stockage. */
public final class BattlesSelfTest {
    /** Joueurs de test qui acceptent et choisissent leur equipe automatiquement. */
    private static final Set<UUID> AUTO = ConcurrentHashMap.newKeySet();
    private static Scheduler.Task autoTask;

    private BattlesSelfTest() {}

    static void done(String name) {
        Battletower.LOG.info("[Battletower][selftest] SELFTEST-DONE {}", name);
    }

    static Loc loc(ServerLevel level, int dx, int dz) {
        BlockPos b = fr.cobblepulse.battletower.command.SelfTest.base(level);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, b.getX() + dx, b.getZ() + dz);
        return new Loc(level.dimension().location().toString(), b.getX() + dx + 0.5, y, b.getZ() + dz + 0.5, 0, 0);
    }

    static double dist(ServerPlayer p, Loc l) {
        return l == null ? 9999 : Math.sqrt(p.distanceToSqr(l.x, l.y, l.z));
    }

    static void click(ServerPlayer p, int slot) {
        Screens.click(p, Screens.sessionId(p.getUUID()), slot, "left");
    }

    static Pokemon create(MonSpec m) {
        return TrainerFactory.create(m, new ArrayList<>());
    }

    static MonSpec[] splash(int n) {
        String[] species = {"magikarp", "feebas", "hoppip", "wynaut"};
        MonSpec[] out = new MonSpec[n];
        for (int i = 0; i < n; i++) out[i] = mon(species[i], 20, "splash");
        return out;
    }

    private static void autoDrive() {
        for (UUID id : new ArrayList<>(AUTO)) {
            MatchManager.Match m = MatchManager.matchOf(id);
            ServerPlayer p = fr.cobblepulse.battletower.core.Players.get(id);
            if (m == null || p == null) continue;
            MatchManager.Side s = m.side(id.toString());
            if (m.state == MatchManager.State.READY && !s.accepted) MatchManager.accept(p);
            else if (m.state == MatchManager.State.SELECT && s.partyLoaded && !s.confirmed) {
                MatchManager.autoSelect(p);
                MatchManager.confirm(p);
            }
        }
    }

    private static void startAuto() {
        if (autoTask == null) autoTask = Scheduler.repeat(10, 10, BattlesSelfTest::autoDrive);
    }

    // ------------------------------------------------------------------ Ranked : parcours complet par les ecrans

    public static int ranked(CommandSourceStack src) {
        fr.cobblepulse.battletower.command.SelfTest.startBot();
        ServerLevel level = src.getServer().overworld();
        // Configuration
        FormatRules simple = RankedConfig.rules("simple");
        FormatRules dbl = RankedConfig.rules("double");
        check(src, "ranked.config", RankedConfig.enabled() && RankedConfig.RANKS.size() == 6 && simple != null && dbl != null
                        && simple.teamSize == 3 && dbl.teamSize == 4 && simple.level == 50 && !simple.doubles && dbl.doubles,
                RankedConfig.RANKS.size() + " rangs");
        check(src, "ranked.season", Seasons.active() && "saison1".equals(Seasons.currentId()), Seasons.describe() + " / " + Seasons.currentId());
        check(src, "ranked.elo_formula", Elo.next(1390, 1390, 1, 0, 0) == 1410 && Elo.next(1390, 1390, 0, 0, 0) == 1370
                        && Elo.next(1000, 1000, 1, 0, 0) == 1045 && Elo.next(1000, 1000, 0, 0, 0) == 1000,
                Elo.next(1390, 1390, 1, 0, 0) + "/" + Elo.next(1390, 1390, 0, 0, 0) + "/" + Elo.next(1000, 1000, 1, 0, 0));
        check(src, "ranked.ranks", "hyperball".equals(RankedConfig.rankFor(1410).id()) && "superball".equals(RankedConfig.rankFor(1399).id())
                && "pokeball".equals(RankedConfig.rankFor(1000).id()), null);
        // Regles d'equipe : restreints, especes interdites, clause d'espece
        Pokemon mewtwo = create(mon("mewtwo", 70, "psychic"));
        Pokemon lugia = create(mon("lugia", 70, "aeroblast"));
        Pokemon pika = create(mon("pikachu", 30, "thunderbolt"));
        Pokemon pika2 = create(mon("pikachu", 30, "thunderbolt"));
        Pokemon eevee = create(mon("eevee", 30, "tackle"));
        Pokemon koraidon = create(mon("koraidon", 70, "collisioncourse"));
        List<String> restrictedErr = TeamRules.checkSelection(List.of(mewtwo, lugia, pika), simple);
        List<String> oneRestricted = TeamRules.checkSelection(List.of(mewtwo, pika, eevee), simple);
        List<String> clauseErr = TeamRules.checkSelection(List.of(pika, pika2, eevee), simple);
        check(src, "ranked.rules_restricted", restrictedErr.size() == 1 && oneRestricted.isEmpty(), restrictedErr + " " + oneRestricted);
        check(src, "ranked.rules_species_clause", clauseErr.size() == 1, clauseErr.toString());
        check(src, "ranked.rules_banned", koraidon == null || TeamRules.checkPokemon(koraidon, simple) != null,
                koraidon == null ? "espece absente" : TeamRules.checkPokemon(koraidon, simple));
        // Arene de test
        Arenas.Arena arena = Arenas.create("selftest");
        arena.pos1 = loc(level, -14, 2);
        arena.pos2 = loc(level, -14, 10);
        arena.exit = null;
        Arenas.save();
        check(src, "ranked.arena", Arenas.anyConfigured() && Arenas.get("selftest") != null, null);
        // Joueurs
        FakePlayer ace = fake(level, "RKAce");
        FakePlayer fish = fake(level, "RKFish");
        Screens.capture(ace.getUUID(), true);
        Screens.capture(fish.getUUID(), true);
        party(ace, mon("garchomp", 60, "dragonclaw", "earthquake"), mon("lucario", 60, "flashcannon"), mon("gyarados", 60, "waterfall"),
                mon("snorlax", 60, "bodyslam"));
        party(fish, splash(3));
        Loc home = loc(level, -6, 0);
        home.teleport(ace);
        home.teleport(fish);
        RankedProfile pAce = RankedStore.get(ace);
        RankedProfile pFish = RankedStore.get(fish);
        // Commande d'administration (Elo) : les deux joueurs a 1390 (une victoire fait passer Hyper Ball)
        src.getServer().getCommands().performPrefixedCommand(src.getServer().createCommandSourceStack(), "ranked admin elo definir RKAce simple 1390");
        src.getServer().getCommands().performPrefixedCommand(src.getServer().createCommandSourceStack(), "ranked admin elo definir RKFish simple 1390");
        // les commandes lancees depuis une commande s'executent juste apres elle (vanilla 1.21) : suite au tick suivant
        Scheduler.later(5, () -> rankedQueue(src, ace, fish, home, arena, pAce, pFish));
        return 1;
    }

    private static void rankedQueue(CommandSourceStack src, FakePlayer ace, FakePlayer fish, Loc home, Arenas.Arena arena, RankedProfile pAce,
                                    RankedProfile pFish) {
        check(src, "ranked.admin_elo", pAce.stats("simple").elo == 1390 && pFish.stats("simple").elo == 1390,
                pAce.stats("simple").elo + "/" + pFish.stats("simple").elo);
        // Missions deterministes pour le test
        pAce.dailyPeriod = Missions.dailyPeriod();
        pAce.daily = new ArrayList<>(List.of(new RankedProfile.MissionState("gagner_1"), new RankedProfile.MissionState("jouer_3"),
                new RankedProfile.MissionState("ko_10")));
        int expBefore = CobblemonBridge.partyList(ace).get(0).getExperience();
        // File d'attente
        boolean j1 = RankedQueue.join(ace, "simple", true);
        boolean j2 = RankedQueue.join(fish, "simple", true);
        check(src, "ranked.queue_join", j1 && j2 && (RankedQueue.queued(ace.getUUID()) || MatchManager.inMatch(ace.getUUID())), j1 + "/" + j2);
        // Restrictions en file : commande bloquee (passe par le mixin), PC interdit, Tour refusee
        int blockedBefore = Restrictions.BLOCKED.get();
        double y0 = ace.getY();
        src.getServer().getCommands().performPrefixedCommand(ace.createCommandSourceStack().withPermission(4), "tp @s ~ ~5 ~");
        check(src, "ranked.restrict_command", Restrictions.BLOCKED.get() == blockedBefore + 1 && Math.abs(ace.getY() - y0) < 0.5,
                "bloquees=" + (Restrictions.BLOCKED.get() - blockedBefore) + " dy=" + (ace.getY() - y0));
        check(src, "ranked.restrict_pc", Restrictions.blocked(ace, "pc") && !Restrictions.blocked(ace, "casser_blocs"), null);
        TowerManager.startAssault(ace);
        check(src, "ranked.tower_refused", !TowerManager.inRun(ace.getUUID()) && Ranked.busy(ace.getUUID()), null);
        // Hub (ecran)
        Hub.open(ace);
        check(src, "screens.hub", "menu".equals(Screens.lastKind(ace.getUUID())) && Screens.names(ace.getUUID()).size() >= 5,
                Screens.names(ace.getUUID()).toString());
        String[] matchId = new String[1];
        waitFor(() -> {
            MatchManager.Match m = MatchManager.matchOf(ace.getUUID());
            return m != null && m.state == MatchManager.State.READY && Screens.slotNamed(ace.getUUID(), "Accepter") >= 0;
        }, 15, () -> {
            MatchManager.Match m = MatchManager.matchOf(ace.getUUID());
            check(src, "ranked.match_found", m != null && m.ranked && "simple".equals(m.format) && m.side(fish.getUUID().toString()) != null,
                    m == null ? "aucun" : m.state + " " + m.format);
            if (m == null) {
                done("ranked");
                return;
            }
            matchId[0] = m.id;
            check(src, "screens.ready", Screens.slotNamed(ace.getUUID(), "Accepter") == 11 && Screens.slotNamed(fish.getUUID(), "Refuser") == 15,
                    Screens.names(ace.getUUID()).toString());
            // les clics d'ecran sont traites au tick suivant : une action toutes les 3 ticks
            click(ace, 11);
            click(fish, 11);
            MatchManager.Side sa = m.side(ace.getUUID().toString());
            MatchManager.Side sf = m.side(fish.getUUID().toString());
            chain(() -> {
                check(src, "ranked.accepted", m.state == MatchManager.State.SELECT && sa.partyLoaded && sf.partyLoaded, m.state.name());
                check(src, "screens.select", Screens.slotNamed(ace.getUUID(), "Confirmer") == 40 && Screens.names(ace.getUUID()).size() >= 4 + 3 + 3,
                        Screens.names(ace.getUUID()).toString());
                // Choix par l'ecran : Garchomp (meneur), Lucario, Leviator ; l'adversaire en selection automatique
                click(ace, 9);
            }, () -> click(ace, 10), () -> click(ace, 11), () -> {
                check(src, "ranked.select_picks", sa.picks.equals(List.of(0, 1, 2)), sa.picks.toString());
                click(ace, 40);
                click(fish, 36);
            }, () -> click(fish, 40), () -> {
            check(src, "ranked.countdown", m.state == MatchManager.State.COUNTDOWN && m.arena != null, m.state.name());
            check(src, "ranked.arena_teleport", dist(ace, arena.pos1) < 2 && dist(fish, arena.pos2) < 2,
                    String.format("%.1f / %.1f", dist(ace, arena.pos1), dist(fish, arena.pos2)));
            waitFor(() -> m.state == MatchManager.State.BATTLE && m.battleId != null, 15, () -> {
                PokemonBattle b = CobblemonBridge.battle(m.battleId);
                check(src, "ranked.battle_started", b != null, m.state.name());
                waitFor(() -> MatchManager.RESULTS.containsKey(m.id), 150, () -> {
                    String res = MatchManager.RESULTS.getOrDefault(m.id, "aucun");
                    check(src, "ranked.result", res.startsWith("victoire:RKAce:victoire"), res);
                    if (b != null) {
                        boolean lvl50 = false;
                        String firstSwitch = "";
                        for (String chunk : b.getShowdownMessages()) {
                            for (String line : chunk.split("\n")) {
                                if (line.contains("|Garchomp, ") && line.contains(", L50")) lvl50 = true;
                                if (firstSwitch.isEmpty() && line.startsWith("|switch|" + sa.actorId + "a")) firstSwitch = line;
                            }
                        }
                        check(src, "ranked.level_cap", lvl50, null);
                        check(src, "ranked.lead", firstSwitch.contains("Garchomp"), firstSwitch);
                    }
                    RankedProfile a = RankedStore.get(ace.getUUID());
                    RankedProfile f = RankedStore.get(fish.getUUID());
                    check(src, "ranked.elo_update", a.stats("simple").elo == 1410 && f.stats("simple").elo == 1370 && a.stats("simple").wins == 1
                            && f.stats("simple").losses == 1, a.stats("simple").elo + "/" + f.stats("simple").elo);
                    check(src, "ranked.rank_up", a.stats("simple").rankRewards.contains("hyperball"), a.stats("simple").rankRewards.toString());
                    check(src, "ranked.history", !a.history.isEmpty() && "victoire".equals(a.history.get(0).result) && !f.history.isEmpty()
                            && "defaite".equals(f.history.get(0).result) && a.history.get(0).turns > 0, null);
                    RankedProfile.MissionState win1 = a.daily.get(0);
                    RankedProfile.MissionState play3 = a.daily.get(1);
                    RankedProfile.MissionState ko10 = a.daily.get(2);
                    check(src, "ranked.missions_progress", win1.done && play3.progress == 1 && !play3.done && ko10.progress == 3,
                            "gagner_1=" + win1.done + " jouer_3=" + play3.progress + " ko_10=" + ko10.progress);
                    boolean claimed = Missions.claim(ace, "gagner_1", false);
                    boolean again = Missions.claim(ace, "gagner_1", false);
                    check(src, "ranked.missions_claim", claimed && !again && win1.claimed, null);
                    boolean discordResult = Discord.SENT.stream().anyMatch(s -> s.startsWith("resultat_classe") && s.contains("RKAce"));
                    boolean discordRank = Discord.SENT.stream().anyMatch(s -> s.startsWith("montee_rang") && s.contains("RKAce"));
                    check(src, "ranked.discord_payload", discordResult && discordRank, Discord.SENT.isEmpty() ? "vide" : Discord.SENT.peekFirst());
                    int placeA = RankedStore.place("simple", a.uuid);
                    int placeF = RankedStore.place("simple", f.uuid);
                    check(src, "ranked.leaderboard", placeA >= 1 && placeA < placeF, placeA + "/" + placeF);
                    Pokemon after = CobblemonBridge.partyList(ace).get(0);
                    check(src, "ranked.party_untouched", after.getLevel() == 60 && after.getExperience() == expBefore
                                    && after.getCurrentHealth() == after.getMaxHealth(), "niveau " + after.getLevel() + " exp " + expBefore + " -> " + after.getExperience());
                    check(src, "ranked.no_restriction_after", !Ranked.busy(ace.getUUID()) && !Restrictions.blocked(ace, "pc"), null);
                    // Ecrans de consultation
                    RankedMenus.main(ace);
                    boolean mainOk = Screens.hasSession(ace.getUUID()) && Screens.names(ace.getUUID()).size() >= 6;
                    RankedMenus.leaderboard(ace, "simple");
                    boolean topOk = Screens.names(ace.getUUID()).stream().anyMatch(n -> n.contains("RKAce"));
                    RankedMenus.missions(ace);
                    boolean missionsOk = Screens.names(ace.getUUID()).size() >= 4;
                    RankedMenus.history(ace, a);
                    boolean histOk = Screens.names(ace.getUUID()).stream().anyMatch(n -> n.contains("RKFish"));
                    RankedMenus.profile(ace, fish.getUUID());
                    boolean profOk = Screens.names(ace.getUUID()).stream().anyMatch(n -> n.contains("RKFish"));
                    RankedMenus.ranks(ace);
                    boolean ranksOk = Screens.names(ace.getUUID()).size() >= 6;
                    check(src, "screens.ranked", mainOk && topOk && missionsOk && histOk && profOk && ranksOk,
                            mainOk + " " + topOk + " " + missionsOk + " " + histOk + " " + profOk + " " + ranksOk);
                    // Retour a la position d'avant l'arene
                    Scheduler.later(20 * 5, () -> {
                        check(src, "ranked.return", dist(ace, home) < 2 && dist(fish, home) < 2,
                                String.format("%.1f / %.1f", dist(ace, home), dist(fish, home)));
                        check(src, "ranked.arena_released", !Arenas.busy(arena), null);
                        // Nouvelle entree trop tot : delai entre deux matchs
                        check(src, "ranked.match_cooldown", !RankedQueue.join(ace, "simple", true), null);
                        done("ranked");
                    });
                });
            });
            });
        });
    }

    /** Etapes espacees de 3 ticks (les clics d'ecran sont traites au tick suivant). */
    static void chain(Runnable... steps) {
        for (int i = 0; i < steps.length; i++) Scheduler.later(3L * (i + 1), steps[i]);
    }

    // ------------------------------------------------------------------ Ranked : minuteur, deconnexion, nul, refus

    public static int rankedTimers(CommandSourceStack src) {
        fr.cobblepulse.battletower.command.SelfTest.startBot();
        startAuto();
        ServerLevel level = src.getServer().overworld();
        RankedConfig.testTurnTimer = 3;
        RankedConfig.testMatchSeconds = 40;
        // Minuteur de tour : RKIdle ne joue pas (3 tours expires = forfait), classe simple
        FakePlayer idle = fake(level, "RKIdle");
        FakePlayer busy = fake(level, "RKBusy");
        fr.cobblepulse.battletower.command.SelfTest.PASSIVE.add(idle.getUUID());
        // Deconnexion en combat : classe double
        FakePlayer quitA = fake(level, "RKQuitA");
        FakePlayer quitB = fake(level, "RKQuitB");
        // Duree de match depassee : nul au departage, amical simple
        FakePlayer tieA = fake(level, "RKTieA");
        FakePlayer tieB = fake(level, "RKTieB");
        // Refus : amical double
        FakePlayer decA = fake(level, "RKDecA");
        FakePlayer decB = fake(level, "RKDecB");
        // chaque paire au meme point (le match les ecarte avant le combat), les paires a distance les unes des autres
        loc(level, -8, 20).teleport(idle);
        loc(level, -8, 20).teleport(busy);
        loc(level, -22, 20).teleport(quitA);
        loc(level, -22, 20).teleport(quitB);
        loc(level, -8, 34).teleport(tieA);
        loc(level, -8, 34).teleport(tieB);
        loc(level, -22, 34).teleport(decA);
        loc(level, -22, 34).teleport(decB);
        for (FakePlayer p : List.of(idle, busy, quitA, quitB, tieA, tieB, decA, decB)) {
            RankedStore.get(p);
            if (p != decA && p != decB) AUTO.add(p.getUUID());
        }
        party(idle, splash(3));
        party(busy, splash(3));
        party(quitA, splash(4));
        party(quitB, splash(4));
        party(tieA, splash(3));
        party(tieB, splash(3));
        party(decA, splash(4));
        party(decB, splash(4));
        Screens.capture(decA.getUUID(), true);
        Screens.capture(decB.getUUID(), true);
        boolean joined = RankedQueue.join(idle, "simple", true) & RankedQueue.join(busy, "simple", true)
                & RankedQueue.join(quitA, "double", true) & RankedQueue.join(quitB, "double", true)
                & RankedQueue.join(tieA, "simple", false) & RankedQueue.join(tieB, "simple", false)
                & RankedQueue.join(decA, "double", false) & RankedQueue.join(decB, "double", false);
        check(src, "ranked.queue_formats", joined && RankedQueue.count("double", false) + RankedQueue.count("simple", false) <= 4, null);
        // Refus du match trouve
        waitFor(() -> {
            MatchManager.Match m = MatchManager.matchOf(decA.getUUID());
            return m != null && m.state == MatchManager.State.READY && Screens.slotNamed(decA.getUUID(), "Refuser") >= 0;
        }, 15, () -> {
            MatchManager.Match m = MatchManager.matchOf(decA.getUUID());
            check(src, "ranked.casual_double_match", m != null && !m.ranked && "double".equals(m.format), m == null ? "aucun" : m.format);
            click(decA, 15);
            chain(() -> {
                RankedProfile pd = RankedStore.get(decA);
                check(src, "ranked.decline", m != null && m.state == MatchManager.State.ENDED && pd.declineUntil > System.currentTimeMillis()
                        && RankedQueue.queued(decB.getUUID()) && !RankedQueue.queued(decA.getUUID()), m == null ? "-" : m.state.name());
                check(src, "ranked.decline_cooldown", !RankedQueue.join(decA, "double", false), null);
                RankedQueue.leave(decB.getUUID());
            });
        });
        // Deconnexion pendant le combat
        waitFor(() -> {
            MatchManager.Match m = MatchManager.matchOf(quitA.getUUID());
            return m != null && m.state == MatchManager.State.BATTLE;
        }, 40, () -> {
            MatchManager.Match m = MatchManager.matchOf(quitA.getUUID());
            String id = m == null ? "" : m.id;
            check(src, "ranked.double_battle", m != null && m.rules.doubles && CobblemonBridge.battle(m.battleId) != null, null);
            Ranked.onQuit(quitA);
            Scheduler.later(40, () -> {
                String res = MatchManager.RESULTS.getOrDefault(id, "aucun");
                RankedProfile pq = RankedStore.get(quitA);
                check(src, "ranked.disconnect", res.startsWith("victoire:RKQuitB:deconnexion") && pq.flees == 1
                        && pq.stats("double").losses == 1 && !CobblemonBridge.isInBattle(quitB), res + " fuites=" + pq.flees);
            });
        });
        // Minuteur de tour et duree de match
        String[] ids = new String[2];
        waitFor(() -> MatchManager.matchOf(idle.getUUID()) != null && MatchManager.matchOf(tieA.getUUID()) != null, 15, () -> {
            ids[0] = MatchManager.matchOf(idle.getUUID()).id;
            ids[1] = MatchManager.matchOf(tieA.getUUID()).id;
            // les regles sont figees a la creation du match : les autres tests reprennent les valeurs du fichier
            Scheduler.later(60, () -> {
                RankedConfig.testTurnTimer = 0;
                RankedConfig.testMatchSeconds = 0;
            });
            waitFor(() -> MatchManager.RESULTS.containsKey(ids[0]) && MatchManager.RESULTS.containsKey(ids[1]), 150, () -> {
                String timer = MatchManager.RESULTS.getOrDefault(ids[0], "aucun");
                RankedProfile pi = RankedStore.get(idle);
                check(src, "ranked.turn_timer_forfeit", timer.startsWith("victoire:RKBusy:minuteur") && pi.flees == 1, timer + " fuites=" + pi.flees);
                String tie = MatchManager.RESULTS.getOrDefault(ids[1], "aucun");
                RankedProfile pt = RankedStore.get(tieA);
                check(src, "ranked.match_duration_tie", tie.startsWith("nul:duree") && pt.stats("simple").elo == 1000
                        && !pt.history.isEmpty() && "nul".equals(pt.history.get(0).result) && pt.history.get(0).turns >= 3,
                        tie + (pt.history.isEmpty() ? "" : " tours=" + pt.history.get(0).turns));
                fr.cobblepulse.battletower.command.SelfTest.PASSIVE.remove(idle.getUUID());
                for (FakePlayer p : List.of(idle, busy, tieA, tieB)) AUTO.remove(p.getUUID());
                check(src, "ranked.battles_closed", !CobblemonBridge.isInBattle(idle) && !CobblemonBridge.isInBattle(tieA), null);
                done("rankedtimer");
            });
        });
        return 1;
    }

    // ------------------------------------------------------------------ Dresseurs hors Tour

    public static int trainers(CommandSourceStack src) {
        fr.cobblepulse.battletower.command.SelfTest.startBot();
        ServerLevel level = src.getServer().overworld();
        FakePlayer admin = fake(level, "TRAdmin");
        loc(level, 14, 0).teleport(admin);
        WorldTrainers.WorldTrainer route = WorldTrainers.get("route1");
        if (route == null) route = WorldTrainers.create(admin, "route1");
        TrainerSpec rs = fr.cobblepulse.battletower.command.SelfTest.trainer("Gamin Route", "EASY", mon("caterpie", 5, "tackle"));
        rs.world = new TrainerSpec.WorldOptions();
        rs.world.bp = 7;
        rs.world.items.add("minecraft:emerald 2");
        rs.world.rematchHours = 24;
        WorldTrainers.update(route, rs);
        WorldTrainers.refresh(src.getServer(), route);
        loc(level, 14, 10).teleport(admin);
        WorldTrainers.WorldTrainer champ = WorldTrainers.get("champion");
        if (champ == null) champ = WorldTrainers.create(admin, "champion");
        MonSpec chomp = mon("garchomp", 70, "dragonclaw", "earthquake");
        TrainerSpec cs = fr.cobblepulse.battletower.command.SelfTest.trainer("Champion", "HARD", chomp);
        cs.world = new TrainerSpec.WorldOptions();
        cs.world.mode = "exhibition";
        cs.world.lossCooldownMinutes = 5;
        cs.world.bp = 50;
        WorldTrainers.update(champ, cs);
        WorldTrainers.refresh(src.getServer(), champ);
        // relecture du fichier
        WorldTrainers.load();
        WorldTrainers.WorldTrainer reread = WorldTrainers.get("route1");
        check(src, "trainers.saved", reread != null && reread.trainer.world != null && reread.trainer.world.bp == 7
                && reread.trainer.world.items.size() == 1 && reread.npcUuid != null && WorldTrainers.get("champion") != null, null);
        WorldTrainers.WorldTrainer w1 = WorldTrainers.get("route1");
        WorldTrainers.WorldTrainer w2 = WorldTrainers.get("champion");
        check(src, "trainers.npc", WorldTrainers.npc(src.getServer(), w1, false) != null && WorldTrainers.npc(src.getServer(), w2, false) != null, null);
        // Victoire avec recompenses (mode normal : equipe reelle)
        FakePlayer win = fake(level, "TRWin");
        Screens.capture(win.getUUID(), true);
        party(win, mon("pikachu", 60, "thunderbolt", "quickattack"));
        loc(level, 12, 0).teleport(win);
        PlayerData dw = PlayerStore.get(win);
        long bp0 = dw.bp;
        int exp0 = CobblemonBridge.partyList(win).get(0).getExperience();
        TrainerMenus.challenge(win, w1);
        int fight = Screens.slotNamed(win.getUUID(), "Défier");
        check(src, "screens.trainer_challenge", fight == 20, Screens.names(win.getUUID()).toString());
        click(win, fight);
        Scheduler.later(10, () -> check(src, "trainers.battle_started", CobblemonBridge.isInBattle(win), null));
        // Defaite en exhibition (copie de l'equipe) : delai apres defaite
        FakePlayer lose = fake(level, "TRLose");
        party(lose, mon("magikarp", 5, "splash"));
        loc(level, 12, 10).teleport(lose);
        WorldTrainers.challenge(lose, w2);
        String wu = win.getUUID().toString();
        String lu = lose.getUUID().toString();
        waitFor(() -> WorldTrainers.RESULTS.containsKey(wu) && WorldTrainers.RESULTS.containsKey(lu), 120, () -> {
            String rw = WorldTrainers.RESULTS.getOrDefault(wu, "aucun");
            PlayerData d = PlayerStore.get(win);
            int emeralds = win.getInventory().countItem(Items.EMERALD);
            check(src, "trainers.win_rewards", rw.equals("route1:victoire:recompense") && d.bp == bp0 + 7 && emeralds == 2,
                    rw + " bp=" + (d.bp - bp0) + " emeraudes=" + emeralds);
            Pokemon pk = CobblemonBridge.partyList(win).get(0);
            check(src, "trainers.normal_mode_exp", pk.getExperience() > exp0, exp0 + " -> " + pk.getExperience());
            WorldTrainers.Availability a = WorldTrainers.availability(d, w1);
            check(src, "trainers.rematch_wait", a.status() == WorldTrainers.Status.WAIT_WIN && a.waitMs() > 23 * 3_600_000L,
                    a.status() + " " + a.waitMs());
            WorldTrainers.challenge(win, w1);
            check(src, "trainers.rematch_refused", !CobblemonBridge.isInBattle(win), null);
            PlayerData.TrainerRecord rec = d.trainers.get("route1");
            check(src, "trainers.record", rec != null && rec.wins == 1 && rec.firstWinAt > 0, null);
            String rl = WorldTrainers.RESULTS.getOrDefault(lu, "aucun");
            PlayerData dl = PlayerStore.get(lose);
            WorldTrainers.Availability al = WorldTrainers.availability(dl, w2);
            check(src, "trainers.loss_cooldown", rl.equals("champion:defaite") && al.status() == WorldTrainers.Status.WAIT_LOSS, rl + " " + al.status());
            Pokemon karp = CobblemonBridge.partyList(lose).get(0);
            check(src, "trainers.exhibition_protected", karp.getCurrentHealth() == karp.getMaxHealth() && dl.bp == 0, "pv " + karp.getCurrentHealth());
            // Revanche immediate avec recompenses
            w1.trainer.world.rematchHours = 0;
            w1.trainer.world.rematchRewards = true;
            check(src, "trainers.rematch_reward_status", WorldTrainers.availability(d, w1).status() == WorldTrainers.Status.REMATCH_REWARD, null);
            w1.trainer.world.rematchHours = 24;
            w1.trainer.world.rematchRewards = false;
            WorldTrainers.save();
            // Liste des dresseurs (ecran)
            TrainerMenus.list(win);
            check(src, "screens.trainer_list", Screens.names(win.getUUID()).stream().anyMatch(n -> n.contains("Gamin Route")),
                    Screens.names(win.getUUID()).toString());
            tool(src, admin, w1, () -> done("trainers"));
        });
        return 1;
    }

    /** Objet « Editeur de PNJ » : enregistrement, reserve au staff, edition d'un PNJ, liste, pose d'un dresseur. */
    static void tool(CommandSourceStack src, FakePlayer admin, WorldTrainers.WorldTrainer w1, Runnable after) {
        var tool = fr.cobblepulse.battles.tool.NpcEditorTool.class;
        check(src, "tool.registered", fr.cobblepulse.battles.tool.NpcEditorTool.ITEM != null
                && "cobblepulse_battles:editeur_pnj".equals(net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(fr.cobblepulse.battles.tool.NpcEditorTool.ITEM).toString()), tool.getSimpleName());
        FakePlayer visitor = fake(src.getServer().overworld(), "TRVisitor");
        com.cobblemon.mod.common.entity.npc.NPCEntity npc = WorldTrainers.npc(src.getServer(), w1, false);
        fr.cobblepulse.battletower.net.EditorService.OPENED.remove(visitor.getUUID());
        if (npc != null) fr.cobblepulse.battles.tool.NpcEditorTool.onNpc(visitor, npc);
        check(src, "tool.staff_only", !fr.cobblepulse.battles.tool.NpcEditorTool.allowed(visitor)
                && !fr.cobblepulse.battletower.net.EditorService.OPENED.containsKey(visitor.getUUID()), null);
        src.getServer().getPlayerList().op(admin.getGameProfile());
        Screens.capture(admin.getUUID(), true);
        admin.getInventory().clearContent();
        fr.cobblepulse.battles.tool.NpcEditorTool.give(admin);
        boolean inHand = fr.cobblepulse.battles.tool.NpcEditorTool.is(admin.getMainHandItem());
        if (npc != null) fr.cobblepulse.battles.tool.NpcEditorTool.onNpc(admin, npc);
        check(src, "tool.edit_world_npc", inHand && fr.cobblepulse.battles.tool.NpcEditorTool.allowed(admin)
                && ("world:" + w1.id).equals(fr.cobblepulse.battletower.net.EditorService.OPENED.get(admin.getUUID())),
                fr.cobblepulse.battletower.net.EditorService.OPENED.get(admin.getUUID()));
        int floor = 0;
        for (net.minecraft.world.entity.Entity e : src.getServer().overworld().getAllEntities()) {
            if (e instanceof com.cobblemon.mod.common.entity.npc.NPCEntity n && fr.cobblepulse.battletower.npc.TrainerNpcs.floorOf(n) > 0) {
                floor = fr.cobblepulse.battletower.npc.TrainerNpcs.floorOf(n);
                fr.cobblepulse.battles.tool.NpcEditorTool.onNpc(admin, n);
                break;
            }
        }
        check(src, "tool.edit_floor_npc", floor == 0 || ("floor:" + floor).equals(fr.cobblepulse.battletower.net.EditorService.OPENED.get(admin.getUUID())),
                floor == 0 ? "aucun PNJ d'etage (tests de la Tour non lances)" : "etage " + floor + " -> " + fr.cobblepulse.battletower.net.EditorService.OPENED.get(admin.getUUID()));
        // clic dans le vide : liste des dresseurs (avec le bouton de l'outil pour le staff)
        fr.cobblepulse.battles.tool.NpcEditorTool.ITEM.use(admin.level(), admin, net.minecraft.world.InteractionHand.MAIN_HAND);
        check(src, "tool.air_list", Screens.names(admin.getUUID()).stream().anyMatch(n -> n.contains("Gamin Route"))
                && Screens.slotNamed(admin.getUUID(), "Éditeur de PNJ") == 51, Screens.names(admin.getUUID()).toString());
        // clic sur un bloc : nouveau dresseur pose dessus
        BlockPos ground = BlockPos.containing(loc(src.getServer().overworld(), 18, 4).x, loc(src.getServer().overworld(), 18, 4).y - 1,
                loc(src.getServer().overworld(), 18, 4).z);
        fr.cobblepulse.battles.tool.NpcEditorTool.onBlock(admin, ground, net.minecraft.core.Direction.UP);
        fr.cobblepulse.battletower.gui.ChatInput.answer(admin, "outil_test", false);
        // la reponse saisie est traitee au tick suivant
        Scheduler.later(3, () -> {
            WorldTrainers.WorldTrainer placed = WorldTrainers.get("outil_test");
            check(src, "tool.place_trainer", placed != null && placed.location != null && (int) Math.floor(placed.location.y) == ground.getY() + 1
                            && placed.npcUuid != null && ("world:outil_test").equals(fr.cobblepulse.battletower.net.EditorService.OPENED.get(admin.getUUID())),
                    placed == null ? "absent" : placed.location.pretty());
            if (placed != null) WorldTrainers.remove(src.getServer(), placed);
            after.run();
        });
    }

    // ------------------------------------------------------------------ Ecrans sur un vrai client (CI : client de production connecte)

    /** Ouvre chaque ecran pour un vrai joueur connecte et demande une capture au client de test. */
    public static int clientScreens(CommandSourceStack src, String name) {
        ServerPlayer p = src.getServer().getPlayerList().getPlayerByName(name);
        if (p == null) {
            check(src, "client.player", false, name + " absent");
            return 0;
        }
        ServerLevel level = src.getServer().overworld();
        src.getServer().getPlayerList().op(p.getGameProfile());
        party(p, mon("garchomp", 60, "dragonclaw", "earthquake", "swordsdance", "stoneedge"), mon("lucario", 55, "aurasphere", "flashcannon"),
                mon("gyarados", 58, "waterfall", "dragondance"), mon("rotom", 50, "thunderbolt"), mon("snorlax", 52, "bodyslam"),
                mon("gengar", 54, "shadowball"));
        Loc here = Loc.of(p);
        WorldTrainers.WorldTrainer w = WorldTrainers.get("route_ci");
        if (w == null) {
            w = WorldTrainers.create(p, "route_ci", new Loc(here.world, here.x + 3, here.y, here.z + 3, 135f, 0f));
            TrainerSpec t = fr.cobblepulse.battletower.command.SelfTest.trainer("Gamin du Lac", "MEDIUM", mon("magikarp", 12, "splash"),
                    mon("goldeen", 14, "peck"), mon("psyduck", 15, "watergun"));
            t.world = new TrainerSpec.WorldOptions();
            t.world.bp = 15;
            t.world.items.add("cobblemon:rare_candy 2");
            WorldTrainers.update(w, t);
            WorldTrainers.refresh(src.getServer(), w);
        }
        WorldTrainers.WorldTrainer tw = w;
        FakePlayer shadow = fake(level, "Rival");
        party(shadow, mon("charizard", 50, "flamethrower"), mon("blastoise", 50, "surf"), mon("venusaur", 50, "gigadrain"),
                mon("pikachu", 50, "thunderbolt"));
        Loc.of(p).teleport(shadow);
        RankedProfile rp = RankedStore.get(p);
        rp.stats("simple").elo = 1452;
        rp.stats("simple").wins = 12;
        rp.stats("simple").losses = 5;
        rp.stats("double").elo = 1218;
        RankedStore.get(shadow).stats("simple").elo = 1460;
        Runnable[] steps = {
                () -> Hub.open(p), () -> shot(p, "bt_hub"),
                () -> RankedMenus.main(p), () -> shot(p, "bt_ranked_main"),
                () -> RankedMenus.leaderboard(p, "simple"), () -> shot(p, "bt_ranked_top"),
                () -> RankedMenus.missions(p), () -> shot(p, "bt_ranked_missions"),
                () -> RankedMenus.ranks(p), () -> shot(p, "bt_ranked_ranks"),
                () -> TrainerMenus.challenge(p, tw), () -> shot(p, "bt_trainer_challenge"),
                () -> TrainerMenus.list(p), () -> shot(p, "bt_trainer_list"),
                () -> {
                    Gui.close(p);
                    RankedQueue.join(p, "simple", true);
                    RankedQueue.join(shadow, "simple", true);
                }, () -> shot(p, "bt_ranked_ready"),
                () -> {
                    MatchManager.accept(p);
                    MatchManager.accept(shadow);
                }, () -> shot(p, "bt_ranked_select"),
                () -> {
                    MatchManager.toggle(p, 0);
                    MatchManager.toggle(p, 2);
                }, () -> shot(p, "bt_ranked_select_picked"),
                () -> {
                    MatchManager.decline(p);
                    Gui.close(p);
                    p.getInventory().selected = 0;
                    p.getInventory().setItem(0, fr.cobblepulse.battles.tool.NpcEditorTool.stack());
                }, () -> shot(p, "bt_tool_hand"),
                () -> {
                    check(src, "client.screens_sent", true, null);
                    Net.send(p, "selftest", "{\"done\":true}");
                    done("clientscreens");
                }
        };
        for (int i = 0; i < steps.length; i++) Scheduler.later(30L * (i + 1), steps[i]);
        return 1;
    }

    private static void shot(ServerPlayer p, String name) {
        Net.send(p, "selftest", "{\"shot\":\"" + name + "\"}");
    }

    // ------------------------------------------------------------------ Multi-serveur (CI : serveurs lobby + combat, Redis, MySQL)

    /** Serveur « lobby » : deux joueurs entrent en file ; le serveur de combat les apparie et ils y sont transferes. */
    public static int multiLobby(CommandSourceStack src) {
        ServerLevel level = src.getServer().overworld();
        check(src, "multi.lobby_config", fr.cobblepulse.battles.ranked.CrossServer.enabled() && !fr.cobblepulse.battles.ranked.CrossServer.isMatchmaker()
                && "lobby".equals(fr.cobblepulse.battles.ranked.CrossServer.serverId()), fr.cobblepulse.battles.ranked.CrossServer.serverId());
        FakePlayer a = fake(level, "MSAce");
        FakePlayer b = fake(level, "MSFish");
        party(a, mon("garchomp", 60, "dragonclaw"), mon("lucario", 60, "flashcannon"), mon("gyarados", 60, "waterfall"), mon("snorlax", 60, "bodyslam"));
        party(b, splash(3));
        loc(level, 0, 0).teleport(a);
        loc(level, 0, 0).teleport(b);
        RankedStore.get(a);
        RankedStore.get(b);
        boolean ja = RankedQueue.join(a, "simple", true);
        boolean jb = RankedQueue.join(b, "simple", true);
        check(src, "multi.queue_join", ja && jb, ja + "/" + jb);
        waitFor(() -> RankedQueue.count("simple", true) >= 2, 10, () -> {
            check(src, "multi.queue_shared", RankedQueue.count("simple", true) >= 2 || fr.cobblepulse.battles.ranked.CrossServer.TRANSFERS.size() >= 2,
                    "file vue par le lobby : " + RankedQueue.count("simple", true));
            waitFor(() -> "combat".equals(fr.cobblepulse.battles.ranked.CrossServer.TRANSFERS.get(a.getUUID().toString()))
                    && "combat".equals(fr.cobblepulse.battles.ranked.CrossServer.TRANSFERS.get(b.getUUID().toString())), 40, () -> {
                check(src, "multi.transfer_to_battle", "combat".equals(fr.cobblepulse.battles.ranked.CrossServer.TRANSFERS.get(a.getUUID().toString()))
                        && "combat".equals(fr.cobblepulse.battles.ranked.CrossServer.TRANSFERS.get(b.getUUID().toString())),
                        fr.cobblepulse.battles.ranked.CrossServer.TRANSFERS.toString());
                check(src, "multi.queue_left", !RankedQueue.queued(a.getUUID()) && !RankedQueue.queued(b.getUUID()), null);
                fr.cobblepulse.battles.ranked.CrossServer.TRANSFERS.clear();
                done("multi-lobby");
            });
        });
        return 1;
    }

    /** Serveur « combat » : les joueurs « arrivent », l'equipe vient du lobby (Redis), combat, Elo, retour. */
    public static int multiCombat(CommandSourceStack src) {
        fr.cobblepulse.battletower.command.SelfTest.startBot();
        startAuto();
        ServerLevel level = src.getServer().overworld();
        check(src, "multi.battle_config", fr.cobblepulse.battles.ranked.CrossServer.enabled() && fr.cobblepulse.battles.ranked.CrossServer.isMatchmaker(), null);
        MatchManager.Match found = null;
        for (MatchManager.Match m : MatchManager.all()) if ("MSAce".equals(m.a.name) || "MSAce".equals(m.b.name)) found = m;
        MatchManager.Match m = found;
        check(src, "multi.match_waiting_arrival", m != null && m.state == MatchManager.State.ARRIVING, m == null ? "aucun match" : m.state.name());
        if (m == null) {
            done("multi-combat");
            return 0;
        }
        FakePlayer a = fake(level, "MSAce");
        FakePlayer b = fake(level, "MSFish");
        loc(level, 0, 0).teleport(a);
        loc(level, 0, 0).teleport(b);
        check(src, "multi.empty_local_party", CobblemonBridge.partyList(a).isEmpty(), null);
        AUTO.add(a.getUUID());
        AUTO.add(b.getUUID());
        Discord.SENT.clear();
        Ranked.onJoin(a);
        Ranked.onJoin(b);
        waitFor(() -> m.state == MatchManager.State.BATTLE || m.state == MatchManager.State.ENDED, 40, () -> {
            MatchManager.Side sa = m.side(a.getUUID().toString());
            boolean fromLobby = sa != null && sa.party.size() == 4 && "garchomp".equals(sa.party.get(0).getSpecies().getName().toLowerCase(java.util.Locale.ROOT));
            check(src, "multi.party_from_lobby", fromLobby, sa == null ? "-" : sa.party.size() + " Pokemon");
            check(src, "multi.battle_started", m.state == MatchManager.State.BATTLE, m.state.name());
            waitFor(() -> MatchManager.RESULTS.containsKey(m.id), 150, () -> {
                String res = MatchManager.RESULTS.getOrDefault(m.id, "aucun");
                check(src, "multi.result", res.startsWith("victoire:MSAce:victoire") && res.contains("1000->1045") && res.contains("1000->1000"), res);
                check(src, "multi.discord", Discord.SENT.stream().anyMatch(x -> x.startsWith("resultat_classe")), null);
                Scheduler.later(20 * 6, () -> {
                    check(src, "multi.return_to_origin", "lobby".equals(fr.cobblepulse.battles.ranked.CrossServer.TRANSFERS.get(a.getUUID().toString()))
                            && "lobby".equals(fr.cobblepulse.battles.ranked.CrossServer.TRANSFERS.get(b.getUUID().toString())),
                            fr.cobblepulse.battles.ranked.CrossServer.TRANSFERS.toString());
                    AUTO.remove(a.getUUID());
                    AUTO.remove(b.getUUID());
                    done("multi-combat");
                });
            });
        });
        return 1;
    }

    /** Retour au lobby : profil relu depuis MySQL apres le message « profil » du serveur de combat. */
    public static int multiCheck(CommandSourceStack src) {
        RankedProfile a = RankedStore.get(UUID.nameUUIDFromBytes("bt-MSAce".getBytes(StandardCharsets.UTF_8)));
        RankedProfile b = RankedStore.get(UUID.nameUUIDFromBytes("bt-MSFish".getBytes(StandardCharsets.UTF_8)));
        check(src, "multi.profile_synced", a != null && a.stats("simple").elo == 1045 && a.stats("simple").wins == 1 && !a.history.isEmpty()
                && b != null && b.stats("simple").losses == 1, a == null ? "absent" : "elo=" + a.stats("simple").elo);
        check(src, "multi.lobby_idle", !MatchManager.inMatch(UUID.nameUUIDFromBytes("bt-MSAce".getBytes(StandardCharsets.UTF_8)))
                && RankedQueue.count("simple", true) == 0, null);
        // profil plus ancien ecrit en retard par un autre serveur : ignore (ecriture conditionnelle)
        if (a != null) {
            RankedProfile stale = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(a), RankedProfile.class);
            stale.stats("simple").elo = 1;
            String json = new com.google.gson.Gson().toJson(stale);
            String uuid = a.uuid;
            DataService.async(s -> {
                s.saveRankedProfile(uuid, "MSAce", 1L, json);
                return s.loadRankedProfile(uuid);
            }).whenComplete((v, e) -> DataService.main(() -> {
                check(src, "multi.stale_write_ignored", v != null && v.contains("\"elo\":1045"), e == null ? null : e.toString());
                done("multi-check");
            }));
        } else {
            done("multi-check");
        }
        return 1;
    }

    // ------------------------------------------------------------------ Saisons

    public static int season(CommandSourceStack src) {
        UUID id = UUID.nameUUIDFromBytes("rk-season".getBytes(StandardCharsets.UTF_8));
        RankedProfile p = RankedStore.getOrCreate(id, "RKSeason");
        p.season = "testold";
        RankedProfile.FormatStats st = p.stats("simple");
        st.elo = 1300;
        st.wins = 3;
        st.streak = 3;
        st.rankRewards.add("superball");
        p.pending.clear();
        RankedStore.mark(p);
        Discord.SENT.clear();
        Seasons.testRotate("testold");
        check(src, "season.reset", st.elo == 1150 && st.wins == 0 && st.streak == 0 && st.rankRewards.isEmpty() && Seasons.currentId().equals(p.season),
                "elo=" + st.elo + " saison=" + p.season);
        check(src, "season.rewards_pending", p.pending.size() == 2, p.pending.toString());
        check(src, "season.discord", Discord.SENT.stream().anyMatch(s -> s.startsWith("fin_saison") && s.contains("RKSeason")), null);
        RankedProfile ace = RankedStore.byName("RKAce");
        check(src, "season.current_untouched", ace == null || ace.stats("simple").elo == 1410, ace == null ? "absent" : String.valueOf(ace.stats("simple").elo));
        DataService.async(s -> s.loadKv("ranked_archive_testold")).whenComplete((v, e) -> DataService.main(() -> {
            check(src, "season.archive", v != null && v.contains("RKSeason"), v == null ? "absente" : v.length() + " car.");
            done("season");
        }));
        return 1;
    }

    // ------------------------------------------------------------------ Stockage des profils (JSON, MySQL, SQLite)

    public static int storage(CommandSourceStack src) {
        UUID id = UUID.nameUUIDFromBytes("rk-store".getBytes(StandardCharsets.UTF_8));
        RankedProfile p = RankedStore.getOrCreate(id, "RKStore");
        p.stats("simple").elo = 1234;
        p.stats("double").wins = 5;
        p.flees = 2;
        RankedStore.saveNow(p, () -> DataService.async(s -> s.loadRankedProfile(id.toString())).whenComplete((json, e) -> DataService.main(() -> {
            boolean ok = json != null && json.contains("1234") && json.contains("RKStore");
            check(src, "ranked.storage_roundtrip", ok, DataService.storage().name() + (e == null ? "" : " " + e));
            done("rankedstorage");
        })));
        java.util.Map<String, Object> log = new java.util.LinkedHashMap<>();
        log.put("id", "selftest");
        log.put("format", "simple");
        RankedStore.logMatch(log);
        return 1;
    }

    /** Apres redemarrage (tous les stockages). */
    public static int storagePersisted(CommandSourceStack src) {
        RankedProfile p = RankedStore.byName("RKStore");
        check(src, "persist.ranked_profile", p != null && p.stats("simple").elo == 1234 && p.stats("double").wins == 5 && p.flees == 2,
                p == null ? "absent" : "elo=" + p.stats("simple").elo);
        return 1;
    }

    /** Apres redemarrage (stockage JSON, apres les tests complets). */
    public static int persisted(CommandSourceStack src) {
        RankedProfile ace = RankedStore.byName("RKAce");
        check(src, "persist.ranked_match", ace != null && ace.stats("simple").elo == 1410 && !ace.history.isEmpty() && ace.daily.get(0).claimed,
                ace == null ? "absent" : "elo=" + ace.stats("simple").elo);
        RankedProfile idle = RankedStore.byName("RKIdle");
        check(src, "persist.ranked_flees", idle != null && idle.flees == 1, null);
        RankedProfile season = RankedStore.byName("RKSeason");
        check(src, "persist.season_reset", season != null && season.stats("simple").elo == 1150 && season.pending.size() == 2, null);
        check(src, "persist.arena", Arenas.get("selftest") != null && Arenas.get("selftest").ready(), null);
        WorldTrainers.WorldTrainer w = WorldTrainers.get("route1");
        check(src, "persist.world_trainer", w != null && w.trainer.world.bp == 7 && WorldTrainers.npc(src.getServer(), w, false) != null, null);
        PlayerData d = PlayerStore.byName("TRWin");
        PlayerData.TrainerRecord r = d == null ? null : d.trainers.get("route1");
        check(src, "persist.trainer_record", r != null && r.wins == 1 && w != null
                && WorldTrainers.availability(d, w).status() == WorldTrainers.Status.WAIT_WIN, null);
        check(src, "persist.queue_empty", RankedQueue.localEntries().isEmpty() && MatchManager.all().isEmpty(), null);
        // Migration de l'ancien dossier de configuration (cree par la CI avant le premier demarrage)
        boolean migrated = Files.exists(Cfg.skinsDir().resolve("Legacy-Marker.png"));
        check(src, "migration.legacy_config", migrated && (!Files.isDirectory(Cfg.legacyRoot())
                || Files.exists(Cfg.legacyRoot().resolve("DEPLACE_VERS_cobblepulse_battles.txt"))), null);
        return 1;
    }
}
