package fr.cobblepulse.battles.ranked;

import com.google.gson.reflect.TypeToken;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.core.Json;
import fr.cobblepulse.battletower.core.Loc;
import fr.cobblepulse.battletower.storage.JsonStorage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Arenes de la Ranked (ranked/arenes.json) : deux positions de joueur, une sortie facultative. */
public final class Arenas {
    public static final class Arena {
        public String name;
        public boolean enabled = true;
        public Loc pos1;
        public Loc pos2;
        public Loc exit;

        public boolean ready() {
            return enabled && pos1 != null && pos2 != null;
        }
    }

    private static final List<Arena> ARENAS = new ArrayList<>();
    private static final Set<String> BUSY = new HashSet<>();
    private static int cursor;

    private Arenas() {}

    static Path file() {
        return Cfg.root().resolve("ranked").resolve("arenes.json");
    }

    public static void load() {
        ARENAS.clear();
        try {
            if (Files.exists(file())) {
                List<Arena> list = Json.GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), new TypeToken<List<Arena>>() {}.getType());
                if (list != null) for (Arena a : list) if (a != null && a.name != null) ARENAS.add(a);
            } else {
                save();
            }
        } catch (Exception e) {
            Battletower.LOG.error("[Battles] arenes.json illisible : {}", e.toString());
        }
    }

    public static void save() {
        try {
            JsonStorage.writeAtomic(file(), Json.GSON.toJson(ARENAS));
        } catch (Exception e) {
            Battletower.LOG.error("[Battles] Ecriture de arenes.json impossible : {}", e.toString());
        }
    }

    public static List<Arena> all() {
        return ARENAS;
    }

    public static Arena get(String name) {
        for (Arena a : ARENAS) if (a.name.equalsIgnoreCase(name)) return a;
        return null;
    }

    public static Arena create(String name) {
        Arena a = get(name);
        if (a != null) return a;
        a = new Arena();
        a.name = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        ARENAS.add(a);
        save();
        return a;
    }

    public static boolean remove(String name) {
        boolean r = ARENAS.removeIf(a -> a.name.equalsIgnoreCase(name));
        if (r) save();
        return r;
    }

    public static boolean busy(Arena a) {
        return BUSY.contains(a.name);
    }

    /** Reserve une arene libre (null si aucune). */
    public static Arena reserve() {
        List<Arena> free = new ArrayList<>();
        for (Arena a : ARENAS) if (a.ready() && !BUSY.contains(a.name)) free.add(a);
        if (free.isEmpty()) return null;
        Arena a;
        if ("sequentiel".equalsIgnoreCase(RankedConfig.general.str("arenes.selection", "aleatoire"))) {
            a = free.get(Math.floorMod(cursor++, free.size()));
        } else {
            a = free.get(ThreadLocalRandom.current().nextInt(free.size()));
        }
        BUSY.add(a.name);
        return a;
    }

    public static void release(Arena a) {
        if (a != null) BUSY.remove(a.name);
    }

    public static boolean anyConfigured() {
        for (Arena a : ARENAS) if (a.ready()) return true;
        return false;
    }

    public static void clearBusy() {
        BUSY.clear();
    }
}
