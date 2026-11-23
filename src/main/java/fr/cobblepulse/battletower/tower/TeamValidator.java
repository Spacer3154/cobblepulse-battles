package fr.cobblepulse.battletower.tower;

import fr.cobblepulse.battletower.battle.CobblemonBridge;
import fr.cobblepulse.battletower.battle.CobblemonBridge.MonInfo;
import fr.cobblepulse.battletower.config.TierRules;
import fr.cobblepulse.battletower.core.Msg;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Verification automatique d'une equipe contre les regles d'un tier (avant chaque combat de la tour). */
public final class TeamValidator {
    private TeamValidator() {}

    public static List<String> validate(ServerPlayer p, TierRules r) {
        return validate(p, r, false);
    }

    /** @param ignoreLevel le nivellement automatique du dresseur remplace la limite de niveau du tier */
    public static List<String> validate(ServerPlayer p, TierRules r, boolean ignoreLevel) {
        List<String> errors = new ArrayList<>();
        List<MonInfo> team;
        try {
            team = CobblemonBridge.teamInfo(p);
        } catch (Throwable t) {
            errors.add(Msg.format("tier.team_read_error"));
            return errors;
        }
        return validate(team, r, ignoreLevel);
    }

    /** Validation d'une liste de Pokemon (selection de la Ranked, equipe transmise par un autre serveur...). */
    public static List<String> validate(List<MonInfo> team, TierRules r, boolean ignoreLevel) {
        List<String> errors = new ArrayList<>();
        if (team.size() < r.minTeamSize) errors.add(Msg.format("tier.team_too_small", "min", r.minTeamSize));
        if (team.size() > r.maxTeamSize) errors.add(Msg.format("tier.team_too_big", "max", r.maxTeamSize));
        Set<String> species = new HashSet<>();
        Set<String> items = new HashSet<>();
        Set<String> shared = null;
        for (MonInfo m : team) {
            boolean bannedAlias = false;
            for (String a : m.aliases()) if (r.bannedPokemon.contains(a)) bannedAlias = true;
            if (r.bannedPokemon.contains(m.speciesId()) || r.bannedPokemon.contains(m.formId()) || bannedAlias) {
                if (!r.allowedPokemon.contains(m.formId())) errors.add(Msg.format("tier.banned_pokemon", "pokemon", m.species()));
            }
            String item = TierRules.norm(m.heldItem());
            if (!item.isEmpty() && r.bannedItems.contains(item)) errors.add(Msg.format("tier.banned_item", "pokemon", m.species(), "item", m.heldItem()));
            if (!m.ability().isEmpty() && r.bannedAbilities.contains(TierRules.norm(m.ability()))) {
                errors.add(Msg.format("tier.banned_ability", "pokemon", m.species(), "ability", m.ability()));
            }
            for (String mv : m.moves()) {
                if (r.bannedMoves.contains(TierRules.norm(mv))) errors.add(Msg.format("tier.banned_move", "pokemon", m.species(), "move", mv));
            }
            if (!ignoreLevel && r.levelCap > 0 && m.level() > r.levelCap) errors.add(Msg.format("tier.level_cap", "pokemon", m.species(), "level", m.level(), "cap", r.levelCap));
            if (r.requireUnevolved && (m.hasPreEvolution() || !m.canEvolve())) errors.add(Msg.format("tier.must_be_unevolved", "pokemon", m.species()));
            if (r.requireNotFullyEvolved && !m.canEvolve()) errors.add(Msg.format("tier.must_be_nfe", "pokemon", m.species()));
            if (r.speciesClause && !species.add(m.speciesId())) errors.add(Msg.format("tier.species_clause", "pokemon", m.species()));
            if (r.itemClause && !item.isEmpty() && !items.add(item)) errors.add(Msg.format("tier.item_clause", "item", m.heldItem()));
            if (shared == null) shared = new LinkedHashSet<>(m.types());
            else shared.retainAll(m.types());
        }
        if (r.requireSharedType && !team.isEmpty() && (shared == null || shared.isEmpty())) errors.add(Msg.format("tier.monotype"));
        return errors;
    }

    /** Resume lisible des regles d'un tier (GUI). */
    public static List<String> describe(TierRules r) {
        List<String> lore = new ArrayList<>();
        if (!r.description.isEmpty()) lore.add("&7" + r.description);
        lore.add("&7Taille d'équipe : &f" + r.minTeamSize + "-" + r.maxTeamSize);
        if (r.levelCap > 0) lore.add("&7Niveau maximum : &f" + r.levelCap);
        if (r.requireSharedType) lore.add("&7Type commun obligatoire (Monotype)");
        if (r.requireUnevolved) lore.add("&7Pokémon non évolués uniquement");
        if (r.requireNotFullyEvolved) lore.add("&7Pokémon pouvant encore évoluer uniquement");
        if (r.speciesClause) lore.add("&7Species Clause : une seule fois chaque espèce");
        if (r.itemClause) lore.add("&7Item Clause : objets tenus uniques");
        lore.add("&7Pokémon bannis : &f" + r.bannedPokemon.size());
        int shown = 0;
        StringBuilder line = new StringBuilder("&8");
        for (String b : r.bannedPokemon) {
            if (shown >= 24) {
                line.append("...");
                break;
            }
            line.append(b).append(", ");
            shown++;
            if (shown % 6 == 0) {
                lore.add(line.toString());
                line = new StringBuilder("&8");
            }
        }
        if (line.length() > 2) lore.add(line.toString());
        if (!r.bannedItems.isEmpty()) lore.add("&7Objets bannis : &f" + String.join(", ", r.bannedItems));
        if (!r.bannedAbilities.isEmpty()) lore.add("&7Talents bannis : &f" + String.join(", ", r.bannedAbilities));
        if (!r.bannedMoves.isEmpty()) lore.add("&7Capacités bannies : &f" + String.join(", ", r.bannedMoves));
        return lore;
    }
}
