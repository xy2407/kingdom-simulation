package com.xy2407.kingdom.server.rts;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.network.rts.RtsOpsCommandPacket;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * RTS 指挥动作服务：坚守把市民钉在原地，进攻让市民进入主动索敌状态
 */
public final class RtsOpsService {

    private static final Set<UUID> HOLDING = ConcurrentHashMap.newKeySet();

    private RtsOpsService() {
    }

    public static void apply(ServerPlayer player, int action) {
        Set<UUID> selected = RtsSelectionService.getSelected(player);
        if (selected.isEmpty()) {
            return;
        }
        if (action == RtsOpsCommandPacket.ACTION_HOLD) {
            for (UUID id : selected) {
                Entity entity = RtsSelectionService.findEntity(player.serverLevel(), id);
                if (entity instanceof NpcEntity npc) {
                    cancelPatrolOf(player, id);
                    stopInPlace(npc);
                    npc.setCombatHold(true);
                    HOLDING.add(id);
                }
            }
            return;
        }
        if (action == RtsOpsCommandPacket.ACTION_ATTACK) {
            for (UUID id : selected) {
                HOLDING.remove(id);
                Entity entity = RtsSelectionService.findEntity(player.serverLevel(), id);
                if (entity instanceof NpcEntity npc) {
                    cancelPatrolOf(player, id);
                    npc.setCombatAttack();
                }
            }
        }
    }

    /** 坚守/进攻是玩家显式命令：队伍若在巡逻，先撤掉巡逻安排，避免两套逻辑互相拉扯。 */
    private static void cancelPatrolOf(ServerPlayer player, UUID npcId) {
        RtsPatrolService.cancelForMember(player.serverLevel(), npcId);
    }

    private static void stopInPlace(NpcEntity npc) {
        npc.clearMoveTargets();
        npc.cancelRtsOrder();
        npc.getNavigation().stop();
        npc.setDeltaMovement(Vec3.ZERO);
        npc.setZza(0.0F);
        npc.setXxa(0.0F);
        npc.setJumping(false);
    }

    public static boolean isHolding(UUID entityId) {
        return entityId != null && HOLDING.contains(entityId);
    }

    /** 从存档还原坚守指令时把市民重新挂进坚守集合，队伍跟随才不会把它拽走。 */
    public static void markHolding(UUID entityId) {
        if (entityId != null) {
            HOLDING.add(entityId);
        }
    }

    public static void clearHolding(UUID entityId) {
        if (entityId != null) {
            HOLDING.remove(entityId);
        }
    }

    public static void clearAll() {
        HOLDING.clear();
    }
}
