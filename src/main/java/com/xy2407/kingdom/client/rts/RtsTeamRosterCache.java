package com.xy2407.kingdom.client.rts;

import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.network.clientbound.RtsTeamRosterBridge;
import com.xy2407.kingdom.common.network.rts.RtsTeamRosterPacket;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 队伍名单缓存：保存服务端推来的成员姓名与皮肤，供 AUI 面板渲染头像
 */
@OnlyIn(Dist.CLIENT)
public final class RtsTeamRosterCache {

    private static volatile List<RtsTeamRosterPacket.Member> members = List.of();

    private RtsTeamRosterCache() {
    }

    public static void install() {
        RtsTeamRosterBridge.setSink(RtsTeamRosterCache::accept);
    }

    private static void accept(List<RtsTeamRosterPacket.Member> synced) {
        members = synced == null ? List.of() : List.copyOf(synced);
    }

    public static RtsTeamRosterPacket.Member of(UUID id) {
        if (id == null) {
            return null;
        }
        for (RtsTeamRosterPacket.Member member : members) {
            if (id.equals(member.uuid())) {
                return member;
            }
        }
        return null;
    }

    public static String nameOf(UUID id) {
        RtsTeamRosterPacket.Member member = of(id);
        if (member != null && !member.name().isBlank()) {
            return member.name();
        }
        return id == null ? "市民" : "市民" + id.toString().substring(0, 4);
    }

    public static String skinOf(UUID id) {
        RtsTeamRosterPacket.Member member = of(id);
        return member == null ? "" : member.skin();
    }

    public static String jobOf(UUID id) {
        RtsTeamRosterPacket.Member member = of(id);
        return member == null || member.job().isBlank() ? "市民" : member.job();
    }
}
