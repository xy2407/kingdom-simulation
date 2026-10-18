package com.xy2407.kingdom.common.network.builder;

import java.util.function.Consumer;

/**
 * 建筑工面板的客户端回调壳，客户端启动时把真实界面处理器塞进来
 */
public final class BuilderHooks {

    public static volatile Consumer<BuilderOpenPacket> openSink = packet -> {
    };

    public static volatile Consumer<BuilderBoardDataPacket> dataSink = packet -> {
    };

    private BuilderHooks() {
    }
}
