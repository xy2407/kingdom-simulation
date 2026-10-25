package com.xy2407.kingdom.client.path;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xy2407.kingdom.common.network.debug.NpcPathDebugPacket;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 把收到的 NPC 寻路航点画成高亮线框，按动作类型区分颜色
 */
@OnlyIn(Dist.CLIENT)
public final class NpcPathDebugRenderer {

    private static final double BOX_INFLATE = 0.02D;

    private static final float[] WALK = {1.0F, 0.35F, 0.35F};
    private static final float[] STEP_UP = {1.0F, 0.85F, 0.2F};
    private static final float[] DROP = {0.4F, 0.6F, 1.0F};
    private static final float[] CLIMB = {0.2F, 1.0F, 0.6F};
    private static final float[] BLOCKED = {1.0F, 0.15F, 0.85F};

    private static final List<Trace> TRACES = new ArrayList<>();

    private NpcPathDebugRenderer() {
    }

    public static void accept(NpcPathDebugPacket packet) {
        UUID npcId = packet.npcId();
        TRACES.removeIf(trace -> trace.npcId().equals(npcId));
        if (packet.entries().isEmpty()) {
            return;
        }
        List<NpcPathDebugPacket.Entry> entries = packet.entries();
        BlockPos target = entries.get(entries.size() - 1).pos();
        TRACES.add(new Trace(npcId, entries, target));
    }

    public static void clear() {
        TRACES.clear();
    }

    public static void onRenderLevel(PoseStack poseStack, Camera camera) {
        if (TRACES.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        TRACES.removeIf(trace -> {
            Entity entity = findEntity(minecraft.level, trace.npcId());
            if (entity == null || !entity.isAlive()) {
                return true;
            }
            return arrived(entity, trace.target());
        });
        if (TRACES.isEmpty()) {
            return;
        }
        Vec3 camPos = camera.getPosition();
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        VertexConsumer consumer = minecraft.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        for (Trace trace : TRACES) {
            for (NpcPathDebugPacket.Entry entry : trace.entries()) {
                BlockPos pos = entry.pos();
                AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                        pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D).inflate(BOX_INFLATE);
                float[] color = colorOf(entry.move());
                LevelRenderer.renderLineBox(poseStack, consumer, box, color[0], color[1], color[2], 1.0F);
            }
        }
        minecraft.renderBuffers().bufferSource().endBatch(RenderType.lines());
        poseStack.popPose();
    }

    private static boolean arrived(Entity entity, BlockPos target) {
        if (entity == null || target == null) {
            return false;
        }
        double dx = entity.getX() - (target.getX() + 0.5D);
        double dy = entity.getY() - target.getY();
        double dz = entity.getZ() - (target.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz <= 0.7D * 0.7D;
    }

    private static Entity findEntity(net.minecraft.client.multiplayer.ClientLevel level, UUID id) {
        if (level == null || id == null) {
            return null;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (id.equals(entity.getUUID())) {
                return entity;
            }
        }
        return null;
    }

    private static float[] colorOf(byte move) {
        return switch (move) {
            case 1 -> STEP_UP;
            case 2 -> DROP;
            case 3 -> CLIMB;
            case 4 -> BLOCKED;
            default -> WALK;
        };
    }

    private record Trace(UUID npcId, List<NpcPathDebugPacket.Entry> entries, BlockPos target) {
    }
}
