package fr.cobblepulse.battletower.data;

/** Ascension en cours d'un joueur (persistee pour la reprise apres redemarrage). */
public final class RunState {
    public enum Phase { FLOOR, QUEUED, COUNTDOWN, BATTLE, TRANSIT, BOSS }

    public String uuid;
    public String name;
    public int floor = 1;
    public Phase phase = Phase.FLOOR;
    public long startedAt;
    /** Temps d'ascension actif cumule (hors file d'attente et hors arret du serveur). */
    public long activeMs;
    /** Debut de la periode active en cours (0 = chrono en pause). */
    public long activeSince;
    public long floorSince;
    public int noKoStreak;
    public int wins;
    /** Tentative du Boss du Jour (et non une ascension). */
    public boolean daily;
    public String dailyBossId;
    /** Deconnexion hors combat : fin du delai de grace. */
    public long graceUntil;
    /** Ascension interrompue par un arret/crash du serveur : reprise a la reconnexion, sans penalite. */
    public boolean serverInterrupted;
    public transient java.util.UUID battleId;
    public transient long battleStartedAt;
    public transient boolean anyFainted;
    public transient long protectUntil;
    public transient long countdownToken;

    public void resumeClock(long now) {
        if (activeSince == 0) activeSince = now;
    }

    public void pauseClock(long now) {
        if (activeSince > 0) {
            activeMs += Math.max(0, now - activeSince);
            activeSince = 0;
        }
    }

    public long elapsed(long now) {
        return activeMs + (activeSince > 0 ? Math.max(0, now - activeSince) : 0);
    }
}
