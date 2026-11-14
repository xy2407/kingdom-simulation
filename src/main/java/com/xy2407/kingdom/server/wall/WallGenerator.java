package com.xy2407.kingdom.server.wall;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.wall.WallBlock;
import com.xy2407.kingdom.common.wall.WallNode;
import com.xy2407.kingdom.common.wall.WallSegment;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 城墙生成器：墙段逐柱产出（顶线线性渐变、地形挖补、墙沿与上城梯），节点直接读 NBT
 */
public final class WallGenerator {

    public static final int WALL_HEIGHT = 9;

    public static final int TOWER_HEIGHT = 12;

    private static final int TOWER_PLATFORM = 9;

    private static final int SECTION_RADIUS = 1;

    private static final int STAIR_MIN_LENGTH = 10;

    private static final int MAX_FOUNDATION_DEPTH = 32;

    /** 城楼/城门 NBT 足迹边长（5×5），墙柱不得侵入该范围 */
    private static final int NODE_SPAN = 5;

    /** 墙柱截面半径（3×3） */
    private static final int WALL_FOOT_RADIUS = 1;

    private WallGenerator() {
    }

    public static int columnCount(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ())) + 1;
    }

    public static List<WallBlock> nodeBlocks(ServerLevel level, WallNode node) {
        List<WallBlock> source = WallStructureReader.read(node.kind().blueprint());
        if (source.isEmpty()) {
            source = com.xy2407.kingdom.common.wall.WallDefaults.blocks(node.kind());
        }
        if (source.isEmpty()) {
            return List.of();
        }
        int sizeX = 0;
        int sizeY = 0;
        int sizeZ = 0;
        for (WallBlock block : source) {
            sizeX = Math.max(sizeX, block.x() + 1);
            sizeY = Math.max(sizeY, block.y() + 1);
            sizeZ = Math.max(sizeZ, block.z() + 1);
        }
        int rotation = ((node.rotation() % 360) + 360) % 360;
        int spanX = rotation == 90 || rotation == 270 ? sizeZ : sizeX;
        int spanZ = rotation == 90 || rotation == 270 ? sizeX : sizeZ;
        BlockPos origin = node.pos();
        Map<Long, WallBlock> structure = new java.util.LinkedHashMap<>(source.size());
        for (WallBlock block : source) {
            int rx = block.x();
            int rz = block.z();
            switch (rotation) {
                case 90 -> {
                    rx = sizeZ - 1 - block.z();
                    rz = block.x();
                }
                case 180 -> {
                    rx = sizeX - 1 - block.x();
                    rz = sizeZ - 1 - block.z();
                }
                case 270 -> {
                    rx = block.z();
                    rz = sizeX - 1 - block.x();
                }
                default -> {
                }
            }
            int wx = origin.getX() + rx;
            int wy = origin.getY() + block.y();
            int wz = origin.getZ() + rz;
            structure.put(BlockPos.asLong(wx, wy, wz),
                    new WallBlock(wx, wy, wz, block.stateId()));
        }
        Map<Long, WallBlock> result = new java.util.LinkedHashMap<>();
        int airId = Block.getId(Blocks.AIR.defaultBlockState());
        int dirtId = Block.getId(Blocks.DIRT.defaultBlockState());
        for (int lx = 0; lx < spanX; lx++) {
            for (int lz = 0; lz < spanZ; lz++) {
                int wx = origin.getX() + lx;
                int wz = origin.getZ() + lz;
                int surface = surfaceAt(level, wx, wz, origin.getY());
                int fillTop = origin.getY() - 1;
                if (fillTop > surface && fillTop - surface <= MAX_FOUNDATION_DEPTH) {
                    for (int y = surface + 1; y <= fillTop; y++) {
                        long key = BlockPos.asLong(wx, y, wz);
                        if (!structure.containsKey(key)) {
                            result.put(key, new WallBlock(wx, y, wz, dirtId));
                        }
                    }
                }
                int digTop = Math.min(surface, origin.getY() + sizeY - 1);
                for (int y = origin.getY(); y <= digTop; y++) {
                    long key = BlockPos.asLong(wx, y, wz);
                    if (!structure.containsKey(key)) {
                        result.put(key, new WallBlock(wx, y, wz, airId));
                    }
                }
            }
        }
        result.putAll(structure);
        return new ArrayList<>(result.values());
    }

    private static int surfaceAt(ServerLevel level, int x, int z, int refY) {
        int surface = WorldSurfaceSampler.walkableSurfaceY(level, x, z, refY);
        if (surface == WorldSurfaceSampler.NO_WALKABLE) {
            surface = WorldSurfaceSampler.walkableSurfaceY(level, x, z,
                    WorldSurfaceSampler.NO_WALKABLE);
        }
        return surface == WorldSurfaceSampler.NO_WALKABLE ? refY - 1 : surface;
    }

    public static List<WallBlock> segmentBlocks(ServerLevel level, WallSegment segment,
                                                BlockPos territoryCenter) {
        int ax = segment.a().getX();
        int az = segment.a().getZ();
        int bx = segment.b().getX();
        int bz = segment.b().getZ();
        int steps = Math.max(Math.abs(bx - ax), Math.abs(bz - az));
        if (steps <= 0) {
            return List.of();
        }
        int dirX = Integer.compare(bx, ax);
        int dirZ = Integer.compare(bz, az);
        int stepX = ax;
        int stepZ = az;
        int err = Math.abs(bx - ax) - Math.abs(bz - az);
        int refY = segment.a().getY();
        List<int[]> columns = new ArrayList<>(steps + 1);
        for (int index = 0; index <= steps; index++) {
            columns.add(new int[] {stepX, stepZ});
            int doubleErr = err * 2;
            if (doubleErr > -Math.abs(bz - az)) {
                err -= Math.abs(bz - az);
                stepX += dirX;
            }
            if (doubleErr < Math.abs(bx - ax)) {
                err += Math.abs(bx - ax);
                stepZ += dirZ;
            }
        }
        List<WallBlock> result = new ArrayList<>();
        Set<Long> placed = new HashSet<>();
        int stairIndex = columns.size() / 2;
        for (int index = 0; index < columns.size(); index++) {
            int[] column = columns.get(index);
            double t = columns.size() <= 1 ? 0.0D : (double) index / (columns.size() - 1);
            double topLine = segment.a().getY() + TOWER_PLATFORM
                    + ((segment.b().getY() + TOWER_PLATFORM) - (segment.a().getY() + TOWER_PLATFORM)) * t;
            int topY = (int) Math.round(topLine);
            int groundY = WorldSurfaceSampler.walkableSurfaceY(level, column[0], column[1], refY);
            if (groundY == WorldSurfaceSampler.NO_WALKABLE) {
                groundY = WorldSurfaceSampler.walkableSurfaceY(level, column[0], column[1],
                        WorldSurfaceSampler.NO_WALKABLE);
            }
            if (groundY == WorldSurfaceSampler.NO_WALKABLE) {
                groundY = refY;
            }
            refY = groundY;
            if (overlapsNode(column[0], column[1], ax, az)
                    || overlapsNode(column[0], column[1], bx, bz)) {
                // 墙柱 3×3 截面与城楼/城门 5×5 足迹相交即整柱跳过：
                // 按两轴包围盒相交判定，横平竖直与斜向（45° 切角穿过足迹）行为一致，
                // 不再出现斜向城墙把城楼/城门方块顶掉、挖穿地基的情况
                continue;
            }
            columnBlocks(level, result, placed, column[0], column[1], groundY, topY);
            if (index == stairIndex && columns.size() > STAIR_MIN_LENGTH) {
                stairBlocks(level, result, placed, column[0], column[1], groundY, topY,
                        dirX, dirZ, territoryCenter);
            }
        }
        return result;
    }

    /**
     * 墙柱 3×3 截面是否与节点 5×5 足迹相交（段端点即节点足迹原点）
     */
    private static boolean overlapsNode(int cx, int cz, int nodeX, int nodeZ) {
        boolean overlapX = cx + WALL_FOOT_RADIUS >= nodeX
                && cx - WALL_FOOT_RADIUS <= nodeX + NODE_SPAN - 1;
        boolean overlapZ = cz + WALL_FOOT_RADIUS >= nodeZ
                && cz - WALL_FOOT_RADIUS <= nodeZ + NODE_SPAN - 1;
        return overlapX && overlapZ;
    }

    private static void columnBlocks(ServerLevel level, List<WallBlock> result, Set<Long> placed,
                                     int x, int z, int groundY, int topY) {
        int base = topY - WALL_HEIGHT + 1;
        for (int dx = -SECTION_RADIUS; dx <= SECTION_RADIUS; dx++) {
            for (int dz = -SECTION_RADIUS; dz <= SECTION_RADIUS; dz++) {
                int cx = x + dx;
                int cz = z + dz;
                int cellGround = WorldSurfaceSampler.walkableSurfaceY(level, cx, cz, groundY);
                if (cellGround == WorldSurfaceSampler.NO_WALKABLE) {
                    cellGround = groundY;
                }
                if (cellGround > topY) {
                    for (int y = topY + 1; y <= cellGround; y++) {
                        add(level, result, placed, cx, y, cz, Blocks.AIR.defaultBlockState());
                    }
                }
                for (int y = cellGround + 1; y < base; y++) {
                    add(level, result, placed, cx, y, cz, Blocks.DIRT.defaultBlockState());
                }
                for (int y = base; y <= topY; y++) {
                    add(level, result, placed, cx, y, cz, Blocks.STONE_BRICKS.defaultBlockState());
                }
                if ((cx + cz) % 2 == 0) {
                    add(level, result, placed, cx, topY + 1, cz,
                            Blocks.STONE_BRICKS.defaultBlockState());
                }
            }
        }
    }

    private static void stairBlocks(ServerLevel level, List<WallBlock> result, Set<Long> placed,
                                    int x, int z, int groundY, int topY,
                                    int dirX, int dirZ, BlockPos center) {
        int perpX = -dirZ;
        int perpZ = dirX;
        int towardX = center.getX() - x;
        int towardZ = center.getZ() - z;
        if (perpX * towardX + perpZ * towardZ < 0) {
            perpX = -perpX;
            perpZ = -perpZ;
        }
        int offsetX = perpX * (SECTION_RADIUS + 1);
        int offsetZ = perpZ * (SECTION_RADIUS + 1);
        int height = Math.min(WALL_HEIGHT, topY - groundY);
        for (int step = 0; step <= height; step++) {
            int y = Math.max(groundY + 1, topY - step);
            int px = x + offsetX + dirX * step;
            int pz = z + offsetZ + dirZ * step;
            add(level, result, placed, px, y, pz, Blocks.STONE_BRICK_STAIRS.defaultBlockState());
            int nx = x + offsetX - dirX * step;
            int nz = z + offsetZ - dirZ * step;
            add(level, result, placed, nx, y, nz, Blocks.STONE_BRICK_STAIRS.defaultBlockState());
        }
    }

    private static void add(ServerLevel level, List<WallBlock> result, Set<Long> placed,
                            int x, int y, int z, BlockState state) {
        long key = BlockPos.asLong(x, y, z);
        if (!placed.add(key)) {
            return;
        }
        result.add(new WallBlock(x, y, z, Block.getId(state)));
    }
}
