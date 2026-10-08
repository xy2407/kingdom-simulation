package com.xy2407.kingdom.client.npc;

import com.xy2407.kingdom.common.network.npc.NpcBackpackOpenPacket;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 客户端保存当前打开的是哪个市民的行装，供左侧模型与自定义元素取用。
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

    public static int entityId() {
        return snapshot == null ? -1 : snapshot.entityId();
    }

    public static void clear() {
        snapshot = null;
    }
}
