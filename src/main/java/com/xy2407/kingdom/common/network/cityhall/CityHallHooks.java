package com.xy2407.kingdom.common.network.cityhall;

/**
 * 市政厅开屏回包的落地钩子，由客户端在 setup 时指向具体实现
 */
public final class CityHallHooks {

    public static Runnable openSink = () -> {
    };

    private CityHallHooks() {
    }
}
