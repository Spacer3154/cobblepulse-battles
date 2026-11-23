package fr.cobblepulse.battles.ranked;

import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.core.Json;
import fr.cobblepulse.battletower.storage.DataService;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Profils Ranked en memoire, sauvegardes en differe sur le stockage (JSON, SQLite ou MySQL). */
public final class RankedStore {
    private static final Map<String, RankedProfile> PROFILES = new ConcurrentHashMap<>();
    private static final Map<String, RankedProfile> DIRTY = new LinkedHashMap<>();
    private static boolean hooked;

    private RankedStore() {}

    public static void load() {
        PROFILES.clear();
        if (!hooked) {
            hooked = true;
            DataService.FLUSH_HOOKS.add(RankedStore::flush);
        }
        List<String> rows = DataService.blocking(s -> s.loadRankedProfiles());
        if (rows == null) return;
        for (String json : rows) {
            RankedProfile p = parse(json);
            if (p != null) PROFILES.put(p.uuid, p);
        }
        Battletower.LOG.info("[Battles] Ranked : {} profils charges", PROFILES.size());
    }

    static RankedProfile parse(String json) {
        try {
            RankedProfile p = Json.COMPACT.fromJson(json, RankedProfile.class);
            if (p == null || p.uuid == null) return null;
            p.normalize();
            return p;
        } catch (Exception e) {
            Battletower.LOG.warn("[Battles] Profil Ranked illisible : {}", e.toString());
            return null;
        }
    }

    public static RankedProfile get(UUID uuid) {
        return PROFILES.get(uuid.toString());
    }

    public static RankedProfile get(ServerPlayer p) {
        RankedProfile r = PROFILES.computeIfAbsent(p.getUUID().toString(), u -> new RankedProfile(u, p.getGameProfile().getName()));
        if (!p.getGameProfile().getName().equals(r.name)) {
            r.name = p.getGameProfile().getName();
            mark(r);
        }
        Seasons.ensureSeason(r);
        return r;
    }

    public static RankedProfile getOrCreate(UUID uuid, String name) {
        RankedProfile r = PROFILES.computeIfAbsent(uuid.toString(), u -> new RankedProfile(u, name == null ? "?" : name));
        Seasons.ensureSeason(r);
        return r;
    }

    public static RankedProfile byName(String name) {
        for (RankedProfile p : PROFILES.values()) if (p.name.equalsIgnoreCase(name)) return p;
        return null;
    }

    public static List<RankedProfile> all() {
        return new ArrayList<>(PROFILES.values());
    }

    public static void mark(RankedProfile p) {
        p.updated = System.currentTimeMillis();
        synchronized (DIRTY) {
            DIRTY.put(p.uuid, p);
        }
    }

    /** Ecriture immediate (fin de match en multi-serveur : les autres serveurs relisent juste apres). */
    public static void saveNow(RankedProfile p, Runnable after) {
        p.updated = System.currentTimeMillis();
        synchronized (DIRTY) {
            DIRTY.remove(p.uuid);
        }
        String json = Json.COMPACT.toJson(p);
        String uuid = p.uuid;
        String name = p.name;
        long updated = p.updated;
        DataService.async(s -> {
            s.saveRankedProfile(uuid, name, updated, json);
            return null;
        }).whenComplete((v, t) -> {
            if (after != null) DataService.main(after);
        });
    }

    private static void flush() {
        Map<String, String[]> snaps = new LinkedHashMap<>();
        synchronized (DIRTY) {
            for (RankedProfile p : DIRTY.values()) snaps.put(p.uuid, new String[]{p.name, Json.COMPACT.toJson(p), String.valueOf(p.updated)});
            DIRTY.clear();
        }
        if (snaps.isEmpty()) return;
        DataService.async(s -> {
            for (Map.Entry<String, String[]> e : snaps.entrySet()) {
                s.saveRankedProfile(e.getKey(), e.getValue()[0], Long.parseLong(e.getValue()[2]), e.getValue()[1]);
            }
            return null;
        });
    }

    /** Relit un profil depuis le stockage (message d'un autre serveur). */
    public static void reload(String uuid) {
        DataService.async(s -> s.loadRankedProfile(uuid)).thenAccept(json -> DataService.main(() -> {
            if (json == null) return;
            RankedProfile p = parse(json);
            if (p == null) return;
            synchronized (DIRTY) {
                DIRTY.remove(p.uuid);
            }
            PROFILES.put(p.uuid, p);
        }));
    }

    public static void reloadAll() {
        DataService.async(s -> s.loadRankedProfiles()).thenAccept(rows -> DataService.main(() -> {
            if (rows == null) return;
            for (String json : rows) {
                RankedProfile p = parse(json);
                if (p != null) PROFILES.put(p.uuid, p);
            }
        }));
    }

    public static void logMatch(Object match) {
        String json = Json.COMPACT.toJson(match);
        DataService.async(s -> {
            s.logRankedMatch(json);
            return null;
        });
    }

    /** Classement d'un format : Elo decroissant, au moins une partie classee dans la saison. */
    public static List<RankedProfile> top(String format, int n) {
        String f = format.toLowerCase(Locale.ROOT);
        return PROFILES.values().stream()
                .filter(p -> p.formats.containsKey(f) && p.formats.get(f).games() > 0 && Seasons.currentId().equals(p.season))
                .sorted(Comparator.comparingInt((RankedProfile p) -> -p.formats.get(f).elo)
                        .thenComparingInt(p -> -p.formats.get(f).wins)
                        .thenComparing(p -> p.name.toLowerCase(Locale.ROOT)))
                .limit(n)
                .toList();
    }

    /** Place au classement (1 = premier), 0 si non classe. */
    public static int place(String format, String uuid) {
        List<RankedProfile> t = top(format, Integer.MAX_VALUE);
        for (int i = 0; i < t.size(); i++) if (t.get(i).uuid.equals(uuid)) return i + 1;
        return 0;
    }

    public static void clear() {
        PROFILES.clear();
        synchronized (DIRTY) {
            DIRTY.clear();
        }
    }
}
