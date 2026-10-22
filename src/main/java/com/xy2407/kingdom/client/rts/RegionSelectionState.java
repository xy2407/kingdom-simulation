package com.xy2407.kingdom.client.rts;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import com.xy2407.kingdom.ModMain;

import com.xy2407.kingdom.common.network.builder.RegionTaskCreatePacket;

/**
 * 区域框选状态机：右键在命中面上放两角、左键按住拖角、Enter 包面再 Enter 弹确认
 */
@OnlyIn(Dist.CLIENT)
public final class RegionSelectionState {

    public enum Kind { FILL, DEMOLISH }

    private static Kind kind = Kind.FILL;
    private static BlockPos cornerA;
    private static BlockPos cornerB;
    private static BlockPos hover;
    private static int dragCorner;
    private static Integer dragY;
    private static boolean regionVisible;

    private RegionSelectionState() {
    }

    public static Kind kind() {
        return kind;
    }

    public static void begin(Kind value) {
        kind = value == null ? Kind.FILL : value;
        resetCorners();
        hint((kind == Kind.FILL ? "填充" : "拆除") + "区域：右键点击方块表面放角块，左键按住拖动角块，拖住时滚轮调高度");
    }

    public static void resetCorners() {
        cornerA = null;
        cornerB = null;
        hover = null;
        dragCorner = 0;
        dragY = null;
        regionVisible = false;
        RegionHudLayer.closeConfirm();
    }

    public static void reset() {
        resetCorners();
    }

    public static boolean isActive() {
        return RtsModeManager.isActive() && RtsModeManager.getSubMode() == RtsSubMode.REGION_TASK;
    }

    public static BlockPos cornerA() {
        return cornerA;
    }

    public static BlockPos cornerB() {
        return cornerB;
    }

    public static BlockPos hover() {
        return hover;
    }

    public static boolean regionVisible() {
        return regionVisible;
    }

    public static boolean hasBothCorners() {
        return cornerA != null && cornerB != null;
    }

    public static BlockPos minBlock() {
        if (cornerA == null || cornerB == null) {
            return null;
        }
        return new BlockPos(Math.min(cornerA.getX(), cornerB.getX()),
                Math.min(cornerA.getY(), cornerB.getY()),
                Math.min(cornerA.getZ(), cornerB.getZ()));
    }

    public static BlockPos maxBlock() {
        if (cornerA == null || cornerB == null) {
            return null;
        }
        return new BlockPos(Math.max(cornerA.getX(), cornerB.getX()),
                Math.max(cornerA.getY(), cornerB.getY()),
                Math.max(cornerA.getZ(), cornerB.getZ()));
    }

    public static long volume() {
        BlockPos min = minBlock();
        BlockPos max = maxBlock();
        if (min == null) {
            return 0L;
        }
        return (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1)
                * (long) (max.getZ() - min.getZ() + 1);
    }

