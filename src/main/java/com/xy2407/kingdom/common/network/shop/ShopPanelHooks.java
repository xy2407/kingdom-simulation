package com.xy2407.kingdom.common.network.shop;

import java.util.function.Consumer;

/**
 * common 侧留给客户端的商铺面板落地点：默认空实现，客户端启动时装真货
 */
public final class ShopPanelHooks {

    public static volatile Consumer<ShopPanelStatePacket> stateSink = packet -> {
    };

    private ShopPanelHooks() {
    }
}
