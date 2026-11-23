package fr.cobblepulse.battles.ranked;

import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.battletower.battle.CobblemonBridge;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Perms;
import fr.cobblepulse.battletower.core.TimeFmt;
import fr.cobblepulse.battletower.tower.TowerManager;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Files d'attente (classee / amicale, simple / double) et matchmaking. */
public final class RankedQueue {
    public static final class Entry {
        public String uuid;
        public String name;
        public String format;
        public boolean ranked;
        public int elo;
        public long joinedAt;
        public String server;
    }

    /** Joueurs de CE serveur en file (affichage, restrictions). */
    private static final Map<String, Entry> LOCAL = new LinkedHashMap<>();
    /** File complete quand le multi-serveur est inactif (sinon : Redis). */
    private static final Map<String, Entry> QUEUE = new LinkedHashMap<>();
    /** Derniere lecture de la file partagee (affichage du nombre de joueurs). */
    private static List<Entry> lastSeen = new ArrayList<>();
    private static boolean matching;

    private RankedQueue() {}

    public static boolean queued(UUID uuid) {
        return LOCAL.containsKey(uuid.toString());
    }

    public static Entry entry(UUID uuid) {
        return LOCAL.get(uuid.toString());
    }

    public static int count(String format, boolean ranked) {
        int n = 0;
        for (Entry e : snapshot()) if (e.format.equals(format) && e.ranked == ranked) n++;
        return n;
    }

    static List<Entry> snapshot() {
        return CrossServer.enabled() ? lastSeen : new ArrayList<>(QUEUE.values());
    }

    public static String permFor(boolean ranked) {
        return ranked ? Ranked.PERM_RANKED : Ranked.PERM_CASUAL;
    }

    /** Entree en file avec toutes les verifications ; message d'erreur envoye au joueur. */
    public static boolean join(ServerPlayer p, String formatArg, boolean ranked) {
        String format = RankedConfig.normalizeFormat(formatArg);
        if (!RankedConfig.enabled()) return fail(p, "ranked.disabled");
        if (format == null) return fail(p, "ranked.unknown_format", "format", formatArg);
        if (!Perms.player(p, permFor(ranked))) return fail(p, "general.no_permission");
        if (ranked && !Seasons.active()) return fail(p, "ranked.off_season");
        if (!ranked && !Seasons.active() && !RankedConfig.seasons.b("hors_saison.amical_autorise", true)) return fail(p, "ranked.off_season");
        FormatRules rules = RankedConfig.rules(format);
        if (rules == null || !rules.enabled) return fail(p, "ranked.format_disabled", "format", format);
        if (queued(p.getUUID())) return fail(p, "ranked.already_queued");
        if (MatchManager.inMatch(p.getUUID())) return fail(p, "ranked.already_in_match");
        if (TowerManager.inRun(p.getUUID())) return fail(p, "ranked.in_tower");
        if (CobblemonBridge.isInBattle(p)) return fail(p, "ranked.in_battle");
        RankedProfile prof = RankedStore.get(p);
        Penalties.decay(prof);
        long now = System.currentTimeMillis();
        if (ranked && prof.banUntil > now) return fail(p, "ranked.banned", "time", TimeFmt.duration(prof.banUntil - now));
        if (prof.declineUntil > now) return fail(p, "ranked.decline_cooldown", "time", TimeFmt.duration(prof.declineUntil - now));
        long sinceMatch = now - prof.lastMatchEnd;
        long needMatch = RankedConfig.general.i("delais.entre_matchs", 10) * 1000L;
        if (sinceMatch < needMatch) return fail(p, "ranked.match_cooldown", "time", TimeFmt.duration(needMatch - sinceMatch));
        long sinceJoin = now - prof.lastQueueJoin;
        long needJoin = RankedConfig.general.i("delais.rejoindre_file", 3) * 1000L;
        if (sinceJoin < needJoin) return fail(p, "ranked.join_cooldown", "time", TimeFmt.duration(needJoin - sinceJoin));
        List<Pokemon> party = CobblemonBridge.partyList(p);
        String why = TeamRules.canQueue(party, rules);
        if (why != null) return fail(p, "ranked.team_invalid", "reason", why);
        Entry e = new Entry();
        e.uuid = p.getUUID().toString();
        e.name = p.getGameProfile().getName();
        e.format = format;
        e.ranked = ranked;
        e.elo = prof.stats(format).elo;
        e.joinedAt = now;
        e.server = CrossServer.serverId();
        prof.lastQueueJoin = now;
        RankedStore.mark(prof);
        add(e);
        Msg.send(p, ranked ? "ranked.queued_ranked" : "ranked.queued_casual", "format", rules.name, "n", count(format, ranked));
        return true;
    }

