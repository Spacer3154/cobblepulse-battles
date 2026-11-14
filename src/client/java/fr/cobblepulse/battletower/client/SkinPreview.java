package fr.cobblepulse.battletower.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;

/** Rendu 3D d'un skin (modele joueur classique ou fin) dans une interface, orientable a la souris. */
public final class SkinPreview {
    private static PlayerModel<LivingEntity> wide;
    private static PlayerModel<LivingEntity> slim;

    private SkinPreview() {}

    private static PlayerModel<LivingEntity> model(boolean slimArms) {
        Minecraft mc = Minecraft.getInstance();
        if (slimArms) {
            if (slim == null) slim = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
            return slim;
        }
        if (wide == null) wide = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        return wide;
    }

    /**
     * @param feetX  centre horizontal
     * @param feetY  position des pieds
     * @param size   hauteur d'un bloc en pixels d'interface (le modele mesure 2 blocs)
     * @param yaw    rotation en degres
     */
    public static void render(GuiGraphics g, SkinCache.Skin skin, int feetX, int feetY, float size, float yaw, float pitch) {
        if (skin == null) return;
        PlayerModel<LivingEntity> m = model(skin.slim());
        m.young = false;
        m.crouching = false;
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(feetX, feetY, 150);
        ps.scale(size, size, -size);
        ps.translate(0, -1.501f, 0);
        ps.mulPose(Axis.XP.rotationDegrees(-pitch));
        ps.mulPose(Axis.YP.rotationDegrees(yaw));
        Lighting.setupForEntityInInventory();
        var buffer = g.bufferSource().getBuffer(RenderType.entityTranslucent(skin.texture()));
        m.renderToBuffer(ps, buffer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        g.flush();
        Lighting.setupFor3DItems();
        ps.popPose();
    }
}
