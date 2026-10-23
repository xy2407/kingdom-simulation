package com.xy2407.kingdom.server.tourism;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.entity.ModEntities;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcRole;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.faction.PlayerProfileStore;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;
import com.xy2407.kingdom.server.road.RoadStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;

/**
 * 游客：白天按领地生成，只在领地道路上闲逛，进店消费后按剩余寿命离场
 */
public final class TourismService {

    public static final TourismService INSTANCE = new TourismService();

    private static final long DAY_LENGTH = 24000L;

    private static final long SPAWN_WINDOW_END = 12000L;

    private static final long SPAWN_INTERVAL = 1000L;

    private static final float SPAWN_CHANCE = 0.5F;

    private static final int SPAWN_MIN = 1;

    private static final int SPAWN_MAX = 3;

    private static final int LIFETIME_MIN = 12000;

    private static final int LIFETIME_MAX = 24000;

    private static final int STEP_TICKS = 20;

    private static final int WANDER_NEAR = 5;

    private static final int WANDER_FAR = 10;

    private static final int SHOP_COOLDOWN = 600;

    private static final int SHOP_CHANCE_PERCENT = 50;

    private static final int SHOP_RANGE = 128;

    private static final int DINING_TICKS = 400;

    private static final int MAX_TOURISTS_PER_TERRITORY = 12;

    private static final long ROAD_CACHE_TICKS = 1200L;

    private static final int FREE_PRICE_MIN = 5;

    private static final int FREE_PRICE_MAX = 15;

    private final Map<UUID, Tourist> runtime = new HashMap<>();

    private final Map<UUID, Integer> stepAcc = new HashMap<>();

    private final Map<String, CachedRoads> roadCache = new HashMap<>();

    private final Map<String, Long> income = new HashMap<>();

    private TourismService() {
    }

    private static final class Tourist {
        private long remaining;
        private int shopCooldown;
        private int diningTicks;
        private BlockPos target;
        private BlockPos shopTarget;
        private boolean shopDining;
        private String territoryId;
    }

    private record CachedRoads(List<BlockPos> points, long builtAt) {
    }

    public void reset() {
        runtime.clear();
        stepAcc.clear();
        roadCache.clear();
        income.clear();
    }

    public long incomeOf(String territoryId) {
        Long total = income.get(territoryId);
        return total == null ? 0L : total;
    }

    public int activeCount() {
        return runtime.size();
    }

    public void tick(MinecraftServer server) {
        for (Territory territory : TerritoryRegistry.INSTANCE.all()) {
            if (territory == null || territory.owner() == null) {
                continue;
            }
            ServerLevel level = levelOf(server, territory.dimension());
            if (level == null) {
                continue;
            }
            long dayTime = level.getDayTime() % DAY_LENGTH;
            if (dayTime >= SPAWN_WINDOW_END || dayTime % SPAWN_INTERVAL != 0L) {
                continue;
            }
            if (level.random.nextFloat() >= SPAWN_CHANCE) {
                continue;
            }
            List<BlockPos> roads = roadsOf(level, territory, level.getGameTime());
            if (roads.isEmpty()) {
                continue;
            }
            int alive = countOf(territory.id());
            int amount = SPAWN_MIN + level.random.nextInt(SPAWN_MAX - SPAWN_MIN + 1);
            for (int index = 0; index < amount && alive < MAX_TOURISTS_PER_TERRITORY; index++) {
                BlockPos spot = roads.get(level.random.nextInt(roads.size()));
                if (spawn(level, territory, spot)) {
                    alive++;
                }
            }
        }
    }

    public void tickTourist(NpcEntity npc, NpcData data) {
        if (npc == null || data == null || !(npc.level() instanceof ServerLevel level)) {
            return;
        }
        int acc = stepAcc.merge(npc.getUUID(), 1, Integer::sum);
        if (acc < STEP_TICKS) {
            return;
        }
        stepAcc.put(npc.getUUID(), 0);
        Tourist tourist = runtime.computeIfAbsent(npc.getUUID(), key -> newTourist(level));
        step(level, npc, data, tourist);
    }

