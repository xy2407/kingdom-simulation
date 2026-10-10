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
    /** 构建这个快照实际扫描了多少格，供仓库按真实成本分帧。 */
    private final int scanCost;

    private ChunkSnapshot(int minY, short[] starts, short[] levels, int scanCost) {
        this.minY = minY;
        this.starts = starts;
        this.levels = levels;
        this.scanCost = scanCost;
    }

    /** 构建时实际扫描的格子数。高楼/破碎地形能到估算值的数倍，仓库据此扣预算。 */
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

    public static ChunkSnapshot build(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int minY = chunk.getMinBuildHeight();
        int highest = Math.min(chunk.getHighestFilledSectionIndex(), sections.length - 1);
        short[] starts = new short[COLUMNS + 1];
        short[] buffer = new short[COLUMNS * MAX_LAYERS_PER_COLUMN];
        int[] cost = {0};
        int count = 0;
        for (int column = 0; column < COLUMNS; column++) {
            starts[column] = (short) count;
            count = scanColumn(sections, highest, column / 16, column % 16, buffer, count, cost);
        }
        starts[COLUMNS] = (short) count;
        return new ChunkSnapshot(minY, starts, Arrays.copyOf(buffer, count), cost[0]);
    }

    private static int scanColumn(LevelChunkSection[] sections, int highest, int lx, int lz,
                                  short[] buffer, int count, int[] cost) {
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
                cost[0]++;
                BlockState current = section.getBlockState(lx, ly, lz);
                boolean ladder = isLadderLike(current);
                boolean climbableFloor = isClimbableFloor(current);
                // 梯子/藤蔓不登记为节点：它们碰撞箱是贴墙薄片、人站不上去，
                // 而"沿竖井上下爬"这个动作实体执行不了 —— assistClimb 只会跳一下，
                // 原版导航又只按 XZ 判到位、几步就把整段竖井 advance 完然后说"到了"。
                // 登记进来只会让 A* 规划出一条走不出来的路，人贴着墙卡死。
                // 它们仍参与下面的 misses 统计：不算"连续阻碍"。
                if (!ladder && isWalkableFloor(current)
                        && (climbableFloor || (isPassable(above) && isPassable(aboveTwo)))) {
                    // 实体地板：记录的是"站在它上面的那一格"
                    buffer[count + found] = (short) (si * 16 + ly + 1);
                    found++;
                }
                if (isPassable(current) || ladder || climbableFloor) {
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

    /**
     * 既是实体地板又能攀爬的方块（脚手架）：踩得上去，也能沿它上下。
     * {@link NpcPathfinder} 对可攀爬格做上下一格的竖直扩展。
     */
    private static boolean isClimbableFloor(BlockState state) {
        return state.is(Blocks.SCAFFOLDING);
    }

    /**
     * 非实体的攀爬格：梯子、藤蔓。
     * 它们碰撞箱是贴墙薄片、站不上去，但可以停靠并上下移动，
     * 所以按"这一格自己"登记为可停留的攀爬节点（见 scanColumn）。
     */
    private static boolean isLadderLike(BlockState state) {
        return state.getBlock() instanceof LadderBlock || state.getBlock() instanceof VineBlock;
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
        if (STATE_FLAGS.size() >= MAX_CACHED_STATES) {
            // 满了就整体清空重来。原来这里是"不再写入"，那样一旦撞上限，
            // 之后每次 flagsOf 都要重算 getCollisionShape/toAabbs，快照构建成本永久翻倍。
            STATE_FLAGS.clear();
        }
        STATE_FLAGS.put(state, flags);
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
