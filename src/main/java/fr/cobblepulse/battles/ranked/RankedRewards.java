package fr.cobblepulse.battles.ranked;

import fr.cobblepulse.battletower.config.TomlFile;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Players;
import fr.cobblepulse.battletower.core.Rewards;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Recompenses de rang (premiere fois qu'un rang est atteint dans la saison) et de fin de saison. */
public final class RankedRewards {
    private RankedRewards() {}

    static List<String> format(List<String> cmds, Map<String, Object> vars) {
        List<String> out = new ArrayList<>();
        for (String c : cmds) {
            String s = c;
            for (Map.Entry<String, Object> e : vars.entrySet()) s = s.replace("%" + e.getKey() + "%", String.valueOf(e.getValue()));
            out.add(s);
        }
        return out;
    }

    /** Execute maintenant si le joueur est en ligne, sinon a sa prochaine connexion (sur n'importe quel serveur). */
    public static void give(RankedProfile p, List<String> commands, String message) {
        if ((commands == null || commands.isEmpty()) && (message == null || message.isBlank())) return;
        ServerPlayer sp = Players.get(UUID.fromString(p.uuid));
        if (sp != null) {
            if (commands != null) Rewards.runCommands(sp, p.name, commands);
            if (message != null && !message.isBlank()) sp.sendSystemMessage(fr.cobblepulse.battletower.core.TextUtil.legacy(message));
        } else {
            if (commands != null) p.pending.addAll(commands);
            if (message != null && !message.isBlank()) p.pendingMessages.add(message);
            RankedStore.mark(p);
        }
    }

    public static void deliverPending(ServerPlayer sp) {
        RankedProfile p = RankedStore.get(sp);
        if (p.pending.isEmpty() && p.pendingMessages.isEmpty()) return;
        List<String> cmds = new ArrayList<>(p.pending);
        List<String> msgs = new ArrayList<>(p.pendingMessages);
        p.pending.clear();
        p.pendingMessages.clear();
        RankedStore.mark(p);
        Rewards.runCommands(sp, p.name, cmds);
        for (String m : msgs) sp.sendSystemMessage(fr.cobblepulse.battletower.core.TextUtil.legacy(m));
    }

    /** Rang atteint : recompense si pas encore obtenue cette saison pour ce format. */
    public static void rankReached(RankedProfile p, String format, RankedConfig.Rank rank) {
        if (!RankedConfig.rewards.b("recompenses_rang", true)) return;
        RankedProfile.FormatStats st = p.stats(format);
        if (st.rankRewards.contains(rank.id())) return;
        st.rankRewards.add(rank.id());
        // les rangs inferieurs comptent comme obtenus (pas de rattrapage multiple apres un saut)
        List<String> cmds = RankedConfig.rewards.list("rang." + rank.id() + ".commandes");
        RankedStore.mark(p);
        if (cmds.isEmpty()) return;
        give(p, format(cmds, Map.of("uuid", p.uuid, "rang", fr.cobblepulse.battletower.core.TextUtil.strip(rank.name()),
                "saison", Seasons.currentId(), "format", format)), Msg.format("ranked.rank_reward", "rank", rank.name()));
    }

    /** Fin de saison : recompenses des places configurees. */
    public static void seasonReward(RankedProfile p, String format, int place, String seasonId) {
        for (TomlFile t : RankedConfig.rewards.tables("saison")) {
            if (!format.equalsIgnoreCase(t.str("format", ""))) continue;
            if (!inRange(place, t.str("place", ""))) continue;
            List<String> cmds = format(t.list("commandes"), Map.of("uuid", p.uuid, "place", place, "saison", seasonId, "format", format,
                    "rang", ""));
            give(p, cmds, Msg.format("ranked.season_reward", "name", t.str("nom", ""), "place", place, "format", format));
        }
    }

    static boolean inRange(int place, String range) {
        String r = range.trim();
        try {
            if (r.contains("-")) {
                String[] parts = r.split("-");
                return place >= Integer.parseInt(parts[0].trim()) && place <= Integer.parseInt(parts[1].trim());
            }
            return place == Integer.parseInt(r);
        } catch (Exception e) {
            return false;
        }
    }
}
