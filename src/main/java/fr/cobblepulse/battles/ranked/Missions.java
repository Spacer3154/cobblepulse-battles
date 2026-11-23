package fr.cobblepulse.battles.ranked;

import fr.cobblepulse.battletower.config.TomlFile;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Players;
import fr.cobblepulse.battletower.core.Rewards;
import net.minecraft.server.level.ServerPlayer;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Missions quotidiennes et hebdomadaires de la Ranked, tirees par joueur a chaque periode. */
public final class Missions {
    public record Def(String id, String title, String description, String trigger, String type, String format, int required,
                      int chance, String icon, List<String> commands, boolean weekly) {}

    private Missions() {}

    static TomlFile t() {
        return RankedConfig.missions;
    }

    public static boolean enabled() {
        return t() != null && t().b("actif", true);
    }

    public static List<Def> defs(boolean weekly) {
        List<Def> out = new ArrayList<>();
        for (TomlFile d : t().tables(weekly ? "hebdomadaire" : "quotidienne")) {
            out.add(new Def(d.str("id", "mission"), d.str("titre", "Mission"), d.str("description", ""),
                    d.str("declencheur", "victoire").toLowerCase(Locale.ROOT), d.str("type", "tous").toLowerCase(Locale.ROOT),
                    d.str("format", "").toLowerCase(Locale.ROOT), Math.max(1, d.i("requis", 1)), Math.max(0, d.i("chance", 100)),
                    d.str("icone", "minecraft:paper"), d.list("commandes"), weekly));
        }
        return out;
    }

    public static Def def(String id, boolean weekly) {
        for (Def d : defs(weekly)) if (d.id().equals(id)) return d;
        return null;
    }

    static LocalTime resetTime() {
        try {
            return LocalTime.parse(t().str("heure_reset", "00:00"));
        } catch (Exception e) {
            return LocalTime.MIDNIGHT;
        }
    }

    /** Identifiant de la periode quotidienne en cours (date du dernier reset). */
    public static String dailyPeriod() {
        ZonedDateTime now = ZonedDateTime.now(Seasons.zone());
        LocalDate d = now.toLocalDate();
        if (now.toLocalTime().isBefore(resetTime())) d = d.minusDays(1);
        return d.toString();
    }

    public static String weeklyPeriod() {
        ZonedDateTime now = ZonedDateTime.now(Seasons.zone());
        DayOfWeek day;
        try {
            day = DayOfWeek.valueOf(t().str("jour_reset_hebdo", "MONDAY").toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            day = DayOfWeek.MONDAY;
        }
        LocalDate d = now.toLocalDate().with(TemporalAdjusters.previousOrSame(day));
        if (d.equals(now.toLocalDate()) && now.toLocalTime().isBefore(resetTime())) d = d.minusWeeks(1);
        return d.toString();
    }

    /** Prochain reset (ms) de la periode quotidienne. */
    public static long nextDailyResetMs() {
        ZonedDateTime now = ZonedDateTime.now(Seasons.zone());
        ZonedDateTime next = LocalDate.parse(dailyPeriod()).plusDays(1).atTime(resetTime()).atZone(Seasons.zone());
        return Math.max(0, next.toInstant().toEpochMilli() - now.toInstant().toEpochMilli());
    }

    /** Tire les missions des nouvelles periodes. */
    public static boolean refresh(RankedProfile p) {
        if (!enabled()) return false;
        boolean changed = false;
        String dp = dailyPeriod();
        if (!dp.equals(p.dailyPeriod)) {
            p.dailyPeriod = dp;
            p.daily = draw(defs(false), t().i("nombre_quotidiennes", 3));
            changed = true;
        }
        String wp = weeklyPeriod();
        if (!wp.equals(p.weeklyPeriod)) {
            p.weeklyPeriod = wp;
            p.weekly = draw(defs(true), t().i("nombre_hebdomadaires", 2));
            changed = true;
        }
        if (changed) RankedStore.mark(p);
        return changed;
    }

    static List<RankedProfile.MissionState> draw(List<Def> pool, int n) {
        List<Def> left = new ArrayList<>(pool);
        List<RankedProfile.MissionState> out = new ArrayList<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        while (out.size() < n && !left.isEmpty()) {
            int total = 0;
            for (Def d : left) total += Math.max(1, d.chance());
            int r = rnd.nextInt(total);
            Def pick = left.get(left.size() - 1);
            for (Def d : left) {
                r -= Math.max(1, d.chance());
                if (r < 0) {
                    pick = d;
                    break;
                }
            }
            left.remove(pick);
            out.add(new RankedProfile.MissionState(pick.id()));
        }
        return out;
    }

    /** Progression apres un match. */
    public static void onMatch(RankedProfile p, boolean ranked, String format, boolean won, int streak, int kos) {
        if (!enabled()) return;
        refresh(p);
        progress(p, p.daily, false, ranked, format, won, streak, kos);
        progress(p, p.weekly, true, ranked, format, won, streak, kos);
    }

    private static void progress(RankedProfile p, List<RankedProfile.MissionState> list, boolean weekly, boolean ranked, String format,
                                 boolean won, int streak, int kos) {
        for (RankedProfile.MissionState s : list) {
            if (s.done) continue;
            Def d = def(s.id, weekly);
            if (d == null) continue;
            if ("classe".equals(d.type()) && !ranked) continue;
            if ("amical".equals(d.type()) && ranked) continue;
            if (!d.format().isEmpty() && !d.format().equals(format)) continue;
            switch (d.trigger()) {
                case "victoire" -> {
                    if (won) s.progress++;
                }
                case "partie" -> s.progress++;
                case "serie" -> s.progress = Math.max(s.progress, won ? streak : 0);
                case "ko" -> s.progress += kos;
                default -> {
                }
            }
            if (s.progress >= d.required()) {
                s.progress = d.required();
                s.done = true;
                ServerPlayer sp = Players.get(UUID.fromString(p.uuid));
                if (sp != null) Msg.send(sp, "ranked.mission_done", "mission", d.title());
                if (t().b("annoncer", false)) Msg.broadcast("ranked.mission_done_broadcast", "player", p.name, "mission", d.title());
                if (t().b("reclamation_auto", false)) give(p, s, d);
            }
        }
        RankedStore.mark(p);
    }

    /** Reclamation depuis le menu. */
    public static boolean claim(ServerPlayer sp, String id, boolean weekly) {
        RankedProfile p = RankedStore.get(sp);
        refresh(p);
        for (RankedProfile.MissionState s : weekly ? p.weekly : p.daily) {
            if (!s.id.equals(id)) continue;
            if (!s.done || s.claimed) return false;
            Def d = def(id, weekly);
            if (d == null) return false;
            give(p, s, d);
            Msg.send(sp, "ranked.mission_claimed", "mission", d.title());
            return true;
        }
        return false;
    }

    private static void give(RankedProfile p, RankedProfile.MissionState s, Def d) {
        s.claimed = true;
        RankedStore.mark(p);
        List<String> cmds = new ArrayList<>();
        for (String c : d.commands()) cmds.add(c.replace("%uuid%", p.uuid));
        ServerPlayer sp = Players.get(UUID.fromString(p.uuid));
        if (sp != null) Rewards.runCommands(sp, p.name, cmds);
        else RankedRewards.give(p, cmds, null);
    }
}
