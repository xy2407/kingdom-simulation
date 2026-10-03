package com.xy2407.kingdom.client.npc;

import com.xy2407.kingdom.common.network.npc.NpcBackpackOpenPacket;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 客户端保存"当前打开的是哪个市民的行装"。
 *
 * <p>界面本身是 AUI 的容器屏，页面里的静态节点由 {@link NpcBackpackScreenDriver} 填充；
 * 左侧模型元素和自定义元素没有 Document 访问时机（容器屏的 Document 只在 Screen 内部），
 * 所以它们统一从这里取数据。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class NpcBackpackClientState {

    private static NpcBackpackOpenPacket snapshot;

    private NpcBackpackClientState() {
    }

    public static void accept(NpcBackpackOpenPacket packet) {
        snapshot = packet;
    }

    public static NpcBackpackOpenPacket snapshot() {
        return snapshot;
    }

    /** 要渲染的实体：服务端实体 id，没打开界面时为 -1。 */
    public static int entityId() {
        return snapshot == null ? -1 : snapshot.entityId();
    }

    public static void clear() {
        snapshot = null;
    }
}
