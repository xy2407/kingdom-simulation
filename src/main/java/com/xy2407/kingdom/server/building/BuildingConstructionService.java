package com.xy2407.kingdom.server.building;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.ModMain;

import com.xy2407.kingdom.common.block.BuilderBlockEntity;
import com.xy2407.kingdom.common.building.BuildPlan;
import com.xy2407.kingdom.common.building.BuildRequirements;
import com.xy2407.kingdom.common.building.BuildingBlueprints;
import com.xy2407.kingdom.common.building.BuildingTask;
import com.xy2407.kingdom.common.building.ScaffoldPlanner;
import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.npc.NpcBrain;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcSubState;
import com.xy2407.kingdom.common.network.building.BuildingSitePacket;
import com.xy2407.kingdom.common.path.NpcPathfinder;
import com.xy2407.kingdom.common.path.SnapshotRepository;
import com.xy2407.kingdom.server.path.UnitSnapshotStore;
import com.xy2407.kingdom.common.structure.StructureModel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.item.ItemStack;

/**
 * 建筑施工调度，建筑师沿脚手架分段施工并在完成后拆除脚手架。
 */
public final class BuildingConstructionService {
    public static final BuildingConstructionService INSTANCE = new BuildingConstructionService();

    private static String lastFailure;

    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int LIQUID_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS;
    private static final BlockState SCAFFOLD_STATE =
            Blocks.SCAFFOLDING.defaultBlockState().trySetValue(BlockStateProperties.BOTTOM, true);

    private final Map<UUID, BuildingTask> tasks = new ConcurrentHashMap<>();
    private final Map<Long, Set<UUID>> chunkIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Long>> taskChunks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> loadedChunks = new ConcurrentHashMap<>();
    private final Set<UUID> activeTasks = ConcurrentHashMap.newKeySet();
    private boolean loaded;

    private final Set<UUID> pausedTasks = ConcurrentHashMap.newKeySet();

    private BuildingConstructionService() {
    }

    public BuildingTask start(ServerLevel level, String blueprintName, BlockPos origin, int rotationDegrees) {
        return start(level, blueprintName, origin, rotationDegrees, null);
    }

    public BuildingTask start(ServerLevel level, String blueprintName, BlockPos origin,
                              int rotationDegrees, ServerPlayer payer) {
        BuildPlan plan = BuildingBlueprints.load(blueprintName, rotationDegrees);
        if (plan == null || origin == null) {
            ModMain.LOGGER.warn("建筑开工失败：蓝图读不到 {} @ {}", blueprintName, origin);
            return null;
        }
        List<BuildingTask.Step> sequence = workSequence(plan);
        Map<String, Integer> materials = requiredMaterials(sequence);
        BlockPos deposit = findDeposit(level, origin);
        if (BuildRequirements.required()) {
            String missing = checkDeposit(level, deposit, materials);
            if (missing != null) {
                lastFailure = missing;
                ModMain.LOGGER.warn("建筑开工失败：{}（{} @ {}）", missing, blueprintName, origin);
                return null;
            }
        }
        BuildingTask task = new BuildingTask(UUID.randomUUID(), blueprintName, level.dimension(),
                origin.immutable(), plan, sequence,
                ScaffoldPlanner.all(plan.size(), plan.layerCount()),
                null, "", 1, rotationDegrees, DateSystem.totalDay(level), materials, deposit);
        UnitSnapshotStore.INSTANCE.extendYWindow(level.dimension().location().toString(),
                origin.getY(), plan.layerCount());
        tasks.put(task.id(), task);
        indexTask(task);
        recount(level, task);
        save(task);
        broadcastSite(level, task, false);
        return task;
    }

    public void startWithFeedback(ServerLevel level, ServerPlayer player, String blueprintName,
                                  BlockPos origin, int rotationDegrees) {
        if (start(level, blueprintName, origin, rotationDegrees, player) != null) {
            return;
        }
        String reason = lastFailure != null ? lastFailure
                : failureReason(level, blueprintName, origin);
        lastFailure = null;
        String message = "建筑未开工：" + (reason == null ? "未知原因，看日志" : reason);
        player.displayClientMessage(Component.literal(message), false);
        ModMain.LOGGER.warn("{}（蓝图 {} 落点 {}）", message, blueprintName, origin);
    }

