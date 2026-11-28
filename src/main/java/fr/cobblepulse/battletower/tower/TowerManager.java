package fr.cobblepulse.battletower.tower;

import com.cobblemon.mod.common.entity.npc.NPCEntity;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.battle.CobblemonBridge;
import fr.cobblepulse.battletower.battle.TowerBattle;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.config.TierRules;
import fr.cobblepulse.battletower.core.Fx;
import fr.cobblepulse.battletower.core.Json;
import fr.cobblepulse.battletower.core.Loc;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Players;
import fr.cobblepulse.battletower.core.Scheduler;
import fr.cobblepulse.battletower.core.TextUtil;
import fr.cobblepulse.battletower.core.TimeFmt;
import fr.cobblepulse.battletower.data.FloorEntry;
import fr.cobblepulse.battletower.data.PlayerData;
import fr.cobblepulse.battletower.data.RunState;
import fr.cobblepulse.battletower.data.RunState.Phase;
import fr.cobblepulse.battletower.data.TrainerSpec;
import fr.cobblepulse.battletower.economy.Achievements;
import fr.cobblepulse.battletower.economy.BattlePoints;
import fr.cobblepulse.battletower.npc.TrainerNpcs;
import fr.cobblepulse.battletower.storage.DataService;
import fr.cobblepulse.battletower.storage.PlayerStore;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import fr.cobblepulse.battletower.data.TowerData;
import fr.cobblepulse.battletower.core.Perms;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coeur de la Tour de Combat : ascension d'une seule traite, file d'attente par etage, cooldown, deconnexions,
 * reprise apres redemarrage, chronometre, annonces, disqualification et ouverture/fermeture.
 * Les tentatives du Boss du Jour utilisent la meme mecanique (RunState.daily).
 */
public final class TowerManager {
    /** Cle de file pour l'arene du Boss du Jour. */
    public static final int BOSS_QUEUE = 0;

    private static TowerState state = new TowerState();
    private static final Map<String, Deque<UUID>> QUEUES = new HashMap<>();
    /** Limite d'etages imposee par l'auto-test (0 = configuration). */
    public static int testFloorLimit;

    private TowerManager() {}

    public static TowerState state() {
        return state;
    }

    public static int totalFloors() {
        return testFloorLimit > 0 ? testFloorLimit : Cfg.floors();
    }

    /** Nombre d'etages de la tour de cette ascension. */
    public static int totalFloors(RunState r) {
        return TowerConfig.size(towerOf(r));
    }

    /** Tour d'une ascension (la principale si la tour a ete supprimee). */
    public static TowerData towerOf(RunState r) {
        TowerData t = r == null ? null : TowerConfig.tower(r.tower);
        return t == null ? TowerConfig.data() : t;
    }

    static boolean mainRun(RunState r) {
        return TowerConfig.isMain(r.tower);
    }

    static FloorEntry floorEntry(RunState r) {
        return TowerConfig.floor(towerOf(r), r.floor);
    }

    /** Cle de file : un dresseur d'etage d'une tour, ou l'arene du Boss du Jour. */
    private static String key(RunState r) {
        return r.daily ? "boss" : towerOf(r).id + ":" + r.floor;
    }

    /** Hall de sortie d'une tour (sinon celui de la tour principale). */
    public static Loc exitOf(TowerData t) {
        return t != null && t.exit != null ? t.exit : TowerConfig.data().exit;
    }

    // ------------------------------------------------------------------ Chargement / sauvegarde

    public static void load() {
        String json = DataService.blocking(s -> s.loadKv("state"));
        TowerState loaded = null;
        try {
            if (json != null && !json.isBlank()) loaded = Json.GSON.fromJson(json, TowerState.class);
        } catch (Exception e) {
            Battletower.LOG.error("[Battletower] Etat de la tour illisible : {}", e.toString());
        }
        state = loaded != null ? loaded : new TowerState();
        state.normalize();
        // Toute ascension encore en memoire a ete interrompue par l'arret (ou le crash) du serveur
        long now = TimeFmt.now();
        for (RunState r : state.runs.values()) {
            r.serverInterrupted = true;
            r.phase = r.daily ? Phase.FLOOR : Phase.FLOOR;
            r.pauseClock(now);
            r.graceUntil = 0;
        }
        if (!state.runs.isEmpty()) Battletower.LOG.info("[Battletower] {} ascension(s) en cours seront reprises a la reconnexion", state.runs.size());
        save();
        Scheduler.repeat(20, 20, TowerManager::tickSecond);
    }

    public static void save() {
        DataService.markKv("state", () -> state);
    }

