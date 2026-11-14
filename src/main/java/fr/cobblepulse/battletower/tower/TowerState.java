package fr.cobblepulse.battletower.tower;

import fr.cobblepulse.battletower.core.Loc;
import fr.cobblepulse.battletower.data.RunState;

import java.util.LinkedHashMap;
import java.util.Map;

/** Etat persistant de la tour (cle "state") : ouverture, Boss du Jour, ascensions en cours. */
public final class TowerState {
    public boolean open = true;
    /** Jour (yyyy-MM-dd) de la derniere rotation du Boss du Jour. */
    public String rotationDay = "";
    public String currentBossId = "";
    /** Ascensions et tentatives en cours (reprise apres redemarrage). */
    public Map<String, RunState> runs = new LinkedHashMap<>();
    /** Point de retour de chaque joueur en cours d'ascension (si aucun hall n'est defini). */
    public Map<String, Loc> returns = new LinkedHashMap<>();

    public void normalize() {
        if (rotationDay == null) rotationDay = "";
        if (currentBossId == null) currentBossId = "";
        if (runs == null) runs = new LinkedHashMap<>();
        if (returns == null) returns = new LinkedHashMap<>();
    }
}
