package com.xy2407.kingdom.client.rts;

import com.xy2407.kingdom.client.building.BuildingPlacementManager;
import com.xy2407.kingdom.client.farm.FarmAreaSelector;
import com.xy2407.kingdom.common.network.rts.RtsFakePlayerClearPacket;
import com.xy2407.kingdom.common.network.rts.RtsFakePlayerSpawnPacket;
import com.xy2407.kingdom.common.network.rts.RtsMoveCommandPacket;
import com.xy2407.kingdom.common.network.rts.RtsSelectionSyncPacket;

import com.xy2407.kingdom.common.network.mapoverlay.MapOverlayRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * RTS 灵魂出窍视角：相机独立飞行、框选点选、选中集合与矩阵缓存。
 */
@OnlyIn(Dist.CLIENT)
public final class RtsModeManager {

    private static final double CAMERA_MOVE_SPEED_FAST = 1.8;
    private static final double CAMERA_MOVE_SPEED_SLOW = 0.45;

    private static final double ENTER_HEIGHT_OFFSET = 12.0;

    private static final double BOX_SELECT_THRESHOLD = 5.0;

    private static final double ZOOM_SMOOTHING = 0.35;

    private static final double MAX_CAMERA_Y = 256.0D;
    private static final double CAMERA_FAR_CLIP = 2000.0F;

    public enum RtsViewMode { FREE, ISO_60, ISO_45 }

    private static boolean active = false;

    private static RtsSubMode subMode = RtsSubMode.NONE;

    private static Vec3 cameraPos = null;
    private static float cameraYaw = 0.0F;
    private static float cameraPitch = 30.0F;

    private static Vec3 prevCameraPos = null;
    private static float prevCameraYaw = 0.0F;
    private static float prevCameraPitch = 30.0F;

    private static RtsViewMode viewMode = RtsViewMode.FREE;

    private static boolean orthoEnabled = false;
    private static double orthoHalfWidth = 0.0;

    private static double zoomTargetY;

    private static boolean leftMouseDown = false;
    private static boolean boxSelecting = false;
    private static double boxStartX = 0.0;
    private static double boxStartY = 0.0;
    private static double boxEndX = 0.0;
    private static double boxEndY = 0.0;

    private static boolean middleMouseDown = false;
    private static double middleDragLastX = 0.0;
    private static double middleDragLastY = 0.0;

    private static boolean ctrlHeld = false;
    private static boolean ctrlBoxSelecting = false;

    private static final Set<UUID> selectedEntities = new HashSet<>();
    private static final Map<UUID, Vec3> moveTargets = new HashMap<>();
    private static boolean selectionLocked = false;

    private static Matrix4f cachedProjectionMatrix = null;
    private static Matrix4f cachedModelViewMatrix = null;

    private RtsModeManager() {
    }

    public static RtsSubMode getSubMode() {
        return subMode;
    }

    public static boolean isRoadMode() {
        return subMode == RtsSubMode.ROAD_TERRITORY || subMode == RtsSubMode.ROAD_WORLD;
    }

