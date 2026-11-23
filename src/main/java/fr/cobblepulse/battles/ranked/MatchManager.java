package fr.cobblepulse.battles.ranked;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleRules;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.ErroredBattleStart;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobblepulse.battles.common.AutoChoice;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.battle.CobblemonBridge;
import fr.cobblepulse.battletower.core.Fx;
import fr.cobblepulse.battletower.core.Loc;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Players;
import fr.cobblepulse.battletower.core.Rewards;
import fr.cobblepulse.battletower.core.Scheduler;
import fr.cobblepulse.battletower.core.TextUtil;
import fr.cobblepulse.battletower.gui.Gui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deroulement d'un match : arrivee (multi-serveur), confirmation, selection de l'equipe et du meneur, arene, compte a
 * rebours, combat (minuteur de tour, duree maximale), puis resultats (Elo, missions, recompenses, penalites).
 */
public final class MatchManager {
    public enum State { ARRIVING, READY, SELECT, COUNTDOWN, BATTLE, ENDED }

    public static final class Side {
        public final String uuid;
        public final String name;
        public final String server;
        public final int elo;
        final RankedQueue.Entry entry;
        public boolean arrived;
        public boolean accepted;
        public boolean confirmed;
        public boolean forfeited;
        public boolean disconnected;
        public boolean partyLoaded;
        public List<Pokemon> party = new ArrayList<>();
        public final List<Integer> picks = new ArrayList<>();
        public Loc returnLoc;
        public long waitingSince;
        public int timeouts;
        public int kos;
        public String actorId = "p1";

        Side(RankedQueue.Entry e) {
            this.entry = e;
            this.uuid = e.uuid;
            this.name = e.name;
            this.server = e.server == null ? CrossServer.serverId() : e.server;
            this.elo = e.elo;
        }

        public ServerPlayer player() {
            return Players.get(UUID.fromString(uuid));
        }

        public List<Pokemon> selection() {
            List<Pokemon> out = new ArrayList<>();
            for (int i : picks) if (i >= 0 && i < party.size()) out.add(party.get(i));
            return out;
        }
    }

    public static final class Match {
        public final String id;
        public final String format;
        public final boolean ranked;
        public final FormatRules rules;
        public final Side a;
        public final Side b;
        public State state = State.READY;
        public long deadline;
        public Arenas.Arena arena;
        public UUID battleId;
        public long battleStart;
        public long battleGoneSince;
        public int countdown;
        public boolean tiebreak;
        public String endReason = "";
        final Set<Integer> warned = new HashSet<>();

        Match(String id, RankedQueue.Entry ea, RankedQueue.Entry eb) {
            this.id = id;
            this.format = ea.format;
            this.ranked = ea.ranked;
            this.rules = RankedConfig.rules(ea.format);
            this.a = new Side(ea);
            this.b = new Side(eb);
            this.b.actorId = "p2";
        }

        public Side side(String uuid) {
            return a.uuid.equals(uuid) ? a : b.uuid.equals(uuid) ? b : null;
        }

        public Side other(Side s) {
            return s == a ? b : a;
        }

        public long remainingMs() {
            return Math.max(0, deadline - System.currentTimeMillis());
        }
    }

    private static final Map<String, Match> MATCHES = new LinkedHashMap<>();
    private static final Map<String, Match> BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, Match> BY_BATTLE = new ConcurrentHashMap<>();
    /** Auto-tests : derniers resultats (identifiant du match -> resume). */
    public static final Map<String, String> RESULTS = new ConcurrentHashMap<>();

    private MatchManager() {}

    public static boolean inMatch(UUID uuid) {
        return BY_PLAYER.containsKey(uuid.toString());
    }

    public static Match matchOf(UUID uuid) {
        return BY_PLAYER.get(uuid.toString());
    }

    public static Match byBattle(UUID battleId) {
        return battleId == null ? null : BY_BATTLE.get(battleId);
    }

    public static List<Match> all() {
        return new ArrayList<>(MATCHES.values());
    }

    // ------------------------------------------------------------------ creation

    static void create(RankedQueue.Entry ea, RankedQueue.Entry eb) {
        String id = Long.toString(System.currentTimeMillis(), 36) + "-" + Integer.toString((int) (Math.random() * 46656), 36);
        Match m = new Match(id, ea, eb);
        if (m.rules == null) return;
        MATCHES.put(id, m);
        BY_PLAYER.put(m.a.uuid, m);
        BY_PLAYER.put(m.b.uuid, m);
        Battletower.LOG.info("[Battles] Match {} trouve : {} ({}) contre {} ({}) en {} {}", id, ea.name, ea.elo, eb.name, eb.elo,
                ea.format, ea.ranked ? "classe" : "amical");
        m.a.arrived = m.a.player() != null;
        m.b.arrived = m.b.player() != null;
        if (CrossServer.enabled() && (!m.a.arrived || !m.b.arrived)) {
            m.state = State.ARRIVING;
            var t = RankedConfig.network;
            m.deadline = System.currentTimeMillis() + (t.i("multi_serveur.delai_transfert", 5) + t.i("multi_serveur.delai_arrivee", 30)) * 1000L;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("format", ea.format);
            data.put("ranked", ea.ranked);
            data.put("serveur", CrossServer.serverId());
            data.put("a", Map.of("uuid", ea.uuid, "name", ea.name, "server", m.a.server, "elo", ea.elo));
            data.put("b", Map.of("uuid", eb.uuid, "name", eb.name, "server", m.b.server, "elo", eb.elo));
            CrossServer.publish("match_trouve", data);
            for (Side s : List.of(m.a, m.b)) {
                ServerPlayer p = s.player();
                if (p != null) Fx.title(p, Msg.formatBare("ranked.found_title"), Msg.formatBare("ranked.found_waiting"));
            }
            return;
        }
        ready(m);
    }

