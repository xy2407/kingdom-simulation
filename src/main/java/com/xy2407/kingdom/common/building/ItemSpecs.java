package com.xy2407.kingdom.common.building;

import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 食材条目统一匹配：物品 id 或 #标签 都能命中，靠标签实现多 mod 兼容
 */
public final class ItemSpecs {

    private ItemSpecs() {
    }

    public static boolean matches(ItemStack stack, String spec) {
        if (stack == null || stack.isEmpty() || spec == null || spec.isBlank()) {
            return false;
        }
        String trimmed = spec.trim();
        if (trimmed.startsWith("#")) {
            TagKey<Item> tag = tagOf(trimmed.substring(1));
            return tag != null && stack.is(tag);
        }
        Item item = itemOf(trimmed);
        return item != null && stack.is(item);
    }

    public static int count(IItemHandler store, String spec) {
        int total = 0;
        for (int slot = 0; slot < store.getSlots(); slot++) {
            ItemStack stack = store.getStackInSlot(slot);
            if (matches(stack, spec)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static boolean hasAll(IItemHandler store, List<ItemCount> needs) {
        for (ItemCount need : needs) {
            if (count(store, need.item()) < need.count()) {
                return false;
            }
        }
        return true;
    }

    public static boolean consume(IItemHandler store, List<ItemCount> needs) {
        if (!hasAll(store, needs)) {
            return false;
        }
        for (ItemCount need : needs) {
            int remaining = need.count();
            for (int slot = 0; slot < store.getSlots() && remaining > 0; slot++) {
                ItemStack stack = store.getStackInSlot(slot);
                if (!matches(stack, need.item())) {
                    continue;
                }
                ItemStack taken = store.extractItem(slot, Math.min(remaining, stack.getCount()), false);
                remaining -= taken.getCount();
            }
        }
        return true;
    }

    public static boolean canInsertOne(IItemHandler store, ItemCount output) {
        Item item = itemOf(output.item());
        if (item == null) {
            return false;
        }
        ItemStack remaining = new ItemStack(item, output.count());
        for (int slot = 0; slot < store.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = store.insertItem(slot, remaining, true);
        }
        return remaining.isEmpty();
    }

    public static void insertOne(IItemHandler store, ItemCount output) {
        Item item = itemOf(output.item());
        if (item == null) {
            return;
        }
        int remaining = output.count();
        for (int slot = 0; slot < store.getSlots() && remaining > 0; slot++) {
            remaining = store.insertItem(slot, new ItemStack(item, remaining), false).getCount();
        }
    }

    public static Item itemOf(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        ResourceLocation key = ResourceLocation.tryParse(id.trim());
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            return null;
        }
        return BuiltInRegistries.ITEM.get(key);
    }

    private static TagKey<Item> tagOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        ResourceLocation key = ResourceLocation.tryParse(raw.trim());
        return key == null ? null : TagKey.create(Registries.ITEM, key);
    }
}
