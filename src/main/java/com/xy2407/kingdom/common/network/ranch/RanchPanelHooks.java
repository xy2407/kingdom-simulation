package com.xy2407.kingdom.common.network.ranch;

import java.util.function.Consumer;

/**
 * 养殖面板回包的落地钩子，由客户端在 setup 时指向具体实现
 */
public final class RanchPanelHooks {

    public static Consumer<RanchPanelStatePacket> stateSink = packet -> {
    };

    private RanchPanelHooks() {
    }
}
