package fr.cobblepulse.battles.hub;

import fr.cobblepulse.battles.ranked.Ranked;
import fr.cobblepulse.battles.ranked.RankedConfig;
import fr.cobblepulse.battles.ranked.RankedMenus;
import fr.cobblepulse.battles.ranked.RankedProfile;
import fr.cobblepulse.battles.ranked.RankedStore;
import fr.cobblepulse.battles.trainers.TrainerCommands;
import fr.cobblepulse.battles.trainers.TrainerMenus;
import fr.cobblepulse.battles.trainers.WorldTrainers;
import fr.cobblepulse.battletower.core.ItemBuilder;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Perms;
import fr.cobblepulse.battletower.data.PlayerData;
import fr.cobblepulse.battletower.gui.Gui;
import fr.cobblepulse.battletower.menu.AdminMenus;
import fr.cobblepulse.battletower.menu.Menus;
import fr.cobblepulse.battletower.storage.PlayerStore;
import fr.cobblepulse.battletower.tower.TowerManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/** Accueil de CobblePulse-Battles (/battles, /combats) : Tour, Ranked, Dresseurs, Classements. */
public final class Hub {
    private Hub() {}

    static String f(String key, Object... kv) {
        return Msg.formatBare(key, kv);
    }

    public static void open(ServerPlayer p) {
        Gui g = new Gui(3, f("hub.title"));
        PlayerData d = PlayerStore.get(p);
        g.button(10, ItemBuilder.of(Items.NETHERITE_SWORD).name(f("hub.tower")).lore(
                f("hub.tower_lore", "floor", d.maxFloor, "floors", TowerManager.totalFloors(), "bp", d.bp)).hideExtra().build(), Menus::main);
        if (RankedConfig.enabled()) {
            RankedProfile rp = RankedStore.get(p);
            g.button(12, ItemBuilder.of(RankedConfig.rankFor(rp.stats("simple").elo).icon()).name(f("hub.ranked")).lore(
                    f("hub.ranked_lore", "elo", rp.stats("simple").elo, "elo2", rp.stats("double").elo,
                            "rank", RankedConfig.rankFor(rp.stats("simple").elo).name())).build(), RankedMenus::main);
        }
        int beaten = 0;
        for (WorldTrainers.WorldTrainer w : WorldTrainers.all()) {
            PlayerData.TrainerRecord r = d.trainers.get(w.id);
            if (r != null && r.wins > 0) beaten++;
        }
        if (Perms.player(p, TrainerCommands.PERM_USE)) {
            g.button(14, ItemBuilder.of(Items.PLAYER_HEAD).name(f("hub.trainers")).lore(
                    f("hub.trainers_lore", "n", beaten, "total", WorldTrainers.all().size())).build(), TrainerMenus::list);
        }
        g.button(16, ItemBuilder.of(Items.GOLDEN_HELMET).name(f("hub.tops")).lore(f("hub.tops_lore")).hideExtra().build(), Hub::tops);
        g.button(22, ItemBuilder.of(Items.BARRIER).name(f("hub.close")).build(), Gui::close);
        if (Perms.isAdmin(p)) g.button(26, ItemBuilder.of(Items.COMMAND_BLOCK).name(f("hub.admin")).build(), Hub::admin);
        g.open(p);
    }

    static void tops(ServerPlayer p) {
        Gui g = new Gui(3, f("hub.tops"));
        g.button(11, ItemBuilder.of(Items.NETHERITE_SWORD).name(f("hub.top_tower")).hideExtra().build(), Menus::leaderboard);
        g.button(13, ItemBuilder.of(Items.IRON_SWORD).name(f("hub.top_ranked", "format", "Simple")).hideExtra().build(), pl -> RankedMenus.leaderboard(pl, "simple"));
        g.button(15, ItemBuilder.of(Items.SHIELD).name(f("hub.top_ranked", "format", "Double")).hideExtra().build(), pl -> RankedMenus.leaderboard(pl, "double"));
        g.button(18, ItemBuilder.of(Items.ARROW).name(f("hub.back")).build(), Hub::open);
        g.open(p);
    }

    static void admin(ServerPlayer p) {
        Gui g = new Gui(3, f("hub.admin"));
        if (Perms.admin(p, Perms.ADMIN)) g.button(11, ItemBuilder.of(Items.NETHERITE_SWORD).name(f("hub.admin_tower")).hideExtra().build(), AdminMenus::panel);
        if (Perms.admin(p, Ranked.PERM_ADMIN)) g.button(13, ItemBuilder.of(Items.DIAMOND_SWORD).name(f("hub.admin_ranked")).hideExtra().build(), RankedMenus::admin);
        if (Perms.admin(p, TrainerCommands.PERM_ADMIN)) g.button(15, ItemBuilder.of(Items.PLAYER_HEAD).name(f("hub.admin_trainers")).build(), TrainerMenus::list);
        g.button(18, ItemBuilder.of(Items.ARROW).name(f("hub.back")).build(), Hub::open);
        g.open(p);
    }
}
