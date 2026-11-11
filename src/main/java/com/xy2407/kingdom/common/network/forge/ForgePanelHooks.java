package com.xy2407.kingdom.common.network.forge;

import java.util.function.Consumer;

/**
 * 锻造面板的客户端回调挂载点，避免共用代码直接引用客户端界面类
 */
public final class ForgePanelHooks {

    public static volatile Consumer<ForgePanelStatePacket> stateSink = packet -> {
    };

    private ForgePanelHooks() {
    }
}
