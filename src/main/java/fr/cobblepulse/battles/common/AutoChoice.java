package fr.cobblepulse.battles.common;

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.battles.PassActionResponse;
import com.cobblemon.mod.common.battles.ShowdownActionRequest;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.SwitchActionResponse;
import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.battles.Targetable;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import fr.cobblepulse.battletower.Battletower;

import java.util.List;

/**
 * Choix automatique d'un joueur (minuteur de tour ecoule, auto-tests) : premiere capacite utilisable sur la premiere
 * cible, ou premier Pokemon disponible quand un remplacement est force.
 */
public final class AutoChoice {
    private AutoChoice() {}

    /** @return true si une reponse a ete envoyee */
    public static boolean play(BattleActor actor) {
        if (actor == null || !actor.getMustChoose() || actor.getRequest() == null) return false;
        ShowdownActionRequest req = actor.getRequest();
        try {
            List<ShowdownActionResponse> responses = req.iterate(actor.getActivePokemon(),
                    (ActiveBattlePokemon active, ShowdownMoveset ms, Boolean force) -> choose(actor, active, ms, force));
            actor.setActionResponses(responses);
            return true;
        } catch (Throwable t) {
            Battletower.LOG.warn("[Battles] Choix automatique impossible : {}", t.toString());
            return false;
        }
    }

    static ShowdownActionResponse choose(BattleActor actor, ActiveBattlePokemon active, ShowdownMoveset ms, Boolean force) {
        if (Boolean.TRUE.equals(force) || ms == null) {
            for (BattlePokemon bp : actor.getPokemonList()) {
                if (bp.getHealth() <= 0) continue;
                boolean isActive = false;
                for (ActiveBattlePokemon a : actor.getActivePokemon()) if (a.getBattlePokemon() == bp) isActive = true;
                if (!isActive) return new SwitchActionResponse(bp.getUuid());
            }
            return PassActionResponse.INSTANCE;
        }
        for (InBattleMove m : ms.getMoves()) {
            if (!m.canBeUsed()) continue;
            String target = null;
            List<Targetable> t = m.getTargets(active);
            if (t != null && !t.isEmpty()) target = t.get(0).getPNX();
            return new MoveActionResponse(m.getId(), target, null);
        }
        return PassActionResponse.INSTANCE;
    }
}
