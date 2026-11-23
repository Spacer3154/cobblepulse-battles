package fr.cobblepulse.battles.ranked;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.config.TomlFile;
import fr.cobblepulse.battletower.core.Json;
import fr.cobblepulse.battletower.storage.DataService;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Multi-serveur de la Ranked : file partagee et messages entre serveurs via Redis, transfert des joueurs vers le
 * serveur de combat (canal BungeeCord de Velocity, ou paquet de transfert vanilla) et retour au serveur d'origine.
 * Desactive : tout reste local au serveur.
 */
public final class CrossServer {
    private static JedisPooled redis;
    private static Thread subscriber;
    private static JedisPubSub pubsub;
    private static volatile boolean running;
    /** Appels Redis hors du fil serveur (un Redis lent ne bloque jamais les ticks). */
    private static final java.util.concurrent.ExecutorService EXEC = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Battles-Redis-IO");
        t.setDaemon(true);
        return t;
    });

    private static void run(Runnable r) {
        EXEC.execute(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                Battletower.LOG.warn("[Battles] Redis : {}", t.toString());
            }
        });
    }
    private static final Map<String, Consumer<JsonObject>> HANDLERS = new ConcurrentHashMap<>();
    /** Auto-tests : transferts demandes (joueur -> serveur), au lieu de les envoyer a un proxy absent. */
    public static final Map<String, String> TRANSFERS = new ConcurrentHashMap<>();

    private CrossServer() {}

    static TomlFile cfg() {
        return RankedConfig.network;
    }

    public static boolean configured() {
        return cfg() != null && cfg().b("multi_serveur.actif", false);
    }

    public static boolean enabled() {
        return configured() && redis != null;
    }

    public static String serverId() {
        return cfg() == null ? "local" : cfg().str("multi_serveur.id_serveur", "lobby");
    }

    public static String battleServer() {
        return cfg() == null ? "local" : cfg().str("multi_serveur.serveur_combat", "combat");
    }

    /** Le serveur qui fait le matchmaking, joue les matchs et change de saison. */
    public static boolean isMatchmaker() {
        return !configured() || serverId().equalsIgnoreCase(battleServer());
    }

    private static String key(String k) {
        return cfg().str("redis.prefixe", "cpbattles") + ":" + k;
    }

    public static void on(String type, Consumer<JsonObject> handler) {
        HANDLERS.put(type, handler);
    }

    public static void start() {
        stop();
        if (!configured()) return;
        try {
            TomlFile c = cfg();
            HostAndPort hp = new HostAndPort(c.str("redis.hote", "127.0.0.1"), c.i("redis.port", 6379));
            DefaultJedisClientConfig.Builder b = DefaultJedisClientConfig.builder().database(c.i("redis.base", 0)).ssl(c.b("redis.ssl", false))
                    .timeoutMillis(5000);
            String pw = c.str("redis.mot_de_passe", "");
            if (!pw.isBlank()) b.password(pw);
            DefaultJedisClientConfig conf = b.build();
            redis = new JedisPooled(hp, conf);
            redis.ping();
            running = true;
            String channel = key("evenements");
            pubsub = new JedisPubSub() {
                @Override
                public void onMessage(String ch, String message) {
                    receive(message);
                }
            };
            subscriber = new Thread(() -> {
                while (running) {
                    try (Jedis j = new Jedis(hp, conf)) {
                        j.subscribe(pubsub, channel);
                    } catch (Throwable t) {
                        if (!running) break;
                        Battletower.LOG.warn("[Battles] Redis (abonnement) : {} ; nouvel essai dans 5 s", t.toString());
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            }, "Battles-Redis");
            subscriber.setDaemon(true);
            subscriber.start();
            Battletower.LOG.info("[Battles] Multi-serveur actif : serveur {} (combat : {}), Redis {}:{}", serverId(), battleServer(), hp.getHost(), hp.getPort());
            if (!"mysql".equalsIgnoreCase(fr.cobblepulse.battletower.config.Cfg.storage.str("mode", "json"))) {
                Battletower.LOG.warn("[Battles] Multi-serveur : storage.toml n'est pas en MySQL ; les profils Ranked ne seront pas partages !");
            }
        } catch (Throwable t) {
            Battletower.LOG.error("[Battles] Connexion Redis impossible, la Ranked reste locale : {}", t.toString());
            redis = null;
        }
    }

    public static void stop() {
        running = false;
        try {
            if (pubsub != null && pubsub.isSubscribed()) pubsub.unsubscribe();
        } catch (Throwable ignored) {
        }
        try {
            if (redis != null) redis.close();
        } catch (Throwable ignored) {
        }
        redis = null;
        pubsub = null;
        subscriber = null;
    }

    private static void receive(String message) {
        try {
            JsonObject o = JsonParser.parseString(message).getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString() : "";
            String from = o.has("from") ? o.get("from").getAsString() : "";
            Consumer<JsonObject> h = HANDLERS.get(type);
            if (h == null) return;
            DataService.main(() -> {
                try {
                    h.accept(o);
                } catch (Throwable t) {
                    Battletower.LOG.error("[Battles] Message {} de {} : erreur", type, from, t);
                }
            });
        } catch (Throwable t) {
            Battletower.LOG.warn("[Battles] Message Redis illisible : {}", t.toString());
        }
    }

    /** Message a tous les serveurs (y compris celui-ci). */
    public static void publish(String type, Map<String, ?> data) {
        if (!enabled()) return;
        JsonObject o = Json.COMPACT.toJsonTree(data).getAsJsonObject();
        o.addProperty("type", type);
        o.addProperty("from", serverId());
        String msg = o.toString();
        String channel = key("evenements");
        JedisPooled r = redis;
        run(() -> r.publish(channel, msg));
    }

    // ------------------------------------------------------------------ file partagee

    public static void queuePut(RankedQueue.Entry e) {
        if (!enabled()) return;
        JedisPooled r = redis;
        String k = key("file");
        String json = Json.COMPACT.toJson(e);
        run(() -> r.hset(k, e.uuid, json));
    }

    public static void queueRemove(String uuid) {
        if (!enabled()) return;
        JedisPooled r = redis;
        String k = key("file");
        run(() -> r.hdel(k, uuid));
    }

    /** Lecture de la file partagee (hors fil serveur) ; le resultat revient sur le fil serveur. */
    public static void queueAll(Consumer<List<RankedQueue.Entry>> then) {
        if (!enabled()) {
            then.accept(new ArrayList<>());
            return;
        }
        JedisPooled r = redis;
        String k = key("file");
        run(() -> {
            List<RankedQueue.Entry> out = new ArrayList<>();
            for (String json : r.hgetAll(k).values()) {
                try {
                    RankedQueue.Entry e = Json.COMPACT.fromJson(json, RankedQueue.Entry.class);
                    if (e != null && e.uuid != null) out.add(e);
                } catch (Exception ignored) {
                }
            }
            DataService.main(() -> then.accept(out));
        });
    }

    // ------------------------------------------------------------------ equipe transmise

    public static void putParty(String uuid, String json) {
        if (!enabled()) return;
        JedisPooled r = redis;
        String k = key("equipe:" + uuid);
        run(() -> r.setex(k, 900, json));
    }

    /** Equipe transmise par le serveur d'origine ; le resultat (null si absente) revient sur le fil serveur. */
    public static void getParty(String uuid, Consumer<String> then) {
        if (!enabled()) {
            then.accept(null);
            return;
        }
        JedisPooled r = redis;
        String k = key("equipe:" + uuid);
        run(() -> {
            String v = r.get(k);
            DataService.main(() -> then.accept(v));
        });
    }

    // ------------------------------------------------------------------ transfert

    /** Message BungeeCord (Velocity : bungee-plugin-message-channel = true). */
    public record BungeePayload(byte[] data) implements CustomPacketPayload {
        public static final Type<BungeePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("bungeecord", "main"));
        public static final StreamCodec<ByteBuf, BungeePayload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBytes(p.data),
                buf -> {
                    byte[] b = new byte[buf.readableBytes()];
                    buf.readBytes(b);
                    return new BungeePayload(b);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void registerPayloads() {
        try {
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(BungeePayload.TYPE, BungeePayload.CODEC);
        } catch (Throwable t) {
            Battletower.LOG.warn("[Battles] Canal BungeeCord deja enregistre : {}", t.toString());
        }
    }

    /** Envoie le joueur sur un autre serveur du reseau. */
    public static void transfer(ServerPlayer p, String server) {
        if (p == null || server == null || server.isBlank() || server.equalsIgnoreCase(serverId())) return;
        if (fr.cobblepulse.battletower.core.Players.isTest(p.getUUID())) {
            TRANSFERS.put(p.getUUID().toString(), server);
            Battletower.LOG.info("[Battles] (test) transfert de {} vers {}", p.getGameProfile().getName(), server);
            return;
        }
        String method = cfg().str("multi_serveur.methode_transfert", "velocity").toLowerCase(java.util.Locale.ROOT);
        try {
            if (method.startsWith("transf")) {
                String addr = cfg().str("multi_serveur.adresses." + server, "");
                int colon = addr.lastIndexOf(':');
                String host = colon > 0 ? addr.substring(0, colon) : addr;
                int port = colon > 0 ? Integer.parseInt(addr.substring(colon + 1)) : 25565;
                p.connection.send(new net.minecraft.network.protocol.common.ClientboundTransferPacket(host, port));
            } else {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeUTF("Connect");
                out.writeUTF(server);
                p.connection.send(new ClientboundCustomPayloadPacket(new BungeePayload(bytes.toByteArray())));
            }
            Battletower.LOG.info("[Battles] Transfert de {} vers {} ({})", p.getGameProfile().getName(), server, method);
        } catch (Throwable t) {
            Battletower.LOG.error("[Battles] Transfert de {} vers {} impossible : {}", p.getGameProfile().getName(), server, t.toString());
        }
    }

}