    /** Arret du serveur : les combats en cours seront rejoues a la reprise. */
    public static void onServerStopping() {
        long now = TimeFmt.now();
        for (RunState r : state.runs.values()) {
            r.serverInterrupted = true;
            r.pauseClock(now);
            if (r.phase == Phase.BATTLE || r.phase == Phase.COUNTDOWN || r.phase == Phase.QUEUED || r.phase == Phase.TRANSIT) r.phase = Phase.FLOOR;
        }
        save();
    }

    public static void clearRuntime() {
        QUEUES.clear();
    }

    // ------------------------------------------------------------------ Acces

    public static RunState run(UUID player) {
        return player == null ? null : state.runs.get(player.toString());
    }

    public static boolean inRun(UUID player) {
        return run(player) != null;
    }

    public static List<RunState> runs() {
        return new ArrayList<>(state.runs.values());
    }

    public static boolean isOpen() {
        return state.open;
    }

    public static void setOpen(boolean open) {
        state.open = open;
        save();
        Msg.broadcast(open ? "tower.opened_broadcast" : "tower.closed_broadcast");
    }

    public static long cooldownLeft(PlayerData d) {
        return Math.max(0, d.cooldownUntil - TimeFmt.now());
    }

    public static long cooldownLeft(PlayerData d, TowerData t) {
        if (TowerConfig.isMain(t)) return cooldownLeft(d);
        PlayerData.TowerRecord rec = d.towers.get(t.id);
        return rec == null ? 0 : Math.max(0, rec.cooldownUntil - TimeFmt.now());
    }

    /** Record d'etage du joueur dans une tour. */
    public static int record(PlayerData d, TowerData t) {
        if (TowerConfig.isMain(t)) return d.maxFloor;
        PlayerData.TowerRecord rec = d.towers.get(t.id);
        return rec == null ? 0 : rec.maxFloor;
    }

    // ------------------------------------------------------------------ Lancement

    /** Verifie qu'un etage est jouable (point d'arrivee, PNJ, equipe). */
    public static String floorProblem(FloorEntry fe) {
        if (fe == null) return Msg.format("tower.floor_missing", "floor", "?");
        if (fe.arrival == null) return Msg.format("tower.floor_no_tp", "floor", fe.floor);
        if (fe.npcUuid == null || fe.npcLocation == null) return Msg.format("tower.floor_no_npc", "floor", fe.floor);
        if (fe.trainer == null || fe.trainer.team.isEmpty()) return Msg.format("tower.floor_no_team", "floor", fe.floor);
        return null;
    }

    public static void startAssault(ServerPlayer p) {
        startAssault(p, TowerConfig.MAIN);
    }

    /** Lance une ascension dans la tour indiquee (principale ou secondaire). */
    public static void startAssault(ServerPlayer p, String towerId) {
        TowerData tower = TowerConfig.tower(towerId);
        if (tower == null) {
            Msg.send(p, "tower.unknown_tower", "tower", towerId);
            return;
        }
        if (!tower.permission.isBlank() && !Perms.player(p, tower.permission)) {
            Msg.send(p, "tower.tower_locked", "tower", TowerConfig.name(tower));
            return;
        }
        PlayerData d = PlayerStore.get(p);
        if (inRun(p.getUUID())) {
            Msg.send(p, "tower.already_running");
            return;
        }
        if (fr.cobblepulse.battles.ranked.Ranked.busy(p.getUUID())) {
            Msg.send(p, "tower.ranked_busy");
            return;
        }
        if (!state.open) {
            Msg.send(p, "tower.closed");
            return;
        }
        long cd = cooldownLeft(d, tower);
        if (cd > 0) {
            Msg.send(p, "tower.cooldown", "time", TimeFmt.duration(cd), "tower", TowerConfig.name(tower));
            return;
        }
        if (CobblemonBridge.isInBattle(p)) {
            Msg.send(p, "tower.in_battle");
            return;
        }
        if (CobblemonBridge.partyList(p).isEmpty()) {
            Msg.send(p, "tower.no_team");
            return;
        }
        FloorEntry first = TowerConfig.floor(tower, 1);
        String problem = floorProblem(first);
        if (problem != null) {
            Msg.send(p, "tower.not_ready", "reason", problem);
            return;
        }
        long now = TimeFmt.now();
        RunState r = new RunState();
        r.uuid = p.getUUID().toString();
        r.name = p.getGameProfile().getName();
        r.floor = 1;
        r.tower = TowerConfig.isMain(tower) ? "" : tower.id;
        r.startedAt = now;
        r.activeSince = now;
        r.floorSince = now;
        r.phase = Phase.FLOOR;
        state.runs.put(r.uuid, r);
        if (exitOf(tower) == null) state.returns.put(r.uuid, Loc.of(p));
        d.lastRunAt = now;
        if (!TowerConfig.isMain(tower)) d.tower(tower.id).lastRunAt = now;
        PlayerStore.save(d);
        save();
        Msg.send(p, "tower.assault_started", "floors", totalFloors(r), "tower", TowerConfig.name(tower));
        arriveAtFloor(p, r);
    }

