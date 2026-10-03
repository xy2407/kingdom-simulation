package com.xy2407.kingdom.client.date;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 客户端当前总天数缓存，由服务端同步包写入
 */
@OnlyIn(Dist.CLIENT)
public final class ClientDateInfo {
    private static volatile int totalDay;

    private ClientDateInfo() {
    }

    public static void setTotalDay(int day) {
        totalDay = day;
    }

    public static int getTotalDay() {
        return totalDay;
    }
}
