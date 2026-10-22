package com.xy2407.kingdom.client.mining;

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
 * 采矿笔刷的世界层预览：把已刷的每一格最高方块描成线框。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class MiningBrushRenderer {

    private static final float[] EDGE = {0.95F, 0.72F, 0.28F, 1.0F};

    private MiningBrushRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!MiningBrushSelector.isActive() || MiningBrushSelector.count() == 0) {
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
        for (long key : MiningBrushSelector.pending().keySet()) {
            BlockPos pos = BlockPos.of(key);
            AABB box = new AABB(pos.getX(), pos.getY() - 1.0D, pos.getZ(),
                    pos.getX() + 1.0D, pos.getY(), pos.getZ() + 1.0D).inflate(0.002D);
            LevelRenderer.renderLineBox(poseStack, consumer, box,
                    EDGE[0], EDGE[1], EDGE[2], EDGE[3]);
        }
        minecraft.renderBuffers().bufferSource().endBatch(RenderType.lines());
        poseStack.popPose();
    }
}
