package fr.cobblepulse.battletower.menu;

import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.core.ItemBuilder;
import fr.cobblepulse.battletower.core.Loc;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Perms;
import fr.cobblepulse.battletower.data.FloorEntry;
import fr.cobblepulse.battletower.data.RunState;
import fr.cobblepulse.battletower.data.TrainerSpec;
import fr.cobblepulse.battletower.gui.Gui;
import fr.cobblepulse.battletower.gui.Paged;
import fr.cobblepulse.battletower.net.EditorService;
import fr.cobblepulse.battletower.npc.TrainerNpcs;
import fr.cobblepulse.battletower.tower.DailyBoss;
import fr.cobblepulse.battletower.tower.TowerConfig;
import fr.cobblepulse.battletower.tower.TowerManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Panneau d'administration (GUI) : ouverture, etages, Boss du Jour, hall, rechargement. */
public final class AdminMenus {
    private AdminMenus() {}

    public static void panel(ServerPlayer p) {
        if (!Perms.isAdmin(p)) {
            Msg.send(p, "general.no_permission");
            return;
        }
        Gui gui = new Gui(3, Msg.raw("admin.title"));
        gui.fill(Gui.pane());
        boolean open = TowerManager.isOpen();
        gui.button(10, ItemBuilder.of(open ? Items.LIME_CONCRETE : Items.RED_CONCRETE)
                .name(open ? Msg.raw("admin.tower_open") : Msg.raw("admin.tower_closed")).lore(Msg.raw("admin.toggle_lore"),
                        Msg.format("admin.running", "n", TowerManager.runs().size())).build(), pl -> {
            if (!Perms.admin(pl, Perms.ADMIN_TOGGLE)) {
                Msg.send(pl, "general.no_permission");
                return;
            }
            TowerManager.setOpen(!TowerManager.isOpen());
            panel(pl);
        });
        int configured = 0;
        for (FloorEntry fe : TowerConfig.floors()) if (fe.floor <= TowerManager.totalFloors() && TowerManager.floorProblem(fe) == null) configured++;
        gui.button(12, ItemBuilder.of(Items.LADDER).name(Msg.raw("admin.floors")).lore(
                Msg.format("admin.floors_ready", "ready", configured, "floors", TowerManager.totalFloors())).build(), pl -> floors(pl, 0));
        TrainerSpec boss = DailyBoss.current();
        gui.button(14, ItemBuilder.of(Items.WITHER_SKELETON_SKULL).name(Msg.raw("admin.boss")).lore(
                Msg.format("admin.boss_current", "boss", boss == null ? DailyBoss.currentId() : boss.display())).build(), AdminMenus::bosses);
        Loc exit = TowerConfig.data().exit;
        gui.button(16, ItemBuilder.of(Items.OAK_DOOR).name(Msg.raw("admin.exit")).lore(
                Msg.format("admin.exit_current", "loc", exit == null ? "-" : exit.pretty()), Msg.raw("admin.exit_click")).build(), pl -> {
            TowerConfig.data().exit = Loc.of(pl);
            TowerConfig.saveFloors();
            Msg.send(pl, "admin.exit_set", "loc", TowerConfig.data().exit.pretty());
            panel(pl);
        });
        gui.button(20, ItemBuilder.of(Items.CLOCK).name(Msg.raw("admin.runs")).lore(Msg.format("admin.running", "n", TowerManager.runs().size())).build(),
                AdminMenus::runs);
        gui.button(22, ItemBuilder.of(Items.BARRIER).name(Msg.raw("menu.close")).build(), ServerPlayer::closeContainer);
        gui.button(24, ItemBuilder.of(Items.REPEATER).name(Msg.raw("admin.reload")).build(), pl -> {
            if (!Perms.admin(pl, Perms.ADMIN_RELOAD)) {
                Msg.send(pl, "general.no_permission");
                return;
            }
            Battletower.reload();
            Msg.send(pl, "admin.reloaded");
            panel(pl);
        });
        gui.open(p);
    }

    public static void floors(ServerPlayer p, int page) {
        List<Paged.Entry> entries = new ArrayList<>();
        for (FloorEntry fe : TowerConfig.floors()) {
            if (fe.floor > TowerManager.totalFloors()) continue;
            String problem = TowerManager.floorProblem(fe);
            List<String> lore = new ArrayList<>();
            lore.add("&7" + Menus.floorSummary(fe));
            lore.add(Msg.format("admin.floor_team", "n", fe.trainer.team.size()));
            lore.add(Msg.format("admin.floor_tp", "loc", fe.arrival == null ? "&c-" : fe.arrival.pretty()));
            lore.add(Msg.format("admin.floor_npc", "loc", fe.npcLocation == null ? "&c-" : fe.npcLocation.pretty()));
            lore.add(problem == null ? Msg.raw("admin.floor_ok") : "&c" + problem);
            lore.add(Msg.raw("admin.floor_click"));
            entries.add(new Paged.Entry(ItemBuilder.of(problem == null ? Items.LIME_STAINED_GLASS : Items.ORANGE_STAINED_GLASS)
                    .name("&e" + Msg.format("admin.floor_name", "floor", fe.floor)).count(Math.min(64, fe.floor)).lore(lore).build(),
                    (pl, c) -> floor(pl, fe.floor)));
        }
        Paged.open(p, Msg.raw("admin.floors"), entries, page, AdminMenus::panel);
    }

