package com.xy2407.kingdom.client.rts;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 按住 C 录制节点路径期间，绘制路点方块与相邻路点之间的连线
 */
@OnlyIn(Dist.CLIENT)
public final class RtsWaypointMarkerRenderer {

    private static final float CELL_R = 0.0F;
    private static final float CELL_G = 1.0F;
    private static final float CELL_B = 0.5F;
    private static final float LINE_R = 0.3F;
    private static final float LINE_G = 0.8F;
    private static final float LINE_B = 1.0F;
    private static final double CELL_HALF = 0.45D;
    private static final double LINE_LIFT = 0.5D;

    private RtsWaypointMarkerRenderer() {
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || !RtsModeManager.isActive()) {
            return;
        }
        List<Vec3> waypoints = RtsWaypointState.getWaypoints();
        if (waypoints.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        VertexConsumer consumer = minecraft.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        for (Vec3 waypoint : waypoints) {
            renderCell(poseStack, consumer, waypoint);
        }
        for (int index = 0; index < waypoints.size() - 1; index++) {
            Vec3 from = waypoints.get(index).add(0.0D, LINE_LIFT, 0.0D);
            Vec3 to = waypoints.get(index + 1).add(0.0D, LINE_LIFT, 0.0D);
            renderLine(poseStack, consumer, from, to);
        }
        minecraft.renderBuffers().bufferSource().endBatch(RenderType.lines());

        poseStack.popPose();
    }

    private static void renderCell(PoseStack poseStack, VertexConsumer consumer, Vec3 waypoint) {
        AABB box = new AABB(
                waypoint.x - CELL_HALF, waypoint.y - 0.02D, waypoint.z - CELL_HALF,
                waypoint.x + CELL_HALF, waypoint.y + 0.05D, waypoint.z + CELL_HALF);
        LevelRenderer.renderLineBox(poseStack, consumer, box, CELL_R, CELL_G, CELL_B, 1.0F);
    }

    private static void renderLine(PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to) {
        Matrix4f matrix = poseStack.last().pose();
        float dx = (float) (to.x - from.x);
        float dy = (float) (to.y - from.y);
        float dz = (float) (to.z - from.z);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float nx = len > 0.0F ? dx / len : 0.0F;
        float ny = len > 0.0F ? dy / len : 0.0F;
        float nz = len > 0.0F ? dz / len : 0.0F;
        consumer.addVertex(matrix, (float) from.x, (float) from.y, (float) from.z)
                .setColor(LINE_R, LINE_G, LINE_B, 1.0F)
                .setNormal(nx, ny, nz);
        consumer.addVertex(matrix, (float) to.x, (float) to.y, (float) to.z)
                .setColor(LINE_R, LINE_G, LINE_B, 1.0F)
                .setNormal(nx, ny, nz);
    }
}
