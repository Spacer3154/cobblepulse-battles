package fr.cobblepulse.battletower.menu;

import com.mojang.authlib.GameProfile;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.config.TierRules;
import fr.cobblepulse.battletower.core.ItemBuilder;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Perms;
import fr.cobblepulse.battletower.core.TimeFmt;
import fr.cobblepulse.battletower.data.FloorEntry;
import fr.cobblepulse.battletower.data.PlayerData;
import fr.cobblepulse.battletower.data.TowerData;
import fr.cobblepulse.battletower.data.RunState;
import fr.cobblepulse.battletower.data.TrainerSpec;
import fr.cobblepulse.battletower.economy.Achievements;
import fr.cobblepulse.battletower.gui.Gui;
import fr.cobblepulse.battletower.gui.Guis;
import fr.cobblepulse.battletower.gui.Paged;
import fr.cobblepulse.battletower.storage.PlayerStore;
import fr.cobblepulse.battletower.tower.DailyBoss;
import fr.cobblepulse.battletower.tower.TowerConfig;
import fr.cobblepulse.battletower.tower.TowerManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Menus joueurs : menu principal (9x3), classement (9x6), succes. */
public final class Menus {
    private Menus() {}

    // ------------------------------------------------------------------ Menu principal

    public static void main(ServerPlayer p) {
        PlayerData d = PlayerStore.get(p);
        Gui gui = new Gui(3, Msg.raw("menu.title"));
        gui.fill(Gui.pane());
        RunState run = TowerManager.run(p.getUUID());
        // Slot 4 : profil
        int rank = PlayerStore.rank(d.uuid);
        gui.set(4, ItemBuilder.of(Items.PLAYER_HEAD).head(p.getGameProfile()).name("&e" + d.name).lore(
                Msg.format("menu.profile_bp", "bp", d.bp),
                Msg.format("menu.profile_record", "floor", d.maxFloor, "floors", TowerManager.totalFloors()),
                Msg.format("menu.profile_completions", "n", d.completions),
                Msg.format("menu.profile_wins", "n", d.totalWins),
                Msg.format("menu.profile_best", "time", d.bestTimeMs > 0 ? TimeFmt.clock(d.bestTimeMs) : "-"),
                Msg.format("menu.profile_rank", "rank", rank > 0 ? "#" + rank : "-")).build());
        // Slot 10 : assaut
        boolean several = TowerConfig.towers().size() > 1;
        if (run != null && !run.daily) {
            gui.set(10, ItemBuilder.of(Items.IRON_SWORD).name(Msg.format("menu.run_title", "floor", run.floor)).lore(
                    Msg.format("menu.run_tower", "tower", TowerConfig.name(TowerManager.towerOf(run)), "floors", TowerManager.totalFloors(run)),
                    Msg.format("menu.run_time", "time", TimeFmt.clock(run.elapsed(TimeFmt.now()))),
                    Msg.raw("menu.run_click"), Msg.raw("menu.run_right")).glow(true).hideExtra().build(), (pl, c) -> {
                fr.cobblepulse.battletower.gui.Gui.close(pl);
                if (c.right()) TowerManager.openPc(pl);
                else if (c.shift()) confirmAbandon(pl);
                else TowerManager.engage(pl);
            });
        } else if (several) {
            List<String> lore = new ArrayList<>();
            lore.add(Msg.format("menu.towers_lore", "n", TowerConfig.towers().size()));
            if (!TowerManager.isOpen()) lore.add(Msg.raw("menu.tower_closed"));
            else lore.add(Msg.raw("menu.towers_click"));
            gui.button(10, ItemBuilder.of(Items.DIAMOND_SWORD).name(Msg.raw("menu.assault")).lore(lore).hideExtra().build(), Menus::chooseTower);
        } else {
            List<String> lore = new ArrayList<>();
            lore.add(Msg.format("menu.assault_lore", "floors", TowerManager.totalFloors()));
            long cd = TowerManager.cooldownLeft(d);
            if (!TowerManager.isOpen()) lore.add(Msg.raw("menu.tower_closed"));
            else if (cd > 0) lore.add(Msg.format("menu.cooldown", "time", TimeFmt.duration(cd)));
            else lore.add(Msg.raw("menu.assault_click"));
            gui.button(10, ItemBuilder.of(Items.DIAMOND_SWORD).name(Msg.raw("menu.assault")).lore(lore).hideExtra().build(), pl -> {
                fr.cobblepulse.battletower.gui.Gui.close(pl);
                TowerManager.startAssault(pl);
            });
        }
        // Slot 12 : Boss du Jour
        TrainerSpec boss = DailyBoss.current();
        List<String> blore = new ArrayList<>();
        blore.add(Msg.format("menu.boss_name", "boss", boss == null ? "-" : boss.display()));
        blore.add(Msg.raw("menu.boss_rewards"));
        for (String l : DailyBoss.rewardDisplay(DailyBoss.currentId())) blore.add("&7 - " + l);
        blore.add(DailyBoss.status(d));
        gui.button(12, ItemBuilder.of(Items.WITHER_SKELETON_SKULL).name(Msg.raw("menu.boss")).lore(blore).build(), pl -> {
            fr.cobblepulse.battletower.gui.Gui.close(pl);
            DailyBoss.start(pl);
        });
        gui.button(14, ItemBuilder.of(Items.GOLDEN_HELMET).name(Msg.raw("menu.leaderboard")).lore(Msg.raw("menu.leaderboard_lore")).hideExtra().build(),
                Menus::leaderboard);
        gui.button(16, ItemBuilder.of(Items.WRITABLE_BOOK).name(Msg.raw("menu.achievements")).lore(Msg.raw("menu.achievements_lore")).build(),
                Menus::achievements);
        gui.button(22, ItemBuilder.of(Items.BARRIER).name(Msg.raw("menu.close")).build(), fr.cobblepulse.battletower.gui.Gui::close);
        if (Perms.isAdmin(p)) {
            gui.button(26, ItemBuilder.of(Items.COMMAND_BLOCK).name(Msg.raw("menu.admin")).build(), AdminMenus::panel);
        }
        gui.open(p);
    }

