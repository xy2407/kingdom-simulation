package com.xy2407.kingdom.client.rts;

import com.xy2407.kingdom.common.network.clientbound.RtsTeamClientBridge;
import com.xy2407.kingdom.common.network.rts.RtsTeamSyncPacket;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.UUID;

/** 客户端队伍缓存：接收服务端同步的队伍列表，供左侧栏与阵型编辑器读取。 */
@OnlyIn(Dist.CLIENT)
public final class RtsTeamClientCache {

    private static volatile List<RtsTeamSyncPacket.Entry> teams = List.of();

    private RtsTeamClientCache() {
    }

    public static void install() {
        RtsTeamClientBridge.setSink(RtsTeamClientCache::accept);
    }

    private static void accept(List<RtsTeamSyncPacket.Entry> synced) {
        teams = synced == null ? List.of() : List.copyOf(synced);
    }

    public static List<RtsTeamSyncPacket.Entry> teams() {
        return teams;
    }

    public static RtsTeamSyncPacket.Entry first() {
        return teams.isEmpty() ? null : teams.get(0);
    }

    public static RtsTeamSyncPacket.Entry byId(UUID teamId) {
        if (teamId == null) {
            return null;
        }
        for (RtsTeamSyncPacket.Entry entry : teams) {
            if (teamId.equals(entry.teamId())) {
                return entry;
            }
        }
        return null;
    }

    public static String describe() {
        if (teams.isEmpty()) {
            return "无队伍";
        }
        return teams.size() + " 支 · " + teams.get(0).members().size() + " 人";
    }
}
