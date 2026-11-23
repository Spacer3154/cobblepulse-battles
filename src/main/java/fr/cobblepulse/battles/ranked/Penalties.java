package fr.cobblepulse.battles.ranked;

import fr.cobblepulse.battletower.config.TomlFile;

/** Penalites de fuite (deconnexion ou abandon en classe) : interdiction de file croissante, avec decroissance. */
public final class Penalties {
    private Penalties() {}

    public static void decay(RankedProfile p) {
        TomlFile t = RankedConfig.general;
        if (p.flees <= 0) {
            p.lastFleeDecay = System.currentTimeMillis();
            return;
        }
        long every = Math.max(1, t.i("penalites.decroissance_heures", 24)) * 3_600_000L;
        long now = System.currentTimeMillis();
        if (p.lastFleeDecay == 0) p.lastFleeDecay = now;
        long steps = (now - p.lastFleeDecay) / every;
        if (steps > 0) {
            p.flees = (int) Math.max(0, p.flees - steps);
            p.lastFleeDecay += steps * every;
            RankedStore.mark(p);
        }
    }

    /** Enregistre une fuite et applique l'interdiction correspondante. @return minutes d'interdiction */
    public static int flee(RankedProfile p, boolean early) {
        TomlFile t = RankedConfig.general;
        if (!t.b("penalites.actif", true)) return 0;
        decay(p);
        p.flees++;
        int minutes = 0;
        for (TomlFile tier : t.tables("penalites.palier")) {
            if (p.flees >= tier.i("fuites_min", 1) && p.flees <= tier.i("fuites_max", 999)) {
                minutes = tier.i("minutes", 0);
                break;
            }
        }
        if (early) minutes = (int) Math.round(minutes * t.d("penalites.abandon_precoce_multiplicateur", 1.5));
        if (minutes > 0) {
            p.banUntil = Math.max(p.banUntil, System.currentTimeMillis() + minutes * 60_000L);
            p.banReason = "fuite";
        }
        RankedStore.mark(p);
        return minutes;
    }
}
