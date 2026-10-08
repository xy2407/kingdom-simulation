package com.xy2407.kingdom.client.rts;

import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.entity.RtsFakePlayerEntity;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * RTS 拾取：点选框选单位、世界射线解算与屏幕世界坐标互转。
 */
@OnlyIn(Dist.CLIENT)
public final class RtsPicker {

    private static final double PICK_MAX_DISTANCE = 400.0D;
    private static final double BOX_SEARCH_RADIUS = 200.0D;

    private RtsPicker() {
    }

    public static void clickSelect(double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Entity hit = pickEntityAtScreen(mouseX, mouseY);
        if (hit != null && isSelectable(hit)) {
            Set<UUID> ids = RtsModeManager.isCtrlHeld()
                    ? new HashSet<>(RtsModeManager.getSelectedEntities())
                    : new HashSet<>();
            ids.add(hit.getUUID());
            RtsModeManager.setSelectedEntities(ids);
            return;
        }
        RtsModeManager.clearSelection();
    }

    public static void boxSelect(double x1, double y1, double x2, double y2) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();

        Vec3 forward = getForward(camera);
        Vec3 up = getUp(camera);
        Vec3 right = forward.cross(up).normalize();
        double tanHalfFov = getTanHalfFov(minecraft);
        double aspect = (double) width / height;

        double minX = Math.min(x1, x2);
        double maxX = Math.max(x1, x2);
        double minY = Math.min(y1, y2);
        double maxY = Math.max(y1, y2);

        AABB searchBox = new AABB(
                camPos.x - BOX_SEARCH_RADIUS, camPos.y - BOX_SEARCH_RADIUS, camPos.z - BOX_SEARCH_RADIUS,
                camPos.x + BOX_SEARCH_RADIUS, camPos.y + BOX_SEARCH_RADIUS, camPos.z + BOX_SEARCH_RADIUS);

