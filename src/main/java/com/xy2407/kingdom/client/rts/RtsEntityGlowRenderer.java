package com.xy2407.kingdom.client.rts;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xy2407.kingdom.common.entity.RtsFakePlayerEntity;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.*;

/**
 * 为选中单位绘制线框轮廓，并在右键移动目标处绘制旗帜标记与连线
 */
@OnlyIn(Dist.CLIENT)
public final class RtsEntityGlowRenderer {

    private static final float MARK_R = 1.0F;
    private static final float MARK_G = 0.85F;
    private static final float MARK_B = 0.0F;
    private static final float LINE_R = 0.3F;
    private static final float LINE_G = 0.8F;
    private static final float LINE_B = 1.0F;
    private static final float SELECTED_PLAYER_R = 0.3F;
    private static final float SELECTED_PLAYER_G = 0.6F;
    private static final float SELECTED_PLAYER_B = 1.0F;
    private static final float SELECTED_NPC_R = 0.0F;
    private static final float SELECTED_NPC_G = 1.0F;
    private static final float SELECTED_NPC_B = 0.0F;
    private static final float ATTACK_TARGET_R = 1.0F;
    private static final float ATTACK_TARGET_G = 0.25F;
    private static final float ATTACK_TARGET_B = 0.2F;
    private static final double MARK_HALF = 0.35D;
    private static final double MARK_PILLAR = 1.5D;
    private static final double SEARCH_RADIUS = 200.0D;

    private RtsEntityGlowRenderer() {
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || !RtsModeManager.isActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        RtsModeManager.setCachedProjectionMatrix(new Matrix4f(RenderSystem.getProjectionMatrix()));
        RtsModeManager.setCachedModelViewMatrix(new Matrix4f(RenderSystem.getModelViewMatrix()));

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        VertexConsumer consumer = minecraft.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        Set<UUID> selected = RtsModeManager.getSelectedEntities();
        Set<UUID> attackTargets = RtsAttackTargetState.ids();
        if (!selected.isEmpty() || !attackTargets.isEmpty()) {
            Frustum frustum = event.getFrustum();
            if (!attackTargets.isEmpty()) {
                drawAttackTargetBoxes(poseStack, consumer, minecraft, camPos, partialTick, attackTargets, frustum);
            }
            if (!selected.isEmpty()) {
                drawSelectedBoxes(poseStack, consumer, minecraft, camPos, partialTick, selected, frustum);
                drawMoveTargets(poseStack, consumer, minecraft, partialTick, frustum);
            }
        }
        minecraft.renderBuffers().bufferSource().endBatch(RenderType.lines());
        minecraft.renderBuffers().bufferSource().endLastBatch();
        poseStack.popPose();
    }

    private static void drawSelectedBoxes(PoseStack poseStack, VertexConsumer consumer, Minecraft minecraft,
                                          Vec3 camPos, float partialTick, Set<UUID> selected, Frustum frustum) {
        AABB searchBox = new AABB(
                camPos.x - SEARCH_RADIUS, camPos.y - SEARCH_RADIUS, camPos.z - SEARCH_RADIUS,
                camPos.x + SEARCH_RADIUS, camPos.y + SEARCH_RADIUS, camPos.z + SEARCH_RADIUS);
        for (Entity entity : minecraft.level.getEntities(minecraft.player, searchBox)) {
            if (!selected.contains(entity.getUUID()) || !entity.isAlive()) {
                continue;
            }
            renderBox(poseStack, consumer, entity, partialTick, frustum);
        }
        if (selected.contains(minecraft.player.getUUID()) && minecraft.player.isAlive()) {
            renderBox(poseStack, consumer, minecraft.player, partialTick, frustum);
        }
    }

    private static void drawAttackTargetBoxes(PoseStack poseStack, VertexConsumer consumer, Minecraft minecraft,
                                              Vec3 camPos, float partialTick, Set<UUID> targets, Frustum frustum) {
        AABB searchBox = new AABB(
                camPos.x - SEARCH_RADIUS, camPos.y - SEARCH_RADIUS, camPos.z - SEARCH_RADIUS,
                camPos.x + SEARCH_RADIUS, camPos.y + SEARCH_RADIUS, camPos.z + SEARCH_RADIUS);
        for (Entity entity : minecraft.level.getEntities(minecraft.player, searchBox)) {
            if (!targets.contains(entity.getUUID()) || !entity.isAlive()) {
                continue;
            }
            Vec3 pos = interpolatedPos(entity, partialTick);
            AABB box = entity.getBoundingBox()
                    .move(pos.x - entity.getX(), pos.y - entity.getY(), pos.z - entity.getZ())
                    .inflate(0.08D);
            if (!frustum.isVisible(box)) {
                continue;
            }
            LevelRenderer.renderLineBox(poseStack, consumer, box,
                    ATTACK_TARGET_R, ATTACK_TARGET_G, ATTACK_TARGET_B, 1.0F);
        }
    }

