package fr.cobblepulse.battles.ranked;

import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.config.TomlFile;
import fr.cobblepulse.battletower.core.Json;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Scheduler;
import fr.cobblepulse.battletower.storage.DataService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Saisons de la Ranked : saison active d'apres saisons.toml, changement de saison (archive, recompenses de fin de
 * saison, reset partiel ou total de l'Elo), annonces periodiques.
 */
public final class Seasons {
    public record Season(String id, String name, ZonedDateTime start, ZonedDateTime end, String preset) {}

    private static Season current;
    /** Derniere saison traitee (persistee) : detecte le changement meme apres un redemarrage. */
    private static String lastProcessed = "";
    private static Scheduler.Task task;
    private static long lastAnnounce;

    private Seasons() {}

    public static ZoneId zone() {
        String tz = Cfg.general == null ? "" : Cfg.general.str("timezone", "").trim();
        try {
            if (!tz.isEmpty()) return ZoneId.of(tz);
        } catch (Exception ignored) {
        }
        return ZoneId.systemDefault();
    }

    static ZonedDateTime parse(String s, boolean end) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        try {
            if (v.length() <= 10) {
                LocalDate d = LocalDate.parse(v);
                return end ? d.atTime(23, 59, 59).atZone(zone()) : d.atStartOfDay(zone());
            }
            return LocalDateTime.parse(v.replace(' ', 'T')).atZone(zone());
        } catch (Exception e) {
            Battletower.LOG.warn("[Battles] Date de saison invalide : {}", s);
            return null;
        }
    }

    public static List<Season> schedule() {
        List<Season> out = new ArrayList<>();
        for (TomlFile t : RankedConfig.seasons.tables("saison")) {
            String name = t.str("nom", "Saison");
            ZonedDateTime a = parse(t.str("debut", ""), false);
            ZonedDateTime b = parse(t.str("fin", ""), true);
            if (a == null || b == null) continue;
            out.add(new Season(FormatRules.norm(name), name, a, b, t.str("preset", "default")));
        }
        return out;
    }

    public static Season find(ZonedDateTime now) {
        for (Season s : schedule()) if (!now.isBefore(s.start()) && now.isBefore(s.end())) return s;
        return null;
    }

    public static Season current() {
        return current;
    }

    /** Identifiant de la saison en cours ("hors_saison" sinon). */
    public static String currentId() {
        return current == null ? "hors_saison" : current.id();
    }

    public static String presetId() {
        return current == null ? "default" : current.preset();
    }

    public static boolean active() {
        return current != null;
    }

    public static String endText() {
        if (current == null) return "-";
        return current.end().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    public static long remainingMs() {
        return current == null ? 0 : Math.max(0, current.end().toInstant().toEpochMilli() - System.currentTimeMillis());
    }

    public static void init() {
        String saved = DataService.blocking(s -> s.loadKv("ranked_season"));
        lastProcessed = saved == null ? "" : Json.COMPACT.fromJson(saved, String.class);
        current = find(ZonedDateTime.now(zone()));
        if (lastProcessed == null || lastProcessed.isEmpty()) {
            lastProcessed = currentId();
            persist();
        }
        if (task != null) task.cancel();
        long every = Math.max(1, RankedConfig.seasons.i("verification_minutes", 1)) * 60L * 20L;
        task = Scheduler.repeat(200, every, Seasons::check);
        Battletower.LOG.info("[Battles] Ranked : {} (preset {})", current == null ? "hors saison" : current.name(), presetId());
    }

    private static void persist() {
        String v = lastProcessed;
        DataService.markKv("ranked_season", () -> v);
    }

    /** Verification periodique : changement de saison + annonces. */
    public static void check() {
        Season now = find(ZonedDateTime.now(zone()));
        current = now;
        String id = currentId();
        if (!id.equals(lastProcessed) && CrossServer.isMatchmaker()) {
            rotate(lastProcessed, id);
        } else if (!id.equals(lastProcessed)) {
            // un autre serveur fait le changement ; ici on relit simplement les profils
            lastProcessed = id;
            RankedStore.reloadAll();
        }
        announce();
    }

    /** Force le passage a la saison configuree (commande admin) ou relance le traitement de fin de saison. */
    public static void forceRotate() {
        current = find(ZonedDateTime.now(zone()));
        rotate(lastProcessed, currentId());
    }

    /** Auto-tests : traitement de fin d'une saison fictive vers la saison en cours. */
    public static void testRotate(String oldId) {
        current = find(ZonedDateTime.now(zone()));
        rotate(oldId, currentId());
    }

    private static void rotate(String oldId, String newId) {
        Battletower.LOG.info("[Battles] Ranked : changement de saison {} -> {}", oldId, newId);
        // 1. archive + recompenses de fin de saison de l'ancienne saison
        if (oldId != null && !oldId.isEmpty() && !"hors_saison".equals(oldId)) {
            Map<String, Object> archive = new LinkedHashMap<>();
            int top = Math.max(1, RankedConfig.seasons.i("archive.top", 100));
            for (String f : RankedConfig.FORMATS) {
                List<Map<String, Object>> rows = new ArrayList<>();
                List<RankedProfile> ranking = topOf(oldId, f, top);
                for (int i = 0; i < ranking.size(); i++) {
                    RankedProfile p = ranking.get(i);
                    RankedProfile.FormatStats st = p.formats.get(f);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("place", i + 1);
                    row.put("uuid", p.uuid);
                    row.put("nom", p.name);
                    row.put("elo", st.elo);
                    row.put("victoires", st.wins);
                    row.put("defaites", st.losses);
                    rows.add(row);
                    RankedRewards.seasonReward(p, f, i + 1, oldId);
                }
                archive.put(f, rows);
            }
            String key = "ranked_archive_" + oldId;
            DataService.markKv(key, () -> archive);
            Discord.seasonEnd(oldId, archive);
            Msg.broadcast("ranked.season_end", "season", oldId);
        }
        // 2. reset des profils
        for (RankedProfile p : RankedStore.all()) {
            if (!newId.equals(p.season)) {
                resetProfile(p, newId);
                RankedStore.mark(p);
            }
        }
        lastProcessed = newId;
        persist();
        DataService.flushDirty();
        CrossServer.publish("season", Map.of("id", newId));
        if (current != null) Msg.broadcast("ranked.season_start", "season", current.name(), "end", endText());
    }

    private static List<RankedProfile> topOf(String seasonId, String format, int n) {
        return RankedStore.all().stream()
                .filter(p -> seasonId.equals(p.season) && p.formats.containsKey(format) && p.formats.get(format).games() > 0)
                .sorted((a, b) -> Integer.compare(b.formats.get(format).elo, a.formats.get(format).elo))
                .limit(n).toList();
    }

    /** Applique les regles de fin de saison a un profil d'une saison precedente. */
    static void resetProfile(RankedProfile p, String newId) {
        TomlFile t = RankedConfig.seasons;
        int start = RankedConfig.startElo();
        for (RankedProfile.FormatStats s : p.formats.values()) {
            if (t.b("fin_de_saison.reset_total", false)) s.elo = start;
            else if (t.b("fin_de_saison.reset_partiel", true)) {
                double f = t.d("fin_de_saison.facteur", 0.5);
                s.elo = Math.max(RankedConfig.floorElo(), (int) Math.round(start + (s.elo - start) * f));
            }
            s.peak = s.elo;
            if (t.b("fin_de_saison.reset_victoires", true)) {
                s.wins = 0;
                s.losses = 0;
                s.draws = 0;
                s.casualWins = 0;
                s.casualLosses = 0;
            }
            if (t.b("fin_de_saison.reset_series", true)) {
                s.streak = 0;
                s.bestStreak = 0;
            }
            if (t.b("fin_de_saison.reset_recompenses_rang", true)) s.rankRewards.clear();
            s.gainToday = 0;
        }
        p.season = newId;
    }

    /** Profil d'une saison passee (joueur absent lors du changement) : reset a la volee. */
    public static void ensureSeason(RankedProfile p) {
        String id = currentId();
        if (p.season == null || p.season.isEmpty()) {
            p.season = id;
            return;
        }
        if (!id.equals(p.season) && id.equals(lastProcessed)) {
            resetProfile(p, id);
            RankedStore.mark(p);
        }
    }

    private static void announce() {
        if (!RankedConfig.seasons.b("annonces.actif", true) || current == null) return;
        long every = Math.max(1, RankedConfig.seasons.i("annonces.intervalle_minutes", 30)) * 60_000L;
        long now = System.currentTimeMillis();
        if (lastAnnounce == 0) {
            lastAnnounce = now;
            return;
        }
        if (now - lastAnnounce < every) return;
        lastAnnounce = now;
        long days = remainingMs() / 86_400_000L;
        Msg.broadcast("ranked.season_announce", "season", current.name(), "days", days, "end", endText());
    }

    public static void stop() {
        if (task != null) task.cancel();
        task = null;
        current = null;
    }

    public static String describe() {
        if (current == null) return "hors saison";
        return current.name() + " (" + RankedConfig.presetName(current.preset()).toLowerCase(Locale.ROOT) + ")";
    }
}
