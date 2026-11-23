package fr.cobblepulse.battletower.storage;

import fr.cobblepulse.battletower.data.PlayerData;

import java.util.List;

/**
 * Couche de persistance. Trois implementations : JSON local (atomique), MySQL (HikariCP) et SQLite.
 * Les methodes sont bloquantes et ne sont appelees que depuis le thread d'E/S ({@link DataService}).
 */
public interface Storage {
    String name();

    void init() throws Exception;

    void close();

    List<PlayerData> loadAllPlayers() throws Exception;

    void savePlayer(PlayerData data) throws Exception;

    /** Etat annexe (ascensions en cours, etat de la tour, boss du jour). */
    String loadKv(String key) throws Exception;

    void saveKv(String key, String json) throws Exception;

    /** Journal des tentatives du Boss du Jour. */
    void logDaily(DailyLog log) throws Exception;

    // ------------------------------------------------------------------ Ranked (CobblePulse-Battles)

    /** Tous les profils Ranked (JSON). */
    List<String> loadRankedProfiles() throws Exception;

    /** Un profil Ranked relu depuis le stockage (multi-serveur : donnees fraiches), ou null. */
    String loadRankedProfile(String uuid) throws Exception;

    /** {@code updated} : horodatage du profil ; une version plus ancienne n'ecrase pas une plus recente (SQL). */
    void saveRankedProfile(String uuid, String name, long updated, String json) throws Exception;

    /** Journal des matchs Ranked (une ligne JSON par match). */
    void logRankedMatch(String json) throws Exception;

    record DailyLog(String day, String bossId, String playerUuid, String playerName, String result, int turns, long at) {}
}
