package com.xy2407.kingdom.client.farm;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.rts.RtsModeManager;
import com.xy2407.kingdom.client.rts.RtsPicker;
import com.xy2407.kingdom.common.network.farm.FarmPanelActionPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * 农田范围的 RTS 框选：拖动屏幕矩形投影到地面，得到水平范围后发包写回
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
public final class FarmAreaSelector {

    private static final int BOX_BORDER = 0xFFE8D9A8;
    private static final int BOX_FILL = 0x40203040;
    private static final String HINT = "拖拽框选农田范围（松开确认）";

    private static UUID targetPlot;
    private static boolean dragging;
    private static double startX;
    private static double startY;
    private static double endX;
    private static double endY;

    private FarmAreaSelector() {
    }

    public static void begin(UUID plotId) {
        if (plotId == null) {
            return;
        }
        targetPlot = plotId;
        dragging = false;
        if (!RtsModeManager.isActive()) {
            RtsModeManager.enter();
        }
        hint(HINT);
    }

    public static boolean isActive() {
        return targetPlot != null;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!isActive()) {
            return;
        }
        event.setCanceled(true);
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }
        if (event.getAction() == InputConstants.PRESS) {
            double[] mouse = mouse();
            startX = mouse[0];
            startY = mouse[1];
            endX = mouse[0];
            endY = mouse[1];
            dragging = true;
        } else if (dragging) {
            dragging = false;
            finish();
        }
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        if (isActive() && dragging) {
            double[] mouse = mouse();
            endX = mouse[0];
            endY = mouse[1];
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!isActive() || !dragging) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int x1 = (int) Math.round(startX);
        int y1 = (int) Math.round(startY);
        int x2 = (int) Math.round(endX);
        int y2 = (int) Math.round(endY);
        int left = Math.min(x1, x2);
        int top = Math.min(y1, y2);
        int right = Math.max(x1, x2);
        int bottom = Math.max(y1, y2);
        graphics.fill(left, top, right, bottom, BOX_FILL);
        graphics.fill(left, top, right, top + 1, BOX_BORDER);
        graphics.fill(left, bottom - 1, right, bottom, BOX_BORDER);
        graphics.fill(left, top, left + 1, bottom, BOX_BORDER);
        graphics.fill(right - 1, top, right, bottom, BOX_BORDER);
    }

    private static void finish() {
        UUID plotId = targetPlot;
        Vec3 first = RtsPicker.pickTargetBlock(startX, startY);
        Vec3 second = RtsPicker.pickTargetBlock(endX, endY);
        reset();

        if (plotId == null || first == null || second == null) {
            hint("框选失败：没能打到地面，请从俯视角度重新框");
            reopen();
            return;
        }
        int minX = (int) Math.floor(Math.min(first.x, second.x));
        int maxX = (int) Math.floor(Math.max(first.x, second.x));
        int minZ = (int) Math.floor(Math.min(first.z, second.z));
        int maxZ = (int) Math.floor(Math.max(first.z, second.z));
        PacketDistributor.sendToServer(FarmPanelActionPacket.setArea(plotId, minX, minZ, maxX, maxZ));
        reopen();
    }

    private static void reopen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        minecraft.setScreen(new FarmPanelScreen());
    }

    public static void cancel() {
        if (isActive()) {
            reset();
        }
    }

    private static void reset() {
        targetPlot = null;
        dragging = false;
    }

    private static double[] mouse() {
        Minecraft minecraft = Minecraft.getInstance();
        double scale = minecraft.getWindow().getGuiScale();
        return new double[] {
                minecraft.mouseHandler.xpos() / scale,
                minecraft.mouseHandler.ypos() / scale
        };
    }

    private static void hint(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), true);
        }
    }
}
