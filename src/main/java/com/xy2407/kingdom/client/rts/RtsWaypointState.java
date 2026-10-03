package com.xy2407.kingdom.client.rts;

import com.xy2407.kingdom.common.network.rts.RtsWaypointMovePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 按住 C 键录制节点路径、松开时提交给服务端的客户端状态与按键轮询
 */
@OnlyIn(Dist.CLIENT)
public final class RtsWaypointState {

    private static final List<Vec3> WAYPOINTS = new ArrayList<>();

    private static boolean active;
    private static boolean keyHeld;

    private RtsWaypointState() {
    }

    public static void begin() {
        active = true;
        WAYPOINTS.clear();
    }

    public static void append(Vec3 point) {
        if (!active || point == null) {
            return;
        }
        WAYPOINTS.add(point);
    }

    public static boolean isActive() {
        return active;
    }

    public static int size() {
        return WAYPOINTS.size();
    }

    public static List<Vec3> getWaypoints() {
        return WAYPOINTS;
    }

    public static void end() {
        if (!active) {
            return;
        }
        active = false;
        if (WAYPOINTS.size() < 2) {
            WAYPOINTS.clear();
            return;
        }
        List<UUID> ids = new ArrayList<>(RtsModeManager.getSelectedEntities());
        if (ids.isEmpty()) {
            WAYPOINTS.clear();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            PacketDistributor.sendToServer(new RtsWaypointMovePacket(ids, new ArrayList<>(WAYPOINTS)));
        }
        WAYPOINTS.clear();
    }

    public static void clear() {
        active = false;
        WAYPOINTS.clear();
    }

    public static void pollKey() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!RtsModeManager.isActive() || minecraft.screen != null || minecraft.getWindow() == null) {
            if (active) {
                clear();
            }
            keyHeld = false;
            return;
        }
        boolean down = GLFW.glfwGetKey(minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_C) == GLFW.GLFW_PRESS;
        if (down && !keyHeld && !RtsModeManager.getSelectedEntities().isEmpty()) {
            begin();
        } else if (!down && keyHeld) {
            end();
        }
        keyHeld = down;
    }
}
