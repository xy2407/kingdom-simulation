package com.xy2407.kingdom.server.ranch;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.building.AnimalSpec;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcInventory;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.persistence.RanchSchema;
import com.xy2407.kingdom.common.ranch.RanchAnimals;
import com.xy2407.kingdom.common.ranch.RanchBounds;
import com.xy2407.kingdom.common.ranch.RanchPen;
import com.xy2407.kingdom.server.building.BuildingRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 养殖核心循环：校验建筑与饲养员、维护动物数量、投喂繁殖、采集与宰杀
 */
public final class RanchWorkService {

    private static final long VALIDATE_INTERVAL = 20L;
    private static final long ENTITY_SCAN_INTERVAL = 40L;
    private static final long FEED_INTERVAL = 2400L;
    private static final long COLLECT_INTERVAL = 600L;
    private static final int MAX_COLUMN = 24;

    public static final String STATUS_NO_BUILDING = "no_building";
    public static final String STATUS_NO_ANIMAL = "no_animal";
    public static final String STATUS_NO_KEEPER = "no_keeper";
    public static final String STATUS_NO_FEED = "no_feed";
    public static final String STATUS_GROWING = "growing";
    public static final String STATUS_FULL = "full";
    public static final String STATUS_COLLECTING = "collecting";

    private static final Map<UUID, PenRuntime> RUNTIMES = new ConcurrentHashMap<>();
    private static final Set<UUID> CONTROLLED_KEEPERS = new HashSet<>();

    private RanchWorkService() {
    }

