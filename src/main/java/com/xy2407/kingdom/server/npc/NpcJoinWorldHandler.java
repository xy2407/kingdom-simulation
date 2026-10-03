package com.xy2407.kingdom.server.npc;

import com.xy2407.kingdom.common.entity.NpcEntity;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * NPC实体加入世界时回写坐标并立即提交入库
 */
public final class NpcJoinWorldHandler {
    private NpcJoinWorldHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof NpcEntity npc) {
            NpcEntity.onJoinWorld(npc);
        }
    }
}
