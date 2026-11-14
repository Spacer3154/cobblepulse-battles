package fr.cobblepulse.battletower.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pokemon d'un dresseur PNJ (editeur d'equipe). Serialise dans floors.json / bosses.json. */
public final class MonSpec {
    public static final List<String> STATS = List.of("hp", "atk", "def", "spa", "spd", "spe");

    public String species = "";
    /** Aspects / forme Cobblemon separes par des virgules (ex. "alolan", "galarian", "mega" interdit ici). */
    public String form = "";
    public String nickname = "";
    public int level = 50;
    public String nature = "";
    public String ability = "";
    public String gender = "";
    public boolean shiny;
    public String heldItem = "";
    public Map<String, Integer> ivs = defaults(31);
    public Map<String, Integer> evs = defaults(0);
    public List<MoveSpec> moves = new ArrayList<>();
    public String teraType = "";
    public boolean allowMega;
    public boolean allowTera;

    public static final class MoveSpec {
        public String id = "";
        /** PP max (0 = valeur normale de la capacite). */
        public int ppMax;
        /** PP actuels au debut du combat (-1 = plein). */
        public int pp = -1;

        public MoveSpec() {}

        public MoveSpec(String id) {
            this.id = id;
        }
    }

    public static Map<String, Integer> defaults(int v) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String s : STATS) m.put(s, v);
        return m;
    }

    public int iv(String stat) {
        Integer v = ivs == null ? null : ivs.get(stat);
        return v == null ? 31 : Math.max(0, Math.min(31, v));
    }

    public int ev(String stat) {
        Integer v = evs == null ? null : evs.get(stat);
        return v == null ? 0 : Math.max(0, Math.min(252, v));
    }

    public void normalize() {
        if (species == null) species = "";
        if (form == null) form = "";
        if (nickname == null) nickname = "";
        if (nature == null) nature = "";
        if (ability == null) ability = "";
        if (gender == null) gender = "";
        if (heldItem == null) heldItem = "";
        if (teraType == null) teraType = "";
        level = Math.max(1, Math.min(100, level));
        Map<String, Integer> iv = defaults(31);
        Map<String, Integer> ev = defaults(0);
        for (String s : STATS) {
            iv.put(s, iv(s));
            ev.put(s, ev(s));
        }
        ivs = iv;
        evs = ev;
        if (moves == null) moves = new ArrayList<>();
        moves.removeIf(m -> m == null || m.id == null || m.id.isBlank());
        while (moves.size() > 4) moves.remove(moves.size() - 1);
    }
}
