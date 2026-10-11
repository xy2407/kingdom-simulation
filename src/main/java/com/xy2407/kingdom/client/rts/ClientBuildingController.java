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
 * RTS 建筑迁移操盘手：点选建筑拉出方块投影，拖动落点并旋转落地
 */
@OnlyIn(Dist.CLIENT)
public final class ClientBuildingController {

    private static final double CLICK_SLOP = 5.0D;

    private static UUID movingId;
    private static String movingName = "";
    private static StructureModel sourceModel;
    private static StructureMesh mesh;
    private static BlockPos origin;
    private static int rotation;
    private static int baseY;
    private static int heightOffset;

    private static boolean dragging;
    private static Vec3 dragGroundStart;
    private static Vec3 dragOriginStart;

    private static double pressX = -1.0D;
    private static double pressY = -1.0D;

    private ClientBuildingController() {
    }


    public static boolean onLeftPress(double mouseX, double mouseY) {
        if (!RtsModeManager.isActive()) {
            return false;
        }
        if (isMoving()) {
            RtsModeManager.cancelLeftClick();
            beginDrag(mouseX, mouseY);
            return true;
        }
        pressX = mouseX;
        pressY = mouseY;
        return false;
    }

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
        RtsModeManager.cancelLeftClick();
        return true;
    }

    public static void tick() {
        if (dragging) {
            updateDrag();
        }
    }


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
        rotation = entry.rotation();
        heightOffset = 0;
        baseY = entry.min().getY();
        origin = entry.min();
        rebuildMesh();
        return true;
    }


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


    public static boolean isMoving() {
        return movingId != null && mesh != null;
    }

    public static StructureMesh mesh() {
        return mesh;
    }

    public static BlockPos origin() {
        return origin;
    }

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

    public static void onModeChanged(boolean active) {
        if (active) {
            ClientBuildingStore.requestSync();
            return;
        }
        clear();
    }


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
        RtsModeManager.cancelLeftClick();
    }
}
