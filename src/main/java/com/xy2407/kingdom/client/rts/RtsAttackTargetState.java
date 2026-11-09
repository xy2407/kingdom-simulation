package com.xy2407.kingdom.client.rts;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * RTS 攻击目标缓存：记录红框选中的敌对生物，供描边渲染与清除仇恨使用
 */
@OnlyIn(Dist.CLIENT)
public final class RtsAttackTargetState {

    private static final Set<UUID> TARGETS = new LinkedHashSet<>();

    private RtsAttackTargetState() {
    }

    public static void set(Set<UUID> ids) {
        TARGETS.clear();
        if (ids != null) {
            TARGETS.addAll(ids);
        }
    }

    public static Set<UUID> ids() {
        return TARGETS;
    }

    public static boolean isEmpty() {
        return TARGETS.isEmpty();
    }

    public static void clear() {
        TARGETS.clear();
    }

    /** 目标死亡/被移除：只摘这一个，别把其它仍然活着的红框一起清掉。 */
    public static void remove(UUID id) {
        if (id != null) {
            TARGETS.remove(id);
        }
    }
}
