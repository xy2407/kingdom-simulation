package com.xy2407.kingdom.common.path;

import java.util.Set;

import com.xy2407.kingdom.server.road.RoadStore;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 单元快照采集：语义与整列 ChunkSnapshot 一致，粒度 16³，并补记段底跨层站立点。
 */
public final class UnitSnapshotFactory {

    private UnitSnapshotFactory() {
    }

    public static boolean isSealed(Level level, int cx, int layer, int cz) {
        if (!level.hasChunk(cx, cz)
                || !level.hasChunk(cx - 1, cz) || !level.hasChunk(cx + 1, cz)
                || !level.hasChunk(cx, cz - 1) || !level.hasChunk(cx, cz + 1)) {
            return false;
        }
        int baseX = cx << 4;
        int baseY = layer << 4;
        int baseZ = cz << 4;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int side = 0; side < 2; side++) {
            int y = side == 0 ? baseY - 1 : baseY + 16;
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    cursor.set(baseX + lx, y, baseZ + lz);
                    if (ChunkSnapshot.isPassable(level.getBlockState(cursor))) {
                        return false;
                    }
                }
            }
        }
        for (int side = 0; side < 2; side++) {
            int x = side == 0 ? baseX - 1 : baseX + 16;
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    cursor.set(x, baseY + ly, baseZ + lz);
                    if (ChunkSnapshot.isPassable(level.getBlockState(cursor))) {
                        return false;
                    }
                }
            }
        }
        for (int side = 0; side < 2; side++) {
            int z = side == 0 ? baseZ - 1 : baseZ + 16;
            for (int ly = 0; ly < 16; ly++) {
                for (int lx = 0; lx < 16; lx++) {
                    cursor.set(baseX + lx, baseY + ly, z);
                    if (ChunkSnapshot.isPassable(level.getBlockState(cursor))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public static UnitSnapshot build(Level level, int cx, int layer, int cz) {
        int minLayer = level.getMinBuildHeight() >> 4;
        int maxLayer = (level.getMaxBuildHeight() - 1) >> 4;
        if (layer < minLayer || layer > maxLayer || !level.hasChunk(cx, cz)) {
            return null;
        }
        int baseY = layer << 4;
        int baseX = cx << 4;
        int baseZ = cz << 4;
        Set<Long> roads = RoadStore.INSTANCE.roadCells(level.dimension().location().toString());
        LevelChunkSection section = level.getChunk(cx, cz).getSections()[layer - minLayer];
        boolean airOnly = section.hasOnlyAir();
        BlockState air = Blocks.AIR.defaultBlockState();
        UnitSnapshot.Builder builder = UnitSnapshot.builder();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                builder.beginColumn(lx * 16 + lz);
                BlockState above = air;
                BlockState aboveTwo = air;
                for (int ly = airOnly ? 0 : 15; ly >= 0; ly--) {
                    cursor.set(baseX + lx, baseY + ly, baseZ + lz);
                    BlockState current = airOnly ? air : section.getBlockState(lx, ly, lz);
                    boolean ladder = !airOnly && ChunkSnapshot.isLadderLike(current);
                    boolean scaffold = !airOnly && ChunkSnapshot.isClimbableFloor(current);
                    if (ladder) {
                        builder.addStand(ly, UnitSnapshot.KIND_CLIMB, UnitSnapshot.HAZARD_NONE,
                                roads.contains(cursor.asLong()));
                    } else if (!airOnly && ChunkSnapshot.isWalkableFloor(current)
                            && (scaffold || (ChunkSnapshot.isPassable(above)
                            && ChunkSnapshot.isPassable(aboveTwo)))
                            && ly < 15) {
                        builder.addStand(ly + 1,
                                scaffold ? UnitSnapshot.KIND_CLIMB
                                        : ChunkSnapshot.isHalfFloor(current) ? UnitSnapshot.KIND_HALF
                                        : UnitSnapshot.KIND_WALK,
                                ChunkSnapshot.hazardOf(above),
                                roads.contains(cursor.asLong()));
                    }
                    if (!airOnly && !ChunkSnapshot.isPassable(current) && !ladder && !scaffold) {
                        builder.addBlock(ly, ChunkSnapshot.isCactus(current));
                    }
                    if (ly == 0) {
                        probe.set(baseX + lx, baseY - 1, baseZ + lz);
                        BlockState below = level.getBlockState(probe);
                        boolean belowLadder = ChunkSnapshot.isLadderLike(below);
                        boolean belowScaffold = ChunkSnapshot.isClimbableFloor(below);
                        if (belowLadder) {
                            builder.addStand(0, UnitSnapshot.KIND_CLIMB, UnitSnapshot.HAZARD_NONE,
                                    roads.contains(probe.asLong()));
                        } else if (ChunkSnapshot.isWalkableFloor(below)
                                && (belowScaffold || (ChunkSnapshot.isPassable(current)
                                && ChunkSnapshot.isPassable(above)))) {
                            builder.addStand(0,
                                    belowScaffold ? UnitSnapshot.KIND_CLIMB
                                            : ChunkSnapshot.isHalfFloor(below) ? UnitSnapshot.KIND_HALF
                                            : UnitSnapshot.KIND_WALK,
                                    ChunkSnapshot.hazardOf(current),
                                    roads.contains(probe.asLong()));
                        }
                    }
                    aboveTwo = above;
                    above = current;
                }
            }
        }
        return builder.build();
    }
}
