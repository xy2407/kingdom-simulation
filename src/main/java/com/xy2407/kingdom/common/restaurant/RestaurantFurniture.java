package com.xy2407.kingdom.common.restaurant;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 餐厅家具方块目录：椅子与桌子的注册 id，支持 #标签 写法以便换材质整套替换
 */
public final class RestaurantFurniture {

    public static final String CHAIR_BLOCK = "xy2407_kingdom:wooden_chair";

    public static final String TABLE_BLOCK = "xy2407_kingdom:wooden_table";

    private RestaurantFurniture() {
    }

    public static boolean isChair(BlockState state) {
        return matches(state, CHAIR_BLOCK);
    }

    public static boolean isTable(BlockState state) {
        return matches(state, TABLE_BLOCK);
    }

    public static boolean enabled() {
        return !CHAIR_BLOCK.isBlank() && !TABLE_BLOCK.isBlank() && !CHAIR_BLOCK.equals(TABLE_BLOCK);
    }

    private static boolean matches(BlockState state, String spec) {
        if (state == null || spec == null || spec.isBlank()) {
            return false;
        }
        String trimmed = spec.trim();
        if (trimmed.startsWith("#")) {
            TagKey<Block> tag = tagOf(trimmed.substring(1));
            return tag != null && state.is(tag);
        }
        Block block = blockOf(trimmed);
        return block != null && state.is(block);
    }

    private static Block blockOf(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.get(key);
    }

    private static TagKey<Block> tagOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        ResourceLocation key = ResourceLocation.tryParse(raw.trim());
        return key == null ? null : TagKey.create(Registries.BLOCK, key);
    }
}
