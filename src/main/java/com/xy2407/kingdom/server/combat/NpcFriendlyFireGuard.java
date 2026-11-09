package com.xy2407.kingdom.server.combat;

import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.server.rts.RtsSelectionService;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 友军火力判定：市民射出的箭矢对「自己人」直接无视——不是命中后取消，而是碰撞检测
 * 阶段就把友军排除（见 mixin ProjectileIgnoreFriendlyFireMixin），箭会真正穿过他们。
 * <p>
 * 判定优先级：优先按领地，没有领地才按归属玩家。
 */
public final class NpcFriendlyFireGuard {

    private NpcFriendlyFireGuard() {
    }

    /** 这支箭（主人为 owner）能不能打中 target：友军一律 false，箭直接穿过。 */
    public static boolean canArrowHit(Entity owner, Entity target) {
        if (!(owner instanceof NpcEntity shooter) || !(target instanceof LivingEntity living)) {
            return true;
        }
        if (shooter == target) {
            return false;
        }
        if (living instanceof NpcEntity other) {
            return !sameFaction(shooter, other);
        }
        if (living instanceof ServerPlayer player) {
            // 自己手下的市民不许射到自己
            return !RtsSelectionService.canControlNpc(player, shooter);
        }
        return true;
    }

    private static boolean sameFaction(NpcEntity first, NpcEntity second) {
        NpcData a = dataOf(first);
        NpcData b = dataOf(second);
        if (a == null || b == null) {
            return false;
        }
        String territoryA = a.getTerritoryId();
        String territoryB = b.getTerritoryId();
        boolean hasTerritoryA = territoryA != null && !territoryA.isBlank();
        boolean hasTerritoryB = territoryB != null && !territoryB.isBlank();
        // 领地优先：两边都有领地时只看领地，领地不同就是敌人——哪怕同属一个玩家。
        if (hasTerritoryA && hasTerritoryB) {
            return territoryA.equals(territoryB);
        }
        // 没有领地（至少一方没有）才回退到归属玩家
        String ownerA = a.getOwnerId();
        String ownerB = b.getOwnerId();
        return ownerA != null && !ownerA.isBlank() && ownerA.equals(ownerB);
    }

    private static NpcData dataOf(NpcEntity npc) {
        return npc.getCitizenId() == null ? null : NpcManager.INSTANCE.getNpc(npc.getCitizenId());
    }
}
