package com.xy2407.kingdom.server.tourism;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.building.MenuSpec;
import com.xy2407.kingdom.common.block.ShopShelfBlockEntity;
import com.xy2407.kingdom.common.entity.ModEntities;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcRestaurantService;
import com.xy2407.kingdom.common.npc.NpcRole;
import com.xy2407.kingdom.common.restaurant.RestaurantOrder;
import com.xy2407.kingdom.common.restaurant.RestaurantSeat;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.faction.MoneyService;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;
import com.xy2407.kingdom.server.restaurant.RestaurantOrderStore;
import com.xy2407.kingdom.server.restaurant.RestaurantSeatStore;
import com.xy2407.kingdom.server.road.RoadStore;
import com.xy2407.kingdom.server.shop.ShopStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * 游客：白天按领地在道路上闲逛，每三次触发逛店、每五次触发用餐，消费直接进领地金库
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

    private static final int MAX_TOURISTS_PER_TERRITORY = 12;

    private static final long ROAD_CACHE_TICKS = 1200L;

    private static final int TOURIST_WEALTH_MIN = 20;

    private static final int TOURIST_WEALTH_MAX = 200;

    private static final int WANDER_COOLDOWN = 100;

    private static final int WANDER_RANGE = 15;

    private static final int WANDER_MIN_RANGE = 5;

    private static final int SHOP_EVERY = 3;

    private static final int DINE_EVERY = 5;

    private static final int TRIGGER_PERCENT = 50;

    private static final int BUY_PERCENT = 50;

    private static final int SHELF_SAMPLE = 3;

    private static final int SHOP_RANGE = 128;

    private static final int EXIT_CANDIDATES = 20;

    private static final double NEAR_SQR = 6.25D;

    private static final int MOVE_TIMEOUT_TICKS = 1200;

    private static final int DINE_TIMEOUT_TICKS = 6000;

    private static final int SHELF_WAIT_TIMEOUT = 600;

    private final Map<UUID, Tourist> runtime = new HashMap<>();

    private final Map<String, CachedRoads> roadCache = new HashMap<>();

    private final Map<String, Long> income = new HashMap<>();

    private final Map<Long, ArrayDeque<UUID>> shelfQueues = new HashMap<>();

    private TourismService() {
    }

    private enum Mode {
        WANDER_MOVING,
        WANDER_REST,
        SHOP_MOVING,
        SHOP_SHELF_MOVING,
        SHOP_SHELF_WAIT,
        SHOP_LEAVE,
        DINE_MOVING,
        DINE_WAIT,
        DINE_LEAVE
    }

    private static final class Tourist {
        private long remaining;
        private Mode mode = Mode.WANDER_MOVING;
        private int wanderCooldown;
        private int wanderCount;
        private int moveTicks;
        private BlockPos target;
        private String territoryId;
        private UUID shopBuildingId;
        private List<BlockPos> shelfQueue = List.of();
        private int shelfIndex;
        private int waitTicks;
        private UUID diningBuildingId;
        private BlockPos seat;
        private int dineWaitTicks;
        private boolean seated;
    }

    private record CachedRoads(List<BlockPos> points, long builtAt) {
    }

    public void reset() {
        runtime.clear();
        roadCache.clear();
        income.clear();
        shelfQueues.clear();
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
        Tourist tourist = runtime.computeIfAbsent(npc.getUUID(), key -> newTourist(level));
        if (tourist.territoryId == null) {
            Territory territory = TerritoryRegistry.INSTANCE.byBlock(
                    level.dimension().location().toString(), npc.getX(), npc.getZ());
            tourist.territoryId = territory == null ? "" : territory.id();
        }
        tick(level, npc, data, tourist);
    }

    private static Tourist newTourist(ServerLevel level) {
        Tourist tourist = new Tourist();
        tourist.remaining = LIFETIME_MIN + level.random.nextInt(LIFETIME_MAX - LIFETIME_MIN + 1);
        tourist.wanderCooldown = WANDER_COOLDOWN;
        return tourist;
    }

    private void tick(ServerLevel level, NpcEntity npc, NpcData data, Tourist tourist) {
        if (isWandering(tourist.mode)) {
            if (--tourist.remaining <= 0L) {
                despawn(npc, data, tourist);
                return;
            }
        }
        switch (tourist.mode) {
            case WANDER_MOVING -> tickWanderMoving(level, npc, data, tourist);
            case WANDER_REST -> tickWanderRest(level, npc, data, tourist);
            case SHOP_MOVING -> tickShopMoving(level, npc, data, tourist);
            case SHOP_SHELF_MOVING -> tickShelfMoving(level, npc, data, tourist);
            case SHOP_SHELF_WAIT -> tickShelfWait(level, npc, data, tourist);
            case SHOP_LEAVE -> tickLeaving(npc, tourist);
            case DINE_MOVING -> tickDineMoving(level, npc, data, tourist);
            case DINE_WAIT -> tickDineWait(level, npc, data, tourist);
            case DINE_LEAVE -> tickLeaving(npc, tourist);
            default -> tourist.mode = Mode.WANDER_MOVING;
        }
    }

    private static boolean isWandering(Mode mode) {
        return mode == Mode.WANDER_MOVING || mode == Mode.WANDER_REST;
    }

    private void tickWanderMoving(ServerLevel level, NpcEntity npc, NpcData data, Tourist tourist) {
        if (tourist.target == null) {
            tourist.target = pickWanderTarget(level, npc, tourist.territoryId);
            if (tourist.target == null) {
                toRest(tourist);
                return;
            }
            tourist.moveTicks = 0;
            npc.setFarmTarget(tourist.target);
        }
        if (near(npc, tourist.target)
                || !RoadStore.INSTANCE.contains(data.getDimension(), tourist.target)) {
            npc.getNavigation().stop();
            npc.clearFarmTarget();
            toRest(tourist);
            return;
        }
        if (++tourist.moveTicks > MOVE_TIMEOUT_TICKS) {
            npc.getNavigation().stop();
            npc.clearFarmTarget();
            tourist.target = null;
            tourist.moveTicks = 0;
            return;
        }
        npc.tickTravelPath(tourist.target);
    }

    private static void toRest(Tourist tourist) {
        tourist.target = null;
        tourist.moveTicks = 0;
        tourist.mode = Mode.WANDER_REST;
        tourist.wanderCooldown = WANDER_COOLDOWN;
    }

    private void tickWanderRest(ServerLevel level, NpcEntity npc, NpcData data, Tourist tourist) {
        if (--tourist.wanderCooldown > 0) {
            return;
        }
        tourist.wanderCooldown = 0;
        tourist.wanderCount++;
        boolean dueShop = tourist.wanderCount % SHOP_EVERY == 0;
        boolean dueDine = tourist.wanderCount % DINE_EVERY == 0;
        if (dueShop && dueDine) {
            if (level.random.nextBoolean()) {
                dueDine = false;
            } else {
                dueShop = false;
            }
        }
        if (dueDine && level.random.nextInt(100) < TRIGGER_PERCENT
                && startDining(level, npc, data, tourist)) {
            return;
        }
        if (dueShop && level.random.nextInt(100) < TRIGGER_PERCENT
                && startShopping(level, npc, data, tourist)) {
            return;
        }
        tourist.mode = Mode.WANDER_MOVING;
        tourist.target = null;
    }

    private BlockPos pickWanderTarget(ServerLevel level, NpcEntity npc, String territoryId) {
        Territory territory = TerritoryRegistry.INSTANCE.byId(territoryId);
        if (territory == null) {
            territory = TerritoryRegistry.INSTANCE.byBlock(
                    level.dimension().location().toString(), npc.getX(), npc.getZ());
        }
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
            if (distance >= WANDER_MIN_RANGE * WANDER_MIN_RANGE
                    && distance <= WANDER_RANGE * WANDER_RANGE) {
                return candidate;
            }
        }
        return null;
    }

    private boolean startShopping(ServerLevel level, NpcEntity npc, NpcData data, Tourist tourist) {
        if (data.getWealth() <= 0L) {
            return false;
        }
        BuildingRecord shop = pickShop(level, npc, tourist.territoryId);
        if (shop == null) {
            return false;
        }
        BlockPos stand = standInside(level, shop);
        if (stand == null) {
            return false;
        }
        tourist.shopBuildingId = shop.id();
        tourist.shelfQueue = List.of();
        tourist.shelfIndex = 0;
        tourist.target = stand;
        tourist.moveTicks = 0;
        tourist.mode = Mode.SHOP_MOVING;
        npc.setFarmTarget(stand);
        return true;
    }

    private void tickShopMoving(ServerLevel level, NpcEntity npc, NpcData data, Tourist tourist) {
        if (tourist.target == null) {
            startShopLeave(level, npc, tourist);
            return;
        }
        if (!near(npc, tourist.target)) {
            if (++tourist.moveTicks > MOVE_TIMEOUT_TICKS) {
                startShopLeave(level, npc, tourist);
                return;
            }
            npc.tickTravelPath(tourist.target);
            return;
        }
        npc.getNavigation().stop();
        npc.clearFarmTarget();
        List<BlockPos> shelves = new ArrayList<>();
        for (BlockPos pos : ShopStore.INSTANCE.shelvesOf(tourist.shopBuildingId)) {
            if (level.getBlockEntity(pos) instanceof ShopShelfBlockEntity) {
                shelves.add(pos);
            }
        }
        if (shelves.isEmpty()) {
            startShopLeave(level, npc, tourist);
            return;
        }
        shuffle(shelves, level);
        tourist.shelfQueue = new ArrayList<>(
                shelves.subList(0, Math.min(SHELF_SAMPLE, shelves.size())));
        tourist.shelfIndex = 0;
        tourist.target = tourist.shelfQueue.get(0);
        tourist.moveTicks = 0;
        tourist.mode = Mode.SHOP_SHELF_MOVING;
        npc.setFarmTarget(tourist.target);
    }

    private void tickShelfMoving(ServerLevel level, NpcEntity npc, NpcData data, Tourist tourist) {
        if (tourist.target == null) {
            startShopLeave(level, npc, tourist);
            return;
        }
        if (!near(npc, tourist.target)) {
            if (++tourist.moveTicks > MOVE_TIMEOUT_TICKS) {
                startShopLeave(level, npc, tourist);
                return;
            }
            npc.tickTravelPath(tourist.target);
            return;
        }
        npc.getNavigation().stop();
        npc.clearFarmTarget();
        tryBrowseShelf(level, npc, data, tourist, tourist.target);
    }

    private void tryBrowseShelf(ServerLevel level, NpcEntity npc, NpcData data,
                                Tourist tourist, BlockPos pos) {
        if (!acquireShelf(pos, npc.getUUID())) {
            tourist.waitTicks = 0;
            tourist.mode = Mode.SHOP_SHELF_WAIT;
            return;
        }
        browseShelf(level, data, tourist, pos);
        releaseShelf(pos, npc.getUUID());
        advanceShelf(level, npc, tourist);
    }

    private void tickShelfWait(ServerLevel level, NpcEntity npc, NpcData data, Tourist tourist) {
        if (tourist.target == null) {
            startShopLeave(level, npc, tourist);
            return;
        }
        if (acquireShelf(tourist.target, npc.getUUID())) {
            browseShelf(level, data, tourist, tourist.target);
            releaseShelf(tourist.target, npc.getUUID());
            advanceShelf(level, npc, tourist);
            return;
        }
        if (++tourist.waitTicks > SHELF_WAIT_TIMEOUT) {
            releaseShelf(tourist.target, npc.getUUID());
            advanceShelf(level, npc, tourist);
        }
    }

    private void advanceShelf(ServerLevel level, NpcEntity npc, Tourist tourist) {
        tourist.shelfIndex++;
        if (tourist.shelfIndex >= tourist.shelfQueue.size()) {
            startShopLeave(level, npc, tourist);
            return;
        }
        tourist.target = tourist.shelfQueue.get(tourist.shelfIndex);
        tourist.moveTicks = 0;
        tourist.waitTicks = 0;
        tourist.mode = Mode.SHOP_SHELF_MOVING;
        npc.setFarmTarget(tourist.target);
    }

    private boolean acquireShelf(BlockPos pos, UUID id) {
        if (pos == null || id == null) {
            return false;
        }
        ArrayDeque<UUID> queue = shelfQueues.computeIfAbsent(pos.asLong(),
                key -> new ArrayDeque<>());
        if (!queue.contains(id)) {
            queue.addLast(id);
        }
        return queue.peekFirst() != null && queue.peekFirst().equals(id);
    }

    private void releaseShelf(BlockPos pos, UUID id) {
        if (pos == null || id == null) {
            return;
        }
        long key = pos.asLong();
        ArrayDeque<UUID> queue = shelfQueues.get(key);
        if (queue == null) {
            return;
        }
        queue.remove(id);
        if (queue.isEmpty()) {
            shelfQueues.remove(key);
        }
    }

    private void browseShelf(ServerLevel level, NpcData data, Tourist tourist, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ShopShelfBlockEntity shelf)) {
            return;
        }
        long wealth = Math.max(0L, data.getWealth());
        if (wealth <= 0L) {
            return;
        }
        List<Integer> candidates = new ArrayList<>();
        for (int slot = 0; slot < ShopShelfBlockEntity.SLOTS; slot++) {
            if (shelf.getItem(slot).isEmpty()) {
                continue;
            }
            int price = shelf.priceOf(slot);
            if (price > 0 && price * 2L < wealth) {
                candidates.add(slot);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        int slot = candidates.get(level.random.nextInt(candidates.size()));
        if (level.random.nextInt(100) >= BUY_PERCENT) {
            return;
        }
        ItemStack stack = shelf.getItem(slot);
        if (stack.isEmpty()) {
            return;
        }
        int price = shelf.priceOf(slot);
        if (price * 2L >= wealth) {
            return;
        }
        if (shelf.removeItem(slot, 1).isEmpty()) {
            return;
        }
        data.setWealth(wealth - price);
        creditTerritory(tourist, price, "商铺营收");
    }

    private void startShopLeave(ServerLevel level, NpcEntity npc, Tourist tourist) {
        if (tourist.target != null) {
            releaseShelf(tourist.target, npc.getUUID());
        }
        BuildingRecord building = tourist.shopBuildingId == null
                ? null : BuildingRegistry.INSTANCE.byId(tourist.shopBuildingId);
        if (!beginLeaving(level, npc, tourist, building, Mode.SHOP_LEAVE)) {
            tourist.mode = Mode.WANDER_MOVING;
            tourist.target = null;
        }
        tourist.shopBuildingId = null;
        tourist.shelfQueue = List.of();
        tourist.shelfIndex = 0;
    }

    private boolean startDining(ServerLevel level, NpcEntity npc, NpcData data, Tourist tourist) {
        if (data.getWealth() <= 0L) {
            return false;
        }
        BuildingRecord restaurant = pickRestaurant(level, npc, tourist.territoryId);
        if (restaurant == null) {
            return false;
        }
        if (!NpcRestaurantService.rolesOf(restaurant.id()).open()) {
            return false;
        }
        if (RestaurantSeatStore.INSTANCE.seatsOf(restaurant.id()).isEmpty()) {
            return false;
        }
        BlockPos stand = standInside(level, restaurant);
        if (stand == null) {
            return false;
        }
        tourist.diningBuildingId = restaurant.id();
        tourist.seat = null;
        tourist.dineWaitTicks = 0;
        tourist.target = stand;
        tourist.moveTicks = 0;
        tourist.mode = Mode.DINE_MOVING;
        npc.setFarmTarget(stand);
        return true;
    }

    private void tickDineMoving(ServerLevel level, NpcEntity npc, NpcData data, Tourist tourist) {
        if (tourist.target == null) {
            endDining(level, npc, tourist);
            return;
        }
        if (!near(npc, tourist.target)) {
            if (++tourist.moveTicks > MOVE_TIMEOUT_TICKS) {
                endDining(level, npc, tourist);
                return;
            }
            npc.tickTravelPath(tourist.target);
            return;
        }
        npc.getNavigation().stop();
        npc.clearFarmTarget();
        String dimension = level.dimension().location().toString();
        BlockPos seat = null;
        for (RestaurantSeat candidate : RestaurantSeatStore.INSTANCE.seatsOf(tourist.diningBuildingId)) {
            if (!dimension.equals(candidate.dimension())) {
                continue;
            }
            if (RestaurantOrderStore.INSTANCE.occupySeat(candidate.seat(), data.getId())) {
                seat = candidate.seat();
                break;
            }
        }
        if (seat == null) {
            endDining(level, npc, tourist);
            return;
        }
        BuildingRecord building = BuildingRegistry.INSTANCE.byId(tourist.diningBuildingId);
        BuildingMeta meta = building == null ? null : BuildingRegistry.INSTANCE.metaOf(building);
        List<MenuSpec> menu = meta == null ? List.of() : meta.menu();
        if (menu.isEmpty()) {
            RestaurantOrderStore.INSTANCE.releaseSeat(seat);
            endDining(level, npc, tourist);
            return;
        }
        MenuSpec dish = menu.get(level.random.nextInt(menu.size()));
        RestaurantOrderStore.INSTANCE.put(new RestaurantOrder(data.getId(), tourist.diningBuildingId,
                dimension, seat, dish.output().item(), RestaurantOrder.Status.PENDING));
        tourist.seat = seat;
        tourist.dineWaitTicks = 0;
        tourist.mode = Mode.DINE_WAIT;
        npc.sitAt(seat);
        tourist.seated = true;
    }

    private void tickDineWait(ServerLevel level, NpcEntity npc, NpcData data, Tourist tourist) {
        RestaurantOrder order = RestaurantOrderStore.INSTANCE.byCustomer(data.getId());
        if (order == null) {
            if (tourist.seated) {
                npc.standUpFromSeat();
                tourist.seated = false;
            }
            endDining(level, npc, tourist);
            return;
        }
        if (order.status() == RestaurantOrder.Status.SERVING) {
            long price = dishPrice(tourist.diningBuildingId, order.dishId());
            long budget = Math.max(0L, data.getWealth());
            long paid = Math.min(budget, price);
            if (paid > 0L) {
                data.setWealth(budget - paid);
                creditTerritory(tourist, paid, "餐厅营收");
            }
            if (tourist.seated) {
                npc.standUpFromSeat();
                tourist.seated = false;
            }
            RestaurantOrderStore.INSTANCE.remove(data.getId());
            endDining(level, npc, tourist);
            return;
        }
        if (++tourist.dineWaitTicks > DINE_TIMEOUT_TICKS) {
            if (tourist.seated) {
                npc.standUpFromSeat();
                tourist.seated = false;
            }
            RestaurantOrderStore.INSTANCE.remove(data.getId());
            BuildingRecord building = tourist.diningBuildingId == null
                    ? null : BuildingRegistry.INSTANCE.byId(tourist.diningBuildingId);
            BlockPos exit = pickExitRoad(level, tourist, building);
            tourist.diningBuildingId = null;
            tourist.seat = null;
            tourist.dineWaitTicks = 0;
            tourist.target = null;
            tourist.moveTicks = 0;
            tourist.mode = Mode.WANDER_MOVING;
            if (exit != null) {
                npc.getNavigation().stop();
                npc.clearFarmTarget();
                npc.teleportTo(exit.getX() + 0.5D, exit.getY(), exit.getZ() + 0.5D);
            }
        }
    }

    private long dishPrice(UUID buildingId, String dishId) {
        BuildingRecord building = buildingId == null ? null : BuildingRegistry.INSTANCE.byId(buildingId);
        BuildingMeta meta = building == null ? null : BuildingRegistry.INSTANCE.metaOf(building);
        if (meta == null) {
            return 0L;
        }
        for (MenuSpec dish : meta.menu()) {
            if (dish.id().equals(dishId) || dish.output().item().equals(dishId)) {
                return Math.max(0, dish.price());
            }
        }
        return 0L;
    }

    private void endDining(ServerLevel level, NpcEntity npc, Tourist tourist) {
        BuildingRecord building = tourist.diningBuildingId == null
                ? null : BuildingRegistry.INSTANCE.byId(tourist.diningBuildingId);
        if (!beginLeaving(level, npc, tourist, building, Mode.DINE_LEAVE)) {
            tourist.mode = Mode.WANDER_MOVING;
            tourist.target = null;
        }
        tourist.diningBuildingId = null;
        tourist.seat = null;
        tourist.dineWaitTicks = 0;
    }

    private boolean beginLeaving(ServerLevel level, NpcEntity npc, Tourist tourist,
                                 BuildingRecord building, Mode leaveMode) {
        BlockPos exit = pickExitRoad(level, tourist, building);
        if (exit == null) {
            npc.getNavigation().stop();
            npc.clearFarmTarget();
            return false;
        }
        tourist.target = exit;
        tourist.moveTicks = 0;
        tourist.mode = leaveMode;
        npc.setFarmTarget(exit);
        return true;
    }

    private void tickLeaving(NpcEntity npc, Tourist tourist) {
        if (tourist.target == null || near(npc, tourist.target)) {
            npc.getNavigation().stop();
            npc.clearFarmTarget();
            tourist.target = null;
            tourist.moveTicks = 0;
            tourist.mode = Mode.WANDER_MOVING;
            return;
        }
        if (++tourist.moveTicks > MOVE_TIMEOUT_TICKS) {
            npc.getNavigation().stop();
            npc.clearFarmTarget();
            tourist.target = null;
            tourist.moveTicks = 0;
            tourist.mode = Mode.WANDER_MOVING;
            return;
        }
        npc.tickTravelPath(tourist.target);
    }

    private BlockPos pickExitRoad(ServerLevel level, Tourist tourist, BuildingRecord building) {
        String dimension = level.dimension().location().toString();
        Territory territory = TerritoryRegistry.INSTANCE.byId(tourist.territoryId);
        if (territory == null && building != null) {
            territory = TerritoryRegistry.INSTANCE.byBlock(dimension,
                    building.min().getX(), building.min().getZ());
        }
        if (territory == null) {
            return null;
        }
        List<BlockPos> roads = roadsOf(level, territory, level.getGameTime());
        if (roads.isEmpty()) {
            return null;
        }
        List<BlockPos> outside = new ArrayList<>();
        for (BlockPos pos : roads) {
            if (building != null && insideBuilding(building, pos)) {
                continue;
            }
            outside.add(pos);
        }
        if (outside.isEmpty()) {
            return null;
        }
        BlockPos center = building == null ? null : building.min();
        if (center == null) {
            return outside.get(level.random.nextInt(outside.size()));
        }
        outside.sort((left, right) -> Double.compare(
                left.distSqr(center), right.distSqr(center)));
        int limit = Math.min(EXIT_CANDIDATES, outside.size());
        return outside.get(level.random.nextInt(limit));
    }

    private static boolean insideBuilding(BuildingRecord building, BlockPos pos) {
        BlockPos min = building.min();
        BlockPos max = building.max();
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    private BuildingRecord pickShop(ServerLevel level, NpcEntity npc, String territoryId) {
        String dimension = level.dimension().location().toString();
        Territory territory = TerritoryRegistry.INSTANCE.byId(territoryId);
        List<BuildingRecord> candidates = new ArrayList<>();
        for (BuildingRecord building : BuildingRegistry.INSTANCE.all()) {
            if (!dimension.equals(building.dimension()) || !inTerritory(territory, building)) {
                continue;
            }
            BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
            if (meta == null || !meta.isWork(BuildingWork.SHOP)) {
                continue;
            }
            if (!ShopStore.INSTANCE.isOpen(building.id())
                    || ShopStore.INSTANCE.shelvesOf(building.id()).isEmpty()) {
                continue;
            }
            if (distSqr(npc, building) > (double) SHOP_RANGE * SHOP_RANGE) {
                continue;
            }
            candidates.add(building);
        }
        return candidates.isEmpty() ? null : candidates.get(level.random.nextInt(candidates.size()));
    }

    private BuildingRecord pickRestaurant(ServerLevel level, NpcEntity npc, String territoryId) {
        String dimension = level.dimension().location().toString();
        Territory territory = TerritoryRegistry.INSTANCE.byId(territoryId);
        List<BuildingRecord> candidates = new ArrayList<>();
        for (BuildingRecord building : BuildingRegistry.INSTANCE.all()) {
            if (!dimension.equals(building.dimension()) || !inTerritory(territory, building)) {
                continue;
            }
            BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
            if (meta == null || !meta.isWork(BuildingWork.RESTAURANT) || meta.menu().isEmpty()) {
                continue;
            }
            if (distSqr(npc, building) > (double) SHOP_RANGE * SHOP_RANGE) {
                continue;
            }
            if (!NpcRestaurantService.rolesOf(building.id()).open()
                    || !RestaurantSeatStore.INSTANCE.hasTableSeat(building.id())) {
                continue;
            }
            candidates.add(building);
        }
        return candidates.isEmpty() ? null : candidates.get(level.random.nextInt(candidates.size()));
    }

    private static boolean inTerritory(Territory territory, BuildingRecord building) {
        if (territory == null) {
            return true;
        }
        BlockPos min = building.min();
        return territory.chunks().contains(Territory.chunkKey(min.getX() >> 4, min.getZ() >> 4));
    }

    private static double distSqr(NpcEntity npc, BuildingRecord building) {
        double dx = npc.getX() - building.min().getX();
        double dz = npc.getZ() - building.min().getZ();
        return dx * dx + dz * dz;
    }

    private void creditTerritory(Tourist tourist, long amount, String kind) {
        if (amount <= 0L) {
            return;
        }
        Territory territory = TerritoryRegistry.INSTANCE.byId(tourist.territoryId);
        if (territory == null) {
            return;
        }
        MoneyService.creditTerritory(territory.id(), amount, kind);
        income.merge(territory.id(), amount, Long::sum);
    }

    private static void shuffle(List<BlockPos> list, ServerLevel level) {
        for (int index = list.size() - 1; index > 0; index--) {
            int other = level.random.nextInt(index + 1);
            Collections.swap(list, index, other);
        }
    }

    private boolean spawn(ServerLevel level, Territory territory, BlockPos road) {
        var type = ModEntities.NPC.get();
        var entity = type.create(level);
        if (!(entity instanceof NpcEntity npc)) {
            return false;
        }
        NpcData data = new NpcData(UUID.randomUUID());
        data.setGender(level.random.nextBoolean() ? "MALE" : "FEMALE");
        data.setName(com.xy2407.kingdom.common.npc.NpcNameGenerator.generate(data.getGender()));
        data.setRole(NpcRole.TOURIST);
        data.setDimension(territory.dimension());
        data.setTerritoryId(territory.id());
        data.setNationId(territory.nationId());
        data.setEntityUuid(npc.getUUID());
        data.setWealth(TOURIST_WEALTH_MIN
                + level.random.nextInt(TOURIST_WEALTH_MAX - TOURIST_WEALTH_MIN + 1));
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

    private void despawn(NpcEntity npc, NpcData data, Tourist tourist) {
        if (tourist.target != null) {
            releaseShelf(tourist.target, npc.getUUID());
        }
        if (tourist.seated) {
            npc.standUpFromSeat();
            tourist.seated = false;
        }
        if (tourist.seat != null) {
            RestaurantOrderStore.INSTANCE.releaseSeat(tourist.seat);
        }
        RestaurantOrderStore.INSTANCE.remove(data.getId());
        runtime.remove(npc.getUUID());
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
        return dx * dx + dz * dz <= NEAR_SQR;
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
