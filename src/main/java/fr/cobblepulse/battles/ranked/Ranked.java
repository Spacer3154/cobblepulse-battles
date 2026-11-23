package fr.cobblepulse.battles.ranked;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.core.Scheduler;
import kotlin.Unit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Point d'entree de la Ranked de CobblePulse-Battles. */
public final class Ranked {
    public static final String PERM_RANKED = "cobblepulse.battles.ranked.use";
    public static final String PERM_CASUAL = "cobblepulse.battles.ranked.casual";
    public static final String PERM_ADMIN = "cobblepulse.battles.ranked.admin";
    private static Scheduler.Task task;

    private Ranked() {}

    /** Au chargement du mod (une seule fois). */
    public static void init() {
        CrossServer.registerPayloads();
        Restrictions.register();
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, e -> {
            safe(() -> MatchManager.onVictory(e.getBattle(), e.getWinners()));
            return Unit.INSTANCE;
        });
        CobblemonEvents.BATTLE_FAINTED.subscribe(Priority.NORMAL, e -> {
            safe(() -> MatchManager.onFainted(e.getBattle(), e.getKilled()));
            return Unit.INSTANCE;
        });
        CrossServer.on("match_trouve", MatchManager::onRemoteMatch);
        CrossServer.on("profil", o -> RankedStore.reload(o.get("uuid").getAsString()));
        CrossServer.on("season", o -> RankedStore.reloadAll());
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            Battletower.LOG.error("[Battles] Erreur d'evenement (Ranked)", t);
        }
    }

    public static void onStarted(MinecraftServer server) {
        RankedConfig.load();
        Arenas.load();
        RankedStore.load();
        CrossServer.start();
        Seasons.init();
        if (task != null) task.cancel();
        task = Scheduler.repeat(20, 20, () -> {
            safe(RankedQueue::tick);
            safe(MatchManager::tick);
        });
        Battletower.LOG.info("[Battles] Ranked prete ({} rangs, {} arenes, multi-serveur {})", RankedConfig.RANKS.size(), Arenas.all().size(),
                CrossServer.enabled() ? "actif" : "inactif");
    }

    public static void onStopping() {
        if (task != null) task.cancel();
        task = null;
        for (RankedQueue.Entry e : RankedQueue.localEntries()) CrossServer.queueRemove(e.uuid);
        MatchManager.stopAll();
        Seasons.stop();
        CrossServer.stop();
        RankedQueue.clear();
        Arenas.clearBusy();
    }

    public static void afterStop() {
        RankedStore.clear();
    }

    public static void reload() {
        RankedConfig.load();
        Arenas.load();
        CrossServer.start();
        Seasons.check();
    }

    public static void onJoin(ServerPlayer p) {
        if (RankedConfig.general == null) return;
        RankedProfile prof = RankedStore.get(p);
        Penalties.decay(prof);
        Missions.refresh(prof);
        RankedRewards.deliverPending(p);
        MatchManager.onJoin(p);
    }

    public static void onQuit(ServerPlayer p) {
        if (RankedConfig.general == null) return;
        MatchManager.onQuit(p);
    }

    /** Joueur en file ou en match (la Tour et les dresseurs le refusent). */
    public static boolean busy(UUID uuid) {
        return RankedQueue.queued(uuid) || MatchManager.inMatch(uuid);
    }
}
