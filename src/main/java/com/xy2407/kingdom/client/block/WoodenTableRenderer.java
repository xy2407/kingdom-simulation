package com.xy2407.kingdom.client.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.xy2407.kingdom.common.block.WoodenTableBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 木桌渲染：把桌面上那件物品缩小后立在桌面中心，随方块实体数据同步刷新
 */
@OnlyIn(Dist.CLIENT)
public class WoodenTableRenderer implements BlockEntityRenderer<WoodenTableBlockEntity> {

    private static final float SCALE = 0.5F;

    private static final double HEIGHT = 1.06D;

    public WoodenTableRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(WoodenTableBlockEntity table, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        ItemStack stack = table.display();
        if (stack.isEmpty()) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0.5D, HEIGHT, 0.5D);
        poseStack.scale(SCALE, SCALE, SCALE);
        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED,
                packedLight, packedOverlay, poseStack, bufferSource, table.getLevel(), 0);
        poseStack.popPose();
    }
}
