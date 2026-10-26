package com.xy2407.kingdom.common.block;

import com.xy2407.kingdom.ModMain;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 创造模式标签页：本模组所有可放置内容的统一入口。 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ModMain.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN =
            TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.xy2407_kingdom.main"))
                    .icon(() -> new ItemStack(ModBlocks.CITY_HALL_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModBlocks.CITY_HALL_ITEM.get());
                        output.accept(ModBlocks.BUILDER_BLOCK_ITEM.get());
                        output.accept(ModBlocks.SHOP_SHELF_ITEM.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
