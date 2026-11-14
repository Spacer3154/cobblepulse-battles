package fr.cobblepulse.battletower.battle;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.data.TrainerSpec;

/**
 * Modificateurs de terrain du dresseur : meteo et champ permanents (y compris le Brouillard Epais, absent de la
 * generation 9 et recree ici : precision x0,6). Ils sont (re)poses en debut de tour via le flux Showdown du combat,
 * avec une duree infinie ; une meteo/un champ pose par un Pokemon reste prioritaire jusqu'a sa fin.
 */
public final class FieldModifiers {
    private FieldModifiers() {}

    public static String weatherId(String w) {
        return switch (w == null ? "none" : w) {
            case "sun" -> "sunnyday";
            case "rain" -> "raindance";
            case "sand" -> "sandstorm";
            case "snow" -> "snow";
            case "fog" -> "btfog";
            default -> null;
        };
    }

    public static String terrainId(String t) {
        return switch (t == null ? "none" : t) {
            case "electric" -> "electricterrain";
            case "grassy" -> "grassyterrain";
            case "psychic" -> "psychicterrain";
            case "misty" -> "mistyterrain";
            default -> null;
        };
    }

    static String fogDefinition() {
        return "if(!battle.dex.data.Conditions.btfog){battle.dex.data.Conditions.btfog={name:'Fog',effectType:'Weather',"
                + "onModifyAccuracyPriority:-1,onModifyAccuracy:function(acc){if(typeof acc!=='number')return;return this.chainModify([2458,4096]);},"
                + "onFieldStart:function(){this.add('-weather','Fog');},onFieldResidualOrder:1,"
                + "onFieldResidual:function(){this.add('-weather','Fog','[upkeep]');this.eachEvent('Weather');},"
                + "onFieldEnd:function(){this.add('-weather','none');}};}";
    }

    /** Script Showdown qui pose meteo/champ s'ils sont absents. */
    public static String script(TrainerSpec spec) {
        String w = weatherId(spec.weather);
        String t = terrainId(spec.terrain);
        if (w == null && t == null) return null;
        StringBuilder js = new StringBuilder("(function(){try{");
        if ("btfog".equals(w)) js.append(fogDefinition());
        if (w != null) {
            js.append("if(!battle.field.weather){battle.field.setWeather('").append(w).append("','debug');")
                    .append("if(battle.field.weatherState){battle.field.weatherState.duration=0;}}");
        }
        if (t != null) {
            js.append("if(!battle.field.terrain){battle.field.setTerrain('").append(t).append("','debug');")
                    .append("if(battle.field.terrainState){battle.field.terrainState.duration=0;}}");
        }
        js.append("}catch(e){}})()");
        return js.toString();
    }

    public static void enforce(PokemonBattle battle, TrainerSpec spec) {
        String js = script(spec);
        if (js == null || battle == null || battle.getEnded()) return;
        try {
            battle.writeShowdownAction(">eval " + js);
        } catch (Throwable t) {
            Battletower.LOG.warn("[Battletower] Pose de la meteo/du champ impossible : {}", t.toString());
        }
    }
}