    private static void drawMoveTargets(PoseStack poseStack, VertexConsumer consumer,
                                        Minecraft minecraft, float partialTick, Frustum frustum) {
        Map<UUID, Vec3> moveTargets = RtsModeManager.getMoveTargets();
        if (moveTargets.isEmpty()) {
            return;
        }
        Map<UUID, Entity> index = new HashMap<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            index.put(entity.getUUID(), entity);
        }
        Set<Vec3> drawnMarkers = new HashSet<>();
        for (Vec3 target : moveTargets.values()) {
            if (!drawnMarkers.add(target)) {
                continue;
            }
            renderMoveTarget(poseStack, consumer, target, target.y + 0.05D, frustum);
        }
        for (Map.Entry<UUID, Vec3> entry : moveTargets.entrySet()) {
            Entity entity = index.get(entry.getKey());
            if (entity == null || !entity.isAlive()) {
                continue;
            }
            Vec3 target = entry.getValue();
            Vec3 markTop = new Vec3(target.x, target.y + 0.05D + MARK_PILLAR, target.z);
            Vec3 from = interpolatedPos(entity, partialTick);
            if (frustum.isVisible(new AABB(from, markTop).inflate(1.0D))) {
                renderLine(poseStack, consumer, from, markTop);
            }
        }
    }

    private static void renderBox(PoseStack poseStack, VertexConsumer consumer, Entity entity,
                                  float partialTick, Frustum frustum) {
        Vec3 pos = interpolatedPos(entity, partialTick);
        AABB box = entity.getBoundingBox()
                .move(pos.x - entity.getX(), pos.y - entity.getY(), pos.z - entity.getZ())
                .inflate(0.05D);
        if (!frustum.isVisible(box)) {
            return;
        }
        if (entity instanceof Player || entity instanceof RtsFakePlayerEntity) {
            LevelRenderer.renderLineBox(poseStack, consumer, box,
                    SELECTED_PLAYER_R, SELECTED_PLAYER_G, SELECTED_PLAYER_B, 1.0F);
        } else {
            LevelRenderer.renderLineBox(poseStack, consumer, box,
                    SELECTED_NPC_R, SELECTED_NPC_G, SELECTED_NPC_B, 1.0F);
        }
    }

    private static void renderMoveTarget(PoseStack poseStack, VertexConsumer consumer, Vec3 target,
                                         double markY, Frustum frustum) {
        AABB markBox = new AABB(
                target.x - MARK_HALF, markY - 0.05D, target.z - MARK_HALF,
                target.x + MARK_HALF, markY + MARK_PILLAR, target.z + MARK_HALF);
        if (!frustum.isVisible(markBox)) {
            return;
        }
        AABB baseBox = new AABB(
                target.x - MARK_HALF, markY - 0.05D, target.z - MARK_HALF,
                target.x + MARK_HALF, markY + 0.05D, target.z + MARK_HALF);
        LevelRenderer.renderLineBox(poseStack, consumer, baseBox, MARK_R, MARK_G, MARK_B, 1.0F);
        AABB pillarBox = new AABB(
                target.x - 0.06D, markY, target.z - 0.06D,
                target.x + 0.06D, markY + MARK_PILLAR, target.z + 0.06D);
        LevelRenderer.renderLineBox(poseStack, consumer, pillarBox, MARK_R, MARK_G, MARK_B, 1.0F);
        AABB flagBox = new AABB(
                target.x - MARK_HALF, markY + MARK_PILLAR - 0.1D, target.z - 0.06D,
                target.x + MARK_HALF, markY + MARK_PILLAR, target.z + 0.06D);
        LevelRenderer.renderLineBox(poseStack, consumer, flagBox, MARK_R, MARK_G, MARK_B, 1.0F);
    }

    private static void renderLine(PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to) {
        Matrix4f matrix = poseStack.last().pose();
        float dx = (float) (to.x - from.x);
        float dy = (float) (to.y - from.y);
        float dz = (float) (to.z - from.z);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float nx = len > 0.0F ? dx / len : 0.0F;
        float ny = len > 0.0F ? dy / len : 0.0F;
        float nz = len > 0.0F ? dz / len : 0.0F;
        consumer.addVertex(matrix, (float) from.x, (float) from.y, (float) from.z)
                .setColor(LINE_R, LINE_G, LINE_B, 1.0F)
                .setNormal(nx, ny, nz);
        consumer.addVertex(matrix, (float) to.x, (float) to.y, (float) to.z)
                .setColor(LINE_R, LINE_G, LINE_B, 1.0F)
                .setNormal(nx, ny, nz);
    }

    private static Vec3 interpolatedPos(Entity entity, float partialTick) {
        return new Vec3(
                Mth.lerp(partialTick, entity.xo, entity.getX()),
                Mth.lerp(partialTick, entity.yo, entity.getY()),
                Mth.lerp(partialTick, entity.zo, entity.getZ())
        );
    }
}
