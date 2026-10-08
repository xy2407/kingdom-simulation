package com.xy2407.kingdom.client.building;

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
 * 世界内建筑投影渲染：绘制跟随鼠标的幽灵几何、已暂存蓝影与施工中虚影。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class BuildingPlacementRenderer {

    private BuildingPlacementRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        StructureMesh activeMesh = BuildingPlacementManager.mesh();
        BlockPos activeOrigin = BuildingPlacementManager.origin();
        if (activeMesh != null && !activeMesh.isEmpty() && activeOrigin != null) {
            Vec3i size = BuildingPlacementManager.size();
            Matrix4f view = worldView(event, activeOrigin, size);
            activeMesh.draw(view, event.getProjectionMatrix(), BuildingPlacementManager.GHOST_ALPHA);
            drawFootprintBox(view, size);
        }
        for (BuildingPlacementManager.Pending pending : BuildingPlacementManager.pendings()) {
            StructureMesh pendingMesh = pending.mesh();
            if (pendingMesh == null || pendingMesh.isEmpty()) {
                continue;
            }
            Matrix4f view = worldView(event, pending.origin(), pending.size());
            pendingMesh.draw(view, event.getProjectionMatrix(), BuildingPlacementManager.PENDING_ALPHA);
            drawFootprintBox(view, pending.size());
        }
        for (BuildingSiteManager.Site site : BuildingSiteManager.sites()) {
            StructureMesh siteMesh = site.mesh();
            if (siteMesh == null || siteMesh.isEmpty()) {
                continue;
            }
            Matrix4f view = worldView(event, site.origin(), site.size());
            siteMesh.draw(view, event.getProjectionMatrix(), BuildingSiteManager.SITE_ALPHA);
            drawFootprintBox(view, site.size());
        }
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

    private static void drawFootprintBox(Matrix4f view, Vec3i size) {
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        AABB box = new AABB(
                -size.getX() / 2.0, -size.getY() / 2.0, -size.getZ() / 2.0,
                size.getX() / 2.0, size.getY() / 2.0, size.getZ() / 2.0);
        LevelRenderer.renderLineBox(new PoseStack(), consumer, box, 0.2F, 0.9F, 1.0F, 1.0F);
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.set(view);
        RenderSystem.applyModelViewMatrix();
        bufferSource.endBatch(RenderType.lines());
        modelViewStack.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }
}
