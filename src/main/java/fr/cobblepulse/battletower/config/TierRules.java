package fr.cobblepulse.battletower.config;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Regles d'un format competitif (tier) de tournoi. */
public final class TierRules {
    public final String id;
    public String displayName;
    public String description;
    public String inherits;
    public final Set<String> bannedPokemon = new LinkedHashSet<>();
    public final Set<String> allowedPokemon = new LinkedHashSet<>();
    public final Set<String> bannedItems = new LinkedHashSet<>();
    public final Set<String> bannedAbilities = new LinkedHashSet<>();
    public final Set<String> bannedMoves = new LinkedHashSet<>();
    public int levelCap;
    public int minTeamSize;
    public int maxTeamSize;
    public boolean requireSharedType;
    public boolean requireUnevolved;
    public boolean requireNotFullyEvolved;
    public boolean speciesClause;
    public boolean itemClause;
    private boolean resolved;

    private TierRules(String id) {
        this.id = id;
    }

    public static String norm(String s) {
        if (s == null) return "";
        String v = s.toLowerCase(Locale.ROOT);
        int colon = v.indexOf(':');
        if (colon >= 0) v = v.substring(colon + 1);
        return v.replaceAll("[^a-z0-9]", "");
    }

    static TierRules from(String id, TomlFile f) {
        TierRules r = new TierRules(id);
        r.displayName = f.str("display_name", id.toUpperCase(Locale.ROOT));
        r.description = f.str("description", "");
        r.inherits = f.str("inherits", "").toLowerCase(Locale.ROOT).trim();
        for (String s : f.list("banned_pokemon")) r.bannedPokemon.add(norm(s));
        for (String s : f.list("allowed_pokemon")) r.allowedPokemon.add(norm(s));
        for (String s : f.list("banned_items")) r.bannedItems.add(norm(s));
        for (String s : f.list("banned_abilities")) r.bannedAbilities.add(norm(s));
        for (String s : f.list("banned_moves")) r.bannedMoves.add(norm(s));
        r.levelCap = f.i("level_cap", 0);
        r.minTeamSize = Math.max(1, f.i("min_team_size", 1));
        r.maxTeamSize = Math.min(6, Math.max(1, f.i("max_team_size", 6)));
        r.requireSharedType = f.b("rules.require_shared_type", f.b("require_shared_type", false));
        r.requireUnevolved = f.b("rules.require_unevolved", f.b("require_unevolved", false));
        r.requireNotFullyEvolved = f.b("rules.require_not_fully_evolved", f.b("require_not_fully_evolved", false));
        r.speciesClause = f.b("rules.species_clause", f.b("species_clause", true));
        r.itemClause = f.b("rules.item_clause", f.b("item_clause", false));
        return r;
    }

    void resolveInheritance(Map<String, TierRules> all) {
        resolveInheritance(all, new HashSet<>());
    }

    private void resolveInheritance(Map<String, TierRules> all, Set<String> visiting) {
        if (resolved) return;
        resolved = true;
        if (inherits == null || inherits.isEmpty() || !visiting.add(id)) return;
        TierRules parent = all.get(inherits);
        if (parent == null) return;
        parent.resolveInheritance(all, visiting);
        bannedPokemon.addAll(parent.bannedPokemon);
        bannedItems.addAll(parent.bannedItems);
        bannedAbilities.addAll(parent.bannedAbilities);
        bannedMoves.addAll(parent.bannedMoves);
        bannedPokemon.removeAll(allowedPokemon);
    }
}
