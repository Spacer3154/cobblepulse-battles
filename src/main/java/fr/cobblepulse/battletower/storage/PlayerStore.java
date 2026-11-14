package fr.cobblepulse.battletower.storage;

import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.core.Scheduler;
import fr.cobblepulse.battletower.data.PlayerData;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tous les dossiers joueurs sont gardes en memoire (quelques Ko chacun) : le classement, les placeholders et les
 * actions du staff sur des joueurs hors-ligne sont instantanes. Les ecritures restent asynchrones.
 */
public final class PlayerStore {
    private static final Map<String, PlayerData> ALL = new HashMap<>();
    private static final Map<String, String> BY_NAME = new HashMap<>();
    private static List<PlayerData> ranking = new ArrayList<>();
    private static final Map<String, Integer> RANKS = new HashMap<>();
    private static boolean rankDirty = true;

    /** Ordre du classement : etage max, completions, victoires, meilleur temps (le plus court). */
    public static final Comparator<PlayerData> ORDER = Comparator
            .comparingInt((PlayerData d) -> -d.maxFloor)
            .thenComparingInt(d -> -d.completions)
            .thenComparingInt(d -> -d.totalWins)
            .thenComparingLong(d -> d.bestTimeMs <= 0 ? Long.MAX_VALUE : d.bestTimeMs)
            .thenComparing(d -> d.name == null ? "" : d.name.toLowerCase(Locale.ROOT));

    private PlayerStore() {}

    public static void load() {
        ALL.clear();
        BY_NAME.clear();
        List<PlayerData> list = DataService.blocking(Storage::loadAllPlayers);
        if (list != null) {
            for (PlayerData d : list) {
                ALL.put(d.uuid, d);
                if (d.name != null && !d.name.isEmpty()) BY_NAME.put(d.name.toLowerCase(Locale.ROOT), d.uuid);
            }
        }
        rankDirty = true;
        Battletower.LOG.info("[Battletower] {} dossiers joueurs charges", ALL.size());
        Scheduler.repeat(20, 20 * 30, PlayerStore::rebuildRanking);
    }

    public static PlayerData get(UUID uuid) {
        return uuid == null ? null : ALL.get(uuid.toString());
    }

    public static PlayerData get(ServerPlayer p) {
        String id = p.getUUID().toString();
        PlayerData d = ALL.get(id);
        String name = p.getGameProfile().getName();
        if (d == null) {
            d = new PlayerData(id, name);
            ALL.put(id, d);
            rankDirty = true;
            DataService.markPlayer(d);
        } else if (!name.equals(d.name)) {
            d.name = name;
            DataService.markPlayer(d);
        }
        BY_NAME.put(name.toLowerCase(Locale.ROOT), id);
        return d;
    }

    public static PlayerData getOrCreate(UUID uuid, String name) {
        PlayerData d = ALL.get(uuid.toString());
        if (d == null) {
            d = new PlayerData(uuid.toString(), name);
            ALL.put(d.uuid, d);
            if (name != null) BY_NAME.put(name.toLowerCase(Locale.ROOT), d.uuid);
            rankDirty = true;
        }
        return d;
    }

    public static PlayerData byName(String name) {
        if (name == null) return null;
        String id = BY_NAME.get(name.toLowerCase(Locale.ROOT));
        return id == null ? null : ALL.get(id);
    }

    public static void save(PlayerData d) {
        rankDirty = true;
        DataService.markPlayer(d);
    }

    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (PlayerData d : ALL.values()) if (d.name != null && !d.name.isEmpty()) out.add(d.name);
        return out;
    }

    public static void rebuildRanking() {
        if (!rankDirty) return;
        rankDirty = false;
        List<PlayerData> list = new ArrayList<>();
        for (PlayerData d : ALL.values()) if (d.maxFloor > 0 || d.totalWins > 0) list.add(d);
        list.sort(ORDER);
        ranking = list;
        RANKS.clear();
        for (int i = 0; i < list.size(); i++) RANKS.put(list.get(i).uuid, i + 1);
    }

    public static List<PlayerData> top(int n) {
        rebuildRanking();
        return new ArrayList<>(ranking.subList(0, Math.min(n, ranking.size())));
    }

    /** Rang (1 = premier), 0 si non classe. */
    public static int rank(String uuid) {
        rebuildRanking();
        Integer r = RANKS.get(uuid);
        return r == null ? 0 : r;
    }

    public static int rankedCount() {
        rebuildRanking();
        return ranking.size();
    }

    public static void clear() {
        ALL.clear();
        BY_NAME.clear();
        ranking = new ArrayList<>();
        RANKS.clear();
    }
}