    /** Teleporte le joueur sur son etage courant et affiche les informations du dresseur. */
    static void arriveAtFloor(ServerPlayer p, RunState r) {
        if (!mainRun(r) && TowerConfig.tower(r.tower) == null) {
            Msg.send(p, "tower.unknown_tower", "tower", r.tower);
            endRun(p.getUUID(), EndReason.CANCELLED, null);
            return;
        }
        FloorEntry fe = floorEntry(r);
        String problem = floorProblem(fe);
        if (problem != null) {
            // Etage mal configure : on annule sans penalite
            Msg.send(p, "tower.not_ready", "reason", problem);
            endRun(p.getUUID(), EndReason.CANCELLED, null);
            return;
        }
        r.phase = Phase.FLOOR;
        r.floorSince = TimeFmt.now();
        r.protectUntil = TimeFmt.now() + 4000;
        r.resumeClock(TimeFmt.now());
        fe.arrival.teleport(p);
        TrainerSpec t = fe.trainer;
        TierRules tier = Cfg.tier(t.tier);
        Fx.title(p, Msg.format("tower.floor_title", "floor", r.floor, "floors", totalFloors(r)),
                Msg.format("tower.floor_subtitle", "trainer", t.display(), "tier", tier == null ? t.tier : tier.displayName));
        Fx.sound(p, SoundEvents.BEACON_ACTIVATE, 0.6f, 1.4f);
        sendFloorInfo(p, r, fe);
        save();
        if (Cfg.tower.b("auto_engage", false) || Players.isTest(p.getUUID())) Scheduler.later(40, () -> {
            RunState cur = run(p.getUUID());
            if (cur == r && cur.phase == Phase.FLOOR && !p.hasDisconnected()) engage(p);
        });
    }

    private static void sendFloorInfo(ServerPlayer p, RunState r, FloorEntry fe) {
        TrainerSpec t = fe.trainer;
        TierRules tier = Cfg.tier(t.tier);
        List<String> mods = new ArrayList<>();
        mods.add(t.doubles() ? Msg.raw("tower.format_doubles") : Msg.raw("tower.format_singles"));
        if (t.levelScale > 0) mods.add(Msg.format("tower.mod_level", "level", t.levelScale));
        if (t.inverse) mods.add(Msg.raw("tower.mod_inverse"));
        if (!"none".equals(t.weather)) mods.add(Msg.format("tower.mod_weather", "weather", Msg.raw("weather." + t.weather)));
        if (!"none".equals(t.terrain)) mods.add(Msg.format("tower.mod_terrain", "terrain", Msg.raw("terrain." + t.terrain)));
        p.sendSystemMessage(Msg.get("tower.floor_info", "floor", r.floor, "floors", totalFloors(r), "trainer", t.display(),
                "tier", tier == null ? t.tier : tier.displayName, "ai", Msg.raw("ai." + t.aiLevel().name().toLowerCase()),
                "modifiers", String.join("&7, ", mods), "time", TimeFmt.duration(r.elapsed(TimeFmt.now()))));
        if (!t.introMessage.isBlank()) p.sendSystemMessage(TextUtil.legacy("&e" + t.display() + " &7: &f" + t.introMessage));
        MutableComponent buttons = Component.empty()
                .append(TextUtil.button(Msg.raw("tower.button_engage"), "/battletower engage", Msg.raw("tower.button_engage_hover")))
                .append(Component.literal("  "));
        if (Cfg.tower.b("allow_pc_on_floor", true)) {
            buttons.append(TextUtil.button(Msg.raw("tower.button_pc"), "/battletower pc", Msg.raw("tower.button_pc_hover"))).append(Component.literal("  "));
        }
        buttons.append(TextUtil.button(Msg.raw("tower.button_abandon"), "/battletower abandon", Msg.raw("tower.button_abandon_hover")));
        p.sendSystemMessage(buttons);
    }

    // ------------------------------------------------------------------ Engagement / file d'attente

