package com.xy2407.kingdom.common.npc;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.farm.CropCatalog;
import com.xy2407.kingdom.common.farm.FarmPlot;
import com.xy2407.kingdom.common.farm.FarmRegion;
import com.xy2407.kingdom.common.path.SnapshotRepository;
import com.xy2407.kingdom.server.farm.FarmLandRegistry;
import com.xy2407.kingdom.server.farm.FarmPlotManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 农活执行：服务端每 1000 tick 巡检一次农田表算出待办，雇员按状态机领活开垦、种植、收割。
 */
public final class NpcFarmService {

    private static final int MAX_RESUME_RETRIES = 3;
    private static final int ACTION_INTERVAL_TICKS = 20;
    private static final double ARRIVE_SQR = 6.25D;
    private static final int AREA_LIMIT = 4096;

    private static final Map<UUID, Work> WORKS = new ConcurrentHashMap<>();
    private static final Set<UUID> ASSIGNED = new HashSet<>();
    private static final Map<UUID, UUID> WORKER_PLOT = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<Task>> QUEUES = new ConcurrentHashMap<>();

    private enum Action { PLANT, HARVEST }

    private record Task(BlockPos pos, Action action) {
    }

    private static final class Work {
        private BlockPos target;
        private Action action = Action.PLANT;
        private int retries;
        private int cooldown;
    }

    private NpcFarmService() {
    }

    public static void reset() {
        WORKS.clear();
        ASSIGNED.clear();
        WORKER_PLOT.clear();
        QUEUES.clear();
    }

    public static void survey(MinecraftServer server) {
        for (FarmPlot plot : FarmPlotManager.INSTANCE.all()) {
            if (plot.cropId().isBlank() || !plot.hasArea()) {
                QUEUES.remove(plot.id());
                continue;
            }
            ServerLevel level = levelOf(server, plot);
            if (level == null) {
                continue;
            }
            Deque<Task> tasks = collect(level, plot);
            QUEUES.put(plot.id(), tasks);
            for (UUID id : plot.workerIds()) {
                if (tasks.isEmpty()) {
                    break;
                }
                NpcData data = NpcManager.INSTANCE.getNpc(id);
                if (data == null || data.isDead() || data.getBrain().isStarving()) {
                    continue;
                }
                Work held = WORKS.get(id);
                if (held != null && held.target != null) {
                    continue;
                }
                UUID entityId = data.getEntityUuid();
                if (entityId != null && level.getEntity(entityId) instanceof NpcEntity npc) {
                    take(npc, data, tasks.poll());
                }
            }
        }
    }

    private static ServerLevel levelOf(MinecraftServer server, FarmPlot plot) {
        ResourceLocation id = ResourceLocation.tryParse(plot.dimension());
        if (server == null || id == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    private static Deque<Task> collect(ServerLevel level, FarmPlot plot) {
        Deque<Task> harvests = new ArrayDeque<>();
        Deque<Task> plants = new ArrayDeque<>();
        Set<Long> held = heldCells(plot);
        int scanned = 0;
        int occupied = 0;
        int groundY = plot.min().getY();
        scan:
        for (int index = -1; index < plot.extra().size(); index++) {
            FarmRegion box = regionOf(plot, index);
            if (box == null) {
                continue;
            }
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    if (++scanned > AREA_LIMIT) {
                        break scan;
                    }
                    BlockPos soil = resolveSoil(level, x, groundY, z);
                    if (soil == null) {
                        continue;
                    }
                    if (held.contains(soil.asLong())) {
                        occupied++;
                        continue;
                    }
                    BlockState above = level.getBlockState(soil.above());
                    if (isMature(above)) {
                        harvests.add(new Task(soil, Action.HARVEST));
                        occupied++;
                        continue;
                    }
                    if (!above.isAir()) {
                        occupied++;
                        continue;
                    }
                    if (isPlantable(level.getBlockState(soil))) {
                        plants.add(new Task(soil, Action.PLANT));
                    }
                }
            }
        }
        Deque<Task> tasks = new ArrayDeque<>(harvests);
        for (int room = Math.max(0, plot.slots() - occupied); room > 0 && !plants.isEmpty(); room--) {
            tasks.add(plants.poll());
        }
        return tasks;
    }

