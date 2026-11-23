package fr.cobblepulse.battles.ranked;

import fr.cobblepulse.battletower.config.TomlFile;
import fr.cobblepulse.battletower.core.Msg;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Restrictions pendant la file d'attente et pendant un match (restrictions.toml). */
public final class Restrictions {
    private static final Map<UUID, Long> LAST_WARN = new ConcurrentHashMap<>();
    /** Auto-tests : nombre de commandes bloquees. */
    public static final java.util.concurrent.atomic.AtomicInteger BLOCKED = new java.util.concurrent.atomic.AtomicInteger();

    private Restrictions() {}

    /** Section active pour le joueur : "match", "file" ou null. */
    static String section(UUID uuid) {
        if (MatchManager.inMatch(uuid)) return "match";
        if (RankedQueue.queued(uuid)) return "file";
        return null;
    }

    public static boolean blocked(Player p, String rule) {
        if (!(p instanceof ServerPlayer sp) || RankedConfig.restrictions == null) return false;
        String sec = section(sp.getUUID());
        if (sec == null) return false;
        TomlFile t = RankedConfig.restrictions;
        boolean b = t.b(sec + "." + rule, false);
        if (b) warn(sp, sec);
        return b;
    }

    private static void warn(ServerPlayer p, String sec) {
        long now = System.currentTimeMillis();
        Long last = LAST_WARN.get(p.getUUID());
        if (last != null && now - last < 2000) return;
        LAST_WARN.put(p.getUUID(), now);
        Msg.send(p, "file".equals(sec) ? "ranked.restricted_queue" : "ranked.restricted_match");
    }

    /** Appele par le mixin de commandes. */
    public static boolean blockCommand(ServerPlayer p, String command) {
        if (RankedConfig.restrictions == null) return false;
        String sec = section(p.getUUID());
        if (sec == null) return false;
        String c = command.startsWith("/") ? command.substring(1) : command;
        String root = c.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon >= 0) root = root.substring(colon + 1);
        // les commandes de la Ranked restent toujours possibles (quitter la file, accepter...)
        if (root.equals("ranked") || root.equals("amical") || root.equals("battles") || root.equals("combats")) return false;
        for (String b : RankedConfig.restrictions.list(sec + ".commandes_bloquees")) {
            if (root.equalsIgnoreCase(b.trim())) {
                Msg.send(p, "ranked.command_blocked", "command", root);
                BLOCKED.incrementAndGet();
                return true;
            }
        }
        return false;
    }

    static boolean isPcBlock(net.minecraft.world.level.block.state.BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return id.equals("cobblemon:pc");
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) return InteractionResult.PASS;
            var state = level.getBlockState(hit.getBlockPos());
            if (isPcBlock(state) && blocked(player, "pc")) return InteractionResult.FAIL;
            ItemStack inHand = player.getItemInHand(hand);
            if (inHand.getItem() instanceof BlockItem && blocked(player, "poser_blocs")) return InteractionResult.FAIL;
            if (blocked(player, "interagir_blocs")) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> !blocked(player, "casser_blocs"));
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack st = player.getItemInHand(hand);
            if (!level.isClientSide() && blocked(player, "utiliser_objets")) return InteractionResultHolder.fail(st);
            return InteractionResultHolder.pass(st);
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!level.isClientSide() && blocked(player, "interagir_entites")) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!level.isClientSide() && blocked(player, "attaquer")) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
    }
}
