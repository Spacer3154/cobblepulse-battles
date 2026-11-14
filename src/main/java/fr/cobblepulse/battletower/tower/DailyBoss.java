package fr.cobblepulse.battletower.tower;

import com.cobblemon.mod.common.entity.npc.NPCEntity;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.battle.CobblemonBridge;
import fr.cobblepulse.battletower.battle.TowerBattle;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.config.TierRules;
import fr.cobblepulse.battletower.config.TomlFile;
import fr.cobblepulse.battletower.core.Fx;
import fr.cobblepulse.battletower.core.Loc;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Rewards;
import fr.cobblepulse.battletower.core.Scheduler;
import fr.cobblepulse.battletower.core.TextUtil;
import fr.cobblepulse.battletower.core.TimeFmt;
import fr.cobblepulse.battletower.data.PlayerData;
import fr.cobblepulse.battletower.data.RunState;
import fr.cobblepulse.battletower.data.TowerData;
import fr.cobblepulse.battletower.data.TrainerSpec;
import fr.cobblepulse.battletower.economy.Achievements;
import fr.cobblepulse.battletower.economy.BattlePoints;
import fr.cobblepulse.battletower.npc.TrainerNpcs;
import fr.cobblepulse.battletower.storage.DataService;
import fr.cobblepulse.battletower.storage.PlayerStore;
import fr.cobblepulse.battletower.storage.Storage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Boss du Jour : rotation automatique a minuit (cyclique ou par jour de la semaine), une seule tentative par jour
 * et par joueur (victoire ou defaite), recompenses exclusives (BP, items, commandes console).
 */
public final class DailyBoss {
    private DailyBoss() {}

    public static ZoneId zone() {
        String tz = Cfg.daily.str("timezone", "").trim();
        if (!tz.isEmpty()) {
            try {
                return ZoneId.of(tz);
            } catch (Exception e) {
                Battletower.LOG.warn("[Battletower] Fuseau horaire invalide '{}' : heure du serveur utilisee", tz);
            }
        }
        return ZoneId.systemDefault();
    }

    public static String today() {
        return LocalDate.now(zone()).toString();
    }

    public static boolean enabled() {
        return Cfg.daily.b("enabled", true);
    }

    public static void init() {
        rotateIfNeeded(true);
        Scheduler.repeat(20 * 5, 20 * 15, () -> rotateIfNeeded(false));
    }

    /** Boss prevu pour une date donnee selon daily_boss.toml. */
    public static String bossFor(LocalDate date) {
        String mode = Cfg.daily.str("mode", "cycle").toLowerCase(Locale.ROOT);
        if ("weekday".equals(mode)) {
            DayOfWeek d = date.getDayOfWeek();
            return Cfg.daily.str("rotation." + d.name().toLowerCase(Locale.ROOT), "").toLowerCase(Locale.ROOT).trim();
        }
        List<String> cycle = new ArrayList<>();
        for (String s : Cfg.daily.list("rotation.cycle")) if (!s.isBlank()) cycle.add(s.toLowerCase(Locale.ROOT).trim());
        if (cycle.isEmpty()) cycle.addAll(TowerConfig.bosses().bosses.keySet());
        if (cycle.isEmpty()) return "";
        return cycle.get((int) Math.floorMod(date.toEpochDay(), cycle.size()));
    }

    public static void rotateIfNeeded(boolean startup) {
        TowerState st = TowerManager.state();
        String today = today();
        if (today.equals(st.rotationDay) && !startup) return;
        String id = bossFor(LocalDate.now(zone()));
        boolean changed = !today.equals(st.rotationDay) || !id.equals(st.currentBossId);
        st.rotationDay = today;
        st.currentBossId = id;
        TowerManager.save();
        MinecraftServer s = Battletower.server();
        if (s != null) {
            NPCEntity npc = npc(s);
            if (npc != null) TrainerNpcs.refresh(npc);
        }
        if (changed && !startup) {
            TrainerSpec t = current();
            if (t != null && enabled()) Msg.broadcast("daily.rotated", "boss", t.display());
            Battletower.LOG.info("[Battletower] Rotation du Boss du Jour : {} ({})", id, today);
        }
    }

    public static String currentId() {
        return TowerManager.state().currentBossId;
    }

    public static TrainerSpec current() {
        return TowerConfig.boss(currentId());
    }

    public static NPCEntity npc(MinecraftServer s) {
        TowerData d = TowerConfig.data();
        NPCEntity npc = TrainerNpcs.find(s, d.bossNpcUuid, d.bossNpcLocation);
        if (npc == null && d.bossNpcLocation != null && current() != null) {
            npc = TrainerNpcs.spawn(s, d.bossNpcLocation, "&c&l" + current().display(), current().skin, TrainerNpcs.TAG_BOSS);
            if (npc != null) {
                d.bossNpcUuid = npc.getUUID().toString();
                TowerConfig.saveFloors();
            }
        }
        return npc;
    }

    public static boolean attemptedToday(PlayerData d) {
        return today().equals(d.dailyBossDay);
    }

