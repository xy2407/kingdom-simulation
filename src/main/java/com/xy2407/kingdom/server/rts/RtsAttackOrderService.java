package com.xy2407.kingdom.server.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.entity.NpcEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;

/**
 * RTS 攻击目标分配：把红框目标随机均摊给选中市民，并支持一键清仇恨
 */
public final class RtsAttackOrderService {

    private RtsAttackOrderService() {
    }

    public static void assign(ServerPlayer player, List<UUID> npcIds, List<UUID> targetIds) {
        if (player == null || npcIds == null || npcIds.isEmpty()
                || targetIds == null || targetIds.isEmpty()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        List<NpcEntity> units = new ArrayList<>();
        for (UUID id : npcIds) {
            Entity entity = RtsSelectionService.findEntity(level, id);
            if (entity instanceof NpcEntity npc && npc.isAlive()
                    && RtsSelectionService.canControl(player, npc)) {
                units.add(npc);
            }
        }
        if (units.isEmpty()) {
            return;
        }
        List<LivingEntity> targets = new ArrayList<>();
        for (UUID id : targetIds) {
            Entity entity = RtsSelectionService.findEntity(level, id);
            if (entity instanceof LivingEntity living && living.isAlive()
                    && entity instanceof Enemy) {
                targets.add(living);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        for (int index = targets.size() - 1; index > 0; index--) {
            int swap = player.getRandom().nextInt(index + 1);
            LivingEntity swapped = targets.get(index);
            targets.set(index, targets.get(swap));
            targets.set(swap, swapped);
        }
        for (int index = 0; index < units.size(); index++) {
            NpcEntity npc = units.get(index);
            LivingEntity target = targets.get(index % targets.size());
            // 巡逻中的队伍：Ctrl 指定目标也按「共享目标」处理，打完继续巡逻，而不是把巡逻整个取消
            if (!RtsPatrolService.engageOn(npc, target)) {
                npc.assignCombatTarget(target);
            }
        }
    }

    public static void clearAggro(ServerPlayer player, List<UUID> npcIds) {
        if (player == null || npcIds == null || npcIds.isEmpty()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        for (UUID id : npcIds) {
            Entity entity = RtsSelectionService.findEntity(level, id);
            if (entity instanceof NpcEntity npc) {
                npc.clearCombatAggro();
            }
        }
    }
}
