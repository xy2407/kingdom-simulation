package com.xy2407.kingdom.client.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.xy2407.kingdom.common.block.ShopShelfBlock;
import com.xy2407.kingdom.common.block.ShopShelfBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** 货架渲染：把 9 个陈列位的物品按台阶高度摆到对应位置，并随方块朝向旋转。 */
@OnlyIn(Dist.CLIENT)
public class ShopShelfRenderer implements BlockEntityRenderer<ShopShelfBlockEntity> {

    private static final float SCALE = 0.42F;

    public ShopShelfRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ShopShelfBlockEntity shelf, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        Direction facing = shelf.getBlockState().getValue(ShopShelfBlock.FACING);
        Minecraft minecraft = Minecraft.getInstance();
        for (int slot = 0; slot < ShopShelfBlockEntity.SLOTS; slot++) {
            ItemStack stack = shelf.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            int row = slot / ShopShelfBlockEntity.COLS;
            int col = slot % ShopShelfBlockEntity.COLS;
            double[] world = ShopShelfLayout.localToWorld(facing,
                    ShopShelfLayout.COL_X[col], ShopShelfLayout.ROW_Z[row]);
            poseStack.pushPose();
            poseStack.translate(world[0], ShopShelfLayout.ROW_Y[row] + 0.02D, world[1]);
            poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
            poseStack.scale(SCALE, SCALE, SCALE);
            minecraft.getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED,
                    packedLight, packedOverlay, poseStack, buffer, shelf.getLevel(), slot);
            poseStack.popPose();
        }
    }
}
