package fr.cobblepulse.battletower.client;

import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Suggestions a partir des registres Cobblemon synchronises sur le client. */
final class ClientRegistries {
    private ClientRegistries() {}

    private static String id(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static List<String> species(String prefix, int max) {
        String p = id(prefix);
        List<String> out = new ArrayList<>();
        if (p.isEmpty()) return out;
        for (Species s : PokemonSpecies.getSpecies()) {
            String n = id(s.getName());
            if (n.startsWith(p)) out.add(n);
            if (out.size() >= max) break;
        }
        return out;
    }

    static List<String> moves(String prefix, int max) {
        String p = id(prefix);
        List<String> out = new ArrayList<>();
        if (p.isEmpty()) return out;
        for (String n : Moves.names()) {
            String i = id(n);
            if (i.startsWith(p)) out.add(i);
            if (out.size() >= max) break;
        }
        return out;
    }
}
