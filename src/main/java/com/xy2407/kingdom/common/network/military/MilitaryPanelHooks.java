package com.xy2407.kingdom.common.network.military;

import java.util.function.Consumer;

/**
 * 军事面板的客户端回调挂载点，避免共用代码直接引用客户端界面类
 */
public final class MilitaryPanelHooks {

    public static volatile Consumer<MilitaryPanelStatePacket> stateSink = packet -> {
    };

    private MilitaryPanelHooks() {
    }
}
