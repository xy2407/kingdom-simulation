package com.xy2407.kingdom.mixin;

import com.xy2407.kingdom.common.path.SnapshotRepository;

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
 * 方块发生变化时，给寻路快照所在的区块打脏标记。
 * <p>
 * 注入 {@link LevelChunk#setBlockState} 的返回处 —— 这是所有方块变化的公共出口：
 * 玩家挖掘与放置、爆炸、活塞推动、红石、作物生长、其它模组改方块，全都会经过这里。
 * 比 {@code BlockEvent.BreakEvent} / {@code EntityPlaceEvent} 那种只管玩家操作的钩子覆盖得全，
 * 代价是每次方块变化都会进来一次，所以 {@link SnapshotRepository#markDirty} 内部
 * 对"还没建过快照的区块"直接短路返回 —— 绝大多数调用会在那里掉头。
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void kingdomMarkSnapshotDirty(BlockPos pos, BlockState state, boolean isMoving,
                                          CallbackInfoReturnable<BlockState> callback) {
        BlockState previous = callback.getReturnValue();
        // 返回的是旧状态：为 null 或与传入的是同一个实例，说明这一格实际没被改动
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
    }
}
