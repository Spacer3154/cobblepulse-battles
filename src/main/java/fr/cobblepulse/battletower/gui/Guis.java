package fr.cobblepulse.battletower.gui;

import fr.cobblepulse.battletower.core.ItemBuilder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Menus utilitaires : confirmation, choix d'un joueur en ligne. */
public final class Guis {
    private Guis() {}

    public static void confirm(ServerPlayer player, String title, String question, Runnable yes, Runnable no) {
        Gui gui = new Gui(3, title);
        gui.fill(Gui.pane());
        gui.button(11, ItemBuilder.of(Items.LIME_CONCRETE).name("&a&lConfirmer").lore("&7" + question).build(), p -> {
            p.closeContainer();
            yes.run();
        });
        gui.button(15, ItemBuilder.of(Items.RED_CONCRETE).name("&c&lAnnuler").build(), p -> {
            p.closeContainer();
            if (no != null) no.run();
        });
        gui.open(player);
    }

    public static void pickOnlinePlayer(ServerPlayer viewer, String title, Consumer<ServerPlayer> onPick, Consumer<ServerPlayer> back) {
        List<ServerPlayer> players = new ArrayList<>(viewer.server.getPlayerList().getPlayers());
        players.sort(Comparator.comparing(p -> p.getGameProfile().getName().toLowerCase()));
        List<Paged.Entry> entries = new ArrayList<>();
        for (ServerPlayer target : players) {
            entries.add(new Paged.Entry(ItemBuilder.of(Items.PLAYER_HEAD).head(target.getGameProfile())
                    .name("&e" + target.getGameProfile().getName()).lore("&7Cliquez pour selectionner").build(),
                    (p, c) -> {
                        ServerPlayer fresh = p.server.getPlayerList().getPlayer(target.getUUID());
                        if (fresh != null) onPick.accept(fresh);
                        else p.closeContainer();
                    }));
        }
        Paged.open(viewer, title, entries, 0, back);
    }
}
