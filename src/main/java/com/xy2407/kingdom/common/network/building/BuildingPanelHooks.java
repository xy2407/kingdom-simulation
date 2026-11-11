package com.xy2407.kingdom.common.network.building;

import java.util.function.Consumer;

/**
 * 建筑面板的客户端回调挂载点，避免共用代码直接引用客户端界面类
 */
public final class BuildingPanelHooks {

    public static volatile Consumer<BuildingPanelStatePacket> stateSink = packet -> {
    };

    public static volatile Consumer<BuildingWorkChoicePacket> workChoiceSink = packet -> {
    };

    private BuildingPanelHooks() {
    }
}
