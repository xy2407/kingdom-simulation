package com.xy2407.kingdom.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.xy2407.kingdom.common.entity.SeatEntity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 座位实体渲染器：只是一个逻辑挂点，画面上什么都不画
 */
@OnlyIn(Dist.CLIENT)
public class SeatRenderer extends EntityRenderer<SeatEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public SeatRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(SeatEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
    }

    @Override
    public ResourceLocation getTextureLocation(SeatEntity entity) {
        return TEXTURE;
    }
}
