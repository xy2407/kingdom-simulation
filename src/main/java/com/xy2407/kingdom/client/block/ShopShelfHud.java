package com.xy2407.kingdom.client.block;

import com.xy2407.kingdom.common.block.ShopShelfBlock;
import com.xy2407.kingdom.common.block.ShopShelfBlockEntity;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** 货架价格 HUD：准星对准某一格陈列位时，在准星旁显示物品名与价格。 */
@OnlyIn(Dist.CLIENT)
public final class ShopShelfHud implements LayeredDraw.Layer {

    public static final ShopShelfHud INSTANCE = new ShopShelfHud();
    public static final String ID = "shop_shelf_price";

    private ShopShelfHud() {
    }

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.screen != null) {
            return;
        }
        HitResult hitResult = minecraft.hitResult;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK
                || !(hitResult instanceof BlockHitResult hit)) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = minecraft.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ShopShelfBlock)) {
            return;
        }
        if (!(minecraft.level.getBlockEntity(pos) instanceof ShopShelfBlockEntity shelf)) {
            return;
        }
        Direction facing = state.getValue(ShopShelfBlock.FACING);
        Vec3 location = hit.getLocation();
        double[] local = ShopShelfLayout.worldToLocal(facing,
                location.x - pos.getX(), location.z - pos.getZ());
        int slot = ShopShelfLayout.slotAt(local[0], local[1]);
        if (slot < 0) {
            return;
        }
        ItemStack stack = shelf.getItem(slot);
        if (stack.isEmpty()) {
            return;
        }
        int centerX = guiGraphics.guiWidth() / 2;
        int centerY = guiGraphics.guiHeight() / 2;
        String name = stack.getHoverName().getString();
        String price = shelf.priceOf(slot) + " 金币";
        guiGraphics.drawString(minecraft.font, name, centerX + 14, centerY - 16, 0xFFFFFFFF, true);
        guiGraphics.drawString(minecraft.font, price, centerX + 14, centerY - 5, 0xFFFFD060, true);
    }
}
