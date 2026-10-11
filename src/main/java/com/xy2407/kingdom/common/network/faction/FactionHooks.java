package com.xy2407.kingdom.common.network.faction;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.server.level.ServerPlayer;

/**
 * 阵营网络包两侧的落地钩子：setup 阶段分别指向客户端与服务端的实现。
 */
public final class FactionHooks {

    public static Consumer<FactionStatePacket> stateSink = packet -> {
    };

    public static BiConsumer<ServerPlayer, FactionSelectPacket> selectHandler = (player, packet) -> {
    };

    public static BiConsumer<ServerPlayer, TerritoryCreatePacket> territoryCreateHandler = (player, packet) -> {
    };

    public static BiConsumer<ServerPlayer, TerritoryBuyPacket> territoryBuyHandler = (player, packet) -> {
    };

    private FactionHooks() {
    }
}
