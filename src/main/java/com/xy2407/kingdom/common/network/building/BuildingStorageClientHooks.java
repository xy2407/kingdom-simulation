package com.xy2407.kingdom.common.network.building;

import java.util.function.Consumer;

/**
 * common 侧留给客户端的可插拔落地点。
 *
 * <p>与 {@code NpcBackpackClientHooks} 同一套路：包定义在 common，
 * 落地实现在 client，这样 common 不必引用任何客户端类。默认空实现，客户端启动时装真货。</p>
 */
public final class BuildingStorageClientHooks {

    /** 收到仓库展示参数时的回调。 */
    public static volatile Consumer<BuildingStorageOpenPacket> openSink = packet -> {
    };

    private BuildingStorageClientHooks() {
    }
}
