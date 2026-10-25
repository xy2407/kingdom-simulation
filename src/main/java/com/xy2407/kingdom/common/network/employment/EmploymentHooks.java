package com.xy2407.kingdom.common.network.employment;

import java.util.function.Consumer;

/**
 * 雇佣界面客户端挂点：状态包到达时打开界面，由客户端启动时装配
 */
public final class EmploymentHooks {

    public static Consumer<EmploymentPanelStatePacket> openSink = packet -> {
    };

    private EmploymentHooks() {
    }
}
