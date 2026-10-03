package com.xy2407.kingdom.client.rts;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.init.Node;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.render.Base;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.building.BuildingPlacementManager;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * RTS 的键鼠输入与渲染事件处理，含模式切换、相机推进与世界空间线框入口
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class RtsClientEvents {

    public static final KeyMapping TOGGLE_RTS_MODE = new KeyMapping(
            "key.xy2407_kingdom.rts_toggle",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F6,
            "key.categories.xy2407_kingdom"
    );

    public static final KeyMapping TOGGLE_RTS_ORTHO = new KeyMapping(
            "key.xy2407_kingdom.rts_ortho",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F7,
            "key.categories.xy2407_kingdom"
    );

    /** 覆盖层文档的深度基准，需大于原版聊天栏。 */
    private static final float OVERLAY_DOCUMENT_Z = 2000.0F;
    /** 准星要再高一层，免得被建筑列表 HUD 压住。 */
    private static final float OVERLAY_CROSSHAIR_Z = 100.0F;

    private static boolean wasDead;

    private RtsClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (TOGGLE_RTS_MODE.consumeClick()) {
            RtsModeManager.toggle();
        }
        while (TOGGLE_RTS_ORTHO.consumeClick()) {
            if (RtsModeManager.isActive()) {
                RtsModeManager.setOrthoEnabled(!RtsModeManager.isOrthoEnabled());
            }
        }

        if (minecraft.player == null || minecraft.level == null) {
            RtsModeManager.onLogout();
            wasDead = false;
            return;
        }

        boolean dead = minecraft.player.isDeadOrDying();
        if (dead && !wasDead) {
            RtsModeManager.onPlayerDeath();
        } else if (!dead && wasDead) {
            RtsModeManager.syncCameraToRespawn();
        }
        wasDead = dead;

        // AUI 的命中判定读 MouseHandler.xpos/ypos：光标被抓回时这两个值会脱离屏幕范围
        // （中途开过一次 GUI 再关掉就会被抓回），HUD 上的一切按钮都会点不到。这里每 tick 兜回释放态。
        if (RtsModeManager.isActive() && minecraft.screen == null && minecraft.mouseHandler.isMouseGrabbed()) {
            minecraft.mouseHandler.releaseMouse();
        }
        RtsModeManager.tickCamera();
    }

    /**
     * 鼠标（GUI 坐标）是否落在建筑列表 HUD 内。落在 UI 上时把输入整个让给 AUI：
     * 既不消费事件也不走 RTS 的世界交互，这样卡片里的拖拽、滚轮、按钮才收得到。
     *
     * <p>页面声明了 {@code aui-mouse-events=intercept}，文档级判定会对整屏返回真，
     * 所以必须用元素级命中再向上追溯是否真的落在面板里。</p>
     */
    private static boolean overAui() {
        double[] mouse = RtsModeManager.getGuiScaledMouse();
        Position screen = new Position(mouse[0], mouse[1]);
        for (Document document : ApricityUI.getAllDocument()) {
            if (insideViewer(document.hitTest(document.screenToDocumentPosition(screen)))) {
                return true;
            }
        }
        return false;
    }

    /** 命中元素是否落在建筑列表 HUD（{@code viewer-* / card-* / load-* / big-*}）内部。 */
    private static boolean insideViewer(Element element) {
        for (Node node = element; node instanceof Element current; node = current.getParentNode()) {
            String id = current.getAttribute("id");
            if (id != null && (id.startsWith("viewer-") || id.startsWith("card-")
                    || id.startsWith("load-") || id.startsWith("big-"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 排最低优先级：AUI 在默认优先级处理覆盖层的卡片与按钮，必须排在它之后才看得到它是否已消费，
     * 否则这里的 {@code setCanceled(true)} 会把 AUI 的鼠标分发一起挡掉。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!RtsModeManager.isActive() || minecraft.screen != null || minecraft.player == null) {
            return;
        }
        if (event.isCanceled() || overAui()) {
            // AUI 排在我们之前，它已经处理完这次输入；这里再消费一次，
            // 免得原版把鼠标抓回去（坐标一乱，投影会跟着跳）。
            event.setCanceled(true);
            return;
        }
        boolean press = event.getAction() == InputConstants.PRESS;
        switch (event.getButton()) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> {
                if (press) {
                    if (BuildingPlacementManager.beginPendingDrag()) {
                        event.setCanceled(true);
                        return;
                    }
                    RtsModeManager.onLeftMouseDown();
                } else if (BuildingPlacementManager.isDragging()) {
                    BuildingPlacementManager.endPendingDrag();
                } else {
                    RtsModeManager.onLeftMouseUp();
                }
                event.setCanceled(true);
            }
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> {
                if (press) {
                    if (BuildingPlacementManager.isActive()) {
                        BuildingPlacementManager.confirmPlace();
                    } else {
                        RtsModeManager.issueMoveCommand();
                    }
                }
                event.setCanceled(true);
            }
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> {
                if (press) {
                    RtsModeManager.onMiddleDown();
                } else {
                    RtsModeManager.onMiddleUp();
                }
                event.setCanceled(true);
            }
            default -> {
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!RtsModeManager.isActive() || Minecraft.getInstance().screen != null) {
            return;
        }
        if (event.isCanceled() || overAui()) {
            event.setCanceled(true);
            return;
        }
        if (BuildingPlacementManager.isActive() || BuildingPlacementManager.isDragging()) {
            BuildingPlacementManager.adjustTargetHeight(event.getScrollDeltaY() > 0 ? 1 : -1);
            event.setCanceled(true);
            return;
        }
        RtsModeManager.onScroll(event.getScrollDeltaY());
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!RtsModeManager.isActive()) {
            return;
        }
        int key = event.getKey();
        if (key == GLFW.GLFW_KEY_LEFT_CONTROL || key == GLFW.GLFW_KEY_RIGHT_CONTROL) {
            RtsModeManager.setCtrlHeld(event.getAction() != GLFW.GLFW_RELEASE);
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        if (key == GLFW.GLFW_KEY_R) {
            BuildingPlacementManager.rotateTarget();
        } else if (key == GLFW.GLFW_KEY_X) {
            // 用 X 而非 C：C 已经被 RtsWaypointState 占用（按住录节点路径），
            // 两者同键会让"按住 C 录路径"顺手把已暂存的蓝影撤回掉。
            BuildingPlacementManager.undoLastPlacement();
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        RtsEntityGlowRenderer.onRenderLevel(event);
        RtsWaypointMarkerRenderer.onRenderLevel(event);
    }

    /**
     * 最高优先级，排在 AUI 绘制覆盖层之前：把文档的深度基准整体抬高。
     *
     * <p>AUI 的覆盖层文档默认 z 从 {@code GLOBAL_DOCUMENT_Z_OFFSET}（1.0）起算，
     * 会被原版聊天栏压住；这里抬到聊天之上，结束时在最低优先级还原。</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderGuiPre(RenderGuiEvent.Post event) {
        if (!RtsModeManager.isActive() || Minecraft.getInstance().screen != null) {
            return;
        }
        Base.pushDocumentZOffset(OVERLAY_DOCUMENT_Z);
    }

    /** 最低优先级，排在 AUI 覆盖层文档之后：画准星并还原深度基准。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        if (!RtsModeManager.isActive() || Minecraft.getInstance().screen != null) {
            return;
        }
        PoseStack poseStack = event.getGuiGraphics().pose();
        poseStack.pushPose();
        poseStack.translate(0.0F, 0.0F, OVERLAY_DOCUMENT_Z + OVERLAY_CROSSHAIR_Z);
        RtsSelectionRenderer.render(event.getGuiGraphics());
        poseStack.popPose();
        Base.popDocumentZOffset();
    }
}
