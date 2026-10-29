package com.xy2407.kingdom.mixin;

import com.xy2407.kingdom.server.path.UnitSnapshotStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 方块变化时给单元寻路快照打脏标记，注入 setBlockState 覆盖全部改块路径
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void kingdomMarkSnapshotDirty(BlockPos pos, BlockState state, boolean isMoving,
                                          CallbackInfoReturnable<BlockState> callback) {
        BlockState previous = callback.getReturnValue();
        if (previous == null || previous == state) {
            return;
        }
        Level level = ((LevelChunk) (Object) this).getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        String dimension = serverLevel.dimension().location().toString();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        int layer = pos.getY() >> 4;
        long gameTime = serverLevel.getGameTime();
        UnitSnapshotStore.INSTANCE.markDirty(dimension, cx, layer, cz, gameTime);
        int localY = pos.getY() & 15;
        if (localY == 15) {
            UnitSnapshotStore.INSTANCE.markDirty(dimension, cx, layer + 1, cz, gameTime);
        } else if (localY == 0) {
            UnitSnapshotStore.INSTANCE.markDirty(dimension, cx, layer - 1, cz, gameTime);
        }
    }
}
