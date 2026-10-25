package com.xy2407.kingdom.server.building;

import com.xy2407.kingdom.ModMain;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * 区块加载事件：工地覆盖区块全部到场才恢复施工，离开即冻结
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class BuildingChunkEvents {

    private BuildingChunkEvents() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            BuildingConstructionService.INSTANCE.onChunkLoad(level, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            BuildingConstructionService.INSTANCE.onChunkUnload(level, event.getChunk().getPos());
        }
    }
}
