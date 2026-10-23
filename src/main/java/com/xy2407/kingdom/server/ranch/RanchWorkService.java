package com.xy2407.kingdom.server.ranch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.area.PlotArea;
import com.xy2407.kingdom.common.area.PlotAreaKind;
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
import com.xy2407.kingdom.server.area.PlotAreaManager;
import com.xy2407.kingdom.server.building.BuildingRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Animal;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 养殖核心循环：饲养员在框选范围内取料并逐个喂食，兼管数量补充与产物回收
 */
public final class RanchWorkService {

    private static final long VALIDATE_INTERVAL = 20L;

    private static final long ENTITY_SCAN_INTERVAL = 40L;

    private static final long FEED_INTERVAL = 2400L;

    private static final long COLLECT_INTERVAL = 600L;

    private static final double KEEPER_REACH = 3.0D;

    private static final int FEED_RANGE_UP = 5;

    private static final int ADULT_FEED_MIN = 2;

    private static final int BABY_AGE = -24000;

    private static final int GROWTH_STEP = 2400;

    private static final int BABY_CHANCE_PERCENT = 30;

    private static final int SPAWN_BUDGET = 4;

    private static final String FEED_DUE = "ranch_feed_due";

    private static final String FEED_COOLDOWN = "ranch_feed_at";

    private static final int PHASE_IDLE = 0;

    private static final int PHASE_TO_BUILDING = 1;

    private static final int PHASE_TO_PEN = 2;

