package com.xy2407.kingdom.server.building;

import com.xy2407.kingdom.common.block.MapStandBlockEntity;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.network.building.MapStandDataPacket;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 仓库地图架：汇总领地范围与建筑清单并下发，处理管辖加入/移除。 */
public final class MapStandService {

    private MapStandService() {
    }

    public static BuildingRecord warehouseAt(ServerLevel level, BlockPos pos) {
        BuildingRecord record = BuildingRegistry.INSTANCE.at(level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ());
        if (record == null) {
            return null;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(record);
        return meta != null && meta.isWork(BuildingWork.WAREHOUSE) ? record : null;
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        BuildingRecord warehouse = warehouseAt(level, pos);
        if (warehouse == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, buildData(level, pos, warehouse));
    }

    public static void toggle(ServerPlayer player, BlockPos pos, List<UUID> buildingIds, boolean govern) {
        ServerLevel level = player.serverLevel();
        if (!(level.getBlockEntity(pos) instanceof MapStandBlockEntity stand)) {
            return;
        }
        BuildingRecord warehouse = warehouseAt(level, pos);
        if (warehouse == null) {
            return;
        }
        for (UUID id : buildingIds) {
            BuildingRecord target = BuildingRegistry.INSTANCE.byId(id);
            if (target == null || isWarehouse(target)) {
                continue;
            }
            stand.setGoverned(id, govern);
        }
        PacketDistributor.sendToPlayer(player, buildData(level, pos, warehouse));
    }

    private static MapStandDataPacket buildData(ServerLevel level, BlockPos pos, BuildingRecord warehouse) {
        List<BuildingRecord> all = BuildingRegistry.INSTANCE.all();
        List<MapStandDataPacket.Building> buildings = new ArrayList<>();
        Territory territory = TerritoryRegistry.INSTANCE.byBlock(warehouse.dimension(),
                pos.getX(), pos.getZ());
        int minX;
        int minZ;
        int maxX;
        int maxZ;
        if (territory == null || territory.chunks().isEmpty()) {
            minX = warehouse.min().getX() - 64;
            minZ = warehouse.min().getZ() - 64;
            maxX = warehouse.max().getX() + 64;
            maxZ = warehouse.max().getZ() + 64;
        } else {
            int minChunkX = Integer.MAX_VALUE;
            int minChunkZ = Integer.MAX_VALUE;
            int maxChunkX = Integer.MIN_VALUE;
            int maxChunkZ = Integer.MIN_VALUE;
            for (long key : territory.chunks()) {
                int chunkX = Territory.chunkKeyX(key);
                int chunkZ = Territory.chunkKeyZ(key);
                minChunkX = Math.min(minChunkX, chunkX);
                minChunkZ = Math.min(minChunkZ, chunkZ);
                maxChunkX = Math.max(maxChunkX, chunkX);
                maxChunkZ = Math.max(maxChunkZ, chunkZ);
            }
            minX = minChunkX * 16;
            minZ = minChunkZ * 16;
            maxX = (maxChunkX + 1) * 16 - 1;
            maxZ = (maxChunkZ + 1) * 16 - 1;
        }
        MapStandBlockEntity stand = level.getBlockEntity(pos) instanceof MapStandBlockEntity found
                ? found : null;
        boolean hasTerritory = territory != null && !territory.chunks().isEmpty();
        for (BuildingRecord record : all) {
            if (!record.dimension().equals(warehouse.dimension())) {
                continue;
            }
            int coreX = (record.min().getX() + record.max().getX()) / 2;
            int coreZ = (record.min().getZ() + record.max().getZ()) / 2;
            if (hasTerritory && !territory.containsBlock(coreX + 0.5D, coreZ + 0.5D)) {
                continue;
            }
            BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(record);
            String type = meta == null || meta.work() == null ? "" : meta.work().label();
            boolean isWarehouse = meta != null && meta.isWork(BuildingWork.WAREHOUSE);
            boolean governed = stand != null && stand.isGoverned(record.id());
            buildings.add(new MapStandDataPacket.Building(record.id(), record.name(), type,
                    record.min().getX(), record.min().getZ(), record.max().getX(), record.max().getZ(),
                    coreX, coreZ, isWarehouse, governed));
        }
        List<MapStandDataPacket.Staff> staff = new ArrayList<>();
        String warehouseId = warehouse.id().toString();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !warehouseId.equals(npc.getWorkplaceId())) {
                continue;
            }
            staff.add(new MapStandDataPacket.Staff(npc.getId(), npc.getName(), npc.getJob()));
        }
        return new MapStandDataPacket(pos, warehouse.id(), warehouse.name(), minX, minZ, maxX, maxZ,
                buildings, staff);
    }

    private static boolean isWarehouse(BuildingRecord record) {
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(record);
        return meta != null && meta.isWork(BuildingWork.WAREHOUSE);
    }
}
