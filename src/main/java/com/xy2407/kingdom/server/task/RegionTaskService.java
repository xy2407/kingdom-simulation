package com.xy2407.kingdom.server.task;

import java.util.*;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingLevelRules;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.network.builder.BuilderBoardDataPacket;
import com.xy2407.kingdom.common.network.builder.RegionTaskCreatePacket;
import com.xy2407.kingdom.common.npc.NpcBrain;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.RegionTaskSchema;
import com.xy2407.kingdom.common.task.RegionTask;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.employment.EmploymentService;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 区域任务服务：拆除自顶向下填充自底向上逐格推进，雇员沿框选边缘站立挥手
 */
public final class RegionTaskService {
    public static final RegionTaskService INSTANCE = new RegionTaskService();

    public static final int MAX_VOLUME = 32768;
    public static final int PROCESS_INTERVAL = 2;
    public static final int SWING_INTERVAL = 20;
    private static final int LOAD_RETRY_DELAY_TICKS = 200;
    private static final int MAX_MATERIAL_ROWS = 16;
    private static final int DEPOSIT_RADIUS = 32;
    private static final int DEPOSIT_RETRY_TICKS = 200;

    public record Employee(UUID id, String name, String skin, int hiredDay) {
    }

    private final Map<UUID, RegionTask> tasks = new LinkedHashMap<>();
    private final Map<UUID, Employee> employees = new LinkedHashMap<>();
    private final Map<UUID, Integer> swingTimers = new HashMap<>();
    private final Map<UUID, List<BlockPos>> hutStands = new HashMap<>();
    private final Map<UUID, UUID> hutChoice = new HashMap<>();
    private final Map<UUID, BlockPos> deposits = new HashMap<>();
    private final Map<UUID, Long> depositRetry = new HashMap<>();

    private boolean loaded;
    private int loadRetryCooldown;
    private int tickCounter;

    private RegionTaskService() {
    }

    public void reset() {
        tasks.clear();
        employees.clear();
        swingTimers.clear();
        hutStands.clear();
        hutChoice.clear();
        deposits.clear();
        depositRetry.clear();
        loaded = false;
        loadRetryCooldown = 0;
        tickCounter = 0;
    }

    public void tick(MinecraftServer server) {
        if (!loaded) {
            if (loadRetryCooldown > 0) {
                loadRetryCooldown--;
            } else if (KingdomPersistence.isReady()) {
                load();
            }
            return;
        }
        tickCounter++;
        boolean swingDue = tickCounter % SWING_INTERVAL == 0;
        for (RegionTask task : List.copyOf(tasks.values())) {
            if (task.status() != RegionTask.Status.RUNNING) {
                continue;
            }
            tickTask(server, task, swingDue);
        }
        tickIdleBuilders(server);
    }

    public boolean isLoaded() {
        return loaded;
    }

    public Collection<RegionTask> allTasks() {
        return Collections.unmodifiableCollection(tasks.values());
    }

    public Collection<Employee> allEmployees() {
        return Collections.unmodifiableCollection(employees.values());
    }

    public boolean isEmployed(UUID npcId) {
        return npcId != null && employees.containsKey(npcId);
    }

    public String statusOf(UUID npcId) {
        if (!isEmployed(npcId)) {
            return "";
        }
        for (RegionTask task : tasks.values()) {
            if (task.status() == RegionTask.Status.RUNNING && task.contains(npcId)) {
                return "工作中";
            }
        }
        return "待命";
    }

    public List<BuilderBoardDataPacket.EmployeeEntry> employeeEntries() {
        List<BuilderBoardDataPacket.EmployeeEntry> list = new ArrayList<>();
        for (Employee employee : employees.values()) {
            list.add(new BuilderBoardDataPacket.EmployeeEntry(employee.id(),
                    employee.name(), employee.skin(), statusOf(employee.id())));
        }
        return list;
    }

    public List<BuilderBoardDataPacket.TaskEntry> taskEntries() {
        List<BuilderBoardDataPacket.TaskEntry> list = new ArrayList<>();
        for (RegionTask task : tasks.values()) {
            list.add(toEntry(task));
        }
        return list;
    }

