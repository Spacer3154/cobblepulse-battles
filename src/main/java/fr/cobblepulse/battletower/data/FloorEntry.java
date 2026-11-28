package fr.cobblepulse.battletower.data;

import fr.cobblepulse.battletower.core.Loc;

/** Un etage physique de la tour. */
public final class FloorEntry {
    public int floor;
    /** Point d'arrivee du joueur (coordonnees + monde). */
    public Loc arrival;
    /** Position et identifiant de l'entite PNJ dans le monde. */
    public Loc npcLocation;
    public String npcUuid;
    public TrainerSpec trainer = new TrainerSpec();
    /** Tour a laquelle appartient l'etage (renseigne au chargement, non enregistre). */
    public transient String tower = "principale";

    public FloorEntry() {}

    public FloorEntry(int floor) {
        this.floor = floor;
        this.trainer.name = "Dresseur de l'étage " + floor;
    }
}
