package com.xy2407.kingdom.client.rts;

import java.util.UUID;

import com.xy2407.kingdom.client.structure.StructureLoader;
import com.xy2407.kingdom.client.structure.StructureMesh;
import com.xy2407.kingdom.client.structure.StructureMesher;
import com.xy2407.kingdom.common.building.BuildingBlueprints;
import com.xy2407.kingdom.common.network.building.BuildingMovePacket;
import com.xy2407.kingdom.common.structure.StructureModel;
import com.xy2407.kingdom.common.structure.StructureRotation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * RTS 建筑迁移的操盘手，照 xy's_nsuk 的 {@code RtsBuildingPlacementManager} 写：
 * 短按点中建筑 → 拉出这栋楼的**方块投影** → **按住左键拖动**投影（鼠标射线与地面的交点算位移）
 * → R 旋转 / 滚轮调高 → Enter 落地 / 右键取消。
 * 投影是真实方块几何（{@link StructureMesh}）而不是线框；拖动只改落点，网格只在旋转时重建。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientBuildingController {

    /** 短按阈值：按下与松开的位移小于它才算"点选"，否则视为拖动。 */
    private static final double CLICK_SLOP = 5.0D;

    private static UUID movingId;
    private static String movingName = "";
    private static StructureModel sourceModel;
    private static StructureMesh mesh;
    private static BlockPos origin;
    private static int rotation;
    private static int baseY;
    private static int heightOffset;

    /** 拖动基准：起拖时"鼠标∩地面"的点，与起拖时的落点快照。 */
    private static boolean dragging;
    private static Vec3 dragGroundStart;
    private static Vec3 dragOriginStart;

    /** 短按判定用：左键按下时的鼠标位置。 */
    private static double pressX = -1.0D;
    private static double pressY = -1.0D;

    private ClientBuildingController() {
    }

    // ================= 输入入口（由 RtsClientEvents 调） =================

    /** 左键按下：迁移中就去起拖；否则只记下按下点，等松开时判断是不是短按。 */
    public static boolean onLeftPress(double mouseX, double mouseY) {
        if (!RtsModeManager.isActive()) {
            return false;
        }
        if (isMoving()) {
            // 起拖前先把 RTS 那边的框选收掉：这次左键归迁移用，屏幕上不该再画框
            RtsModeManager.cancelLeftClick();
            beginDrag(mouseX, mouseY);
            return true;
        }
        pressX = mouseX;
        pressY = mouseY;
        return false;
    }

    /** 左键松开：迁移中结束拖动；否则若是短按就尝试点选一栋建筑。 */
    public static boolean onLeftRelease(double mouseX, double mouseY) {
        if (!RtsModeManager.isActive()) {
            return false;
        }
        if (isMoving()) {
            endDrag();
            return true;
        }
        boolean shortClick = pressX >= 0.0D
                && Math.abs(mouseX - pressX) < CLICK_SLOP
                && Math.abs(mouseY - pressY) < CLICK_SLOP;
        double clickX = pressX;
        double clickY = pressY;
        pressX = -1.0D;
        pressY = -1.0D;
        if (!shortClick || !tryPick(clickX, clickY)) {
            return false;
        }
        // 点中建筑：这次左键归迁移用。但按下那一刻 RtsModeManager 已经开了框选
        // （leftMouseDown = true），这里若不显式收掉，它就永远等不到自己的 onLeftMouseUp，
        // 结果框选框一直画在屏幕上、后续点击也被"选中锁定"卡住。
        // nsuk 的 RtsInputHandler 在 tryStartMoveFromClick 成功后正是这么处理的。
        RtsModeManager.cancelLeftClick();
        return true;
    }

    /** 每 tick：拖动中就用鼠标当前的地面交点刷新落点。 */
    public static void tick() {
        if (dragging) {
            updateDrag();
        }
    }

    // ================= 点选 =================

    /**
     * 短按点选：鼠标射线打到地面，取那一格的占地范围命中的建筑。
     * 必须能读到蓝图才接（读不到就没法画投影）。
     */
    private static boolean tryPick(double mouseX, double mouseY) {
        Vec3 ground = RtsPicker.pickTargetBlock(mouseX, mouseY);
        if (ground == null) {
            return false;
        }
        ClientBuildingStore.Entry entry = ClientBuildingStore.pickAt(ground.x, ground.z);
        if (entry == null) {
            return false;
        }
        StructureModel loaded = StructureLoader.loadFile(BuildingBlueprints.fileOf(entry.name()));
        if (loaded == null || loaded.blocks().isEmpty()) {
            return false;
        }
        clear();
        movingId = entry.id();
        movingName = entry.name();
        sourceModel = loaded;
        rotation = 0;
        heightOffset = 0;
        baseY = entry.min().getY();
        origin = entry.min();
        rebuildMesh();
        return true;
    }

    // ================= 拖动 =================

    /** 起拖：左键必须落在投影的屏幕范围里（照 nsuk，点空白不会把楼拖走）。 */
    private static void beginDrag(double mouseX, double mouseY) {
        if (!isMoving() || !hitsProjection(mouseX, mouseY)) {
            return;
        }
        Vec3 ground = RtsPicker.pickTargetBlock(mouseX, mouseY);
        dragging = true;
        dragGroundStart = ground != null ? ground
                : new Vec3(origin.getX(), origin.getY(), origin.getZ());
        dragOriginStart = new Vec3(origin.getX(), origin.getY(), origin.getZ());
    }

    /** 拖动：位移 = 当前地面交点 − 起拖时的地面交点，整格对齐；Y 只由滚轮改。 */
    private static void updateDrag() {
        if (dragGroundStart == null || dragOriginStart == null) {
            return;
        }
        double[] mouse = RtsModeManager.getGuiScaledMouse();
        Vec3 ground = RtsPicker.pickTargetBlock(mouse[0], mouse[1]);
        if (ground == null) {
            return;
        }
        int dx = (int) Math.floor(ground.x - dragGroundStart.x);
        int dz = (int) Math.floor(ground.z - dragGroundStart.z);
        BlockPos next = new BlockPos(
                (int) Math.floor(dragOriginStart.x) + dx,
                baseY + heightOffset,
                (int) Math.floor(dragOriginStart.z) + dz);
        if (!next.equals(origin)) {
            origin = next;
        }
    }

    private static void endDrag() {
        dragging = false;
        dragGroundStart = null;
        dragOriginStart = null;
    }

    // ================= 旋转 / 调高 / 落地 / 取消 =================

    public static void rotate() {
        if (!isMoving()) {
            return;
        }
        rotation = (rotation + 90) % 360;
        rebuildMesh();
    }

    public static void adjustHeight(int delta) {
        if (!isMoving() || delta == 0 || origin == null) {
            return;
        }
        heightOffset += delta;
        origin = new BlockPos(origin.getX(), baseY + heightOffset, origin.getZ());
    }

    public static void confirm() {
        if (!isMoving() || origin == null) {
            return;
        }
        PacketDistributor.sendToServer(new BuildingMovePacket(movingId, origin));
        clear();
    }

    public static void cancel() {
        clear();
    }

    // ================= 给渲染用 =================

    public static boolean isMoving() {
        return movingId != null && mesh != null;
    }

    public static StructureMesh mesh() {
        return mesh;
    }

    public static BlockPos origin() {
        return origin;
    }

    /** 线框尺寸取旋转后的尺寸。 */
    public static Vec3i size() {
        StructureModel rotated = sourceModel == null ? null : StructureRotation.rotate(sourceModel, rotation);
        return rotated == null ? Vec3i.ZERO : rotated.size();
    }

    public static String movingName() {
        return movingName;
    }

    public static boolean isDragging() {
        return dragging;
    }

    /** 进 RTS 时拉一份建筑清单；退出时把迁移状态收干净（关掉投影网格）。 */
    public static void onModeChanged(boolean active) {
        if (active) {
            ClientBuildingStore.requestSync();
            return;
        }
        clear();
    }

    // ================= 内部 =================

    private static void rebuildMesh() {
        StructureModel rotated = sourceModel == null ? null : StructureRotation.rotate(sourceModel, rotation);
        if (rotated == null) {
            return;
        }
        StructureMesh next = StructureMesher.build(rotated);
        if (mesh != null) {
            mesh.close();
        }
        mesh = next;
    }

    /** 投影的屏幕外接矩形：把落点方块盒的 8 个角投影到屏幕再取包围盒（同放置那条链的写法）。 */
    private static boolean hitsProjection(double mouseX, double mouseY) {
        Vec3i size = size();
        if (origin == null || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            return false;
        }
        double left = Double.MAX_VALUE;
        double top = Double.MAX_VALUE;
        double right = -Double.MAX_VALUE;
        double bottom = -Double.MAX_VALUE;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    double[] screen = RtsPicker.projectToScreen(new Vec3(
                            origin.getX() + dx * size.getX(),
                            origin.getY() + dy * size.getY(),
                            origin.getZ() + dz * size.getZ()));
                    if (screen == null) {
                        return false;
                    }
                    left = Math.min(left, screen[0]);
                    top = Math.min(top, screen[1]);
                    right = Math.max(right, screen[0]);
                    bottom = Math.max(bottom, screen[1]);
                }
            }
        }
        return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
    }

    private static void clear() {
        if (mesh != null) {
            mesh.close();
            mesh = null;
        }
        movingId = null;
        movingName = "";
        sourceModel = null;
        origin = null;
        rotation = 0;
        heightOffset = 0;
        baseY = 0;
        endDrag();
        pressX = -1.0D;
        pressY = -1.0D;
        // 退出/落地/取消之后也不该再留着半个框选状态
        RtsModeManager.cancelLeftClick();
    }
}
