package com.xy2407.kingdom.common.npc;

import java.util.List;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.farm.FarmPlot;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 收获物入库：建筑仓库优先，满了进 NPC 背包，最后才掉在地上。
 */
final class FarmProduceDeposit {

    private FarmProduceDeposit() {
    }

    static void hold(ServerLevel level, FarmPlot plot, BlockPos pos, NpcEntity npc, List<ItemStack> drops) {
        var inventory = npc == null ? null : npc.getNpcInventory();
        for (ItemStack drop : drops) {
            if (drop == null || drop.isEmpty()) {
                continue;
            }
            ItemStack remaining = deposit(level, plot, drop.copy());
            if (inventory != null) {
                for (int slot = 0; slot < inventory.getSlots() && !remaining.isEmpty(); slot++) {
                    remaining = inventory.insertItem(slot, remaining, false);
                }
            }
            if (!remaining.isEmpty()) {
                net.minecraft.world.level.block.Block.popResource(level, pos, remaining);
            }
        }
    }

    private static ItemStack deposit(ServerLevel level, FarmPlot plot, ItemStack stack) {
        if (stack.isEmpty() || plot == null || level.getServer() == null) {
            return stack;
        }
        BuildingRecord building = BuildingRegistry.INSTANCE.byId(plot.buildingId());
        if (building == null) {
            return stack;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        if (meta == null) {
            return stack;
        }
        IItemHandler store = BuildingStorageStore.INSTANCE.handlerOf(level.getServer(),
                building.id(), meta.storageSlots());
        if (store == null) {
            return stack;
        }
        ItemStack remaining = stack;
        for (int slot = 0; slot < store.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = store.insertItem(slot, remaining, false);
        }
        return remaining;
    }
}
