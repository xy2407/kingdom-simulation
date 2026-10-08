package com.xy2407.kingdom.common.network.rts;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * RTS 选中集合的客户端落地点：common 只留空实现，客户端初始化时挂真货。
 */
public final class RtsClientHooks {

    public static volatile Consumer<Set<UUID>> selectionSink = ids -> {
    };

    private RtsClientHooks() {
    }
}
