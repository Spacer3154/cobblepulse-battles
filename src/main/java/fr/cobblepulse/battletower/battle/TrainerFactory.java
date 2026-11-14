package fr.cobblepulse.battletower.battle;

import com.cobblemon.mod.common.api.abilities.PotentialAbility;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.types.tera.TeraType;
import com.cobblemon.mod.common.api.types.tera.TeraTypes;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.core.ItemBuilder;
import fr.cobblepulse.battletower.data.MonSpec;
import fr.cobblepulse.battletower.data.TrainerSpec;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Construit les Pokemon des dresseurs a partir de l'editeur (MonSpec) et valide les equipes. */
public final class TrainerFactory {
    private TrainerFactory() {}

    public static Stats stat(String id) {
        return switch (id) {
            case "hp" -> Stats.HP;
            case "atk" -> Stats.ATTACK;
            case "def" -> Stats.DEFENCE;
            case "spa" -> Stats.SPECIAL_ATTACK;
            case "spd" -> Stats.SPECIAL_DEFENCE;
            default -> Stats.SPEED;
        };
    }

    public static String id(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Cree le Pokemon decrit par la fiche (erreurs ajoutees a la liste, Pokemon null si espece inconnue). */
    public static Pokemon create(MonSpec spec, List<String> errors) {
        spec.normalize();
        Species species = PokemonSpecies.getByName(id(spec.species));
        if (species == null) {
            errors.add("Espece inconnue : " + spec.species);
            return null;
        }
        StringBuilder props = new StringBuilder(species.getName().toLowerCase(Locale.ROOT).replace(" ", ""));
        props.append(" level=").append(spec.level);
        if (!spec.form.isBlank()) {
            for (String aspect : spec.form.split("[,\\s]+")) if (!aspect.isBlank()) props.append(' ').append(aspect.trim().toLowerCase(Locale.ROOT));
        }
        if (!spec.nature.isBlank()) props.append(" nature=").append(id(spec.nature));
        if (!spec.ability.isBlank()) props.append(" ability=").append(id(spec.ability));
        if (!spec.gender.isBlank()) props.append(" gender=").append(spec.gender.toLowerCase(Locale.ROOT));
        props.append(" shiny=").append(spec.shiny ? "yes" : "no");
        Pokemon pk;
        try {
            pk = PokemonProperties.Companion.parse(props.toString()).create();
        } catch (Throwable t) {
            errors.add("Creation impossible (" + spec.species + ") : " + t.getMessage());
            return null;
        }
        for (String st : MonSpec.STATS) {
            try {
                pk.setIV(stat(st), spec.iv(st));
                pk.setEV(stat(st), spec.ev(st));
            } catch (Throwable t) {
                errors.add("IV/EV invalides pour " + spec.species + " : " + t.getMessage());
            }
        }
        int evTotal = 0;
        for (String st : MonSpec.STATS) evTotal += spec.ev(st);
        if (evTotal > 510) errors.add(spec.species + " : total d'EV " + evTotal + " > 510 (accepte, mais hors regles officielles)");
        if (!spec.nickname.isBlank()) pk.setNickname(Component.literal(spec.nickname));
        if (!spec.heldItem.isBlank()) {
            Item item = ItemBuilder.item(spec.heldItem.contains(":") ? spec.heldItem : "cobblemon:" + spec.heldItem, Items.AIR);
            if (item == Items.AIR) errors.add(spec.species + " : objet inconnu " + spec.heldItem);
            else pk.swapHeldItem(new ItemStack(item), false, false);
        }
        if (!spec.moves.isEmpty()) {
            pk.getMoveSet().clear();
            int slot = 0;
            for (MonSpec.MoveSpec ms : spec.moves) {
                MoveTemplate tpl = Moves.getByName(id(ms.id));
                if (tpl == null) {
                    errors.add(spec.species + " : capacite inconnue " + ms.id);
                    continue;
                }
                int stages = 0;
                if (ms.ppMax > 0 && tpl.getPp() > 0) {
                    stages = (int) Math.round((ms.ppMax / (double) tpl.getPp() - 1.0) / 0.2);
                    stages = Math.max(0, Math.min(3, stages));
                }
                Move mv = tpl.create();
                mv.setRaisedPpStages(stages);
                int max = mv.getMaxPp();
                mv.setCurrentPp(ms.pp < 0 ? max : Math.max(0, Math.min(max, ms.pp)));
                pk.getMoveSet().setMove(slot++, mv);
                if (slot >= 4) break;
            }
        }
        if (!spec.teraType.isBlank()) {
            try {
                TeraType tt = TeraTypes.get(id(spec.teraType));
                if (tt != null) pk.setTeraType(tt);
                else errors.add(spec.species + " : type Tera inconnu " + spec.teraType);
            } catch (Throwable t) {
                errors.add(spec.species + " : type Tera invalide " + spec.teraType);
            }
        }
        pk.heal();
        return pk;
    }

    public static List<Pokemon> team(TrainerSpec trainer, List<String> errors) {
        List<Pokemon> out = new ArrayList<>();
        for (MonSpec m : trainer.team) {
            Pokemon pk = create(m, errors);
            if (pk != null) out.add(pk);
        }
        return out;
    }

    /** Verifie une fiche de dresseur sans la sauvegarder (editeur). */
    public static List<String> validate(TrainerSpec t) {
        List<String> errors = new ArrayList<>();
        t.normalize();
        if (t.team.isEmpty()) errors.add("Equipe vide : ajoutez au moins un Pokemon.");
        if (t.doubles() && t.team.size() < 2) errors.add("Format Duo : il faut au moins 2 Pokemon.");
        team(t, errors);
        return errors;
    }

    /** Talents possibles d'une forme (pour l'editeur et l'IA). */
    public static Set<String> possibleAbilities(FormData form) {
        Set<String> out = new LinkedHashSet<>();
        if (form == null) return out;
        try {
            Object pool = form.getAbilities();
            if (pool instanceof Iterable<?> it) {
                for (Object o : it) if (o instanceof PotentialAbility pa) out.add(id(pa.getTemplate().getName()));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static FormData form(Pokemon pk) {
        return pk.getForm();
    }

    public static void logErrors(String who, List<String> errors) {
        if (!errors.isEmpty()) Battletower.LOG.warn("[Battletower] {} : {}", who, String.join(" | ", errors));
    }
}
