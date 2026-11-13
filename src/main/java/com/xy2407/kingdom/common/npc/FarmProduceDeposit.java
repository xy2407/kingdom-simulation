package com.xy2407.kingdom.common.npc;

import java.util.List;

import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.farm.FarmPlot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * 收获物入库：产物先进 NPC 背包 24 格；忙完本轮任务由 NPC 亲自送回建筑仓库，
 * 仓库满时由「正在存入产物」流程催运输清仓，最后才允许掉在地上。
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
            ItemStack remaining = drop.copy();
            if (inventory != null) {
                for (int slot = NpcInventory.BAG_START; slot < NpcInventory.SLOT_COUNT
                        && !remaining.isEmpty(); slot++) {
                    remaining = inventory.insertItem(slot, remaining, false);
                }
            }
            if (!remaining.isEmpty()) {
                net.minecraft.world.level.block.Block.popResource(level, pos, remaining);
            }
        }
    }
}