    public static void floor(ServerPlayer p, int n) {
        FloorEntry fe = TowerConfig.floor(n);
        if (fe == null) return;
        Gui gui = new Gui(3, Msg.format("admin.floor_name", "floor", n));
        gui.fill(Gui.pane());
        gui.button(10, ItemBuilder.of(Items.ENDER_PEARL).name(Msg.raw("admin.floor_goto")).build(), pl -> {
            if (fe.arrival != null) fe.arrival.teleport(pl);
            pl.closeContainer();
        });
        gui.button(11, ItemBuilder.of(Items.WRITABLE_BOOK).name(Msg.raw("admin.floor_edit")).lore(Msg.raw("admin.floor_edit_lore")).build(), pl -> {
            pl.closeContainer();
            EditorService.openFloor(pl, n);
        });
        gui.button(13, ItemBuilder.of(Items.COMPASS).name(Msg.raw("admin.floor_settp")).build(), pl -> {
            fe.arrival = Loc.of(pl);
            TowerConfig.saveFloors();
            Msg.send(pl, "admin.settp_done", "floor", n, "loc", fe.arrival.pretty());
            floor(pl, n);
        });
        gui.button(15, ItemBuilder.of(Items.ARMOR_STAND).name(Msg.raw("admin.floor_spawn")).build(), pl -> {
            TrainerNpcs.spawnFloor(pl.server, fe, Loc.of(pl));
            Msg.send(pl, "admin.npc_spawned", "floor", n);
            floor(pl, n);
        });
        gui.button(16, ItemBuilder.of(Items.TNT).name(Msg.raw("admin.floor_remove")).build(), pl -> {
            TrainerNpcs.remove(pl.server, fe.npcUuid, fe.npcLocation);
            fe.npcUuid = null;
            fe.npcLocation = null;
            TowerConfig.saveFloors();
            Msg.send(pl, "admin.npc_removed", "floor", n);
            floor(pl, n);
        });
        gui.button(22, ItemBuilder.of(Items.OAK_DOOR).name(Msg.raw("general.back")).build(), pl -> floors(pl, (n - 1) / 45));
        gui.open(p);
    }

    public static void bosses(ServerPlayer p) {
        List<Paged.Entry> entries = new ArrayList<>();
        String current = DailyBoss.currentId();
        for (Map.Entry<String, TrainerSpec> e : TowerConfig.bosses().bosses.entrySet()) {
            TrainerSpec t = e.getValue();
            entries.add(new Paged.Entry(ItemBuilder.of(e.getKey().equals(current) ? Items.WITHER_SKELETON_SKULL : Items.SKELETON_SKULL)
                    .name("&c" + t.display() + " &8(" + e.getKey() + ")").lore(Msg.format("admin.floor_team", "n", t.team.size()),
                            "&7IA : &f" + t.aiLevel() + " &7| Tier : &f" + t.tier, Msg.raw("admin.boss_click")).glow(e.getKey().equals(current)).build(),
                    (pl, c) -> {
                        pl.closeContainer();
                        EditorService.openBoss(pl, e.getKey());
                    }));
        }
        Paged.open(p, Msg.raw("admin.boss"), entries, 0, AdminMenus::panel, gui -> {
            gui.button(46, ItemBuilder.of(Items.COMPASS).name(Msg.raw("admin.boss_settp")).build(), pl -> {
                TowerConfig.data().bossArrival = Loc.of(pl);
                TowerConfig.saveFloors();
                Msg.send(pl, "admin.boss_settp_done", "loc", TowerConfig.data().bossArrival.pretty());
                bosses(pl);
            });
            gui.button(47, ItemBuilder.of(Items.ARMOR_STAND).name(Msg.raw("admin.boss_spawn")).build(), pl -> {
                fr.cobblepulse.battletower.command.Commands.spawnBossNpc(pl);
                bosses(pl);
            });
            gui.button(51, ItemBuilder.of(Items.CLOCK).name(Msg.raw("admin.boss_rotate")).build(), pl -> {
                DailyBoss.rotateIfNeeded(true);
                Msg.send(pl, "admin.boss_rotated", "boss", DailyBoss.currentId());
                bosses(pl);
            });
        });
    }

    public static void runs(ServerPlayer p) {
        List<Paged.Entry> entries = new ArrayList<>();
        for (RunState r : TowerManager.runs()) {
            entries.add(new Paged.Entry(ItemBuilder.of(Items.PLAYER_HEAD).name("&e" + r.name)
                    .lore("&7" + (r.daily ? "Boss du Jour" : "Etage " + r.floor) + " &8(" + r.phase + ")",
                            Msg.raw("admin.runs_click")).build(), (pl, c) -> {
                if (!Perms.staff(pl, Perms.STAFF_DISQUALIFY)) {
                    Msg.send(pl, "general.no_permission");
                    return;
                }
                if (c.shift()) {
                    TowerManager.disqualify(java.util.UUID.fromString(r.uuid), pl.getGameProfile().getName());
                    Msg.send(pl, "staff.disqualified", "player", r.name);
                    runs(pl);
                }
            }));
        }
        Paged.open(p, Msg.raw("admin.runs"), entries, 0, AdminMenus::panel);
    }

    public static int countConfigured() {
        int n = 0;
        for (FloorEntry fe : TowerConfig.floors()) if (fe.floor <= Cfg.floors() && TowerManager.floorProblem(fe) == null) n++;
        return n;
    }
}