    /** Le joueur engage le dresseur de son etage (bouton, commande ou clic sur le PNJ). */
    public static void engage(ServerPlayer p) {
        RunState r = run(p.getUUID());
        if (r == null) {
            Msg.send(p, "tower.not_running");
            return;
        }
        if (r.phase == Phase.BATTLE || r.phase == Phase.COUNTDOWN) return;
        if (r.phase == Phase.QUEUED) {
            Msg.send(p, "tower.already_queued", "position", queuePosition(r));
            return;
        }
        if (r.phase == Phase.TRANSIT) return;
        String key = key(r);
        if (isBusy(key, p.getUUID())) {
            Deque<UUID> q = QUEUES.computeIfAbsent(key, k -> new ArrayDeque<>());
            if (!q.contains(p.getUUID())) q.addLast(p.getUUID());
            r.phase = Phase.QUEUED;
            r.pauseClock(TimeFmt.now());
            Msg.send(p, "tower.queued", "position", queuePosition(r));
            save();
            return;
        }
        beginCountdown(p, r);
    }

    private static int queuePosition(RunState r) {
        Deque<UUID> q = QUEUES.get(key(r));
        if (q == null) return 1;
        int i = 1;
        for (UUID u : q) {
            if (u.toString().equals(r.uuid)) return i;
            i++;
        }
        return i;
    }

    /** Le dresseur de cet etage est-il deja en combat (ou reserve par un autre joueur en compte a rebours) ? */
    private static boolean isBusy(String key, UUID except) {
        RunState sample = null;
        for (RunState o : state.runs.values()) {
            if (!key(o).equals(key)) continue;
            sample = o;
            if (except != null && o.uuid.equals(except.toString())) continue;
            if (o.phase == Phase.BATTLE || o.phase == Phase.COUNTDOWN) return true;
        }
        NPCEntity npc = sample == null ? null : npcFor(sample, false);
        return npc != null && TowerBattle.npcBusy(npc.getUUID());
    }

    private static NPCEntity npcFor(RunState r, boolean respawn) {
        MinecraftServer s = Battletower.server();
        if (s == null) return null;
        if (r.daily) return DailyBoss.npc(s);
        FloorEntry fe = floorEntry(r);
        return fe == null ? null : TrainerNpcs.floorNpc(s, fe, respawn);
    }

    private static TrainerSpec trainerFor(RunState r) {
        if (r.daily) return DailyBoss.current();
        FloorEntry fe = floorEntry(r);
        return fe == null ? null : fe.trainer;
    }

    private static void beginCountdown(ServerPlayer p, RunState r) {
        TrainerSpec t = trainerFor(r);
        if (t == null) {
            Msg.send(p, "tower.not_ready", "reason", Msg.raw("tower.no_trainer"));
            return;
        }
        // Verification de l'equipe face au tier du dresseur
        TierRules tier = Cfg.tier(t.tier);
        if (tier != null) {
            List<String> errors = TeamValidator.validate(p, tier, t.levelScale > 0);
            if (t.doubles() && CobblemonBridge.partyList(p).size() < 2) errors.add(Msg.raw("tower.doubles_need_two"));
            if (!errors.isEmpty()) {
                Msg.send(p, "tower.team_invalid", "tier", tier.displayName);
                for (String e : errors) p.sendSystemMessage(TextUtil.legacy("&c - " + e));
                r.phase = Phase.FLOOR;
                return;
            }
        }
        r.phase = Phase.COUNTDOWN;
        r.resumeClock(TimeFmt.now());
        long token = ++r.countdownToken;
        int cd = Math.max(0, Cfg.tower.i("countdown_seconds", 3));
        fr.cobblepulse.battletower.gui.Gui.close(p);
        for (int i = 0; i < cd; i++) {
            int remaining = cd - i;
            Scheduler.later(1 + i * 20L, () -> {
                if (r.phase != Phase.COUNTDOWN || r.countdownToken != token || p.hasDisconnected()) return;
                Fx.title(p, Msg.format("tower.countdown_title", "seconds", remaining), Msg.format("tower.countdown_subtitle", "trainer", t.display()));
                Fx.sound(p, SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 1.0f + 0.1f * (cd - remaining));
            });
        }
        Scheduler.later(1 + cd * 20L, () -> {
            if (run(p.getUUID()) != r || r.phase != Phase.COUNTDOWN || r.countdownToken != token) return;
            if (p.hasDisconnected() && !Players.isTest(p.getUUID())) return;
            launch(p, r, t);
        });
        save();
    }