    private static Set<Long> heldCells(FarmPlot plot) {
        Set<Long> held = new HashSet<>();
        for (UUID id : plot.workerIds()) {
            Work work = WORKS.get(id);
            if (work != null && work.target != null) {
                held.add(work.target.asLong());
            }
        }
        return held;
    }

    private static void take(NpcEntity npc, NpcData data, Task task) {
        Work work = WORKS.computeIfAbsent(data.getId(), id -> new Work());
        work.target = task.pos();
        work.action = task.action();
        work.retries = 0;
        data.getBrain().setFarmDetail(task.action() == Action.HARVEST ? NpcDetail.HARVESTING : NpcDetail.PLANTING);
        npc.setFarmTarget(task.pos());
    }

    public static void assignWorkers() {
        Set<UUID> wanted = new HashSet<>();
        WORKER_PLOT.clear();
        for (FarmPlot plot : FarmPlotManager.INSTANCE.all()) {
            if (plot.cropId().isBlank() || !plot.hasArea()) {
                continue;
            }
            for (UUID worker : plot.workerIds()) {
                NpcData npc = NpcManager.INSTANCE.getNpc(worker);
                if (npc == null || npc.isDead()) {
                    continue;
                }
                WORKER_PLOT.put(worker, plot.id());
                if (!npc.getBrain().isStarving()) {
                    wanted.add(worker);
                }
            }
        }
        for (UUID id : new ArrayList<>(ASSIGNED)) {
            if (wanted.contains(id)) {
                continue;
            }
            ASSIGNED.remove(id);
            if (!WORKER_PLOT.containsKey(id)) {
                WORKS.remove(id);
            }
            NpcData npc = NpcManager.INSTANCE.getNpc(id);
            if (npc != null) {
                npc.getBrain().setFarmControlled(false);
            }
        }
        for (UUID id : wanted) {
            if (ASSIGNED.contains(id)) {
                continue;
            }
            NpcData npc = NpcManager.INSTANCE.getNpc(id);
            if (npc == null || npc.isDead()) {
                continue;
            }
            NpcBrain brain = npc.getBrain();
            if (brain.isRtsControlled() || brain.isConstructionControlled()) {
                continue;
            }
            ASSIGNED.add(id);
            brain.setFarmControlled(true);
        }
    }

