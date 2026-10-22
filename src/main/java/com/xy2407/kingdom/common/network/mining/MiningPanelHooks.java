package com.xy2407.kingdom.common.network.mining;

import java.util.function.Consumer;

/**
 * 矿坑面板状态包到客户端界面的接线点，由客户端启动时注入具体实现。
 */
public final class MiningPanelHooks {

    private static Consumer<MiningPanelStatePacket> stateSink = packet -> {
    };

    private MiningPanelHooks() {
    }

    public static void setStateSink(Consumer<MiningPanelStatePacket> sink) {
        stateSink = sink == null ? packet -> {
        } : sink;
    }

    public static Consumer<MiningPanelStatePacket> stateSink() {
        return stateSink;
    }
}
