package fr.cobblepulse.battles.ranked;

import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.battletower.battle.CobblemonBridge;
import fr.cobblepulse.battletower.battle.CobblemonBridge.MonInfo;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.config.TierRules;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.tower.TeamValidator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Regles d'equipe de la Ranked : par Pokemon (eligibilite) et par selection (clauses d'equipe). */
public final class TeamRules {
    private TeamRules() {}

    static boolean megaStone(String item) {
        String i = item.toLowerCase(Locale.ROOT);
        if (!i.startsWith("mega_showdown:")) return false;
        String path = i.substring(i.indexOf(':') + 1);
        return path.matches(".*ite(_[xy])?$") && !path.equals("eviolite") && !path.endsWith("_crystalite");
    }

    static boolean zCrystal(String item) {
        String i = item.toLowerCase(Locale.ROOT);
        return i.matches(".*:[a-z_]*ium_z$") || i.endsWith("_z_crystal");
    }

    /** Raison pour laquelle ce Pokemon ne peut pas etre amene (null s'il est autorise). */
    public static String checkPokemon(Pokemon pk, FormatRules r) {
        MonInfo m;
        try {
            m = CobblemonBridge.info(pk);
        } catch (Throwable t) {
            return Msg.formatBare("ranked.rule_unreadable");
        }
        Set<String> ids = new HashSet<>(m.aliases());
        ids.add(m.speciesId());
        ids.add(m.formId());
        for (String id : ids) if (r.bannedSpecies.contains(id)) return Msg.formatBare("ranked.rule_banned_species");
        for (String mv : m.moves()) {
            String n = FormatRules.norm(mv);
            if (r.bannedMoves.contains(n)) return Msg.formatBare("ranked.rule_banned_move", "move", mv);
            if (r.ohkoClause && RankedConfig.OHKO.contains(n)) return Msg.formatBare("ranked.rule_ohko", "move", mv);
            if (r.evasionClause && RankedConfig.EVASION.contains(n)) return Msg.formatBare("ranked.rule_evasion", "move", mv);
        }
        if (!m.ability().isEmpty() && r.bannedAbilities.contains(FormatRules.norm(m.ability()))) {
            return Msg.formatBare("ranked.rule_banned_ability", "ability", m.ability());
        }
        String item = m.heldItem() == null ? "" : m.heldItem().toLowerCase(Locale.ROOT);
        if (!item.isEmpty()) {
            if (r.bannedItems.contains(item)) return Msg.formatBare("ranked.rule_banned_item", "item", item);
            if (!r.mega && megaStone(item)) return Msg.formatBare("ranked.rule_no_mega");
            if (!r.z && zCrystal(item)) return Msg.formatBare("ranked.rule_no_z");
        }
        switch (r.special) {
            case "petite_coupe", "little_cup" -> {
                if (m.hasPreEvolution() || !m.canEvolve()) return Msg.formatBare("ranked.rule_little_cup");
            }
            case "nfe" -> {
                if (!m.canEvolve()) return Msg.formatBare("ranked.rule_nfe");
            }
            case "chromatique", "shiny" -> {
                if (!m.shiny()) return Msg.formatBare("ranked.rule_shiny");
            }
            case "pokedex", "dex" -> {
                int dex = pk.getSpecies().getNationalPokedexNumber();
                if (dex < r.dexMin || dex > r.dexMax) return Msg.formatBare("ranked.rule_dex", "min", r.dexMin, "max", r.dexMax);
            }
            default -> {
            }
        }
        return null;
    }

