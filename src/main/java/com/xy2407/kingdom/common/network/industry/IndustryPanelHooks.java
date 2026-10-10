package com.xy2407.kingdom.common.network.industry;

import java.util.function.Consumer;

/**
 * 工业面板回包的落地钩子，由客户端在 setup 时指向具体实现
 */
public final class IndustryPanelHooks {

    public static Consumer<IndustryPanelStatePacket> stateSink = packet -> {
    };

    private IndustryPanelHooks() {
    }
}
