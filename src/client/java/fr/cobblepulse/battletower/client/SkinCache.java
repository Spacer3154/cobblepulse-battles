package fr.cobblepulse.battletower.client;

import com.mojang.blaze3d.platform.NativeImage;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Textures de skins pour l'apercu 3D : embarquees (assets du mod) ou recues du serveur (dossier skins, comptes Mojang). */
public final class SkinCache {
    public record Skin(ResourceLocation texture, boolean slim) {}

    private static final Map<String, Skin> LOADED = new HashMap<>();
    private static final Set<String> PENDING = new HashSet<>();
    private static final Set<String> MISSING = new HashSet<>();

    private SkinCache() {}

    /** Texture d'un skin (null tant qu'il n'est pas recu). */
    public static Skin get(String ref, boolean slimHint) {
        if (ref == null || ref.isBlank()) return null;
        if (ref.startsWith("builtin:")) {
            String name = ref.substring(8).replaceAll("[^a-z0-9_\\-]", "");
            return new Skin(ResourceLocation.fromNamespaceAndPath(Battletower.ASSET_NS, "textures/entity/npc/" + name + ".png"), slimHint);
        }
        Skin s = LOADED.get(ref);
        if (s != null || MISSING.contains(ref)) return s;
        if (PENDING.add(ref) && ClientPlayNetworking.canSend(Payloads.SkinRequest.TYPE)) {
            ClientPlayNetworking.send(new Payloads.SkinRequest(ref));
        }
        return null;
    }

    public static boolean missing(String ref) {
        return MISSING.contains(ref);
    }

    public static void forget(String ref) {
        LOADED.remove(ref);
        MISSING.remove(ref);
        PENDING.remove(ref);
    }

    static void receive(Payloads.SkinData data) {
        PENDING.remove(data.ref());
        if (data.png() == null || data.png().length == 0) {
            MISSING.add(data.ref());
            return;
        }
        try {
            NativeImage img = NativeImage.read(new ByteArrayInputStream(data.png()));
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(Battletower.ASSET_NS,
                    "dynamic_skins/" + Integer.toHexString(data.ref().hashCode()) + "_" + System.nanoTime());
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(img));
            LOADED.put(data.ref(), new Skin(rl, data.slim()));
        } catch (Exception e) {
            Battletower.LOG.warn("[Battletower] Skin recu illisible ({}) : {}", data.ref(), e.toString());
            MISSING.add(data.ref());
        }
    }
}
