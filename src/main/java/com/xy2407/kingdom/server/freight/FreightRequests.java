package com.xy2407.kingdom.server.freight;

import com.xy2407.kingdom.common.building.ItemCount;
import com.xy2407.kingdom.common.freight.FreightSource;
import com.xy2407.kingdom.server.building.BuildingRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** 物流的统一申请入口：工业、加工、养殖、农业、建筑都从这里下单。 */
public final class FreightRequests {

    private FreightRequests() {
    }

    public static void requestToBuilding(MinecraftServer server, UUID buildingId, String dimension,
                                         List<ItemCount> items) {
        FreightService.INSTANCE.submit(server, buildingId, dimension, items);
    }

    public static void requestToContainer(MinecraftServer server, String dimension, BlockPos pos,
                                          List<ItemCount> items) {
        FreightService.INSTANCE.submitToContainer(server, dimension, pos, items);
    }

    public static void watchBuilding(MinecraftServer server, UUID buildingId, String dimension,
                                     Supplier<List<ItemCount>> missing) {
        if (buildingId == null || missing == null) {
            return;
        }
        FreightService.INSTANCE.watch(new FreightSource() {
            @Override
            public UUID sourceId() {
                return buildingId;
            }

            @Override
            public String dimension() {
                return dimension == null ? "" : dimension;
            }

            @Override
            public boolean valid() {
                return BuildingRegistry.INSTANCE.byId(buildingId) != null;
            }

            @Override
            public UUID buildingId() {
                return buildingId;
            }

            @Override
            public List<ItemCount> missing() {
                return missing.get();
            }
        });
    }

    public static void watchContainer(MinecraftServer server, UUID ownerId, String dimension,
                                      BlockPos pos, Supplier<List<ItemCount>> missing) {
        if (ownerId == null || pos == null || missing == null) {
            return;
        }
        BlockPos target = pos.immutable();
        FreightService.INSTANCE.watch(new FreightSource() {
            @Override
            public UUID sourceId() {
                return ownerId;
            }

            @Override
            public String dimension() {
                return dimension == null ? "" : dimension;
            }

            @Override
            public boolean valid() {
                ServerLevel level = levelOf(server, dimension);
                return level != null && level.getBlockEntity(target) != null;
            }

            @Override
            public BlockPos containerPos() {
                return target;
            }

            @Override
            public List<ItemCount> missing() {
                return missing.get();
            }
        });
    }

    public static void stop(UUID sourceId) {
        FreightService.INSTANCE.unwatch(sourceId);
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        if (server == null || dimension == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }
}