    /** Icone d'une tour (champ icon, sinon epee de diamant pour la principale et de fer pour les autres). */
    public static net.minecraft.world.item.Item towerIcon(TowerData t) {
        if (t != null && t.icon != null && !t.icon.isBlank()) {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(t.icon.contains(":") ? t.icon : "minecraft:" + t.icon);
            if (rl != null) {
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
                if (item != Items.AIR) return item;
            }
        }
        return TowerConfig.isMain(t) ? Items.DIAMOND_SWORD : Items.IRON_SWORD;
    }

    /** Choix de la tour a gravir : etages, record, temps, cooldown. */
    public static void chooseTower(ServerPlayer p) {
        PlayerData d = PlayerStore.get(p);
        List<fr.cobblepulse.battletower.gui.Paged.Entry> entries = new ArrayList<>();
        for (TowerData t : TowerConfig.towers()) {
            boolean main = TowerConfig.isMain(t);
            int size = TowerConfig.size(t);
            PlayerData.TowerRecord rec = main ? null : d.towers.get(t.id);
            int best = TowerManager.record(d, t);
            int completions = main ? d.completions : rec == null ? 0 : rec.completions;
            long bestTime = main ? d.bestTimeMs : rec == null ? 0 : rec.bestTimeMs;
            long cd = TowerManager.cooldownLeft(d, t);
            boolean locked = !t.permission.isBlank() && !Perms.player(p, t.permission);
            List<String> lore = new ArrayList<>();
            lore.add(Msg.format("menu.tower_floors", "floors", size));
            lore.add(Msg.format("menu.profile_record", "floor", best, "floors", size));
            lore.add(Msg.format("menu.profile_completions", "n", completions));
            lore.add(Msg.format("menu.profile_best", "time", bestTime > 0 ? TimeFmt.clock(bestTime) : "-"));
            if (!main && t.finalBp > 0) lore.add(Msg.format("menu.tower_final_bp", "bp", t.finalBp));
            if (locked) lore.add(Msg.raw("menu.tower_locked"));
            else if (!TowerManager.isOpen()) lore.add(Msg.raw("menu.tower_closed"));
            else if (cd > 0) lore.add(Msg.format("menu.cooldown", "time", TimeFmt.duration(cd)));
            else lore.add(Msg.raw("menu.assault_click"));
            entries.add(new fr.cobblepulse.battletower.gui.Paged.Entry(ItemBuilder.of(towerIcon(t)).name("&6&l" + TowerConfig.name(t))
                    .count(Math.max(1, Math.min(64, size))).lore(lore).glow(completions > 0).hideExtra().build(), (pl, c) -> {
                fr.cobblepulse.battletower.gui.Gui.close(pl);
                TowerManager.startAssault(pl, t.id);
            }));
        }
        fr.cobblepulse.battletower.gui.Paged.open(p, Msg.raw("menu.towers_title"), entries, 0, Menus::main);
    }

    public static void confirmAbandon(ServerPlayer p) {
        Guis.confirm(p, Msg.raw("menu.abandon_title"), Msg.raw("menu.abandon_question"), () -> TowerManager.abandon(p), null);
    }

    // ------------------------------------------------------------------ Classement

    private static final int[] TOP_SLOTS = {4, 12, 14, 19, 20, 21, 22, 23, 24, 25};
    private static final String[] RANK_COLORS = {"&6&l", "&f&l", "&c&l", "&e", "&e", "&e", "&e", "&e", "&e", "&e"};