    public static void enterSubMode(RtsSubMode mode) {
        RtsSubMode next = mode == null ? RtsSubMode.NONE : mode;
        if (subMode == next) {
            return;
        }
        exitSubMode();
        subMode = next;
        boxSelecting = false;
        ctrlBoxSelecting = false;
        leftMouseDown = false;
        if (next == RtsSubMode.ROAD_TERRITORY || next == RtsSubMode.ROAD_WORLD) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                PacketDistributor.sendToServer(new MapOverlayRequestPacket(
                        minecraft.player.getBlockX(), minecraft.player.getBlockZ(), 0));
            }
        }
    }

    public static void exitSubMode() {
        if (subMode == RtsSubMode.NONE) {
            return;
        }
        subMode = RtsSubMode.NONE;
        RtsRoadState.reset();
        RegionSelectionState.reset();
    }

    public static boolean isActive() {
        return active;
    }

    public static void toggle() {
        if (active) {
            exit();
        } else {
            enter();
        }
    }

    public static void enter() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        cameraPos = minecraft.player.position().add(0.0D, ENTER_HEIGHT_OFFSET, 0.0D);
        cameraYaw = minecraft.player.getYRot();
        cameraPitch = 30.0F;
        viewMode = RtsViewMode.FREE;
        zoomTargetY = cameraPos.y;
        prevCameraPos = cameraPos;
        prevCameraYaw = cameraYaw;
        prevCameraPitch = cameraPitch;

        active = true;
        subMode = RtsSubMode.NONE;
        orthoEnabled = false;
        selectedEntities.clear();
        moveTargets.clear();
        selectionLocked = false;
        boxSelecting = false;
        ctrlBoxSelecting = false;
        leftMouseDown = false;
        middleMouseDown = false;

        RtsWaypointState.clear();
        minecraft.mouseHandler.releaseMouse();
        minecraft.gameRenderer.setRenderHand(false);
        if (minecraft.getConnection() != null) {
            PacketDistributor.sendToServer(RtsFakePlayerSpawnPacket.INSTANCE);
        }
    }

    public static void exit() {
        Minecraft minecraft = Minecraft.getInstance();
        active = false;
        subMode = RtsSubMode.NONE;
        orthoEnabled = false;
        cameraPos = null;
        prevCameraPos = null;
        selectedEntities.clear();
        moveTargets.clear();
        selectionLocked = false;
        boxSelecting = false;
        ctrlBoxSelecting = false;
        leftMouseDown = false;
        middleMouseDown = false;

        RtsWaypointState.clear();
        BuildingPlacementManager.cancel();
        FarmAreaSelector.cancel();
        RegionSelectionState.reset();
        // 退出 RTS 时把队伍面板与阵型编辑器一并收掉，否则它们会残留在普通视角里
        if (RtsFormationEditorState.isOpen()) {
            RtsFormationEditorState.close();
        }
        RtsTeamPanelState.close();
        if (minecraft.getConnection() != null) {
            PacketDistributor.sendToServer(RtsFakePlayerClearPacket.INSTANCE);
        }
        minecraft.gameRenderer.setRenderHand(true);
        if (minecraft.player != null && minecraft.screen == null) {
            minecraft.mouseHandler.grabMouse();
        }
    }

    public static void onLogout() {
        if (active) {
            exit();
        }
    }

    public static void onPlayerDeath() {
        if (!active) {
            return;
        }
        selectedEntities.clear();
        moveTargets.clear();
        selectionLocked = false;
        boxSelecting = false;
        ctrlBoxSelecting = false;
        leftMouseDown = false;
        middleMouseDown = false;
    }

    public static void syncCameraToRespawn() {
        if (!active) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        cameraPos = minecraft.player.position().add(0.0D, ENTER_HEIGHT_OFFSET, 0.0D);
        cameraYaw = minecraft.player.getYRot();
        cameraPitch = 30.0F;
        viewMode = RtsViewMode.FREE;
        zoomTargetY = cameraPos.y;
        prevCameraPos = cameraPos;
        prevCameraYaw = cameraYaw;
        prevCameraPitch = cameraPitch;
    }

    public static Vec3 getCameraPos() {
        return cameraPos;
    }

    public static float getCameraYaw() {
        return cameraYaw;
    }

    public static float getCameraPitch() {
        return cameraPitch;
    }

    public static RtsViewMode getViewMode() {
        return viewMode;
    }

    public static void setViewMode(RtsViewMode mode) {
        viewMode = mode == null ? RtsViewMode.FREE : mode;
        if (viewMode == RtsViewMode.ISO_60) {
            cameraYaw = 45.0F;
            cameraPitch = 60.0F;
        } else if (viewMode == RtsViewMode.ISO_45) {
            cameraYaw = 45.0F;
            cameraPitch = 45.0F;
        }
    }

    public static boolean isIso() {
        return viewMode != RtsViewMode.FREE;
    }

    public static void tiltCameraToGround(float pitch) {
        if (cameraPos == null) {
            return;
        }
        float next = Math.max(-89.0F, Math.min(89.0F, pitch));
        cameraPitch = next;
        prevCameraPitch = next;
    }

    public static void teleportCameraTo(Vec3 pos) {
        if (pos == null) {
            return;
        }
        cameraPos = pos.add(0.0D, ENTER_HEIGHT_OFFSET, 0.0D);
        prevCameraPos = cameraPos;
        zoomTargetY = cameraPos.y;
        cameraPitch = 30.0F;
        prevCameraPitch = cameraPitch;
    }

    public static Vec3 getRenderCameraPos(float partialTicks) {
        if (cameraPos == null) {
            return null;
        }
        if (prevCameraPos == null) {
            return cameraPos;
        }
        return new Vec3(
                prevCameraPos.x + (cameraPos.x - prevCameraPos.x) * partialTicks,
                prevCameraPos.y + (cameraPos.y - prevCameraPos.y) * partialTicks,
                prevCameraPos.z + (cameraPos.z - prevCameraPos.z) * partialTicks
        );
    }

    public static float getRenderCameraYaw(float partialTicks) {
        return lerpAngle(prevCameraYaw, cameraYaw, partialTicks);
    }

    public static float getRenderCameraPitch(float partialTicks) {
        return prevCameraPitch + (cameraPitch - prevCameraPitch) * partialTicks;
    }

    private static float lerpAngle(float from, float to, float t) {
        float diff = to - from;
        while (diff > 180.0F) {
            diff -= 360.0F;
        }
        while (diff < -180.0F) {
            diff += 360.0F;
        }
        return from + diff * t;
    }

    public static boolean isOrthoEnabled() {
        return orthoEnabled;
    }

    public static void setOrthoEnabled(boolean enable) {
        orthoEnabled = enable;
        if (enable) {
            updateOrthoHalfWidth();
        }
    }

    public static double getOrthoHalfWidth() {
        return orthoHalfWidth;
    }

    public static void updateOrthoHalfWidth() {
        Minecraft minecraft = Minecraft.getInstance();
        double height = cameraPos == null
                ? ENTER_HEIGHT_OFFSET
                : Math.max(cameraPos.y - (minecraft.player == null ? cameraPos.y : minecraft.player.getY()), 8.0D);
        double pitchRad = Math.toRadians(Math.max(Math.abs(cameraPitch), 30.0D));
        double factor = 1.0D / Math.tan(pitchRad) * 0.9D;
        orthoHalfWidth = Math.max(16.0D, height * factor);
    }

    public static double getCameraFarClip() {
        return CAMERA_FAR_CLIP;
    }

    public static void tickCamera() {
        if (!active) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || cameraPos == null || minecraft.screen != null) {
            return;
        }

        prevCameraPos = cameraPos;
        prevCameraYaw = cameraYaw;
        prevCameraPitch = cameraPitch;

        long window = minecraft.getWindow().getWindow();
        boolean keyW = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS;
        boolean keyS = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS;
        boolean keyA = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS;
        boolean keyD = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS;
        boolean keySpace = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
        boolean keyShift = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS;

        float yawRad = (float) Math.toRadians(cameraYaw);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double strafeX = -Math.cos(yawRad);
        double strafeZ = -Math.sin(yawRad);

        double moveX = 0.0D;
        double moveZ = 0.0D;
        if (keyW) {
            moveX += forwardX;
            moveZ += forwardZ;
        }
        if (keyS) {
            moveX -= forwardX;
            moveZ -= forwardZ;
        }
        if (keyD) {
            moveX += strafeX;
            moveZ += strafeZ;
        }
        if (keyA) {
            moveX -= strafeX;
            moveZ -= strafeZ;
        }

        double speed = minecraft.options.keySprint.isDown() ? CAMERA_MOVE_SPEED_FAST : CAMERA_MOVE_SPEED_SLOW;

        double length = Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (length > 0.0D) {
            moveX = moveX / length * speed;
            moveZ = moveZ / length * speed;
        }

        double moveY = 0.0D;
        if (keySpace) {
            moveY += speed;
        }
        if (keyShift) {
            moveY -= speed;
        }

        cameraPos = new Vec3(cameraPos.x + moveX, cameraPos.y + moveY, cameraPos.z + moveZ);
        if (moveY != 0.0D) {
            zoomTargetY += moveY;
        }

        double smoothedY = cameraPos.y + (zoomTargetY - cameraPos.y) * ZOOM_SMOOTHING;
        cameraPos = new Vec3(cameraPos.x, smoothedY, cameraPos.z);

        if (orthoEnabled) {
            updateOrthoHalfWidth();
        }
    }

    public static void onScroll(double vertical) {
        if (!active || cameraPos == null) {
            return;
        }
        int step = (int) Math.max(-5, Math.min(5, vertical));
        if (step == 0) {
            return;
        }
        double maxY = MAX_CAMERA_Y;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            maxY = Math.max(MAX_CAMERA_Y, minecraft.level.getMaxBuildHeight() + 128.0D);
        }
        zoomTargetY = Math.min(maxY, zoomTargetY + step);
    }

    public static void onMiddleDown() {
        middleMouseDown = true;
        double[] pos = getGuiScaledMouse();
        middleDragLastX = pos[0];
        middleDragLastY = pos[1];
    }

    public static void onMiddleUp() {
        middleMouseDown = false;
    }

    public static void onMiddleDrag() {
        if (!middleMouseDown) {
            return;
        }
        double[] pos = getGuiScaledMouse();
        double dx = pos[0] - middleDragLastX;
        double dy = pos[1] - middleDragLastY;
        middleDragLastX = pos[0];
        middleDragLastY = pos[1];

        float yawDelta = (float) (dx * 0.5D);
        if (viewMode != RtsViewMode.FREE) {
            cameraYaw += yawDelta;
            prevCameraYaw += yawDelta;
            return;
        }
        float newPitch = (float) Math.max(-89.0D, Math.min(89.0D, cameraPitch + dy * 0.5D));
        float pitchDelta = newPitch - cameraPitch;

        cameraYaw += yawDelta;
        cameraPitch = newPitch;
        prevCameraYaw += yawDelta;
        prevCameraPitch += pitchDelta;
    }

    public static void onLeftMouseDown() {
        leftMouseDown = true;
        ctrlBoxSelecting = ctrlHeld;
        double[] pos = getGuiScaledMouse();
        boxStartX = pos[0];
        boxStartY = pos[1];
        boxEndX = pos[0];
        boxEndY = pos[1];
        boxSelecting = false;
    }

    public static void onLeftMouseDrag() {
        if (!leftMouseDown) {
            return;
        }
        double[] pos = getGuiScaledMouse();
        boxEndX = pos[0];
        boxEndY = pos[1];
        double dx = pos[0] - boxStartX;
        double dy = pos[1] - boxStartY;
        if (dx * dx + dy * dy > BOX_SELECT_THRESHOLD * BOX_SELECT_THRESHOLD) {
            boxSelecting = true;
        }
    }

    public static void onLeftMouseUp() {
        if (!leftMouseDown) {
            return;
        }
        leftMouseDown = false;
        boolean dragged = boxSelecting;
        boxSelecting = false;
        ctrlBoxSelecting = false;
        if (isSelectionLocked()) {
            return;
        }
        if (dragged) {
            RtsPicker.boxSelect(boxStartX, boxStartY, boxEndX, boxEndY);
        } else {
            RtsPicker.clickSelect(boxEndX, boxEndY);
        }
    }

    public static void issueMoveCommand() {
        if (!active || selectedEntities.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        double[] mouse = getGuiScaledMouse();
        if (RtsWaypointState.isActive()) {
            Vec3 node = RtsPicker.pickTargetBlock(mouse[0], mouse[1]);
            if (node != null) {
                RtsWaypointState.append(node);
            }
            return;
        }
        Vec3 target = RtsPicker.pickTargetBlock(mouse[0], mouse[1]);
        if (target == null) {
            return;
        }
        List<UUID> ids = new ArrayList<>(selectedEntities);
        setMoveTargets(new HashSet<>(ids), target);
        PacketDistributor.sendToServer(new RtsMoveCommandPacket(ids, target));
    }

    public static void cancelLeftClick() {
        leftMouseDown = false;
        boxSelecting = false;
        ctrlBoxSelecting = false;
        boxStartX = 0.0D;
        boxStartY = 0.0D;
        boxEndX = 0.0D;
        boxEndY = 0.0D;
    }

    public static boolean isLeftMouseDown() {
        return leftMouseDown;
    }

    public static boolean isMiddleMouseDown() {
        return middleMouseDown;
    }

    public static boolean isBoxSelecting() {
        return boxSelecting;
    }

    public static double getBoxStartX() {
        return boxStartX;
    }

    public static double getBoxStartY() {
        return boxStartY;
    }

    public static double getBoxEndX() {
        return boxEndX;
    }

    public static double getBoxEndY() {
        return boxEndY;
    }

    public static boolean isCtrlHeld() {
        return ctrlHeld;
    }

    public static void setCtrlHeld(boolean held) {
        ctrlHeld = held;
    }

    public static boolean isCtrlBoxSelecting() {
        return ctrlBoxSelecting;
    }

    public static double[] getGuiScaledMouse() {
        Minecraft minecraft = Minecraft.getInstance();
        double x = minecraft.mouseHandler.xpos();
        double y = minecraft.mouseHandler.ypos();
        double scale = minecraft.getWindow().getGuiScale();
        return new double[]{x / scale, y / scale};
    }

    public static Set<UUID> getSelectedEntities() {
        return selectedEntities;
    }

    public static void setSelectedEntities(Set<UUID> ids) {
        selectedEntities.clear();
        if (ids != null) {
            selectedEntities.addAll(ids);
        }
        if (selectedEntities.isEmpty()) {
            moveTargets.clear();
        }
        syncSelectionToServer();
    }

    public static void applyServerSelection(Set<UUID> ids) {
        selectedEntities.clear();
        if (ids != null) {
            selectedEntities.addAll(ids);
        }
        if (selectedEntities.isEmpty()) {
            moveTargets.clear();
        }
    }

    private static void syncSelectionToServer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        PacketDistributor.sendToServer(new RtsSelectionSyncPacket(new HashSet<>(selectedEntities)));
    }

    public static void clearSelection() {
        if (selectedEntities.isEmpty() && moveTargets.isEmpty()) {
            return;
        }
        selectedEntities.clear();
        moveTargets.clear();
        syncSelectionToServer();
    }

    public static boolean isSelectionLocked() {
        return selectionLocked;
    }

    public static void lockSelection() {
        if (!selectedEntities.isEmpty()) {
            selectionLocked = true;
        }
    }

    public static void unlockSelection() {
        selectionLocked = false;
    }

    public static void setMoveTargets(Set<UUID> ids, Vec3 target) {
        moveTargets.clear();
        if (ids == null || target == null) {
            return;
        }
        for (UUID id : ids) {
            moveTargets.put(id, target);
        }
    }

    public static Map<UUID, Vec3> getMoveTargets() {
        return moveTargets;
    }

    public static void setCachedProjectionMatrix(Matrix4f matrix) {
        cachedProjectionMatrix = matrix == null ? null : new Matrix4f(matrix);
    }

    public static Matrix4f getCachedProjectionMatrix() {
        return cachedProjectionMatrix;
    }

    public static void setCachedModelViewMatrix(Matrix4f matrix) {
        cachedModelViewMatrix = matrix == null ? null : new Matrix4f(matrix);
    }

    public static Matrix4f getCachedModelViewMatrix() {
        return cachedModelViewMatrix;
    }
}
