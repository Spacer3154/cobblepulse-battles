package fr.cobblepulse.battletower.command;

import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.core.Loc;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Perms;
import fr.cobblepulse.battletower.core.TimeFmt;
import fr.cobblepulse.battletower.data.FloorEntry;
import fr.cobblepulse.battletower.data.PlayerData;
import fr.cobblepulse.battletower.data.RunState;
import fr.cobblepulse.battletower.data.TowerData;
import fr.cobblepulse.battletower.data.TrainerSpec;
import fr.cobblepulse.battletower.economy.BattlePoints;
import fr.cobblepulse.battletower.menu.AdminMenus;
import fr.cobblepulse.battletower.menu.Menus;
import fr.cobblepulse.battletower.net.EditorService;
import fr.cobblepulse.battletower.npc.TrainerNpcs;
import fr.cobblepulse.battletower.storage.PlayerStore;
import fr.cobblepulse.battletower.tower.DailyBoss;
import fr.cobblepulse.battletower.tower.TowerConfig;
import fr.cobblepulse.battletower.tower.TowerManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** /battletower (alias /bt) : joueurs, staff et administration. */
public final class Commands {
    private static final SuggestionProvider<CommandSourceStack> BOSSES =
            (ctx, b) -> SharedSuggestionProvider.suggest(TowerConfig.bosses().bosses.keySet(), b);
    private static final SuggestionProvider<CommandSourceStack> KNOWN_PLAYERS =
            (ctx, b) -> SharedSuggestionProvider.suggest(PlayerStore.names(), b);

    private Commands() {}

    private interface PlayerAction {
        void run(ServerPlayer p) throws CommandSyntaxException;
    }

    private static int player(CommandContext<CommandSourceStack> ctx, PlayerAction a) throws CommandSyntaxException {
        a.run(ctx.getSource().getPlayerOrException());
        return 1;
    }

