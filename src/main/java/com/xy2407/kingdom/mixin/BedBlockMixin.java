package com.xy2407.kingdom.mixin;

import com.xy2407.kingdom.common.home.HomeBeds;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 床被移除时立刻解绑住户，注入 onRemove 覆盖全部移除路径
 */
@Mixin(BlockBehaviour.class)
public abstract class BedBlockMixin {

    @Inject(method = "onRemove", at = @At("TAIL"))
    private void kingdomUnbindResidentOnBedRemoved(BlockState state, Level level, BlockPos pos,
                                                   BlockState newState, boolean movedByPiston,
                                                   CallbackInfo callback) {
        if (!(state.getBlock() instanceof BedBlock)) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (newState.is(state.getBlock())) {
            return;
        }
        HomeBeds.onBedRemoved(serverLevel, pos, state);
    }
}
