package fr.cobblepulse.battletower.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Import / export d'equipes au format texte de Pokemon Showdown (teambuilder), pour remplir l'editeur en un collage.
 */
public final class ShowdownFormat {
    private static final Map<String, String> STAT_IN = Map.of("hp", "hp", "atk", "atk", "def", "def", "spa", "spa", "spd", "spd", "spe", "spe");
    private static final Map<String, String> STAT_OUT = Map.of("hp", "HP", "atk", "Atk", "def", "Def", "spa", "SpA", "spd", "SpD", "spe", "Spe");
    private static final Map<String, String> FORM_SUFFIX = Map.of("alola", "alolan", "galar", "galarian", "hisui", "hisuian",
            "paldea", "paldean");

    private ShowdownFormat() {}

    public static String id(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** "Life Orb" -> "cobblemon:life_orb" */
    public static String itemId(String name) {
        String n = name.trim();
        if (n.contains(":")) return n.toLowerCase(Locale.ROOT);
        return "cobblemon:" + n.toLowerCase(Locale.ROOT).replace("'", "").replace("-", "_").replaceAll("[^a-z0-9_ ]", "").trim().replace(' ', '_');
    }

    public static List<MonSpec> parse(String text) {
        List<MonSpec> out = new ArrayList<>();
        if (text == null) return out;
        MonSpec cur = null;
        for (String raw : text.replace("\r", "").split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                if (cur != null) {
                    out.add(cur);
                    cur = null;
                }
                continue;
            }
            if (cur == null) {
                cur = header(line);
                continue;
            }
            String low = line.toLowerCase(Locale.ROOT);
            if (low.startsWith("ability:")) cur.ability = id(line.substring(8));
            else if (low.startsWith("level:")) cur.level = intOr(line.substring(6).trim(), 50);
            else if (low.startsWith("shiny:")) cur.shiny = low.contains("yes");
            else if (low.startsWith("tera type:")) cur.teraType = id(line.substring(10));
            else if (low.startsWith("evs:")) stats(cur, line.substring(4), true);
            else if (low.startsWith("ivs:")) stats(cur, line.substring(4), false);
            else if (low.endsWith(" nature")) cur.nature = id(line.substring(0, line.length() - 7));
            else if (line.startsWith("-") || line.startsWith("~")) {
                String mv = line.substring(1).trim();
                int slash = mv.indexOf('/');
                if (slash > 0) mv = mv.substring(0, slash).trim();
                if (mv.toLowerCase(Locale.ROOT).startsWith("hidden power")) mv = "Hidden Power";
                if (cur.moves.size() < 4) cur.moves.add(new MonSpec.MoveSpec(id(mv)));
            }
        }
        if (cur != null) out.add(cur);
        while (out.size() > 6) out.remove(out.size() - 1);
        for (MonSpec m : out) m.normalize();
        return out;
    }

    private static MonSpec header(String line) {
        MonSpec m = new MonSpec();
        String item = "";
        int at = line.lastIndexOf(" @ ");
        if (at >= 0) {
            item = line.substring(at + 3).trim();
            line = line.substring(0, at).trim();
        }
        String gender = "";
        if (line.endsWith("(M)") || line.endsWith("(F)")) {
            gender = line.endsWith("(M)") ? "male" : "female";
            line = line.substring(0, line.length() - 3).trim();
        }
        String species = line;
        int open = line.lastIndexOf('(');
        int close = line.lastIndexOf(')');
        if (open > 0 && close > open) {
            m.nickname = line.substring(0, open).trim();
            species = line.substring(open + 1, close).trim();
        }
        String form = "";
        int dash = species.indexOf('-');
        if (dash > 0) {
            String suffix = species.substring(dash + 1).toLowerCase(Locale.ROOT);
            String mapped = FORM_SUFFIX.get(suffix);
            if (mapped != null) {
                form = mapped;
                species = species.substring(0, dash);
            }
        }
        m.species = id(species);
        m.form = form;
        m.gender = gender;
        if (!item.isEmpty()) m.heldItem = itemId(item);
        m.evs = MonSpec.defaults(0);
        m.ivs = MonSpec.defaults(31);
        return m;
    }

    private static void stats(MonSpec m, String spec, boolean ev) {
        for (String part : spec.split("/")) {
            String[] kv = part.trim().split("\\s+");
            if (kv.length < 2) continue;
            String stat = STAT_IN.get(kv[1].toLowerCase(Locale.ROOT));
            if (stat == null) continue;
            int v = intOr(kv[0], ev ? 0 : 31);
            if (ev) m.evs.put(stat, v);
            else m.ivs.put(stat, v);
        }
    }

    private static int intOr(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static String export(List<MonSpec> team) {
        StringBuilder b = new StringBuilder();
        for (MonSpec m : team) {
            String species = cap(m.species) + (m.form.isBlank() ? "" : "-" + formOut(m.form));
            String head = m.nickname.isBlank() ? species : m.nickname + " (" + species + ")";
            if ("male".equalsIgnoreCase(m.gender)) head += " (M)";
            if ("female".equalsIgnoreCase(m.gender)) head += " (F)";
            if (!m.heldItem.isBlank()) head += " @ " + itemOut(m.heldItem);
            b.append(head).append('\n');
            if (!m.ability.isBlank()) b.append("Ability: ").append(cap(m.ability)).append('\n');
            if (m.level != 100) b.append("Level: ").append(m.level).append('\n');
            if (m.shiny) b.append("Shiny: Yes\n");
            if (!m.teraType.isBlank()) b.append("Tera Type: ").append(cap(m.teraType)).append('\n');
            String evs = statLine(m, true);
            if (!evs.isEmpty()) b.append("EVs: ").append(evs).append('\n');
            if (!m.nature.isBlank()) b.append(cap(m.nature)).append(" Nature\n");
            String ivs = statLine(m, false);
            if (!ivs.isEmpty()) b.append("IVs: ").append(ivs).append('\n');
            for (MonSpec.MoveSpec mv : m.moves) b.append("- ").append(cap(mv.id)).append('\n');
            b.append('\n');
        }
        return b.toString();
    }

    private static String statLine(MonSpec m, boolean ev) {
        List<String> parts = new ArrayList<>();
        for (String s : MonSpec.STATS) {
            int v = ev ? m.ev(s) : m.iv(s);
            if (ev ? v > 0 : v < 31) parts.add(v + " " + STAT_OUT.get(s));
        }
        return String.join(" / ", parts);
    }

    private static String formOut(String form) {
        for (Map.Entry<String, String> e : FORM_SUFFIX.entrySet()) if (e.getValue().equalsIgnoreCase(form)) return cap(e.getKey());
        return cap(form);
    }

    private static String itemOut(String id) {
        String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        StringBuilder b = new StringBuilder();
        for (String w : p.split("_")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
