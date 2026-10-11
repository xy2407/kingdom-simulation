package com.xy2407.kingdom.common.network.debug;

import java.util.function.Consumer;

/**
 * NPC 寻路调试包的客户端接收口，由客户端 setup 装配
 */
public final class NpcPathDebugHooks {

    public static volatile Consumer<NpcPathDebugPacket> sink = packet -> {
    };

    private NpcPathDebugHooks() {
    }
}
