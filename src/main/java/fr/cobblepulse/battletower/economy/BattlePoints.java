package fr.cobblepulse.battletower.economy;

import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.config.TomlFile;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Perms;
import fr.cobblepulse.battletower.core.Rewards;
import fr.cobblepulse.battletower.data.PlayerData;
import fr.cobblepulse.battletower.storage.PlayerStore;
import net.minecraft.server.level.ServerPlayer;

/** Battle Points : gains progressifs par etage (rewards.toml), bonus VIP, paliers et recompense finale. */
public final class BattlePoints {
    private BattlePoints() {}

    /** BP de base d'un etage : la derniere plage "debut-fin" (ou "n") correspondante de [floors]. */
    public static long baseForFloor(int floor) {
        long value = 1;
        for (String key : Cfg.rewards.keys("floors")) {
            String k = key.replace("\"", "").trim();
            int from;
            int to;
            try {
                if (k.contains("-")) {
                    String[] parts = k.split("-");
                    from = Integer.parseInt(parts[0].trim());
                    to = Integer.parseInt(parts[1].trim());
                } else {
                    from = to = Integer.parseInt(k);
                }
            } catch (NumberFormatException e) {
                continue;
            }
            if (floor >= from && floor <= to) value = Cfg.rewards.l("floors." + quoteIfNeeded(key), value);
        }
        return value;
    }

    private static String quoteIfNeeded(String key) {
        return key;
    }

    public static double multiplier(ServerPlayer p) {
        if (p != null && Perms.vip(p, Perms.VIP_BOOST)) return Math.max(1.0, Cfg.rewards.d("vip.bp_multiplier", 1.5));
        return 1.0;
    }

    public static long forFloor(int floor, ServerPlayer p) {
        return Math.round(baseForFloor(floor) * multiplier(p));
    }

    public static void give(PlayerData d, long amount) {
        if (amount <= 0) return;
        d.bp += amount;
        d.bpEarned += amount;
        PlayerStore.save(d);
    }

    public static boolean take(PlayerData d, long amount) {
        if (amount < 0 || d.bp < amount) return false;
        d.bp -= amount;
        PlayerStore.save(d);
        return true;
    }

    public static void milestone(int floor, ServerPlayer p, PlayerData d) {
        TomlFile m = Cfg.rewards.sub("milestones." + floor);
        if (!m.has("bp") && !m.has("items") && !m.has("commands")) return;
        long bp = m.l("bp", 0);
        give(d, bp);
        if (p != null) {
            Rewards.giveItems(p, m.list("items"));
            if (bp > 0) Msg.send(p, "tower.milestone_reward", "floor", floor, "bp", bp);
        }
        Rewards.runCommands(p, d.name, m.list("commands"));
    }

    public static void finalReward(ServerPlayer p, PlayerData d) {
        TomlFile f = Cfg.rewards.sub("final");
        long bp = f.l("bp", 0);
        give(d, bp);
        if (p != null) {
            Rewards.giveItems(p, f.list("items"));
            if (bp > 0) Msg.send(p, "tower.final_reward", "bp", bp);
        }
        Rewards.runCommands(p, d.name, f.list("commands"));
    }
}