    private static int floorArg(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "etage");
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralCommandNode<CommandSourceStack> root = d.register(literal("battletower").requires(Perms.playerReq(Perms.USE))
                .executes(ctx -> player(ctx, Menus::main))
                .then(literal("top").executes(ctx -> player(ctx, Menus::leaderboard)))
                .then(literal("succes").executes(ctx -> player(ctx, Menus::achievements)))
                .then(literal("achievements").executes(ctx -> player(ctx, Menus::achievements)))
                .then(literal("start").executes(ctx -> player(ctx, TowerManager::startAssault)))
                .then(literal("engage").executes(ctx -> player(ctx, TowerManager::engage)))
                .then(literal("pc").executes(ctx -> player(ctx, TowerManager::openPc)))
                .then(literal("abandon").executes(ctx -> player(ctx, p -> {
                    RunState r = TowerManager.run(p.getUUID());
                    if (r != null && r.daily) TowerManager.abandon(p);
                    else Menus.confirmAbandon(p);
                })))
                .then(literal("boss").executes(ctx -> player(ctx, DailyBoss::start))
                        .then(literal("create").requires(Perms.adminReq(Perms.ADMIN_BOSS)).then(argument("id", StringArgumentType.word())
                                .executes(ctx -> bossCreate(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                        .then(literal("edit").requires(Perms.adminReq(Perms.ADMIN_BOSS)).then(argument("id", StringArgumentType.word()).suggests(BOSSES)
                                .executes(ctx -> player(ctx, p -> EditorService.openBoss(p, StringArgumentType.getString(ctx, "id"))))))
                        .then(literal("delete").requires(Perms.adminReq(Perms.ADMIN_BOSS)).then(argument("id", StringArgumentType.word()).suggests(BOSSES)
                                .executes(ctx -> bossDelete(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                        .then(literal("list").requires(Perms.adminReq(Perms.ADMIN_BOSS)).executes(ctx -> {
                            Msg.send(ctx.getSource(), "admin.boss_list", "list", String.join(", ", TowerConfig.bosses().bosses.keySet()),
                                    "current", DailyBoss.currentId());
                            return 1;
                        }))
                        .then(literal("settp").requires(Perms.adminReq(Perms.ADMIN_BOSS)).executes(ctx -> player(ctx, p -> {
                            TowerConfig.data().bossArrival = Loc.of(p);
                            TowerConfig.saveFloors();
                            Msg.send(p, "admin.boss_settp_done", "loc", TowerConfig.data().bossArrival.pretty());
                        })))
                        .then(literal("spawn").requires(Perms.adminReq(Perms.ADMIN_BOSS)).executes(ctx -> player(ctx, Commands::spawnBossNpc)))
                        .then(literal("rotate").requires(Perms.adminReq(Perms.ADMIN_BOSS)).executes(ctx -> {
                            DailyBoss.rotateIfNeeded(true);
                            Msg.send(ctx.getSource(), "admin.boss_rotated", "boss", DailyBoss.currentId());
                            return 1;
                        })))
                .then(literal("npc").requires(Perms.adminReq(Perms.ADMIN_NPC))
                        .then(literal("spawn").then(argument("etage", IntegerArgumentType.integer(1, 200))
                                .executes(ctx -> player(ctx, p -> npcSpawn(p, floorArg(ctx))))))
                        .then(literal("settp").then(argument("etage", IntegerArgumentType.integer(1, 200))
                                .executes(ctx -> player(ctx, p -> npcSetTp(p, floorArg(ctx))))))
                        .then(literal("edit").executes(ctx -> player(ctx, Commands::npcEditTargeted))
                                .then(argument("etage", IntegerArgumentType.integer(1, 200)).executes(ctx -> player(ctx, p -> EditorService.openFloor(p, floorArg(ctx))))))
                        .then(literal("remove").then(argument("etage", IntegerArgumentType.integer(1, 200))
                                .executes(ctx -> player(ctx, p -> npcRemove(p, floorArg(ctx))))))
                        .then(literal("tp").then(argument("etage", IntegerArgumentType.integer(1, 200)).executes(ctx -> player(ctx, p -> {
                            FloorEntry fe = TowerConfig.floor(floorArg(ctx));
                            if (fe != null && fe.arrival != null) fe.arrival.teleport(p);
                            else Msg.send(p, "admin.floor_no_tp_admin");
                        })))))
                .then(literal("shop").requires(Perms.adminReq(Perms.ADMIN_SHOP))
                        .then(literal("spawn").executes(ctx -> player(ctx, Commands::shopSpawn)))
                        .then(literal("remove").executes(ctx -> player(ctx, Commands::shopRemove))))
                .then(literal("staff").requires(src -> !src.isPlayer() || Perms.staff(src.getPlayer(), Perms.STAFF_DISQUALIFY)
                                || Perms.staff(src.getPlayer(), Perms.STAFF_RESET_COOLDOWN))
                        .then(literal("disqualify").requires(Perms.staffReq(Perms.STAFF_DISQUALIFY))
                                .then(argument("joueurs", EntityArgument.players()).executes(ctx -> {
                                    Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "joueurs");
                                    String by = ctx.getSource().getTextName();
                                    int n = 0;
                                    for (ServerPlayer t : targets) {
                                        if (TowerManager.disqualify(t.getUUID(), by)) {
                                            n++;
                                            Msg.send(ctx.getSource(), "staff.disqualified", "player", t.getGameProfile().getName());
                                        } else {
                                            Msg.send(ctx.getSource(), "staff.not_running", "player", t.getGameProfile().getName());
                                        }
                                    }
                                    return n;
                                })))
                        .then(literal("resetcooldown").requires(Perms.staffReq(Perms.STAFF_RESET_COOLDOWN))
                                .then(argument("joueur", StringArgumentType.word()).suggests(KNOWN_PLAYERS).executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "joueur");
                                    PlayerData pd = PlayerStore.byName(name);
                                    if (pd == null) {
                                        Msg.send(ctx.getSource(), "general.player_not_found", "player", name);
                                        return 0;
                                    }
                                    pd.cooldownUntil = 0;
                                    PlayerStore.save(pd);
                                    Battletower.LOG.info("[Battletower] Cooldown de {} reinitialise par {}", pd.name, ctx.getSource().getTextName());
                                    Msg.send(ctx.getSource(), "staff.cooldown_reset", "player", pd.name);
                                    return 1;
                                })))
                        .then(literal("inspect").requires(Perms.staffReq(Perms.STAFF_DISQUALIFY))
                                .then(argument("joueur", StringArgumentType.word()).suggests(KNOWN_PLAYERS).executes(ctx -> inspect(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "joueur"))))))
                .then(literal("admin").requires(src -> !src.isPlayer() || Perms.isAdmin(src.getPlayer()) || Perms.admin(src.getPlayer(), Perms.ADMIN_BP))
                        .executes(ctx -> player(ctx, AdminMenus::panel))
                        .then(literal("toggle").requires(Perms.adminReq(Perms.ADMIN_TOGGLE)).executes(ctx -> {
                            TowerManager.setOpen(!TowerManager.isOpen());
                            Msg.send(ctx.getSource(), TowerManager.isOpen() ? "admin.toggled_open" : "admin.toggled_closed");
                            return 1;
                        }))
                        .then(literal("reload").requires(Perms.adminReq(Perms.ADMIN_RELOAD)).executes(ctx -> {
                            Battletower.reload();
                            Msg.send(ctx.getSource(), "admin.reloaded");
                            return 1;
                        }))
                        .then(literal("setexit").requires(Perms.adminReq(Perms.ADMIN_NPC)).executes(ctx -> player(ctx, p -> {
                            TowerConfig.data().exit = Loc.of(p);
                            TowerConfig.saveFloors();
                            Msg.send(p, "admin.exit_set", "loc", TowerConfig.data().exit.pretty());
                        })))
                        .then(literal("floors").requires(Perms.adminReq(Perms.ADMIN_NPC)).executes(ctx -> player(ctx, p -> AdminMenus.floors(p, 0))))
                        .then(literal("bp").requires(Perms.adminReq(Perms.ADMIN_BP))
                                .then(bpOp("give")).then(bpOp("take")).then(bpOp("set"))))
                .then(literal("selftest").requires(src -> "true".equals(System.getProperty("battletower.selftest")) && Perms.adminReq(Perms.ADMIN_RELOAD).test(src))
                        .executes(ctx -> SelfTest.basic(ctx.getSource()))
                        .then(literal("ascension").executes(ctx -> SelfTest.ascension(ctx.getSource())))
                        .then(literal("defeat").executes(ctx -> SelfTest.defeat(ctx.getSource())))
                        .then(literal("daily").executes(ctx -> SelfTest.daily(ctx.getSource())))
                        .then(literal("extreme").executes(ctx -> SelfTest.extreme(ctx.getSource())))
                        .then(literal("resume").executes(ctx -> SelfTest.resumeSetup(ctx.getSource())))
                        .then(literal("persisted").executes(ctx -> SelfTest.persisted(ctx.getSource())))
                        .then(literal("ranked").executes(ctx -> fr.cobblepulse.battles.BattlesSelfTest.ranked(ctx.getSource())))
                        .then(literal("rankedtimer").executes(ctx -> fr.cobblepulse.battles.BattlesSelfTest.rankedTimers(ctx.getSource())))
                        .then(literal("trainers").executes(ctx -> fr.cobblepulse.battles.BattlesSelfTest.trainers(ctx.getSource())))
                        .then(literal("season").executes(ctx -> fr.cobblepulse.battles.BattlesSelfTest.season(ctx.getSource())))
                        .then(literal("rankedstorage").executes(ctx -> fr.cobblepulse.battles.BattlesSelfTest.storage(ctx.getSource())))
                        .then(literal("rankedpersisted").executes(ctx -> fr.cobblepulse.battles.BattlesSelfTest.storagePersisted(ctx.getSource())))
                        .then(literal("battlespersisted").executes(ctx -> fr.cobblepulse.battles.BattlesSelfTest.persisted(ctx.getSource())))
                        .then(literal("report").executes(ctx -> SelfTest.report(ctx.getSource())))));
        d.register(literal("bt").requires(Perms.playerReq(Perms.USE)).executes(ctx -> player(ctx, Menus::main)).redirect(root));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> bpOp(String op) {
        return literal(op).then(argument("joueur", StringArgumentType.word()).suggests(KNOWN_PLAYERS)
                .then(argument("montant", LongArgumentType.longArg(0)).executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "joueur");
                    long amount = LongArgumentType.getLong(ctx, "montant");
                    PlayerData pd = PlayerStore.byName(name);
                    if (pd == null) {
                        Msg.send(ctx.getSource(), "general.player_not_found", "player", name);
                        return 0;
                    }
                    switch (op) {
                        case "give" -> BattlePoints.give(pd, amount);
                        case "take" -> pd.bp = Math.max(0, pd.bp - amount);
                        default -> pd.bp = amount;
                    }
                    PlayerStore.save(pd);
                    Msg.send(ctx.getSource(), "admin.bp_done", "player", pd.name, "bp", pd.bp);
                    return 1;
                })));
    }

