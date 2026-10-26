package com.xy2407.kingdom.common.restaurant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.xy2407.kingdom.common.building.BuildingRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 餐厅家具扫描：在建筑包围盒内找椅子与桌子，按邻接关系配成座位，不再随机取空位
 */
public final class RestaurantSeats {

    private static final int MAX_SCAN = 32768;

    private RestaurantSeats() {
    }

    public static List<RestaurantSeat> scan(ServerLevel level, BuildingRecord building) {
        List<RestaurantSeat> result = new ArrayList<>();
        if (level == null || building == null) {
            return result;
        }
        String dimension = level.dimension().location().toString();
        List<BlockPos> chairs = new ArrayList<>();
        Set<BlockPos> tables = new HashSet<>();
        int scanned = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = building.min().getY(); y <= building.max().getY() && scanned < MAX_SCAN; y++) {
            for (int x = building.min().getX(); x <= building.max().getX() && scanned < MAX_SCAN; x++) {
                for (int z = building.min().getZ(); z <= building.max().getZ() && scanned < MAX_SCAN; z++) {
                    scanned++;
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (RestaurantFurniture.isChair(state)) {
                        chairs.add(new BlockPos(x, y, z));
                    } else if (RestaurantFurniture.isTable(state)) {
                        tables.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        for (BlockPos chair : chairs) {
            result.add(new RestaurantSeat(building.id(), dimension, chair, nearestTable(chair, tables)));
        }
        return result;
    }

    private static BlockPos nearestTable(BlockPos chair, Set<BlockPos> tables) {
        if (tables.isEmpty()) {
            return null;
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos candidate = chair.offset(dx, dy, dz);
                    if (!tables.contains(candidate)) {
                        continue;
                    }
                    double score = dx * dx + dz * dz + Math.abs(dy) * 4;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }
}
