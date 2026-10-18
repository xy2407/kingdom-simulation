package com.xy2407.kingdom.common.path;

import java.util.Arrays;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
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
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 区块寻路快照：按列存可停留与挡路的格，全高扫描供寻路线程只读查询
 */
public final class ChunkSnapshot {
    private static final int COLUMNS = 256;
    private static final int MAX_LAYERS_PER_COLUMN = 128;
    private static final double SLICE_MIN_Y = 0.1D;
    private static final double BODY_SLICE_MAX_Y = 1.0D;
    private static final double FLOOR_MIN_HEIGHT = 0.3D;
    private static final double MIN_TOP_FOOTPRINT = 0.5D;

    private static final int[] FLAGS = new int[Math.max(1, Block.BLOCK_STATE_REGISTRY.size())];
    static {
        Arrays.fill(FLAGS, -1);
    }
    private static final int FLAG_FLOOR = 1;
    private static final int FLAG_PASSABLE = 2;

    private final int minY;
    private final short[] starts;
    private final short[] levels;
    private final short[] blockStarts;
    private final short[] blocks;
    private final byte[] kinds;
    private final byte[] standHazard;
    private final byte[] blockFlags;
    private final int scanCost;

    private static final byte KIND_WALK = 0;
    private static final byte KIND_CLIMB = 1;
    private static final byte KIND_WALK_HALF = 2;

    public static final byte HAZARD_NONE = 0;
    public static final byte HAZARD_WATER = 1;
    public static final byte HAZARD_LAVA = 2;
    public static final byte HAZARD_FIRE = 3;
    public static final byte HAZARD_POWDER = 4;

    private static final byte BLOCK_CACTUS = 1;

    private ChunkSnapshot(int minY, short[] starts, short[] levels, byte[] kinds,
                          short[] blockStarts, short[] blocks, byte[] standHazard, byte[] blockFlags,
                          int scanCost) {
        this.minY = minY;
        this.starts = starts;
        this.levels = levels;
        this.kinds = kinds;
        this.blockStarts = blockStarts;
        this.blocks = blocks;
        this.standHazard = standHazard;
        this.blockFlags = blockFlags;
        this.scanCost = scanCost;
    }

