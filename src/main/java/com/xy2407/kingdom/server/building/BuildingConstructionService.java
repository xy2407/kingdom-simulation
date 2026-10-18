package com.xy2407.kingdom.server.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildPlan;
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

    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int LIQUID_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS;
    private static final BlockState SCAFFOLD_STATE =
            Blocks.SCAFFOLDING.defaultBlockState().trySetValue(BlockStateProperties.BOTTOM, true);

    private final Map<UUID, BuildingTask> tasks = new ConcurrentHashMap<>();

    private final Set<UUID> pausedTasks = ConcurrentHashMap.newKeySet();

    private BuildingConstructionService() {
    }

    public BuildingTask start(ServerLevel level, String blueprintName, BlockPos origin, int rotationDegrees) {
        BuildPlan plan = BuildingBlueprints.load(blueprintName, rotationDegrees);
        if (plan == null || origin == null) {
            ModMain.LOGGER.warn("建筑开工失败：蓝图读不到 {} @ {}", blueprintName, origin);
            return null;
        }
        NpcData architect = pickArchitect(level, origin);
        if (architect == null) {
            ModMain.LOGGER.warn("建筑开工失败：没有可用市民 {} @ {}", blueprintName, origin);
            return null;
        }
        BuildingTask task = new BuildingTask(UUID.randomUUID(), blueprintName, level.dimension(),
                origin.immutable(), plan, workSequence(plan), ScaffoldPlanner.all(plan.size(), plan.layerCount()),
                architect.getId(), architect.getName(),
                1, rotationDegrees, DateSystem.totalDay(level));
        architect.getBrain().setConstructionControlled(true);
        warmUpSnapshot(level, origin.immutable(), plan.size(), plan.layerCount(), architect);
        UnitSnapshotStore.INSTANCE.extendYWindow(level.dimension().location().toString(),
                origin.getY(), plan.layerCount());
        tasks.put(task.id(), task);
        broadcastSite(level, task, false);
        return task;
    }

    public void startWithFeedback(ServerLevel level, ServerPlayer player, String blueprintName,
                                  BlockPos origin, int rotationDegrees) {
        if (start(level, blueprintName, origin, rotationDegrees) != null) {
            return;
        }
        String reason = failureReason(level, blueprintName, origin);
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
        String dimension = level.dimension().location().toString();
        boolean anyCitizen = false;
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !dimension.equals(npc.getDimension())) {
                continue;
            }
            anyCitizen = true;
            NpcBrain brain = npc.getBrain();
            if (brain.isConstructionControlled() || brain.isStarving()) {
                continue;
            }
            return null;
        }
        return anyCitizen ? "市民都饿着或已在施工中" : "这个维度还没有市民（/kingdom npc spawn 3）";
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
                        new BlockPos(pillar.getX(), y0 + 2, pillar.getZ())));
                out.add(new BuildingTask.Step(
                        new BlockPos(pillar.getX(), y0 + 3, pillar.getZ()), SCAFFOLD_STATE,
                        BuildingTask.WorkKind.PILLAR, section,
                        new BlockPos(pillar.getX(), y0 + 3, pillar.getZ())));
            }
            appendRing(out, walk, y1, section, new BlockPos(walk.get(0).getX(), y1, walk.get(0).getZ()));
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
        int margin = 8;
        repository.buildNow(level,
                (origin.getX() - margin) >> 4, (origin.getX() + size.getX() + margin) >> 4,
                (origin.getZ() - margin) >> 4, (origin.getZ() + size.getZ() + margin) >> 4);
        BlockPos from = new BlockPos((int) Math.floor(architect.getX()), (int) Math.floor(architect.getY()),
                (int) Math.floor(architect.getZ()));
        BlockPos to = origin.offset(size.getX() / 2, Math.min(height, 8), size.getZ() / 2);
        NpcPathfinder.requestChunks(repository, level.dimension(), from, to, NpcPathfinder.DEFAULT_PADDING);
    }

    private NpcData pickArchitect(ServerLevel level, BlockPos origin) {
        NpcData best = null;
        double bestDist = Double.MAX_VALUE;
        String dimension = level.dimension().location().toString();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !dimension.equals(npc.getDimension())
                    || npc.getBrain().isConstructionControlled() || npc.getBrain().isStarving()) {
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

    public int activeCount() {
        return tasks.size();
    }

    public record TaskSnapshot(UUID id, String blueprintName, String dimension, BlockPos origin,
                               Vec3i size, int cursor, int total, UUID architectId,
                               String architectName, boolean paused) {
    }

    public List<TaskSnapshot> snapshots() {
        List<TaskSnapshot> list = new ArrayList<>();
        for (BuildingTask task : tasks.values()) {
            list.add(new TaskSnapshot(task.id(), task.blueprintName(),
                    task.dimension().location().toString(), task.origin(), task.plan().size(),
                    task.cursor(), task.sequence().size(), task.architectId(),
                    task.architectName(), pausedTasks.contains(task.id())));
        }
        return list;
    }

    public void setPaused(UUID taskId, boolean value) {
        if (value) {
            pausedTasks.add(taskId);
        } else {
            pausedTasks.remove(taskId);
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
            if (pausedTasks.contains(task.id())) {
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
        if (architect == null || architect.isDead()) {
            abort(level, task);
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
        entity.setHeldItem(new ItemStack(step.state().getBlock()));
        if (task.isSwingDue()) {
            entity.playSwing();
        }
        if (!task.isPlacementDue()) {
            return;
        }
        if (!level.isLoaded(world)) {
            return;
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
        markSnapshotDirty(level, pos);
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
            markSnapshotDirty(level, pos);
        }
        for (BlockPos pos : removed) {
            level.blockUpdated(pos, Blocks.AIR);
        }
    }

    private void markSnapshotDirty(ServerLevel level, BlockPos pos) {
        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository != null) {
            repository.refreshNow(level, pos);
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
        broadcastSite(level, task, true);
    }

    private void abort(ServerLevel level, BuildingTask task) {
        clearHeldItem(level, task);
        removeScaffold(level, task);
        releaseArchitect(task);
        tasks.remove(task.id());
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
