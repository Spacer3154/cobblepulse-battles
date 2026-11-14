package fr.cobblepulse.battletower.integration;

import eu.pb4.placeholders.api.PlaceholderResult;
import eu.pb4.placeholders.api.Placeholders;
import fr.cobblepulse.battletower.core.TimeFmt;
import fr.cobblepulse.battletower.data.PlayerData;
import fr.cobblepulse.battletower.data.RunState;
import fr.cobblepulse.battletower.data.TrainerSpec;
import fr.cobblepulse.battletower.storage.PlayerStore;
import fr.cobblepulse.battletower.tower.DailyBoss;
import fr.cobblepulse.battletower.tower.TowerManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.Function;

/**
 * Placeholders (Text Placeholder API) pour hologrammes et tablists :
 * %cobblepulse_bt:top_1_name% ... top_10_*, %cobblepulse_bt:player_rank%, player_floor, player_bp, etc.
 */
final class PlaceholderHook {
    private static final String NS = "cobblepulse_bt";

    private PlaceholderHook() {}

    private static void global(String id, java.util.function.Supplier<String> fn) {
        Placeholders.register(ResourceLocation.fromNamespaceAndPath(NS, id), (ctx, arg) -> PlaceholderResult.value(Component.literal(fn.get())));
    }

    private static void player(String id, Function<ServerPlayer, String> fn) {
        Placeholders.register(ResourceLocation.fromNamespaceAndPath(NS, id), (ctx, arg) -> {
            ServerPlayer p = ctx.player();
            if (p == null) return PlaceholderResult.invalid("No player!");
            return PlaceholderResult.value(Component.literal(fn.apply(p)));
        });
    }

    private static PlayerData top(int i) {
        List<PlayerData> top = PlayerStore.top(10);
        return i - 1 < top.size() ? top.get(i - 1) : null;
    }

    static void register() {
        for (int i = 1; i <= 10; i++) {
            int n = i;
            global("top_" + n + "_name", () -> {
                PlayerData d = top(n);
                return d == null ? "-" : d.name;
            });
            global("top_" + n + "_floor", () -> {
                PlayerData d = top(n);
                return d == null ? "0" : String.valueOf(d.maxFloor);
            });
            global("top_" + n + "_completions", () -> {
                PlayerData d = top(n);
                return d == null ? "0" : String.valueOf(d.completions);
            });
            global("top_" + n + "_wins", () -> {
                PlayerData d = top(n);
                return d == null ? "0" : String.valueOf(d.totalWins);
            });
            global("top_" + n + "_time", () -> {
                PlayerData d = top(n);
                return d == null || d.bestTimeMs <= 0 ? "-" : TimeFmt.clock(d.bestTimeMs);
            });
        }
        global("daily_boss_name", () -> {
            TrainerSpec t = DailyBoss.current();
            return t == null ? "-" : t.display();
        });
        global("tower_status", () -> TowerManager.isOpen() ? "Ouverte" : "Fermee");
        global("runs", () -> String.valueOf(TowerManager.runs().size()));
        player("player_rank", p -> {
            int r = PlayerStore.rank(p.getUUID().toString());
            return r > 0 ? String.valueOf(r) : "-";
        });
        player("player_floor", p -> String.valueOf(PlayerStore.get(p).maxFloor));
        player("player_bp", p -> String.valueOf(PlayerStore.get(p).bp));
        player("player_completions", p -> String.valueOf(PlayerStore.get(p).completions));
        player("player_wins", p -> String.valueOf(PlayerStore.get(p).totalWins));
        player("player_best_time", p -> {
            long t = PlayerStore.get(p).bestTimeMs;
            return t <= 0 ? "-" : TimeFmt.clock(t);
        });
        player("player_cooldown", p -> {
            long cd = TowerManager.cooldownLeft(PlayerStore.get(p));
            return cd <= 0 ? "0" : TimeFmt.duration(cd);
        });
        player("player_current_floor", p -> {
            RunState r = TowerManager.run(p.getUUID());
            return r == null || r.daily ? "-" : String.valueOf(r.floor);
        });
        player("player_daily_status", p -> DailyBoss.status(PlayerStore.get(p)));
    }
}
