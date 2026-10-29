package com.xy2407.kingdom.server.npc;

import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;

/** NPC 离开世界或跨维度时，把最后一次维度与区块回写到市民数据。 */
public final class NpcChunkEvents {

    private NpcChunkEvents() {
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof NpcEntity npc) {
            NpcEntity.onLeaveWorld(npc);
        }
    }

    @SubscribeEvent
    public static void onTravel(EntityTravelToDimensionEvent event) {
        if (!(event.getEntity() instanceof NpcEntity npc)) {
            return;
        }
        NpcData data = npc.getCitizenId() == null ? null : NpcManager.INSTANCE.getNpc(npc.getCitizenId());
        if (data == null) {
            return;
        }
        data.setDimension(event.getDimension().location().toString());
        data.setChunk(npc.chunkPosition().toLong());
    }
}
