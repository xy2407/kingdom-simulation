package com.xy2407.kingdom.client.rts;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * RTS 子模式：默认框选建造，另有道路绘制与区域任务两种模式，鼠标按键按此分派
 */
@OnlyIn(Dist.CLIENT)
public enum RtsSubMode {

    NONE,

    ROAD_TERRITORY,

    ROAD_WORLD,

    REGION_TASK,

    WALL_BUILD
}