    public static void tick(NpcEntity npc, NpcData data) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return;
        }
        FarmPlot plot = plotOf(data.getId());
        if (plot == null) {
            npc.haltFarm();
            return;
        }
        NpcBrain brain = data.getBrain();
        Work work = WORKS.computeIfAbsent(data.getId(), id -> new Work());
        if (work.cooldown > 0) {
            work.cooldown--;
            return;
        }
        if (work.target != null) {
            if (near(npc, work.target)) {
                apply(level, plot, work, npc);
                work.target = null;
                work.retries = 0;
                work.cooldown = ACTION_INTERVAL_TICKS;
            } else if (!npc.getNavigation().isInProgress()) {
                resumeOrDrop(npc, work);
            }
            return;
        }
        Deque<Task> queue = QUEUES.get(plot.id());
        Task next = queue == null ? null : queue.poll();
        if (next == null) {
            brain.setFarmDetail(NpcDetail.FARMING);
            npc.haltFarm();
            return;
        }
        take(npc, data, next);
    }

    private static void resumeOrDrop(NpcEntity npc, Work work) {
        if (work.retries >= MAX_RESUME_RETRIES) {
            work.target = null;
            work.retries = 0;
            return;
        }
        work.retries++;
        npc.setFarmTarget(work.target);
    }

    private static void apply(ServerLevel level, FarmPlot plot, Work work, NpcEntity npc) {
        BlockPos soil = work.target;
        BlockPos cropPos = soil.above();
        BlockState cropState = level.getBlockState(cropPos);
        if (work.action == Action.HARVEST) {
            if (!isMature(cropState)) {
                return;
            }
            harvest(level, plot, npc, soil, cropPos, cropState);
            return;
        }
        plant(level, plot, soil, cropPos, cropState);
    }

    private static void harvest(ServerLevel level, FarmPlot plot, NpcEntity npc,
                                BlockPos soil, BlockPos cropPos, BlockState cropState) {
        List<ItemStack> drops = Block.getDrops(cropState, level, cropPos, null, npc, ItemStack.EMPTY);
        level.setBlock(cropPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        markSnapshot(level, cropPos);
        hold(level, cropPos, npc, drops);
        plant(level, plot, soil, cropPos, level.getBlockState(cropPos));
    }

    private static void hold(ServerLevel level, BlockPos pos, NpcEntity npc, List<ItemStack> drops) {
        NpcInventory inventory = npc == null ? null : npc.getNpcInventory();
        for (ItemStack drop : drops) {
            if (drop == null || drop.isEmpty()) {
                continue;
            }
            ItemStack remaining = drop.copy();
            if (inventory != null) {
                for (int slot = 0; slot < inventory.getSlots() && !remaining.isEmpty(); slot++) {
                    remaining = inventory.insertItem(slot, remaining, false);
                }
            }
            if (!remaining.isEmpty()) {
                Block.popResource(level, pos, remaining);
            }
        }
    }

    private static void plant(ServerLevel level, FarmPlot plot, BlockPos soil, BlockPos cropPos,
                              BlockState cropState) {
        if (!cropState.isAir()) {
            return;
        }
        Block crop = cropBlock(plot.cropId());
        if (crop == null) {
            return;
        }
        BlockState planted = crop.defaultBlockState();
        if (!planted.canSurvive(level, cropPos)) {
            if (!level.getBlockState(soil).is(BlockTags.DIRT)) {
                return;
            }
            level.setBlock(soil, Blocks.FARMLAND.defaultBlockState()
                    .setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), Block.UPDATE_ALL);
            FarmLandRegistry.mark(level, soil, DateSystem.totalDay(level));
            markSnapshot(level, soil);
            if (!planted.canSurvive(level, cropPos)) {
                return;
            }
        }
        level.setBlock(cropPos, planted, Block.UPDATE_ALL);
        markSnapshot(level, cropPos);
    }

    private static FarmRegion regionOf(FarmPlot plot, int index) {
        if (index < 0) {
            return plot.hasCore()
                    ? new FarmRegion(plot.min().getX(), plot.min().getZ(), plot.max().getX(), plot.max().getZ())
                    : null;
        }
        return plot.extra().get(index);
    }

    private static boolean isMature(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty age && "age".equals(property.getName())) {
                return state.getValue(age) >= Collections.max(age.getPossibleValues());
            }
        }
        return false;
    }

    private static boolean isFarmland(BlockState state) {
        return state.is(Blocks.FARMLAND);
    }

    private static boolean isPlantable(BlockState state) {
        return isFarmland(state) || state.is(BlockTags.DIRT);
    }

    private static BlockPos resolveSoil(ServerLevel level, int x, int y, int z) {
        BlockPos recorded = new BlockPos(x, y, z);
        if (!level.isLoaded(recorded)) {
            return null;
        }
        for (int dy = 0; dy >= -3; dy--) {
            BlockPos pos = new BlockPos(x, y + dy, z);
            if (isPlantable(level.getBlockState(pos))) {
                return pos;
            }
        }
        return recorded;
    }

    private static Block cropBlock(String cropId) {
        if (cropId == null || cropId.isBlank()) {
            return null;
        }
        Block fromCatalog = resolvePlantBlock(CropCatalog.plantBlockId(cropId));
        if (fromCatalog != null) {
            return fromCatalog;
        }
        ResourceLocation key = ResourceLocation.tryParse(cropId);
        if (key != null) {
            if (BuiltInRegistries.BLOCK.containsKey(key)) {
                Block block = BuiltInRegistries.BLOCK.get(key);
                if (block != null && block != Blocks.AIR && CropCatalog.isCropPlant(block)) {
                    return block;
                }
            }
            if (BuiltInRegistries.ITEM.containsKey(key)) {
                Item item = BuiltInRegistries.ITEM.get(key);
                if (item instanceof BlockItem blockItem && CropCatalog.isCropPlant(blockItem.getBlock())) {
                    return blockItem.getBlock();
                }
            }
        }
        return legacyCropBlock(cropId);
    }

    private static Block resolvePlantBlock(String plantBlockId) {
        if (plantBlockId == null || plantBlockId.isBlank()) {
            return null;
        }
        ResourceLocation key = ResourceLocation.tryParse(plantBlockId);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(key);
        return block == null || block == Blocks.AIR ? null : block;
    }

    private static Block legacyCropBlock(String cropId) {
        return switch (cropId) {
            case "minecraft:wheat", "minecraft:wheat_seeds" -> Blocks.WHEAT;
            case "minecraft:carrot" -> Blocks.CARROTS;
            case "minecraft:potato" -> Blocks.POTATOES;
            case "minecraft:beetroot", "minecraft:beetroot_seeds" -> Blocks.BEETROOTS;
            case "minecraft:pumpkin", "minecraft:pumpkin_seeds" -> Blocks.PUMPKIN_STEM;
            case "minecraft:melon", "minecraft:melon_seeds" -> Blocks.MELON_STEM;
            case "minecraft:torchflower_seeds" -> Blocks.TORCHFLOWER_CROP;
            case "minecraft:pitcher_pod" -> Blocks.PITCHER_CROP;
            case "minecraft:sugar_cane" -> Blocks.SUGAR_CANE;
            case "minecraft:sweet_berries" -> Blocks.SWEET_BERRY_BUSH;
            case "minecraft:bamboo" -> Blocks.BAMBOO;
            case "minecraft:nether_wart" -> Blocks.NETHER_WART;
            default -> null;
        };
    }

    private static FarmPlot plotOf(UUID npcId) {
        UUID plotId = npcId == null ? null : WORKER_PLOT.get(npcId);
        return plotId == null ? null : FarmPlotManager.INSTANCE.byId(plotId);
    }

    private static boolean near(NpcEntity npc, BlockPos target) {
        double dx = npc.getX() - (target.getX() + 0.5D);
        double dz = npc.getZ() - (target.getZ() + 0.5D);
        return dx * dx + dz * dz <= ARRIVE_SQR;
    }

    private static void markSnapshot(ServerLevel level, BlockPos pos) {
        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository != null) {
            repository.markDirty(level, pos);
        }
    }

    public static List<String> debugReport(ServerLevel level) {
        List<String> lines = new ArrayList<>();
        for (FarmPlot plot : FarmPlotManager.INSTANCE.all()) {
            Deque<Task> queue = QUEUES.get(plot.id());
            lines.add("农田 " + plot.id().toString().substring(0, 8)
                    + " 作物=" + (plot.cropId().isBlank() ? "未选" : plot.cropId())
                    + " 格数=" + plot.slots()
                    + " 地=" + (plot.hasArea() ? plot.areaBlocks() + "格" : "未划")
                    + " 待办=" + (queue == null ? "未巡检" : String.valueOf(queue.size())));
            BlockPos sample = plot.hasCore()
                    ? plot.min()
                    : (plot.extra().isEmpty() ? null
                            : new BlockPos(plot.extra().get(0).minX(), plot.min().getY(),
                                    plot.extra().get(0).minZ()));
            if (level != null && sample != null && level.isLoaded(sample)) {
                BlockPos soil = resolveSoil(level, sample.getX(), sample.getY(), sample.getZ());
                lines.add("   采样 " + sample.toShortString()
                        + " 该格=" + level.getBlockState(sample).getBlock().getName().getString()
                        + " 上方=" + level.getBlockState(sample.above()).getBlock().getName().getString()
                        + " 土壤=" + (soil == null ? "无"
                        : soil.toShortString() + "/" + level.getBlockState(soil).getBlock().getName().getString()));
            }
            for (UUID id : plot.workerIds()) {
                NpcData npc = NpcManager.INSTANCE.getNpc(id);
                if (npc == null) {
                    lines.add("   打理人 " + id.toString().substring(0, 8) + " 不在内存");
                    continue;
                }
                Work work = WORKS.get(id);
                lines.add("   打理人 " + npc.getName()
                        + " 饥饿=" + npc.getBrain().isStarving()
                        + " 农活受控=" + npc.getBrain().isFarmControlled()
                        + " 明细=" + npc.getBrain().getDetail()
                        + " 排班=" + NpcSchedule.subState(npc.getShift(), npc.getDayTime())
                        + " 时刻=" + npc.getDayTime()
                        + " 目标=" + (work == null || work.target == null ? "无" : work.target.toShortString()));
            }
        }
        if (lines.isEmpty()) {
            lines.add("没有任何农田");
        }
        return lines;
    }
}
