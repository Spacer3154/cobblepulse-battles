package fr.cobblepulse.battles.ranked;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Profil Ranked d'un joueur (serialise en JSON, une ligne par joueur en SQL). */
public final class RankedProfile {
    public String uuid;
    public String name = "?";
    /** Saison a laquelle appartiennent les statistiques (reset au changement). */
    public String season = "";
    public Map<String, FormatStats> formats = new LinkedHashMap<>();
    // fair-play
    public int flees;
    public long lastFleeDecay;
    public long banUntil;
    public String banReason = "";
    public List<Recent> recent = new ArrayList<>();
    public long lastMatchEnd;
    public long lastQueueJoin;
    public long declineUntil;
    // missions
    public String dailyPeriod = "";
    public List<MissionState> daily = new ArrayList<>();
    public String weeklyPeriod = "";
    public List<MissionState> weekly = new ArrayList<>();
    // recompenses en attente (joueur hors ligne) : commandes deja formatees
    public List<String> pending = new ArrayList<>();
    public List<String> pendingMessages = new ArrayList<>();
    public List<MatchSummary> history = new ArrayList<>();
    public long updated;

    public static final class FormatStats {
        public int elo;
        public int peak;
        public int wins;
        public int losses;
        public int draws;
        public int streak;
        public int bestStreak;
        public int casualWins;
        public int casualLosses;
        public long lastPlayed;
        public String gainDay = "";
        public int gainToday;
        public Set<String> rankRewards = new LinkedHashSet<>();

        public int games() {
            return wins + losses + draws;
        }

        public int winrate() {
            int g = wins + losses;
            return g == 0 ? 0 : Math.round(wins * 100f / g);
        }
    }

    public static final class Recent {
        public String uuid;
        public long at;

        public Recent() {}

        public Recent(String uuid, long at) {
            this.uuid = uuid;
            this.at = at;
        }
    }

    public static final class MissionState {
        public String id;
        public int progress;
        public boolean done;
        public boolean claimed;

        public MissionState() {}

        public MissionState(String id) {
            this.id = id;
        }
    }

    public static final class MatchSummary {
        public String opponent;
        public String opponentName;
        public String format;
        public boolean ranked;
        /** victoire, defaite, nul */
        public String result;
        public int eloBefore;
        public int eloAfter;
        public int turns;
        public String reason = "";
        public long at;
    }

    public RankedProfile() {}

    public RankedProfile(String uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public FormatStats stats(String format) {
        return formats.computeIfAbsent(format, f -> {
            FormatStats s = new FormatStats();
            s.elo = RankedConfig.startElo();
            s.peak = s.elo;
            return s;
        });
    }

    public void normalize() {
        if (formats == null) formats = new LinkedHashMap<>();
        if (recent == null) recent = new ArrayList<>();
        if (daily == null) daily = new ArrayList<>();
        if (weekly == null) weekly = new ArrayList<>();
        if (pending == null) pending = new ArrayList<>();
        if (pendingMessages == null) pendingMessages = new ArrayList<>();
        if (history == null) history = new ArrayList<>();
        if (season == null) season = "";
        if (dailyPeriod == null) dailyPeriod = "";
        if (weeklyPeriod == null) weeklyPeriod = "";
        if (banReason == null) banReason = "";
        for (FormatStats s : formats.values()) {
            if (s.rankRewards == null) s.rankRewards = new LinkedHashSet<>();
            if (s.gainDay == null) s.gainDay = "";
        }
    }
}