    private static void launch(ServerPlayer p, RunState r, TrainerSpec t) {
        String key = key(r);
        NPCEntity npc = npcFor(r, true);
        if (npc == null) {
            Msg.send(p, "tower.not_ready", "reason", Msg.raw("tower.npc_missing"));
            r.phase = Phase.FLOOR;
            return;
        }
        if (r.daily) DailyBoss.consumeAttempt(p);
        String err = TowerBattle.start(p, npc, t, outcome -> DataService.main(() -> onBattleEnd(outcome, r, key)));
        if (err != null) {
            Msg.send(p, "tower.battle_failed", "error", err);
            r.phase = Phase.FLOOR;
            releaseQueue(key);
            return;
        }
        r.phase = Phase.BATTLE;
        r.battleStartedAt = TimeFmt.now();
        r.anyFainted = false;
        Fx.title(p, Msg.raw("tower.go_title"), Msg.format("tower.go_subtitle", "trainer", t.display()));
        save();
    }

    /** Libere l'etage : le premier joueur en file engage automatiquement. */
    private static void releaseQueue(String key) {
        if (isBusy(key, null)) return;
        Deque<UUID> q = QUEUES.get(key);
        while (q != null && !q.isEmpty()) {
            UUID next = q.pollFirst();
            RunState nr = run(next);
            ServerPlayer np = Players.get(next);
            if (nr == null || np == null || nr.phase != Phase.QUEUED) continue;
            if (!key(nr).equals(key)) continue;
            nr.phase = Phase.FLOOR;
            Msg.send(np, "tower.queue_your_turn");
            beginCountdown(np, nr);
            return;
        }
    }

    private static void removeFromQueues(UUID u) {
        for (Deque<UUID> q : QUEUES.values()) q.remove(u);
    }

    // ------------------------------------------------------------------ Issue des combats

    private static void onBattleEnd(TowerBattle.Outcome o, RunState r, String key) {
        Scheduler.later(30, () -> releaseQueue(key));
        if (run(o.player()) != r) return;
        ServerPlayer p = Players.get(o.player());
        if (o.aborted()) {
            r.phase = Phase.FLOOR;
            if (p != null) {
                Msg.send(p, "tower.battle_interrupted");
                CobblemonBridge.heal(p);
            }
            save();
            return;
        }
        if (r.daily) {
            DailyBoss.onResult(o, r, p);
            endRun(o.player(), EndReason.DAILY_DONE, null);
            return;
        }
        if (o.won()) onFloorWon(o, r, p);
        else endRun(o.player(), EndReason.DEFEAT, o.reason());
    }

    private static void onFloorWon(TowerBattle.Outcome o, RunState r, ServerPlayer p) {
        PlayerData d = PlayerStore.getOrCreate(o.player(), r.name);
        TowerData tower = towerOf(r);
        boolean main = TowerConfig.isMain(tower);
        r.wins++;
        d.totalWins++;
        if (main) {
            if (r.floor > d.maxFloor) d.maxFloor = r.floor;
        } else {
            PlayerData.TowerRecord rec = d.tower(tower.id);
            rec.wins++;
            if (r.floor > rec.maxFloor) rec.maxFloor = r.floor;
        }
        if (o.playerFainted()) r.noKoStreak = 0;
        else {
            r.noKoStreak++;
            if (r.noKoStreak > d.bestNoKoStreak) d.bestNoKoStreak = r.noKoStreak;
        }
        if (p != null && CobblemonBridge.monotype(p)) d.monotypeFloors++;
        long bp = BattlePoints.forFloor(r.floor, p);
        if (!main) bp = Math.round(bp * Math.max(0, tower.bpMultiplier));
        BattlePoints.give(d, bp);
        if (p != null) {
            if (Cfg.tower.b("battle.heal_between_floors", true)) CobblemonBridge.heal(p);
            Msg.send(p, "tower.floor_won", "floor", r.floor, "bp", bp, "balance", d.bp);
        }
        if (main) BattlePoints.milestone(r.floor, p, d);
        List<Integer> milestones = new ArrayList<>();
        for (String s : Cfg.tower.list("announce.milestone_floors")) {
            try {
                milestones.add(Integer.parseInt(s.trim().replace(".0", "")));
            } catch (NumberFormatException ignored) {
            }
        }
        if (main && milestones.contains(r.floor) && r.floor < totalFloors()) {
            Msg.broadcast(TextUtil.legacy(Cfg.tower.str("announce.milestone_message", "").replace("%player%", r.name).replace("%floor%", String.valueOf(r.floor))));
        }
        PlayerStore.save(d);
        Achievements.check(d, p);
        if (r.floor >= totalFloors(r)) {
            complete(r, p, d);
            return;
        }
        r.floor++;
        r.phase = Phase.TRANSIT;
        save();
        long delay = Math.max(1, Cfg.tower.i("next_floor_delay_seconds", 4)) * 20L;
        if (p != null) Msg.send(p, "tower.next_floor_soon", "floor", r.floor, "seconds", delay / 20);
        Scheduler.later(delay, () -> {
            ServerPlayer pp = Players.get(o.player());
            if (pp != null && run(o.player()) == r && r.phase == Phase.TRANSIT) arriveAtFloor(pp, r);
        });
    }

