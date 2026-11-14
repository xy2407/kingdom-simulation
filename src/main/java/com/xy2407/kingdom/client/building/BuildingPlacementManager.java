package com.xy2407.kingdom.client.building;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.client.rts.RtsModeManager;
import com.xy2407.kingdom.client.rts.RtsPicker;
import com.xy2407.kingdom.client.structure.StructureLoader;
import com.xy2407.kingdom.client.structure.StructureMesh;
import com.xy2407.kingdom.client.structure.StructureMesher;
import com.xy2407.kingdom.common.building.BuildingMetaLoader;
import com.xy2407.kingdom.common.building.BuildingBlueprints;
import com.xy2407.kingdom.common.network.building.BuildingPlaceConfirmPacket;
import com.xy2407.kingdom.common.structure.StructureModel;
import com.xy2407.kingdom.common.structure.StructureRotation;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 建筑投影放置状态机，投影跟随鼠标并支持旋转、调高与暂存后统一开工。
 */
@OnlyIn(Dist.CLIENT)
public final class BuildingPlacementManager {
    public static final float GHOST_ALPHA = 1.0F;

    public static final float PENDING_ALPHA = 0.8F;

    public record Pending(String name, BlockPos origin, int rotation, Vec3i size,
                          StructureModel source, StructureMesh mesh) {
    }

    private static String blueprintName = "";
    private static StructureModel sourceModel;
    private static StructureMesh mesh;
    private static BlockPos origin;
    private static int rotation;
    private static int heightOffset;
    private static int baseY;
    private static boolean active;
    private static int dragIndex = -1;
    private static Vec3i cachedSize;
    private static StructureModel cachedSizeSource;
    private static int cachedSizeRotation = -1;
    private static final List<Pending> PENDINGS = new ArrayList<>();

    private BuildingPlacementManager() {
    }

    public static void beginRtsFollow(String name, BlockPos startOrigin) {
        beginRtsFollow(name, startOrigin, null);
    }

    public static void beginRtsFollow(String name, BlockPos startOrigin,
                                      StructureModel fallbackModel) {
        discardActive();
        if (name == null || name.isBlank() || startOrigin == null) {
            return;
        }
        StructureModel loaded = StructureLoader.loadFile(BuildingMetaLoader.blueprintFile(name));
        if (loaded == null || loaded.blocks().isEmpty()) {
            loaded = fallbackModel;
        }
        if (loaded == null || loaded.blocks().isEmpty()) {
            return;
        }
        blueprintName = name;
        sourceModel = loaded;
        rotation = 0;
        heightOffset = 0;
        baseY = startOrigin.getY();
        origin = startOrigin.immutable();
        active = true;
        com.xy2407.kingdom.client.area.AreaOverviewCache.request();
        rebuildMesh();
    }