    private static BuilderBoardDataPacket.TaskEntry toEntry(RegionTask task) {
        List<BuilderBoardDataPacket.NpcRef> npcs = new ArrayList<>();
        for (UUID npcId : task.assigned()) {
            NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
            if (npc != null && !npc.isDead()) {
                npcs.add(new BuilderBoardDataPacket.NpcRef(npcId, npc.getName(), npc.getSkin()));
            }
        }
        int kind = task.kind() == RegionTask.Kind.FILL
                ? BuilderBoardDataPacket.KIND_FILL : BuilderBoardDataPacket.KIND_DEMOLISH;
        List<BuilderBoardDataPacket.MatEntry> materials = new ArrayList<>();
        for (RegionTask.Material material : task.materials()) {
            materials.add(new BuilderBoardDataPacket.MatEntry(material.itemId(), material.count()));
        }
        return new BuilderBoardDataPacket.TaskEntry(task.id(), kind, titleOf(task), task.dimension(),
                task.min().getX(), task.min().getY(), task.min().getZ(),
                task.max().getX(), task.max().getY(), task.max().getZ(),
                task.status().name(), task.cursor(), task.scanLimit(), npcs, materials);
    }

    private static String titleOf(RegionTask task) {
        return task.kind() == RegionTask.Kind.FILL ? "填充区域" : "拆除区域";
    }

