package com.xy2407.kingdom.common.network.faction;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.server.level.ServerPlayer;

/**
 * 阵营网络包两侧的落地钩子。
 *
 * <p>由 setup 阶段各自指向实现：客户端指到 {@code FactionClientState}（缓存状态并按需弹界面），
 * 服务端指到 {@code FactionService}（落库）。包本身只做编解码，不掺业务。</p>
 */
public final class FactionHooks {

    /** 服务端 → 客户端：收到阵营状态后做什么（弹择旗 / 弹建城 / 只刷新）。 */
    public static Consumer<FactionStatePacket> stateSink = packet -> {
    };

    /** 客户端 → 服务端：选国或建国。 */
    public static BiConsumer<ServerPlayer, FactionSelectPacket> selectHandler = (player, packet) -> {
    };

    /** 客户端 → 服务端：在指定市政厅上立领地。 */
    public static BiConsumer<ServerPlayer, TerritoryCreatePacket> territoryCreateHandler = (player, packet) -> {
    };

    private FactionHooks() {
    }
}
