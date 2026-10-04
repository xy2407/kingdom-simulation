package com.xy2407.kingdom.server.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import com.xy2407.kingdom.common.path.SnapshotRepository;
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

/**
 * 建筑施工调度，建筑师沿脚手架分段施工并在完成后拆除脚手架
 */
public final class BuildingConstructionService {
    public static final BuildingConstructionService INSTANCE = new BuildingConstructionService();

    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int LIQUID_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS;
    private static final BlockState SCAFFOLD_STATE =
            Blocks.SCAFFOLDING.defaultBlockState().trySetValue(BlockStateProperties.BOTTOM, true);

    private final Map<UUID, BuildingTask> tasks = new ConcurrentHashMap<>();

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
        tasks.put(task.id(), task);
        broadcastSite(level, task, false);
        return task;
    }

    /**
     * 开工入口（带玩家反馈）：失败时把原因直接发给玩家，别再静默什么都不发生。
     */
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

    /** 开工失败的具体原因，能给玩家一句能照着做的提示。 */
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

    /**
     * 工步序列：每个 4 层段 = 底圈（仅首段）→ 柱（每柱自下而上）→ 顶圈（沿圈顶挪）→ 段内建筑层。
     * 站位规则：铺圈时站上一块圈顶，铺柱时站柱旁圈顶，铺建筑时站 z=-1 侧边框顶随 x 进度推进。
     */
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

    /** 沿 walk 序生成整圈工步，每块站位 = 上一块圈顶；首块站位由调用方给定 */
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

    /** 柱位在圈上的前一个相邻格（角柱取圈末尾格），铺柱时站它的圈顶 */
    private static BlockPos ringNeighborOf(List<BlockPos> walk, BlockPos pillar) {
        int index = walk.indexOf(pillar);
        int prev = index <= 0 ? walk.size() - 1 : index - 1;
        return walk.get(prev);
    }

    /** 选取离落点最近的空闲市民作为建筑师；必须同维度，否则施工会永远等不到人。 */
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

    public void tick(MinecraftServer server) {
        if (tasks.isEmpty()) {
            return;
        }
        for (BuildingTask task : tasks.values()) {
            ServerLevel level = server.getLevel(task.dimension());
            if (level != null) {
                tickTask(level, task);
            }
        }
    }

    /** NPC 驱动放置：到站位才放，放置与 NPC 到方块的距离无关 */
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

    /**
     * 只扫描已施工过的脚手架缺失，破坏的先修复再继续建造。
     *
     * <p>每 {@code REPAIR_SCAN_INTERVAL}（80 tick）跑一次，扫的是"上一段起点 → 当前游标"，
     * 大型建筑一段就有上千个工步。这里用可复用的 {@link BlockPos.MutableBlockPos} 代替
     * {@code offset(...)}：后者每步都会新建一个 BlockPos，一轮扫描就是上千次小对象分配，
     * 正好落在每几秒一次的周期里，表现为帧/刻时间规律性抖一下。</p>
     */
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

    /**
     * 拆除脚手架，且不产生任何掉落物。
     *
     * <p>细节有两处：</p>
     * <ol>
     *   <li>只用 {@code UPDATE_CLIENTS} 标志逐格清除，<b>不带 {@code UPDATE_NEIGHBORS}</b>。
     *       {@code ScaffoldingBlock} 的稳定性是邻居驱动的——每移除一格，相邻脚手架都会重算
     *       {@code DISTANCE}，算出来大于 6 就自己排一个 tick 变成掉落实体。掉的根本不是被移除的
     *       那一格，而是它的邻居。</li>
     *   <li>清空之后再统一补一次邻居更新，让周围方块的形状/光照回到正常状态。此时场地上已经没有
     *       脚手架，不会再触发掉落判定。</li>
     * </ol>
     */
    private void removeScaffold(ServerLevel level, BuildingTask task) {
        List<BlockPos> removed = new ArrayList<>();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos relative : task.scaffold()) {
            BlockPos pos = task.origin().offset(relative.getX(), relative.getY(), relative.getZ());
            if (!level.isLoaded(pos) || !(level.getBlockState(pos).getBlock() instanceof ScaffoldingBlock)) {
                // 该位置可能已经被建筑方块占用（或本来就是空的），只清真正的脚手架
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

    /** 施工改动了方块，寻路快照必须重建，否则 A* 永远看不到新脚手架 */
    private void markSnapshotDirty(ServerLevel level, BlockPos pos) {
        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository != null) {
            repository.markDirty(level, pos);
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
        removeScaffold(level, task);
        BuildingRegistry.INSTANCE.register(task);
        releaseArchitect(task);
        tasks.remove(task.id());
        broadcastSite(level, task, true);
    }

    private void abort(ServerLevel level, BuildingTask task) {
        removeScaffold(level, task);
        releaseArchitect(task);
        tasks.remove(task.id());
        broadcastSite(level, task, true);
    }

    /** 施工点增删广播给所有在线玩家，客户端据此渲染或清理施工虚影。 */
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

    /** 玩家加入时补推进行中的施工点，重进世界后虚影不会丢。 */
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
