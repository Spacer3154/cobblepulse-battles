package fr.cobblepulse.battletower.integration;

import fr.cobblepulse.battletower.Battletower;
import net.fabricmc.loader.api.FabricLoader;

/** Enregistre les placeholders %cobblepulse_bt:...% si Text Placeholder API est installe. */
public final class PlaceholderBridge {
    private PlaceholderBridge() {}

    public static boolean present() {
        return FabricLoader.getInstance().isModLoaded("placeholder-api");
    }

    public static void init() {
        if (!present()) {
            Battletower.LOG.info("[Battletower] Text Placeholder API absent : placeholders non exposes");
            return;
        }
        try {
            PlaceholderHook.register();
            Battletower.LOG.info("[Battletower] Placeholders enregistres (%cobblepulse_bt:top_1_name% ...)");
        } catch (Throwable t) {
            Battletower.LOG.warn("[Battletower] Enregistrement des placeholders impossible : {}", t.toString());
        }
    }
}
