package fr.cobblepulse.battles.trainers;

import fr.cobblepulse.battles.hub.Hub;
import fr.cobblepulse.battletower.battle.CobblemonBridge;
import fr.cobblepulse.battletower.core.ItemBuilder;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Perms;
import fr.cobblepulse.battletower.core.TimeFmt;
import fr.cobblepulse.battletower.data.MonSpec;
import fr.cobblepulse.battletower.data.PlayerData;
import fr.cobblepulse.battletower.data.TrainerSpec;
import fr.cobblepulse.battletower.gui.ChatInput;
import fr.cobblepulse.battletower.gui.Gui;
import fr.cobblepulse.battletower.gui.Guis;
import fr.cobblepulse.battletower.net.EditorService;
import fr.cobblepulse.battletower.storage.PlayerStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** Ecrans des dresseurs hors Tour : defi, liste des dresseurs, administration. */
public final class TrainerMenus {
    private TrainerMenus() {}

    static String f(String key, Object... kv) {
        return Msg.formatBare(key, kv);
    }

    static List<String> rewardLines(TrainerSpec.WorldOptions o) {
        List<String> l = new ArrayList<>();
        if (o.bp > 0) l.add(f("trainer.reward_bp", "bp", o.bp));
        for (String i : o.items) l.add(f("trainer.reward_item", "item", i));
        if (!o.commands.isEmpty()) l.add(f("trainer.reward_commands", "n", o.commands.size()));
        if (l.isEmpty()) l.add(f("trainer.reward_none"));
        return l;
    }

    static String statusLine(WorldTrainers.Availability a) {
        return switch (a.status()) {
            case AVAILABLE -> f("trainer.status_available");
            case REMATCH_REWARD -> f("trainer.status_rematch_reward");
            case REMATCH -> f("trainer.status_rematch");
            case WAIT_WIN -> f("trainer.status_wait_win", "time", TimeFmt.duration(a.waitMs()));
            case WAIT_LOSS -> f("trainer.status_wait_loss", "time", TimeFmt.duration(a.waitMs()));
            case DONE -> f("trainer.status_done");
        };
    }

    /** Clic droit sur le PNJ : fiche du dresseur et bouton Defier. */
    public static void challenge(ServerPlayer p, WorldTrainers.WorldTrainer w) {
        TrainerSpec t = w.trainer;
        TrainerSpec.WorldOptions o = t.world == null ? new TrainerSpec.WorldOptions() : t.world;
        PlayerData d = PlayerStore.get(p);
        WorldTrainers.Availability a = WorldTrainers.availability(d, w);
        Gui g = new Gui(3, f("trainer.challenge_title", "trainer", t.display()));
        List<String> info = new ArrayList<>();
        info.add(f("trainer.info_format", "format", f(t.doubles() ? "trainer.format_doubles" : "trainer.format_singles")));
        int min = 100;
        int max = 1;
        for (MonSpec m : t.team) {
            min = Math.min(min, t.levelScale > 0 ? t.levelScale : m.level);
            max = Math.max(max, t.levelScale > 0 ? t.levelScale : m.level);
        }
        info.add(f("trainer.info_team", "n", t.team.size(), "min", t.team.isEmpty() ? 0 : min, "max", t.team.isEmpty() ? 0 : max));
        info.add(f("trainer.info_mode", "mode", f("exhibition".equals(o.mode) ? "trainer.mode_exhibition" : "trainer.mode_normal")));
        if (t.inverse) info.add(f("trainer.info_inverse"));
        info.add(statusLine(a));
        g.set(4, ItemBuilder.of(Items.PLAYER_HEAD).name("&e" + t.display()).lore(info).build());
        List<String> rewards = new ArrayList<>(rewardLines(o));
        rewards.add(0, f(a.rewards() ? "trainer.rewards_available" : "trainer.rewards_unavailable"));
        g.set(13, ItemBuilder.of(Items.CHEST).name(f("trainer.rewards_title")).lore(rewards).build());
        int slot = 9;
        for (MonSpec m : t.team) {
            if (slot > 12 && slot < 14) slot = 14;
            if (slot > 17) break;
            g.set(slot++, ItemBuilder.of(CobblemonBridge.speciesIcon(m.species)).name("&f" + m.species).build());
        }
        if (a.canFight()) {
            g.button(20, ItemBuilder.of(Items.IRON_SWORD).name(f("trainer.fight")).lore(f("trainer.fight_lore")).hideExtra().build(), pl -> {
                Gui.close(pl);
                WorldTrainers.challenge(pl, w);
            });
        } else {
            g.set(20, ItemBuilder.of(Items.BARRIER).name(statusLine(a)).build());
        }
        g.button(24, ItemBuilder.of(Items.OAK_DOOR).name(f("trainer.close")).build(), Gui::close);
        if (Perms.admin(p, TrainerCommands.PERM_ADMIN)) {
            g.button(26, ItemBuilder.of(Items.WRITABLE_BOOK).name(f("trainer.edit")).build(), pl -> EditorService.openWorld(pl, w.id));
        }
        g.open(p);
    }