    public static String failureReason(ServerLevel level, String blueprintName, BlockPos origin) {
        if (origin == null) {
            return "落点无效";
        }
        if (BuildingBlueprints.load(blueprintName) == null) {
            return "蓝图读不到 " + blueprintName + "（要放在 游戏目录/kingdom simulation/structures 下的同名 nbt）";
        }
        return null;
    }

    private static List<BuildingTask.Step> workSequence(BuildPlan plan) {
        Vec3i size = plan.size();
        int height = plan.layerCount();
        int top = ScaffoldPlanner.scaffoldTop(height);
        int interval = ScaffoldPlanner.LAYER_INTERVAL;
        List<BlockPos> walk = ScaffoldPlanner.walkPositions(size);
        List<BlockPos> pillars = ScaffoldPlanner.pillarPositions(size);
        List<BuildingTask.Step> out = new ArrayList<>();
        for (int y0 = 0; y0 < top; y0 += interval) {
            int section = y0 / interval;
            int y1 = y0 + interval;
            if (section == 0) {
                appendRing(out, walk, 0, section, new BlockPos(walk.get(1).getX(), 0, walk.get(1).getZ()));
            }
            for (BlockPos pillar : pillars) {
                BlockPos neighbor = ringNeighborOf(walk, pillar);
                out.add(new BuildingTask.Step(
                        new BlockPos(pillar.getX(), y0 + 1, pillar.getZ()), SCAFFOLD_STATE,
                        BuildingTask.WorkKind.PILLAR, section,
                        new BlockPos(neighbor.getX(), y0 + 1, neighbor.getZ())));
                out.add(new BuildingTask.Step(
                        new BlockPos(pillar.getX(), y0 + 2, pillar.getZ()), SCAFFOLD_STATE,
                        BuildingTask.WorkKind.PILLAR, section,
                        new BlockPos(neighbor.getX(), y0 + 1, neighbor.getZ())));
                out.add(new BuildingTask.Step(
                        new BlockPos(pillar.getX(), y0 + 3, pillar.getZ()), SCAFFOLD_STATE,
                        BuildingTask.WorkKind.PILLAR, section,
                        new BlockPos(neighbor.getX(), y0 + 1, neighbor.getZ())));
            }
            appendRing(out, walk, y1, section, new BlockPos(walk.get(0).getX(), y0 + 1, walk.get(0).getZ()));
            for (int y = y0; y <= Math.min(y1, height - 1); y++) {
                for (StructureModel.BlockEntry entry : plan.layer(y)) {
                    BlockPos pos = entry.pos();
                    int standY = pos.getY() <= y0 + 2 ? y0 + 1 : y1 + 1;
                    int standX = Math.max(0, Math.min(pos.getX(), size.getX() - 1));
                    out.add(new BuildingTask.Step(pos, entry.state(), BuildingTask.WorkKind.BUILD,
                            section, new BlockPos(standX, standY, -1)));
                }
            }
        }
        return out;
    }

    private static void appendRing(List<BuildingTask.Step> out, List<BlockPos> walk, int y,
                                   int section, BlockPos firstStand) {
        BlockPos prev = null;
        for (BlockPos p : walk) {
            BlockPos pos = new BlockPos(p.getX(), y, p.getZ());
            BlockPos stand = prev != null
                    ? new BlockPos(prev.getX(), y + 1, prev.getZ())
                    : firstStand;
            out.add(new BuildingTask.Step(pos, SCAFFOLD_STATE, BuildingTask.WorkKind.RING, section, stand));
            prev = pos;
        }
    }

    private static BlockPos ringNeighborOf(List<BlockPos> walk, BlockPos pillar) {
        int index = walk.indexOf(pillar);
        int prev = index <= 0 ? walk.size() - 1 : index - 1;
        return walk.get(prev);
    }