    private static Tourist newTourist(ServerLevel level) {
        Tourist tourist = new Tourist();
        tourist.remaining = LIFETIME_MIN + level.random.nextInt(LIFETIME_MAX - LIFETIME_MIN + 1);
        tourist.shopCooldown = SHOP_COOLDOWN / 2 + level.random.nextInt(SHOP_COOLDOWN);
        return tourist;
    }

    private void step(ServerLevel level, NpcEntity npc, NpcData data, Tourist tourist) {
        if (tourist.diningTicks > 0) {
            tourist.diningTicks -= STEP_TICKS;
            if (tourist.diningTicks <= 0) {
                tourist.diningTicks = 0;
                tourist.shopTarget = null;
                tourist.target = null;
                npc.clearFarmTarget();
            }
            return;
        }
        tourist.remaining -= STEP_TICKS;
        if (tourist.remaining <= 0L) {
            despawn(npc, data);
            return;
        }
        if (tourist.shopTarget != null) {
            if (near(npc, tourist.shopTarget)) {
                payVisit(level, npc, data, tourist);
                return;
            }
            npc.tickTravelPath(tourist.shopTarget);
            return;
        }
        if (tourist.target == null || near(npc, tourist.target)
                || !RoadStore.INSTANCE.contains(data.getDimension(), tourist.target)) {
            BlockPos next = pickRoadPoint(level, npc);
            if (next != null) {
                tourist.target = next;
                npc.setFarmTarget(next);
            }
        }
        if (tourist.target != null) {
            npc.tickTravelPath(tourist.target);
        }
        if (--tourist.shopCooldown <= 0) {
            tourist.shopCooldown = SHOP_COOLDOWN;
            if (level.random.nextInt(100) < SHOP_CHANCE_PERCENT) {
                BuildingRecord shop = pickShop(level, npc);
                if (shop != null) {
                    tourist.shopTarget = standInside(level, shop);
                    if (tourist.shopTarget != null) {
                        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(shop);
                        tourist.shopDining = meta != null && meta.isRestaurant();
                        npc.setFarmTarget(tourist.shopTarget);
                    }
                }
            }
        }
    }

    private boolean spawn(ServerLevel level, Territory territory, BlockPos road) {
        var type = ModEntities.NPC.get();
        var entity = type.create(level);
        if (!(entity instanceof NpcEntity npc)) {
            return false;
        }
        NpcData data = new NpcData(UUID.randomUUID());
        data.setName("游客");
        data.setGender(level.random.nextBoolean() ? "MALE" : "FEMALE");
        data.setRole(NpcRole.TOURIST);
        data.setDimension(territory.dimension());
        data.setEntityUuid(npc.getUUID());
        NpcManager.INSTANCE.registerNpc(data);
        npc.setCitizenId(data.getId());
        npc.moveTo(road.getX() + 0.5D, road.getY() + 1.0D, road.getZ() + 0.5D,
                level.random.nextFloat() * 360.0F, 0.0F);
        level.addFreshEntity(npc);
        Tourist tourist = newTourist(level);
        tourist.territoryId = territory.id();
        runtime.put(npc.getUUID(), tourist);
        return true;
    }

    private void payVisit(ServerLevel level, NpcEntity npc, NpcData data, Tourist tourist) {
        npc.swing(InteractionHand.MAIN_HAND, true);
        Territory territory = TerritoryRegistry.INSTANCE.byId(data.getHomeId());
        long price = FREE_PRICE_MIN + level.random.nextInt(FREE_PRICE_MAX - FREE_PRICE_MIN + 1);
        if (territory != null && territory.owner() != null) {
            PlayerProfileStore.INSTANCE.addMoney(territory.owner(), price);
            income.merge(territory.id(), price, Long::sum);
        }
        tourist.shopTarget = null;
        tourist.target = null;
        tourist.shopCooldown = SHOP_COOLDOWN;
        if (tourist.shopDining) {
            tourist.diningTicks = DINING_TICKS;
            tourist.shopDining = false;
        }
        npc.clearFarmTarget();
    }

