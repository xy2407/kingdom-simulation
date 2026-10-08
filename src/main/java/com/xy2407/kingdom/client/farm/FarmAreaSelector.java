package com.xy2407.kingdom.client.farm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.rts.RtsModeManager;
import com.xy2407.kingdom.client.rts.RtsPicker;
import com.xy2407.kingdom.common.farm.FarmRegion;
import com.xy2407.kingdom.common.network.farm.FarmPanelActionPacket;
import com.xy2407.kingdom.common.network.farm.FarmPanelStatePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * 农田范围框选：左键拖拽可多次累加、重复格自动去重，Enter 保存、Esc 清空全部退出。
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
public final class FarmAreaSelector {

    private static final int BOX_BORDER = 0xFFE8D9A8;
    private static final int BOX_FILL = 0x40203040;
    private static final int COUNT_TEXT = 0xFFFFF0C0;
    private static final int COUNT_BACK = 0xB0202030;
    private static final int COUNT_BORDER = 0xFFE8D9A8;

    private static final int MAX_CELLS = 65536;

    private static UUID targetPlot;
    private static boolean draftMode;

    private static boolean dragging;
    private static double startX;
    private static double startY;
    private static double endX;
    private static double endY;

    private static final List<int[]> BLOCKS = new ArrayList<>();
    private static final Set<Long> CELLS = new HashSet<>();
    private static final List<FarmRegion> EXTRA = new ArrayList<>();
    private static int[] live;

    private static int minX;
    private static int minZ;
    private static int maxX;
    private static int maxZ;
    private static int groundY;
    private static int cells;

    private FarmAreaSelector() {
    }

    public static void beginNew() {
        targetPlot = null;
        draftMode = true;
        clearSelection();
        enterRts();
        hint("左键拖拽框选地面，可多次累加；Enter 保存 / Esc 清空全部");
    }

    public static void begin(UUID plotId) {
        if (plotId == null) {
            return;
        }
        targetPlot = plotId;
        draftMode = false;
        clearSelection();
        enterRts();
        hint("左键拖拽框选农田范围，可多次累加；Enter 保存 / Esc 清空全部");
    }

    public static boolean isActive() {
        return draftMode || targetPlot != null;
    }

    public static boolean hasRect() {
        return !CELLS.isEmpty();
    }

    public static int minX() {
        return minX;
    }

    public static int minZ() {
        return minZ;
    }

    public static int maxX() {
        return maxX;
    }

    public static int maxZ() {
        return maxZ;
    }

    public static int groundY() {
        return groundY;
    }

    public static int cells() {
        return cells;
    }

    public static List<int[]> blocks() {
        return BLOCKS;
    }

