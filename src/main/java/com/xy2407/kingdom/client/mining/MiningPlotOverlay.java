package com.xy2407.kingdom.client.mining;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.rts.RtsModeManager;
import com.xy2407.kingdom.common.network.mining.MiningPanelStatePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * RTS 下的矿坑范围叠层：把已有矿坑的矩形边界与每层轮廓画成线框。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class MiningPlotOverlay {

    private static final float[] RUNNING = {0.40F, 0.85F, 0.55F, 0.95F};

    private static final float[] PAUSED = {0.95F, 0.72F, 0.28F, 0.95F};

    private static final float[] DONE = {0.55F, 0.62F, 0.70F, 0.85F};

    private static List<MiningPanelStatePacket.Mine> mines = List.of();

    private static UUID activePlotId;

    private MiningPlotOverlay() {
    }

    public static void cache(MiningPanelStatePacket packet) {
        if (packet == null) {
            return;
        }
        mines = packet.mines();
    }

    public static void setActive(UUID plotId) {
        activePlotId = plotId;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!RtsModeManager.isActive() || mines.isEmpty()) {
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
        List<MiningPanelStatePacket.Mine> snapshot = new ArrayList<>(mines);
        for (MiningPanelStatePacket.Mine mine : snapshot) {
            float[] color = colorOf(mine);
            double minX = mine.minX();
            double minZ = mine.minZ();
            double maxX = mine.maxX() + 1.0D;
            double maxZ = mine.maxZ() + 1.0D;
            double top = mine.surfaceY() + 0.02D;
            boolean activePlot = activePlotId != null && activePlotId.equals(mine.id());
            AABB topBox = new AABB(minX, top, minZ, maxX, top, maxZ).inflate(activePlot ? 0.01D : 0.0D);
            LevelRenderer.renderLineBox(poseStack, consumer, topBox, color[0], color[1], color[2], color[3]);
            double bottom = mine.currentY() + 0.02D;
            AABB bottomBox = new AABB(minX, bottom, minZ, maxX, bottom, maxZ);
            LevelRenderer.renderLineBox(poseStack, consumer, bottomBox, color[0], color[1], color[2], color[3]);
            renderCorners(poseStack, consumer, minX, minZ, maxX, maxZ, bottom, top, color);
        }
        minecraft.renderBuffers().bufferSource().endBatch(RenderType.lines());
        poseStack.popPose();
    }

    private static void renderCorners(PoseStack poseStack, VertexConsumer consumer,
                                      double minX, double minZ, double maxX, double maxZ,
                                      double bottom, double top, float[] color) {
        renderPillar(poseStack, consumer, minX, minZ, bottom, top, color);
        renderPillar(poseStack, consumer, maxX, minZ, bottom, top, color);
        renderPillar(poseStack, consumer, minX, maxZ, bottom, top, color);
        renderPillar(poseStack, consumer, maxX, maxZ, bottom, top, color);
    }

    private static void renderPillar(PoseStack poseStack, VertexConsumer consumer,
                                     double x, double z, double bottom, double top, float[] color) {
        AABB box = new AABB(x, bottom, z, x, top, z).inflate(0.01D);
        LevelRenderer.renderLineBox(poseStack, consumer, box, color[0], color[1], color[2], color[3]);
    }

    private static float[] colorOf(MiningPanelStatePacket.Mine mine) {
        if ("RUNNING".equals(mine.status())) {
            return RUNNING;
        }
        if ("PAUSED".equals(mine.status())) {
            return PAUSED;
        }
        return DONE;
    }
}
