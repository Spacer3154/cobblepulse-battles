package fr.cobblepulse.battletower.menu;

import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.core.ItemBuilder;
import fr.cobblepulse.battletower.core.Loc;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Perms;
import fr.cobblepulse.battletower.data.FloorEntry;
import fr.cobblepulse.battletower.data.TowerData;
import fr.cobblepulse.battletower.gui.ChatInput;
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
        int configured = countConfigured(TowerConfig.data());
        int towers = TowerConfig.towers().size();
        gui.button(12, ItemBuilder.of(Items.LADDER).name(Msg.raw("admin.towers")).lore(
                Msg.format("admin.floors_ready", "ready", configured, "floors", TowerManager.totalFloors()),
                Msg.format("admin.towers_count", "n", towers)).build(), AdminMenus::towers);
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
        gui.button(22, ItemBuilder.of(Items.BARRIER).name(Msg.raw("menu.close")).build(), fr.cobblepulse.battletower.gui.Gui::close);
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

    /** Liste des tours (principale + secondaires) et creation d'une nouvelle tour. */
    public static void towers(ServerPlayer p) {
        List<Paged.Entry> entries = new ArrayList<>();
        for (TowerData t : TowerConfig.towers()) {
            boolean main = TowerConfig.isMain(t);
            int size = TowerConfig.size(t);
            int ready = countConfigured(t);
            List<String> lore = new ArrayList<>();
            lore.add(Msg.format("admin.tower_id", "id", t.id));
            lore.add(Msg.format("admin.floors_ready", "ready", ready, "floors", size));
            lore.add(Msg.format("admin.tower_exit", "loc", TowerManager.exitOf(t) == null ? "-" : TowerManager.exitOf(t).pretty()));
            if (!main) {
                lore.add(Msg.format("admin.tower_settings", "cooldown", t.cooldownHours < 0 ? Cfg.tower.l("defeat_cooldown_hours", 24) : t.cooldownHours,
                        "bp", t.finalBp, "mult", t.bpMultiplier));
            }
            lore.add(Msg.raw("admin.tower_click"));
            if (!main) lore.add(Msg.raw("admin.tower_click_delete"));
            entries.add(new Paged.Entry(ItemBuilder.of(Menus.towerIcon(t)).name("&6" + TowerConfig.name(t)).count(Math.max(1, Math.min(64, size)))
                    .lore(lore).glow(ready >= size).hideExtra().build(), (pl, c) -> {
                if (!main && c.shift() && c.right()) {
                    fr.cobblepulse.battletower.command.Commands.towerDelete(pl.createCommandSourceStack(), t.id);
                    towers(pl);
                } else if (c.right()) {
                    t.exit = Loc.of(pl);
                    TowerConfig.save(t);
                    Msg.send(pl, "admin.exit_set", "loc", t.exit.pretty());
                    towers(pl);
                } else {
                    floors(pl, t.id, 0);
                }
            }));
        }
        Paged.open(p, Msg.raw("admin.towers"), entries, 0, AdminMenus::panel, gui -> gui.button(51, ItemBuilder.of(Items.LIME_DYE)
                .name(Msg.raw("admin.tower_create")).lore(Msg.raw("admin.tower_create_lore")).build(), pl -> ChatInput.ask(pl, "admin.tower_create_ask", text -> {
            String[] parts = text.trim().split("\\s+", 3);
            int size;
            try {
                size = parts.length < 2 ? 10 : Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                size = 10;
            }
            fr.cobblepulse.battletower.command.Commands.towerCreate(pl.createCommandSourceStack(), parts[0], Math.max(1, Math.min(200, size)),
                    parts.length > 2 ? parts[2] : "");
            towers(pl);
        }, () -> towers(pl))));
    }

    public static void floors(ServerPlayer p, int page) {
        floors(p, TowerConfig.MAIN, page);
    }

    public static void floors(ServerPlayer p, String towerId, int page) {
        TowerData tower = TowerConfig.tower(towerId);
        if (tower == null) return;
        List<Paged.Entry> entries = new ArrayList<>();
        for (FloorEntry fe : tower.floors) {
            if (fe.floor > TowerConfig.size(tower)) continue;
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
                    (pl, c) -> floor(pl, tower.id, fe.floor)));
        }
        Paged.open(p, Msg.raw("admin.floors") + " - " + TowerConfig.name(tower), entries, page, AdminMenus::towers);
    }

    public static void floor(ServerPlayer p, int n) {
        floor(p, TowerConfig.MAIN, n);
    }

    public static void floor(ServerPlayer p, String towerId, int n) {
        FloorEntry fe = TowerConfig.floor(towerId, n);
        if (fe == null) return;
        String tid = fe.tower;
        Gui gui = new Gui(3, Msg.format("admin.floor_name", "floor", n));
        gui.fill(Gui.pane());
        gui.button(10, ItemBuilder.of(Items.ENDER_PEARL).name(Msg.raw("admin.floor_goto")).build(), pl -> {
            if (fe.arrival != null) fe.arrival.teleport(pl);
            fr.cobblepulse.battletower.gui.Gui.close(pl);
        });
        gui.button(11, ItemBuilder.of(Items.WRITABLE_BOOK).name(Msg.raw("admin.floor_edit")).lore(Msg.raw("admin.floor_edit_lore")).build(), pl -> {
            fr.cobblepulse.battletower.gui.Gui.close(pl);
            EditorService.openFloor(pl, tid, n);
        });
        gui.button(13, ItemBuilder.of(Items.COMPASS).name(Msg.raw("admin.floor_settp")).build(), pl -> {
            fe.arrival = Loc.of(pl);
            TowerConfig.saveFloor(fe);
            Msg.send(pl, "admin.settp_done", "floor", n, "loc", fe.arrival.pretty());
            floor(pl, tid, n);
        });
        gui.button(15, ItemBuilder.of(Items.ARMOR_STAND).name(Msg.raw("admin.floor_spawn")).build(), pl -> {
            TrainerNpcs.spawnFloor(pl.server, fe, Loc.of(pl));
            Msg.send(pl, "admin.npc_spawned", "floor", n);
            floor(pl, tid, n);
        });
        gui.button(16, ItemBuilder.of(Items.TNT).name(Msg.raw("admin.floor_remove")).build(), pl -> {
            TrainerNpcs.remove(pl.server, fe.npcUuid, fe.npcLocation);
            fe.npcUuid = null;
            fe.npcLocation = null;
            TowerConfig.saveFloor(fe);
            Msg.send(pl, "admin.npc_removed", "floor", n);
            floor(pl, tid, n);
        });
        gui.button(22, ItemBuilder.of(Items.OAK_DOOR).name(Msg.raw("general.back")).build(), pl -> floors(pl, tid, (n - 1) / 45));
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
                        fr.cobblepulse.battletower.gui.Gui.close(pl);
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
                    .lore("&7" + (r.daily ? "Boss du Jour" : TowerConfig.name(TowerManager.towerOf(r)) + " - Etage " + r.floor) + " &8(" + r.phase + ")",
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
        return countConfigured(TowerConfig.data());
    }

    public static int countConfigured(TowerData t) {
        int n = 0;
        int size = TowerConfig.size(t);
        for (FloorEntry fe : t.floors) if (fe.floor <= size && TowerManager.floorProblem(fe) == null) n++;
        return n;
    }
}
