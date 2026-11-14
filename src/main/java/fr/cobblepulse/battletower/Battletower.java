package fr.cobblepulse.battletower;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Battletower implements ModInitializer {
    public static final String MOD_ID = "cobblepulse_battletower";
    public static final Logger LOG = LoggerFactory.getLogger("Battletower");

    @Override
    public void onInitialize() {
        LOG.info("[Battletower] Initialise");
    }
}
