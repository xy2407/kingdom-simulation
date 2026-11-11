package com.xy2407.kingdom.client.area;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.building.BuildingPlacementManager;
import com.xy2407.kingdom.client.rts.RtsModeManager;
import com.xy2407.kingdom.common.area.PlotAreaKind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 作业范围叠层：圈地与建筑投影期间按类型高亮全部已圈地块，并画每块外框
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class AreaSelectionRenderer {

    private static final float[] PENDING_COLOR = {0.98F, 0.82F, 0.25F, 0.95F};

    private static final float[] EXISTING_COLOR = {0.35F, 0.85F, 0.80F, 0.85F};

    private static final float[] LUMBER_COLOR = {0.35F, 0.85F, 0.55F, 0.90F};

    private static final float[] RANCH_COLOR = {0.95F, 0.65F, 0.30F, 0.90F};

    private static final double SHOW_RADIUS = 128.0D;

    private static final int MAX_BOXES = 20000;

    private AreaSelectionRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !RtsModeManager.isActive()) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = new PoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        VertexConsumer consumer = minecraft.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        if (AreaSelectionState.isActive()) {
            int drawn = 0;
            for (long[] area : new ArrayList<>(AreaSelectionState.existing())) {
                for (long cell : area) {
                    if (drawn++ >= MAX_BOXES) {
                        break;
                    }
                    drawCell(poseStack, consumer, cell, EXISTING_COLOR);
                }
            }
            drawn = 0;
            for (Map.Entry<Long, Integer> entry
                    : new ArrayList<>(AreaSelectionState.pending().entrySet())) {
                if (drawn++ >= MAX_BOXES) {
                    break;
                }
                drawCell(poseStack, consumer, entry.getKey(), PENDING_COLOR);
            }
        }
        if (AreaSelectionState.isActive() || BuildingPlacementManager.isActive()
                || BuildingPlacementManager.hasPending()) {
            drawOverview(poseStack, consumer, camera);
        }
        minecraft.renderBuffers().bufferSource().endBatch(RenderType.lines());
        poseStack.popPose();
    }

    private static void drawOverview(PoseStack poseStack, VertexConsumer consumer, Vec3 camera) {
        int drawn = 0;
        for (AreaOverviewCache.Block block : AreaOverviewCache.areas()) {
            float[] color = colorOf(block.kind());
            boolean near = false;
            double minX = Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            double topY = -Double.MAX_VALUE;
            for (long cell : block.cells()) {
                BlockPos pos = BlockPos.of(cell);
                double dx = pos.getX() + 0.5D - camera.x;
                double dz = pos.getZ() + 0.5D - camera.z;
                if (!near && dx * dx + dz * dz > SHOW_RADIUS * SHOW_RADIUS) {
                    continue;
                }
                near = true;
                if (drawn >= MAX_BOXES) {
                    continue;
                }
                drawn++;
                drawCell(poseStack, consumer, cell, color);
                minX = Math.min(minX, pos.getX());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX() + 1.0D);
                maxZ = Math.max(maxZ, pos.getZ() + 1.0D);
                topY = Math.max(topY, pos.getY() + 1.02D);
            }
            if (near && minX <= maxX) {
                AABB outline = new AABB(minX, topY + 0.35D, minZ, maxX, topY + 0.35D, maxZ);
                LevelRenderer.renderLineBox(poseStack, consumer, outline,
                        color[0], color[1], color[2], 1.0F);
            }
        }
    }

    private static float[] colorOf(PlotAreaKind kind) {
        return kind == PlotAreaKind.RANCH ? RANCH_COLOR : LUMBER_COLOR;
    }

    private static void drawCell(PoseStack poseStack, VertexConsumer consumer, Long packed,
                                 float[] color) {
        if (packed == null) {
            return;
        }
        BlockPos pos = BlockPos.of(packed);
        double x = pos.getX();
        double y = pos.getY() + 1.02D;
        double z = pos.getZ();
        AABB box = new AABB(x, y, z, x + 1.0D, y, z + 1.0D);
        LevelRenderer.renderLineBox(poseStack, consumer, box, color[0], color[1], color[2], color[3]);
    }
}
