package com.xy2407.kingdom.common.date;

import java.util.function.IntConsumer;

/**
 * 总天数同步桥接器，解耦公共包对客户端缓存的直接依赖
 */
public final class DateSyncBridge {
    private static IntConsumer handler = value -> {
    };

    private DateSyncBridge() {
    }

    public static void install(IntConsumer receiver) {
        handler = receiver != null ? receiver : value -> {
        };
    }

    public static void handle(int totalDay) {
        handler.accept(totalDay);
    }
}
