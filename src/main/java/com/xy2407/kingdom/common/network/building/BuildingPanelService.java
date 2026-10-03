package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.server.building.BuildingRegistry;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 建筑面板的服务端侧处理：按玩家当前位置判定所处建筑并回包。
 *
 * <p>位置由服务端从 {@code context.player()} 直接取，不信任客户端传来的坐标。</p>
 */
public final class BuildingPanelService {

    private BuildingPanelService() {
    }

    static void respond(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        BuildingRecord hit = BuildingRegistry.INSTANCE.locate(player);
        PacketDistributor.sendToPlayer(player, hit == null
                ? BuildingPanelStatePacket.outside()
                : new BuildingPanelStatePacket(hit.name(), hit.level(), hit.farmSlots()));
    }
}