    // ------------------------------------------------------------------ PNJ

    private static void npcSpawn(ServerPlayer p, int floor) {
        FloorEntry fe = TowerConfig.floor(floor);
        if (fe == null || floor > TowerManager.totalFloors()) {
            Msg.send(p, "admin.floor_unknown", "floor", floor);
            return;
        }
        NPCEntity npc = TrainerNpcs.spawnFloor(p.server, fe, Loc.of(p));
        Msg.send(p, npc != null ? "admin.npc_spawned" : "admin.npc_spawn_failed", "floor", floor);
    }

    private static void npcSetTp(ServerPlayer p, int floor) {
        FloorEntry fe = TowerConfig.floor(floor);
        if (fe == null || floor > TowerManager.totalFloors()) {
            Msg.send(p, "admin.floor_unknown", "floor", floor);
            return;
        }
        fe.arrival = Loc.of(p);
        TowerConfig.saveFloors();
        Msg.send(p, "admin.settp_done", "floor", floor, "loc", fe.arrival.pretty());
    }

    private static void npcRemove(ServerPlayer p, int floor) {
        FloorEntry fe = TowerConfig.floor(floor);
        if (fe == null) return;
        TrainerNpcs.remove(p.server, fe.npcUuid, fe.npcLocation);
        fe.npcUuid = null;
        fe.npcLocation = null;
        TowerConfig.saveFloors();
        Msg.send(p, "admin.npc_removed", "floor", floor);
    }

