package fr.cobblepulse.battles.ranked;

import fr.cobblepulse.battletower.config.TomlFile;

/** Calcul de l'Elo (formule de Pokemon Showdown, ou K personnalise). */
public final class Elo {
    private Elo() {}

    /** Esperance de victoire de {@code elo} contre {@code foe}. */
    public static double expected(int elo, int foe) {
        return 1.0 / (1.0 + Math.pow(10, (foe - elo) / 400.0));
    }

    /** K dynamique de Pokemon Showdown (ladders). */
    public static double showdownK(int elo, double score) {
        double k = 50;
        if (elo < 1200) {
            if (score < 0.5) k = 10 + (elo - 1000) * 40.0 / 200.0;
            else if (score > 0.5) k = 90 - (elo - 1000) * 40.0 / 200.0;
        } else if (elo > 1350 && elo <= 1600) {
            k = 40;
        } else if (elo > 1600) {
            k = 32;
        }
        return Math.max(0, k);
    }

    public static double customK(int elo, int games, int streak) {
        TomlFile t = RankedConfig.elo;
        double k;
        if (games < t.i("personnalise.parties_debutant", 30)) {
            k = t.i("personnalise.k_debutant", 50);
        } else {
            k = 12;
            for (TomlFile band : t.tables("personnalise.palier")) {
                if (elo <= band.i("elo_max", 999999)) {
                    k = band.d("k", 12);
                    break;
                }
            }
        }
        if (t.b("personnalise.bonus_serie", false)) {
            if (streak >= 5) k += t.i("personnalise.bonus_serie_5", 5);
            else if (streak >= 3) k += t.i("personnalise.bonus_serie_3", 3);
        }
        return k;
    }

    /**
     * Nouvel Elo apres un match. {@code score} : 1 victoire, 0 defaite, 0.5 nul.
     */
    public static int next(int elo, int foe, double score, int games, int streak) {
        boolean showdown = !"personnalise".equalsIgnoreCase(RankedConfig.elo.str("systeme", "showdown"));
        double k = showdown ? showdownK(elo, score) : customK(elo, games, streak);
        double e = expected(elo, foe);
        int out = (int) Math.round(elo + k * (score - e));
        return Math.max(RankedConfig.floorElo(), out);
    }
}