        Set<UUID> selected = RtsModeManager.isCtrlHeld()
                ? new HashSet<>(RtsModeManager.getSelectedEntities())
                : new HashSet<>();
        for (Entity entity : minecraft.level.getEntities(minecraft.player, searchBox)) {
            if (!isSelectable(entity) || !entity.isAlive()) {
                continue;
            }
            Vec3 center = entity.getBoundingBox().getCenter();
            double[] screen = worldToScreen(center, camPos, forward, right, up, tanHalfFov, aspect, width, height);
            if (screen == null) {
                continue;
            }
            if (screen[0] < minX || screen[0] > maxX || screen[1] < minY || screen[1] > maxY) {
                continue;
            }
            if (isOccludedByBlocks(minecraft.level, camPos, entity, minecraft.player)) {
                continue;
            }
            selected.add(entity.getUUID());
        }
        RtsModeManager.setSelectedEntities(selected);
    }

    public static boolean isSelectable(Entity entity) {
        return entity instanceof NpcEntity || entity instanceof RtsFakePlayerEntity;
    }

    public static Vec3 pickTargetBlock(double mouseX, double mouseY) {
        BlockHit hit = pickBlockHit(mouseX, mouseY);
        return hit == null ? null : hit.standPos;
    }

    public record BlockHit(BlockPos blockPos, Direction direction, Vec3 standPos, Vec3 location) {
    }

    public static BlockHit pickBlockHit(double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return null;
        }
        Vec3[] ray = screenToWorldRay(minecraft, mouseX, mouseY);
        if (ray == null) {
            return null;
        }
        Vec3 origin = ray[0];
        Vec3 end = ray[1];

        BlockHitResult blockHit = minecraft.level.clip(new ClipContext(
                origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
        if (blockHit.getType() == HitResult.Type.BLOCK) {
            Direction face = blockHit.getDirection();
            Vec3 loc = blockHit.getLocation();
            Vec3 adjusted = loc.subtract(face.getStepX() * 0.001D, face.getStepY() * 0.001D, face.getStepZ() * 0.001D);
            BlockPos hitPos = BlockPos.containing(adjusted);
            Vec3 standPos = face.getAxis() == Direction.Axis.Y
                    ? new Vec3(hitPos.getX() + 0.5D, loc.y, hitPos.getZ() + 0.5D)
                    : resolveStandTarget(minecraft, hitPos, face);
            return new BlockHit(hitPos, face, standPos, loc);
        }

        Vec3 rayDir = end.subtract(origin).normalize();
        if (Math.abs(rayDir.y) < 1.0E-6D) {
            return null;
        }
        double t = (minecraft.player.getY() - origin.y) / rayDir.y;
        if (t <= 0.0D || t > PICK_MAX_DISTANCE) {
            return null;
        }
        Vec3 proj = origin.add(rayDir.scale(t));
        BlockPos ground = BlockPos.containing(proj);
        Vec3 stand = new Vec3(ground.getX() + 0.5D, ground.getY() + 1.0D, ground.getZ() + 0.5D);
        return new BlockHit(ground, Direction.UP, stand, stand);
    }

    private static Vec3 resolveStandTarget(Minecraft minecraft, BlockPos hitPos, Direction face) {
        if (face.getAxis().isHorizontal()) {
            BlockPos side = hitPos.relative(face);
            int minY = Math.max(minecraft.level.getMinBuildHeight() + 1, side.getY() - 16);
            for (int y = side.getY(); y > minY; y--) {
                if (isSolidAt(minecraft, side.getX(), y, side.getZ())
                        && isAirAt(minecraft, side.getX(), y + 1, side.getZ())) {
                    return new Vec3(side.getX() + 0.5D, y + 1.0D, side.getZ() + 0.5D);
                }
            }
            return new Vec3(side.getX() + 0.5D, side.getY(), side.getZ() + 0.5D);
        }
        return new Vec3(hitPos.getX() + 0.5D, hitPos.getY() + 1.0D, hitPos.getZ() + 0.5D);
    }

    private static boolean isSolidAt(Minecraft minecraft, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return minecraft.level.getBlockState(pos).isSolidRender(minecraft.level, pos);
    }

    private static boolean isAirAt(Minecraft minecraft, int x, int y, int z) {
        return minecraft.level.getBlockState(new BlockPos(x, y, z)).isAir();
    }

    public static Entity pickEntityAtScreen(double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return null;
        }
        Vec3[] ray = screenToWorldRay(minecraft, mouseX, mouseY);
        if (ray == null) {
            return null;
        }
        Vec3 origin = ray[0];
        Vec3 end = ray[1];
        AABB searchBox = new AABB(origin, end).inflate(1.0D);

        Entity best = null;
        double bestSqr = PICK_MAX_DISTANCE * PICK_MAX_DISTANCE;
        for (Entity entity : minecraft.level.getEntities(minecraft.player, searchBox)) {
            if (!(entity instanceof LivingEntity) || !entity.isAlive()) {
                continue;
            }
            if (entity instanceof Player) {
                continue;
            }
            Optional<Vec3> hitPoint = entity.getBoundingBox().clip(origin, end);
            if (hitPoint.isEmpty()) {
                continue;
            }
            double distance = origin.distanceToSqr(hitPoint.get());
            if (distance < bestSqr) {
                bestSqr = distance;
                best = entity;
            }
        }
        return best;
    }

    public static Vec3[] screenToWorldRay(Minecraft minecraft, double mouseX, double mouseY) {
        if (minecraft.level == null) {
            return null;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        Vec3 forward = getForward(camera);
        Vec3 up = getUp(camera);
        Vec3 right = forward.cross(up).normalize();

        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        double ndcX = 2.0D * mouseX / width - 1.0D;
        double ndcY = 1.0D - 2.0D * mouseY / height;

        if (RtsModeManager.isOrthoEnabled()) {
            double halfWidth = RtsModeManager.getOrthoHalfWidth();
            if (halfWidth <= 0.0D) {
                return null;
            }
            double halfHeight = halfWidth * height / width;
            Vec3 origin = camPos.add(right.scale(ndcX * halfWidth)).add(up.scale(ndcY * halfHeight));
            return new Vec3[]{origin, origin.add(forward.scale(PICK_MAX_DISTANCE))};
        }

        Matrix4f projection = RtsModeManager.getCachedProjectionMatrix();
        if (projection != null) {
            float m11 = projection.m11();
            float m00 = projection.m00();
            if (Math.abs(m11) > 1.0E-6F && Math.abs(m00) > 1.0E-6F) {
                double tanHalfFov = 1.0D / m11;
                double aspect = m11 / m00;
                if (tanHalfFov > 0.1D && tanHalfFov < 20.0D && aspect > 0.1D && aspect < 20.0D) {
                    return ray(camPos, forward, right, up, ndcX, ndcY, tanHalfFov, aspect);
                }
            }
        }
        return ray(camPos, forward, right, up, ndcX, ndcY, getTanHalfFov(minecraft), (double) width / height);
    }

    private static Vec3[] ray(Vec3 camPos, Vec3 forward, Vec3 right, Vec3 up,
                              double ndcX, double ndcY, double tanHalfFov, double aspect) {
        Vec3 dir = forward
                .add(right.scale(ndcX * tanHalfFov * aspect))
                .add(up.scale(ndcY * tanHalfFov))
                .normalize();
        return new Vec3[]{camPos, camPos.add(dir.scale(PICK_MAX_DISTANCE))};
    }

    public static double[] projectToScreen(Vec3 worldPos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        Vec3 forward = getForward(camera);
        Vec3 up = getUp(camera);
        Vec3 right = forward.cross(up).normalize();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        return worldToScreen(worldPos, camPos, forward, right, up,
                getTanHalfFov(minecraft), (double) width / height, width, height);
    }

    private static double[] worldToScreen(Vec3 worldPos, Vec3 camPos,
                                          Vec3 forward, Vec3 right, Vec3 up,
                                          double tanHalfFov, double aspect,
                                          int width, int height) {
        Vec3 toPoint = worldPos.subtract(camPos);
        double forwardDist = toPoint.dot(forward);
        double rightDist = toPoint.dot(right);
        double upDist = toPoint.dot(up);

        if (RtsModeManager.isOrthoEnabled()) {
            double halfWidth = RtsModeManager.getOrthoHalfWidth();
            if (halfWidth <= 0.0D) {
                return null;
            }
            double halfHeight = halfWidth * height / width;
            double ndcX = rightDist / halfWidth;
            double ndcY = upDist / halfHeight;
            return new double[]{(ndcX + 1.0D) * 0.5D * width, (1.0D - (ndcY + 1.0D) * 0.5D) * height};
        }

        if (forwardDist <= 0.0D) {
            return null;
        }
        double ndcX = rightDist / (forwardDist * tanHalfFov * aspect);
        double ndcY = upDist / (forwardDist * tanHalfFov);
        return new double[]{(ndcX + 1.0D) * 0.5D * width, (1.0D - (ndcY + 1.0D) * 0.5D) * height};
    }

    private static boolean isOccludedByBlocks(Level level, Vec3 camPos, Entity entity, Entity context) {
        Vec3 to = entity.getBoundingBox().getCenter();
        double distance = camPos.distanceTo(to);
        if (distance < 1.0E-4D) {
            return false;
        }
        BlockHitResult hit = level.clip(new ClipContext(
                camPos, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, context));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getLocation().distanceTo(camPos) < distance - 0.05D;
    }

    private static Vec3 getForward(Camera camera) {
        Quaternionf rotation = new Quaternionf(camera.rotation());
        Vector3f vector = new Vector3f(0.0F, 0.0F, -1.0F).rotate(rotation);
        return new Vec3(vector.x, vector.y, vector.z);
    }

    private static Vec3 getUp(Camera camera) {
        Quaternionf rotation = new Quaternionf(camera.rotation());
        Vector3f vector = new Vector3f(0.0F, 1.0F, 0.0F).rotate(rotation);
        return new Vec3(vector.x, vector.y, vector.z);
    }

    private static double getTanHalfFov(Minecraft minecraft) {
        double fovRad = Math.toRadians(minecraft.options.fov().get());
        return Math.tan(fovRad / 2.0D);
    }
}