    /** /battletower npc edit sans argument : PNJ vise (6 blocs). */
    private static void npcEditTargeted(ServerPlayer p) {
        Entity target = lookedAt(p, 6);
        if (target == null || !TrainerNpcs.isOurs(target)) {
            Msg.send(p, "admin.no_target");
            return;
        }
        int floor = TrainerNpcs.floorOf(target);
        if (floor > 0) EditorService.openFloor(p, floor);
        else if (target.getTags().contains(TrainerNpcs.TAG_BOSS)) EditorService.openBoss(p, DailyBoss.currentId());
        else Msg.send(p, "admin.no_target");
    }

    public static Entity lookedAt(ServerPlayer p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(range));
        AABB box = p.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        Entity best = null;
        double bestDist = range * range;
        for (Entity e : p.level().getEntities(p, box, e -> e instanceof NPCEntity)) {
            AABB eb = e.getBoundingBox().inflate(0.3);
            var hit = eb.clip(eye, end);
            if (eb.contains(eye) || hit.isPresent()) {
                double dist = hit.map(eye::distanceToSqr).orElse(0.0);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = e;
                }
            }
        }
        return best;
    }

    public static void spawnBossNpc(ServerPlayer p) {
        TowerData d = TowerConfig.data();
        TrainerNpcs.remove(p.server, d.bossNpcUuid, d.bossNpcLocation);
        TrainerSpec t = DailyBoss.current();
        String name = t == null ? "&c&lBoss du Jour" : "&c&l" + t.display();
        NPCEntity npc = TrainerNpcs.spawn(p.server, Loc.of(p), name, t == null ? "builtin:champion" : t.skin, TrainerNpcs.TAG_BOSS);
        if (npc != null) {
            d.bossNpcUuid = npc.getUUID().toString();
            d.bossNpcLocation = Loc.of(p);
            TowerConfig.saveFloors();
            Msg.send(p, "admin.boss_spawned");
        }
    }

    private static int bossCreate(CommandSourceStack src, String rawId) {
        String id = rawId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
        if (id.isEmpty() || TowerConfig.bosses().bosses.containsKey(id)) {
            Msg.send(src, "admin.boss_exists", "id", id);
            return 0;
        }
        TrainerSpec t = new TrainerSpec();
        t.name = rawId;
        t.title = "Boss";
        t.ai = "EXTREME";
        t.skin = "builtin:champion";
        TowerConfig.bosses().bosses.put(id, t);
        TowerConfig.saveBosses();
        Msg.send(src, "admin.boss_created", "id", id);
        if (src.getPlayer() != null) EditorService.openBoss(src.getPlayer(), id);
        return 1;
    }

    private static int bossDelete(CommandSourceStack src, String id) {
        if (TowerConfig.bosses().bosses.remove(id.toLowerCase(Locale.ROOT)) == null) {
            Msg.send(src, "admin.boss_unknown", "id", id);
            return 0;
        }
        TowerConfig.saveBosses();
        Msg.send(src, "admin.boss_deleted", "id", id);
        return 1;
    }

    // ------------------------------------------------------------------ Boutique

    private static void shopSpawn(ServerPlayer p) {
        NPCEntity npc = TrainerNpcs.spawn(p.server, Loc.of(p), Cfg.shop.str("menu.npc_name", "&6Marchand de la Tour"),
                Cfg.shop.str("menu.npc_skin", "builtin:shopkeeper"), TrainerNpcs.TAG_SHOP);
        if (npc == null) return;
        TowerData.ShopNpc s = new TowerData.ShopNpc();
        s.uuid = npc.getUUID().toString();
        s.location = Loc.of(p);
        TowerConfig.data().shops.add(s);
        TowerConfig.saveFloors();
        Msg.send(p, "admin.shop_spawned");
    }

    private static void shopRemove(ServerPlayer p) {
        Entity target = lookedAt(p, 6);
        if (target == null || !target.getTags().contains(TrainerNpcs.TAG_SHOP)) {
            Msg.send(p, "admin.no_target");
            return;
        }
        String id = target.getUUID().toString();
        target.discard();
        TowerConfig.data().shops.removeIf(s -> id.equals(s.uuid));
        TowerConfig.saveFloors();
        Msg.send(p, "admin.shop_removed");
    }

    // ------------------------------------------------------------------ Staff

    private static int inspect(CommandSourceStack src, String name) {
        PlayerData d = PlayerStore.byName(name);
        if (d == null) {
            Msg.send(src, "general.player_not_found", "player", name);
            return 0;
        }
        RunState r = TowerManager.run(java.util.UUID.fromString(d.uuid));
        Msg.send(src, "staff.inspect", "player", d.name, "bp", d.bp, "floor", d.maxFloor, "completions", d.completions,
                "wins", d.totalWins, "losses", d.totalLosses, "cooldown", TimeFmt.duration(TowerManager.cooldownLeft(d)),
                "run", r == null ? "-" : (r.daily ? "Boss du Jour" : "etage " + r.floor) + " (" + r.phase + ")",
                "daily", DailyBoss.attemptedToday(d) ? "oui" : "non");
        return 1;
    }
}
