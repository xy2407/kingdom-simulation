package com.xy2407.kingdom.server.collision;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 实体碰撞空间网格：按 1 格立方分桶并按碰撞体身份分组，缩小移动碰撞的候选集。
 */
public final class NpcCollisionGrid {

    private static final int MIN_ENTITIES = 256;
    // canCollideWith 的默认实现只接受 canBeCollidedWith 的实体；若其它 mod 覆写该语义请置 false 回到全量遍历
    private static final boolean SKIP_NON_COLLIDABLE = true;
    private static final Map<ServerLevel, NpcCollisionGrid> GRIDS = new HashMap<>();
    private static final ThreadLocal<List<Entity>> CANDIDATE_BUFFER = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<VoxelShape>> SHAPE_BUFFER = ThreadLocal.withInitial(ArrayList::new);

    private final Long2ObjectOpenHashMap<Bucket> buckets = new Long2ObjectOpenHashMap<>();
    private int entityCount;
    private int rebuildCount;
    private boolean ready;

    private static final class Bucket {
        final List<Entity> collidable = new ArrayList<>();
        final List<Entity> others = new ArrayList<>();
    }

    private NpcCollisionGrid() {
    }

    public static void rebuild(ServerLevel level) {
        GRIDS.computeIfAbsent(level, key -> new NpcCollisionGrid()).doRebuild(level);
    }

    private void doRebuild(ServerLevel level) {
        for (Bucket bucket : buckets.values()) {
            bucket.collidable.clear();
            bucket.others.clear();
        }
        if (++rebuildCount % 100 == 0) {
            buckets.values().removeIf(b -> b.collidable.isEmpty() && b.others.isEmpty());
        }
        int count = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity.isRemoved() || entity.isSpectator()) {
                continue;
            }
            count++;
            AABB box = entity.getBoundingBox();
            for (int cx = cellOf(box.minX); cx <= cellOf(box.maxX); cx++) {
                for (int cy = cellOf(box.minY); cy <= cellOf(box.maxY); cy++) {
                    for (int cz = cellOf(box.minZ); cz <= cellOf(box.maxZ); cz++) {
                        Bucket bucket = buckets.computeIfAbsent(key(cx, cy, cz), k -> new Bucket());
                        (entity.canBeCollidedWith() ? bucket.collidable : bucket.others).add(entity);
                    }
                }
            }
        }
        entityCount = count;
        ready = true;
    }

    public static List<VoxelShape> entityCollisions(Level level, Entity entity, AABB collisionBox) {
        if (collisionBox.getSize() < 1.0E-7D) {
            return List.of();
        }
        AABB query = collisionBox.inflate(1.0E-7D);
        NpcCollisionGrid grid = level instanceof ServerLevel serverLevel ? GRIDS.get(serverLevel) : null;
        List<Entity> candidates = grid != null && grid.ready && grid.entityCount >= MIN_ENTITIES
                ? grid.collect(entity, query)
                : fallback(level, entity, query);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<VoxelShape> shapes = SHAPE_BUFFER.get();
        shapes.clear();
        for (Entity candidate : candidates) {
            shapes.add(Shapes.create(candidate.getBoundingBox()));
        }
        return shapes;
    }

    private static List<Entity> fallback(Level level, Entity entity, AABB query) {
        Predicate<Entity> predicate = entity == null
                ? EntitySelector.CAN_BE_COLLIDED_WITH
                : EntitySelector.NO_SPECTATORS.and(entity::canCollideWith);
        return level.getEntities(entity, query, predicate);
    }

    private List<Entity> collect(Entity self, AABB query) {
        List<Entity> out = CANDIDATE_BUFFER.get();
        out.clear();
        for (int cx = cellOf(query.minX) - 1; cx <= cellOf(query.maxX) + 1; cx++) {
            for (int cy = cellOf(query.minY) - 1; cy <= cellOf(query.maxY) + 1; cy++) {
                for (int cz = cellOf(query.minZ) - 1; cz <= cellOf(query.maxZ) + 1; cz++) {
                    Bucket bucket = buckets.get(key(cx, cy, cz));
                    if (bucket == null) {
                        continue;
                    }
                    for (Entity candidate : bucket.collidable) {
                        if (candidate != self && !candidate.isSpectator()
                                && candidate.getBoundingBox().intersects(query)) {
                            out.add(candidate);
                        }
                    }
                    if (!SKIP_NON_COLLIDABLE) {
                        for (Entity candidate : bucket.others) {
                            if (candidate != self && !candidate.isRemoved() && !candidate.isSpectator()
                                    && accepts(self, candidate)
                                    && candidate.getBoundingBox().intersects(query)) {
                                out.add(candidate);
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean accepts(Entity self, Entity candidate) {
        return self == null
                ? candidate.canBeCollidedWith()
                : EntitySelector.NO_SPECTATORS.test(candidate) && self.canCollideWith(candidate);
    }

    private static int cellOf(double value) {
        return Mth.floor(value);
    }

    private static long key(int cx, int cy, int cz) {
        return ((long) cx * 73856093L) ^ ((long) cy * 19349663L) ^ ((long) cz * 83492791L);
    }
}
