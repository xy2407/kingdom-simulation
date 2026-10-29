package com.xy2407.kingdom.common.network.clientbound;

import java.util.List;

import com.xy2407.kingdom.common.network.rts.RtsTeamRosterPacket;

/**
 * 队伍名单的客户端落点：缓存成员名字与皮肤，供 AUI 面板渲染头像
 */
public final class RtsTeamRosterBridge {

    public interface Sink {
        void accept(List<RtsTeamRosterPacket.Member> members);
    }

    private static volatile Sink sink;

    private RtsTeamRosterBridge() {
    }

    public static void setSink(Sink value) {
        sink = value;
    }

    public static void accept(List<RtsTeamRosterPacket.Member> members) {
        Sink current = sink;
        if (current != null) {
            current.accept(members);
        }
    }
}
