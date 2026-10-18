package com.xy2407.kingdom.client.rts;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.map.MapOverlayStore;

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
    private static final float[] HOVER_CLEAR = {0.95F, 0.30F, 0.25F, 0.90F};
    private static final float[] EDGE_CLEAR = {0.95F, 0.30F, 0.25F, 0.85F};
    private static final float[] FILL_CLEAR = {0.85F, 0.22F, 0.18F, 0.30F};
    private static final float[] EXISTS_FILL = {0.55F, 0.75F, 0.95F, 0.22F};
    private static final float[] EXISTS_EDGE = {0.60F, 0.80F, 1.00F, 0.75F};

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
        // 已铺道路高亮（叠层包内即本人道路区块）：表面淡蓝 + 外轮廓，便于对照调整
        for (MapOverlayStore.RoadView road : MapOverlayStore.roads()) {
            VertexConsumer fill = buffers.getBuffer(RenderType.debugFilledBox());
            for (long cell : road.cells()) {
                BlockPos pos = BlockPos.of(cell);
                addTopFace(fill, matrix,
                        (float) (pos.getX() - camera.x),
                        (float) (pos.getY() - camera.y) + 1.02F,
                        (float) (pos.getZ() - camera.z), EXISTS_FILL);
            }
            buffers.endBatch(RenderType.debugFilledBox());
            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            for (long cell : road.cells()) {
                BlockPos pos = BlockPos.of(cell);
                float x = (float) (pos.getX() - camera.x);
                float z = (float) (pos.getZ() - camera.z);
                float top = (float) (pos.getY() - camera.y) + 1.02F;
                LevelRenderer.renderLineBox(poseStack, lines,
                        new AABB(x, top - 1.0D, z, x + 1.0D, top, z + 1.0D),
                        EXISTS_EDGE[0], EXISTS_EDGE[1], EXISTS_EDGE[2], EXISTS_EDGE[3]);
            }
            buffers.endBatch(RenderType.lines());
        }

        Map<Long, Integer> pending = RtsRoadState.pending();
        boolean clearMode = RtsRoadState.mode() == RtsRoadState.Mode.CLEAR;
        float[] fill = clearMode ? FILL_CLEAR : FILL;
        float[] edge = clearMode ? EDGE_CLEAR : EDGE;
        if (!pending.isEmpty()) {
            VertexConsumer fillConsumer = buffers.getBuffer(RenderType.debugFilledBox());
            for (long key : pending.keySet()) {
                BlockPos pos = BlockPos.of(key);
                addTopFace(fillConsumer, matrix,
                        (float) (pos.getX() - camera.x),
                        (float) (pos.getY() - camera.y) + 1.0F,
                        (float) (pos.getZ() - camera.z), fill);
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
                        edge[0], edge[1], edge[2], edge[3]);
            }
            buffers.endBatch(RenderType.lines());
        }

        BlockPos hover = RtsRoadState.hoverCell();
        if (hover != null) {
            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            double x = hover.getX() - camera.x;
            double y = hover.getY() - camera.y;
            double z = hover.getZ() - camera.z;
            float[] hoverColor = clearMode ? HOVER_CLEAR : HOVER;
            LevelRenderer.renderLineBox(poseStack, lines,
                    new AABB(x - 0.002D, y - 0.002D, z - 0.002D, x + 1.002D, y + 1.002D, z + 1.002D),
                    hoverColor[0], hoverColor[1], hoverColor[2], hoverColor[3]);
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
