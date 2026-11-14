package com.xy2407.kingdom.client.wall;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.rts.RtsModeManager;

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

/**
 * 城墙选中高亮：按任务的全量方块逐个描边（不用整体包围箱），任意 RTS 模式下都可选中
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class WallHighlightRenderer {

    private WallHighlightRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!RtsModeManager.isActive() || !WallClientState.hasSelection()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderType.lines());
        int drawn = 0;
        for (long packed : WallClientState.selectedBlocks()) {
            BlockPos pos = BlockPos.of(packed);
            LevelRenderer.renderLineBox(pose, consumer,
                    new AABB(pos.getX() - 0.01D, pos.getY() - 0.01D, pos.getZ() - 0.01D,
                            pos.getX() + 1.01D, pos.getY() + 1.01D, pos.getZ() + 1.01D),
                    1.0F, 0.82F, 0.25F, 1.0F);
            drawn++;
            if (drawn >= 4096) {
                break;
            }
        }
        if (drawn == 0) {
            // 方块数据还没回来（或老存档无逐方块记录）：退回整体包围箱
            AABB box = WallClientState.selectedBounds();
            if (box != null) {
                LevelRenderer.renderLineBox(pose, consumer, box.inflate(0.02D),
                        1.0F, 0.82F, 0.25F, 1.0F);
            }
        }
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }
}
