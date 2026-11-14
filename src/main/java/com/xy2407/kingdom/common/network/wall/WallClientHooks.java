package com.xy2407.kingdom.common.network.wall;

import java.util.function.Consumer;

/**
 * 城墙状态/选中包的客户端落点钩子，避免 common 直接引用客户端类
 */
public final class WallClientHooks {

    public static Consumer<WallStatePacket> stateSink = packet -> {
    };

    public static Consumer<WallPickResultPacket> pickResultSink = packet -> {
    };

    private WallClientHooks() {
    }
}
