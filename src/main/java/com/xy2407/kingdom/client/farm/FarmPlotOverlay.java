package com.xy2407.kingdom.client.farm;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.farm.FarmRegion;
import com.xy2407.kingdom.common.network.farm.FarmPanelStatePacket;
import com.xy2407.kingdom.common.network.farm.FarmPanelStatePacket.Plot;

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
import org.joml.Matrix4f;

/**
 * 农田地表网格：框选期间画出正在框的地，并把已建成的农田一并圈出来。
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
public final class FarmPlotOverlay {

    private static final int GRID_CELL_LIMIT = 900;
    private static final float DRAFT_R = 0.55F;
    private static final float DRAFT_G = 0.95F;
    private static final float DRAFT_B = 0.55F;
    private static final float BUILT_R = 0.35F;
    private static final float BUILT_G = 0.75F;
    private static final float BUILT_B = 1.0F;
    private static final double LIFT = 0.03D;

    private static FarmPanelStatePacket lastState;

    private FarmPlotOverlay() {
    }

    public static void cache(FarmPanelStatePacket packet) {
        lastState = packet;
    }

    public static FarmPanelStatePacket lastState() {
        return lastState;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        boolean selecting = FarmAreaSelector.isActive();
        FarmPlotDraft draft = FarmPlotDraft.current();
        if (!selecting && draft == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        VertexConsumer consumer = minecraft.renderBuffers().bufferSource().getBuffer(RenderType.lines());

        if (selecting && lastState != null) {
            for (Plot plot : lastState.plots()) {
                if (!plot.hasArea()) {
                    continue;
                }
                if (hasCore(plot.minX(), plot.minZ(), plot.maxX(), plot.maxZ())) {
                    grid(poseStack, consumer, plot.minX(), plot.minZ(), plot.maxX(), plot.maxZ(),
                            plot.groundY(), BUILT_R, BUILT_G, BUILT_B);
                }
                for (FarmRegion region : plot.extra()) {
                    grid(poseStack, consumer, region.minX(), region.minZ(), region.maxX(), region.maxZ(),
                            plot.groundY(), BUILT_R, BUILT_G, BUILT_B);
                }
            }
        }
        if (selecting) {
            for (int[] block : FarmAreaSelector.blocks()) {
                grid(poseStack, consumer, block[0], block[1], block[2], block[3], block[4],
                        DRAFT_R, DRAFT_G, DRAFT_B);
            }
            int[] live = FarmAreaSelector.liveRect();
            if (live != null) {
                grid(poseStack, consumer, live[0], live[1], live[2], live[3], live[4],
                        DRAFT_R, DRAFT_G, DRAFT_B);
            }
        }
        if (draft != null) {
            if (hasCore(draft.minX(), draft.minZ(), draft.maxX(), draft.maxZ())) {
                grid(poseStack, consumer, draft.minX(), draft.minZ(), draft.maxX(), draft.maxZ(),
                        draft.groundY(), DRAFT_R, DRAFT_G, DRAFT_B);
            }
            for (FarmRegion region : draft.extra()) {
                grid(poseStack, consumer, region.minX(), region.minZ(), region.maxX(), region.maxZ(),
                        draft.groundY(), DRAFT_R, DRAFT_G, DRAFT_B);
            }
        }

        minecraft.renderBuffers().bufferSource().endBatch(RenderType.lines());
        poseStack.popPose();
    }

    private static boolean hasCore(int minX, int minZ, int maxX, int maxZ) {
        return maxX > minX || maxZ > minZ;
    }

    private static void grid(PoseStack poseStack, VertexConsumer consumer,
                             int minX, int minZ, int maxX, int maxZ, int groundY,
                             float red, float green, float blue) {
        double y = groundY + LIFT;
        double x0 = minX;
        double x1 = maxX + 1.0D;
        double z0 = minZ;
        double z1 = maxZ + 1.0D;
        LevelRenderer.renderLineBox(poseStack, consumer, new AABB(x0, y, z0, x1, y + 0.02D, z1),
                red, green, blue, 1.0F);
        if ((maxX - minX + 1) * (maxZ - minZ + 1) > GRID_CELL_LIMIT) {
            return;
        }
        for (int x = minX + 1; x <= maxX; x++) {
            line(poseStack, consumer, x, y, z0, x, y, z1, red, green, blue);
        }
        for (int z = minZ + 1; z <= maxZ; z++) {
            line(poseStack, consumer, x0, y, z, x1, y, z, red, green, blue);
        }
    }

    private static void line(PoseStack poseStack, VertexConsumer consumer,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             float red, float green, float blue) {
        Matrix4f matrix = poseStack.last().pose();
        float dx = (float) (x2 - x1);
        float dy = (float) (y2 - y1);
        float dz = (float) (z2 - z1);
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float nx = length > 0.0F ? dx / length : 0.0F;
        float ny = length > 0.0F ? dy / length : 0.0F;
        float nz = length > 0.0F ? dz / length : 0.0F;
        consumer.addVertex(matrix, (float) x1, (float) y1, (float) z1)
                .setColor(red, green, blue, 1.0F)
                .setNormal(nx, ny, nz);
        consumer.addVertex(matrix, (float) x2, (float) y2, (float) z2)
                .setColor(red, green, blue, 1.0F)
                .setNormal(nx, ny, nz);
    }
}
