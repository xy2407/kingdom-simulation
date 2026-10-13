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

import java.util.Map;

/**
 * 领地道路的世界层渲染：准星命中格的描边与待铺格的半透明顶面加外轮廓边框。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class RoadTerritoryRenderer {

    private static final float[] HOVER = {0.95F, 0.86F, 0.48F, 0.90F};
    private static final float[] EDGE = {0.92F, 0.80F, 0.42F, 0.85F};
    private static final float[] FILL = {0.78F, 0.67F, 0.48F, 0.30F};

    private RoadTerritoryRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!RtsModeManager.isActive() || RtsModeManager.getSubMode() != RtsSubMode.ROAD_TERRITORY) {
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

        // BufferSource 是立即模式：getBuffer(新类型) 会 endBatch 上一个类型，
        // 所以 fill 与 lines 必须分两遍循环画，不能交叉持有两个 consumer 同时写（§开发规则 渲染）
        Map<Long, Integer> pending = RtsRoadState.pending();
        if (!pending.isEmpty()) {
            VertexConsumer fill = buffers.getBuffer(RenderType.debugFilledBox());
            for (long key : pending.keySet()) {
                BlockPos pos = BlockPos.of(key);
                addTopFace(fill, matrix,
                        (float) (pos.getX() - camera.x),
                        (float) (pos.getY() - camera.y) + 1.0F,
                        (float) (pos.getZ() - camera.z), FILL);
            }
            buffers.endBatch(RenderType.debugFilledBox());

            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            for (long key : pending.keySet()) {
                BlockPos pos = BlockPos.of(key);
                if (!isEdge(pending, pos)) {
                    continue;
                }
                float x = (float) (pos.getX() - camera.x);
                float z = (float) (pos.getZ() - camera.z);
                float top = (float) (pos.getY() - camera.y) + 1.0F;
                LevelRenderer.renderLineBox(poseStack, lines,
                        new AABB(x, top - 1.0D, z, x + 1.0D, top, z + 1.0D),
                        EDGE[0], EDGE[1], EDGE[2], EDGE[3]);
            }
            buffers.endBatch(RenderType.lines());
        }

        BlockPos hover = RtsRoadState.hoverCell();
        if (hover != null) {
            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            double x = hover.getX() - camera.x;
            double y = hover.getY() - camera.y;
            double z = hover.getZ() - camera.z;
            LevelRenderer.renderLineBox(poseStack, lines,
                    new AABB(x - 0.002D, y - 0.002D, z - 0.002D, x + 1.002D, y + 1.002D, z + 1.002D),
                    HOVER[0], HOVER[1], HOVER[2], HOVER[3]);
            buffers.endBatch(RenderType.lines());
        }
    }

    private static void addTopFace(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z, float[] color) {
        consumer.addVertex(matrix, x, y, z).setColor(color[0], color[1], color[2], color[3]);
        consumer.addVertex(matrix, x, y, z + 1.0F).setColor(color[0], color[1], color[2], color[3]);
        consumer.addVertex(matrix, x + 1.0F, y, z).setColor(color[0], color[1], color[2], color[3]);
        consumer.addVertex(matrix, x + 1.0F, y, z + 1.0F).setColor(color[0], color[1], color[2], color[3]);
    }

    private static boolean isEdge(Map<Long, Integer> pending, BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        return !pending.containsKey(BlockPos.asLong(x + 1, y, z))
                || !pending.containsKey(BlockPos.asLong(x - 1, y, z))
                || !pending.containsKey(BlockPos.asLong(x, y, z + 1))
                || !pending.containsKey(BlockPos.asLong(x, y, z - 1));
    }
}
