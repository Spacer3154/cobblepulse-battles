package fr.cobblepulse.battletower.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.core.Json;
import fr.cobblepulse.battletower.gui.ChatInput;
import fr.cobblepulse.battletower.gui.Screens;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Paquets generiques des ecrans (type + JSON) ; chaque action est re-verifiee cote serveur. */
public final class Net {
    private static final Map<String, BiConsumer<ServerPlayer, JsonObject>> HANDLERS = new ConcurrentHashMap<>();

    private Net() {}

    public static void registerTypes() {
        PayloadTypeRegistry.playC2S().register(Payloads.Action.TYPE, Payloads.Action.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.Data.TYPE, Payloads.Data.CODEC);
    }

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Action.TYPE, (payload, ctx) -> {
            ServerPlayer p = ctx.player();
            ctx.server().execute(() -> handle(p, payload.action(), payload.json()));
        });
        on("menu_click", (p, o) -> Screens.click(p, num(o, "id"), num(o, "slot"), str(o, "kind", "left")));
        on("menu_close", (p, o) -> Screens.closedByClient(p, num(o, "id")));
        on("prompt_answer", (p, o) -> ChatInput.answer(p, str(o, "text", ""), o.has("cancel") && o.get("cancel").getAsBoolean()));
    }

    /** Action client supplementaire (Ranked : selection d'equipe, pret, file...). */
    public static void on(String action, BiConsumer<ServerPlayer, JsonObject> handler) {
        HANDLERS.put(action, handler);
    }

    public static boolean hasClient(ServerPlayer p) {
        try {
            return p != null && p.connection != null && ServerPlayNetworking.canSend(p, Payloads.Data.TYPE);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void send(ServerPlayer p, String kind, Object data) {
        if (!hasClient(p)) return;
        ServerPlayNetworking.send(p, new Payloads.Data(kind, data instanceof String s ? s : Json.COMPACT.toJson(data)));
    }

    public static int num(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsInt() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String str(JsonObject o, String k, String def) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
        } catch (Exception e) {
            return def;
        }
    }

    /** Point d'entree commun (aussi utilise par les auto-tests pour simuler un client). */
    public static void handle(ServerPlayer p, String action, String json) {
        JsonObject o;
        try {
            o = json == null || json.isBlank() ? new JsonObject() : JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            o = new JsonObject();
        }
        BiConsumer<ServerPlayer, JsonObject> h = HANDLERS.get(action);
        if (h == null) {
            Battletower.LOG.debug("[Battles] Action inconnue {}", action);
            return;
        }
        try {
            h.accept(p, o);
        } catch (Throwable t) {
            Battletower.LOG.error("[Battles] Erreur sur l'action {} de {}", action, p.getGameProfile().getName(), t);
        }
    }
}
