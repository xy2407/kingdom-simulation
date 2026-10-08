package com.xy2407.kingdom.server.building;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingRecord;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 建筑仓库入口：潜行右键建筑范围内的任意方块，判定一律在服务端做。
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
            return;
        }
        if (BuildingStorageMenu.open(player, building, capacity)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }
}
