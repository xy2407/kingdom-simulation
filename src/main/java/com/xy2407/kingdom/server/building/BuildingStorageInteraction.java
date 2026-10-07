package com.xy2407.kingdom.server.building;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingRecord;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 建筑仓库的进入入口：**潜行 + 右键**建筑范围内的任意方块。
 *
 * <p>为什么挑潜行右键：建筑是蓝图铺出来的一片方块，没有"仓库方块"这种东西，
 * 所以要靠"点在建筑包围盒里"来判定。而普通右键得让给门、箱子、按钮这些原版交互，
 * 用潜行把它们岔开。不是仓库的建筑（蓝图没写 {@code storage_slots}）直接放行，不拦原版行为。</p>
 *
 * <p>判定一律在服务端做：不采信客户端坐标，也不给客户端"我自己算一下"的机会。</p>
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class BuildingStorageInteraction {

    private BuildingStorageInteraction() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.isShiftKeyDown()) {
            return;
        }
        BuildingRecord building = BuildingRegistry.INSTANCE.at(
                event.getLevel().dimension().location().toString(),
                event.getPos().getX() + 0.5D,
                event.getPos().getY() + 0.5D,
                event.getPos().getZ() + 0.5D);
        if (building == null) {
            return;
        }
        int capacity = BuildingRegistry.INSTANCE.storageSlotsOf(building);
        if (capacity <= 0) {
            /* 这座建筑不是仓库：放行，让原版交互（开门、放方块等）照常发生 */
            return;
        }
        if (BuildingStorageMenu.open(player, building, capacity)) {
            /* 拦下这次右键，否则开门/放方块会和开仓库同时发生 */
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }
}
