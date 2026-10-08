package com.xy2407.kingdom.common.network.npc;

import java.util.function.Consumer;

/**
 * common 侧留给客户端的行装界面落地点：默认空实现，客户端启动时装真货。
 */
public final class NpcBackpackClientHooks {

    public static volatile Consumer<NpcBackpackOpenPacket> openSink = packet -> {
    };

    private NpcBackpackClientHooks() {
    }
}
