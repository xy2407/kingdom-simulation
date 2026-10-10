package com.xy2407.kingdom.server.building;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.network.building.BuildingSyncPacket;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 建筑清单下发：把玩家当前维度里所有已完工建筑发给客户端，供 RTS 画框与点选。
 */
public final class BuildingSyncService {

    private BuildingSyncService() {
    }

    public static void sendTo(ServerPlayer player) {
        if (player == null) {
            return;
        }
        String dimension = player.level().dimension().location().toString();
        List<BuildingSyncPacket.Entry> entries = new ArrayList<>();
        for (BuildingRecord building : BuildingRegistry.INSTANCE.all()) {
            if (!dimension.equals(building.dimension())) {
                continue;
            }
            entries.add(new BuildingSyncPacket.Entry(building.id(), building.name(),
                    building.min(), building.max()));
        }
        PacketDistributor.sendToPlayer(player, new BuildingSyncPacket(entries));
    }
}
