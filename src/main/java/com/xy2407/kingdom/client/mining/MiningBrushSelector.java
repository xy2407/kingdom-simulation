package com.xy2407.kingdom.client.mining;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.blaze3d.platform.InputConstants;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.rts.RtsModeManager;
import com.xy2407.kingdom.client.rts.RtsPicker;
import com.xy2407.kingdom.common.network.mining.MiningAreaSubmitPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 采矿笔刷：地表 5×5 圆盘刷逐格记录，Enter 提交矿坑范围，Esc 退出。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class MiningBrushSelector {

    private static final int BRUSH_DIAMETER = 5;

    private static final Map<Long, Integer> PENDING = new LinkedHashMap<>();

    private static final Deque<Set<Long>> UNDO = new ArrayDeque<>();

    private static Set<Long> stroke;

    private static boolean active;

    private static boolean drawing;

    private static BlockPos lastCell;

    private static UUID buildingId;

    private MiningBrushSelector() {
    }

    public static void begin(UUID building) {
        buildingId = building;
        active = true;
        clearPending();
        hint("采矿笔刷 5×5：右键拖动刷地表，Enter 提交，C 撤回，Esc 退出");
    }

    public static void toggle() {
        if (!active && !RtsModeManager.isActive()) {
            hint("先按 F6 进入 RTS 视角，再开采矿笔刷");
            return;
        }
        active = !active;
        if (active) {
            hint("采矿笔刷 5×5：右键拖动刷地表，Enter 提交，C 撤回，Esc 退出");
            PacketDistributor.sendToServer(new com.xy2407.kingdom.common.network.mining.MiningPanelRequestPacket());
        } else {
            clearPending();
            hint("采矿笔刷已关闭");
        }
    }

    private static void hint(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), true);
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static Map<Long, Integer> pending() {
        return PENDING;
    }

    public static int count() {
        return PENDING.size();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!active || Minecraft.getInstance().screen != null) {
            return;
        }
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return;
        }
        event.setCanceled(true);
        if (event.getAction() == InputConstants.PRESS) {
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

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!active || event.getAction() != InputConstants.PRESS) {
            return;
        }
        if (event.getKey() == GLFW.GLFW_KEY_ENTER || event.getKey() == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
        } else if (event.getKey() == GLFW.GLFW_KEY_C) {
            undo();
        } else if (event.getKey() == GLFW.GLFW_KEY_ESCAPE) {
            toggle();
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!active || !drawing) {
            return;
        }
        tick();
    }

    private static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        double[] mouse = mouse();
        RtsPicker.BlockHit hit = RtsPicker.pickBlockHit(mouse[0], mouse[1]);
        if (hit == null) {
            return;
        }
        int x = hit.blockPos().getX();
        int z = hit.blockPos().getZ();
        if (lastCell == null) {
            stamp(x, z);
        } else {
            stampLine(lastCell.getX(), lastCell.getZ(), x, z);
        }
        lastCell = new BlockPos(x, minecraft.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z), z);
    }

    private static void stampLine(int x0, int z0, int x1, int z1) {
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0;
        int z = z0;
        while (true) {
            stamp(x, z);
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

    private static void stamp(int centerX, int centerZ) {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) {
            return;
        }
        int radius = (BRUSH_DIAMETER - 1) / 2;
        int centerY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                centerX, centerZ);
        addCell(centerX, centerY, centerZ);
        double limit = (radius + 0.5) * (radius + 0.5);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (dx * dx + dz * dz > limit) {
                    continue;
                }
                int x = centerX + dx;
                int z = centerZ + dz;
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                addCell(x, y, z);
            }
        }
    }

    private static void addCell(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        if (PENDING.containsKey(key)) {
            return;
        }
        PENDING.put(key, y);
        if (stroke != null) {
            stroke.add(key);
        }
    }

    private static void undo() {
        if (UNDO.isEmpty()) {
            return;
        }
        Set<Long> last = UNDO.pop();
        for (long key : last) {
            PENDING.remove(key);
        }
    }

    private static void submit() {
        if (PENDING.isEmpty()) {
            return;
        }
        long[] cells = new long[PENDING.size()];
        int index = 0;
        for (long key : PENDING.keySet()) {
            cells[index++] = key;
        }
        PacketDistributor.sendToServer(new MiningAreaSubmitPacket(cells, buildingId));
        clearPending();
        active = false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.setScreen(new com.xy2407.kingdom.client.industry.IndustryPanelScreen());
        }
    }

    private static void clearPending() {
        PENDING.clear();
        UNDO.clear();
        stroke = null;
        lastCell = null;
        drawing = false;
    }

    private static double[] mouse() {
        Minecraft minecraft = Minecraft.getInstance();
        double[] result = new double[2];
        if (minecraft.getWindow() == null) {
            return result;
        }
        double scale = minecraft.getWindow().getGuiScale();
        result[0] = minecraft.mouseHandler.xpos() / scale;
        result[1] = minecraft.mouseHandler.ypos() / scale;
        return result;
    }
}