    static void add(Entry e) {
        LOCAL.put(e.uuid, e);
        if (CrossServer.enabled()) CrossServer.queuePut(e);
        else QUEUE.put(e.uuid, e);
    }

    /** Remet un joueur en file en tete (adversaire qui a refuse le match). */
    static void requeue(Entry e, ServerPlayer p) {
        if (p == null) return;
        add(e);
        Msg.send(p, "ranked.requeued");
    }

    public static boolean leave(UUID uuid) {
        String id = uuid.toString();
        boolean was = LOCAL.remove(id) != null;
        QUEUE.remove(id);
        CrossServer.queueRemove(id);
        return was;
    }

    private static boolean fail(ServerPlayer p, String key, Object... kv) {
        Msg.send(p, key, kv);
        return false;
    }

    /** Tick du matchmaking (chaque seconde) ; uniquement sur le serveur de matchmaking. */
    static void tick() {
        // joueurs de ce serveur qui ne sont plus en file partagee (appaires ailleurs) : maj de l'affichage
        if (CrossServer.enabled()) {
            if (matching) return;
            matching = true;
            CrossServer.queueAll(all -> {
                matching = false;
                lastSeen = all;
                Set<String> present = new HashSet<>();
                for (Entry e : all) present.add(e.uuid);
                LOCAL.keySet().removeIf(u -> !present.contains(u) && !recentlyAdded(u));
                if (CrossServer.isMatchmaker()) match(all);
            });
        } else {
            match(new ArrayList<>(QUEUE.values()));
        }
    }

    private static boolean recentlyAdded(String uuid) {
        Entry e = LOCAL.get(uuid);
        return e != null && System.currentTimeMillis() - e.joinedAt < 3000;
    }

    private static void match(List<Entry> all) {
        if (all.size() < 2) return;
        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        for (Entry e : all) groups.computeIfAbsent(e.format + "|" + e.ranked, k -> new ArrayList<>()).add(e);
        long now = System.currentTimeMillis();
        for (List<Entry> group : groups.values()) {
            if (group.size() < 2) continue;
            group.sort(Comparator.comparingLong(e -> e.joinedAt));
            Set<String> used = new HashSet<>();
            FormatRules rules = RankedConfig.rules(group.get(0).format);
            if (rules == null) continue;
            for (Entry a : group) {
                if (used.contains(a.uuid)) continue;
                Entry best = null;
                int bestDiff = Integer.MAX_VALUE;
                for (Entry b : group) {
                    if (b == a || used.contains(b.uuid)) continue;
                    if (avoid(a, b, group.size(), now) || avoid(b, a, group.size(), now)) continue;
                    int diff = Math.abs(a.elo - b.elo);
                    if (a.ranked) {
                        int range = Math.min(rules.rangeAfter((now - a.joinedAt) / 1000), rules.rangeAfter((now - b.joinedAt) / 1000));
                        if (diff > rules.immediateRange && diff > range) continue;
                    }
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        best = b;
                    }
                }
                if (best == null) continue;
                used.add(a.uuid);
                used.add(best.uuid);
                remove(a);
                remove(best);
                MatchManager.create(a, best);
            }
        }
    }

    private static void remove(Entry e) {
        LOCAL.remove(e.uuid);
        QUEUE.remove(e.uuid);
        CrossServer.queueRemove(e.uuid);
    }

    /** Evite un adversaire recent (sauf longue attente ou petite file). */
    private static boolean avoid(Entry a, Entry b, int groupSize, long now) {
        var t = RankedConfig.general;
        if (!t.b("adversaires_recents.actif", true)) return false;
        if (groupSize < t.i("adversaires_recents.file_minimum", 4)) return false;
        if ((now - a.joinedAt) / 1000 >= t.i("adversaires_recents.relache_apres_secondes", 120)) return false;
        RankedProfile p = RankedStore.get(UUID.fromString(a.uuid));
        if (p == null) return false;
        long exp = t.i("adversaires_recents.expiration_secondes", 300) * 1000L;
        int n = t.i("adversaires_recents.nombre", 3);
        int seen = 0;
        for (int i = p.recent.size() - 1; i >= 0 && seen < n; i--, seen++) {
            RankedProfile.Recent r = p.recent.get(i);
            if (now - r.at > exp) continue;
            if (r.uuid.equals(b.uuid)) return true;
        }
        return false;
    }

    public static List<Entry> localEntries() {
        return new ArrayList<>(LOCAL.values());
    }

    public static void clear() {
        LOCAL.clear();
        QUEUE.clear();
        lastSeen = new ArrayList<>();
        matching = false;
    }
}
