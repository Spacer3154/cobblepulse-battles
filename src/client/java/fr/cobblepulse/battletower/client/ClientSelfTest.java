package fr.cobblepulse.battletower.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.core.Json;
import fr.cobblepulse.battletower.data.ShowdownFormat;
import fr.cobblepulse.battletower.data.TrainerSpec;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Auto-test visuel (CI) : ouvre l'editeur sur l'ecran titre, capture chaque onglet puis ferme le jeu. */
final class ClientSelfTest {
    private static int ticks;
    private static int start = -1;
    private static TrainerEditorScreen screen;

    private ClientSelfTest() {}

    static void install() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientSelfTest::tick);
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), c -> Battletower.LOG.info("[Battletower][clienttest] {}", c.getString()));
    }

    private static String meta() {
        JsonObject o = new JsonObject();
        o.addProperty("title", "Etage 12 - Editeur du dresseur");
        JsonArray skins = new JsonArray();
        try (InputStream in = ClientSelfTest.class.getResourceAsStream("/assets/cobblepulse_battletower/textures/entity/npc/index.txt")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] p = line.split("\\|");
                    JsonObject s = new JsonObject();
                    s.addProperty("ref", "builtin:" + p[0].trim());
                    s.addProperty("label", p.length > 2 ? p[2].trim() : p[0].trim());
                    s.addProperty("slim", p.length > 1 && "slim".equalsIgnoreCase(p[1].trim()));
                    skins.add(s);
                }
            }
        } catch (Exception ignored) {
        }
        o.add("skins", skins);
        JsonArray tiers = new JsonArray();
        for (String[] t : new String[][]{{"ag", "AG (Anything Goes)"}, {"ou", "OU (OverUsed)"}, {"monotype", "Monotype"}, {"lc", "Little Cup"}}) {
            JsonObject j = new JsonObject();
            j.addProperty("id", t[0]);
            j.addProperty("name", t[1]);
            tiers.add(j);
        }
        o.add("tiers", tiers);
        return Json.COMPACT.toJson(o);
    }

    private static void tick(Minecraft mc) {
        ticks++;
        if (ticks % 200 == 0) {
            Battletower.LOG.info("[Battletower][clienttest] tick {} ecran={} overlay={}", ticks,
                    mc.screen == null ? "aucun" : mc.screen.getClass().getSimpleName(), mc.getOverlay() != null);
        }
        if (start < 0) {
            // Ecran titre, ou tout autre ecran d'accueil (premier lancement : accessibilite, avertissements...)
            if (mc.getOverlay() == null && ((mc.screen instanceof TitleScreen && ticks > 60) || (mc.screen != null && ticks > 400))) {
                start = ticks;
                Battletower.LOG.info("[Battletower][clienttest] debut sur {}", mc.screen.getClass().getSimpleName());
            } else if (ticks > 6000) {
                Battletower.LOG.error("[Battletower][clienttest] ecran titre jamais atteint");
                mc.stop();
            }
            return;
        }
        int t = ticks - start;
        try {
            if (t == 20) {
                TrainerSpec spec = new TrainerSpec();
                spec.name = "Lysandre";
                spec.title = "As";
                spec.ai = "HARD";
                spec.tier = "ou";
                spec.weather = "rain";
                spec.levelScale = 50;
                spec.skin = "builtin:veteran";
                spec.team.addAll(ShowdownFormat.parse("Garchomp @ Life Orb\nAbility: Rough Skin\nLevel: 50\nTera Type: Steel\n"
                        + "EVs: 252 Atk / 4 SpD / 252 Spe\nJolly Nature\n- Earthquake\n- Dragon Claw\n- Swords Dance\n- Stone Edge\n\n"
                        + "Rotom-Wash @ Leftovers\nAbility: Levitate\nLevel: 50\nEVs: 252 HP / 252 Def / 4 SpA\nBold Nature\n- Hydro Pump\n- Volt Switch\n"
                        + "- Will-O-Wisp\n- Protect\n"));
                screen = new TrainerEditorScreen("floor", "12", spec, meta());
                mc.setScreen(screen);
            } else if (t == 50) {
                shot(mc, "bt_editor_general");
                screen.setTab(1);
            } else if (t == 80) {
                shot(mc, "bt_editor_team");
                screen.setTab(2);
            } else if (t == 120) {
                shot(mc, "bt_editor_skins");
                screen.spec.skin = "builtin:ace_trainer_f";
                screen.setTab(2);
            } else if (t == 150) {
                shot(mc, "bt_editor_skins_slim");
            } else if (t == 170) {
                Battletower.LOG.info("[Battletower][clienttest] CLIENT TEST DONE");
                mc.stop();
            }
        } catch (Throwable e) {
            Battletower.LOG.error("[Battletower][clienttest] erreur", e);
            mc.stop();
        }
    }
}