    public static TomlFile rewards(String bossId) {
        TomlFile specific = Cfg.daily.sub("rewards." + bossId);
        if (specific.has("bp") || specific.has("items") || specific.has("commands")) return specific;
        return Cfg.daily.sub("rewards.default");
    }

    public static List<String> rewardDisplay(String bossId) {
        return rewards(bossId).list("display");
    }

    /** Le joueur rejoint l'arene du Boss du Jour. */
    public static void start(ServerPlayer p) {
        PlayerData d = PlayerStore.get(p);
        if (!enabled()) {
            Msg.send(p, "daily.disabled");
            return;
        }
        if (!TowerManager.isOpen()) {
            Msg.send(p, "tower.closed");
            return;
        }
        if (TowerManager.inRun(p.getUUID())) {
            Msg.send(p, "tower.already_running");
            return;
        }
        if (attemptedToday(d)) {
            Msg.send(p, "daily.already_tried");
            return;
        }
        TrainerSpec t = current();
        TowerData td = TowerConfig.data();
        if (t == null || td.bossArrival == null || td.bossNpcLocation == null || t.team.isEmpty()) {
            Msg.send(p, "daily.not_ready");
            return;
        }
        if (CobblemonBridge.isInBattle(p)) {
            Msg.send(p, "tower.in_battle");
            return;
        }
        RunState r = TowerManager.startDaily(p, currentId());
        arrive(p, r);
    }

    static void arrive(ServerPlayer p, RunState r) {
        TrainerSpec t = current();
        Loc arrival = TowerConfig.data().bossArrival;
        if (t == null || arrival == null) {
            TowerManager.endRun(p.getUUID(), TowerManager.EndReason.CANCELLED, null);
            return;
        }
        r.phase = RunState.Phase.FLOOR;
        r.protectUntil = TimeFmt.now() + 4000;
        r.floorSince = TimeFmt.now();
        arrival.teleport(p);
        TierRules tier = Cfg.tier(t.tier);
        Fx.title(p, Msg.raw("daily.title"), Msg.format("daily.subtitle", "boss", t.display()));
        Fx.sound(p, SoundEvents.WITHER_SPAWN, 0.4f, 1.2f);
        p.sendSystemMessage(Msg.get("daily.info", "boss", t.display(), "tier", tier == null ? t.tier : tier.displayName,
                "ai", Msg.raw("ai." + t.aiLevel().name().toLowerCase(Locale.ROOT))));
        for (String line : rewardDisplay(currentId())) p.sendSystemMessage(TextUtil.legacy("&7 - " + line));
        if (!t.introMessage.isBlank()) p.sendSystemMessage(TextUtil.legacy("&c" + t.display() + " &7: &f" + t.introMessage));
        p.sendSystemMessage(Component.empty()
                .append(TextUtil.button(Msg.raw("tower.button_engage"), "/battletower engage", Msg.raw("daily.engage_hover")))
                .append(Component.literal("  "))
                .append(TextUtil.button(Msg.raw("daily.button_leave"), "/battletower abandon", Msg.raw("daily.leave_hover"))));
        TowerManager.save();
    }

    /** La tentative est consommee des que le combat commence (victoire comme defaite). */
    static void consumeAttempt(ServerPlayer p) {
        PlayerData d = PlayerStore.get(p);
        d.dailyBossDay = today();
        PlayerStore.save(d);
    }

    static void onResult(TowerBattle.Outcome o, RunState r, ServerPlayer p) {
        PlayerData d = PlayerStore.getOrCreate(o.player(), r.name);
        String bossId = r.dailyBossId == null ? currentId() : r.dailyBossId;
        if (o.won()) {
            d.dailyBossWins++;
            d.dailyBossBeaten.add(bossId);
            TomlFile rw = rewards(bossId);
            long bp = rw.l("bp", 0);
            if (bp > 0) BattlePoints.give(d, bp);
            if (p != null) {
                Rewards.giveItems(p, rw.list("items"));
                Fx.title(p, Msg.raw("daily.win_title"), Msg.format("daily.win_subtitle", "bp", bp));
                Msg.send(p, "daily.won", "bp", bp, "balance", d.bp);
            }
            Rewards.runCommands(p, r.name, rw.list("commands"));
            TrainerSpec t = TowerConfig.boss(bossId);
            Msg.broadcast("daily.won_broadcast", "player", r.name, "boss", t == null ? bossId : t.display());
        } else if (p != null) {
            Msg.send(p, "daily.lost");
        }
        PlayerStore.save(d);
        Achievements.check(d, p);
        DataService.logDaily(new Storage.DailyLog(today(), bossId, r.uuid, r.name, o.won() ? "victoire" : "defaite", o.turns(), TimeFmt.now()));
    }

    static void logDisconnect(RunState r) {
        DataService.logDaily(new Storage.DailyLog(today(), r.dailyBossId, r.uuid, r.name, "deconnexion", 0, TimeFmt.now()));
    }

    public static String status(PlayerData d) {
        return attemptedToday(d) ? Msg.raw("daily.status_done") : Msg.raw("daily.status_available");
    }
}