    private void despawn(NpcEntity npc, NpcData data) {
        runtime.remove(npc.getUUID());
        stepAcc.remove(npc.getUUID());
        NpcManager.INSTANCE.removeNpc(data.getId());
        npc.discard();
    }

    private int countOf(String territoryId) {
        int count = 0;
        for (Tourist tourist : runtime.values()) {
            if (territoryId.equals(tourist.territoryId)) {
                count++;
            }
        }
        return count;
    }

    private BlockPos pickRoadPoint(ServerLevel level, NpcEntity npc) {
        Territory territory = TerritoryRegistry.INSTANCE.byBlock(level.dimension().location().toString(),
                npc.getX(), npc.getZ());
        if (territory == null) {
            return null;
        }
        List<BlockPos> roads = roadsOf(level, territory, level.getGameTime());
        if (roads.isEmpty()) {
            return null;
        }
        BlockPos here = npc.blockPosition();
        for (int attempt = 0; attempt < 80; attempt++) {
            BlockPos candidate = roads.get(level.random.nextInt(roads.size()));
            int dx = candidate.getX() - here.getX();
            int dz = candidate.getZ() - here.getZ();
            int distance = dx * dx + dz * dz;
            if (distance >= WANDER_NEAR * WANDER_NEAR && distance <= WANDER_FAR * WANDER_FAR) {
                return candidate;
            }
        }
        return null;
    }

    private BuildingRecord pickShop(ServerLevel level, NpcEntity npc) {
        String dimension = level.dimension().location().toString();
        List<BuildingRecord> candidates = new ArrayList<>();
        for (BuildingRecord building : BuildingRegistry.INSTANCE.all()) {
            if (!dimension.equals(building.dimension())) {
                continue;
            }
            BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
            if (meta == null || (!meta.isShop() && !meta.isRestaurant())) {
                continue;
            }
            double dx = npc.getX() - building.min().getX();
            double dz = npc.getZ() - building.min().getZ();
            if (dx * dx + dz * dz > (double) SHOP_RANGE * SHOP_RANGE) {
                continue;
            }
            candidates.add(building);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(level.random.nextInt(candidates.size()));
    }

    private static BlockPos standInside(ServerLevel level, BuildingRecord building) {
        BlockPos min = building.min();
        BlockPos max = building.max();
        int x = (min.getX() + max.getX()) / 2;
        int z = (min.getZ() + max.getZ()) / 2;
        for (int y = max.getY(); y >= min.getY(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()) {
                return pos;
            }
        }
        return null;
    }

    private List<BlockPos> roadsOf(ServerLevel level, Territory territory, long gameTime) {
        CachedRoads cached = roadCache.get(territory.id());
        if (cached != null && gameTime - cached.builtAt() < ROAD_CACHE_TICKS) {
            return cached.points();
        }
        List<BlockPos> points = new ArrayList<>();
        for (long cell : RoadStore.INSTANCE.roadCells(territory.dimension())) {
            BlockPos pos = BlockPos.of(cell);
            if (territory.chunks().contains(Territory.chunkKey(pos.getX() >> 4, pos.getZ() >> 4))) {
                points.add(pos);
            }
        }
        roadCache.put(territory.id(), new CachedRoads(points, gameTime));
        return points;
    }

    private static boolean near(NpcEntity npc, BlockPos target) {
        if (target == null) {
            return false;
        }
        double dx = npc.getX() - (target.getX() + 0.5D);
        double dz = npc.getZ() - (target.getZ() + 0.5D);
        return dx * dx + dz * dz <= 6.25D;
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }
}