    /** Serveur d'origine : un match a ete trouve pour un de nos joueurs ; equipe transmise puis transfert. */
    static void onRemoteMatch(JsonObject o) {
        String server = o.get("serveur").getAsString();
        for (String k : List.of("a", "b")) {
            JsonObject s = o.getAsJsonObject(k);
            String uuid = s.get("uuid").getAsString();
            ServerPlayer p = Players.get(UUID.fromString(uuid));
            if (p == null || server.equalsIgnoreCase(CrossServer.serverId())) continue;
            RankedQueue.leave(p.getUUID());
            String party = snapshotParty(p);
            CrossServer.putParty(uuid, party);
            JsonObject foe = o.getAsJsonObject(k.equals("a") ? "b" : "a");
            Fx.title(p, Msg.formatBare("ranked.found_title"), Msg.formatBare("ranked.found_transfer", "opponent", foe.get("name").getAsString()));
            Fx.sound(p, SoundEvents.PLAYER_LEVELUP, 1f, 1.2f);
            int delay = RankedConfig.network.i("multi_serveur.delai_transfert", 5);
            Msg.send(p, "ranked.transfer_soon", "server", server, "seconds", delay);
            Scheduler.later(Math.max(1, delay) * 20L, () -> {
                ServerPlayer again = Players.get(UUID.fromString(uuid));
                if (again != null) CrossServer.transfer(again, server);
            });
        }
    }

    /** Equipe du joueur serialisee (JSON Cobblemon) pour le serveur de combat. */
    static String snapshotParty(ServerPlayer p) {
        JsonArray arr = new JsonArray();
        for (Pokemon pk : CobblemonBridge.partyList(p)) {
            if (pk == null) continue;
            try {
                arr.add(pk.saveToJSON(p.registryAccess(), new JsonObject()));
            } catch (Throwable t) {
                Battletower.LOG.warn("[Battles] Pokemon non transmis ({}) : {}", pk.getSpecies().getName(), t.toString());
            }
        }
        return arr.toString();
    }