    public static void reset() {
        RUNTIMES.clear();
        CONTROLLED_KEEPERS.clear();
    }

    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        Set<UUID> activePens = new HashSet<>();
        Set<UUID> activeKeepers = new HashSet<>();
        for (RanchPen pen : RanchPenManager.INSTANCE.all()) {
            activePens.add(pen.id());
            PenRuntime runtime = RUNTIMES.computeIfAbsent(pen.id(), id -> new PenRuntime());
            if (!pen.running()) {
                runtime.clear();
                continue;
            }
            ServerLevel level = levelOf(server, pen.dimension());
            if (level == null) {
                continue;
            }
            NpcData keeper = firstKeeper(pen);
            if (keeper != null) {
                activeKeepers.add(keeper.getId());
            }
            long gameTime = level.getGameTime();
            if (gameTime < runtime.nextTick) {
                continue;
            }
            tickPen(level, pen, runtime, keeper, gameTime);
        }
        RUNTIMES.keySet().retainAll(activePens);
        releaseUnusedKeepers(activeKeepers);
    }

    private static void tickPen(ServerLevel level, RanchPen pen, PenRuntime runtime,
                                NpcData keeper, long gameTime) {
        if (gameTime - runtime.lastValidate >= VALIDATE_INTERVAL) {
            runtime.lastValidate = gameTime;
            BuildingRecord building = BuildingRegistry.INSTANCE.byId(pen.buildingId());
            AnimalSpec spec = RanchPenManager.INSTANCE.animalOf(pen);
            if (building == null) {
                status(pen, STATUS_NO_BUILDING);
                runtime.clear();
                return;
            }
            if (spec == null) {
                status(pen, STATUS_NO_ANIMAL);
                runtime.clear();
                return;
            }
            if (keeper == null) {
                status(pen, STATUS_NO_KEEPER);
                runtime.clear();
                return;
            }
            runtime.building = building;
            runtime.spec = spec;
            runtime.keeper = keeper;
            runtime.type = entityType(spec.entity());
            runtime.bounds = RanchBounds.box(building);
            runtime.base = null;
            keepControlled(keeper);
        }
        if (runtime.building == null || runtime.spec == null || runtime.keeper == null) {
            return;
        }
        NpcEntity keeperEntity = entityOf(level, runtime.keeper);

        if (gameTime - runtime.lastScan >= ENTITY_SCAN_INTERVAL) {
            runtime.lastScan = gameTime;
            ensureAnimals(level, pen, runtime);
            moveKeeper(level, runtime, keeperEntity);
        }
        if (gameTime - runtime.lastFeed >= FEED_INTERVAL) {
            runtime.lastFeed = gameTime;
            feed(level, pen, runtime);
            if (!runtime.spec.collecting()) {
                produce(level, pen, runtime, keeperEntity, false);
            }
        }
        if (runtime.spec.collecting() && gameTime - runtime.lastCollect >= COLLECT_INTERVAL) {
            runtime.lastCollect = gameTime;
            produce(level, pen, runtime, keeperEntity, true);
        }
        runtime.nextTick = gameTime + 1;
    }

    /** 栏内动物按容量补齐：同类合并到一个基座实体，数量存在它的虚拟条目里。 */
    private static void ensureAnimals(ServerLevel level, RanchPen pen, PenRuntime runtime) {
        int capacity = Math.max(1, Math.min(MAX_COLUMN, runtime.spec.capacity()));
        Mob base = RanchAnimals.consolidate(level, runtime.bounds, penKey(pen), runtime.type);
        if (base == null) {
            List<BlockPos> points = RanchBounds.standablePoints(level, runtime.building);
            base = RanchAnimals.spawnBase(level, points.get(0), runtime.type, penKey(pen), capacity);
        } else if (RanchAnimals.count(base) < capacity) {
            RanchAnimals.keep(base, capacity);
        }
        runtime.base = base;
        if (base != null) {
            RanchAnimals.updateDisplay(base, runtime.spec.name());
        }
    }

    private static void feed(ServerLevel level, RanchPen pen, PenRuntime runtime) {
        Mob base = baseOf(runtime);
        if (base == null) {
            return;
        }
        int babies = RanchAnimals.count(base) - RanchAnimals.adults(base);
        if (babies <= 0) {
            status(pen, STATUS_FULL);
            return;
        }
        if (!runtime.spec.feed().isBlank()
                && !consumeFeed(level, runtime.building, runtime.spec.feed(), 1)) {
            status(pen, STATUS_NO_FEED);
            return;
        }
        RanchAnimals.grow(base);
        RanchAnimals.updateDisplay(base, runtime.spec.name());
        status(pen, STATUS_GROWING);
    }

    /** 采集型按成年数定期出货；宰杀型每次拿一头成年换一份产出并补一条幼年。 */
    private static void produce(ServerLevel level, RanchPen pen, PenRuntime runtime,
                                NpcEntity keeper, boolean collecting) {
        Mob base = baseOf(runtime);
        if (base == null) {
            return;
        }
        int adults = RanchAnimals.adults(base);
        if (adults <= 0 || runtime.spec.output().isBlank()) {
            return;
        }
        if (collecting) {
            deposit(keeper, runtime.spec.output(), Math.max(1, adults / 2));
            status(pen, STATUS_COLLECTING);
            return;
        }
        int killed = RanchAnimals.takeAdults(base, 1);
        if (killed > 0) {
            deposit(keeper, runtime.spec.output(), killed);
            RanchAnimals.updateDisplay(base, runtime.spec.name());
            status(pen, STATUS_GROWING);
        }
    }

    private static void moveKeeper(ServerLevel level, PenRuntime runtime, NpcEntity keeper) {
        if (keeper == null) {
            return;
        }
        BlockPos target = nearest(RanchBounds.standablePoints(level, runtime.building), keeper.position());
        if (target != null) {
            keeper.setRanchTarget(target);
        }
    }

    private static BlockPos nearest(List<BlockPos> points, Vec3 origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos point : points) {
            double distance = origin.distanceToSqr(point.getX() + 0.5D, point.getY(), point.getZ() + 0.5D);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = point;
            }
        }
        return best;
    }

    private static boolean consumeFeed(ServerLevel level, BuildingRecord building, String feedId, int amount) {
        ResourceLocation id = ResourceLocation.tryParse(feedId);
        if (id == null) {
            return true;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return true;
        }
        int remaining = amount;
        for (Container container : RanchBounds.containers(level, building)) {
            for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || !stack.is(item)) {
                    continue;
                }
                int take = Math.min(stack.getCount(), remaining);
                stack.shrink(take);
                container.setChanged();
                remaining -= take;
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return remaining <= 0;
    }

    /** 产出发给饲养员背包（替代参照实现的产出容器）；背包装不下就丢弃。 */
    private static void deposit(NpcEntity keeper, String itemId, int amount) {
        if (keeper == null || amount <= 0) {
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) {
            return;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return;
        }
        ItemStack stack = new ItemStack(item, 1);
        stack.setCount(Math.min(amount, stack.getMaxStackSize()));
        NpcInventory inventory = keeper.getNpcInventory();
        for (int slot = 0; slot < inventory.getSlots() && !stack.isEmpty(); slot++) {
            stack = inventory.insertItem(slot, stack, false);
        }
    }

    private static Mob baseOf(PenRuntime runtime) {
        Mob base = runtime.base;
        if (base == null || !base.isAlive()) {
            runtime.base = null;
            return null;
        }
        return base;
    }

    private static String penKey(RanchPen pen) {
        return RanchSchema.penKey(pen.buildingId(), pen.animalId());
    }

    private static NpcData firstKeeper(RanchPen pen) {
        for (UUID id : pen.keeperIds()) {
            NpcData npc = NpcManager.INSTANCE.getNpc(id);
            if (npc != null && !npc.isDead()) {
                return npc;
            }
        }
        return null;
    }

    private static NpcEntity entityOf(ServerLevel level, NpcData npc) {
        UUID uuid = npc.getEntityUuid();
        if (uuid == null) {
            return null;
        }
        return level.getEntity(uuid) instanceof NpcEntity entity && entity.isAlive() ? entity : null;
    }

    private static EntityType<?> entityType(String entityId) {
        ResourceLocation id = ResourceLocation.tryParse(entityId);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    private static void keepControlled(NpcData keeper) {
        keeper.getBrain().setRanchControlled(true);
        CONTROLLED_KEEPERS.add(keeper.getId());
    }

    private static void releaseUnusedKeepers(Set<UUID> activeKeepers) {
        for (UUID id : new HashSet<>(CONTROLLED_KEEPERS)) {
            if (activeKeepers.contains(id)) {
                continue;
            }
            CONTROLLED_KEEPERS.remove(id);
            NpcData npc = NpcManager.INSTANCE.getNpc(id);
            if (npc != null) {
                npc.getBrain().setRanchControlled(false);
            }
        }
    }

    private static void status(RanchPen pen, String key) {
        if (key.equals(pen.statusKey())) {
            return;
        }
        RanchPenManager.INSTANCE.update(pen.withStatus(key));
    }

    private static final class PenRuntime {
        private BuildingRecord building;
        private AnimalSpec spec;
        private NpcData keeper;
        private EntityType<?> type;
        private AABB bounds;
        private Mob base;
        private long lastValidate;
        private long lastScan;
        private long lastFeed;
        private long lastCollect;
        private long nextTick;

        private void clear() {
            building = null;
            spec = null;
            keeper = null;
            type = null;
            bounds = null;
            base = null;
        }
    }
}
