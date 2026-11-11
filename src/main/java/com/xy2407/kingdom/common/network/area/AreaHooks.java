package com.xy2407.kingdom.common.network.area;

import java.util.function.Consumer;

/**
 * 客户端 sink：服务端下发范围后交给 AUI 层/选区状态消费。
 */
public final class AreaHooks {

    public static volatile Consumer<AreaStatePacket> stateSink;

    public static volatile Consumer<AreaOverviewPacket> overviewSink;

    private AreaHooks() {
    }

    public static void accept(AreaStatePacket packet) {
        Consumer<AreaStatePacket> sink = stateSink;
        if (sink != null) {
            sink.accept(packet);
        }
    }

    public static void acceptOverview(AreaOverviewPacket packet) {
        Consumer<AreaOverviewPacket> sink = overviewSink;
        if (sink != null) {
            sink.accept(packet);
        }
    }
}