    public int scanCost() {
        return scanCost;
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

    public boolean blocked(int lx, int y, int lz) {
        int offset = y - minY;
        if (offset < 0) {
            return true;
        }
        int column = lx * 16 + lz;
        for (int i = blockStarts[column]; i < blockStarts[column + 1]; i++) {
            if (blocks[i] == offset) {
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
                return kinds[i] == KIND_CLIMB;
            }
        }
        return false;
    }

    public byte standHazard(int lx, int y, int lz) {
        int offset = y - minY;
        if (offset < 0) {
            return HAZARD_NONE;
        }
        int column = lx * 16 + lz;
        for (int i = starts[column]; i < starts[column + 1]; i++) {
            if (levels[i] == offset) {
                return standHazard[i];
            }
        }
        return HAZARD_NONE;
    }

    public boolean blockedCactus(int lx, int y, int lz) {
        int offset = y - minY;
        if (offset < 0) {
            return false;
        }
        int column = lx * 16 + lz;
        for (int i = blockStarts[column]; i < blockStarts[column + 1]; i++) {
            if (blocks[i] == offset) {
                return (blockFlags[i] & BLOCK_CACTUS) != 0;
            }
        }
        return false;
    }

    public boolean halfFloor(int lx, int y, int lz) {
        int offset = y - minY;
        if (offset < 0) {
            return false;
        }
        int column = lx * 16 + lz;
        for (int i = starts[column]; i < starts[column + 1]; i++) {
            if (levels[i] == offset) {
                return kinds[i] == KIND_WALK_HALF;
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
        byte[] kindBuffer = new byte[COLUMNS * MAX_LAYERS_PER_COLUMN];
        byte[] hazardBuffer = new byte[COLUMNS * MAX_LAYERS_PER_COLUMN];
        short[] blockStarts = new short[COLUMNS + 1];
        short[] blockBuffer = new short[COLUMNS * MAX_LAYERS_PER_COLUMN];
        byte[] flagBuffer = new byte[COLUMNS * MAX_LAYERS_PER_COLUMN];
        int[] counts = {0, 0};
        int[] cost = {0};
        for (int column = 0; column < COLUMNS; column++) {
            starts[column] = (short) counts[0];
            blockStarts[column] = (short) counts[1];
            scanColumn(sections, highest, column / 16, column % 16,
                    buffer, kindBuffer, hazardBuffer, blockBuffer, flagBuffer, counts, cost);
        }
        starts[COLUMNS] = (short) counts[0];
        blockStarts[COLUMNS] = (short) counts[1];
        return new ChunkSnapshot(minY, starts, Arrays.copyOf(buffer, counts[0]),
                Arrays.copyOf(kindBuffer, counts[0]),
                blockStarts, Arrays.copyOf(blockBuffer, counts[1]),
                Arrays.copyOf(hazardBuffer, counts[0]),
                Arrays.copyOf(flagBuffer, counts[1]), cost[0]);
    }

    private static void scanColumn(LevelChunkSection[] sections, int highest, int lx, int lz,
                                   short[] buffer, byte[] kindBuffer, byte[] hazardBuffer,
                                   short[] blockBuffer, byte[] flagBuffer,
                                   int[] counts, int[] cost) {
        int count = counts[0];
        int blockCount = counts[1];
        int found = 0;
        BlockState above = Blocks.AIR.defaultBlockState();
        BlockState aboveTwo = above;
        for (int si = highest; si >= 0 && found < MAX_LAYERS_PER_COLUMN; si--) {
            LevelChunkSection section = sections[si];
            if (section.hasOnlyAir()) {
                above = Blocks.AIR.defaultBlockState();
                aboveTwo = above;
                continue;
            }
            for (int ly = 15; ly >= 0 && found < MAX_LAYERS_PER_COLUMN; ly--) {
                cost[0]++;
                BlockState current = section.getBlockState(lx, ly, lz);
                boolean ladder = isLadderLike(current);
                boolean climbableFloor = isClimbableFloor(current);
                if (ladder) {
                    if (found < MAX_LAYERS_PER_COLUMN) {
                        buffer[count + found] = (short) (si * 16 + ly);
                        kindBuffer[count + found] = KIND_CLIMB;
                        hazardBuffer[count + found] = HAZARD_NONE;
                        found++;
                    }
                } else if (isWalkableFloor(current)
                        && (climbableFloor || (isPassable(above) && isPassable(aboveTwo)))) {
                    buffer[count + found] = (short) (si * 16 + ly + 1);
                    kindBuffer[count + found] = climbableFloor ? KIND_CLIMB
                            : (isHalfFloor(current) ? KIND_WALK_HALF : KIND_WALK);
                    hazardBuffer[count + found] = hazardOf(above);
                    found++;
                }
                if (!isPassable(current) && !ladder && !climbableFloor) {
                    if (blockCount < blockBuffer.length) {
                        blockBuffer[blockCount] = (short) (si * 16 + ly);
                        flagBuffer[blockCount] = isCactus(current) ? BLOCK_CACTUS : (byte) 0;
                        blockCount++;
                    }
                }
                aboveTwo = above;
                above = current;
            }
        }
        counts[0] = count + found;
        counts[1] = blockCount;
    }

    public static boolean isPassable(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        return (flagsOf(state) & FLAG_PASSABLE) != 0;
    }

    public static boolean isWalkableFloor(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        return (flagsOf(state) & FLAG_FLOOR) != 0;
    }

    public static boolean isClimbableFloor(BlockState state) {
        return state.is(Blocks.SCAFFOLDING);
    }

    public static boolean isHalfFloor(BlockState state) {
        if (state.getBlock() instanceof SlabBlock) {
            return state.getValue(SlabBlock.TYPE) != SlabType.DOUBLE;
        }
        if (state.getBlock() instanceof StairBlock) {
            return state.getValue(StairBlock.HALF) == Half.BOTTOM;
        }
        return state.getBlock() instanceof BedBlock;
    }

    public static boolean isLadderLike(BlockState state) {
        return state.getBlock() instanceof LadderBlock || state.getBlock() instanceof VineBlock;
    }

    private static int flagsOf(BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id < 0 || id >= FLAGS.length) {
            return computeFlags(state);
        }
        int flags = FLAGS[id];
        if (flags >= 0) {
            return flags;
        }
        flags = computeFlags(state);
        FLAGS[id] = flags;
        return flags;
    }

    private static int computeFlags(BlockState state) {
        int flags = 0;
        if (computeWalkableFloor(state)) {
            flags |= FLAG_FLOOR;
        }
        if (computePassable(state)) {
            flags |= FLAG_PASSABLE;
        }
        return flags;
    }

    public static byte hazardOf(BlockState state) {
        if (state.is(Blocks.LAVA)) {
            return HAZARD_LAVA;
        }
        if (state.is(Blocks.WATER)) {
            return HAZARD_WATER;
        }
        if (NodeEvaluator.isBurningBlock(state)) {
            return HAZARD_FIRE;
        }
        if (state.is(Blocks.POWDER_SNOW)) {
            return HAZARD_POWDER;
        }
        return HAZARD_NONE;
    }

    public static boolean isCactus(BlockState state) {
        return state.is(Blocks.CACTUS);
    }

    private static boolean computePassable(BlockState state) {
        if (state.is(Blocks.IRON_DOOR) || state.is(Blocks.IRON_TRAPDOOR)) {
            return false;
        }
        if (state.getBlock() instanceof DoorBlock) {
            return true;
        }
        if (state.getBlock() instanceof TrapDoorBlock) {
            return !state.getValue(TrapDoorBlock.OPEN);
        }
        if (state.getBlock() instanceof FenceGateBlock) {
            return state.getValue(FenceGateBlock.OPEN);
        }
        if (state.getBlock() instanceof LadderBlock
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
