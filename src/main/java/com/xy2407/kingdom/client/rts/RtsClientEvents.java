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

import com.xy2407.kingdom.common.network.rts.RtsTeamSyncPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * RTS 的键鼠输入与渲染事件处理，含模式切换、相机推进与世界空间线框入口。
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

    public static final KeyMapping TOGGLE_MINING_BRUSH = new KeyMapping(
            "key.xy2407_kingdom.mining_brush",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.categories.xy2407_kingdom"
    );

    private static final float OVERLAY_DOCUMENT_Z = 2000.0F;
    private static final float OVERLAY_CROSSHAIR_Z = 100.0F;

    private static boolean wasDead;

    private RtsClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (TOGGLE_RTS_MODE.consumeClick()) {
            RtsModeManager.toggle();
            ClientBuildingController.onModeChanged(RtsModeManager.isActive());
        }
        while (TOGGLE_RTS_ORTHO.consumeClick()) {
            if (RtsModeManager.isActive()) {
                RtsModeManager.setOrthoEnabled(!RtsModeManager.isOrthoEnabled());
            }
        }
        while (TOGGLE_MINING_BRUSH.consumeClick()) {
            com.xy2407.kingdom.client.mining.MiningBrushSelector.toggle();
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

        if (RtsModeManager.isActive() && minecraft.screen == null && minecraft.mouseHandler.isMouseGrabbed()) {
            minecraft.mouseHandler.releaseMouse();
        }
        RtsModeManager.tickCamera();
        ClientBuildingController.tick();
        if (RtsTeamPanelState.dragging()) {
            long handle = minecraft.getWindow().getWindow();
            if (GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
                RtsTeamPanelState.endDrag();
            } else {
                double[] dragMouse = RtsModeManager.getGuiScaledMouse();
                RtsTeamPanelState.updateDrag(dragMouse[0], dragMouse[1]);
            }
        }
        com.xy2407.kingdom.client.area.AreaSelectionState.tick();
    }

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

    private static boolean insideViewer(Element element) {
        for (Node node = element; node instanceof Element current; node = current.getParentNode()) {
            String id = current.getAttribute("id");
            if (id != null && (id.startsWith("viewer-") || id.startsWith("card-")
                    || id.startsWith("load-") || id.startsWith("big-")
                    || id.startsWith("rtsops-"))) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!RtsModeManager.isActive() || minecraft.screen != null || minecraft.player == null) {
            return;
        }
        boolean press = event.getAction() == InputConstants.PRESS;
        double[] mouse = RtsModeManager.getGuiScaledMouse();
        if (!press) {
            RtsTeamPanelState.endDrag();
        }
        // 阵型编辑器是自绘 HUD，AUI 不认识它的矩形；必须在「事件是否已被取消 / 是否压在 AUI 上」
        // 判定之前拦下来，否则指挥面板开着时事件先被 AUI 吃掉，编辑器按钮就点不动。
        if (press && RtsFormationEditorHud.handleClick(mouse[0], mouse[1])) {
            event.setCanceled(true);
            return;
        }
        if (event.isCanceled() || overAui()) {
            event.setCanceled(true);
            return;
        }
        if (RtsTeamPanelState.isOpen()) {
            if (RtsTeamPanelLayer.isInTitleBar(mouse[0], mouse[1])) {
                if (press) {
                    RtsTeamPanelState.startDrag(mouse[0], mouse[1]);
                }
                event.setCanceled(true);
                return;
            }
            RtsTeamPanelLayer.Area area = RtsTeamPanelLayer.hitTest(mouse[0], mouse[1]);
            if (area != null) {
                if (press) {
                    handleTeamPanel(area);
                }
                event.setCanceled(true);
                return;
            }
            if (RtsTeamPanelLayer.isMouseInPanel(mouse[0], mouse[1])) {
                if (press) {
                    RtsModeManager.clearSelection();
                }
                event.setCanceled(true);
                return;
            }
        }
        switch (event.getButton()) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> {
                if (com.xy2407.kingdom.client.area.AreaSelectionState.isActive()) {
                    event.setCanceled(true);
                    return;
                }
                if (RtsModeManager.isRoadMode()) {
                    if (press) {
                        RoadHudLayer.handleClick(mouse[0], mouse[1]);
                    }
                    event.setCanceled(true);
                    return;
                }
                if (RtsModeManager.getSubMode() == RtsSubMode.REGION_TASK) {
                    if (RegionHudLayer.handleClick(mouse[0], mouse[1])) {
                        event.setCanceled(true);
                        return;
                    }
                    if (press) {
                        RegionSelectionState.onLeftDown();
                    } else {
                        RegionSelectionState.onLeftUp();
                    }
                    event.setCanceled(true);
                    return;
                }
                if (press) {
                    if (BuildingPlacementManager.beginPendingDrag()) {
                        event.setCanceled(true);
                        return;
                    }
                    if (ClientBuildingController.onLeftPress(mouse[0], mouse[1])) {
                        event.setCanceled(true);
                        return;
                    }
                    RtsModeManager.onLeftMouseDown();
                } else if (!ClientBuildingController.onLeftRelease(mouse[0], mouse[1])) {
                    if (BuildingPlacementManager.isDragging()) {
                        RtsModeManager.cancelLeftClick();
                        BuildingPlacementManager.endPendingDrag();
                    } else {
                        RtsModeManager.onLeftMouseUp();
                    }
                }
                event.setCanceled(true);
            }
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> {
                if (com.xy2407.kingdom.client.area.AreaSelectionState.isActive()) {
                    com.xy2407.kingdom.client.area.AreaSelectionState.onRightButton(
                            press, mouse[0], mouse[1]);
                    event.setCanceled(true);
                    return;
                }
                if (RtsModeManager.isRoadMode()) {
                    RtsRoadState.onRightButton(press, mouse[0], mouse[1]);
                    event.setCanceled(true);
                    return;
                }
                if (RtsModeManager.getSubMode() == RtsSubMode.REGION_TASK) {
                    RegionSelectionState.onRightButton(press);
                    event.setCanceled(true);
                    return;
                }
                if (press) {
                    if (ClientBuildingController.isMoving()) {
                        ClientBuildingController.cancel();
                    } else if (BuildingPlacementManager.isActive()) {
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
        // 区域框选拖角中：滚轮调角块高度（对齐建筑投影的滚轮调 y 手感）
        if (RegionSelectionState.isDragging()) {
            RegionSelectionState.adjustHeight(event.getScrollDeltaY() > 0 ? 1 : -1);
            event.setCanceled(true);
            return;
        }
        if (ClientBuildingController.isMoving()) {
            ClientBuildingController.adjustHeight(event.getScrollDeltaY() > 0 ? 1 : -1);
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
        if (com.xy2407.kingdom.client.area.AreaSelectionState.isActive()) {
            com.xy2407.kingdom.client.area.AreaSelectionState.onKeyPress(key);
            return;
        }
        if (RtsModeManager.getSubMode() == RtsSubMode.ROAD_TERRITORY) {
            RtsRoadState.onKeyPress(key);
            return;
        }
        if (RtsModeManager.getSubMode() == RtsSubMode.REGION_TASK) {
            RegionSelectionState.onKeyPress(key);
            return;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (RtsFormationEditorState.isOpen() && RtsFormationEditorState.isWorldOps()) {
                RtsFormationEditorState.returnToGrid();
            } else if (ClientBuildingController.isMoving()) {
                ClientBuildingController.confirm();
            }
        } else if (key == GLFW.GLFW_KEY_R) {
            if (ClientBuildingController.isMoving()) {
                ClientBuildingController.rotate();
            } else {
                BuildingPlacementManager.rotateTarget();
            }
        } else if (key == GLFW.GLFW_KEY_X) {
            BuildingPlacementManager.undoLastPlacement();
        } else if (key == GLFW.GLFW_KEY_G) {
            RtsTeamPanelState.toggle();
        } else if (key == GLFW.GLFW_KEY_H) {
            toggleFormationEditor();
        } else if (key == GLFW.GLFW_KEY_GRAVE_ACCENT) {
            RtsModeManager.clearAggro();
        }
    }

    private static void handleTeamPanel(RtsTeamPanelLayer.Area area) {
        List<RtsTeamSyncPacket.Entry> teams = RtsTeamPanelLayer.orderedTeams(
                RtsTeamPanelState.selectedSingle());
        switch (area.kind()) {
            case LEADER -> {
                List<UUID> picked = new ArrayList<>(RtsModeManager.getSelectedEntities());
                if (area.index() >= 0 && area.index() < picked.size()) {
                    RtsTeamPanelState.selectLeader(picked.get(area.index()));
                }
            }
            case CREATE -> RtsTeamPanelState.createTeam();
            case FOCUS -> {
                if (area.index() >= 0 && area.index() < teams.size()) {
                    RtsTeamPanelState.focusLeader(teams.get(area.index()).teamId());
                }
            }
            case EDIT -> {
                if (area.index() >= 0 && area.index() < teams.size()) {
                    UUID teamId = teams.get(area.index()).teamId();
                    RtsTeamPanelState.close();
                    RtsFormationEditorState.openById(teamId);
                }
            }
            case DELETE -> {
                if (area.index() >= 0 && area.index() < teams.size()) {
                    RtsTeamPanelState.deleteTeam(teams.get(area.index()).teamId());
                }
            }
            case JOIN -> {
                UUID single = RtsTeamPanelState.selectedSingle();
                if (single != null && area.index() >= 0 && area.index() < teams.size()) {
                    RtsTeamPanelState.joinTeam(teams.get(area.index()).teamId(), single);
                }
            }
            case LEAVE -> {
                UUID single = RtsTeamPanelState.selectedSingle();
                if (single != null && area.index() >= 0 && area.index() < teams.size()) {
                    RtsTeamPanelState.leaveTeam(teams.get(area.index()).teamId(), single);
                }
            }
            default -> {
            }
        }
    }

    private static void toggleFormationEditor() {
        if (RtsFormationEditorState.isOpen()) {
            RtsFormationEditorState.close();
            return;
        }
        if (RtsTeamClientCache.first() != null) {
            RtsFormationEditorState.openFirstTeam();
            return;
        }
        RtsFormationEditorState.requestCreate();
        com.xy2407.kingdom.client.structure.StructureDebugChat.chat(
                "已创建队伍，再按 G 进入阵型编辑");
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        RtsEntityGlowRenderer.onRenderLevel(event);
        RtsWaypointMarkerRenderer.onRenderLevel(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderGuiPre(RenderGuiEvent.Post event) {
        if (!RtsModeManager.isActive() || Minecraft.getInstance().screen != null) {
            return;
        }
        Base.pushDocumentZOffset(OVERLAY_DOCUMENT_Z);
    }

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
