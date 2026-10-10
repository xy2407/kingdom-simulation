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
 * 食材条目的统一匹配：{@code ItemCount#item()} 既可以写具体物品 id，
 * 也可以写 {@code #命名空间:标签}（如 {@code #c:foods}、{@code #minecraft:planks}）。
 * 走标签是为了让别的 mod 的物品只要带对应标签就能当食材，不必逐个写死 id —— 这就是"多 mod 兼容"。
 * 成品不用标签：写标签无法决定具体插哪个物品，只会让仓库里多出一堆随机货。
 */
public final class ItemSpecs {

    private ItemSpecs() {
    }

    /** 该堆物品是否满足条目：具体 id 直接比，{@code #tag} 走标签匹配。 */
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

    /** 仓库里满足该条目的总量。 */
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

    /** 扣掉全部需求；任一项不够则原地不动（先 {@link #hasAll} 判定）。 */
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

    /** 只试不放：仓库放得下这单个成品才返回 true（免得食材消耗了、成品却塞不进去）。 */
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

    /** 真正放入单个成品。 */
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

    /** 具体物品 id → 物品；解析不了返回 null。 */
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
