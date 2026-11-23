package fr.cobblepulse.battles.ranked;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.core.TextUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Webhook Discord : montees de rang, fin de saison, resultats de match. Envoi asynchrone, jamais bloquant. */
public final class Discord {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    /** Auto-test : derniers messages construits (meme si l'envoi est desactive). */
    public static final java.util.Deque<String> SENT = new java.util.concurrent.ConcurrentLinkedDeque<>();

    private Discord() {}

    static boolean on(String event) {
        var t = RankedConfig.network;
        return t != null && t.b("discord.actif", false) && t.b("discord." + event, true) && !t.str("discord.webhook", "").isBlank();
    }

    static void send(String event, String title, String description, int color) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("description", description);
        embed.addProperty("color", color);
        JsonObject body = new JsonObject();
        body.addProperty("username", RankedConfig.network.str("discord.nom", "CobblePulse Ranked"));
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        body.add("embeds", embeds);
        String json = body.toString();
        SENT.addFirst(event + " " + json);
        while (SENT.size() > 20) SENT.removeLast();
        if (!on(event)) return;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RankedConfig.network.str("discord.webhook", "")))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((r, t) -> {
                if (t != null) Battletower.LOG.warn("[Battles] Webhook Discord : {}", t.toString());
                else if (r.statusCode() >= 300) Battletower.LOG.warn("[Battles] Webhook Discord : HTTP {}", r.statusCode());
            });
        } catch (Exception e) {
            Battletower.LOG.warn("[Battles] Webhook Discord invalide : {}", e.toString());
        }
    }

    public static void rankUp(String player, String format, RankedConfig.Rank rank, int elo) {
        send("montee_rang", "Montée de rang", "**" + player + "** atteint le rang **" + TextUtil.strip(rank.name()) + "** en "
                + format + " (" + elo + " Elo)", 0x5FD3E8);
    }

    public static void matchResult(boolean ranked, String format, String winner, String loser, int winnerElo, int winnerDelta,
                                   int loserElo, int loserDelta, boolean draw) {
        String desc = draw ? "Match nul entre **" + winner + "** et **" + loser + "** (" + format + ")"
                : "**" + winner + "** bat **" + loser + "** (" + format + ")"
                + (ranked ? "\n" + winner + " : " + winnerElo + " (" + sign(winnerDelta) + ")\n" + loser + " : " + loserElo + " (" + sign(loserDelta) + ")" : "");
        send(ranked ? "resultat_classe" : "resultat_amical", ranked ? "Match classé" : "Match amical", desc, ranked ? 0x7CFC8E : 0x9BA3AF);
    }

    @SuppressWarnings("unchecked")
    public static void seasonEnd(String season, Map<String, Object> archive) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : archive.entrySet()) {
            sb.append("**").append(e.getKey()).append("**\n");
            List<Map<String, Object>> rows = (List<Map<String, Object>>) e.getValue();
            for (int i = 0; i < Math.min(3, rows.size()); i++) {
                Map<String, Object> r = rows.get(i);
                sb.append(r.get("place")).append(". ").append(r.get("nom")).append(" — ").append(r.get("elo")).append("\n");
            }
        }
        send("fin_saison", "Fin de la saison " + season, sb.toString(), 0xFFD24A);
    }

    static String sign(int d) {
        return d >= 0 ? "+" + d : String.valueOf(d);
    }
}
