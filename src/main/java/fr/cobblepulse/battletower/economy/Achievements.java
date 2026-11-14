package fr.cobblepulse.battletower.economy;

import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.config.TomlFile;
import fr.cobblepulse.battletower.core.Fx;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Rewards;
import fr.cobblepulse.battletower.core.TimeFmt;
import fr.cobblepulse.battletower.data.PlayerData;
import fr.cobblepulse.battletower.storage.PlayerStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Quetes & Succes permanents (achievements.toml). */
public final class Achievements {
    public record Def(String id, String name, String description, String icon, String type, long target, long bp,
                      List<String> items, List<String> commands) {}

    private Achievements() {}

    public static List<Def> all() {
        List<Def> out = new ArrayList<>();
        for (TomlFile t : Cfg.achievements.tables("achievements")) {
            String id = t.str("id", "").trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            out.add(new Def(id, t.str("name", id), t.str("description", ""), t.str("icon", "minecraft:paper"),
                    t.str("type", "reach_floor").toLowerCase(Locale.ROOT), t.l("target", 1), t.l("bp", 0), t.list("items"), t.list("commands")));
        }
        return out;
    }

    /** Valeur actuelle de la progression d'un joueur pour un type de succes. */
    public static long progress(PlayerData d, Def def) {
        return switch (def.type()) {
            case "reach_floor" -> d.maxFloor;
            case "completions" -> d.completions;
            case "total_wins" -> d.totalWins;
            case "no_ko_streak" -> d.bestNoKoStreak;
            case "daily_boss_distinct" -> d.dailyBossBeaten.size();
            case "daily_boss_wins" -> d.dailyBossWins;
            case "monotype_floors" -> d.monotypeFloors;
            case "bp_earned" -> d.bpEarned;
            case "best_time_under" -> d.bestTimeMs > 0 && d.bestTimeMs <= def.target() * TimeFmt.MINUTE ? def.target() : 0;
            default -> 0;
        };
    }

    public static boolean done(PlayerData d, Def def) {
        return progress(d, def) >= def.target();
    }

    /** Debloque les succes atteints et distribue leurs recompenses. */
    public static void check(PlayerData d, ServerPlayer p) {
        boolean changed = false;
        for (Def def : all()) {
            if (d.achievements.containsKey(def.id()) || !done(d, def)) continue;
            d.achievements.put(def.id(), TimeFmt.now());
            changed = true;
            if (def.bp() > 0) BattlePoints.give(d, def.bp());
            if (p != null) {
                Rewards.giveItems(p, def.items());
                Msg.send(p, "achievement.unlocked", "name", def.name(), "description", def.description(), "bp", def.bp());
                Fx.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
            }
            Rewards.runCommands(p, d.name, def.commands());
        }
        if (changed) PlayerStore.save(d);
    }
}
