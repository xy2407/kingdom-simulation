package com.xy2407.kingdom.common.restaurant;

import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * 餐厅里的一件家具配对：椅子作为座位，紧邻的桌子作为上菜点，可为空
 */
public record RestaurantSeat(UUID buildingId, String dimension, BlockPos seat, BlockPos table) {

    public RestaurantSeat {
        dimension = dimension == null ? "" : dimension;
        seat = seat == null ? BlockPos.ZERO : seat.immutable();
        table = table == null ? null : table.immutable();
    }

    public BlockPos servePoint() {
        return table == null ? seat : table;
    }

    public String key() {
        String owner = buildingId == null ? "" : buildingId.toString();
        return owner + "@" + seat.getX() + "," + seat.getY() + "," + seat.getZ();
    }
}
