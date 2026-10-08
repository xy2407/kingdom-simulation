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
 * 看门狗的心跳接线：每帧打心跳、记慢帧面包屑与开屏事件。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class ClientWatchdogEvents {

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