    public static void leaderboard(ServerPlayer p) {
        Gui gui = new Gui(6, Msg.raw("top.title"));
        gui.fill(Gui.pane());
        List<PlayerData> top = PlayerStore.top(10);
        for (int i = 0; i < TOP_SLOTS.length; i++) {
            if (i >= top.size()) {
                gui.set(TOP_SLOTS[i], ItemBuilder.of(Items.SKELETON_SKULL).name(RANK_COLORS[i] + "#" + (i + 1) + " &8-").build());
                continue;
            }
            PlayerData d = top.get(i);
            gui.set(TOP_SLOTS[i], ItemBuilder.of(Items.PLAYER_HEAD).head(profile(d)).name(RANK_COLORS[i] + "#" + (i + 1) + " &f" + d.name)
                    .lore(stats(d)).build());
        }
        gui.set(40, ItemBuilder.of(Items.BOOK).name(Msg.raw("top.criteria")).lore(Msg.raw("top.criteria_1"), Msg.raw("top.criteria_2"),
                Msg.raw("top.criteria_3"), Msg.raw("top.criteria_4")).build());
        PlayerData me = PlayerStore.get(p);
        int rank = PlayerStore.rank(me.uuid);
        List<String> lore = new ArrayList<>(stats(me));
        lore.add(0, Msg.format("top.you_rank", "rank", rank > 0 ? "#" + rank : "-", "total", PlayerStore.rankedCount()));
        gui.set(49, ItemBuilder.of(Items.PLAYER_HEAD).head(p.getGameProfile()).name(Msg.raw("top.you")).lore(lore).glow(true).build());
        gui.button(45, ItemBuilder.of(Items.OAK_DOOR).name(Msg.raw("general.back")).build(), Menus::main);
        gui.button(53, ItemBuilder.of(Items.BARRIER).name(Msg.raw("menu.close")).build(), fr.cobblepulse.battletower.gui.Gui::close);
        gui.open(p);
    }

    private static GameProfile profile(PlayerData d) {
        try {
            return new GameProfile(UUID.fromString(d.uuid), d.name == null || d.name.isEmpty() ? "?" : d.name);
        } catch (Exception e) {
            return null;
        }
    }

    public static List<String> stats(PlayerData d) {
        List<String> l = new ArrayList<>();
        l.add(Msg.format("top.stat_floor", "floor", d.maxFloor, "floors", TowerManager.totalFloors()));
        l.add(Msg.format("top.stat_completions", "n", d.completions));
        l.add(Msg.format("top.stat_wins", "n", d.totalWins));
        l.add(Msg.format("top.stat_time", "time", d.bestTimeMs > 0 ? TimeFmt.clock(d.bestTimeMs) : "-"));
        return l;
    }

    // ------------------------------------------------------------------ Succes

    public static void achievements(ServerPlayer p) {
        PlayerData d = PlayerStore.get(p);
        List<Paged.Entry> entries = new ArrayList<>();
        int done = 0;
        List<Achievements.Def> defs = Achievements.all();
        for (Achievements.Def def : defs) {
            boolean unlocked = d.achievements.containsKey(def.id());
            if (unlocked) done++;
            long prog = Math.min(def.target(), Achievements.progress(d, def));
            String state = unlocked ? Msg.raw("achievement.state_done")
                    : prog > 0 ? Msg.format("achievement.state_progress", "progress", prog, "target", def.target())
                    : Msg.raw("achievement.state_locked");
            List<String> lore = new ArrayList<>();
            lore.add("&7" + def.description());
            lore.add("");
            lore.add(state);
            if (def.bp() > 0) lore.add(Msg.format("achievement.reward_bp", "bp", def.bp()));
            if (unlocked) lore.add(Msg.format("achievement.unlocked_at", "date", TimeFmt.date(d.achievements.get(def.id()))));
            ItemStack icon = ItemBuilder.of(unlocked ? ItemBuilder.item(def.icon(), Items.PAPER) : prog > 0 ? Items.CLOCK : Items.GRAY_DYE)
                    .name((unlocked ? "&a" : "&7") + fr.cobblepulse.battletower.core.TextUtil.strip(def.name())).lore(lore).glow(unlocked).hideExtra().build();
            entries.add(new Paged.Entry(icon, null));
        }
        Paged.open(p, Msg.format("achievement.title", "done", done, "total", defs.size()), entries, 0, Menus::main);
    }

    // ------------------------------------------------------------------ Utilitaires d'affichage

    public static String floorSummary(FloorEntry fe) {
        TierRules t = Cfg.tier(fe.trainer.tier);
        return fe.trainer.display() + " &8(" + (t == null ? fe.trainer.tier : t.displayName) + ", " + fe.trainer.aiLevel() + ")";
    }
}
