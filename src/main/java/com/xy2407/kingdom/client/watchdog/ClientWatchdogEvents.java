package com.xy2407.kingdom.client.watchdog;

import com.xy2407.kingdom.ModMain;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 看门狗的心跳接线。
 *
 * <p>只做"打心跳"和"记面包屑"两件事，全部是几纳秒的字段写，可以放心挂在每帧路径上。</p>
 *
 * <ul>
 *   <li>{@code RenderFrameEvent.Pre} —— 客户端线程真的要出一帧了。这是最准的心跳点：
 *       它卡住 = 画面已经不动了。</li>
 *   <li>{@code RenderFrameEvent.Post} —— 顺手量一帧耗时，超过阈值记条面包屑（不刷屏，
 *       只进环形缓冲，卡死后能在报告里看到"卡死之前帧已经在变慢"）。</li>
 *   <li>{@code ClientTickEvent.Post} —— 逻辑 tick 的心跳，用来区分"渲染卡住但逻辑还在走"。</li>
 *   <li>{@code ScreenEvent.Init.Post} —— 记录开屏事件，报告里就能看到卡死前开了哪个界面。</li>
 * </ul>
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class ClientWatchdogEvents {

    /** 单帧超过这个时长就记一条面包屑。 */
    private static final long SLOW_FRAME_MS = 1500L;

    private static long frameStartNanos;

    private ClientWatchdogEvents() {
    }

    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        frameStartNanos = System.nanoTime();
        ClientWatchdog.heartbeat("frame");
    }

    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        long millis = (System.nanoTime() - frameStartNanos) / 1_000_000L;
        ClientWatchdog.noteFrameMillis(millis);
        if (millis >= SLOW_FRAME_MS) {
            ClientWatchdog.crumb("slow-frame", millis + "ms");
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientWatchdog.heartbeat("tick");
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        ClientWatchdog.crumb("screen-init", event.getScreen().getClass().getName());
    }
}
