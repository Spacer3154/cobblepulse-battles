package fr.cobblepulse.battletower.data;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Donnees persistantes d'un joueur (players/[uuid].json ou cp_bt_players). */
public final class PlayerData {
    public String uuid;
    public String name = "";
    public long bp;
    public long bpEarned;
    public int maxFloor;
    public int completions;
    public int totalWins;
    public int totalLosses;
    public long bestTimeMs;
    public long cooldownUntil;
    public long lastRunAt;
    /** Jour (yyyy-MM-dd) de la derniere tentative du Boss du Jour. */
    public String dailyBossDay = "";
    public int dailyBossWins;
    public Set<String> dailyBossBeaten = new LinkedHashSet<>();
    /** Succes debloques : id -> date. */
    public Map<String, Long> achievements = new LinkedHashMap<>();
    /** Dresseurs hors Tour affrontes (identifiant -> bilan). */
    public Map<String, TrainerRecord> trainers = new LinkedHashMap<>();

    public static final class TrainerRecord {
        public int wins;
        public int losses;
        public long firstWinAt;
        public long lastWinAt;
        public long lastLossAt;
        public long lastBattleAt;
    }
    public int bestNoKoStreak;
    public int monotypeFloors;
    /** Achats limites de la boutique : id -> nombre. */
    public Map<String, Integer> purchases = new HashMap<>();
    /** Le joueur doit etre renvoye au hall a sa prochaine connexion. */
    public boolean pendingExit;
    public String pendingMessage = "";

    public PlayerData() {}

    public PlayerData(String uuid, String name) {
        this.uuid = uuid;
        this.name = name == null ? "" : name;
    }

    public void normalize() {
        if (name == null) name = "";
        if (dailyBossDay == null) dailyBossDay = "";
        if (dailyBossBeaten == null) dailyBossBeaten = new LinkedHashSet<>();
        if (achievements == null) achievements = new LinkedHashMap<>();
        if (trainers == null) trainers = new LinkedHashMap<>();
        if (purchases == null) purchases = new HashMap<>();
        if (pendingMessage == null) pendingMessage = "";
    }
}
