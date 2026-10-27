package com.xy2407.kingdom.common.network.clientbound;

import com.xy2407.kingdom.common.network.rts.RtsTeamSyncPacket;

import java.util.List;

/** 队伍同步的客户端落点：common 侧只存一个槽位，具体缓存由客户端注册。 */
public final class RtsTeamClientBridge {

    public interface Sink {
        void accept(List<RtsTeamSyncPacket.Entry> teams);
    }

    private static volatile Sink sink;

    private RtsTeamClientBridge() {
    }

    public static void setSink(Sink value) {
        sink = value;
    }

    public static void accept(List<RtsTeamSyncPacket.Entry> teams) {
        Sink current = sink;
        if (current != null) {
            current.accept(teams);
        }
    }
}
