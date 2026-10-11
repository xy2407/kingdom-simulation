package com.xy2407.kingdom.common.network.npc;

import java.util.function.Consumer;

/**
 * common 侧留给客户端的市民名册落地点：默认空实现，客户端启动时装真货。
 */
public final class NpcRosterHooks {

    public static volatile Consumer<NpcRosterPacket> rosterSink = packet -> {
    };

    private NpcRosterHooks() {
    }
}