    private static void complete(RunState r, ServerPlayer p, PlayerData d) {
        long now = TimeFmt.now();
        r.pauseClock(now);
        long time = r.activeMs;
        TowerData tower = towerOf(r);
        if (!TowerConfig.isMain(tower)) {
            completeExtra(r, p, d, tower, time);
            return;
        }
        d.completions++;
        boolean record = d.bestTimeMs <= 0 || time < d.bestTimeMs;
        if (record) d.bestTimeMs = time;
        BattlePoints.finalReward(p, d);
        PlayerStore.save(d);
        Achievements.check(d, p);
        String msg = Cfg.tower.str("announce.final_message", "").replace("%player%", r.name).replace("%time%", TimeFmt.duration(time));
        if (!msg.isBlank()) Msg.broadcast(TextUtil.legacy(msg));
        MinecraftServer s = Battletower.server();
        if (s != null) {
            String sound = Cfg.tower.str("announce.final_sound", "minecraft:ui.toast.challenge_complete");
            float vol = (float) Cfg.tower.d("announce.final_sound_volume", 1.0);
            float pitch = (float) Cfg.tower.d("announce.final_sound_pitch", 1.0);
            for (ServerPlayer all : s.getPlayerList().getPlayers()) Fx.sound(all, sound, vol, pitch);
        }
        if (p != null) {
            Fx.title(p, Msg.raw("tower.complete_title"), Msg.format("tower.complete_subtitle", "time", TimeFmt.duration(time)));
            Msg.send(p, record ? "tower.complete_record" : "tower.complete", "time", TimeFmt.duration(time), "completions", d.completions);
        }
        endRun(UUID.fromString(r.uuid), EndReason.COMPLETED, null);
    }

    /** Fin d'une tour secondaire : record et recompense propres a cette tour. */
    private static void completeExtra(RunState r, ServerPlayer p, PlayerData d, TowerData tower, long time) {
        PlayerData.TowerRecord rec = d.tower(tower.id);
        rec.completions++;
        boolean record = rec.bestTimeMs <= 0 || time < rec.bestTimeMs;
        if (record) rec.bestTimeMs = time;
        if (tower.finalBp > 0) BattlePoints.give(d, tower.finalBp);
        if (p != null && !tower.finalCommands.isEmpty()) fr.cobblepulse.battletower.core.Rewards.runCommands(p, r.name, tower.finalCommands);
        PlayerStore.save(d);
        Achievements.check(d, p);
        String name = TowerConfig.name(tower);
        Msg.broadcast("tower.extra_complete_broadcast", "player", r.name, "tower", name, "floors", TowerConfig.size(tower), "time", TimeFmt.duration(time));
        if (p != null) {
            Fx.title(p, Msg.raw("tower.complete_title"), Msg.format("tower.complete_subtitle", "time", TimeFmt.duration(time)));
            Fx.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            Msg.send(p, record ? "tower.extra_complete_record" : "tower.extra_complete", "tower", name, "time", TimeFmt.duration(time),
                    "completions", rec.completions, "bp", tower.finalBp);
        }
        endRun(UUID.fromString(r.uuid), EndReason.COMPLETED, null);
    }

    // ------------------------------------------------------------------ Fin d'ascension

    public enum EndReason { COMPLETED, DEFEAT, ABANDON, DISCONNECT, DISQUALIFIED, CANCELLED, DAILY_DONE }

