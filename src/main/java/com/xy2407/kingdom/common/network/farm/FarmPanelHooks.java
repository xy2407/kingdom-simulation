package com.xy2407.kingdom.common.network.farm;

import java.util.function.Consumer;

/**
 * 农业面板回包的落地钩子，由客户端在 setup 时指向具体实现
 */
public final class FarmPanelHooks {

    public static Consumer<FarmPanelStatePacket> stateSink = packet -> {
    };

    private FarmPanelHooks() {
    }
}