    public static void tickRtsFollow() {
        if (!active || origin == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        Vec3 picked = pickGround();
        if (picked == null) {
            return;
        }
        BlockPos moved = new BlockPos((int) Math.floor(picked.x), baseY + heightOffset, (int) Math.floor(picked.z));
        if (!moved.equals(origin)) {
            origin = moved.immutable();
        }
    }

    public static void rotate() {
        if (!active) {
            return;
        }
        rotation = (rotation + 90) % 360;
        rebuildMesh();
    }

    public static void adjustHeight(int delta) {
        if (!active || origin == null) {
            return;
        }
        heightOffset += delta;
        origin = new BlockPos(origin.getX(), baseY + heightOffset, origin.getZ());
    }

    public static void confirmPlace() {
        if (!active || origin == null || mesh == null || mesh.isEmpty()) {
            return;
        }
        PENDINGS.add(new Pending(blueprintName, origin, rotation, size(), sourceModel, mesh));
        mesh = null;
        active = false;
        origin = null;
        sourceModel = null;
        blueprintName = "";
    }

    public static void undoLastPlacement() {
        if (PENDINGS.isEmpty()) {
            return;
        }
        Pending removed = PENDINGS.remove(PENDINGS.size() - 1);
        closeMesh(removed.mesh());
    }

    public static void confirmAll() {
        Minecraft minecraft = Minecraft.getInstance();
        if (PENDINGS.isEmpty()) {
            hint(minecraft, "还没有待建投影：先把跟随鼠标的建筑右键固定在落点");
            return;
        }
        if (minecraft.getConnection() == null) {
            return;
        }
        int submitted = PENDINGS.size();
        for (Pending pending : PENDINGS) {
            PacketDistributor.sendToServer(
                    new BuildingPlaceConfirmPacket(pending.name(), pending.origin(), pending.rotation()));
        }
        clearPending();
        hint(minecraft, "已提交 " + submitted + " 处建筑开工");
    }

    private static void hint(Minecraft minecraft, String message) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), true);
        }
    }

    public static boolean beginPendingDrag() {
        if (PENDINGS.isEmpty()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return false;
        }
        double[] mouse = RtsModeManager.getGuiScaledMouse();
        for (int index = PENDINGS.size() - 1; index >= 0; index--) {
            if (hitsPending(PENDINGS.get(index), mouse[0], mouse[1])) {
                dragIndex = index;
                return true;
            }
        }
        return false;
    }

    public static void tickPendingDrag() {
        if (dragIndex < 0 || dragIndex >= PENDINGS.size()) {
            return;
        }
        Vec3 picked = pickGround();
        if (picked == null) {
            return;
        }
        Pending pending = PENDINGS.get(dragIndex);
        BlockPos moved = new BlockPos((int) Math.floor(picked.x), pending.origin().getY(), (int) Math.floor(picked.z));
        if (moved.equals(pending.origin())) {
            return;
        }
        PENDINGS.set(dragIndex, new Pending(pending.name(), moved, pending.rotation(), pending.size(),
                pending.source(), pending.mesh()));
    }

    public static void rotateTarget() {
        if (dragIndex < 0 || dragIndex >= PENDINGS.size()) {
            rotate();
            return;
        }
        Pending pending = PENDINGS.get(dragIndex);
        int nextRotation = (pending.rotation() + 90) % 360;
        StructureModel rotated = StructureRotation.rotate(pending.source(), nextRotation);
        StructureMesh next = StructureMesher.build(rotated);
        closeMesh(pending.mesh());
        PENDINGS.set(dragIndex, new Pending(pending.name(), pending.origin(), nextRotation, rotated.size(),
                pending.source(), next));
    }

    public static void adjustTargetHeight(int delta) {
        if (dragIndex < 0 || dragIndex >= PENDINGS.size()) {
            adjustHeight(delta);
            return;
        }
        Pending pending = PENDINGS.get(dragIndex);
        PENDINGS.set(dragIndex, new Pending(pending.name(), pending.origin().above(delta), pending.rotation(),
                pending.size(), pending.source(), pending.mesh()));
    }

    public static void endPendingDrag() {
        dragIndex = -1;
    }

    public static boolean isDragging() {
        return dragIndex >= 0;
    }

    public static void cancel() {
        discardActive();
        clearPending();
    }

    public static void discardPendings() {
        clearPending();
    }

    public static void discardPendingsWithName(java.util.function.Predicate<String> filter) {
        for (int index = PENDINGS.size() - 1; index >= 0; index--) {
            Pending pending = PENDINGS.get(index);
            if (filter.test(pending.name())) {
                closeMesh(pending.mesh());
                PENDINGS.remove(index);
            }
        }
        dragIndex = -1;
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean hasPending() {
        return !PENDINGS.isEmpty();
    }

    public static List<Pending> pendings() {
        return PENDINGS;
    }

    public static StructureMesh mesh() {
        return mesh;
    }

    public static BlockPos origin() {
        return origin;
    }

    public static int rotationDegrees() {
        return rotation;
    }

    public static Vec3i size() {
        StructureModel rotated = rotatedModel();
        return rotated == null ? Vec3i.ZERO : rotated.size();
    }

    private static StructureModel rotatedModel() {
        return sourceModel == null ? null : StructureRotation.rotate(sourceModel, rotation);
    }

    private static void rebuildMesh() {
        StructureModel rotated = rotatedModel();
        if (rotated == null) {
            return;
        }
        StructureMesh next = StructureMesher.build(rotated);
        closeMesh(mesh);
        mesh = next;
    }

    private static Vec3 pickGround() {
        double[] mouse = RtsModeManager.getGuiScaledMouse();
        return RtsPicker.pickTargetBlock(mouse[0], mouse[1]);
    }

    private static boolean hitsPending(Pending pending, double mouseX, double mouseY) {
        BlockPos min = pending.origin();
        Vec3i size = pending.size();
        double left = Double.MAX_VALUE;
        double top = Double.MAX_VALUE;
        double right = -Double.MAX_VALUE;
        double bottom = -Double.MAX_VALUE;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    Vec3 corner = new Vec3(min.getX() + dx * size.getX(), min.getY() + dy * size.getY(),
                            min.getZ() + dz * size.getZ());
                    double[] screen = RtsPicker.projectToScreen(corner);
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

    private static void discardActive() {
        closeMesh(mesh);
        mesh = null;
        sourceModel = null;
        origin = null;
        blueprintName = "";
        rotation = 0;
        heightOffset = 0;
        baseY = 0;
        active = false;
        dragIndex = -1;
    }

    private static void clearPending() {
        for (Pending pending : PENDINGS) {
            closeMesh(pending.mesh());
        }
        PENDINGS.clear();
        dragIndex = -1;
    }

    private static void closeMesh(StructureMesh target) {
        if (target != null) {
            target.close();
        }
    }
}