    /** Termine une ascension : cooldown eventuel, retour au hall, file liberee. */
    public static void endRun(UUID player, EndReason reason, String detail) {
        RunState r = state.runs.remove(player.toString());
        if (r == null) return;
        removeFromQueues(player);
        TowerData tower = towerOf(r);
        boolean main = r.daily || TowerConfig.isMain(tower);
        Loc towerExit = r.daily ? TowerConfig.data().exit : exitOf(tower);
        Loc back = towerExit != null ? towerExit : state.returns.get(player.toString());
        state.returns.remove(player.toString());
        if (r.phase == Phase.BATTLE) TowerBattle.forfeit(player, reason.name());
        PlayerData d = PlayerStore.getOrCreate(player, r.name);
        boolean penalty = !r.daily && (reason == EndReason.DEFEAT || reason == EndReason.ABANDON || reason == EndReason.DISCONNECT
                || reason == EndReason.DISQUALIFIED);
        if (penalty) {
            boolean loss = reason == EndReason.DEFEAT || reason == EndReason.DISCONNECT;
            d.totalLosses += loss ? 1 : 0;
            long hours = main || tower.cooldownHours < 0 ? Cfg.tower.l("defeat_cooldown_hours", 24) : tower.cooldownHours;
            long until = TimeFmt.now() + Math.max(0, hours) * TimeFmt.HOUR;
            if (main) d.cooldownUntil = until;
            else {
                PlayerData.TowerRecord rec = d.tower(tower.id);
                rec.cooldownUntil = until;
                rec.losses += loss ? 1 : 0;
            }
        }
        ServerPlayer p = Players.get(player);
        if (p == null || p.hasDisconnected()) {
            d.pendingExit = back != null;
            d.pendingExitTower = main ? "" : tower.id;
            if (penalty) d.pendingMessage = Msg.format("tower.ended_while_offline", "floor", r.floor, "reason", Msg.raw("reason." + reason.name().toLowerCase()));
        } else {
            switch (reason) {
                case DEFEAT -> Msg.send(p, "tower.defeat", "floor", r.floor, "record", record(d, tower),
                        "cooldown", TimeFmt.duration(cooldownLeft(d, tower)));
                case ABANDON -> Msg.send(p, "tower.abandoned", "floor", r.floor, "cooldown", TimeFmt.duration(cooldownLeft(d, tower)));
                case DISQUALIFIED -> Msg.send(p, "tower.disqualified", "cooldown", TimeFmt.duration(cooldownLeft(d, tower)));
                case CANCELLED -> Msg.send(p, "tower.cancelled");
                default -> {
                }
            }
            if (Cfg.tower.b("battle.heal_between_floors", true)) CobblemonBridge.heal(p);
            if (back != null) {
                long delay = reason == EndReason.CANCELLED ? 1 : Math.max(1, Cfg.tower.i("return_delay_seconds", 4)) * 20L;
                Loc dest = back;
                Scheduler.later(delay, () -> {
                    if (!p.hasDisconnected() && !inRun(player)) dest.teleport(p);
                });
            }
        }
        PlayerStore.save(d);
        save();
        String key = key(r);
        Scheduler.later(10, () -> releaseQueue(key));
    }

    public static void abandon(ServerPlayer p) {
        RunState r = run(p.getUUID());
        if (r == null) {
            Msg.send(p, "tower.not_running");
            return;
        }
        endRun(p.getUUID(), r.daily ? EndReason.CANCELLED : EndReason.ABANDON, null);
    }

    /** Disqualification par le staff : combat ou file annule, renvoi au point de sortie, cooldown applique. */
    public static boolean disqualify(UUID player, String by) {
        RunState r = run(player);
        if (r == null) return false;
        Battletower.LOG.info("[Battletower] {} disqualifie par {} (etage {})", r.name, by, r.floor);
        endRun(player, r.daily ? EndReason.CANCELLED : EndReason.DISQUALIFIED, by);
        return true;
    }

    public static void openPc(ServerPlayer p) {
        RunState r = run(p.getUUID());
        if (r == null) {
            Msg.send(p, "tower.not_running");
            return;
        }
        if (!Cfg.tower.b("allow_pc_on_floor", true)) {
            Msg.send(p, "tower.pc_disabled");
            return;
        }
        if (r.phase != Phase.FLOOR) {
            Msg.send(p, "tower.pc_not_now");
            return;
        }
        if (!CobblemonBridge.openPc(p)) Msg.send(p, "tower.pc_failed");
    }

    // ------------------------------------------------------------------ Connexions

    public static void onJoin(ServerPlayer p) {
        PlayerData d = PlayerStore.get(p);
        RunState r = run(p.getUUID());
        if (r != null) {
            boolean resume = r.serverInterrupted || r.graceUntil > 0;
            if (resume) {
                r.serverInterrupted = false;
                r.graceUntil = 0;
                Scheduler.later(40, () -> {
                    if (p.hasDisconnected() || run(p.getUUID()) != r) return;
                    Msg.send(p, r.daily ? "daily.resumed" : "tower.resumed", "floor", r.floor);
                    if (r.daily) DailyBoss.arrive(p, r);
                    else arriveAtFloor(p, r);
                });
            }
        } else if (d.pendingExit) {
            d.pendingExit = false;
            Loc back = exitOf(TowerConfig.tower(d.pendingExitTower));
            d.pendingExitTower = "";
            if (back != null) Scheduler.later(20, () -> {
                if (!p.hasDisconnected()) back.teleport(p);
            });
        }
        if (!d.pendingMessage.isEmpty()) {
            String m = d.pendingMessage;
            d.pendingMessage = "";
            Scheduler.later(60, () -> p.sendSystemMessage(TextUtil.legacy(m)));
        }
        PlayerStore.save(d);
    }

