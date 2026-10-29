package com.xy2407.kingdom.server.npc;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.common.network.npc.NpcRosterPacket;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcSchedule;
import com.xy2407.kingdom.common.npc.NpcSubState;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 市民名册下发：把玩家当前维度里活着的市民整份发给客户端，供左栏人口页列卡片。
 */
public final class NpcRosterService {

    private NpcRosterService() {
    }

    public static void respond(ServerPlayer player) {
        if (player == null) {
            return;
        }
        String dimension = player.level().dimension().location().toString();
        List<NpcRosterPacket.Entry> entries = new ArrayList<>();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !dimension.equals(npc.getDimension())) {
                continue;
            }
            int[] coord = coordsOf(npc);
            entries.add(new NpcRosterPacket.Entry(
                    npc.getId(),
                    npc.getName(),
                    npc.getSkin(),
                    noteOf(npc),
                    coord[0], coord[1], coord[2]));
        }
        PacketDistributor.sendToPlayer(player, new NpcRosterPacket(entries));
    }

    private static String noteOf(NpcData npc) {
        if (npc.getBrain().isStarving()) {
            return "饥饿";
        }
        return NpcSchedule.subState(npc.getShift(), npc.getDayTime()) == NpcSubState.WORKING ? "在岗" : "休息";
    }

    private static int[] coordsOf(NpcData npc) {
        NpcEntity entity = NpcManager.INSTANCE.liveEntity(npc);
        if (entity != null && !entity.isRemoved()) {
            return new int[]{(int) Math.floor(entity.getX()), (int) Math.floor(entity.getY()),
                    (int) Math.floor(entity.getZ())};
        }
        return new int[]{ChunkPos.getX(npc.getChunk()) << 4, 64, ChunkPos.getZ(npc.getChunk()) << 4};
    }
}
