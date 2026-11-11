package com.xy2407.kingdom.common.network.industry;

import java.util.function.Consumer;

/**
 * 工业用工面板的客户端回调挂载点，避免共用代码直接引用客户端界面类
 */
public final class IndustryStaffHooks {

    public static volatile Consumer<IndustryStaffStatePacket> stateSink = packet -> {
    };

    private IndustryStaffHooks() {
    }
}
