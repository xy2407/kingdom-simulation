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
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 方块通行语义单一真相：静态谓词判定可站/通行/半格/攀爬/危险，供单元快照与寻路共用。
 */
public final class ChunkSnapshot {
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

    public static final byte HAZARD_NONE = 0;
    public static final byte HAZARD_WATER = 1;
    public static final byte HAZARD_LAVA = 2;
    public static final byte HAZARD_FIRE = 3;
    public static final byte HAZARD_POWDER = 4;

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
