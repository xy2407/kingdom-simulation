package com.xy2407.kingdom.client.rts;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.network.road.RoadTerritorySubmitPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 领地道路的客户端状态机：右键圆刷拖拽攒待铺格、两帧之间补线、C 撤回本轮待提交的范围。
 */
@OnlyIn(Dist.CLIENT)
public final class RtsRoadState {

    public static final int[] BRUSH_DIAMETERS = {1, 3, 5, 7};

    private static final Map<Long, Integer> PENDING = new LinkedHashMap<>();
    private static final Deque<Set<Long>> UNDO = new ArrayDeque<>();

    private static int brushIndex;
    private static boolean drawing;
    private static BlockPos lastCell;
    private static Set<Long> stroke;
    private static BlockPos hoverCell;

    private RtsRoadState() {
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            hoverCell = null;
            return;
        }
        double[] mouse = RtsModeManager.getGuiScaledMouse();
        RtsPicker.BlockHit hit = RtsPicker.pickBlockHit(mouse[0], mouse[1]);
        hoverCell = hit == null ? null
                : surfaceCell(minecraft.level, hit.blockPos().getX(), hit.blockPos().getZ());
        if (drawing && hoverCell != null) {
            paintTo(hoverCell);
        }
    }

    public static void onRightButton(boolean press, double mouseX, double mouseY) {
        if (press) {
            drawing = true;
            stroke = new LinkedHashSet<>();
            lastCell = null;
            tick();
            return;
        }
        drawing = false;
        lastCell = null;
        if (stroke != null && !stroke.isEmpty()) {
            UNDO.push(stroke);
        }
        stroke = null;
    }

    public static void onKeyPress(int key) {
        if (key == GLFW.GLFW_KEY_C) {
            undo();
        } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            requestSubmit();
        } else if (key == GLFW.GLFW_KEY_ESCAPE) {
            RoadHudLayer.closeConfirm();
        }
    }

    public static void requestSubmit() {
        if (PENDING.isEmpty()) {
            return;
        }
        RoadHudLayer.openConfirm(PENDING.size());
    }

    public static void submitPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        long[] cells = new long[PENDING.size()];
        int index = 0;
        for (long key : PENDING.keySet()) {
            BlockPos pos = BlockPos.of(key);
            cells[index++] = BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
        }
        PacketDistributor.sendToServer(new RoadTerritorySubmitPacket(cells));
        clearPending();
    }

    public static void undo() {
        if (UNDO.isEmpty()) {
            return;
        }
        Set<Long> last = UNDO.pop();
        for (long key : last) {
            PENDING.remove(key);
        }
    }

    public static void clearPending() {
        PENDING.clear();
        UNDO.clear();
        stroke = null;
        lastCell = null;
        drawing = false;
    }

    public static void reset() {
        clearPending();
        hoverCell = null;
        brushIndex = 0;
    }

    public static Map<Long, Integer> pending() {
        return PENDING;
    }

    public static BlockPos hoverCell() {
        return hoverCell;
    }

    public static int pendingCount() {
        return PENDING.size();
    }

    public static int brushIndex() {
        return brushIndex;
    }

    public static void setBrushIndex(int index) {
        brushIndex = Math.max(0, Math.min(BRUSH_DIAMETERS.length - 1, index));
    }

    public static int brushDiameter() {
        return BRUSH_DIAMETERS[brushIndex];
    }

    private static void paintTo(BlockPos cell) {
        if (lastCell == null) {
            stamp(cell);
        } else {
            stampLine(lastCell, cell);
        }
        lastCell = cell;
    }

    private static void stampLine(BlockPos from, BlockPos to) {
        int x0 = from.getX();
        int z0 = from.getZ();
        int x1 = to.getX();
        int z1 = to.getZ();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0;
        int z = z0;
        while (true) {
            stampXZ(x, z);
            if (x == x1 && z == z1) {
                return;
            }
            int e2 = err * 2;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }
    }

    private static void stamp(BlockPos cell) {
        stampXZ(cell.getX(), cell.getZ());
    }

    // 圆刷采样：中心格先解析（参考上一格高度），圆盘内每格以中心高度为相邻参考，
    // 高差超过 ±2 的格（屋檐/陡崖）不铺也不断线——NPC 走不了的路不画
    private static void stampXZ(int centerX, int centerZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Level level = minecraft.level;
        int refY = lastCell == null ? WorldSurfaceSampler.NO_WALKABLE : lastCell.getY();
        int centerY = WorldSurfaceSampler.walkableSurfaceY(level, centerX, centerZ, refY);
        if (centerY == WorldSurfaceSampler.NO_WALKABLE) {
            return;
        }
        addCell(centerX, centerZ, centerY);
        int radius = (brushDiameter() - 1) / 2;
        double limit = (radius + 0.5) * (radius + 0.5);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (dx * dx + dz * dz > limit) {
                    continue;
                }
                int y = WorldSurfaceSampler.walkableSurfaceY(level,
                        centerX + dx, centerZ + dz, centerY);
                if (y != WorldSurfaceSampler.NO_WALKABLE) {
                    addCell(centerX + dx, centerZ + dz, y);
                }
            }
        }
    }

    private static void addCell(int x, int z, int y) {
        long key = BlockPos.asLong(x, y, z);
        if (PENDING.containsKey(key)) {
            return;
        }
        PENDING.put(key, y);
        if (stroke != null) {
            stroke.add(key);
        }
    }

    private static BlockPos surfaceCell(Level level, int x, int z) {
        int refY = lastCell == null ? WorldSurfaceSampler.NO_WALKABLE : lastCell.getY();
        int y = WorldSurfaceSampler.walkableSurfaceY(level, x, z, refY);
        if (y == WorldSurfaceSampler.NO_WALKABLE) {
            return null;
        }
        return new BlockPos(x, y, z);
    }
}
