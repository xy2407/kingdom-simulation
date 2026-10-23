package com.xy2407.kingdom.client.area;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xy2407.kingdom.ModMain;

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
 * 伐木/养殖范围叠层：正在刷的格子（黄）+ 已保存的范围（青绿），按方块上表面画线框。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class AreaSelectionRenderer {

    private static final float[] PENDING_COLOR = {0.98F, 0.82F, 0.25F, 0.95F};

    private static final float[] EXISTING_COLOR = {0.35F, 0.85F, 0.80F, 0.85F};

    private static final int MAX_BOXES = 20000;

    private AreaSelectionRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!AreaSelectionState.isActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = new PoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        VertexConsumer consumer = minecraft.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        int drawn = 0;
        for (Map.Entry<UUID, List<Long>> entry : new ArrayList<>(AreaSelectionState.existing().entrySet())) {
            for (Long cell : new ArrayList<>(entry.getValue())) {
                if (drawn++ >= MAX_BOXES) {
                    break;
                }
                drawCell(poseStack, consumer, cell, EXISTING_COLOR);
            }
        }
        drawn = 0;
        for (Map.Entry<Long, Integer> entry : new ArrayList<>(AreaSelectionState.pending().entrySet())) {
            if (drawn++ >= MAX_BOXES) {
                break;
            }
            drawCell(poseStack, consumer, entry.getKey(), PENDING_COLOR);
        }
        minecraft.renderBuffers().bufferSource().endBatch(RenderType.lines());
        poseStack.popPose();
    }

    private static void drawCell(PoseStack poseStack, VertexConsumer consumer, Long packed, float[] color) {
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
