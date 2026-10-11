package com.xy2407.kingdom.server.npc;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.common.network.npc.NpcRosterPacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcSchedule;
import com.xy2407.kingdom.common.npc.NpcSubState;

import net.minecraft.server.level.ServerPlayer;
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
            entries.add(new NpcRosterPacket.Entry(
                    npc.getId(),
                    npc.getName(),
                    npc.getSkin(),
                    noteOf(npc),
                    (int) Math.floor(npc.getX()),
                    (int) Math.floor(npc.getY()),
                    (int) Math.floor(npc.getZ())));
        }
        PacketDistributor.sendToPlayer(player, new NpcRosterPacket(entries));
    }

    private static String noteOf(NpcData npc) {
        if (npc.getBrain().isStarving()) {
            return "饥饿";
        }
        return NpcSchedule.subState(npc.getShift(), npc.getDayTime()) == NpcSubState.WORKING ? "在岗" : "休息";
    }
}
