package com.xy2407.kingdom.client.structure;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 建筑预览调试通道，把各环节状态写到屏幕叠加层与日志。
 */
@OnlyIn(Dist.CLIENT)
public final class StructureDebugChat {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static final List<String> LINES = new ArrayList<>();
    private static final int MAX_LINES = 14;

    private StructureDebugChat() {
    }

    public static void chat(String message, Object... args) {
        emit(message, args);
    }

    public static void once(String key, String message, Object... args) {
        if (SEEN.add(key)) {
            emit(message, args);
        }
    }

    public static List<String> lines() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    public static void reset() {
        SEEN.clear();
        synchronized (LINES) {
            LINES.clear();
        }
    }

    private static void emit(String message, Object... args) {
        String text = args.length == 0 ? message : String.format(message, args);
        LOGGER.info("[StructurePreview] {}", text);
        synchronized (LINES) {
            LINES.add(text);
            while (LINES.size() > MAX_LINES) {
                LINES.remove(0);
            }
        }
    }
}