    public static int[] liveRect() {
        return live;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!isActive()) {
            return;
        }
        int button = event.getButton();
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            return;
        }
        event.setCanceled(true);
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }
        if (event.getAction() == InputConstants.PRESS) {
            double[] mouse = mouse();
            startX = mouse[0];
            startY = mouse[1];
            endX = mouse[0];
            endY = mouse[1];
            live = null;
            dragging = true;
        } else if (dragging) {
            dragging = false;
            commitLive();
        }
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!isActive() || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        if (event.getKey() == GLFW.GLFW_KEY_ENTER || event.getKey() == GLFW.GLFW_KEY_KP_ENTER) {
            confirm();
        }
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        if (!isActive() || !dragging) {
            return;
        }
        double[] mouse = mouse();
        endX = mouse[0];
        endY = mouse[1];
        live = rectOf(startX, startY, endX, endY);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        GuiGraphics graphics = event.getGuiGraphics();
        if (!isActive()) {
            FarmPlotDraft draft = FarmPlotDraft.current();
            if (draft != null && Minecraft.getInstance().screen == null) {
                drawCountBox(graphics, draft.cells() + " 格地（待保存）· 在农业面板点卡片配置");
            }
            return;
        }
        if (dragging) {
            drawDragBox(graphics);
        }
        if (hasRect() || dragging) {
            drawCountBox(graphics, statusText());
        }
    }

    private static void drawDragBox(GuiGraphics graphics) {
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

    private static void drawCountBox(GuiGraphics graphics, String text) {
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.font.width(text) + 12;
        int x = Math.max(4, (minecraft.getWindow().getGuiScaledWidth() - width) / 2);
        graphics.fill(x, 6, x + width, 22, COUNT_BACK);
        graphics.fill(x, 6, x + width, 7, COUNT_BORDER);
        graphics.fill(x, 21, x + width, 22, COUNT_BORDER);
        graphics.fill(x, 6, x + 1, 22, COUNT_BORDER);
        graphics.fill(x + width - 1, 6, x + width, 22, COUNT_BORDER);
        graphics.drawString(minecraft.font, text, x + 6, 10, COUNT_TEXT, true);
    }

    private static String statusText() {
        if (dragging && live != null) {
            return cells + " + 本次 " + blockCells(live) + " 格 · 松开并入 / Esc 清空全部";
        }
        if (BLOCKS.isEmpty()) {
            return "左键拖拽框选地面，可多次累加 / Esc 清空全部";
        }
        return cells + " 格地 · " + BLOCKS.size() + " 块 · Enter 保存 / Esc 清空全部";
    }

    public static void confirm() {
        if (!hasRect()) {
            hint("还没有框选范围，先左键拖拽框一块地");
            return;
        }
        resolveRegions();
        if (draftMode) {
            FarmPanelStatePacket state = FarmPlotOverlay.lastState();
            int quota = state == null ? cells : state.remainingSlots();
            int slots = Math.max(0, Math.min(cells, quota));
            FarmPlotDraft.set(new FarmPlotDraft(slots, "", List.of(), minX, minZ, maxX, maxZ, groundY,
                    cells, List.copyOf(EXTRA)));
            if (slots < cells) {
                hint("建筑种植格额度只剩 " + quota + " 格，这块地按 " + slots + " 格记");
            }
        } else if (targetPlot != null) {
            PacketDistributor.sendToServer(FarmPanelActionPacket.setArea(targetPlot, minX, minZ, maxX, maxZ,
                    List.copyOf(EXTRA)));
        }
        leave();
    }

    private static void resolveRegions() {
        int best = -1;
        int bestCells = 0;
        for (int index = 0; index < BLOCKS.size(); index++) {
            int[] block = BLOCKS.get(index);
            if (block[2] <= block[0] && block[3] <= block[1]) {
                continue;
            }
            int area = blockCells(block);
            if (area > bestCells) {
                bestCells = area;
                best = index;
            }
        }
        if (best >= 0) {
            int[] core = BLOCKS.get(best);
            minX = core[0];
            minZ = core[1];
            maxX = core[2];
            maxZ = core[3];
        } else {
            minX = 0;
            minZ = 0;
            maxX = 0;
            maxZ = 0;
        }
        EXTRA.clear();
        for (int index = 0; index < BLOCKS.size() && EXTRA.size() < FarmRegion.MAX_REGIONS; index++) {
            if (index == best) {
                continue;
            }
            int[] block = BLOCKS.get(index);
            EXTRA.add(new FarmRegion(block[0], block[1], block[2], block[3]));
        }
    }

    public static void cancelByEscape() {
        int dropped = BLOCKS.size();
        leave();
        hint(dropped == 0 ? "已取消框选" : "已清空全部框选（" + dropped + " 块）");
    }

    public static void cancel() {
        if (isActive()) {
            clearSelection();
            targetPlot = null;
            draftMode = false;
        }
    }

    private static void leave() {
        clearSelection();
        targetPlot = null;
        draftMode = false;
        if (RtsModeManager.isActive()) {
            RtsModeManager.exit();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.setScreen(new FarmPanelScreen());
        }
    }

    private static void enterRts() {
        if (!RtsModeManager.isActive()) {
            RtsModeManager.enter();
        }
        RtsModeManager.tiltCameraToGround(60.0F);
    }

    private static void commitLive() {
        int[] block = rectOf(startX, startY, endX, endY);
        live = null;
        if (block == null) {
            hint("这一块没打到地面，从更俯视的角度重框");
            return;
        }
        if (blockCells(block) > MAX_CELLS || CELLS.size() + blockCells(block) > MAX_CELLS) {
            hint("框选太大，单块上限 " + MAX_CELLS + " 格");
            return;
        }
        boolean first = BLOCKS.isEmpty();
        BLOCKS.add(block);
        for (int x = block[0]; x <= block[2]; x++) {
            for (int z = block[1]; z <= block[3]; z++) {
                CELLS.add(cellKey(x, z));
            }
        }
        cells = CELLS.size();
        groundY = first ? block[4] : Math.min(groundY, block[4]);
        hint(cells + " 格地 · " + BLOCKS.size() + " 块 · Enter 保存 / Esc 清空全部");
    }

    private static int[] rectOf(double screenX1, double screenY1, double screenX2, double screenY2) {
        Vec3 first = RtsPicker.pickTargetBlock(screenX1, screenY1);
        Vec3 second = RtsPicker.pickTargetBlock(screenX2, screenY2);
        if (first == null || second == null) {
            return null;
        }
        return new int[] {
                (int) Math.floor(Math.min(first.x, second.x)),
                (int) Math.floor(Math.min(first.z, second.z)),
                (int) Math.floor(Math.max(first.x, second.x)),
                (int) Math.floor(Math.max(first.z, second.z)),
                (int) Math.floor(Math.min(first.y, second.y))
        };
    }

    private static int blockCells(int[] block) {
        return (block[2] - block[0] + 1) * (block[3] - block[1] + 1);
    }

    private static long cellKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static void clearSelection() {
        BLOCKS.clear();
        CELLS.clear();
        EXTRA.clear();
        live = null;
        dragging = false;
        cells = 0;
        minX = 0;
        minZ = 0;
        maxX = 0;
        maxZ = 0;
        groundY = 0;
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
