package com.xy2407.kingdom.common.network.building;

import java.util.function.Consumer;

/**
 * common 侧留给客户端的住户名单落地点：默认空实现，客户端启动时装真货。
 */
public final class ResidentHooks {

    public static volatile Consumer<ResidentListPacket> listSink = packet -> {
    };

    private ResidentHooks() {
    }
}
