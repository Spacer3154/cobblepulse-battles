package fr.cobblepulse.battletower.storage;

import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.core.Json;
import fr.cobblepulse.battletower.core.Scheduler;
import fr.cobblepulse.battletower.data.PlayerData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Persistance asynchrone (meme architecture que CobblePulse) : toutes les E/S s'executent sur un thread dedie
 * pour ne jamais toucher au TPS. Les objets vivants sont copies sur le thread principal avant ecriture.
 */
public final class DataService {
    @FunctionalInterface
    public interface IoCall<T> {
        T call(Storage s) throws Exception;
    }

    private static ExecutorService io;
    private static Storage storage;
    private static final Map<String, PlayerData> DIRTY_PLAYERS = new LinkedHashMap<>();
    private static final Map<String, Supplier<Object>> DIRTY_KV = new LinkedHashMap<>();
    private static Scheduler.Task flushTask;

    private DataService() {}

    public static Storage storage() {
        return storage;
    }

    public static void start() {
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Battletower-IO");
            t.setDaemon(true);
            return t;
        });
        String mode = Cfg.storage.str("mode", "json").toLowerCase(Locale.ROOT).trim();
        Storage s = switch (mode) {
            case "mysql" -> new SqlStorage(Cfg.storage, false);
            case "sqlite" -> new SqlStorage(Cfg.storage, true);
            default -> new JsonStorage(Cfg.dataDir());
        };
        try {
            io.submit(() -> {
                s.init();
                return null;
            }).get(30, TimeUnit.SECONDS);
            storage = s;
        } catch (Exception e) {
            Battletower.LOG.error("[Battletower] Echec d'initialisation du stockage {} ; repli sur JSON local.", mode, e);
            JsonStorage fallback = new JsonStorage(Cfg.dataDir());
            try {
                fallback.init();
            } catch (Exception ex) {
                Battletower.LOG.error("[Battletower] Stockage JSON indisponible", ex);
            }
            storage = fallback;
        }
        Battletower.LOG.info("[Battletower] Stockage actif : {}", storage.name());
        flushTask = Scheduler.repeat(60, 60, DataService::flushDirty);
    }

    public static <T> CompletableFuture<T> async(IoCall<T> call) {
        CompletableFuture<T> f = new CompletableFuture<>();
        if (io == null || io.isShutdown()) {
            f.completeExceptionally(new IllegalStateException("Stockage arrete"));
            return f;
        }
        io.execute(() -> {
            try {
                f.complete(call.call(storage));
            } catch (Throwable t) {
                Battletower.LOG.error("[Battletower] Erreur de stockage", t);
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    public static <T> T blocking(IoCall<T> call) {
        try {
            return async(call).get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            Battletower.LOG.error("[Battletower] Operation de stockage bloquante echouee", e);
            return null;
        }
    }

    public static void main(Runnable r) {
        if (Battletower.server() != null) Battletower.server().execute(r);
    }

    public static void markPlayer(PlayerData p) {
        DIRTY_PLAYERS.put(p.uuid, p);
    }

    public static void markKv(String key, Supplier<Object> supplier) {
        DIRTY_KV.put(key, supplier);
    }

    public static void logDaily(Storage.DailyLog log) {
        async(s -> {
            s.logDaily(log);
            return null;
        });
    }

    public static void flushDirty() {
        if (storage == null) return;
        if (!DIRTY_PLAYERS.isEmpty()) {
            List<PlayerData> snaps = new ArrayList<>();
            for (PlayerData p : DIRTY_PLAYERS.values()) snaps.add(Json.copy(p, PlayerData.class));
            DIRTY_PLAYERS.clear();
            async(s -> {
                for (PlayerData p : snaps) s.savePlayer(p);
                return null;
            });
        }
        if (!DIRTY_KV.isEmpty()) {
            Map<String, String> snaps = new LinkedHashMap<>();
            for (Map.Entry<String, Supplier<Object>> e : DIRTY_KV.entrySet()) snaps.put(e.getKey(), Json.GSON.toJson(e.getValue().get()));
            DIRTY_KV.clear();
            async(s -> {
                for (Map.Entry<String, String> e : snaps.entrySet()) s.saveKv(e.getKey(), e.getValue());
                return null;
            });
        }
    }

    public static void shutdown() {
        if (flushTask != null) flushTask.cancel();
        flushDirty();
        if (io != null) {
            io.shutdown();
            try {
                if (!io.awaitTermination(30, TimeUnit.SECONDS)) Battletower.LOG.warn("[Battletower] Ecritures en attente non terminees apres 30s");
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (storage != null) storage.close();
        storage = null;
    }
}
