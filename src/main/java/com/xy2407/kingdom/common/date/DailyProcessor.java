package com.xy2407.kingdom.common.date;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.server.level.ServerLevel;

/**
 * 日结回调注册与跨天欠账补齐驱动：每 20 刻检查一次，把漏掉的每一天补跑一遍。
 */
public final class DailyProcessor {
    private static final long TICK_INTERVAL = 20L;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<IntConsumer> ON_NEW_DAY = new CopyOnWriteArrayList<>();

    private DailyProcessor() {
    }

    public static void registerOnNewDay(IntConsumer handler) {
        if (handler != null) {
            ON_NEW_DAY.add(handler);
        }
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % TICK_INTERVAL != 0L) {
            return;
        }
        DateSystem.Data data = DateSystem.get(level);
        int total = data.totalDay();
        int processed = data.lastProcessedDay();
        if (total <= processed) {
            return;
        }
        for (int day = processed + 1; day <= total; day++) {
            for (IntConsumer handler : ON_NEW_DAY) {
                try {
                    handler.accept(day);
                } catch (Exception e) {
                    LOGGER.error("日结回调执行失败 day={}", day, e);
                }
            }
        }
        data.commitDay();
    }
}
