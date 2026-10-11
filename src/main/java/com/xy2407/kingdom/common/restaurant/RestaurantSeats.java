package com.xy2407.kingdom.common.restaurant;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.common.building.BuildingRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 餐厅座位解析：现扫建筑内能坐下的位置，不额外维护座位表
 */
public final class RestaurantSeats {

    private static final int MAX_SCAN = 32768;

    private RestaurantSeats() {
    }

    public static List<BlockPos> seats(ServerLevel level, BuildingRecord building) {
        List<BlockPos> found = new ArrayList<>();
        if (level == null || building == null) {
            return found;
        }
        int scanned = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = building.min().getY(); y <= building.max().getY() && scanned < MAX_SCAN; y++) {
            for (int x = building.min().getX(); x <= building.max().getX() && scanned < MAX_SCAN; x++) {
                for (int z = building.min().getZ(); z <= building.max().getZ() && scanned < MAX_SCAN; z++) {
                    scanned++;
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor) || !level.getBlockState(cursor).isAir()) {
                        continue;
                    }
                    if (!level.getBlockState(cursor.set(x, y + 1, z)).isAir()) {
                        continue;
                    }
                    BlockState floor = level.getBlockState(cursor.set(x, y - 1, z));
                    if (floor.getCollisionShape(level, cursor).isEmpty()) {
                        continue;
                    }
                    found.add(new BlockPos(x, y, z));
                }
            }
        }
        return found;
    }
}
