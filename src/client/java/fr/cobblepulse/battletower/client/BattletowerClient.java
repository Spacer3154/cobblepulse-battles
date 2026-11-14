package fr.cobblepulse.battletower.client;

import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.core.Json;
import fr.cobblepulse.battletower.data.TrainerSpec;
import fr.cobblepulse.battletower.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** Cote client : editeur graphique des dresseurs et apercu 3D des skins. */
public final class BattletowerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(Payloads.OpenEditor.TYPE, (payload, ctx) -> ctx.client().execute(() -> {
            TrainerSpec spec;
            try {
                spec = Json.GSON.fromJson(payload.trainerJson(), TrainerSpec.class);
            } catch (Exception e) {
                spec = new TrainerSpec();
            }
            Minecraft.getInstance().setScreen(new TrainerEditorScreen(payload.kind(), payload.id(), spec, payload.metaJson()));
        }));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.EditorResult.TYPE, (payload, ctx) -> ctx.client().execute(() -> {
            if (Minecraft.getInstance().screen instanceof TrainerEditorScreen s) s.onResult(payload);
        }));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.SkinData.TYPE, (payload, ctx) -> ctx.client().execute(() -> SkinCache.receive(payload)));
        if ("true".equals(System.getProperty("cobblepulse_battletower.clienttest"))) ClientSelfTest.install();
        Battletower.LOG.info("[Battletower] Client initialise");
    }
}
