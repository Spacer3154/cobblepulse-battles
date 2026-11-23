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
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Data.TYPE, (payload, ctx) -> ctx.client().execute(() -> receive(payload.kind(), payload.json())));
        // les menus couvrent le chat : les messages du serveur s'affichent dans l'ecran ouvert
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof MenuScreen m) m.notice(message);
            else if (mc.screen instanceof Overlay ov && ov.parentScreen() instanceof MenuScreen m) m.notice(message);
        });
        if ("true".equals(System.getProperty("cobblepulse_battles.clienttest"))) ClientSelfTest.install();
        Battletower.LOG.info("[Battles] Client initialise");
    }

    /** Ecrans pilotes par le serveur : handlers supplementaires (Ranked...). */
    private static final java.util.Map<String, java.util.function.Consumer<com.google.gson.JsonObject>> KINDS = new java.util.HashMap<>();

    public static void on(String kind, java.util.function.Consumer<com.google.gson.JsonObject> handler) {
        KINDS.put(kind, handler);
    }

    static void showMenu(Minecraft mc, com.google.gson.JsonObject o) {
        if (mc.screen instanceof MenuScreen m) m.update(o);
        else if (mc.screen instanceof Overlay ov) {
            if (ov.parentScreen() instanceof MenuScreen m) m.update(o);
            else ov.setParentScreen(new MenuScreen(o));
        } else mc.setScreen(new MenuScreen(o));
    }

    private static net.minecraft.network.chat.Component json(Minecraft mc, String raw) {
        try {
            var conn = mc.getConnection();
            var c = conn == null ? null : net.minecraft.network.chat.Component.Serializer.fromJson(raw, conn.registryAccess());
            if (c != null) return c;
        } catch (Exception ignored) {
        }
        return net.minecraft.network.chat.Component.literal(raw);
    }

    static void receive(String kind, String raw) {
        Minecraft mc = Minecraft.getInstance();
        com.google.gson.JsonObject o;
        try {
            o = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            Battletower.LOG.warn("[Battles] Donnees illisibles ({}) : {}", kind, e.toString());
            return;
        }
        switch (kind) {
            case "menu" -> showMenu(mc, o);
            case "menu_close" -> {
                if (mc.screen instanceof MenuScreen || mc.screen instanceof Overlay) mc.setScreen(null);
            }
            case "prompt" -> {
                net.minecraft.client.gui.screens.Screen parent = mc.screen instanceof MenuScreen ? mc.screen : null;
                mc.setScreen(new PromptScreen(parent, json(mc, Ui.str(o, "text", ""))));
            }
            case "pick" -> {
                net.minecraft.client.gui.screens.Screen parent = mc.screen instanceof MenuScreen ? mc.screen : null;
                mc.setScreen(new ItemPickerScreen(parent, json(mc, Ui.str(o, "title", "")), Ui.str(o, "value", "")));
            }
            default -> {
                var h = KINDS.get(kind);
                if (h != null) h.accept(o);
            }
        }
    }
}
