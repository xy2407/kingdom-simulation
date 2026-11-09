package com.xy2407.kingdom.client.rts;

import java.util.UUID;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 市民实体消失（死亡 / 被移除）时的客户端清理入口。
 * <p>
 * RTS 侧的编组、红框目标、选中集合都是纯客户端缓存，服务端不会替我们清；
 * 不清理的话头像会留在框里、点它还会跳相机，红框也会一直描着一个已经不存在的东西。
 */
@OnlyIn(Dist.CLIENT)
public final class RtsEntityLifecycle {

    private RtsEntityLifecycle() {
    }

    public static void onNpcRemoved(UUID entityId) {
        if (entityId == null) {
            return;
        }
        RtsOpsPanel.onEntityRemoved(entityId);
        RtsAttackTargetState.remove(entityId);
        RtsModeManager.removeEntity(entityId);
    }
}
