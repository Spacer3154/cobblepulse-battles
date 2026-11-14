package fr.cobblepulse.battletower.economy;

import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.config.TomlFile;
import fr.cobblepulse.battletower.core.ItemBuilder;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Perms;
import fr.cobblepulse.battletower.core.Rewards;
import fr.cobblepulse.battletower.data.PlayerData;
import fr.cobblepulse.battletower.gui.Gui;
import fr.cobblepulse.battletower.gui.Guis;
import fr.cobblepulse.battletower.integration.LuckPermsBridge;
import fr.cobblepulse.battletower.storage.PlayerStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Boutique BP (shop.toml) ouverte par le PNJ marchand. */
public final class Shop {
    public record Entry(String id, int slot, String icon, String name, List<String> lore, long price, List<String> give,
                        List<String> commands, int limit, String permission) {}

    private Shop() {}

    public static List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        int i = 0;
        for (TomlFile t : Cfg.shop.tables("items")) {
            String id = t.str("id", "item_" + i).toLowerCase(Locale.ROOT);
            out.add(new Entry(id, t.i("slot", i), t.str("icon", "minecraft:paper"), t.str("name", id), t.list("lore"),
                    Math.max(0, t.l("price", 0)), t.list("give"), t.list("commands"), Math.max(0, t.i("limit", 0)), t.str("permission", "")));
            i++;
        }
        return out;
    }

    public static void open(ServerPlayer p) {
        if (!Perms.player(p, Perms.SHOP)) {
            Msg.send(p, "general.no_permission");
            return;
        }
        PlayerData d = PlayerStore.get(p);
        int rows = Math.max(1, Math.min(6, Cfg.shop.i("menu.rows", 6)));
        Gui gui = new Gui(rows, Cfg.shop.str("menu.title", "&8Boutique BP"));
        gui.fill(Gui.pane());
        for (Entry e : entries()) {
            if (e.slot() < 0 || e.slot() >= gui.size()) continue;
            int bought = d.purchases.getOrDefault(e.id(), 0);
            List<String> lore = new ArrayList<>(e.lore());
            lore.add("");
            lore.add(Msg.format("shop.price", "price", e.price()));
            if (e.limit() > 0) lore.add(Msg.format("shop.limit", "bought", bought, "limit", e.limit()));
            lore.add(d.bp >= e.price() ? Msg.raw("shop.click_buy") : Msg.raw("shop.not_enough"));
            gui.button(e.slot(), ItemBuilder.of(ItemBuilder.item(e.icon(), Items.PAPER)).name(e.name()).lore(lore).hideExtra().build(),
                    pl -> attempt(pl, e));
        }
        gui.set(rows * 9 - 5, ItemBuilder.of(Items.SUNFLOWER).name(Msg.format("shop.balance", "bp", d.bp)).build());
        gui.open(p);
    }

    private static void attempt(ServerPlayer p, Entry e) {
        if (Cfg.shop.b("menu.confirm", true)) {
            Guis.confirm(p, Msg.raw("shop.confirm_title"), Msg.format("shop.confirm_question", "name", e.name(), "price", e.price()),
                    () -> {
                        buy(p, e);
                        open(p);
                    }, () -> open(p));
        } else {
            buy(p, e);
            open(p);
        }
    }

    /** Achat : verifie solde, limite et permission, puis donne items et execute les commandes. */
    public static boolean buy(ServerPlayer p, Entry e) {
        PlayerData d = PlayerStore.get(p);
        if (!e.permission().isBlank() && LuckPermsBridge.present() && !Perms.vip(p, e.permission())) {
            Msg.send(p, "shop.no_permission");
            return false;
        }
        int bought = d.purchases.getOrDefault(e.id(), 0);
        if (e.limit() > 0 && bought >= e.limit()) {
            Msg.send(p, "shop.limit_reached");
            return false;
        }
        if (!BattlePoints.take(d, e.price())) {
            Msg.send(p, "shop.not_enough_chat", "price", e.price(), "bp", d.bp);
            return false;
        }
        d.purchases.put(e.id(), bought + 1);
        Rewards.giveItems(p, e.give());
        Rewards.runCommands(p, d.name, e.commands());
        PlayerStore.save(d);
        Msg.send(p, "shop.bought", "name", e.name(), "price", e.price(), "bp", d.bp);
        return true;
    }
}
