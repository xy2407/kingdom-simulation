package com.xy2407.kingdom.client.rts;

import com.xy2407.kingdom.client.entity.NpcEntityRenderer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 面板头像：从 NPC 皮肤贴图裁 8×8 的脸部并等比放大到指定尺寸
 */
@OnlyIn(Dist.CLIENT)
public final class NpcHeadRenderer {

    private static final int FACE_U = 8;
    private static final int FACE_V = 8;
    private static final int FACE = 8;
    private static final int SHEET = 64;

    private NpcHeadRenderer() {
    }

    public static void render(GuiGraphics graphics, String skin, int x, int y, int size) {
        if (graphics == null || size <= 0) {
            return;
        }
        ResourceLocation texture = NpcEntityRenderer.resolve(skin == null ? "" : skin);
        if (texture == null) {
            return;
        }
        float scale = size / (float) FACE;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.blit(texture, 0, 0, FACE_U, FACE_V, FACE, FACE, SHEET, SHEET);
        graphics.pose().popPose();
    }
}