    private static final int PHASE_FEEDING = 3;

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
        releaseUnusedKeepers(server, activeKeepers);
    }

    public static int[] headcount(ServerLevel level, RanchPen pen) {
        int[] heads = new int[]{0, 0};
        if (level == null || pen == null) {
            return heads;
        }
        AnimalSpec spec = RanchPenManager.INSTANCE.animalOf(pen);
        BuildingRecord building = BuildingRegistry.INSTANCE.byId(pen.buildingId());
        EntityType<?> type = spec == null ? null : entityType(spec.entity());
        if (building == null || type == null) {
            return heads;
        }
        String key = penKey(pen);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, rangeOf(pen, building),
                candidate -> candidate.getType() == type && candidate.isAlive()
                        && key.equals(RanchAnimals.penOf(candidate)))) {
            heads[0]++;
            if (!isBaby(mob)) {
                heads[1]++;
            }
        }
        return heads;
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
            runtime.range = rangeOf(pen, building);
            runtime.rangeKey = "range:" + pen.id();
            keepControlled(keeper);
        }
        if (runtime.building == null || runtime.spec == null || runtime.keeper == null) {
            return;
        }
        NpcEntity keeperEntity = entityOf(level, runtime.keeper);

        if (gameTime - runtime.lastScan >= ENTITY_SCAN_INTERVAL) {
            runtime.lastScan = gameTime;
            ensureAnimals(level, pen, runtime);
            if (runtime.phase == PHASE_IDLE) {
                moveKeeper(level, runtime, keeperEntity);
            }
        }
        tickFeedRun(level, pen, runtime, keeperEntity, gameTime);
        if (gameTime - runtime.lastFeed >= FEED_INTERVAL) {
            runtime.lastFeed = gameTime;
            markFeed(level, pen, runtime, gameTime);
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

    private static AABB rangeOf(RanchPen pen, BuildingRecord building) {
        PlotArea area = PlotAreaManager.INSTANCE.byBuilding(pen.buildingId(), PlotAreaKind.RANCH);
        if (area == null || !area.hasArea()) {
            return RanchBounds.box(building);
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int groundY = Integer.MAX_VALUE;
        for (Long cell : area.cells()) {
            BlockPos pos = BlockPos.of(cell);
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
            groundY = Math.min(groundY, pos.getY());
        }
        if (minX > maxX || minZ > maxZ) {
            return RanchBounds.box(building);
        }
        return new AABB(minX, groundY, minZ,
                maxX + 1.0D, groundY + FEED_RANGE_UP + 1.0D, maxZ + 1.0D);
    }

    private static List<Mob> animalsOf(ServerLevel level, RanchPen pen, PenRuntime runtime) {
        if (runtime.type == null || runtime.range == null) {
            return List.of();
        }
        String key = penKey(pen);
        return level.getEntitiesOfClass(Mob.class, runtime.range,
                candidate -> candidate.getType() == runtime.type && candidate.isAlive()
                        && key.equals(RanchAnimals.penOf(candidate)));
    }

    private static void ensureAnimals(ServerLevel level, RanchPen pen, PenRuntime runtime) {
        List<Mob> animals = animalsOf(level, pen, runtime);
        int capacity = runtime.spec.limit();
        int missing = capacity - animals.size();
        if (missing <= 0) {
            return;
        }
        List<BlockPos> points = RanchBounds.standablePoints(level, runtime.range, runtime.rangeKey);
        if (points.isEmpty()) {
            return;
        }
        int budget = Math.min(missing, SPAWN_BUDGET);
        for (int index = 0; index < budget; index++) {
            BlockPos spot = points.get(level.random.nextInt(points.size()));
            spawnAnimal(level, spot, runtime, pen);
        }
    }

    private static void spawnAnimal(ServerLevel level, BlockPos pos, PenRuntime runtime, RanchPen pen) {
        if (!(runtime.type.create(level) instanceof Mob mob)) {
            return;
        }
        mob.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                level.random.nextFloat() * 360.0F, 0.0F);
        mob.setPersistenceRequired();
        mob.setNoAi(true);
        mob.getPersistentData().putString(RanchAnimals.PEN_KEY, penKey(pen));
        if (level.random.nextInt(100) < BABY_CHANCE_PERCENT && mob instanceof AgeableMob aged) {
            aged.setAge(BABY_AGE);
        }
        level.addFreshEntity(mob);
    }

    private static void markFeed(ServerLevel level, RanchPen pen, PenRuntime runtime, long gameTime) {
        if (runtime.phase != PHASE_IDLE) {
            return;
        }
        List<Mob> animals = animalsOf(level, pen, runtime);
        if (animals.isEmpty()) {
            status(pen, STATUS_NO_ANIMAL);
            return;
        }
        List<Mob> adults = new ArrayList<>();
        List<Mob> babies = new ArrayList<>();
        for (Mob mob : animals) {
            if (!mob.isAlive()) {
                continue;
            }
            if (mob.getPersistentData().getLong(FEED_COOLDOWN) > gameTime) {
                continue;
            }
            if (isBreedCooling(mob)) {
                continue;
            }
            if (isBaby(mob)) {
                babies.add(mob);
            } else {
                adults.add(mob);
            }
        }
        List<Mob> targets = new ArrayList<>();
        if (adults.size() >= ADULT_FEED_MIN) {
            targets.addAll(adults);
            targets.addAll(babies);
        } else if (!babies.isEmpty()) {
            targets.addAll(babies);
        } else {
            runtime.feedQueue.clear();
            status(pen, STATUS_FULL);
            return;
        }
        runtime.feedQueue.clear();
        runtime.feedIndex = 0;
        runtime.feedCarried = 0;
        runtime.phase = PHASE_TO_BUILDING;
        for (Mob mob : targets) {
            mob.getPersistentData().putBoolean(FEED_DUE, true);
            runtime.feedQueue.add(mob.getUUID());
        }
        status(pen, STATUS_GROWING);
    }

    private static void tickFeedRun(ServerLevel level, RanchPen pen, PenRuntime runtime,
                                    NpcEntity keeper, long gameTime) {
        if (runtime.phase == PHASE_IDLE || keeper == null || runtime.feedQueue.isEmpty()) {
            return;
        }
        if (runtime.phase == PHASE_TO_BUILDING) {
            BlockPos target = nearest(RanchBounds.standablePoints(level, runtime.building),
                    keeper.position());
            if (target == null) {
                finishFeed(pen, runtime, keeper);
                return;
            }
            keeper.setRanchTarget(target);
            if (keeper.blockPosition().distSqr(target) > KEEPER_REACH * KEEPER_REACH) {
                return;
            }
            int need = runtime.feedQueue.size();
            if (!runtime.spec.feed().isBlank()
                    && !consumeFeed(level, runtime.building, runtime.spec.feed(), need)) {
                clearFeed(pen, runtime, keeper);
                status(pen, STATUS_NO_FEED);
                return;
            }
            runtime.feedCarried = runtime.spec.feed().isBlank() ? 0 : need;
            runtime.phase = PHASE_TO_PEN;
            return;
        }
        if (runtime.phase == PHASE_TO_PEN) {
            BlockPos target = nearest(RanchBounds.standablePoints(level, runtime.range, runtime.rangeKey),
                    keeper.position());
            if (target == null) {
                finishFeed(pen, runtime, keeper);
                return;
            }
            keeper.setRanchTarget(target);
            if (keeper.blockPosition().distSqr(target) <= KEEPER_REACH * KEEPER_REACH) {
                runtime.phase = PHASE_FEEDING;
            }
            return;
        }
        feedNext(level, pen, runtime, keeper, gameTime);
    }

    private static void feedNext(ServerLevel level, RanchPen pen, PenRuntime runtime,
                                 NpcEntity keeper, long gameTime) {
        while (runtime.feedIndex < runtime.feedQueue.size()) {
            UUID id = runtime.feedQueue.get(runtime.feedIndex);
            Entity entity = level.getEntity(id);
            if (!(entity instanceof Mob mob) || !mob.isAlive()
                    || !mob.getPersistentData().getBoolean(FEED_DUE)) {
                runtime.feedIndex++;
                continue;
            }
            if (keeper.blockPosition().distSqr(mob.blockPosition()) > KEEPER_REACH * KEEPER_REACH) {
                keeper.setRanchTarget(mob.blockPosition());
                return;
            }
            feedOne(level, runtime, keeper, mob, gameTime);
            runtime.feedIndex++;
            if (runtime.feedCarried > 0) {
                runtime.feedCarried--;
            }
            return;
        }
        finishFeed(pen, runtime, keeper);
    }

    private static void feedOne(ServerLevel level, PenRuntime runtime, NpcEntity keeper,
                                Mob mob, long gameTime) {
        mob.getPersistentData().remove(FEED_DUE);
        mob.getPersistentData().putLong(FEED_COOLDOWN, gameTime + FEED_INTERVAL);
        if (mob instanceof AgeableMob aged && aged.getAge() < 0) {
            aged.setAge(Math.min(0, aged.getAge() + GROWTH_STEP));
        } else if (mob instanceof Animal animal) {
            animal.setInLove(null);
        }
        keeper.setHeldItem(feedOf(runtime.spec));
        keeper.playSwing();
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                mob.getX(), mob.getY() + 0.8D, mob.getZ(),
                6, 0.3D, 0.3D, 0.3D, 0.0D);
        level.playSound(null, mob.blockPosition(), SoundEvents.GENERIC_EAT,
                SoundSource.NEUTRAL, 0.6F, 1.0F);
    }

    private static void finishFeed(RanchPen pen, PenRuntime runtime, NpcEntity keeper) {
        runtime.feedQueue.clear();
        runtime.feedIndex = 0;
        runtime.feedCarried = 0;
        runtime.phase = PHASE_IDLE;
        if (keeper != null) {
            keeper.setHeldItem(ItemStack.EMPTY);
        }
        status(pen, STATUS_GROWING);
    }

    private static void clearFeed(RanchPen pen, PenRuntime runtime, NpcEntity keeper) {
        for (UUID id : runtime.feedQueue) {
            if (keeper != null && keeper.level() instanceof ServerLevel level) {
                Entity entity = level.getEntity(id);
                if (entity instanceof Mob mob) {
                    mob.getPersistentData().remove(FEED_DUE);
                }
            }
        }
        finishFeed(pen, runtime, keeper);
    }

    private static void produce(ServerLevel level, RanchPen pen, PenRuntime runtime,
                                NpcEntity keeper, boolean collecting) {
        List<Mob> animals = animalsOf(level, pen, runtime);
        int adults = 0;
        for (Mob mob : animals) {
            if (mob.isAlive() && !isBaby(mob)) {
                adults++;
            }
        }
        if (adults <= 0 || runtime.spec.output().isBlank()) {
            return;
        }
        if (collecting) {
            deposit(keeper, runtime.spec.output(), Math.max(1, adults / 2));
            status(pen, STATUS_COLLECTING);
            return;
        }
        for (Mob mob : animals) {
            if (mob.isAlive() && !isBaby(mob)) {
                mob.discard();
                deposit(keeper, runtime.spec.output(), 1);
                status(pen, STATUS_GROWING);
                return;
            }
        }
    }

    private static void moveKeeper(ServerLevel level, PenRuntime runtime, NpcEntity keeper) {
        if (keeper == null) {
            return;
        }
        BlockPos target = nearest(RanchBounds.standablePoints(level, runtime.range, runtime.rangeKey),
                keeper.position());
        if (target == null) {
            return;
        }
        keeper.setRanchTarget(target);
        if (keeper.blockPosition().distSqr(target) <= KEEPER_REACH * KEEPER_REACH) {
            keeper.setHeldItem(feedOf(runtime.spec));
            keeper.playSwing();
        }
    }

    private static boolean isBaby(Mob mob) {
        return mob instanceof AgeableMob aged && aged.getAge() < 0;
    }

    private static boolean isBreedCooling(Mob mob) {
        if (mob instanceof AgeableMob aged && aged.getAge() > 0) {
            return true;
        }
        return mob instanceof Animal animal && animal.isInLove();
    }

    private static ItemStack feedOf(AnimalSpec spec) {
        ResourceLocation id = spec == null ? null : ResourceLocation.tryParse(spec.feed());
        if (id == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
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
        if (countFeed(level, building, item) < amount) {
            return false;
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

    private static int countFeed(ServerLevel level, BuildingRecord building, Item item) {
        int total = 0;
        for (Container container : RanchBounds.containers(level, building)) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && stack.is(item)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

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

    private static void releaseUnusedKeepers(MinecraftServer server, Set<UUID> activeKeepers) {
        for (UUID id : new HashSet<>(CONTROLLED_KEEPERS)) {
            if (activeKeepers.contains(id)) {
                continue;
            }
            CONTROLLED_KEEPERS.remove(id);
            NpcData npc = NpcManager.INSTANCE.getNpc(id);
            if (npc == null) {
                continue;
            }
            npc.getBrain().setRanchControlled(false);
            ServerLevel level = levelOf(server, npc.getDimension());
            NpcEntity entity = level == null ? null : entityOf(level, npc);
            if (entity != null) {
                entity.setHeldItem(ItemStack.EMPTY);
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
        private AABB range;
        private String rangeKey;
        private int phase = PHASE_IDLE;
        private final List<UUID> feedQueue = new ArrayList<>();
        private int feedIndex;
        private int feedCarried;
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
            range = null;
            rangeKey = null;
            phase = PHASE_IDLE;
            feedQueue.clear();
            feedIndex = 0;
            feedCarried = 0;
        }
    }
}
