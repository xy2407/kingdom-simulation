package com.xy2407.kingdom.client.area;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.xy2407.kingdom.client.rts.RtsModeManager;
import com.xy2407.kingdom.client.rts.RtsPicker;
import com.xy2407.kingdom.common.area.PlotArea;
import com.xy2407.kingdom.common.area.PlotAreaKind;
import com.xy2407.kingdom.common.network.area.AreaStatePacket;
import com.xy2407.kingdom.common.network.area.AreaStateRequestPacket;
import com.xy2407.kingdom.common.network.area.AreaSubmitPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 伐木/养殖范围选区：RTS 下右键按住拖动刷地面格，保留原始形状；Enter 保存，C 撤销，Esc 取消。
 * 进入模式时会向服务端请求该维度已有的同类型范围，用于高亮。
 */
@OnlyIn(Dist.CLIENT)
public final class AreaSelectionState {

    private static final int BRUSH_RADIUS = 2;

    private static final int MAX_CELLS = PlotArea.MAX_CELLS;

    private static final Map<Long, Integer> PENDING = new LinkedHashMap<>();

    private static final Deque<Set<Long>> UNDO = new ArrayDeque<>();

    private static final Map<UUID, List<Long>> EXISTING = new LinkedHashMap<>();

    private static PlotAreaKind kind;

    private static boolean drawing;

    private static BlockPos lastCell;

    private static Set<Long> stroke;

    private static String hint = "";

    private AreaSelectionState() {
    }

    public static void begin(PlotAreaKind value) {
        if (value == null) {
            return;
        }
        kind = value;
        PENDING.clear();
        UNDO.clear();
        drawing = false;
        lastCell = null;
        stroke = null;
        hint(value.label() + "：右键按住拖动圈选地面，Enter 保存，C 撤销，Esc 取消");
        PacketDistributor.sendToServer(new AreaStateRequestPacket(value));
    }

    public static boolean isActive() {
        return kind != null && RtsModeManager.isActive();
    }

    public static PlotAreaKind kind() {
        return kind;
    }

    public static Map<Long, Integer> pending() {
        return PENDING;
    }

    public static Map<UUID, List<Long>> existing() {
        return EXISTING;
    }

    public static void onRightButton(boolean press, double mouseX, double mouseY) {
        if (!isActive()) {
            return;
        }
        if (press) {
            drawing = true;
            stroke = new LinkedHashSet<>();
            lastCell = null;
            paintTo(mouseX, mouseY);
        } else {
            drawing = false;
            lastCell = null;
            if (stroke != null && !stroke.isEmpty()) {
                UNDO.push(stroke);
                if (UNDO.size() > 32) {
                    UNDO.removeLast();
                }
            }
            stroke = null;
            hint(kind.label() + "：已选 " + PENDING.size() + " 格，Enter 保存，C 撤销");
        }
    }

    public static void tick() {
        if (!isActive()) {
            return;
        }
        if (drawing) {
            double[] mouse = RtsModeManager.getGuiScaledMouse();
            if (mouse != null) {
                paintTo(mouse[0], mouse[1]);
            }
        }
    }

    public static void onKeyPress(int key) {
        if (!isActive()) {
            return;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return;
        }
        if (key == GLFW.GLFW_KEY_C) {
            undo();
            return;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            cancel();
        }
    }

    public static void submit() {
        if (!isActive()) {
            return;
        }
        if (PENDING.isEmpty()) {
            hint(kind.label() + "：范围为空，先右键刷几格");
            return;
        }
        PlotAreaKind submitting = kind;
        long[] cells = submitting == PlotAreaKind.RANCH ? rectangleCells() : rawCells();
        PacketDistributor.sendToServer(new AreaSubmitPacket(submitting, cells));
        hint(submitting.label() + "：已提交 " + cells.length + " 格");
        PENDING.clear();
        UNDO.clear();
        drawing = false;
        stroke = null;
        kind = null;
        RtsModeManager.exitSubMode();
    }

    private static long[] rawCells() {
        long[] cells = new long[Math.min(PENDING.size(), MAX_CELLS)];
        int index = 0;
        for (Long key : PENDING.keySet()) {
            if (index >= cells.length) {
                break;
            }
            BlockPos pos = BlockPos.of(key);
            Integer y = PENDING.get(key);
            int top = y == null ? pos.getY() : y;
            cells[index++] = BlockPos.asLong(pos.getX(), top, pos.getZ());
        }
        return cells;
    }

