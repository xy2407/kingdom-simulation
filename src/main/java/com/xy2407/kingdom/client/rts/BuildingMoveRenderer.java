package com.xy2407.kingdom.client.rts;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.structure.StructureMesh;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * RTS 建筑线框与迁移投影渲染：建筑淡金框与迁移落点方块投影
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class BuildingMoveRenderer {

    private static final float[] IDLE = {0.86F, 0.72F, 0.35F, 0.45F};
    private static final float[] GHOST = {0.25F, 0.90F, 0.30F, 1.00F};

    private BuildingMoveRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!RtsModeManager.isActive()) {
            return;
        }
        if (ClientBuildingController.isMoving()) {
            BlockPos origin = ClientBuildingController.origin();
            Vec3i size = ClientBuildingController.size();
            drawMesh(event, ClientBuildingController.mesh(), origin, size);
            drawBox(event, origin, size, GHOST);
            return;
        }
        for (ClientBuildingStore.Entry entry : ClientBuildingStore.entries()) {
            drawBox(event, entry.min(), entry.size(), IDLE);
        }
    }

    private static void drawMesh(RenderLevelStageEvent event, StructureMesh mesh, BlockPos origin, Vec3i size) {
        if (mesh == null || mesh.isEmpty() || origin == null
                || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            return;
        }
        mesh.draw(worldView(event, origin, size), event.getProjectionMatrix(), 1.0F);
    }

    private static void drawBox(RenderLevelStageEvent event, BlockPos min, Vec3i size, float[] color) {
        if (min == null || size == null || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            return;
        }
        Matrix4f view = worldView(event, min, size);
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        AABB box = new AABB(
                -size.getX() / 2.0, -size.getY() / 2.0, -size.getZ() / 2.0,
                size.getX() / 2.0, size.getY() / 2.0, size.getZ() / 2.0);
        LevelRenderer.renderLineBox(new PoseStack(), consumer, box, color[0], color[1], color[2], color[3]);
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.set(view);
        RenderSystem.applyModelViewMatrix();
        bufferSource.endBatch(RenderType.lines());
        modelViewStack.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    private static Matrix4f worldView(RenderLevelStageEvent event, BlockPos origin, Vec3i size) {
        float cx = origin.getX() + size.getX() / 2.0F;
        float cy = origin.getY() + size.getY() / 2.0F;
        float cz = origin.getZ() + size.getZ() / 2.0F;
        Vec3 camPos = event.getCamera().getPosition();
        Matrix4f modelView = event.getModelViewMatrix();
        Matrix4f view = new Matrix4f(modelView != null ? modelView : event.getPoseStack().last().pose());
        view.translate(cx - (float) camPos.x, cy - (float) camPos.y, cz - (float) camPos.z);
        return view;
    }
}