    public void create(ServerLevel level, ServerPlayer player, RegionTaskCreatePacket packet) {
        BlockPos min = new BlockPos(Math.min(packet.minX(), packet.maxX()),
                Math.min(packet.minY(), packet.maxY()), Math.min(packet.minZ(), packet.maxZ()));
        BlockPos max = new BlockPos(Math.max(packet.minX(), packet.maxX()),
                Math.max(packet.minY(), packet.maxY()), Math.max(packet.minZ(), packet.maxZ()));
        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1)
                * (long) (max.getZ() - min.getZ() + 1);
        if (volume > MAX_VOLUME) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "范围过大：上限 " + MAX_VOLUME + " 格，当前 " + volume + " 格"), false);
            return;
        }
        RegionTask.Kind kind = packet.kind() == RegionTaskCreatePacket.KIND_DEMOLISH
                ? RegionTask.Kind.DEMOLISH : RegionTask.Kind.FILL;
        String fillItem = "minecraft:dirt";
        int total;
        List<RegionTask.Material> materials;
        if (kind == RegionTask.Kind.FILL) {
            total = 0;
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                if (level.isLoaded(pos) && isReplaceable(level.getBlockState(pos))) {
                    total++;
                }
            }
            materials = List.of(new RegionTask.Material(fillItem, total));
        } else {
            total = 0;
            Map<String, Integer> histogram = new HashMap<>();
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                if (!level.isLoaded(pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }
                total++;
                histogram.merge(nameOf(state), 1, Integer::sum);
            }
            materials = new ArrayList<>();
            histogram.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(MAX_MATERIAL_ROWS)
                    .forEach(entry -> materials.add(new RegionTask.Material(entry.getKey(), entry.getValue())));
        }
        if (total <= 0) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    kind == RegionTask.Kind.FILL ? "范围内没有可填充的空格" : "范围内没有可拆除的方块"), false);
            return;
        }
        RegionTask task = new RegionTask(UUID.randomUUID(), kind,
                level.dimension().location().toString(), min, max, fillItem,
                materials, total, DateSystem.totalDay(level));
        tasks.put(task.id(), task);
        UUID candidate = pickHireCandidate(level, task.min());
        if (candidate != null) {
            hireBySystem(candidate);
            task.assigned().add(candidate);
        } else {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "附近建筑工棚没有空闲建筑工，请在建筑工面板手动雇佣并指派"), false);
        }
        persist(task);
        ModMain.LOGGER.info("[建筑工] 新建{}任务 {} 范围 {} ~ {} 共 {} 格",
                kind == RegionTask.Kind.FILL ? "填充" : "拆除", task.id(), min, max, total);
        BuilderBoardService.broadcastAll(level.getServer());
    }

    public void applyAction(ServerPlayer player, UUID taskId, int action) {
        RegionTask task = tasks.get(taskId);
        if (task == null) {
            feedback(player, "找不到这个任务");
            return;
        }
        switch (action) {
            case com.xy2407.kingdom.common.network.builder.BuilderTaskActionPacket.ACTION_START -> start(player, task);
            case com.xy2407.kingdom.common.network.builder.BuilderTaskActionPacket.ACTION_PAUSE -> pause(task);
            case com.xy2407.kingdom.common.network.builder.BuilderTaskActionPacket.ACTION_CANCEL -> cancel(task);
            default -> feedback(player, "未知操作");
        }
        BuilderBoardService.broadcastAll(player.server);
    }

    private void start(ServerPlayer player, RegionTask task) {
        if (task.status() == RegionTask.Status.RUNNING) {
            return;
        }
        List<UUID> assigned = List.copyOf(task.assigned());
        if (assigned.isEmpty()) {
            feedback(player, "先拖一个建筑工头像到卡片里");
            return;
        }
        for (UUID npcId : assigned) {
            NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
            if (npc != null && !npc.isDead()) {
                npc.getBrain().setConstructionControlled(true);
            }
        }
        task.setStatus(RegionTask.Status.RUNNING);
        persist(task);
    }

    private void pause(RegionTask task) {
        if (task.status() != RegionTask.Status.RUNNING) {
            return;
        }
        task.setStatus(RegionTask.Status.PAUSED);
        persist(task);
    }

    public void cancel(RegionTask task) {
        releaseAll(task);
        tasks.remove(task.id());
        KingdomPersistence.delete(RegionTaskSchema.TABLE_REGION_TASK,
                RegionTaskSchema.COL_UUID, task.id().toString());
    }

    public void applyAssign(ServerPlayer player, UUID taskId, UUID npcId, boolean add) {
        RegionTask task = tasks.get(taskId);
        if (task == null) {
            feedback(player, "找不到这个任务");
            return;
        }
        NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
        if (npc == null || npc.isDead()) {
            feedback(player, "这个建筑工不存在或已死亡");
            return;
        }
        if (add) {
            if (!isEmployed(npcId)) {
                feedback(player, npc.getName() + " 还没被雇佣");
                return;
            }
            if (!task.contains(npcId)) {
                task.assigned().add(npcId);
                if (task.status() == RegionTask.Status.RUNNING) {
                    npc.getBrain().setConstructionControlled(true);
                }
            }
        } else if (task.contains(npcId)) {
            task.assigned().remove(npcId);
            release(npcId, task);
        }
        persist(task);
        BuilderBoardService.broadcastAll(player.server);
    }

    public void applyEmployee(ServerPlayer player, UUID npcId, boolean hire) {
        if (hire) {
            NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
            if (npc == null || npc.isDead()) {
                feedback(player, "这个市民不存在或已死亡");
                return;
            }
            if (isEmployed(npcId)) {
                return;
            }
            int capacity = capacityTotal();
            if (!isEmployed(npcId) && employees.size() >= capacity) {
                feedback(player, "建筑工位已满（当前工棚等级合计最多 " + capacity + " 人）");
                return;
            }
            String conflict = EmploymentService.INSTANCE.conflictAt(npc, null);
            if (conflict != null) {
                feedback(player, npc.getName() + " " + conflict + "，不能同时加入建筑工队");
                return;
            }
            employees.put(npcId, new Employee(npcId, npc.getName(), npc.getSkin(),
                    DateSystem.totalDay(player.serverLevel())));
            npc.getBrain().setConstructionControlled(true);
        } else {
            if (!isEmployed(npcId)) {
                return;
            }
            detachEmployee(npcId);
        }
        persistEmployee(npcId, hire);
        BuilderBoardService.broadcastAll(player.server);
    }

    public UUID pickHireCandidate(ServerLevel level, BlockPos near) {
        String dimension = level.dimension().location().toString();
        BuildingRecord hut = nearestBuilderHut(dimension, near);
        if (hut == null) {
            return null;
        }
        Set<UUID> busy = new java.util.HashSet<>();
        for (RegionTask task : tasks.values()) {
            if (task.status() == RegionTask.Status.RUNNING) {
                busy.addAll(task.assigned());
            }
        }
        List<UUID> idle = new ArrayList<>();
        for (UUID npcId : employees.keySet()) {
            if (busy.contains(npcId)) {
                continue;
            }
            NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
            if (npc == null || npc.isDead() || !dimension.equals(npc.getDimension())) {
                continue;
            }
            idle.add(npcId);
        }
        if (idle.isEmpty()) {
            return null;
        }
        return idle.get(level.random.nextInt(idle.size()));
    }

    private static BuildingRecord nearestBuilderHut(String dimension, BlockPos near) {
        BuildingRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BuildingRecord record : BuildingRegistry.INSTANCE.all()) {
            if (!record.dimension().equals(dimension)) {
                continue;
            }
            BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(record);
            if (meta == null || !meta.isWork(BuildingWork.BUILDER_HUT)) {
                continue;
            }
            double distance = record.min().distSqr(near);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = record;
            }
        }
        return best;
    }

    public int capacityTotal() {
        int total = 0;
        for (BuildingRecord building : BuildingRegistry.INSTANCE.all()) {
            BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
            if (meta != null && meta.isWork(BuildingWork.BUILDER_HUT)) {
                total += BuildingLevelRules.builderHutCrew(building.level());
            }
        }
        return total;
    }

    public boolean hireBySystem(UUID npcId) {
        NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
        if (npc == null || npc.isDead()) {
            return false;
        }
        if (isEmployed(npcId)) {
            return true;
        }
        employees.put(npcId, new Employee(npcId, npc.getName(), npc.getSkin(), 0));
        npc.getBrain().setConstructionControlled(true);
        persistEmployee(npcId, true);
        return true;
    }

    public void dismissBySystem(UUID npcId) {
        if (npcId == null || !isEmployed(npcId)) {
            return;
        }
        detachEmployee(npcId);
        persistEmployee(npcId, false);
    }

    private void detachEmployee(UUID npcId) {
        for (RegionTask task : List.copyOf(tasks.values())) {
            if (task.contains(npcId)) {
                task.assigned().remove(npcId);
                persist(task);
            }
        }
        employees.remove(npcId);
        hutChoice.remove(npcId);
        release(npcId, null);
    }

    public void onCitizenRemoved(UUID npcId) {
        if (npcId == null) {
            return;
        }
        if (loaded) {
            detachEmployee(npcId);
        } else {
            employees.remove(npcId);
        }
        persistEmployee(npcId, false);
        EmploymentService.INSTANCE.dismiss(null, npcId);
    }

    private void tickTask(MinecraftServer server, RegionTask task, boolean swingDue) {
        ServerLevel level = levelOf(server, task.dimension());
        if (level == null) {
            return;
        }
        moveWorkers(level, task, swingDue);
        if (task.isFinished()) {
            complete(task);
            return;
        }
        if (tickCounter % PROCESS_INTERVAL != 0) {
            return;
        }
        BlockPos pos = task.posOf(task.cursor());
        if (!level.isLoaded(pos)) {
            return;
        }
        if (task.kind() == RegionTask.Kind.FILL) {
            BlockState state = level.getBlockState(pos);
            if (!isReplaceable(state)) {
                task.advance();
                return;
            }
            if (!consumeFill(level, task)) {
                return;
            }
            Block fillBlock = BuiltInRegistries.BLOCK
                    .getOptional(ResourceLocation.parse(task.fillItem())).orElse(Blocks.DIRT);
            int flags = Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS;
            level.setBlock(pos, fillBlock.defaultBlockState(), flags);
        } else {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                task.advance();
                return;
            }
            int flags = state.getBlock() instanceof LiquidBlock
                    ? Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS
                    : Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
        }
        task.advance();
    }

    private boolean consumeFill(ServerLevel level, RegionTask task) {
        if (!com.xy2407.kingdom.common.building.BuildRequirements.required()) {
            return true;
        }
        BlockPos deposit = depositOf(level, task);
        if (deposit == null) {
            return true;
        }
        if (level.getBlockEntity(deposit) instanceof com.xy2407.kingdom.common.block.BuilderBlockEntity box) {
            return box.consume(task.fillItem(), 1);
        }
        deposits.remove(task.id());
        return true;
    }

    private BlockPos depositOf(ServerLevel level, RegionTask task) {
        BlockPos cached = deposits.get(task.id());
        if (cached != null) {
            if (level.getBlockEntity(cached)
                    instanceof com.xy2407.kingdom.common.block.BuilderBlockEntity) {
                return cached;
            }
            deposits.remove(task.id());
        }
        long now = level.getGameTime();
        Long next = depositRetry.get(task.id());
        if (next != null && now < next) {
            return null;
        }
        depositRetry.put(task.id(), now + DEPOSIT_RETRY_TICKS);
        BlockPos found = findBuilderBlock(level, task.min());
        if (found != null) {
            deposits.put(task.id(), found);
        }
        return found;
    }

    private static BlockPos findBuilderBlock(ServerLevel level, BlockPos near) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -DEPOSIT_RADIUS; dx <= DEPOSIT_RADIUS; dx++) {
            for (int dz = -DEPOSIT_RADIUS; dz <= DEPOSIT_RADIUS; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    cursor.set(near.getX() + dx, near.getY() + dy, near.getZ() + dz);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    if (!level.getBlockState(cursor).is(
                            com.xy2407.kingdom.common.block.ModBlocks.BUILDER_BLOCK.get())) {
                        continue;
                    }
                    double distance = cursor.distSqr(near);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }

    private void moveWorkers(ServerLevel level, RegionTask task, boolean swingDue) {
        List<UUID> dead = new ArrayList<>();
        List<BlockPos> stands = standsOf(task);
        int index = 0;
        for (UUID npcId : List.copyOf(task.assigned())) {
            NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
            if (npc == null || npc.isDead()) {
                dead.add(npcId);
                continue;
            }
            if (npc.getBrain().isStarving()) {
                continue;
            }
            if (!npc.getBrain().isConstructionControlled()) {
                npc.getBrain().setConstructionControlled(true);
            }
            NpcEntity entity = findEntity(level, npc);
            if (entity == null) {
                continue;
            }
            BlockPos ring = stands.get(index % stands.size());
            int surfaceY = WorldSurfaceSampler.walkableSurfaceY(level, ring.getX(), ring.getZ(),
                    WorldSurfaceSampler.NO_WALKABLE);
            if (surfaceY == WorldSurfaceSampler.NO_WALKABLE) {
                index++;
                continue;
            }
            BlockPos stand = new BlockPos(ring.getX(), surfaceY + 1, ring.getZ());
            boolean arrived = entity.tickConstructionPath(stand);
            if (arrived && swingDue) {
                entity.playSwing();
            }
            index++;
        }
        for (UUID npcId : dead) {
            task.assigned().remove(npcId);
        }
        if (!dead.isEmpty()) {
            persist(task);
        }
    }

    private void tickIdleBuilders(MinecraftServer server) {
        if (employees.isEmpty()) {
            return;
        }
        for (Employee employee : List.copyOf(employees.values())) {
            UUID npcId = employee.id();
            if (isBusy(npcId)) {
                continue;
            }
            NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
            if (npc == null || npc.isDead() || npc.getBrain().isStarving()) {
                continue;
            }
            if (!npc.getBrain().isConstructionControlled()) {
                npc.getBrain().setConstructionControlled(true);
            }
            ServerLevel level = levelOf(server, npc.getDimension());
            if (level == null) {
                continue;
            }
            NpcEntity entity = findEntity(level, npc);
            if (entity == null) {
                continue;
            }
            BlockPos stand = idleStand(level, npc, entity);
            if (stand != null) {
                entity.tickConstructionPath(stand);
            }
        }
    }

    private boolean isBusy(UUID npcId) {
        for (RegionTask task : tasks.values()) {
            if (task.status() == RegionTask.Status.RUNNING && task.contains(npcId)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos idleStand(ServerLevel level, NpcData npc, NpcEntity entity) {
        BuildingRecord hut = hutOf(level, npc, entity);
        if (hut == null) {
            return null;
        }
        List<BlockPos> stands = hutStands.computeIfAbsent(hut.id(),
                key -> standsInside(level, hut));
        if (stands.isEmpty()) {
            return null;
        }
        return stands.get(Math.floorMod(npc.getId().hashCode(), stands.size()));
    }

    private BuildingRecord hutOf(ServerLevel level, NpcData npc, NpcEntity entity) {
        UUID chosen = hutChoice.get(npc.getId());
        if (chosen != null) {
            BuildingRecord cached = BuildingRegistry.INSTANCE.byId(chosen);
            if (cached != null) {
                return cached;
            }
            hutChoice.remove(npc.getId());
        }
        BuildingRecord hut = nearestHut(level, entity);
        if (hut != null) {
            hutChoice.put(npc.getId(), hut.id());
        }
        return hut;
    }

    private static BuildingRecord nearestHut(ServerLevel level, NpcEntity entity) {
        String dimension = level.dimension().location().toString();
        BuildingRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BuildingRecord record : BuildingRegistry.INSTANCE.all()) {
            if (!dimension.equals(record.dimension())) {
                continue;
            }
            BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(record);
            if (meta == null || !meta.isWork(BuildingWork.BUILDER_HUT)) {
                continue;
            }
            double dx = entity.getX() - (record.min().getX() + record.max().getX()) * 0.5D;
            double dz = entity.getZ() - (record.min().getZ() + record.max().getZ()) * 0.5D;
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = record;
            }
        }
        return best;
    }

    private static List<BlockPos> standsInside(ServerLevel level, BuildingRecord hut) {
        List<BlockPos> stands = new ArrayList<>();
        int cx = (hut.min().getX() + hut.max().getX()) / 2;
        int cz = (hut.min().getZ() + hut.max().getZ()) / 2;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if (x < hut.min().getX() || x > hut.max().getX()
                        || z < hut.min().getZ() || z > hut.max().getZ()) {
                    continue;
                }
                BlockPos stand = findFloor(level, hut, x, z);
                if (stand != null) {
                    stands.add(stand);
                }
            }
        }
        return stands;
    }

    private static BlockPos findFloor(ServerLevel level, BuildingRecord hut, int x, int z) {
        for (int y = hut.min().getY(); y <= hut.max().getY(); y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos)) {
                return null;
            }
            if (level.getBlockState(pos).isAir()
                    && level.getBlockState(pos.above()).isAir()
                    && !level.getBlockState(pos.below()).isAir()) {
                return pos;
            }
        }
        return null;
    }

    private List<BlockPos> standsOf(RegionTask task) {
        List<BlockPos> stands = new ArrayList<>();
        int minX = task.min().getX() - 1;
        int maxX = task.max().getX() + 1;
        int minZ = task.min().getZ() - 1;
        int maxZ = task.max().getZ() + 1;
        for (int x = minX; x <= maxX; x++) {
            stands.add(new BlockPos(x, 0, minZ));
            stands.add(new BlockPos(x, 0, maxZ));
        }
        for (int z = minZ + 1; z <= maxZ - 1; z++) {
            stands.add(new BlockPos(minX, 0, z));
            stands.add(new BlockPos(maxX, 0, z));
        }
        return stands;
    }

    private void complete(RegionTask task) {
        releaseAll(task);
        tasks.remove(task.id());
        KingdomPersistence.delete(RegionTaskSchema.TABLE_REGION_TASK,
                RegionTaskSchema.COL_UUID, task.id().toString());
        ModMain.LOGGER.info("[建筑工] 任务完成并移除 {}", task.id());
    }

    private void releaseAll(RegionTask task) {
        for (UUID npcId : task.assigned()) {
            release(npcId, task);
        }
        task.assigned().clear();
        swingTimers.remove(task.id());
        deposits.remove(task.id());
        depositRetry.remove(task.id());
    }

    private void release(UUID npcId, RegionTask except) {
        for (RegionTask other : tasks.values()) {
            if (other != except && other.contains(npcId)
                    && other.status() == RegionTask.Status.RUNNING) {
                return;
            }
        }
        NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
        if (npc != null) {
            npc.getBrain().setConstructionControlled(isEmployed(npcId));
        }
    }

    private static NpcEntity findEntity(ServerLevel level, NpcData npc) {
        UUID entityUuid = npc.getEntityUuid();
        if (entityUuid == null) {
            return null;
        }
        return level.getEntity(entityUuid) instanceof NpcEntity entity && entity.isAlive() ? entity : null;
    }

    private static boolean isReplaceable(BlockState state) {
        return state.isAir() || state.canBeReplaced();
    }

    private static String nameOf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        try {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension));
            return server.getLevel(key);
        } catch (Exception failure) {
            return null;
        }
    }

    private void feedback(ServerPlayer player, String message) {
        player.displayClientMessage(net.minecraft.network.chat.Component.literal("建筑工：" + message), false);
    }

    private void persist(RegionTask task) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(RegionTaskSchema.COL_UUID, task.id().toString());
        values.put(RegionTaskSchema.COL_KIND, task.kind().name());
        values.put(RegionTaskSchema.COL_DIMENSION, task.dimension());
        values.put(RegionTaskSchema.COL_MIN_X, task.min().getX());
        values.put(RegionTaskSchema.COL_MIN_Y, task.min().getY());
        values.put(RegionTaskSchema.COL_MIN_Z, task.min().getZ());
        values.put(RegionTaskSchema.COL_MAX_X, task.max().getX());
        values.put(RegionTaskSchema.COL_MAX_Y, task.max().getY());
        values.put(RegionTaskSchema.COL_MAX_Z, task.max().getZ());
        values.put(RegionTaskSchema.COL_STATUS, task.status().name());
        values.put(RegionTaskSchema.COL_CURSOR, task.cursor());
        values.put(RegionTaskSchema.COL_TOTAL, task.total());
        values.put(RegionTaskSchema.COL_FILL_ITEM, task.fillItem());
        values.put(RegionTaskSchema.COL_ASSIGNED, encodeAssigned(task.assigned()));
        values.put(RegionTaskSchema.COL_MATERIALS, encodeMaterials(task.materials()));
        values.put(RegionTaskSchema.COL_CREATED_DAY, task.createdDay());
        values.put(RegionTaskSchema.COL_UPDATED_AT, System.currentTimeMillis());
        KingdomPersistence.submit(RegionTaskSchema.TABLE_REGION_TASK,
                RegionTaskSchema.COL_UUID, task.id().toString(), values);
    }

    private void persistEmployee(UUID npcId, boolean hire) {
        if (hire) {
            Employee employee = employees.get(npcId);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(RegionTaskSchema.COL_NPC_ID, npcId.toString());
            values.put(RegionTaskSchema.COL_NAME, employee.name());
            values.put(RegionTaskSchema.COL_SKIN, employee.skin());
            values.put(RegionTaskSchema.COL_HIRED_DAY, employee.hiredDay());
            values.put(RegionTaskSchema.COL_UPDATED_AT, System.currentTimeMillis());
            KingdomPersistence.submit(RegionTaskSchema.TABLE_EMPLOYEE,
                    RegionTaskSchema.COL_NPC_ID, npcId.toString(), values);
        } else {
            KingdomPersistence.delete(RegionTaskSchema.TABLE_EMPLOYEE,
                    RegionTaskSchema.COL_NPC_ID, npcId.toString());
        }
    }

    private static String encodeAssigned(List<UUID> assigned) {
        StringBuilder builder = new StringBuilder("[");
        for (UUID npcId : assigned) {
            if (builder.length() > 1) {
                builder.append(',');
            }
            builder.append('"').append(npcId).append('"');
        }
        return builder.append(']').toString();
    }

    private static String encodeMaterials(List<RegionTask.Material> materials) {
        StringBuilder builder = new StringBuilder("[");
        for (RegionTask.Material material : materials) {
            if (builder.length() > 1) {
                builder.append(',');
            }
            builder.append("{\"item\":\"").append(material.itemId())
                    .append("\",\"count\":").append(material.count()).append('}');
        }
        return builder.append(']').toString();
    }

    private void load() {
        try {
            List<RegionTask> loadedTasks = KingdomPersistence.querySync(connection -> {
                List<RegionTask> result = new ArrayList<>();
                try (var st = connection.createStatement();
                     var rs = st.executeQuery("SELECT " + RegionTaskSchema.columnsTask()
                             + " FROM " + RegionTaskSchema.TABLE_REGION_TASK)) {
                    while (rs.next()) {
                        result.add(readTask(rs));
                    }
                }
                return result;
            });
            List<Employee> loadedEmployees = KingdomPersistence.querySync(connection -> {
                List<Employee> result = new ArrayList<>();
                try (var st = connection.createStatement();
                     var rs = st.executeQuery("SELECT " + RegionTaskSchema.columnsEmployee()
                             + " FROM " + RegionTaskSchema.TABLE_EMPLOYEE)) {
                    while (rs.next()) {
                        result.add(new Employee(UUID.fromString(rs.getString(RegionTaskSchema.COL_NPC_ID)),
                                rs.getString(RegionTaskSchema.COL_NAME),
                                rs.getString(RegionTaskSchema.COL_SKIN),
                                rs.getInt(RegionTaskSchema.COL_HIRED_DAY)));
                    }
                }
                return result;
            });
            tasks.clear();
            for (RegionTask task : loadedTasks) {
                tasks.put(task.id(), task);
            }
            employees.clear();
            for (Employee employee : loadedEmployees) {
                employees.put(employee.id(), employee);
            }
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 个区域任务与 {} 名建筑工", tasks.size(), employees.size());
        } catch (Exception failure) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载区域任务失败", failure);
        }
    }

    private static RegionTask readTask(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID id = UUID.fromString(rs.getString(RegionTaskSchema.COL_UUID));
        RegionTask.Kind kind = RegionTask.Kind.valueOf(rs.getString(RegionTaskSchema.COL_KIND));
        String dimension = rs.getString(RegionTaskSchema.COL_DIMENSION);
        BlockPos min = new BlockPos(rs.getInt(RegionTaskSchema.COL_MIN_X),
                rs.getInt(RegionTaskSchema.COL_MIN_Y), rs.getInt(RegionTaskSchema.COL_MIN_Z));
        BlockPos max = new BlockPos(rs.getInt(RegionTaskSchema.COL_MAX_X),
                rs.getInt(RegionTaskSchema.COL_MAX_Y), rs.getInt(RegionTaskSchema.COL_MAX_Z));
        RegionTask task = new RegionTask(id, kind, dimension, min, max,
                rs.getString(RegionTaskSchema.COL_FILL_ITEM),
                decodeMaterials(rs.getString(RegionTaskSchema.COL_MATERIALS)),
                rs.getInt(RegionTaskSchema.COL_TOTAL),
                rs.getInt(RegionTaskSchema.COL_CREATED_DAY));
        task.setStatus(RegionTask.Status.valueOf(rs.getString(RegionTaskSchema.COL_STATUS)));
        for (int i = 0; i < rs.getInt(RegionTaskSchema.COL_CURSOR); i++) {
            task.advance();
        }
        task.assigned().addAll(decodeAssigned(rs.getString(RegionTaskSchema.COL_ASSIGNED)));
        return task;
    }

    private static List<UUID> decodeAssigned(String json) {
        List<UUID> list = new ArrayList<>();
        if (json == null) {
            return list;
        }
        for (String part : json.replace("[", "").replace("]", "").split(",")) {
            String value = part.replace("\"", "").trim();
            if (!value.isEmpty()) {
                try {
                    list.add(UUID.fromString(value));
                } catch (IllegalArgumentException ignored) {
                    ModMain.LOGGER.warn("[建筑工] 无效雇员编号已忽略: {}", value);
                }
            }
        }
        return list;
    }

    private static List<RegionTask.Material> decodeMaterials(String json) {
        List<RegionTask.Material> list = new ArrayList<>();
        if (json == null) {
            return list;
        }
        for (String part : json.replace("[", "").replace("]", "").split("\\},\\{")) {
            String item = between(part, "\"item\":\"", "\"");
            int count = 0;
            try {
                count = Integer.parseInt(between(part, "\"count\":", "}").trim());
            } catch (NumberFormatException ignored) {
                ModMain.LOGGER.warn("[建筑工] 无效材料数量已忽略: {}", part);
            }
            if (!item.isEmpty() && count > 0) {
                list.add(new RegionTask.Material(item, count));
            }
        }
        list.sort(Comparator.comparingInt(RegionTask.Material::count).reversed());
        return list;
    }

    private static String between(String text, String start, String end) {
        int begin = text.indexOf(start);
        if (begin < 0) {
            return "";
        }
        begin += start.length();
        int stop = text.indexOf(end, begin);
        return stop < 0 ? text.substring(begin) : text.substring(begin, stop);
    }
}
