package com.xy2407.kingdom.server.combat;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.ModMain;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 威胁索引：敌对实体事件登记加区块列分桶，索敌只查表不扫世界
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class ThreatIndex {

    private static final int REBUILD_INTERVAL_TICKS = 10;
    private static final int BUCKET_RANGE_LIMIT = 24;
    private static final double VERTICAL_LIMIT = 24.0D;

    private static final Set<Entity> HOSTILES = ConcurrentHashMap.newKeySet();
    private static final Long2ObjectMap<List<Entity>> BUCKETS = new Long2ObjectOpenHashMap<>();

    private static int tickCounter;

    private ThreatIndex() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof Enemy) {
            HOSTILES.add(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof Enemy) {
            HOSTILES.remove(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        HOSTILES.clear();
        BUCKETS.clear();
        tickCounter = 0;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter < REBUILD_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        rebuild();
    }

    private static void rebuild() {
        BUCKETS.clear();
        HOSTILES.removeIf(entity -> entity.isRemoved() || !entity.isAlive());
        for (Entity entity : HOSTILES) {
            ChunkPos pos = entity.chunkPosition();
            long key = ChunkPos.asLong(pos.x, pos.z);
            List<Entity> bucket = BUCKETS.get(key);
            if (bucket == null) {
                bucket = new ObjectArrayList<>(4);
                BUCKETS.put(key, bucket);
            }
            bucket.add(entity);
        }
    }

    public static Entity nearest(ServerLevel level, Vec3 origin, double range) {
        if (level == null || origin == null || range <= 0.0D) {
            return null;
        }
        Entity best = range <= BUCKET_RANGE_LIMIT
                ? nearestByBuckets(level, origin, range)
                : nearestByRegistry(level, origin, range);
        return best;
    }

    private static Entity nearestByBuckets(ServerLevel level, Vec3 origin, double range) {
        int radius = Math.max(1, (int) Math.ceil(range / 16.0D));
        int centerX = (int) Math.floor(origin.x) >> 4;
        int centerZ = (int) Math.floor(origin.z) >> 4;
        double bestSqr = range * range;
        Entity best = null;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                List<Entity> bucket = BUCKETS.get(ChunkPos.asLong(centerX + dx, centerZ + dz));
                if (bucket == null) {
                    continue;
                }
                for (Entity candidate : bucket) {
                    if (!matches(level, candidate, origin, bestSqr)) {
                        continue;
                    }
                    bestSqr = candidate.position().distanceToSqr(origin);
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static Entity nearestByRegistry(ServerLevel level, Vec3 origin, double range) {
        double bestSqr = range * range;
        Entity best = null;
        for (Entity candidate : HOSTILES) {
            if (!matches(level, candidate, origin, bestSqr)) {
                continue;
            }
            bestSqr = candidate.position().distanceToSqr(origin);
            best = candidate;
        }
        return best;
    }

    private static boolean matches(ServerLevel level, Entity candidate, Vec3 origin, double bestSqr) {
        if (candidate.isRemoved() || !candidate.isAlive() || candidate.level() != level) {
            return false;
        }
        Vec3 pos = candidate.position();
        double dy = pos.y - origin.y;
        if (Math.abs(dy) > VERTICAL_LIMIT) {
            return false;
        }
        return candidate.getBoundingBox().getCenter().distanceToSqr(origin) < bestSqr;
    }
}
