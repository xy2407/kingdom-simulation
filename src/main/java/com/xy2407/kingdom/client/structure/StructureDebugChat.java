package com.xy2407.kingdom.client.structure;

import java.util.List;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 建筑预览调试通道：保留调用点但已静默，不再写日志与叠加层。
 */
@OnlyIn(Dist.CLIENT)
public final class StructureDebugChat {

    private StructureDebugChat() {
    }

    public static void chat(String message, Object... args) {
    }

    public static void once(String key, String message, Object... args) {
    }

    public static List<String> lines() {
        return List.of();
    }

    public static void reset() {
    }
}
