package fr.cobblepulse.battletower.core;

import fr.cobblepulse.battletower.battletower.Battletower;
import fr.cobblepulse.battletower.config.Cfg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Messages configurables (messages.toml) avec placeholders %cle%. */
public final class Msg {
    private Msg() {}

    public static String raw(String key) {
        if (Cfg.messages == null) return key;
        String v = Cfg.messages.str(key, null);
        if (v == null) {
            Battletower.LOG.warn("[Battletower] Message manquant dans messages.toml : {}", key);
            return key;
        }
        return v;
    }

    public static String format(String key, Object... kv) {
        String s = raw(key);
        if (s.contains("%prefix%")) s = s.replace("%prefix%", raw("general.prefix"));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("%" + kv[i] + "%", String.valueOf(kv[i + 1]));
        }
        return s;
    }

    public static MutableComponent get(String key, Object... kv) {
        return TextUtil.legacy(format(key, kv));
    }

    public static void send(ServerPlayer p, String key, Object... kv) {
        if (p != null) p.sendSystemMessage(get(key, kv));
    }

    public static void send(UUID uuid, String key, Object... kv) {
        MinecraftServer s = Battletower.server();
        if (s == null || uuid == null) return;
        ServerPlayer p = s.getPlayerList().getPlayer(uuid);
        if (p != null) p.sendSystemMessage(get(key, kv));
    }

    public static void send(CommandSourceStack src, String key, Object... kv) {
        src.sendSystemMessage(get(key, kv));
    }

    public static void broadcast(String key, Object... kv) {
        broadcast(get(key, kv));
    }

    public static void broadcast(Component c) {
        MinecraftServer s = Battletower.server();
        if (s != null) s.getPlayerList().broadcastSystemMessage(c, false);
    }

    public static void actionBar(ServerPlayer p, String key, Object... kv) {
        if (p != null) p.displayClientMessage(get(key, kv), true);
    }
}