    private static long[] rectangleCells() {
        Map<Long, Integer> columnY = new LinkedHashMap<>();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Map.Entry<Long, Integer> entry : PENDING.entrySet()) {
            BlockPos pos = BlockPos.of(entry.getKey());
            columnY.putIfAbsent(BlockPos.asLong(pos.getX(), 0, pos.getZ()), entry.getValue());
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        if (minX > maxX || minZ > maxZ) {
            return new long[0];
        }
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        int total = Math.min(width * depth, MAX_CELLS);
        long[] cells = new long[total];
        Minecraft minecraft = Minecraft.getInstance();
        int index = 0;
        for (int z = minZ; z <= maxZ && index < total; z++) {
            for (int x = minX; x <= maxX && index < total; x++) {
                Integer y = columnY.get(BlockPos.asLong(x, 0, z));
                int top = y == null ? groundY(minecraft, x, z) : y;
                cells[index++] = BlockPos.asLong(x, top, z);
            }
        }
        return cells;
    }

    public static void cancel() {
        PENDING.clear();
        UNDO.clear();
        drawing = false;
        lastCell = null;
        stroke = null;
        kind = null;
        hint = "";
        RtsModeManager.exitSubMode();
    }

    public static void accept(AreaStatePacket packet) {
        if (packet == null || packet.kind() == null) {
            return;
        }
        if (kind != null && packet.kind() != kind) {
            return;
        }
        EXISTING.clear();
        UUID[] ids = packet.buildingIds();
        long[][] cells = packet.cells();
        if (ids == null || cells == null) {
            return;
        }
        for (int index = 0; index < ids.length && index < cells.length; index++) {
            if (ids[index] == null || cells[index] == null) {
                continue;
            }
            List<Long> list = new ArrayList<>(cells[index].length);
            for (long cell : cells[index]) {
                list.add(cell);
            }
            EXISTING.put(ids[index], list);
        }
    }

    private static void undo() {
        Set<Long> last = UNDO.poll();
        if (last == null) {
            hint(kind.label() + "：没有可撤销的一笔");
            return;
        }
        for (Long key : last) {
            PENDING.remove(key);
        }
        hint(kind.label() + "：已撤销一笔，剩 " + PENDING.size() + " 格");
    }

    private static void paintTo(double mouseX, double mouseY) {
        BlockPos hover = hoverCell(mouseX, mouseY);
        if (hover == null) {
            return;
        }
        if (lastCell != null) {
            stampLine(lastCell, hover);
        }
        stamp(hover);
        lastCell = hover;
    }

    private static BlockPos hoverCell(double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        RtsPicker.BlockHit hit = RtsPicker.pickBlockHit(mouseX, mouseY);
        if (hit == null || hit.blockPos() == null) {
            return null;
        }
        BlockPos pos = hit.blockPos();
        return new BlockPos(pos.getX(), groundY(minecraft, pos.getX(), pos.getZ()), pos.getZ());
    }

    private static int groundY(Minecraft minecraft, int x, int z) {
        int y = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        if (y < minecraft.level.getMinBuildHeight()) {
            y = minecraft.level.getMinBuildHeight();
        }
        return y;
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
        Minecraft minecraft = Minecraft.getInstance();
        int guard = 0;
        while (guard++ < 512) {
            addCell(x0, groundY(minecraft, x0, z0), z0);
            if (x0 == x1 && z0 == z1) {
                break;
            }
            int e2 = err << 1;
            if (e2 > -dz) {
                err -= dz;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                z0 += sz;
            }
        }
    }

    private static void stamp(BlockPos center) {
        for (int offsetX = -BRUSH_RADIUS; offsetX <= BRUSH_RADIUS; offsetX++) {
            for (int offsetZ = -BRUSH_RADIUS; offsetZ <= BRUSH_RADIUS; offsetZ++) {
                if (offsetX * offsetX + offsetZ * offsetZ > BRUSH_RADIUS * BRUSH_RADIUS + 1) {
                    continue;
                }
                int x = center.getX() + offsetX;
                int z = center.getZ() + offsetZ;
                Minecraft minecraft = Minecraft.getInstance();
                addCell(x, groundY(minecraft, x, z), z);
            }
        }
    }

    private static void addCell(int x, int y, int z) {
        if (PENDING.size() >= MAX_CELLS) {
            return;
        }
        long key = BlockPos.asLong(x, y, z);
        if (PENDING.containsKey(key)) {
            return;
        }
        PENDING.put(key, y);
        if (stroke != null) {
            stroke.add(key);
        }
    }

    private static void hint(String message) {
        hint = message;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), true);
        }
    }
}
