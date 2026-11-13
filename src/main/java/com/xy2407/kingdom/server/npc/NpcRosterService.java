package com.xy2407.kingdom.server.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        String lordId = player.getUUID().toString();
        List<NpcRosterPacket.Entry> entries = new ArrayList<>();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !dimension.equals(npc.getDimension())
                    || !lordId.equals(npc.getOwnerId())) {
                continue;
            }
            int[] coord = coordsOf(npc);
            // 名册同时带「数据层 id」和「当前实体 UUID」：RTS 的选中/编组/队伍都用后者，
            // 前者跨存档稳定，供人口页这类只需要身份的场合使用。
            NpcEntity live = NpcManager.INSTANCE.liveEntity(npc);
            UUID entityId = live == null || live.isRemoved() ? null : live.getUUID();
            entries.add(new NpcRosterPacket.Entry(
                    npc.getId(),
                    entityId,
                    npc.getName(),
                    npc.getSkin(),
                    noteOf(npc),
                    coord[0], coord[1], coord[2]));
        }
        int total = entries.size();
        if (total == 0) {
            PacketDistributor.sendToPlayer(player, new NpcRosterPacket(List.of(), 0, 0));
            return;
        }
        // 市民可能有几千个，单包发不完：按固定块大小切开发，客户端按偏移拼回去
        int pageSize = NpcRosterPacket.MAX_ENTRIES;
        for (int offset = 0; offset < total; offset += pageSize) {
            int end = Math.min(total, offset + pageSize);
            PacketDistributor.sendToPlayer(player,
                    new NpcRosterPacket(new ArrayList<>(entries.subList(offset, end)), total, offset));
        }
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
