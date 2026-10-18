package com.xy2407.kingdom.client.rts;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xy2407.kingdom.ModMain;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * 区域框选渲染：角块常显红面，按 Enter 后才用红面包裹两角之间的长方体
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class RegionSelectionRenderer {

    private static final float[] CORNER_FILL = {0.95F, 0.28F, 0.22F, 0.40F};
    private static final float[] CORNER_EDGE = {1.00F, 0.35F, 0.28F, 0.95F};
    private static final float[] REGION_FILL = {0.90F, 0.22F, 0.16F, 0.14F};
    private static final float[] REGION_EDGE = {0.98F, 0.32F, 0.25F, 0.90F};
    private static final float[] HOVER_EDGE = {1.00F, 0.55F, 0.45F, 0.85F};

    private RegionSelectionRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!RtsModeManager.isActive() || RtsModeManager.getSubMode() != RtsSubMode.REGION_TASK) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        PoseStack poseStack = new PoseStack();
        Matrix4f matrix = poseStack.last().pose();

        BlockPos min = RegionSelectionState.minBlock();
        BlockPos max = RegionSelectionState.maxBlock();
        BlockPos cornerA = RegionSelectionState.cornerA();
        BlockPos cornerB = RegionSelectionState.cornerB();
        BlockPos hover = RegionSelectionState.hover();
        boolean region = min != null && max != null && RegionSelectionState.regionVisible();

        if (region) {
            VertexConsumer fill = buffers.getBuffer(RenderType.debugFilledBox());
            addBoxFaces(fill, matrix,
                    (float) (min.getX() - camera.x), (float) (min.getY() - camera.y),
                    (float) (min.getZ() - camera.z),
                    (float) (max.getX() + 1 - camera.x), (float) (max.getY() + 1 - camera.y),
                    (float) (max.getZ() + 1 - camera.z), REGION_FILL);
            buffers.endBatch(RenderType.debugFilledBox());
        }

        VertexConsumer fill = buffers.getBuffer(RenderType.debugFilledBox());
        addCornerFaces(fill, matrix, cornerA, camera);
        addCornerFaces(fill, matrix, cornerB, camera);
        buffers.endBatch(RenderType.debugFilledBox());

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        if (region) {
            LevelRenderer.renderLineBox(poseStack, lines,
                    box(min, max, camera),
                    REGION_EDGE[0], REGION_EDGE[1], REGION_EDGE[2], REGION_EDGE[3]);
        }
        drawCornerEdge(lines, poseStack, cornerA, camera);
        drawCornerEdge(lines, poseStack, cornerB, camera);
        if (hover != null && !hover.equals(cornerA) && !hover.equals(cornerB)) {
            LevelRenderer.renderLineBox(poseStack, lines, inflate(box(hover, hover, camera), 0.004D),
                    HOVER_EDGE[0], HOVER_EDGE[1], HOVER_EDGE[2], HOVER_EDGE[3]);
        }
        buffers.endBatch(RenderType.lines());
    }

    private static void addCornerFaces(VertexConsumer fill, Matrix4f matrix, BlockPos corner, Vec3 camera) {
        if (corner == null) {
            return;
        }
        addBoxFaces(fill, matrix,
                (float) (corner.getX() - camera.x), (float) (corner.getY() - camera.y),
                (float) (corner.getZ() - camera.z),
                (float) (corner.getX() + 1 - camera.x), (float) (corner.getY() + 1 - camera.y),
                (float) (corner.getZ() + 1 - camera.z), CORNER_FILL);
    }

    private static void drawCornerEdge(VertexConsumer lines, PoseStack poseStack, BlockPos corner, Vec3 camera) {
        if (corner == null) {
            return;
        }
        LevelRenderer.renderLineBox(poseStack, lines, inflate(box(corner, corner, camera), 0.002D),
                CORNER_EDGE[0], CORNER_EDGE[1], CORNER_EDGE[2], CORNER_EDGE[3]);
    }

    private static AABB box(BlockPos min, BlockPos max, Vec3 camera) {
        return new AABB(
                min.getX() - camera.x, min.getY() - camera.y, min.getZ() - camera.z,
                max.getX() + 1 - camera.x, max.getY() + 1 - camera.y, max.getZ() + 1 - camera.z);
    }

    private static AABB inflate(AABB box, double amount) {
        return box.inflate(amount);
    }

    private static void addBoxFaces(VertexConsumer consumer, Matrix4f matrix,
                                    float x0, float y0, float z0, float x1, float y1, float z1,
                                    float[] color) {
        quad(consumer, matrix, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, color);
        quad(consumer, matrix, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, color);
        quad(consumer, matrix, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, color);
        quad(consumer, matrix, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, color);
        quad(consumer, matrix, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, color);
        quad(consumer, matrix, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, color);
    }

    private static void quad(VertexConsumer consumer, Matrix4f matrix,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float[] color) {
        consumer.addVertex(matrix, ax, ay, az).setColor(color[0], color[1], color[2], color[3]);
        consumer.addVertex(matrix, bx, by, bz).setColor(color[0], color[1], color[2], color[3]);
        consumer.addVertex(matrix, cx, cy, cz).setColor(color[0], color[1], color[2], color[3]);
        consumer.addVertex(matrix, dx, dy, dz).setColor(color[0], color[1], color[2], color[3]);
    }
}
