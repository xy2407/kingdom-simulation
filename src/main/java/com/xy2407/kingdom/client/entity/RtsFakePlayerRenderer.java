package com.xy2407.kingdom.client.entity;

import com.xy2407.kingdom.common.entity.RtsFakePlayerEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 用本地玩家的皮肤渲染 RTS 假人，名条由服务端生成时设置。
 */
@OnlyIn(Dist.CLIENT)
public class RtsFakePlayerRenderer extends LivingEntityRenderer<RtsFakePlayerEntity, PlayerModel<RtsFakePlayerEntity>> {

    private static final ResourceLocation DEFAULT_SKIN =
            ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");

    public RtsFakePlayerRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.0F);
    }

    @Override
    public ResourceLocation getTextureLocation(RtsFakePlayerEntity entity) {
        if (Minecraft.getInstance().player != null) {
            return Minecraft.getInstance().player.getSkin().texture();
        }
        return DEFAULT_SKIN;
    }

}
