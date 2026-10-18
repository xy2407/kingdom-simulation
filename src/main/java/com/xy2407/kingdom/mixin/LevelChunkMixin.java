package com.xy2407.kingdom.mixin;

import com.xy2407.kingdom.common.path.SnapshotRepository;
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
 * 方块变化时给寻路快照区块打脏标记，注入 setBlockState 覆盖全部改块路径
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
        SnapshotRepository repository = SnapshotRepository.get(serverLevel.getServer());
        if (repository != null) {
            repository.markDirty(serverLevel, pos);
        }
        UnitSnapshotStore.INSTANCE.markDirty(serverLevel.dimension().location().toString(),
                pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4, serverLevel.getGameTime());
    }
}
