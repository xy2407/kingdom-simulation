package com.xy2407.kingdom.common.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/** 饲养槽：沿玩家朝向横向占两格，按原版床的方式成对生成、一起拆解。 */
public class FeedingTroughBlock extends Block {

    public enum Part implements StringRepresentable {
        FIRST("first"),
        SECOND("second");

        private final String value;

        Part(String value) {
            this.value = value;
        }

        @Override
        public String getSerializedName() {
            return value;
        }
    }

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    private static final VoxelShape SHAPE_X = Shapes.box(0.0D, 0.0D, 0.25D, 1.0D, 0.66D, 0.75D);
    private static final VoxelShape SHAPE_Z = Shapes.box(0.25D, 0.0D, 0.0D, 0.75D, 0.66D, 1.0D);

    public FeedingTroughBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, Part.FIRST));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction direction = context.getHorizontalDirection();
        BlockPos other = context.getClickedPos().relative(direction);
        Level level = context.getLevel();
        if (!level.getBlockState(other).canBeReplaced(context)
                || !level.getWorldBorder().isWithinBounds(other)) {
            return null;
        }
        return defaultBlockState().setValue(FACING, direction).setValue(PART, Part.FIRST);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        level.setBlockAndUpdate(pos.relative(state.getValue(FACING)),
                state.setValue(PART, Part.SECOND));
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == otherDirection(state)) {
            boolean paired = neighborState.is(this) && neighborState.getValue(PART) != state.getValue(PART);
            return paired ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    private static Direction otherDirection(BlockState state) {
        Direction facing = state.getValue(FACING);
        return state.getValue(PART) == Part.FIRST ? facing : facing.getOpposite();
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return state.getValue(PART) == Part.FIRST ? super.getDrops(state, params) : List.of();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        return facing.getAxis() == Direction.Axis.X ? SHAPE_X : SHAPE_Z;
    }
}
