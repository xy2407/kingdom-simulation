package com.xy2407.kingdom.common.network.clientbound;

import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.network.rts.RtsTeamMemberPosPacket;

/**
 * 队伍成员坐标同步的客户端落点：common 侧只留一个槽位，缓存由客户端注册
 */
public final class RtsTeamPosBridge {

    public interface Sink {
        void accept(UUID teamId, List<RtsTeamMemberPosPacket.MemberPos> members);
    }

    private static volatile Sink sink;

    private RtsTeamPosBridge() {
    }

    public static void setSink(Sink value) {
        sink = value;
    }

    public static void accept(UUID teamId, List<RtsTeamMemberPosPacket.MemberPos> members) {
        Sink current = sink;
        if (current != null) {
            current.accept(teamId, members);
        }
    }
}
