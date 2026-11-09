package com.xy2407.kingdom.client.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.client.structure.StructureDebugChat;
import com.xy2407.kingdom.common.network.rts.RtsPatrolOrderPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 队伍巡逻路线的客户端录制状态。
 * <p>
 * 流程：左侧栏队伍卡片点「安排巡逻」→ {@link #arm(UUID)}（整队自动选中、关掉侧栏）→
 * 玩家按住 V 依次右键点选节点（起点 … 终点）→ 松开 V 提交整条路线给服务端。
 */
@OnlyIn(Dist.CLIENT)
public final class RtsPatrolState {

    private static final List<Vec3> NODES = new ArrayList<>();

    private static UUID teamId;
    private static boolean recording;
    private static boolean keyHeld;

    private RtsPatrolState() {
    }

    /** 进入待录制状态：只记录队伍，节点等玩家按 V 之后才开始积累。 */
    public static void arm(UUID id) {
        teamId = id;
        NODES.clear();
        recording = false;
        keyHeld = false;
    }

    public static boolean isArmed() {
        return teamId != null;
    }

    public static UUID teamId() {
        return teamId;
    }

    public static boolean isRecording() {
        return recording && teamId != null;
    }

    public static int size() {
        return NODES.size();
    }

    public static List<Vec3> nodes() {
        return NODES;
    }

    /** 右键追加节点：同一个方块内重复点击不重复记账。 */
    public static void append(Vec3 point) {
        if (teamId == null || point == null) {
            return;
        }
        if (!NODES.isEmpty() && NODES.get(NODES.size() - 1).distanceToSqr(point) < 0.25D) {
            return;
        }
        NODES.add(point);
    }

    /** 每帧轮询 V：按下开始录、松开提交。 */
    public static void pollKey() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!RtsModeManager.isActive() || teamId == null
                || minecraft.screen != null || minecraft.getWindow() == null) {
            recording = false;
            keyHeld = false;
            return;
        }
        boolean down = isVDown(minecraft);
        if (down && !keyHeld) {
            recording = true;
        } else if (!down && keyHeld) {
            submit();
        }
        keyHeld = down;
    }

    private static boolean isVDown(Minecraft minecraft) {
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_V);
    }

    private static void submit() {
        recording = false;
        if (NODES.size() < 2) {
            StructureDebugChat.chat("巡逻路线至少要两个节点：继续按住 V 右键点选（当前 %d 个）", NODES.size());
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        PacketDistributor.sendToServer(
                new RtsPatrolOrderPacket(teamId, RtsPatrolOrderPacket.ACTION_START, new ArrayList<>(NODES)));
        StructureDebugChat.chat("已下达巡逻：%d 个节点，队伍将往返巡逻（遇敌全队共享目标）", NODES.size());
        clear();
    }

    public static void clear() {
        teamId = null;
        NODES.clear();
        recording = false;
        keyHeld = false;
    }

    public static void cancel() {
        if (teamId != null) {
            StructureDebugChat.chat("已取消巡逻安排");
        }
        clear();
    }
}
