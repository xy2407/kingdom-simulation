package com.xy2407.kingdom.mixin;

import com.xy2407.kingdom.server.farm.FarmLandRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NPC 开垦过的耕地永久湿润：随机刻与计划刻一律跳过
 */
@Mixin(FarmBlock.class)
public abstract class FarmBlockMoistureMixin {

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void kingdomKeepTilledMoisture(BlockState state, ServerLevel level, BlockPos pos,
                                          RandomSource random, CallbackInfo callback) {
        if (FarmLandRegistry.isTilled(level.dimension(), pos)) {
            callback.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void kingdomKeepTilledFromReverting(BlockState state, ServerLevel level, BlockPos pos,
                                                RandomSource random, CallbackInfo callback) {
        if (FarmLandRegistry.isTilled(level.dimension(), pos)) {
            callback.cancel();
        }
    }
}
