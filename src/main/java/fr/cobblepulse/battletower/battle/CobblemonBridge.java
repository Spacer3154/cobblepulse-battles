package fr.cobblepulse.battletower.battle;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import fr.cobblepulse.battletower.Battletower;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Acces a l'API Cobblemon : equipes, PC, combats en cours. */
public final class CobblemonBridge {
    /** Resume d'un Pokemon (validation des tiers). */
    public record MonInfo(String species, String speciesId, String formId, int level, String heldItem, String ability,
                          List<String> moves, Set<String> types, boolean hasPreEvolution, boolean canEvolve, boolean shiny) {}

    private CobblemonBridge() {}

    public static PlayerPartyStore party(ServerPlayer p) {
        return Cobblemon.INSTANCE.getStorage().getParty(p);
    }

    public static List<Pokemon> partyList(ServerPlayer p) {
        List<Pokemon> out = new ArrayList<>();
        for (Pokemon pk : party(p)) if (pk != null) out.add(pk);
        return out;
    }

    public static void heal(ServerPlayer p) {
        try {
            party(p).heal();
        } catch (Throwable t) {
            Battletower.LOG.warn("[Battletower] Soin de l'equipe impossible : {}", t.toString());
        }
    }

    public static boolean isInBattle(ServerPlayer p) {
        try {
            return BattleRegistry.getBattleByParticipatingPlayer(p) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static PokemonBattle battle(UUID id) {
        return id == null ? null : BattleRegistry.getBattle(id);
    }

    public static boolean battleActive(UUID id) {
        PokemonBattle b = battle(id);
        return b != null && !b.getEnded();
    }

    public static void stopBattle(UUID id) {
        try {
            PokemonBattle b = battle(id);
            if (b != null && !b.getEnded()) b.stop();
        } catch (Throwable t) {
            Battletower.LOG.warn("[Battletower] Arret du combat {} impossible : {}", id, t.toString());
        }
    }

    /** Victoire forcee d'un cote (auto-test) : "p1" ou "p2". */
    public static void forceWin(UUID id, String side) {
        PokemonBattle b = battle(id);
        if (b != null) b.writeShowdownAction(">forcewin " + side);
    }

    /** Ouvre le PC du joueur a distance (commande /pc de Cobblemon executee pour lui). */
    public static boolean openPc(ServerPlayer p) {
        MinecraftServer s = p.server;
        try {
            s.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4).withSuppressedOutput(), "pc");
            return true;
        } catch (Throwable t) {
            Battletower.LOG.warn("[Battletower] Ouverture du PC impossible : {}", t.toString());
            return false;
        }
    }

    public static List<MonInfo> teamInfo(ServerPlayer p) {
        List<MonInfo> out = new ArrayList<>();
        for (Pokemon pk : partyList(p)) out.add(info(pk));
        return out;
    }

    public static MonInfo info(Pokemon pk) {
        Species sp = pk.getSpecies();
        FormData form = pk.getForm();
        String speciesId = norm(sp.getName());
        String formName = form == null ? "" : form.getName();
        String formId = formName == null || formName.isEmpty() || formName.equalsIgnoreCase("normal") ? speciesId : norm(sp.getName() + formName);
        ItemStack held = pk.heldItem();
        String heldId = held == null || held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        String ability = "";
        try {
            ability = pk.getAbility().getName();
        } catch (Throwable ignored) {
        }
        List<String> moves = new ArrayList<>();
        try {
            for (Move m : pk.getMoveSet().getMoves()) moves.add(m.getName());
        } catch (Throwable ignored) {
        }
        Set<String> types = new LinkedHashSet<>();
        for (ElementalType t : pk.getTypes()) types.add(t.getName().toLowerCase(Locale.ROOT));
        boolean pre = false;
        boolean evo = false;
        try {
            pre = form != null && form.getPreEvolution() != null;
            evo = form != null && !form.getEvolutions().isEmpty();
        } catch (Throwable ignored) {
        }
        return new MonInfo(sp.getName(), speciesId, formId, pk.getLevel(), heldId, ability, moves, types, pre, evo, pk.getShiny());
    }

    /** L'equipe est-elle Monotype (au moins un type commun) ? */
    public static boolean monotype(ServerPlayer p) {
        Set<String> shared = null;
        for (MonInfo m : teamInfo(p)) {
            if (shared == null) shared = new LinkedHashSet<>(m.types());
            else shared.retainAll(m.types());
        }
        return shared != null && !shared.isEmpty();
    }

    public static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static ItemStack icon(Pokemon pk) {
        try {
            return PokemonItem.from(pk);
        } catch (Throwable t) {
            return new ItemStack(Items.EGG);
        }
    }

    public static ItemStack speciesIcon(String speciesName) {
        try {
            Species sp = PokemonSpecies.getByName(norm(speciesName));
            if (sp != null) return PokemonItem.from(sp);
        } catch (Throwable ignored) {
        }
        return new ItemStack(Items.EGG);
    }

    /** Ajoute un Pokemon a l'equipe d'un joueur (auto-test). */
    public static void givePokemon(ServerPlayer p, String properties) {
        Pokemon pk = PokemonProperties.Companion.parse(properties).create();
        party(p).add(pk);
    }
}