    /** Liste des dresseurs (joueurs : progression ; admins : gestion). */
    public static void list(ServerPlayer p) {
        boolean admin = Perms.admin(p, TrainerCommands.PERM_ADMIN);
        List<WorldTrainers.WorldTrainer> all = WorldTrainers.all();
        Gui g = new Gui(6, f("trainer.list_title", "n", all.size())).list(true);
        PlayerData d = PlayerStore.get(p);
        int beaten = 0;
        int i = 0;
        for (WorldTrainers.WorldTrainer w : all) {
            PlayerData.TrainerRecord r = d.trainers.get(w.id);
            if (r != null && r.wins > 0) beaten++;
            if (i >= 45 && !fr.cobblepulse.battletower.gui.Screens.enabled(p)) continue;
            WorldTrainers.Availability a = WorldTrainers.availability(d, w);
            List<String> l = new ArrayList<>();
            l.add(statusLine(a));
            if (r != null) l.add(f("trainer.record", "wins", r.wins, "losses", r.losses));
            if (admin) {
                l.add(f("trainer.list_where", "pos", w.location == null ? "-" : w.location.pretty()));
                l.add(f("trainer.list_admin_hint"));
            }
            ItemStack icon = new ItemStack(r != null && r.wins > 0 ? Items.EMERALD : Items.PLAYER_HEAD);
            int slot = i < 45 ? i : 54 + i - 45;
            if (i == 45 && all.size() > 45) g.grow(54 + all.size() - 45);
            String id = w.id;
            if (admin) {
                g.button(slot, ItemBuilder.of(icon).name("&e" + w.trainer.display() + " &8(" + w.id + ")").lore(l).build(), (pl, c) -> {
                    WorldTrainers.WorldTrainer cur = WorldTrainers.get(id);
                    if (cur == null) return;
                    if (c.shift()) {
                        if (cur.location != null) cur.location.teleport(pl);
                    } else if (c.right()) {
                        Guis.confirm(pl, f("trainer.delete"), cur.trainer.display(), () -> {
                            WorldTrainers.remove(pl.server, cur);
                            Msg.send(pl, "trainer.deleted", "id", id);
                            list(pl);
                        }, () -> list(pl));
                    } else {
                        EditorService.openWorld(pl, id);
                    }
                });
            } else {
                g.set(slot, ItemBuilder.of(icon).name("&e" + w.trainer.display()).lore(l).build());
            }
            i++;
        }
        if (all.isEmpty()) g.set(22, ItemBuilder.of(Items.PAPER).name(f("trainer.list_empty")).build());
        g.button(45, ItemBuilder.of(Items.ARROW).name(f("hub.back")).build(), Hub::open);
        g.set(49, ItemBuilder.of(Items.EMERALD).name(f("trainer.progress", "n", beaten, "total", all.size())).build());
        if (admin) {
            g.button(51, ItemBuilder.of(fr.cobblepulse.battles.tool.NpcEditorTool.ITEM).name(f("tool.button")).lore(f("tool.button_lore")).build(),
                    fr.cobblepulse.battles.tool.NpcEditorTool::give);
            g.button(53, ItemBuilder.of(Items.LIME_DYE).name(f("trainer.create_here")).lore(f("trainer.create_here_lore")).build(), pl ->
                    ChatInput.ask(pl, "trainer.ask_id", id -> {
                        WorldTrainers.WorldTrainer w = WorldTrainers.create(pl, id);
                        if (w == null) Msg.send(pl, "trainer.create_failed", "id", id);
                        else {
                            Msg.send(pl, "trainer.created", "id", w.id);
                            EditorService.openWorld(pl, w.id);
                        }
                    }, () -> list(pl)));
        }
        g.open(p);
    }
}