    /** Deconnexion : en combat = defaite immediate ; hors combat = delai de grace. */
    public static void onQuit(ServerPlayer p) {
        RunState r = run(p.getUUID());
        if (r == null) return;
        removeFromQueues(p.getUUID());
        if (r.phase == Phase.BATTLE) {
            endRun(p.getUUID(), r.daily ? EndReason.DAILY_DONE : EndReason.DISCONNECT, null);
            if (r.daily) DailyBoss.logDisconnect(r);
            return;
        }
        r.pauseClock(TimeFmt.now());
        if (r.phase != Phase.TRANSIT) r.phase = Phase.FLOOR;
        r.graceUntil = TimeFmt.now() + Math.max(5, Cfg.tower.i("disconnect.grace_seconds", 60)) * 1000L;
        save();
    }

    // ------------------------------------------------------------------ Surveillance (chaque seconde)

    private static void tickSecond() {
        long now = TimeFmt.now();
        int idleMin = Cfg.tower.i("floor_idle_timeout_minutes", 0);
        double radius = Math.max(8, Cfg.tower.d("leave_radius", 64));
        for (RunState r : new ArrayList<>(state.runs.values())) {
            UUID id = UUID.fromString(r.uuid);
            ServerPlayer p = Players.get(id);
            if (p == null) {
                if (r.graceUntil > 0 && now >= r.graceUntil && !r.serverInterrupted) {
                    endRun(id, r.daily ? EndReason.CANCELLED : EndReason.DISCONNECT, null);
                }
                continue;
            }
            if (r.serverInterrupted || r.graceUntil > 0) continue;
            // Sortie de la tour (teleportation, /spawn...) = abandon
            if (now > r.protectUntil && r.phase != Phase.TRANSIT && !Players.isTest(id)) {
                Loc anchor = anchor(r);
                if (anchor != null) {
                    boolean otherWorld = !p.serverLevel().dimension().location().toString().equals(anchor.world);
                    double dist = otherWorld ? Double.MAX_VALUE : p.position().distanceTo(new net.minecraft.world.phys.Vec3(anchor.x, anchor.y, anchor.z));
                    if (dist > radius) {
                        Msg.send(p, "tower.left_area");
                        endRun(id, r.daily ? EndReason.CANCELLED : EndReason.ABANDON, null);
                        continue;
                    }
                }
            }
            if (idleMin > 0 && r.phase == Phase.FLOOR && now - r.floorSince > idleMin * TimeFmt.MINUTE) {
                Msg.send(p, "tower.idle_timeout");
                endRun(id, r.daily ? EndReason.CANCELLED : EndReason.ABANDON, null);
                continue;
            }
            if (r.phase == Phase.QUEUED && !isBusy(key(r), id)) {
                Deque<UUID> q = QUEUES.get(key(r));
                if (q == null || q.peekFirst() == null || q.peekFirst().equals(id)) {
                    if (q != null) q.remove(id);
                    r.phase = Phase.FLOOR;
                    beginCountdown(p, r);
                }
            }
            if (r.phase == Phase.BATTLE || r.phase == Phase.FLOOR || r.phase == Phase.COUNTDOWN) {
                Msg.actionBar(p, r.daily ? "daily.actionbar" : "tower.actionbar", "floor", r.floor, "floors", totalFloors(r),
                        "time", TimeFmt.clock(r.elapsed(now)));
            }
        }
    }

    private static Loc anchor(RunState r) {
        if (r.daily) return TowerConfig.data().bossArrival;
        FloorEntry fe = floorEntry(r);
        return fe == null ? null : fe.arrival;
    }

    // ------------------------------------------------------------------ Boss du Jour (entree commune)

    /** Cree une tentative de Boss du Jour (verifications faites par DailyBoss). */
    static RunState startDaily(ServerPlayer p, String bossId) {
        long now = TimeFmt.now();
        RunState r = new RunState();
        r.uuid = p.getUUID().toString();
        r.name = p.getGameProfile().getName();
        r.daily = true;
        r.dailyBossId = bossId;
        r.floor = 0;
        r.startedAt = now;
        r.activeSince = now;
        r.phase = Phase.FLOOR;
        state.runs.put(r.uuid, r);
        if (TowerConfig.data().exit == null) state.returns.put(r.uuid, Loc.of(p));
        save();
        return r;
    }
}