    static List<Pokemon> readParty(ServerPlayer anyPlayer, String json) {
        List<Pokemon> out = new ArrayList<>();
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (var el : arr) {
                try {
                    Pokemon pk = new Pokemon();
                    pk.loadFromJSON(anyPlayer.registryAccess(), el.getAsJsonObject());
                    out.add(pk);
                } catch (Throwable t) {
                    Battletower.LOG.warn("[Battles] Pokemon transmis illisible : {}", t.toString());
                }
            }
        } catch (Throwable t) {
            Battletower.LOG.warn("[Battles] Equipe transmise illisible : {}", t.toString());
        }
        return out;
    }

    public static void onJoin(ServerPlayer p) {
        Match m = matchOf(p.getUUID());
        if (m == null) return;
        Side s = m.side(p.getUUID().toString());
        if (s == null) return;
        s.arrived = true;
        if (m.state == State.ARRIVING && m.a.arrived && m.b.arrived) ready(m);
    }

    // ------------------------------------------------------------------ confirmation

    static void ready(Match m) {
        m.state = State.READY;
        m.deadline = System.currentTimeMillis() + RankedConfig.general.i("minuteurs.pret", 17) * 1000L;
        for (Side s : List.of(m.a, m.b)) {
            ServerPlayer p = s.player();
            if (p == null) continue;
            Side o = m.other(s);
            Fx.title(p, Msg.formatBare("ranked.found_title"), Msg.formatBare("ranked.found_vs", "opponent", o.name));
            Fx.sound(p, SoundEvents.PLAYER_LEVELUP, 1f, 1.2f);
            RankedMenus.ready(p, m);
        }
    }

    public static void accept(ServerPlayer p) {
        Match m = matchOf(p.getUUID());
        if (m == null || m.state != State.READY) return;
        Side s = m.side(p.getUUID().toString());
        s.accepted = true;
        Msg.send(p, "ranked.accepted");
        if (m.a.accepted && m.b.accepted) select(m);
    }

    public static void decline(ServerPlayer p) {
        Match m = matchOf(p.getUUID());
        if (m == null || (m.state != State.READY && m.state != State.SELECT)) return;
        cancel(m, Set.of(m.side(p.getUUID().toString())), "ranked.cancel_declined");
    }

    // ------------------------------------------------------------------ selection

    static void select(Match m) {
        m.state = State.SELECT;
        m.deadline = System.currentTimeMillis() + RankedConfig.general.i("minuteurs.selection", 60) * 1000L;
        for (Side s : List.of(m.a, m.b)) loadParty(m, s);
    }

    private static void loadParty(Match m, Side s) {
        ServerPlayer p = s.player();
        if (p == null) return;
        if (CrossServer.enabled() && !s.server.equalsIgnoreCase(CrossServer.serverId())) {
            CrossServer.getParty(s.uuid, json -> {
                ServerPlayer again = s.player();
                if (again == null || m.state != State.SELECT) return;
                s.party = json == null ? CobblemonBridge.partyList(again) : readParty(again, json);
                s.party.removeIf(java.util.Objects::isNull);
                s.partyLoaded = true;
                RankedMenus.select(again, m, s);
            });
            return;
        }
        s.party = new ArrayList<>(CobblemonBridge.partyList(p));
        s.party.removeIf(java.util.Objects::isNull);
        s.partyLoaded = true;
        RankedMenus.select(p, m, s);
    }

    /** Clic sur un Pokemon de l'equipe : ajoute (dans l'ordre) ou retire de la selection. */
    public static void toggle(ServerPlayer p, int index) {
        Match m = matchOf(p.getUUID());
        if (m == null || m.state != State.SELECT) return;
        Side s = m.side(p.getUUID().toString());
        if (s.confirmed || index < 0 || index >= s.party.size()) return;
        Integer idx = index;
        if (s.picks.contains(idx)) {
            s.picks.remove(idx);
        } else {
            if (s.picks.size() >= m.rules.teamSize) {
                Msg.send(p, "ranked.select_full", "n", m.rules.teamSize);
                return;
            }
            Pokemon pk = s.party.get(index);
            String why = TeamRules.checkPokemon(pk, m.rules);
            if (why != null) {
                Msg.send(p, "ranked.select_refused", "reason", why);
                return;
            }
            if (!TeamRules.fits(s.selection(), pk, m.rules)) {
                Msg.send(p, "ranked.select_clause");
                return;
            }
            s.picks.add(idx);
        }
        RankedMenus.refreshSelect(p, m, s);
    }

    public static void autoSelect(ServerPlayer p) {
        Match m = matchOf(p.getUUID());
        if (m == null || m.state != State.SELECT) return;
        Side s = m.side(p.getUUID().toString());
        if (s.confirmed) return;
        applyAuto(s, m.rules);
        RankedMenus.refreshSelect(p, m, s);
    }

    private static void applyAuto(Side s, FormatRules r) {
        List<Pokemon> auto = TeamRules.autoPick(s.party, s.selection(), r);
        s.picks.clear();
        for (Pokemon pk : auto) s.picks.add(s.party.indexOf(pk));
    }

    public static void confirm(ServerPlayer p) {
        Match m = matchOf(p.getUUID());
        if (m == null || m.state != State.SELECT) return;
        Side s = m.side(p.getUUID().toString());
        if (s.confirmed) return;
        List<String> errors = TeamRules.checkSelection(s.selection(), m.rules);
        if (!errors.isEmpty()) {
            Msg.send(p, "ranked.select_invalid", "reason", errors.get(0));
            return;
        }
        s.confirmed = true;
        Msg.send(p, "ranked.select_confirmed");
        if (m.a.confirmed && m.b.confirmed) countdown(m);
        else RankedMenus.refreshSelect(p, m, s);
    }

    // ------------------------------------------------------------------ arene, compte a rebours, combat

    static void countdown(Match m) {
        for (Side s : List.of(m.a, m.b)) {
            ServerPlayer p = s.player();
            if (p != null) Gui.close(p);
        }
        if (Arenas.anyConfigured()) {
            m.arena = Arenas.reserve();
            if (m.arena != null) {
                ServerPlayer pa = m.a.player();
                ServerPlayer pb = m.b.player();
                if (pa != null) {
                    m.a.returnLoc = Loc.of(pa);
                    m.arena.pos1.teleport(pa);
                }
                if (pb != null) {
                    m.b.returnLoc = Loc.of(pb);
                    m.arena.pos2.teleport(pb);
                }
            } else {
                for (Side s : List.of(m.a, m.b)) {
                    ServerPlayer p = s.player();
                    if (p != null) Msg.send(p, "ranked.no_arena");
                }
            }
        }
        m.state = State.COUNTDOWN;
        m.countdown = Math.max(0, RankedConfig.general.i("minuteurs.compte_a_rebours", 5));
        m.deadline = System.currentTimeMillis() + m.countdown * 1000L;
    }

    static BattleFormat format(FormatRules r) {
        BattleFormat base = r.doubles ? BattleFormat.Companion.getGEN_9_DOUBLES() : BattleFormat.Companion.getGEN_9_SINGLES();
        Set<String> rules = new LinkedHashSet<>(base.getRuleSet());
        rules.add(BattleRules.BAG_CLAUSE);
        if (r.sleepClause) rules.add("Sleep Clause Mod");
        if (!r.tera) rules.add("Terastal Clause");
        return base.copy(base.getMod(), base.getBattleType(), rules, base.getGen(), Math.max(0, r.level));
    }

    static List<BattlePokemon> team(Side s, FormatRules r) {
        List<BattlePokemon> out = new ArrayList<>();
        for (Pokemon pk : s.selection()) {
            BattlePokemon bp = BattlePokemon.Companion.safeCopyOf(pk);
            if (r.level > 0) bp.getEffectedPokemon().setLevel(r.level);
            bp.getEffectedPokemon().heal();
            out.add(bp);
        }
        return out;
    }

    static void startBattle(Match m) {
        ServerPlayer pa = m.a.player();
        ServerPlayer pb = m.b.player();
        if (pa == null || pb == null) {
            Side absent = pa == null ? m.a : m.b;
            absent.forfeited = true;
            absent.disconnected = true;
            finish(m, m.other(absent).uuid, "absent", false);
            return;
        }
        if (CobblemonBridge.isInBattle(pa) || CobblemonBridge.isInBattle(pb)) {
            cancel(m, Set.of(), "ranked.cancel_busy");
            return;
        }
        try {
            List<BattlePokemon> ta = team(m.a, m.rules);
            List<BattlePokemon> tb = team(m.b, m.rules);
            PlayerBattleActor aa = new PlayerBattleActor(pa.getUUID(), ta);
            PlayerBattleActor ab = new PlayerBattleActor(pb.getUUID(), tb);
            BattleStartResult r = BattleRegistry.startBattle(format(m.rules), new BattleSide(aa), new BattleSide(ab), false);
            if (r instanceof SuccessfulBattleStart ok) {
                PokemonBattle b = ok.getBattle();
                m.battleId = b.getBattleId();
                m.battleStart = System.currentTimeMillis();
                m.state = State.BATTLE;
                BY_BATTLE.put(m.battleId, m);
                for (Side s : List.of(m.a, m.b)) {
                    ServerPlayer p = s.player();
                    if (p != null) Msg.send(p, "ranked.battle_start", "opponent", m.other(s).name);
                }
                return;
            }
            String why = "?";
            if (r instanceof ErroredBattleStart err) why = err.getGeneralErrors().toString() + " " + err.getParticipantErrors();
            Battletower.LOG.warn("[Battles] Match {} : combat refuse par Cobblemon : {}", m.id, why);
            cancel(m, Set.of(), "ranked.cancel_error");
        } catch (Throwable t) {
            Battletower.LOG.error("[Battles] Match {} : lancement impossible", m.id, t);
            cancel(m, Set.of(), "ranked.cancel_error");
        }
    }

    // ------------------------------------------------------------------ tick

    static void tick() {
        long now = System.currentTimeMillis();
        for (Match m : new ArrayList<>(MATCHES.values())) {
            try {
                switch (m.state) {
                    case ARRIVING -> {
                        if (now > m.deadline) {
                            Set<Side> late = new LinkedHashSet<>();
                            if (!m.a.arrived) late.add(m.a);
                            if (!m.b.arrived) late.add(m.b);
                            cancel(m, late, "ranked.cancel_absent");
                        }
                    }
                    case READY -> {
                        if (now > m.deadline) {
                            Set<Side> late = new LinkedHashSet<>();
                            if (!m.a.accepted) late.add(m.a);
                            if (!m.b.accepted) late.add(m.b);
                            cancel(m, late, "ranked.cancel_not_ready");
                        }
                    }
                    case SELECT -> {
                        if (now > m.deadline) {
                            for (Side s : List.of(m.a, m.b)) {
                                if (s.confirmed) continue;
                                applyAuto(s, m.rules);
                                s.confirmed = TeamRules.checkSelection(s.selection(), m.rules).isEmpty();
                                ServerPlayer p = s.player();
                                if (p != null) Msg.send(p, "ranked.select_auto");
                            }
                            if (m.a.confirmed && m.b.confirmed) countdown(m);
                            else {
                                Set<Side> bad = new LinkedHashSet<>();
                                if (!m.a.confirmed) bad.add(m.a);
                                if (!m.b.confirmed) bad.add(m.b);
                                cancel(m, bad, "ranked.cancel_team");
                            }
                        }
                    }
                    case COUNTDOWN -> {
                        long left = (m.deadline - now + 999) / 1000;
                        for (Side s : List.of(m.a, m.b)) {
                            ServerPlayer p = s.player();
                            if (p == null) continue;
                            if (left > 0) {
                                Fx.title(p, "&e" + left, Msg.formatBare("ranked.countdown_sub", "opponent", m.other(s).name));
                                Fx.sound(p, SoundEvents.NOTE_BLOCK_HAT.value(), 1f, 1f);
                            }
                        }
                        if (now >= m.deadline) startBattle(m);
                    }
                    case BATTLE -> tickBattle(m, now);
                    default -> {
                    }
                }
            } catch (Throwable t) {
                Battletower.LOG.error("[Battles] Match {} : erreur de suivi", m.id, t);
            }
        }
    }

    private static void tickBattle(Match m, long now) {
        PokemonBattle battle = CobblemonBridge.battle(m.battleId);
        if (battle == null || battle.getEnded()) {
            if (m.battleGoneSince == 0) m.battleGoneSince = now;
            else if (now - m.battleGoneSince > 3000) finish(m, null, m.tiebreak ? "nul" : "interrompu", !m.tiebreak);
            return;
        }
        m.battleGoneSince = 0;
        int maxTimeouts = Math.max(1, RankedConfig.general.i("minuteurs.tours_expires_max", 3));
        if (m.rules.turnTimer > 0) {
            for (BattleActor actor : battle.getActors()) {
                Side s = null;
                for (UUID u : actor.getPlayerUUIDs()) s = m.side(u.toString());
                if (s == null) continue;
                ServerPlayer p = s.player();
                if (actor.getMustChoose() && actor.getRequest() != null) {
                    if (s.waitingSince == 0) s.waitingSince = now;
                    long left = m.rules.turnTimer - (now - s.waitingSince) / 1000;
                    if (p != null && !Players.isTest(p.getUUID())) Msg.actionBar(p, "ranked.turn_timer", "seconds", Math.max(0, left));
                    if (left <= 0) {
                        s.waitingSince = 0;
                        s.timeouts++;
                        if (s.timeouts >= maxTimeouts) {
                            if (p != null) Msg.send(p, "ranked.timeout_forfeit");
                            s.forfeited = true;
                            m.endReason = "minuteur";
                            battle.writeShowdownAction(">forcewin " + m.other(s).actorId);
                        } else {
                            AutoChoice.play(actor);
                            if (p != null) Msg.send(p, "ranked.timeout_auto", "n", s.timeouts, "max", maxTimeouts);
                        }
                    }
                } else {
                    s.waitingSince = 0;
                }
            }
        }
        if (m.rules.matchMinutes > 0 && !m.tiebreak) {
            long left = m.rules.matchMinutes * 60L - (now - m.battleStart) / 1000;
            for (String w : RankedConfig.general.list("minuteurs.avertissements")) {
                int sec;
                try {
                    sec = Integer.parseInt(w.trim().replace(".0", ""));
                } catch (Exception e) {
                    continue;
                }
                if (left <= sec && m.warned.add(sec)) {
                    for (Side s : List.of(m.a, m.b)) {
                        ServerPlayer p = s.player();
                        if (p != null) Msg.send(p, "ranked.time_warning", "time", fr.cobblepulse.battletower.core.TimeFmt.duration(sec * 1000L));
                    }
                }
            }
            if (left <= 0) {
                m.tiebreak = true;
                m.endReason = "duree";
                for (Side s : List.of(m.a, m.b)) {
                    ServerPlayer p = s.player();
                    if (p != null) Msg.send(p, "ranked.time_up");
                }
                battle.writeShowdownAction(">tiebreak");
            }
        }
    }

    // ------------------------------------------------------------------ evenements de combat

    static void onVictory(PokemonBattle battle, List<BattleActor> winners) {
        Match m = byBattle(battle.getBattleId());
        if (m == null || m.state != State.BATTLE) return;
        String winner = null;
        if (winners != null && winners.size() == 1) {
            for (UUID u : winners.get(0).getPlayerUUIDs()) if (m.side(u.toString()) != null) winner = u.toString();
        }
        if (winner != null) {
            Side w = m.side(winner);
            Side l = m.other(w);
            // abandon : le perdant avait encore des Pokemon debout (hors minuteur et fin de duree)
            if (!m.tiebreak && !"minuteur".equals(m.endReason)) {
                for (BattleActor actor : battle.getActors()) {
                    for (UUID u : actor.getPlayerUUIDs()) {
                        if (!u.toString().equals(l.uuid)) continue;
                        boolean alive = actor.getPokemonList().stream().anyMatch(bp -> bp.getHealth() > 0);
                        if (alive && !l.disconnected) {
                            l.forfeited = true;
                            m.endReason = "abandon";
                        }
                    }
                }
            }
        }
        String fw = winner;
        String reason = m.endReason.isEmpty() ? (winner == null ? "nul" : "victoire") : m.endReason;
        int turns = battle.getTurn();
        Scheduler.next(() -> finish(m, fw, reason, false, turns));
    }

    static void onFainted(PokemonBattle battle, BattlePokemon killed) {
        Match m = byBattle(battle.getBattleId());
        if (m == null || killed == null || killed.actor == null) return;
        for (UUID u : killed.actor.getPlayerUUIDs()) {
            Side victim = m.side(u.toString());
            if (victim != null) m.other(victim).kos++;
        }
    }

    public static void onQuit(ServerPlayer p) {
        RankedQueue.leave(p.getUUID());
        Match m = matchOf(p.getUUID());
        if (m == null) return;
        Side s = m.side(p.getUUID().toString());
        switch (m.state) {
            case BATTLE -> {
                s.forfeited = true;
                s.disconnected = true;
                m.endReason = "deconnexion";
                finish(m, m.other(s).uuid, "deconnexion", false);
            }
            case ARRIVING -> s.arrived = false;
            case ENDED -> {
            }
            default -> cancel(m, Set.of(s), "ranked.cancel_disconnect");
        }
    }

    // ------------------------------------------------------------------ fin

    /** Annule un match (sans Elo) : les fautifs recoivent un delai, les autres retournent en file. */
    static void cancel(Match m, Set<Side> culprits, String key) {
        if (m.state == State.ENDED) return;
        State was = m.state;
        m.state = State.ENDED;
        unregister(m);
        long now = System.currentTimeMillis();
        for (Side s : List.of(m.a, m.b)) {
            ServerPlayer p = s.player();
            if (p != null) {
                Gui.close(p);
                Msg.send(p, key, "opponent", m.other(s).name);
            }
            if (culprits.contains(s)) {
                RankedProfile prof = RankedStore.getOrCreate(UUID.fromString(s.uuid), s.name);
                prof.declineUntil = now + RankedConfig.general.i("delais.refus", 30) * 1000L;
                RankedStore.mark(prof);
            } else if (was == State.READY || was == State.ARRIVING || was == State.SELECT) {
                RankedQueue.Entry e = s.entry;
                if (p != null || CrossServer.enabled()) {
                    if (p != null && !s.server.equalsIgnoreCase(CrossServer.serverId())) {
                        // joueur venu d'un autre serveur : il y retourne et y reprend sa place dans la file
                        CrossServer.transfer(p, s.server);
                        CrossServer.queuePut(e);
                    } else if (p != null) {
                        RankedQueue.requeue(e, p);
                    }
                }
            } else if (p != null && !s.server.equalsIgnoreCase(CrossServer.serverId()) && CrossServer.enabled()) {
                CrossServer.transfer(p, s.server);
            }
        }
        returnPlayers(m, false);
        RESULTS.put(m.id, "annule:" + key);
    }

    static void finish(Match m, String winner, String reason, boolean aborted) {
        finish(m, winner, reason, aborted, -1);
    }

    static void finish(Match m, String winner, String reason, boolean aborted, int turnsArg) {
        if (m.state == State.ENDED) return;
        PokemonBattle battle = CobblemonBridge.battle(m.battleId);
        int turns = turnsArg >= 0 ? turnsArg : battle == null ? 0 : battle.getTurn();
        m.state = State.ENDED;
        unregister(m);
        if (m.battleId != null && CobblemonBridge.battleActive(m.battleId)) CobblemonBridge.stopBattle(m.battleId);
        long now = System.currentTimeMillis();
        RankedProfile pa = RankedStore.getOrCreate(UUID.fromString(m.a.uuid), m.a.name);
        RankedProfile pb = RankedStore.getOrCreate(UUID.fromString(m.b.uuid), m.b.name);
        pa.lastMatchEnd = now;
        pb.lastMatchEnd = now;
        if (aborted) {
            for (Side s : List.of(m.a, m.b)) {
                ServerPlayer p = s.player();
                if (p != null) Msg.send(p, "ranked.match_aborted");
            }
            RankedStore.mark(pa);
            RankedStore.mark(pb);
            returnPlayers(m, true);
            RESULTS.put(m.id, "interrompu");
            return;
        }
        Side w = winner == null ? null : m.side(winner);
        Side l = w == null ? null : m.other(w);
        boolean draw = w == null;
        RankedProfile.FormatStats sa = pa.stats(m.format);
        RankedProfile.FormatStats sb = pb.stats(m.format);
        int beforeA = sa.elo;
        int beforeB = sb.elo;
        RankedConfig.Rank rankA = RankedConfig.rankFor(sa.elo);
        RankedConfig.Rank rankB = RankedConfig.rankFor(sb.elo);
        if (m.ranked) {
            double scoreA = draw ? 0.5 : (w == m.a ? 1 : 0);
            int na = Elo.next(sa.elo, sb.elo, scoreA, sa.games(), scoreA == 1 ? sa.streak + 1 : 0);
            int nb = Elo.next(sb.elo, sa.elo, 1 - scoreA, sb.games(), scoreA == 0 ? sb.streak + 1 : 0);
            applyElo(sa, na);
            applyElo(sb, nb);
            if (draw) {
                sa.draws++;
                sb.draws++;
                sa.streak = 0;
                sb.streak = 0;
            } else {
                RankedProfile.FormatStats ws = w == m.a ? sa : sb;
                RankedProfile.FormatStats ls = w == m.a ? sb : sa;
                ws.wins++;
                ws.streak++;
                ws.bestStreak = Math.max(ws.bestStreak, ws.streak);
                ls.losses++;
                ls.streak = 0;
            }
        } else if (!draw) {
            (w == m.a ? sa : sb).casualWins++;
            (w == m.a ? sb : sa).casualLosses++;
        }
        sa.lastPlayed = now;
        sb.lastPlayed = now;
        // penalites de fuite (classe)
        int minutesA = 0;
        int minutesB = 0;
        if (m.ranked && l != null && l.forfeited) {
            boolean early = !l.disconnected && turns < RankedConfig.general.i("penalites.abandon_precoce_tours", 6);
            int min = Penalties.flee(l == m.a ? pa : pb, early || l.disconnected && turns < 1);
            if (l == m.a) minutesA = min;
            else minutesB = min;
        }
        // historique, adversaires recents
        record(pa, m, m.b, draw ? "nul" : w == m.a ? "victoire" : "defaite", beforeA, sa.elo, turns, reason, now);
        record(pb, m, m.a, draw ? "nul" : w == m.b ? "victoire" : "defaite", beforeB, sb.elo, turns, reason, now);
        // missions
        Missions.onMatch(pa, m.ranked, m.format, !draw && w == m.a, sa.streak, m.a.kos);
        Missions.onMatch(pb, m.ranked, m.format, !draw && w == m.b, sb.streak, m.b.kos);
        // rangs
        RankedConfig.Rank newA = RankedConfig.rankFor(sa.elo);
        RankedConfig.Rank newB = RankedConfig.rankFor(sb.elo);
        if (m.ranked) {
            checkRank(pa, m, rankA, newA, sa.elo);
            checkRank(pb, m, rankB, newB, sb.elo);
        }
        // messages et recompenses de match
        notify(m, m.a, pa, sa, beforeA, draw, w, minutesA);
        notify(m, m.b, pb, sb, beforeB, draw, w, minutesB);
        if (!draw) {
            String base = m.ranked ? "recompenses_match.classe_" : "recompenses_match.amical_";
            runMatchCommands(w, RankedConfig.general.list(base + "victoire"), w == m.a ? sa.elo : sb.elo, m.format);
            runMatchCommands(l, RankedConfig.general.list(base + "defaite"), l == m.a ? sa.elo : sb.elo, m.format);
        }
        Discord.matchResult(m.ranked, m.rules.name, draw ? m.a.name : w.name, draw ? m.b.name : l.name,
                draw ? sa.elo : (w == m.a ? sa.elo : sb.elo), draw ? 0 : (w == m.a ? sa.elo - beforeA : sb.elo - beforeB),
                draw ? sb.elo : (l == m.a ? sa.elo : sb.elo), draw ? 0 : (l == m.a ? sa.elo - beforeA : sb.elo - beforeB), draw);
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("id", m.id);
        log.put("saison", Seasons.currentId());
        log.put("format", m.format);
        log.put("classe", m.ranked);
        log.put("j1", m.a.name);
        log.put("j1_uuid", m.a.uuid);
        log.put("j1_elo", beforeA + "->" + sa.elo);
        log.put("j2", m.b.name);
        log.put("j2_uuid", m.b.uuid);
        log.put("j2_elo", beforeB + "->" + sb.elo);
        log.put("vainqueur", draw ? "" : w.name);
        log.put("raison", reason);
        log.put("tours", turns);
        log.put("duree_s", m.battleStart == 0 ? 0 : (now - m.battleStart) / 1000);
        RankedStore.logMatch(log);
        RESULTS.put(m.id, (draw ? "nul" : "victoire:" + w.name) + ":" + reason + ":" + beforeA + "->" + sa.elo + ":" + beforeB + "->" + sb.elo);
        if (CrossServer.enabled()) {
            RankedStore.saveNow(pa, () -> CrossServer.publish("profil", Map.of("uuid", pa.uuid)));
            RankedStore.saveNow(pb, () -> CrossServer.publish("profil", Map.of("uuid", pb.uuid)));
        } else {
            RankedStore.mark(pa);
            RankedStore.mark(pb);
        }
        returnPlayers(m, true);
    }

    private static void applyElo(RankedProfile.FormatStats s, int next) {
        int delta = next - s.elo;
        int cap = RankedConfig.elo.i("gain_max_par_jour", 200);
        String today = LocalDate.now(Seasons.zone()).toString();
        if (!today.equals(s.gainDay)) {
            s.gainDay = today;
            s.gainToday = 0;
        }
        if (delta > 0 && cap > 0) {
            delta = Math.max(0, Math.min(delta, cap - s.gainToday));
            s.gainToday += delta;
        }
        s.elo = Math.max(RankedConfig.floorElo(), s.elo + delta);
        s.peak = Math.max(s.peak, s.elo);
    }

    private static void record(RankedProfile p, Match m, Side foe, String result, int before, int after, int turns, String reason, long now) {
        RankedProfile.MatchSummary h = new RankedProfile.MatchSummary();
        h.opponent = foe.uuid;
        h.opponentName = foe.name;
        h.format = m.format;
        h.ranked = m.ranked;
        h.result = result;
        h.eloBefore = before;
        h.eloAfter = after;
        h.turns = turns;
        h.reason = reason;
        h.at = now;
        p.history.add(0, h);
        while (p.history.size() > 20) p.history.remove(p.history.size() - 1);
        p.recent.add(new RankedProfile.Recent(foe.uuid, now));
        while (p.recent.size() > 10) p.recent.remove(0);
    }

    private static void checkRank(RankedProfile p, Match m, RankedConfig.Rank before, RankedConfig.Rank after, int elo) {
        if (after.minElo() <= before.minElo()) return;
        Msg.broadcast("ranked.rank_up_broadcast", "player", p.name, "rank", after.name(), "format", m.rules.name, "elo", elo);
        Discord.rankUp(p.name, m.rules.name, after, elo);
        RankedRewards.rankReached(p, m.format, after);
    }

    private static void notify(Match m, Side s, RankedProfile prof, RankedProfile.FormatStats st, int before, boolean draw, Side w, int banMinutes) {
        ServerPlayer p = s.player();
        if (p == null) return;
        int delta = st.elo - before;
        String result = draw ? "ranked.result_draw" : (w == s ? "ranked.result_win" : "ranked.result_loss");
        if (m.ranked) {
            Fx.title(p, Msg.formatBare(result), Msg.formatBare("ranked.result_elo", "elo", st.elo, "delta", (delta >= 0 ? "+" : "") + delta,
                    "rank", RankedConfig.rankFor(st.elo).name()));
        } else {
            Fx.title(p, Msg.formatBare(result), Msg.formatBare("ranked.result_casual"));
        }
        Fx.sound(p, w == s ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.NOTE_BLOCK_BASS.value(), 1f, 1f);
        Msg.send(p, m.ranked ? "ranked.result_chat_ranked" : "ranked.result_chat_casual", "result", Msg.formatBare(result),
                "opponent", m.other(s).name, "elo", st.elo, "delta", (delta >= 0 ? "+" : "") + delta);
        if (banMinutes > 0) Msg.send(p, "ranked.flee_banned", "minutes", banMinutes, "flees", prof.flees);
        else if (s.forfeited && m.ranked) Msg.send(p, "ranked.flee_warning", "flees", prof.flees);
    }

    private static void runMatchCommands(Side s, List<String> cmds, int elo, String format) {
        if (cmds.isEmpty()) return;
        List<String> out = new ArrayList<>();
        for (String c : cmds) out.add(c.replace("%uuid%", s.uuid).replace("%elo%", String.valueOf(elo)).replace("%format%", format));
        RankedProfile prof = RankedStore.getOrCreate(UUID.fromString(s.uuid), s.name);
        ServerPlayer p = s.player();
        if (p != null) Rewards.runCommands(p, s.name, out);
        else RankedRewards.give(prof, out, null);
    }

    /** Retour des joueurs (position d'avant l'arene, puis serveur d'origine). */
    private static void returnPlayers(Match m, boolean afterBattle) {
        Arenas.Arena arena = m.arena;
        int delay = afterBattle ? Math.max(0, RankedConfig.general.i("arenes.retour_secondes", 3)) : 0;
        Scheduler.later(Math.max(1, delay * 20L), () -> {
            for (Side s : List.of(m.a, m.b)) {
                ServerPlayer p = s.player();
                if (p == null) continue;
                if (s.returnLoc != null) {
                    if (arena != null && arena.exit != null) arena.exit.teleport(p);
                    else s.returnLoc.teleport(p);
                }
                if (afterBattle && CrossServer.enabled() && RankedConfig.network.b("multi_serveur.retour_origine", true)
                        && !s.server.equalsIgnoreCase(CrossServer.serverId())) {
                    CrossServer.transfer(p, s.server);
                }
            }
            Arenas.release(arena);
        });
    }

    private static void unregister(Match m) {
        MATCHES.remove(m.id);
        if (BY_PLAYER.get(m.a.uuid) == m) BY_PLAYER.remove(m.a.uuid);
        if (BY_PLAYER.get(m.b.uuid) == m) BY_PLAYER.remove(m.b.uuid);
        if (m.battleId != null) BY_BATTLE.remove(m.battleId);
    }

    /** Fin forcee par le staff : match annule sans Elo. */
    public static boolean forceEnd(UUID player) {
        Match m = matchOf(player);
        if (m == null) return false;
        if (m.state == State.BATTLE) finish(m, null, "staff", true);
        else cancel(m, Set.of(), "ranked.cancel_staff");
        return true;
    }

    public static void stopAll() {
        for (Match m : new ArrayList<>(MATCHES.values())) {
            if (m.state == State.BATTLE) finish(m, null, "arret", true);
            else cancel(m, Set.of(), "ranked.cancel_staff");
        }
        MATCHES.clear();
        BY_PLAYER.clear();
        BY_BATTLE.clear();
    }

    static String stateName(State s) {
        return Msg.formatBare("ranked.state_" + s.name().toLowerCase(java.util.Locale.ROOT));
    }

    @SuppressWarnings("unused")
    private static String legacy(String s) {
        return TextUtil.strip(s);
    }
}
