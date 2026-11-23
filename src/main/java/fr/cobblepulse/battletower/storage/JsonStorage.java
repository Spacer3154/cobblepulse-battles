package fr.cobblepulse.battletower.storage;

import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.core.Json;
import fr.cobblepulse.battletower.data.PlayerData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Mode 1 : fichiers JSON locaux. data/players/[uuid].json, data/state/[cle].json, data/daily_logs.jsonl.
 * Ecritures atomiques : fichier .tmp puis renommage.
 */
public final class JsonStorage implements Storage {
    private final Path root;
    private final Path players;
    private final Path state;

    public JsonStorage(Path dataDir) {
        this.root = dataDir;
        this.players = dataDir.resolve("players");
        this.state = dataDir.resolve("state");
    }

    @Override
    public String name() {
        return "JSON (" + root + ")";
    }

    @Override
    public void init() throws IOException {
        Files.createDirectories(players);
        Files.createDirectories(state);
    }

    @Override
    public void close() {}

    public static void writeAtomic(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    @Override
    public List<PlayerData> loadAllPlayers() throws IOException {
        List<PlayerData> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(players)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".json")).toList()) {
                try {
                    String txt = Files.readString(p, StandardCharsets.UTF_8);
                    if (txt.isBlank()) continue;
                    PlayerData d = Json.GSON.fromJson(txt, PlayerData.class);
                    if (d != null && d.uuid != null) {
                        d.normalize();
                        out.add(d);
                    }
                } catch (Exception e) {
                    Battletower.LOG.error("[Battletower] Fichier joueur illisible {} : {}", p, e.toString());
                }
            }
        }
        return out;
    }

    @Override
    public void savePlayer(PlayerData data) throws IOException {
        writeAtomic(players.resolve(safe(data.uuid) + ".json"), Json.GSON.toJson(data));
    }

    @Override
    public String loadKv(String key) throws IOException {
        Path f = state.resolve(safe(key) + ".json");
        return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    @Override
    public void saveKv(String key, String json) throws IOException {
        writeAtomic(state.resolve(safe(key) + ".json"), json);
    }

    @Override
    public void logDaily(DailyLog log) throws IOException {
        Files.writeString(root.resolve("daily_logs.jsonl"), Json.COMPACT.toJson(log) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private Path ranked() {
        return root.resolve("ranked");
    }

    @Override
    public List<String> loadRankedProfiles() throws IOException {
        List<String> out = new java.util.ArrayList<>();
        if (!Files.isDirectory(ranked())) return out;
        try (Stream<Path> s = Files.list(ranked())) {
            for (Path f : s.filter(x -> x.toString().endsWith(".json")).toList()) {
                try {
                    out.add(Files.readString(f, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    fr.cobblepulse.battletower.Battletower.LOG.warn("[Battles] Profil Ranked illisible {} : {}", f, e.toString());
                }
            }
        }
        return out;
    }

    @Override
    public String loadRankedProfile(String uuid) throws IOException {
        Path f = ranked().resolve(safe(uuid) + ".json");
        return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    @Override
    public void saveRankedProfile(String uuid, String name, String json) throws IOException {
        writeAtomic(ranked().resolve(safe(uuid) + ".json"), json);
    }

    @Override
    public void logRankedMatch(String json) throws IOException {
        Files.writeString(root.resolve("ranked_matches.jsonl"), json + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
