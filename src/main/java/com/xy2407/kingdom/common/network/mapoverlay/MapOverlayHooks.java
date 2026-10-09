package com.xy2407.kingdom.common.network.mapoverlay;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.server.level.ServerPlayer;

/**
 * 地图叠层网络包两侧的落地钩子：setup 阶段分别指向客户端缓存与服务端采集器。
 */
public final class MapOverlayHooks {

    public static BiConsumer<ServerPlayer, MapOverlayRequestPacket> requestHandler = (player, packet) -> {
    };

    public static Consumer<MapOverlayDataPacket> dataSink = packet -> {
    };

    private MapOverlayHooks() {
    }
}
