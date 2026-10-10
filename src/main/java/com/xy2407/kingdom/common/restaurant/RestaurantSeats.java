package com.xy2407.kingdom.common.restaurant;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.common.building.BuildingRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 餐厅座位解析：扫出建筑里"能坐下"的位置（脚位与头顶都空、脚下踩得住）。
 * 与 {@code HomeBeds} 扫床同一套思路 —— 不额外维护座位表，用到时现扫，
 * 所以玩家在店里怎么摆家具都能自适应。
 */
public final class RestaurantSeats {

    /** 单次扫描的方块上限，与床/牧场同一档，避免超大建筑卡主线程。 */
    private static final int MAX_SCAN = 32768;

    private RestaurantSeats() {
    }

    /** 建筑范围内所有可坐的点，按层高从下往上。 */
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
                    // 头顶要能站得下人（坐姿也占一格）
                    if (!level.getBlockState(cursor.set(x, y + 1, z)).isAir()) {
                        continue;
                    }
                    // 脚下要有碰撞体积，不然是悬空的
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
