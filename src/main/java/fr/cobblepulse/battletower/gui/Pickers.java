package fr.cobblepulse.battletower.gui;

import fr.cobblepulse.battletower.core.Msg;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * Selecteurs des editeurs : objet (tous les objets du jeu et des mods, avec recherche) et Pokemon (espece, forme,
 * niveau, chromatique, IV, nature, talent, Ball, objet tenu...). Avec le mod cote client, un ecran dedie ;
 * sans le mod, la saisie de l'identifiant ou de la fiche dans le chat.
 */
public final class Pickers {
    private Pickers() {}

    /** Identifiant d'objet choisi (ex. cobblemon:poke_ball). */
    public static void item(ServerPlayer p, String titleKey, Consumer<String> handler, Runnable onCancel) {
        ChatInput.pick(p, "item", titleKey, "general.ask_item_id", "", handler, onCancel);
    }



    static String title(String key) {
        return Msg.formatBare(key);
    }
}
