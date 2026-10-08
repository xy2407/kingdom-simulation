package com.xy2407.kingdom.common.path;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 区块寻路快照：按列存稀疏的可站立层与可攀爬标记，供寻路线程只读查询。
 */
public final class ChunkSnapshot {
    private static final int COLUMNS = 256;
    private static final int MAX_LAYERS_PER_COLUMN = 32;
    private static final int STOP_AFTER_IMPASSABLE = 4;
    private static final double SLICE_MIN_Y = 0.1D;
    private static final double BODY_SLICE_MAX_Y = 1.0D;
    private static final double FLOOR_MIN_HEIGHT = 0.8D;
    private static final double MIN_TOP_FOOTPRINT = 0.5D;

    private static final Map<BlockState, Integer> STATE_FLAGS = new ConcurrentHashMap<>();
    private static final int FLAG_FLOOR = 1;
    private static final int FLAG_PASSABLE = 2;
    private static final int MAX_CACHED_STATES = 1 << 16;

    private final int minY;
    private final short[] starts;
    private final short[] levels;
    private final byte[] climbs;

    private ChunkSnapshot(int minY, short[] starts, short[] levels, byte[] climbs) {
        this.minY = minY;
        this.starts = starts;
        this.levels = levels;
        this.climbs = climbs;
    }

    public boolean standable(int lx, int y, int lz) {
        int offset = y - minY;
        if (offset < 0) {
            return false;
        }
        int column = lx * 16 + lz;
        for (int i = starts[column]; i < starts[column + 1]; i++) {
            if (levels[i] == offset) {
                return true;
            }
        }
        return false;
    }

    public boolean climbable(int lx, int y, int lz) {
        int offset = y - minY;
        if (offset < 0) {
            return false;
        }
        int column = lx * 16 + lz;
        for (int i = starts[column]; i < starts[column + 1]; i++) {
            if (levels[i] == offset) {
                return climbs[i] != 0;
            }
        }
        return false;
    }

    public static ChunkSnapshot build(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int minY = chunk.getMinBuildHeight();
        int highest = Math.min(chunk.getHighestFilledSectionIndex(), sections.length - 1);
        short[] starts = new short[COLUMNS + 1];
        short[] buffer = new short[COLUMNS * MAX_LAYERS_PER_COLUMN];
        byte[] climbBuffer = new byte[COLUMNS * MAX_LAYERS_PER_COLUMN];
        int count = 0;
        for (int column = 0; column < COLUMNS; column++) {
            starts[column] = (short) count;
            count = scanColumn(sections, highest, column / 16, column % 16, buffer, climbBuffer, count);
        }
        starts[COLUMNS] = (short) count;
        return new ChunkSnapshot(minY, starts, Arrays.copyOf(buffer, count), Arrays.copyOf(climbBuffer, count));
    }

    private static int scanColumn(LevelChunkSection[] sections, int highest, int lx, int lz,
                                  short[] buffer, byte[] climbBuffer, int count) {
        int found = 0;
        int misses = 0;
        BlockState above = Blocks.AIR.defaultBlockState();
        BlockState aboveTwo = above;
        for (int si = highest; si >= 0 && found < MAX_LAYERS_PER_COLUMN && misses < STOP_AFTER_IMPASSABLE; si--) {
            LevelChunkSection section = sections[si];
            if (section.hasOnlyAir()) {
                misses = 0;
                above = Blocks.AIR.defaultBlockState();
                aboveTwo = above;
                continue;
            }
            for (int ly = 15; ly >= 0 && found < MAX_LAYERS_PER_COLUMN && misses < STOP_AFTER_IMPASSABLE; ly--) {
                BlockState current = section.getBlockState(lx, ly, lz);
                boolean climbableFloor = isClimbableFloor(current);
                if (isWalkableFloor(current)
                        && (climbableFloor || (isPassable(above) && isPassable(aboveTwo)))) {
                    buffer[count + found] = (short) (si * 16 + ly + 1);
                    climbBuffer[count + found] = climbableFloor ? (byte) 1 : (byte) 0;
                    found++;
                }
                if (isPassable(current) || climbableFloor) {
                    misses = 0;
                } else {
                    misses++;
                }
                aboveTwo = above;
                above = current;
            }
        }
        return count + found;
    }

    private static boolean isPassable(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        return (flagsOf(state) & FLAG_PASSABLE) != 0;
    }

    private static boolean isWalkableFloor(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        return (flagsOf(state) & FLAG_FLOOR) != 0;
    }

    private static boolean isClimbableFloor(BlockState state) {
        return state.is(Blocks.SCAFFOLDING);
    }

    private static int flagsOf(BlockState state) {
        Integer cached = STATE_FLAGS.get(state);
        if (cached != null) {
            return cached;
        }
        int flags = 0;
        if (computeWalkableFloor(state)) {
            flags |= FLAG_FLOOR;
        }
        if (computePassable(state)) {
            flags |= FLAG_PASSABLE;
        }
        if (STATE_FLAGS.size() < MAX_CACHED_STATES) {
            STATE_FLAGS.put(state, flags);
        }
        return flags;
    }

    private static boolean computePassable(BlockState state) {
        if (NodeEvaluator.isBurningBlock(state) || state.is(Blocks.POWDER_SNOW)) {
            return false;
        }
        if (state.is(Blocks.IRON_DOOR) || state.is(Blocks.IRON_TRAPDOOR)) {
            return false;
        }
        if (state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof LadderBlock
                || state.getBlock() instanceof VineBlock
                || state.getBlock() instanceof CarpetBlock
                || state.getBlock() instanceof SnowLayerBlock
                || state.getBlock() instanceof BasePressurePlateBlock) {
            return true;
        }
        return clearsSlice(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), BODY_SLICE_MAX_Y);
    }

    private static boolean clearsSlice(VoxelShape shape, double maxY) {
        if (shape.isEmpty()) {
            return true;
        }
        for (AABB box : shape.toAabbs()) {
            if (box.maxY > SLICE_MIN_Y && box.minY < maxY) {
                return false;
            }
        }
        return true;
    }

    private static boolean computeWalkableFloor(BlockState state) {
        if (state.is(Blocks.SCAFFOLDING)) {
            return true;
        }
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(Blocks.IRON_BARS)) {
            return false;
        }
        if (state.getBlock() instanceof StairBlock
                || state.getBlock() instanceof SlabBlock
                || state.getBlock() instanceof BedBlock) {
            return true;
        }
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) {
            return false;
        }
        double top = shape.max(Direction.Axis.Y);
        if (top < FLOOR_MIN_HEIGHT) {
            return false;
        }
        for (AABB box : shape.toAabbs()) {
            if (box.maxY >= top - 0.01D
                    && box.maxX - box.minX >= MIN_TOP_FOOTPRINT
                    && box.maxZ - box.minZ >= MIN_TOP_FOOTPRINT) {
                return true;
            }
        }
        return false;
    }
}
