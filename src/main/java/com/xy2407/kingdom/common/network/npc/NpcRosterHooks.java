package com.xy2407.kingdom.common.network.npc;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * common 侧留给客户端的市民名册 / 移除通知落地点：默认空实现，客户端启动时装真货。
 */
public final class NpcRosterHooks {

    public static volatile Consumer<NpcRosterPacket> rosterSink = packet -> {
    };

    /** 市民实体死亡或被移除时回调（参数为实体 UUID）。 */
    public static volatile Consumer<UUID> removedSink = uuid -> {
    };

    private NpcRosterHooks() {
    }
}
