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

    /** Corps JavaScript (variable {@code battle} disponible) qui pose meteo/champ s'ils sont absents. */
    public static String script(TrainerSpec spec) {
        String w = weatherId(spec.weather);
        String t = terrainId(spec.terrain);
        if (w == null && t == null) return null;
        StringBuilder js = new StringBuilder();
        if ("btfog".equals(w)) js.append(fogDefinition());
        if (w != null) {
            js.append("if(!battle.field.weather){battle.field.setWeather('").append(w).append("','debug');")
                    .append("if(battle.field.weatherState){battle.field.weatherState.duration=0;}}");
        }
        if (t != null) {
            js.append("if(!battle.field.terrain){battle.field.setTerrain('").append(t).append("','debug');")
                    .append("if(battle.field.terrainState){battle.field.terrainState.duration=0;}}");
        }
        return js.toString();
    }

    /**
     * Pose meteo/champ. Le flux de combat Showdown embarque par Cobblemon n'accepte pas la commande {@code >eval} :
     * on passe donc par le contexte JavaScript du service Showdown (meme fil que Cobblemon, le fil serveur) et on agit
     * directement sur l'objet {@code Battle} du combat, puis on diffuse les messages produits.
     */
    public static void enforce(PokemonBattle battle, TrainerSpec spec) {
        String js = script(spec);
        if (js == null || battle == null || battle.getEnded()) return;
        String r = runInBattle(battle.getBattleId(), js);
        if (!"ok".equals(r)) Battletower.LOG.warn("[Battletower] Pose de la meteo/du champ impossible : {}", r);
    }

    /** Meme chose au tick suivant (appel depuis un traitement de message Showdown en cours). */
    public static void enforceLater(PokemonBattle battle, TrainerSpec spec) {
        if (script(spec) == null) return;
        fr.cobblepulse.battletower.core.Scheduler.later(1, () -> enforce(battle, spec));
    }

    private static volatile java.lang.reflect.Method getContext;
    private static volatile java.lang.reflect.Method eval;

    /** Execute {@code body} avec {@code battle} = l'objet Battle Showdown du combat. Renvoie "ok" ou une erreur. */
    public static String runInBattle(java.util.UUID battleId, String body) {
        try {
            Object svc = com.cobblemon.mod.common.battles.runner.ShowdownService.Companion.getService();
            if (getContext == null) getContext = svc.getClass().getMethod("getContext");
            Object ctx = getContext.invoke(svc);
            if (ctx == null) return "contexte Showdown indisponible";
            if (eval == null) eval = ctx.getClass().getMethod("eval", String.class, CharSequence.class);
            String code = "(function(){var s=(typeof battleMap!=='undefined')?battleMap.get('" + battleId + "'):null;"
                    + "if(!s||!s.battle)return 'combat introuvable';var battle=s.battle;"
                    + "try{" + body + "}catch(e){return 'erreur JS : '+e;}"
                    + "battle.sendUpdates();return 'ok';})()";
            Object v = eval.invoke(ctx, "js", code);
            return String.valueOf(v);
        } catch (NoSuchMethodException e) {
            return "service Showdown non Graal (" + e.getMessage() + ")";
        } catch (java.lang.reflect.InvocationTargetException e) {
            return String.valueOf(e.getCause());
        } catch (Throwable t) {
            return t.toString();
        }
    }
}
