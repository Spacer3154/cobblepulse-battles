package fr.cobblepulse.battletower.net;

import com.cobblemon.mod.common.api.types.tera.TeraType;
import com.cobblemon.mod.common.api.types.tera.TeraTypes;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.pokemon.Nature;
import com.cobblemon.mod.common.api.pokemon.Natures;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.battle.TrainerFactory;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.config.TierRules;
import fr.cobblepulse.battletower.core.Json;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Perms;
import fr.cobblepulse.battletower.data.FloorEntry;
import fr.cobblepulse.battletower.data.TrainerSpec;
import fr.cobblepulse.battletower.npc.Skins;
import fr.cobblepulse.battletower.npc.TrainerNpcs;
import fr.cobblepulse.battletower.tower.DailyBoss;
import fr.cobblepulse.battletower.tower.TowerConfig;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Cote serveur de l'editeur graphique : ouverture, validation, enregistrement, skins pour l'apercu. */
public final class EditorService {
    private EditorService() {}

    public static void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(Payloads.OpenEditor.TYPE, Payloads.OpenEditor.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.EditorResult.TYPE, Payloads.EditorResult.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.SkinData.TYPE, Payloads.SkinData.CODEC);
        PayloadTypeRegistry.playC2S().register(Payloads.SaveTrainer.TYPE, Payloads.SaveTrainer.CODEC);
        PayloadTypeRegistry.playC2S().register(Payloads.SkinRequest.TYPE, Payloads.SkinRequest.CODEC);
    }

    public static void registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(Payloads.SaveTrainer.TYPE, (payload, ctx) -> ctx.server().execute(() -> onSave(ctx.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(Payloads.SkinRequest.TYPE, (payload, ctx) -> ctx.server().execute(() -> onSkinRequest(ctx.player(), payload.ref())));
    }

    private static boolean clientReady(ServerPlayer p) {
        if (ServerPlayNetworking.canSend(p, Payloads.OpenEditor.TYPE)) return true;
        Msg.send(p, "editor.client_required");
        return false;
    }

    public static void openFloor(ServerPlayer p, int floor) {
        if (!Perms.admin(p, Perms.ADMIN_NPC)) {
            Msg.send(p, "general.no_permission");
            return;
        }
        FloorEntry fe = TowerConfig.floor(floor);
        if (fe == null) {
            Msg.send(p, "admin.floor_unknown", "floor", floor);
            return;
        }
        if (!clientReady(p)) return;
        ServerPlayNetworking.send(p, new Payloads.OpenEditor("floor", String.valueOf(floor), Json.GSON.toJson(fe.trainer),
                meta(Msg.format("editor.title_floor", "floor", floor))));
    }

    public static void openBoss(ServerPlayer p, String id) {
        if (!Perms.admin(p, Perms.ADMIN_BOSS)) {
            Msg.send(p, "general.no_permission");
            return;
        }
        TrainerSpec t = TowerConfig.boss(id);
        if (t == null) {
            Msg.send(p, "admin.boss_unknown", "id", id);
            return;
        }
        if (!clientReady(p)) return;
        ServerPlayNetworking.send(p, new Payloads.OpenEditor("boss", id.toLowerCase(Locale.ROOT), Json.GSON.toJson(t),
                meta(Msg.format("editor.title_boss", "id", id))));
    }

    /** Listes utiles a l'editeur : skins, tiers, natures, types Tera. */
    static String meta(String title) {
        JsonObject o = new JsonObject();
        o.addProperty("title", title);
        JsonArray skins = new JsonArray();
        for (Skins.SkinInfo s : Skins.builtin()) skins.add(skin(s));
        for (Skins.SkinInfo s : Skins.server()) skins.add(skin(s));
        o.add("skins", skins);
        JsonArray tiers = new JsonArray();
        for (Map.Entry<String, TierRules> e : Cfg.TIERS.entrySet()) {
            JsonObject t = new JsonObject();
            t.addProperty("id", e.getKey());
            t.addProperty("name", e.getValue().displayName);
            tiers.add(t);
        }
        o.add("tiers", tiers);
        JsonArray natures = new JsonArray();
        try {
            for (Nature n : Natures.all()) natures.add(n.getName().getPath());
        } catch (Throwable ignored) {
        }
        o.add("natures", natures);
        JsonArray tera = new JsonArray();
        try {
            for (TeraType t : TeraTypes.INSTANCE) tera.add(t.getName());
        } catch (Throwable ignored) {
        }
        o.add("teraTypes", tera);
        return Json.COMPACT.toJson(o);
    }

    private static JsonObject skin(Skins.SkinInfo s) {
        JsonObject j = new JsonObject();
        j.addProperty("ref", s.ref());
        j.addProperty("label", s.label());
        j.addProperty("slim", s.slim());
        return j;
    }

    private static void onSave(ServerPlayer p, Payloads.SaveTrainer payload) {
        boolean boss = "boss".equals(payload.kind());
        if (!Perms.admin(p, boss ? Perms.ADMIN_BOSS : Perms.ADMIN_NPC)) {
            Msg.send(p, "general.no_permission");
            return;
        }
        TrainerSpec spec;
        try {
            spec = Json.GSON.fromJson(payload.trainerJson(), TrainerSpec.class);
        } catch (Exception e) {
            reply(p, false, Msg.raw("editor.invalid_json"), List.of(e.getMessage()));
            return;
        }
        if (spec == null) {
            reply(p, false, Msg.raw("editor.invalid_json"), List.of());
            return;
        }
        List<String> errors = TrainerFactory.validate(spec);
        boolean blocking = errors.stream().anyMatch(e -> !e.contains("accepte"));
        if (blocking && !payload.force()) {
            reply(p, false, Msg.raw("editor.has_errors"), errors);
            return;
        }
        MinecraftServer s = p.server;
        if (boss) {
            TowerConfig.bosses().bosses.put(payload.id().toLowerCase(Locale.ROOT), spec);
            TowerConfig.saveBosses();
            if (payload.id().equalsIgnoreCase(DailyBoss.currentId())) {
                NPCEntity npc = DailyBoss.npc(s);
                if (npc != null) TrainerNpcs.refresh(npc);
            }
        } else {
            int floor;
            try {
                floor = Integer.parseInt(payload.id());
            } catch (NumberFormatException e) {
                reply(p, false, "Etage invalide", List.of());
                return;
            }
            FloorEntry fe = TowerConfig.floor(floor);
            if (fe == null) {
                reply(p, false, "Etage invalide", List.of());
                return;
            }
            fe.trainer = spec;
            TowerConfig.saveFloors();
            NPCEntity npc = TrainerNpcs.find(s, fe.npcUuid, fe.npcLocation);
            if (npc != null) TrainerNpcs.configure(npc, TrainerNpcs.displayName(spec), spec.skin);
        }
        Battletower.LOG.info("[Battletower] {} a enregistre le dresseur {} {}", p.getGameProfile().getName(), payload.kind(), payload.id());
        reply(p, true, Msg.raw("editor.saved"), errors);
    }

    private static void reply(ServerPlayer p, boolean ok, String message, List<String> errors) {
        JsonArray arr = new JsonArray();
        for (String e : errors) arr.add(e == null ? "" : e);
        ServerPlayNetworking.send(p, new Payloads.EditorResult(ok, message, Json.COMPACT.toJson(arr)));
    }

    // ------------------------------------------------------------------ Skins pour l'apercu

    private static void onSkinRequest(ServerPlayer p, String ref) {
        if (!Perms.isAdmin(p) || ref == null) return;
        if (ref.startsWith("player:")) {
            String name = ref.substring(7).trim();
            MinecraftServer s = p.server;
            CompletableFuture.supplyAsync(() -> fetchMojangSkin(s, name)).thenAccept(res -> s.execute(() -> {
                if (!p.hasDisconnected()) ServerPlayNetworking.send(p, new Payloads.SkinData(ref, res.slim, res.png));
            }));
            return;
        }
        byte[] png = Skins.bytes(ref);
        ServerPlayNetworking.send(p, new Payloads.SkinData(ref, Skins.isSlim(ref), png == null ? new byte[0] : png));
    }

    private record Fetched(byte[] png, boolean slim) {}

    private static Fetched fetchMojangSkin(MinecraftServer s, String name) {
        try {
            Optional<GameProfile> prof = s.getProfileCache() == null ? Optional.empty() : s.getProfileCache().get(name);
            if (prof.isEmpty()) return new Fetched(new byte[0], false);
            GameProfile full = s.getSessionService().fetchProfile(prof.get().getId(), true) == null ? prof.get()
                    : s.getSessionService().fetchProfile(prof.get().getId(), true).profile();
            MinecraftProfileTexture tex = s.getSessionService().getTextures(full).skin();
            if (tex == null) return new Fetched(new byte[0], false);
            HttpURLConnection c = (HttpURLConnection) URI.create(tex.getUrl()).toURL().openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            try (InputStream in = c.getInputStream()) {
                byte[] data = in.readNBytes(256 * 1024);
                return new Fetched(data, "slim".equalsIgnoreCase(tex.getMetadata("model")));
            }
        } catch (Throwable t) {
            Battletower.LOG.warn("[Battletower] Skin Mojang de {} introuvable : {}", name, t.toString());
            return new Fetched(new byte[0], false);
        }
    }
}
