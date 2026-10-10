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
 * 床被移除时立刻解绑住户（原来只在"睡觉时每 tick 轮询"，白天拆床不会被发现）。
 * 走 {@code onRemove} 而不是 {@code BlockEvent.BreakEvent}：onRemove 是所有移除路径
 * （挖掉 / 爆炸 / 活塞推 / 指令清 / 其它模组 destroyBlock）的公共出口，而 BreakEvent
 * 只管玩家挖 —— 白天用 TNT 把床炸了就会漏掉。这与 xy's_nsuk 保护核心方块用的是同一条路。
 * <p>
 * 注意注入点是 {@link BlockBehaviour#onRemove}，不是 {@code BedBlock}：BedBlock 并没有覆写
 * {@code onRemove}（它继承自 BlockBehaviour），挂在 BedBlock 上会报
 * "could not find any targets matching 'onRemove'". 代价是每次方块移除都会进来一次，
 * 所以第一件事就是用最便宜的 instanceof 把非床的方块挡掉。
 */
@Mixin(BlockBehaviour.class)
public abstract class BedBlockMixin {

    @Inject(method = "onRemove", at = @At("TAIL"))
    private void kingdomUnbindResidentOnBedRemoved(BlockState state, Level level, BlockPos pos,
                                                   BlockState newState, boolean movedByPiston,
                                                   CallbackInfo callback) {
        // 挂在所有方块上，先用最便宜的判断挡掉绝大多数调用
        if (!(state.getBlock() instanceof BedBlock)) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        // 同种方块的属性变化（双格床的另一半改 part / occupied）不算被拆，先短路掉，
        // 否则一张床会被处理两次。真正的"被拆"新状态是空气或别的方块。
        if (newState.is(state.getBlock())) {
            return;
        }
        HomeBeds.onBedRemoved(serverLevel, pos, state);
    }
}
