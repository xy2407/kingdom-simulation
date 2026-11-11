package com.xy2407.kingdom.client.block;

import net.minecraft.core.Direction;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** 货架九宫格几何：三层台阶的顶面高度、三列横向位置，以及朝向换算。 */
@OnlyIn(Dist.CLIENT)
public final class ShopShelfLayout {

    public static final double[] ROW_Y = {14.0D / 16.0D, 12.0D / 16.0D, 10.0D / 16.0D};

    public static final double[] COL_X = {4.0D / 16.0D, 8.0D / 16.0D, 12.0D / 16.0D};

    public static final double[] ROW_Z = {4.0D / 16.0D, 8.8D / 16.0D, 13.6D / 16.0D};

    private ShopShelfLayout() {
    }

    public static double[] localToWorld(Direction facing, double lx, double lz) {
        return switch (facing) {
            case WEST -> new double[]{lz, 1.0D - lx};
            case NORTH -> new double[]{1.0D - lx, 1.0D - lz};
            case EAST -> new double[]{1.0D - lz, lx};
            default -> new double[]{lx, lz};
        };
    }

    public static double[] worldToLocal(Direction facing, double dx, double dz) {
        return switch (facing) {
            case WEST -> new double[]{1.0D - dz, dx};
            case NORTH -> new double[]{1.0D - dx, 1.0D - dz};
            case EAST -> new double[]{dz, 1.0D - dx};
            default -> new double[]{dx, dz};
        };
    }

    public static int slotAt(double lx, double lz) {
        if (lx < 0.0D || lx > 1.0D || lz < 0.0D || lz > 1.0D) {
            return -1;
        }
        int row;
        if (lz < 0.4D) {
            row = 0;
        } else if (lz < 0.7D) {
            row = 1;
        } else {
            row = 2;
        }
        int col;
        if (lx < 0.375D) {
            col = 0;
        } else if (lx < 0.625D) {
            col = 1;
        } else {
            col = 2;
        }
        return row * 3 + col;
    }
}