    private void warmUpSnapshot(ServerLevel level, BlockPos origin, Vec3i size, int height, NpcData architect) {
        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository == null) {
            return;
        }
        BlockPos min = new BlockPos(origin.getX() - 8, origin.getY() - 2, origin.getZ() - 8);
        BlockPos max = new BlockPos(origin.getX() + size.getX() + 8, origin.getY() + height + 4,
                origin.getZ() + size.getZ() + 8);
        UnitSnapshotStore.INSTANCE.buildNow(level.getServer(),
                level.dimension().location().toString(), min, max);
        BlockPos from = new BlockPos((int) Math.floor(architect.getX()), (int) Math.floor(architect.getY()),
                (int) Math.floor(architect.getZ()));
        BlockPos to = origin.offset(size.getX() / 2, Math.min(height, 8), size.getZ() / 2);
        repository.requestUnits(level.dimension(), from, to, NpcPathfinder.DEFAULT_PADDING);
    }

    private NpcData pickArchitect(ServerLevel level, BlockPos origin) {
        NpcData employee = pickWorker(level, origin, true);
        return employee != null ? employee : pickWorker(level, origin, false);
    }

    private NpcData pickWorker(ServerLevel level, BlockPos origin, boolean employeesOnly) {
        NpcData best = null;
        double bestDist = Double.MAX_VALUE;
        String dimension = level.dimension().location().toString();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !dimension.equals(npc.getDimension())
                    || npc.getBrain().isConstructionControlled() || npc.getBrain().isStarving()) {
                continue;
            }
            if (employeesOnly && !com.xy2407.kingdom.server.task.RegionTaskService.INSTANCE.isEmployed(npc.getId())) {
                continue;
            }
            double dx = npc.getX() - origin.getX();
            double dy = npc.getY() - origin.getY();
            double dz = npc.getZ() - origin.getZ();
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = npc;
            }
        }
        return best;
    }

    public String assign(UUID taskId, UUID npcId) {
        BuildingTask task = taskId == null ? null : tasks.get(taskId);
        if (task == null) {
            return "建筑任务不存在";
        }
        NpcData npc = npcId == null ? null : NpcManager.INSTANCE.getNpc(npcId);
        if (npc == null || npc.isDead()) {
            return "市民不存在";
        }
        if (task.architectId() != null && !task.architectId().equals(npcId)) {
            return "该建筑已有建筑工";
        }
        task.assignArchitect(npc.getId(), npc.getName());
        npc.getBrain().setConstructionControlled(true);
        saveNow(task);
        return null;
    }

    public void unassign(UUID taskId) {
        BuildingTask task = taskId == null ? null : tasks.get(taskId);
        if (task == null) {
            return;
        }
        NpcData npc = task.architectId() == null ? null : NpcManager.INSTANCE.getNpc(task.architectId());
        if (npc != null) {
            npc.getBrain().setConstructionControlled(false);
        }
        task.assignArchitect(null, "");
        saveNow(task);
    }

    private static BlockPos findDeposit(ServerLevel level, BlockPos origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -32; dx <= 32; dx += 2) {
            for (int dz = -32; dz <= 32; dz += 2) {
                for (int dy = -8; dy <= 8; dy += 2) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    if (!(level.getBlockEntity(cursor) instanceof BuilderBlockEntity)) {
                        continue;
                    }
                    double distance = cursor.distSqr(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static String checkDeposit(ServerLevel level, BlockPos deposit,
                                       Map<String, Integer> materials) {
        if (deposit == null) {
            return "附近没有建筑工方块，无法取料";
        }
        if (!(level.getBlockEntity(deposit) instanceof BuilderBlockEntity container)) {
            return "建筑工方块的材料容器不可用";
        }
        for (Map.Entry<String, Integer> entry : materials.entrySet()) {
            int have = container.countOf(entry.getKey());
            if (have < entry.getValue()) {
                return "材料不足，缺 " + entry.getKey() + " × " + (entry.getValue() - have);
            }
        }
        return null;
    }

    private static boolean consumeAtSite(ServerLevel level, BuildingTask task, BuildingTask.Step step) {
        if (step.kind() != BuildingTask.WorkKind.BUILD) {
            return true;
        }
        BlockPos deposit = task.depositPos();
        if (deposit == null || !(level.getBlockEntity(deposit) instanceof BuilderBlockEntity container)) {
            return false;
        }
        String itemId = BuildingTask.itemIdOf(step.state());
        if (!container.consume(itemId, 1)) {
            return false;
        }
        task.markConsumed(step);
        return true;
    }

    private static Map<String, Integer> requiredMaterials(List<BuildingTask.Step> sequence) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (BuildingTask.Step step : sequence) {
            if (step.kind() != BuildingTask.WorkKind.BUILD) {
                continue;
            }
            String id = BuildingTask.itemIdOf(step.state());
            if (id.isEmpty()) {
                continue;
            }
            result.merge(id, 1, Integer::sum);
        }
        return result;
    }

    public void load(MinecraftServer server) {
        if (loaded || !com.xy2407.kingdom.common.persistence.KingdomPersistence.isReady()) {
            return;
        }
        loaded = true;
        try {
            Integer count = com.xy2407.kingdom.common.persistence.KingdomPersistence.querySync(connection -> {
                java.util.List<BuildingTask> restored = new java.util.ArrayList<>();
                try (java.sql.Statement st = connection.createStatement();
                     java.sql.ResultSet rs = st.executeQuery("SELECT "
                             + com.xy2407.kingdom.common.persistence.BuildingTaskSchema.columns()
                             + " FROM " + com.xy2407.kingdom.common.persistence.BuildingTaskSchema
                             .TABLE_BUILDING_TASK)) {
                    while (rs.next()) {
                        BuildingTask task = readTask(rs);
                        if (task != null) {
                            restored.add(task);
                        }
                    }
                }
                for (BuildingTask task : restored) {
                    tasks.put(task.id(), task);
                }
                return restored.size();
            });
            ModMain.LOGGER.info("已恢复 {} 个建筑任务", count);
        } catch (Exception exception) {
            ModMain.LOGGER.warn("建筑任务恢复失败", exception);
        }
        for (BuildingTask task : tasks.values()) {
            indexTask(task);
            ServerLevel level = server.getLevel(task.dimension());
            if (level != null) {
                recount(level, task);
            }
        }
    }

    private void recount(ServerLevel level, BuildingTask task) {
        Set<Long> keys = taskChunks.get(task.id());
        if (keys == null) {
            return;
        }
        int count = 0;
        for (Long key : keys) {
            BlockPos sample = new BlockPos(net.minecraft.world.level.ChunkPos.getX(key) << 4, 0,
                    net.minecraft.world.level.ChunkPos.getZ(key) << 4);
            if (level.isLoaded(sample)) {
                count++;
            }
        }
        loadedChunks.put(task.id(), count);
        if (count >= keys.size()) {
            activeTasks.add(task.id());
        }
    }

    private BuildingTask readTask(java.sql.ResultSet rs) throws java.sql.SQLException {
        String blueprint = rs.getString(com.xy2407.kingdom.common.persistence.BuildingTaskSchema
                .COL_BLUEPRINT);
        int rotation = rs.getInt(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ROTATION);
        BuildPlan plan = BuildingBlueprints.load(blueprint, rotation);
        if (plan == null) {
            ModMain.LOGGER.warn("建筑任务恢复跳过：蓝图读不到 {}（{}°）", blueprint, rotation);
            return null;
        }
        BlockPos origin = new BlockPos(
                rs.getInt(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ORIGIN_X),
                rs.getInt(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ORIGIN_Y),
                rs.getInt(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ORIGIN_Z));
        int depositX = rs.getInt(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_DEPOSIT_X);
        int depositY = rs.getInt(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_DEPOSIT_Y);
        int depositZ = rs.getInt(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_DEPOSIT_Z);
        BlockPos deposit = depositX == 0 && depositY == 0 && depositZ == 0
                ? null : new BlockPos(depositX, depositY, depositZ);
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension =
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(rs.getString(
                                com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_DIMENSION)));
        BuildingTask task = new BuildingTask(
                UUID.fromString(rs.getString(
                        com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ID)),
                blueprint, dimension, origin, plan, workSequence(plan),
                ScaffoldPlanner.all(plan.size(), plan.layerCount()),
                parseUuid(rs.getString(
                        com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ARCHITECT_ID)),
                rs.getString(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ARCHITECT_NAME),
                rs.getInt(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_LEVEL), rotation,
                rs.getInt(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_CREATED_DAY),
                decodeMaterials(rs.getString(
                        com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_MATERIALS)),
                deposit);
        task.setCursor(rs.getInt(
                com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_CURSOR));
        if (rs.getInt(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_PAUSED) != 0) {
            pausedTasks.add(task.id());
        }
        return task;
    }

    private void indexTask(BuildingTask task) {
        Set<Long> keys = ConcurrentHashMap.newKeySet();
        var size = task.plan().size();
        BlockPos origin = task.origin();
        int minChunkX = origin.getX() >> 4;
        int maxChunkX = (origin.getX() + size.getX() - 1) >> 4;
        int minChunkZ = origin.getZ() >> 4;
        int maxChunkZ = (origin.getZ() + size.getZ() - 1) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long key = net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ);
                keys.add(key);
                chunkIndex.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet())
                        .add(task.id());
            }
        }
        taskChunks.put(task.id(), keys);
        loadedChunks.put(task.id(), 0);
        activeTasks.remove(task.id());
    }

    private void unindexTask(UUID taskId) {
        Set<Long> keys = taskChunks.remove(taskId);
        if (keys != null) {
            for (Long key : keys) {
                Set<UUID> ids = chunkIndex.get(key);
                if (ids == null) {
                    continue;
                }
                ids.remove(taskId);
                if (ids.isEmpty()) {
                    chunkIndex.remove(key);
                }
            }
        }
        loadedChunks.remove(taskId);
        activeTasks.remove(taskId);
    }

    public void onChunkLoad(ServerLevel level, net.minecraft.world.level.ChunkPos pos) {
        if (!loaded) {
            return;
        }
        Set<UUID> ids = chunkIndex.get(pos.toLong());
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String dimension = level.dimension().location().toString();
        for (UUID id : ids) {
            BuildingTask task = tasks.get(id);
            if (task == null || !task.dimension().location().toString().equals(dimension)) {
                continue;
            }
            int count = loadedChunks.merge(id, 1, Integer::sum);
            Set<Long> keys = taskChunks.get(id);
            if (keys != null && count >= keys.size()) {
                activeTasks.add(id);
            }
        }
    }

    public void onChunkUnload(ServerLevel level, net.minecraft.world.level.ChunkPos pos) {
        if (!loaded) {
            return;
        }
        Set<UUID> ids = chunkIndex.get(pos.toLong());
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String dimension = level.dimension().location().toString();
        for (UUID id : ids) {
            BuildingTask task = tasks.get(id);
            if (task == null || !task.dimension().location().toString().equals(dimension)) {
                continue;
            }
            int count = loadedChunks.merge(id, -1, Integer::sum);
            Set<Long> keys = taskChunks.get(id);
            if (keys == null || count < keys.size()) {
                activeTasks.remove(id);
            }
        }
    }

    private void save(BuildingTask task) {
        com.xy2407.kingdom.common.persistence.KingdomPersistence.submit(
                com.xy2407.kingdom.common.persistence.BuildingTaskSchema.TABLE_BUILDING_TASK,
                com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ID,
                task.id().toString(), valuesOf(task));
    }

    private void saveNow(BuildingTask task) {
        com.xy2407.kingdom.common.persistence.KingdomPersistence.submitNow(
                com.xy2407.kingdom.common.persistence.BuildingTaskSchema.TABLE_BUILDING_TASK,
                com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ID,
                task.id().toString(), valuesOf(task));
    }

    private void deleteTask(UUID taskId) {
        com.xy2407.kingdom.common.persistence.KingdomPersistence.delete(
                com.xy2407.kingdom.common.persistence.BuildingTaskSchema.TABLE_BUILDING_TASK,
                com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ID, taskId.toString());
    }

    private Map<String, Object> valuesOf(BuildingTask task) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ID,
                task.id().toString());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_BLUEPRINT,
                task.blueprintName());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_DIMENSION,
                task.dimension().location().toString());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ORIGIN_X,
                task.origin().getX());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ORIGIN_Y,
                task.origin().getY());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ORIGIN_Z,
                task.origin().getZ());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ROTATION,
                task.rotation());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_LEVEL,
                task.level());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_CREATED_DAY,
                task.createdDay());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_CURSOR,
                task.cursor());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_TOTAL,
                task.sequence().size());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ARCHITECT_ID,
                task.architectId() == null ? "" : task.architectId().toString());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_ARCHITECT_NAME,
                task.architectName());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_PAUSED,
                pausedTasks.contains(task.id()) ? 1 : 0);
        BlockPos deposit = task.depositPos();
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_DEPOSIT_X,
                deposit == null ? 0 : deposit.getX());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_DEPOSIT_Y,
                deposit == null ? 0 : deposit.getY());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_DEPOSIT_Z,
                deposit == null ? 0 : deposit.getZ());
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_MATERIALS,
                encodeMaterials(task.materials()));
        values.put(com.xy2407.kingdom.common.persistence.BuildingTaskSchema.COL_UPDATED_AT,
                System.currentTimeMillis());
        return values;
    }

    private static String encodeMaterials(Map<String, Integer> materials) {
        StringBuilder text = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : materials.entrySet()) {
            if (!first) {
                text.append(',');
            }
            first = false;
            text.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        return text.append('}').toString();
    }

    private static Map<String, Integer> decodeMaterials(String raw) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        try {
            com.google.gson.JsonObject object = com.google.gson.JsonParser.parseString(raw)
                    .getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : object.entrySet()) {
                result.put(entry.getKey(), entry.getValue().getAsInt());
            }
        } catch (RuntimeException exception) {
            ModMain.LOGGER.warn("建筑任务材料解析失败：{}", raw);
        }
        return result;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public int activeCount() {
        return tasks.size();
    }

    public record TaskSnapshot(UUID id, String blueprintName, String dimension, BlockPos origin,
                               Vec3i size, int cursor, int total, UUID architectId,
                               String architectName, boolean paused, Map<String, Integer> materials) {
    }

    public List<TaskSnapshot> snapshots() {
        List<TaskSnapshot> list = new ArrayList<>();
        for (BuildingTask task : tasks.values()) {
            list.add(new TaskSnapshot(task.id(), task.blueprintName(),
                    task.dimension().location().toString(), task.origin(), task.plan().size(),
                    task.cursor(), task.sequence().size(), task.architectId(),
                    task.architectName(), pausedTasks.contains(task.id()), task.materials()));
        }
        return list;
    }

    public void setPaused(UUID taskId, boolean value) {
        if (value) {
            pausedTasks.add(taskId);
        } else {
            pausedTasks.remove(taskId);
        }
        BuildingTask task = tasks.get(taskId);
        if (task != null) {
            saveNow(task);
        }
    }

    public boolean cancel(MinecraftServer server, UUID taskId) {
        BuildingTask task = tasks.get(taskId);
        if (task == null) {
            return false;
        }
        ServerLevel level = server.getLevel(task.dimension());
        if (level != null) {
            task.clearRepairs();
            clearHeldItem(level, task);
            removeScaffold(level, task);
        }
        releaseArchitect(task);
        tasks.remove(taskId);
        pausedTasks.remove(taskId);
        unindexTask(taskId);
        deleteTask(taskId);
        if (level != null) {
            broadcastSite(level, task, true);
        }
        return true;
    }

    public void tick(MinecraftServer server) {
        if (tasks.isEmpty()) {
            return;
        }
        for (BuildingTask task : tasks.values()) {
            if (pausedTasks.contains(task.id()) || !activeTasks.contains(task.id())) {
                continue;
            }
            ServerLevel level = server.getLevel(task.dimension());
            if (level != null) {
                tickTask(level, task);
            }
        }
    }

    private void tickTask(ServerLevel level, BuildingTask task) {
        NpcData architect = task.architectId() == null ? null : NpcManager.INSTANCE.getNpc(task.architectId());
        if (task.architectId() != null && (architect == null || architect.isDead())) {
            task.assignArchitect(null, "");
            saveNow(task);
            architect = null;
        }
        if (architect == null) {
            return;
        }
        if (task.isFinished()) {
            finish(level, task);
            return;
        }
        if (architect.getBrain().getSubState() == NpcSubState.HUNGRY) {
            return;
        }
        NpcEntity entity = findEntity(level, architect);
        if (entity == null) {
            return;
        }
        if (task.isRepairScanDue()) {
            scanRepairs(level, task);
            task.resetRepairScan();
        }
        boolean repairing = task.hasRepairs();
        BuildingTask.Step step = repairing ? task.peekRepair() : task.next();
        BlockPos rel = step.pos();
        BlockPos world = task.origin().offset(rel.getX(), rel.getY(), rel.getZ());
        BlockPos stand = task.origin().offset(step.stand().getX(), step.stand().getY(), step.stand().getZ());
        entity.getLookControl().setLookAt(world.getX() + 0.5D, world.getY() + 0.5D, world.getZ() + 0.5D);
        if (!entity.tickConstructionPath(stand)) {
            return;
        }
        if (!task.isPlacementDue()) {
            return;
        }
        if (!level.isLoaded(world)) {
            return;
        }
        if (BuildRequirements.required() && !consumeAtSite(level, task, step)) {
            return;
        }
        entity.setHeldItem(new ItemStack(step.state().getBlock()));
        if (task.isSwingDue()) {
            entity.playSwing();
        }
        place(level, world, step.state());
        if (entity.blockPosition().equals(world)) {
            entity.teleportTo(world.getX() + 0.5D, world.getY() + 1.0D, world.getZ() + 0.5D);
        }
        if (repairing) {
            task.popRepair();
        } else {
            task.advance();
        }
        if ((task.cursor() & 15) == 0) {
            save(task);
        }
    }

    private void scanRepairs(ServerLevel level, BuildingTask task) {
        int section = task.next().section();
        List<BuildingTask.Step> sequence = task.sequence();
        int limit = Math.min(task.cursor(), sequence.size());
        BlockPos origin = task.origin();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = task.sectionStart(Math.max(0, section - 1)); i < limit; i++) {
            BuildingTask.Step step = sequence.get(i);
            if (step.kind() == BuildingTask.WorkKind.BUILD) {
                continue;
            }
            BlockPos rel = step.pos();
            cursor.set(origin.getX() + rel.getX(), origin.getY() + rel.getY(), origin.getZ() + rel.getZ());
            if (level.isLoaded(cursor) && !(level.getBlockState(cursor).getBlock() instanceof ScaffoldingBlock)) {
                task.enqueueRepair(step);
            }
        }
    }

    private void place(ServerLevel level, BlockPos pos, BlockState state) {
        int flags = state.getBlock() instanceof LiquidBlock ? LIQUID_FLAGS : PLACE_FLAGS;
        level.setBlock(pos, state, flags);
    }

    private void removeScaffold(ServerLevel level, BuildingTask task) {
        List<BlockPos> removed = new ArrayList<>();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos relative : task.scaffold()) {
            BlockPos pos = task.origin().offset(relative.getX(), relative.getY(), relative.getZ());
            if (!level.isLoaded(pos) || !(level.getBlockState(pos).getBlock() instanceof ScaffoldingBlock)) {
                continue;
            }
            level.setBlock(pos, air, Block.UPDATE_CLIENTS);
            removed.add(pos);
        }
        for (BlockPos pos : removed) {
            level.blockUpdated(pos, Blocks.AIR);
        }
    }

    private void clearHeldItem(ServerLevel level, BuildingTask task) {
        NpcData architect = task.architectId() == null ? null : NpcManager.INSTANCE.getNpc(task.architectId());
        NpcEntity entity = architect == null ? null : findEntity(level, architect);
        if (entity != null) {
            entity.setHeldItem(ItemStack.EMPTY);
        }
    }

    private NpcEntity findEntity(ServerLevel level, NpcData architect) {
        UUID entityUuid = architect.getEntityUuid();
        if (entityUuid == null) {
            return null;
        }
        return level.getEntity(entityUuid) instanceof NpcEntity entity && entity.isAlive() ? entity : null;
    }

    private void finish(ServerLevel level, BuildingTask task) {
        clearHeldItem(level, task);
        removeScaffold(level, task);
        BuildingRegistry.INSTANCE.register(task);
        releaseArchitect(task);
        tasks.remove(task.id());
        unindexTask(task.id());
        deleteTask(task.id());
        broadcastSite(level, task, true);
    }

    private void abort(ServerLevel level, BuildingTask task) {
        clearHeldItem(level, task);
        removeScaffold(level, task);
        releaseArchitect(task);
        tasks.remove(task.id());
        unindexTask(task.id());
        deleteTask(task.id());
        broadcastSite(level, task, true);
    }

    private static void broadcastSite(ServerLevel level, BuildingTask task, boolean remove) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        BuildingSitePacket packet = new BuildingSitePacket(task.id(), remove, task.blueprintName(),
                task.origin(), task.rotation());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, packet);
        }
    }

    public void syncTo(ServerPlayer player) {
        for (BuildingTask task : tasks.values()) {
            PacketDistributor.sendToPlayer(player, new BuildingSitePacket(task.id(), false,
                    task.blueprintName(), task.origin(), task.rotation()));
        }
    }

    private void releaseArchitect(BuildingTask task) {
        if (task.architectId() == null) {
            return;
        }
        NpcData data = NpcManager.INSTANCE.getNpc(task.architectId());
        if (data != null) {
            data.getBrain().setConstructionControlled(false);
        }
    }

}
