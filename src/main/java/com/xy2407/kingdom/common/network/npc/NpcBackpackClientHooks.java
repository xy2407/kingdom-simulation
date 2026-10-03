package com.xy2407.kingdom.common.network.npc;

import java.util.function.Consumer;

/**
 * common 侧留给客户端的可插拔落地点。
 *
 * <p>和 RTS 那边的 {@code RtsClientHooks} 同一套路：包在 common 里，落地实现在 client，
 * 这样 common 包不必引用任何客户端类。默认是空实现，客户端启动时装上真货。</p>
 */
public final class NpcBackpackClientHooks {

    /** 收到行装展示数据时的回调。 */
    public static volatile Consumer<NpcBackpackOpenPacket> openSink = packet -> {
    };

    private NpcBackpackClientHooks() {
    }
}