    public static boolean restricted(Pokemon pk) {
        try {
            MonInfo m = CobblemonBridge.info(pk);
            if (RankedConfig.RESTRICTED.contains(m.speciesId())) return true;
            for (String a : m.aliases()) if (RankedConfig.RESTRICTED.contains(a)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Erreurs d'une selection complete (dans l'ordre choisi). */
    public static List<String> checkSelection(List<Pokemon> sel, FormatRules r) {
        List<String> errors = new ArrayList<>();
        if (sel.size() != r.teamSize) errors.add(Msg.formatBare("ranked.rule_count", "n", r.teamSize));
        Set<String> species = new HashSet<>();
        Map<String, Integer> items = new HashMap<>();
        int restricted = 0;
        int sleepMoves = 0;
        Set<String> shared = null;
        List<MonInfo> infos = new ArrayList<>();
        for (Pokemon pk : sel) {
            String why = checkPokemon(pk, r);
            if (why != null) errors.add(pk.getSpecies().getTranslatedName().getString() + " : " + why);
            MonInfo m = CobblemonBridge.info(pk);
            infos.add(m);
            if (r.speciesClause && !species.add(m.speciesId())) errors.add(Msg.formatBare("ranked.rule_species_clause", "pokemon", m.species()));
            String item = m.heldItem() == null ? "" : m.heldItem().toLowerCase(Locale.ROOT);
            if (!item.isEmpty() && r.itemClause > 0 && items.merge(item, 1, Integer::sum) > r.itemClause) {
                errors.add(Msg.formatBare("ranked.rule_item_clause", "item", item, "n", r.itemClause));
            }
            if (restricted(pk)) restricted++;
            for (String mv : m.moves()) if (RankedConfig.SLEEP.contains(FormatRules.norm(mv))) sleepMoves++;
            Set<String> types = new HashSet<>(m.types());
            shared = shared == null ? types : intersect(shared, types);
        }
        if (restricted > Math.max(0, r.maxRestricted)) errors.add(Msg.formatBare("ranked.rule_restricted", "n", r.maxRestricted));
        if (r.limitSleepMoves && sleepMoves > 1) errors.add(Msg.formatBare("ranked.rule_sleep_moves"));
        if ("monotype".equals(r.special) && !sel.isEmpty() && (shared == null || shared.isEmpty())) errors.add(Msg.formatBare("ranked.rule_monotype"));
        if (!r.tier.isEmpty()) {
            TierRules t = Cfg.tier(r.tier);
            if (t != null) errors.addAll(TeamValidator.validate(infos, t, r.level > 0));
        }
        return errors;
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.retainAll(b);
        return out;
    }

    /** Pokemon de l'equipe pouvant etre amenes. */
    public static List<Pokemon> eligible(List<Pokemon> party, FormatRules r) {
        List<Pokemon> out = new ArrayList<>();
        for (Pokemon pk : party) if (pk != null && checkPokemon(pk, r) == null) out.add(pk);
        return out;
    }

    /** Selection automatique (temps ecoule) : premiers Pokemon eligibles qui respectent les clauses. */
    public static List<Pokemon> autoPick(List<Pokemon> party, List<Pokemon> already, FormatRules r) {
        List<Pokemon> sel = new ArrayList<>();
        for (Pokemon pk : already) if (party.contains(pk) && checkPokemon(pk, r) == null && fits(sel, pk, r)) sel.add(pk);
        for (Pokemon pk : party) {
            if (sel.size() >= r.teamSize) break;
            if (pk == null || sel.contains(pk) || checkPokemon(pk, r) != null) continue;
            if (fits(sel, pk, r)) sel.add(pk);
        }
        return sel;
    }

    static boolean fits(List<Pokemon> sel, Pokemon pk, FormatRules r) {
        List<Pokemon> tmp = new ArrayList<>(sel);
        tmp.add(pk);
        // clauses d'equipe seulement (hors nombre et tier) : espece, objet, restreints
        Set<String> species = new HashSet<>();
        Map<String, Integer> items = new HashMap<>();
        int restricted = 0;
        for (Pokemon p : tmp) {
            MonInfo m = CobblemonBridge.info(p);
            if (r.speciesClause && !species.add(m.speciesId())) return false;
            String item = m.heldItem() == null ? "" : m.heldItem().toLowerCase(Locale.ROOT);
            if (!item.isEmpty() && r.itemClause > 0 && items.merge(item, 1, Integer::sum) > r.itemClause) return false;
            if (restricted(p)) restricted++;
        }
        return restricted <= Math.max(0, r.maxRestricted);
    }

    /** L'equipe permet-elle d'entrer en file ? (null = oui, sinon raison) */
    public static String canQueue(List<Pokemon> party, FormatRules r) {
        List<Pokemon> auto = autoPick(party, List.of(), r);
        if (auto.size() < r.teamSize) {
            return Msg.formatBare("ranked.rule_not_enough", "n", r.teamSize, "have", eligible(party, r).size());
        }
        return null;
    }
}
