package com.xy2407.kingdom.common.network.cityhall;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 市政厅网络包在客户端侧的落地钩子，由客户端在 setup 时指向具体实现。
 */
public final class CityHallHooks {

    /**
     * 开屏回包：参数是市政厅方块的世界 X / Z——界面里的地图按它为中心采样。
     *
     * <p>只负责"打开界面"，界面里填什么数走 {@link #dataSink}。</p>
     */
    public static BiConsumer<Integer, Integer> openSink = (x, z) -> {
    };

    /**
     * 数据回包：界面要填的城镇数据。
     *
     * <p>数据包可能与开屏包任一方先到，所以落地端必须两边都能接：界面还没开就先缓存，
     * 界面已经开了就直接重填一遍（见 {@code CityHallScreen.accept}）。</p>
     */
    public static Consumer<CityHallDataPacket> dataSink = packet -> {
    };

    private CityHallHooks() {
    }
}
