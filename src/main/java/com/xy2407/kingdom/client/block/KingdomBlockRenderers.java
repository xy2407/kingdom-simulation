package com.xy2407.kingdom.client.block;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.block.ModBlockEntities;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端方块实体渲染器注册：目前只有木桌的桌面物品展示
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
public final class KingdomBlockRenderers {

    private KingdomBlockRenderers() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.WOODEN_TABLE.get(),
                WoodenTableRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.SHOP_SHELF.get(),
                ShopShelfRenderer::new);
    }
}
