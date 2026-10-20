package com.xy2407.kingdom.common.npc;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.farm.CropCatalog;
import com.xy2407.kingdom.common.farm.FarmPlot;
import com.xy2407.kingdom.common.farm.FarmRegion;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;
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
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 农活执行：服务端每 1000 tick 巡检一次农田表算出待办，雇员按状态机领活开垦、种植、收割。
 */
public final class NpcFarmService {

    private static final int MAX_RESUME_RETRIES = 3;
    private static final int ACTION_INTERVAL_TICKS = 20;
    private static final double ARRIVE_SQR = 2.25D;
    private static final int AREA_LIMIT = 4096;

    private static final Map<UUID, Work> WORKS = new ConcurrentHashMap<>();
    private static final Set<UUID> ASSIGNED = new HashSet<>();
    private static final Map<UUID, UUID> WORKER_PLOT = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<Task>> QUEUES = new ConcurrentHashMap<>();

    enum Action { TILL, PLANT, HARVEST }

    private record Task(BlockPos pos, Action action) {
    }

    private static final class Work {
        private BlockPos target;
        private Action action;
        private int retries;
        private int cooldown;
        private int resumeCooldown;
        private boolean confirmed;
        private final Deque<Task> route = new ArrayDeque<>();
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
            List<UUID> workers = plot.workerIds();
            if (!workers.isEmpty() && !tasks.isEmpty()) {
                int per = Math.max(1, tasks.size() / workers.size());
                for (UUID id : workers) {
                    Work work = WORKS.computeIfAbsent(id, key -> new Work());
                    if (work.target != null || !work.route.isEmpty()) {
                        continue;
                    }
                    work.confirmed = false;
                    for (int i = 0; i < per && !tasks.isEmpty(); i++) {
                        work.route.add(tasks.poll());
                    }
                }
            }
            QUEUES.put(plot.id(), tasks);
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
        Deque<Task> tills = new ArrayDeque<>();
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
                    if (FarmActionExecutor.isMature(above)) {
                        harvests.add(new Task(soil, Action.HARVEST));
                        occupied++;
                        continue;
                    }
                    if (!above.isAir()) {
                        occupied++;
                        continue;
                    }
                    BlockState soilState = level.getBlockState(soil);
                    if (isPlantable(soilState)) {
                        if (soilState.is(BlockTags.DIRT)) {
                            tills.add(new Task(soil, Action.TILL));
                        } else {
                            plants.add(new Task(soil, Action.PLANT));
                        }
                    }
                }
            }
        }
        Deque<Task> tasks = new ArrayDeque<>();
        int capacity = Math.max(plot.areaBlocks(), plot.slots());
        int room = Math.max(0, capacity - occupied);
        while (room > 0 && !tills.isEmpty()) {
            tasks.add(tills.poll());
            room--;
        }
        while (room > 0 && !plants.isEmpty()) {
            tasks.add(plants.poll());
            room--;
        }
        tasks.addAll(harvests);
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
        work.resumeCooldown = 0;
        work.confirmed = false;
        npc.setFarmTarget(task.pos());
    }

    public static NpcDetail detailOf(UUID npcId) {
        Work work = WORKS.get(npcId);
        if (work == null) {
            return NpcDetail.FARMING;
        }
        if (work.target != null) {
            return detailOf(work.action);
        }
        if (!work.route.isEmpty()) {
            return detailOf(work.route.peek().action());
        }
        if (work.confirmed) {
            return NpcDetail.FARMING;
        }
        UUID plotId = WORKER_PLOT.get(npcId);
        Deque<Task> queue = plotId == null ? null : QUEUES.get(plotId);
        Task head = queue == null ? null : queue.peek();
        if (head != null) {
            return detailOf(head.action());
        }
        if (work.action != null) {
            return detailOf(work.action);
        }
        return NpcDetail.FARMING;
    }

    private static NpcDetail detailOf(Action action) {
        return switch (action) {
            case HARVEST -> NpcDetail.HARVESTING;
            case TILL -> NpcDetail.FARMING;
            case PLANT -> NpcDetail.PLANTING;
        };
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
        Work work = WORKS.computeIfAbsent(data.getId(), id -> new Work());
        if (work.cooldown > 0) {
            work.cooldown--;
            return;
        }
        if (work.target == null && !work.route.isEmpty()) {
            take(npc, data, work.route.poll());
            return;
        }
        if (work.target != null) {
            if (near(npc, work.target)) {
                FarmActionExecutor.apply(level, plot, work.action, work.target, npc);
                work.target = null;
                work.retries = 0;
                work.resumeCooldown = 0;
                work.cooldown = ACTION_INTERVAL_TICKS;
                npc.clearFarmTarget();
            } else if (npc.isTravelling()) {
                npc.tickTravelPath(work.target);
            } else {
                resumeOrDrop(npc, work);
            }
            return;
        }
        Deque<Task> queue = QUEUES.get(plot.id());
        Task next = queue == null ? null : queue.poll();
        if (next == null && !work.confirmed) {
            Deque<Task> rebuilt = collect(level, plot);
            QUEUES.put(plot.id(), rebuilt);
            next = rebuilt.poll();
            if (next == null) {
                work.confirmed = true;
            }
        }
        if (next != null) {
            take(npc, data, next);
            return;
        }
        npc.haltFarm();
    }

    private static void resumeOrDrop(NpcEntity npc, Work work) {
        if (work.resumeCooldown > 0) {
            work.resumeCooldown--;
            return;
        }
        if (work.retries >= MAX_RESUME_RETRIES) {
            work.target = null;
            work.retries = 0;
            return;
        }
        work.retries++;
        work.resumeCooldown = 20;
        npc.setFarmTarget(work.target);
    }

    private static FarmRegion regionOf(FarmPlot plot, int index) {
        if (index < 0) {
            return plot.hasCore()
                    ? new FarmRegion(plot.min().getX(), plot.min().getZ(), plot.max().getX(), plot.max().getZ())
                    : null;
        }
        return plot.extra().get(index);
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

    private static FarmPlot plotOf(UUID npcId) {
        UUID plotId = npcId == null ? null : WORKER_PLOT.get(npcId);
        return plotId == null ? null : FarmPlotManager.INSTANCE.byId(plotId);
    }

    private static boolean near(NpcEntity npc, BlockPos target) {
        double dx = npc.getX() - (target.getX() + 0.5D);
        double dz = npc.getZ() - (target.getZ() + 0.5D);
        double dy = Math.abs(Math.floor(npc.getY() + 0.5D) - target.getY());
        return dx * dx + dz * dz <= ARRIVE_SQR && dy <= 1.0D;
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
