package fr.cobblepulse.battletower.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.config.TomlFile;
import fr.cobblepulse.battletower.core.Json;
import fr.cobblepulse.battletower.data.PlayerData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mode 2 : MySQL (pool HikariCP, peut partager la base de CobblePulse) ou SQLite (fichier local).
 * Tables : cp_bt_players, cp_bt_achievements, cp_bt_daily_logs, cp_bt_kv.
 */
public final class SqlStorage implements Storage {
    private final boolean sqlite;
    private final TomlFile cfg;
    private final String p;
    private HikariDataSource ds;
    private Connection sqliteConn;
    private String desc = "";

    public SqlStorage(TomlFile cfg, boolean sqlite) {
        this.cfg = cfg;
        this.sqlite = sqlite;
        this.p = cfg.str("mysql.table_prefix", "cp_bt_").replaceAll("[^A-Za-z0-9_]", "");
    }

    @Override
    public String name() {
        return desc;
    }

    private Connection conn() throws SQLException {
        if (sqlite) return sqliteConn;
        return ds.getConnection();
    }

    private void release(Connection c) throws SQLException {
        if (!sqlite && c != null) c.close();
    }

    @Override
    public void init() throws Exception {
        if (sqlite) {
            Class.forName("org.sqlite.JDBC");
            Path file = Cfg.root().resolve(cfg.str("sqlite.file", "data/battletower.db"));
            Files.createDirectories(file.getParent());
            sqliteConn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement st = sqliteConn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
            }
            desc = "SQLite (" + file + ")";
        } else {
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException ignored) {
            }
            TomlFile src = cfg;
            String prefix = "mysql.";
            if (cfg.b("mysql.share_cobblepulse", false)) {
                Path cp = Cfg.cobblePulseRoot().resolve("storage.toml");
                if (Files.exists(cp)) src = TomlFile.loadPlain(cp);
            }
            String host = src.str(prefix + "host", "localhost");
            String db = src.str(prefix + "database", "cobblepulse");
            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl("jdbc:mysql://" + host + ":" + src.i(prefix + "port", 3306) + "/" + db + "?useSSL=" + src.b(prefix + "use_ssl", false)
                    + "&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC");
            hc.setUsername(src.str(prefix + "username", "root"));
            hc.setPassword(src.str(prefix + "password", ""));
            hc.setMaximumPoolSize(Math.max(2, cfg.i("mysql.pool_size", 4)));
            hc.setPoolName("Battletower-MySQL");
            hc.setConnectionTimeout(10_000);
            ds = new HikariDataSource(hc);
            desc = "MySQL (" + host + "/" + db + ")";
        }
        String charset = sqlite ? "" : " CHARACTER SET utf8mb4";
        String autoId = sqlite ? "id INTEGER PRIMARY KEY AUTOINCREMENT" : "id BIGINT AUTO_INCREMENT PRIMARY KEY";
        Connection c = conn();
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(32), bp BIGINT DEFAULT 0,"
                    + " bp_earned BIGINT DEFAULT 0, max_floor INT DEFAULT 0, completions INT DEFAULT 0, total_wins INT DEFAULT 0,"
                    + " total_losses INT DEFAULT 0, best_time_ms BIGINT DEFAULT 0, cooldown_until BIGINT DEFAULT 0,"
                    + " daily_boss_day VARCHAR(16), daily_boss_wins INT DEFAULT 0, data MEDIUMTEXT)" + charset);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "achievements (player_uuid VARCHAR(36) NOT NULL, achievement_id VARCHAR(64) NOT NULL,"
                    + " unlocked_at BIGINT, PRIMARY KEY (player_uuid, achievement_id))" + charset);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "daily_logs (" + autoId + ", day VARCHAR(16), boss_id VARCHAR(64),"
                    + " player_uuid VARCHAR(36), player_name VARCHAR(32), result VARCHAR(16), turns INT, at BIGINT)" + charset);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "kv (k VARCHAR(128) PRIMARY KEY, v MEDIUMTEXT)" + charset);
        } finally {
            release(c);
        }
    }

    @Override
    public void close() {
        try {
            if (ds != null) ds.close();
            if (sqliteConn != null) sqliteConn.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public List<PlayerData> loadAllPlayers() throws SQLException {
        Map<String, PlayerData> map = new HashMap<>();
        Connection c = conn();
        try {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, data FROM " + p + "players"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String json = rs.getString("data");
                    PlayerData d = json == null || json.isBlank() ? new PlayerData(rs.getString("uuid"), rs.getString("name"))
                            : Json.COMPACT.fromJson(json, PlayerData.class);
                    if (d == null) continue;
                    d.uuid = rs.getString("uuid");
                    d.normalize();
                    map.put(d.uuid, d);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT player_uuid, achievement_id, unlocked_at FROM " + p + "achievements");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlayerData d = map.get(rs.getString("player_uuid"));
                    if (d != null) d.achievements.putIfAbsent(rs.getString("achievement_id"), rs.getLong("unlocked_at"));
                }
            }
        } finally {
            release(c);
        }
        return new ArrayList<>(map.values());
    }

    @Override
    public void savePlayer(PlayerData d) throws SQLException {
        Connection c = conn();
        try {
            try (PreparedStatement ps = c.prepareStatement("REPLACE INTO " + p + "players (uuid,name,bp,bp_earned,max_floor,completions,total_wins,"
                    + "total_losses,best_time_ms,cooldown_until,daily_boss_day,daily_boss_wins,data) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, d.uuid);
                ps.setString(2, d.name);
                ps.setLong(3, d.bp);
                ps.setLong(4, d.bpEarned);
                ps.setInt(5, d.maxFloor);
                ps.setInt(6, d.completions);
                ps.setInt(7, d.totalWins);
                ps.setInt(8, d.totalLosses);
                ps.setLong(9, d.bestTimeMs);
                ps.setLong(10, d.cooldownUntil);
                ps.setString(11, d.dailyBossDay);
                ps.setInt(12, d.dailyBossWins);
                ps.setString(13, Json.COMPACT.toJson(d));
                ps.executeUpdate();
            }
            String ins = sqlite ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ";
            try (PreparedStatement ps = c.prepareStatement(ins + p + "achievements (player_uuid, achievement_id, unlocked_at) VALUES (?,?,?)")) {
                for (Map.Entry<String, Long> e : d.achievements.entrySet()) {
                    ps.setString(1, d.uuid);
                    ps.setString(2, e.getKey());
                    ps.setLong(3, e.getValue());
                    ps.addBatch();
                }
                if (!d.achievements.isEmpty()) ps.executeBatch();
            }
        } finally {
            release(c);
        }
    }

    @Override
    public String loadKv(String key) throws SQLException {
        Connection c = conn();
        try (PreparedStatement ps = c.prepareStatement("SELECT v FROM " + p + "kv WHERE k = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } finally {
            release(c);
        }
    }

    @Override
    public void saveKv(String key, String json) throws SQLException {
        Connection c = conn();
        try (PreparedStatement ps = c.prepareStatement("REPLACE INTO " + p + "kv (k, v) VALUES (?,?)")) {
            ps.setString(1, key);
            ps.setString(2, json);
            ps.executeUpdate();
        } finally {
            release(c);
        }
    }

    @Override
    public void logDaily(DailyLog log) throws SQLException {
        Connection c = conn();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + p + "daily_logs (day, boss_id, player_uuid, player_name, result, turns, at)"
                + " VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, log.day());
            ps.setString(2, log.bossId());
            ps.setString(3, log.playerUuid());
            ps.setString(4, log.playerName());
            ps.setString(5, log.result());
            ps.setInt(6, log.turns());
            ps.setLong(7, log.at());
            ps.executeUpdate();
        } finally {
            release(c);
        }
    }
}