    public static void tick() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null || minecraft.player == null) {
                hover = null;
                return;
            }
            hover = pickHover();
            if (dragCorner != 0 && hover != null) {
                BlockPos base = hover.immutable();
                BlockPos moved = dragY != null
                        ? new BlockPos(base.getX(), dragY, base.getZ())
                        : base;
                if (dragCorner == 1) {
                    cornerA = moved;
                } else if (dragCorner == 2) {
                    cornerB = moved;
                }
            }
        } catch (Exception failure) {
            ModMain.LOGGER.error("[区域框选] 每帧推进异常 kind={} drag={}", kind, dragCorner, failure);
            hint("区域框选出错，已重置（详见日志）");
            resetCorners();
        }
    }

    public static void onRightButton(boolean press) {
        if (!press) {
            return;
        }
        BlockPos picked = hover != null ? hover.immutable() : pickHover();
        if (picked == null) {
            hint("没有有效的落点，把鼠标对准方块表面");
            return;
        }
        if (cornerA == null) {
            cornerA = picked;
            cornerB = null;
            regionVisible = false;
            hint("已放第一个角 " + describe(picked) + "，再右键放对角");
        } else {
            cornerB = picked;
            regionVisible = false;
            hint("已放对角 " + describe(picked) + "，按 Enter 包面预览");
        }
    }

    public static void onLeftDown() {
        if (cornerA == null && cornerB == null) {
            return;
        }
        BlockPos picked = hover != null ? hover.immutable() : pickHover();
        if (picked == null) {
            return;
        }
        if (cornerB == null) {
            dragCorner = 1;
            return;
        }
        if (cornerA == null) {
            dragCorner = 2;
            return;
        }
        double distanceA = picked.distSqr(cornerA);
        double distanceB = picked.distSqr(cornerB);
        dragCorner = distanceA <= distanceB ? 1 : 2;
    }

    public static void onLeftUp() {
        dragCorner = 0;
        dragY = null;
    }

    public static boolean isDragging() {
        return dragCorner != 0;
    }

    // 左键拖住角块时滚轮调该角高度；未拖动时调最后放置的角。
    // 拖动中只记录 y 覆盖（x/z 仍随鼠标），避免被下一帧 hover 覆盖掉。
    public static void adjustHeight(int delta) {
        BlockPos target = dragCorner == 1 ? cornerA
                : dragCorner == 2 ? cornerB
                : cornerB != null ? cornerB : cornerA;
        if (target == null) {
            return;
        }
        int newY = clampHeight(target.getY() + delta);
        if (dragCorner != 0) {
            dragY = newY;
        }
        if (dragCorner == 1 || (dragCorner == 0 && target == cornerA)) {
            cornerA = new BlockPos(cornerA.getX(), newY, cornerA.getZ());
        } else {
            cornerB = new BlockPos(cornerB.getX(), newY, cornerB.getZ());
        }
        hint("角块高度 → " + newY);
    }

    private static int clampHeight(int y) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return y;
        }
        return Math.max(level.getMinBuildHeight(),
                Math.min(level.getMaxBuildHeight() - 1, y));
    }

    public static void onKeyPress(int key) {
        try {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                onEnter();
                return;
            }
            if (key == GLFW.GLFW_KEY_R) {
                resetCorners();
                hint("已清空框选，重新右键放角");
                return;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                cancelByEscape();
            }
        } catch (Exception failure) {
            ModMain.LOGGER.error("[区域框选] 按键处理异常 key={} a={} b={}", key, cornerA, cornerB, failure);
            hint("区域框选出错，已重置（详见日志）");
            resetCorners();
        }
    }

    private static void onEnter() {
        if (RegionHudLayer.isConfirming()) {
            return;
        }
        if (cornerA == null || cornerB == null) {
            hint(cornerA == null ? "先用右键放第一个角" : "还差一个对角，再右键一次");
            return;
        }
        if (!regionVisible) {
            regionVisible = true;
            hint("区域 " + volume() + " 格，再按 Enter 弹出确认");
            return;
        }
        RegionHudLayer.openConfirm(kind, volume());
    }

    public static void cancelByEscape() {
        if (RegionHudLayer.isConfirming()) {
            RegionHudLayer.closeConfirm();
            hint("已取消确认，Enter 可重新弹出");
            return;
        }
        resetCorners();
    }

    public static void confirm() {
        BlockPos min = minBlock();
        BlockPos max = maxBlock();
        if (min == null || max == null) {
            return;
        }
        int packetKind = kind == Kind.FILL
                ? RegionTaskCreatePacket.KIND_FILL : RegionTaskCreatePacket.KIND_DEMOLISH;
        PacketDistributor.sendToServer(new RegionTaskCreatePacket(packetKind,
                min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
        hint("任务已提交，可在建筑工方块面板里查看");
        resetCorners();
        RtsModeManager.exitSubMode();
    }

    private static BlockPos pickHover() {
        double[] mouse = RtsModeManager.getGuiScaledMouse();
        if (mouse == null) {
            return null;
        }
        RtsPicker.BlockHit hit = RtsPicker.pickBlockHit(mouse[0], mouse[1]);
        if (hit == null || hit.blockPos() == null || hit.direction() == null) {
            return null;
        }
        return hit.blockPos().relative(hit.direction()).immutable();
    }

    private static void hint(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), true);
        }
    }

    private static String describe(BlockPos pos) {
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }
}
