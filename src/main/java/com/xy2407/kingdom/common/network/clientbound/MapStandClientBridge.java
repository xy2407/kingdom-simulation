package com.xy2407.kingdom.common.network.clientbound;

import com.xy2407.kingdom.common.network.building.MapStandDataPacket;

/** 地图数据下发到客户端的落点：页面驱动注册后由它接收。 */
public final class MapStandClientBridge {

    public interface Sink {
        void accept(MapStandDataPacket packet);
    }

    private static volatile Sink sink;

    private MapStandClientBridge() {
    }

    public static void setSink(Sink value) {
        sink = value;
    }

    public static void accept(MapStandDataPacket packet) {
        Sink current = sink;
        if (current != null) {
            current.accept(packet);
        }
    }
}
