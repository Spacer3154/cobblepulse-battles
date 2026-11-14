package fr.cobblepulse.battletower.core;

import fr.cobblepulse.battletower.Battletower;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** Distribution d'items ("modid:item quantite") et execution de commandes console (%player%). */
public final class Rewards {
    private Rewards() {}

    public static void giveItems(ServerPlayer p, List<String> specs) {
        if (p == null || specs == null) return;
        for (String spec : specs) giveItem(p, spec);
    }

    public static boolean giveItem(ServerPlayer p, String spec) {
        if (spec == null || spec.isBlank()) return false;
        String[] parts = spec.trim().split("\\s+");
        Item item = ItemBuilder.item(parts[0], Items.AIR);
        if (item == Items.AIR) {
            Battletower.LOG.warn("[Battletower] Item inconnu dans une recompense : {}", parts[0]);
            return false;
        }
        int count = 1;
        if (parts.length > 1) {
            try {
                count = Math.max(1, Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        while (count > 0) {
            int n = Math.min(count, item.getDefaultMaxStackSize());
            ItemStack stack = new ItemStack(item, n);
            if (!p.getInventory().add(stack)) p.drop(stack, false);
            count -= n;
        }
        return true;
    }

    public static void runCommands(ServerPlayer p, String name, List<String> commands) {
        MinecraftServer s = Battletower.server();
        if (s == null || commands == null) return;
        String player = p != null ? p.getGameProfile().getName() : name;
        for (String c : commands) {
            if (c == null || c.isBlank()) continue;
            String cmd = c.replace("%player%", player == null ? "" : player).trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            try {
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), cmd);
            } catch (Throwable t) {
                Battletower.LOG.warn("[Battletower] Commande de recompense en echec '{}' : {}", cmd, t.toString());
            }
        }
    }
}
