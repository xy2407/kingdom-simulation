package com.xy2407.kingdom.common.network.rts;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * common 侧留出的客户端落地点。
 *
 * <p>包处理代码跑在客户端，但类本身属于 common（专用服务端也要加载它做编解码），
 * 所以这里只放一个可替换的 {@link Consumer}，由客户端初始化时接到
 * {@code RtsModeManager.setSelectedEntities} 上，避免 common 直接 import 客户端类。</p>
 */
public final class RtsClientHooks {

    /** 服务端裁决后的选中集合落地入口；客户端未初始化时是个空实现。 */
    public static volatile Consumer<Set<UUID>> selectionSink = ids -> {
    };

    private RtsClientHooks() {
    }
}
