package com.xy2407.kingdom.server.building;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.farm.FarmPlot;
import com.xy2407.kingdom.common.farm.FarmRegion;
import com.xy2407.kingdom.common.home.HomeBeds;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcFarmService;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.path.SnapshotRepository;
import com.xy2407.kingdom.common.ranch.RanchBounds;
import com.xy2407.kingdom.common.ranch.RanchPen;
import com.xy2407.kingdom.server.farm.FarmPlotManager;
import com.xy2407.kingdom.server.ranch.RanchPenManager;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 建筑整体迁移：照搬 xy's_nsuk 的 RtsBuildingMoveService，按 kingdom 的数据模型适配。
 * 与参照项目的关键差别 —— 我们的关联全是 UUID 引用（NPC 的 home_id、农田的 workerIds、
 * 畜栏的 keeperIds），建筑 id 不变就不必重写指针，只要把"建筑内的绝对坐标"整体平移：
 * 床位、农田矩形与散落块、畜栏包围盒、旧包围盒内的实体，外加清掉指向旧位置的各类缓存。
 * 与参照一致的两条要点：迁移前读世界真实方块 + 方块实体 NBT（容器里的东西跟着走）；
 * 清旧方块时先摘掉方块实体，避免容器 onRemove 把物品掉一地。
 */
public final class BuildingMoveService {

    /** 与建筑施工同一套写方块 flags，保证迁移后的方块与正常建出来的表现一致。 */
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int CLEAR_FLAGS = Block.UPDATE_CLIENTS;

    /** 单次迁移的方块上限，防超大建筑把主线程卡死（超了直接拒绝，不做半截迁移）。 */
    private static final int MAX_BLOCKS = 65536;

    private BuildingMoveService() {
    }

    /**
     * 把建筑整体平移到新落点（只支持同维度内平移，朝向不变）。
     *
     * @param newMin 新的包围盒最小角，通常是玩家指定的落点
     * @return 迁移是否成功
     */
    public static boolean move(ServerLevel level, ServerPlayer player, UUID buildingId, BlockPos newMin) {
        BuildingRecord old = BuildingRegistry.INSTANCE.byId(buildingId);
        if (old == null) {
            feedback(player, "找不到这栋建筑（还没完工？）");
            return false;
        }
        if (newMin == null) {
            feedback(player, "落点无效");
            return false;
        }
        if (!level.dimension().location().toString().equals(old.dimension())) {
            feedback(player, "只能在同一维度里迁移");
            return false;
        }
        BlockPos oldMin = old.min();
        BlockPos delta = newMin.subtract(oldMin);
        if (delta.equals(BlockPos.ZERO)) {
            feedback(player, "新落点和原位一样");
            return false;
        }

        BlockPos oldMax = old.max();
        ensureLoaded(level, oldMin, oldMax);
        ensureLoaded(level, newMin, newMin.offset(oldMax.getX() - oldMin.getX(),
                oldMax.getY() - oldMin.getY(), oldMax.getZ() - oldMin.getZ()));
        List<MovedBlock> snapshot = capture(level, oldMin, oldMax);
        if (snapshot.isEmpty()) {
            feedback(player, "原位置读不到方块（区块没加载？）");
            return false;
        }
        if (snapshot.size() >= MAX_BLOCKS) {
            feedback(player, "建筑太大，超过 " + MAX_BLOCKS + " 个方块，暂不支持整体迁移");
            return false;
        }

        // 1) 先搬实体（人在里面会被请出去，但玩家自己不动，免得把人瞬移走）
        shiftEntities(level, oldMin, oldMax, delta);

        // 2) 清原位置：摘方块实体 + 置空，再扫掉溢出的掉落物。
        //    清方块会触发床的 onRemove，迁移期间必须屏蔽"床没了就解绑住户"，
        //    否则整栋住宅的床位关联会在搬家瞬间被清空（床马上会在新位置重建）。
        HomeBeds.beginMigration();
        try {
            clearOld(level, snapshot, oldMin);
        } finally {
            HomeBeds.endMigration();
        }
        clearDroppedItems(level, oldMin, oldMax);

        // 3) 在新位置重建（同样的相对偏移），方块实体 NBT 逐个回填
        rebuild(level, snapshot, newMin);

        // 4) 更新登记：内存 + 数据库，同时刷新地图叠层
        BlockPos newMax = newMin.offset(oldMax.getX() - oldMin.getX(),
                oldMax.getY() - oldMin.getY(), oldMax.getZ() - oldMin.getZ());
        BuildingRecord moved = new BuildingRecord(old.id(), old.name(), old.dimension(), newMin, newMax,
                old.level(), old.rotation(), old.farmSlots(), old.createdDay());
        BuildingRegistry.INSTANCE.update(moved);

        // 5) 平移所有"建筑内的绝对坐标"，并清掉指向旧位置的缓存
        int homes = shiftHomes(buildingId, delta);
        shiftFarms(buildingId, delta);
        shiftPens(buildingId, delta);
        clearCaches(level, buildingId, delta);

        feedback(player, String.format("建筑已迁移：%s 格，%d 个方块%s",
                Math.abs(delta.getX()) + Math.abs(delta.getY()) + Math.abs(delta.getZ()),
                snapshot.size(),
                homes > 0 ? "，随行住户 " + homes + " 人" : ""));
        // 立刻回一份新清单，否则客户端还画着旧位置的框
        BuildingSyncService.sendTo(player);
        return true;
    }

    /** 迁移前把新旧位置的区块都叫醒，否则 getBlockState 只能读到"未加载"。 */
    private static void ensureLoaded(ServerLevel level, BlockPos min, BlockPos max) {
        for (int chunkX = Math.min(min.getX(), max.getX()) >> 4;
             chunkX <= Math.max(min.getX(), max.getX()) >> 4; chunkX++) {
            for (int chunkZ = Math.min(min.getZ(), max.getZ()) >> 4;
                 chunkZ <= Math.max(min.getZ(), max.getZ()) >> 4; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

    /** 逐块读世界真实状态 + 方块实体 NBT，坐标换成相对包围盒最小角的偏移。 */
    private static List<MovedBlock> capture(ServerLevel level, BlockPos min, BlockPos max) {
        List<MovedBlock> found = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = min.getY(); y <= max.getY() && found.size() < MAX_BLOCKS; y++) {
            for (int x = min.getX(); x <= max.getX() && found.size() < MAX_BLOCKS; x++) {
                for (int z = min.getZ(); z <= max.getZ() && found.size() < MAX_BLOCKS; z++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    CompoundTag nbt = null;
                    BlockEntity blockEntity = level.getBlockEntity(cursor);
                    if (blockEntity != null) {
                        try {
                            nbt = blockEntity.saveWithFullMetadata(level.registryAccess());
                        } catch (RuntimeException ignored) {
                            nbt = null;
                        }
                    }
                    found.add(new MovedBlock(
                            new BlockPos(x - min.getX(), y - min.getY(), z - min.getZ()), state, nbt));
                }
            }
        }
        return found;
    }

    /** 清原位置：有方块实体先摘掉（免得容器 onRemove 掉物品），再置空气并让寻路快照失效。 */
    private static void clearOld(ServerLevel level, List<MovedBlock> snapshot, BlockPos oldMin) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (MovedBlock block : snapshot) {
            BlockPos pos = oldMin.offset(block.relative());
            if (level.getBlockEntity(pos) != null) {
                level.removeBlockEntity(pos);
            }
            level.setBlock(pos, air, CLEAR_FLAGS);
            markSnapshotDirty(level, pos);
        }
    }

    /** 新位置重建；带 NBT 的方块按原样回填（容器里的物品靠这一步保住）。 */
    private static void rebuild(ServerLevel level, List<MovedBlock> snapshot, BlockPos newMin) {
        for (MovedBlock block : snapshot) {
            BlockPos pos = newMin.offset(block.relative());
            level.setBlock(pos, block.state(), PLACE_FLAGS);
            markSnapshotDirty(level, pos);
            if (block.nbt() == null) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                continue;
            }
            try {
                blockEntity.loadWithComponents(block.nbt(), level.registryAccess());
                blockEntity.setChanged();
            } catch (RuntimeException ignored) {
                // 个别方块实体的数据版本对不上就跳过，不影响其余方块
            }
        }
    }

    /** 把旧包围盒内的非玩家实体按同一个位移搬走（玩家不动，免得把人一起瞬移）。 */
    private static void shiftEntities(ServerLevel level, BlockPos min, BlockPos max, BlockPos delta) {
        AABB box = new AABB(min.getX(), min.getY(), min.getZ(),
                max.getX() + 1.0D, max.getY() + 1.0D, max.getZ() + 1.0D);
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box)) {
            if (!entity.isAlive() || entity instanceof ServerPlayer) {
                continue;
            }
            BlockPos from = entity.blockPosition();
            BlockPos to = from.offset(delta);
            entity.moveTo(to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D, entity.getYRot(), entity.getXRot());
            entity.setDeltaMovement(Vec3.ZERO);
        }
    }

    /** 清旧位置附近溢出的掉落物（清方块时可能已经掉了一些）。 */
    private static void clearDroppedItems(ServerLevel level, BlockPos min, BlockPos max) {
        AABB box = new AABB(
                Math.min(min.getX(), max.getX()) - 2.0D, Math.min(min.getY(), max.getY()) - 2.0D,
                Math.min(min.getZ(), max.getZ()) - 2.0D,
                Math.max(min.getX(), max.getX()) + 3.0D, Math.max(min.getY(), max.getY()) + 3.0D,
                Math.max(min.getZ(), max.getZ()) + 3.0D);
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            item.discard();
        }
    }

    /**
     * 平移住户的床坐标：{@code home_id} 指向的是建筑（位号不变），
     * 但那张床是实体方块、跟着建筑走了，所以 {@code home_x/y/z} 必须同步平移。
     *
     * @return 跟着平移的住户数
     */
    private static int shiftHomes(UUID buildingId, BlockPos delta) {
        String id = buildingId.toString();
        int moved = 0;
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !id.equals(npc.getHomeId())) {
                continue;
            }
            npc.setHomeX(npc.getHomeX() + delta.getX());
            npc.setHomeY(npc.getHomeY() + delta.getY());
            npc.setHomeZ(npc.getHomeZ() + delta.getZ());
            moved++;
        }
        return moved;
    }

    /** 平移农田：核心矩形与附加散落块都是绝对坐标。 */
    private static void shiftFarms(UUID buildingId, BlockPos delta) {
        for (FarmPlot plot : FarmPlotManager.INSTANCE.plotsOf(buildingId)) {
            List<FarmRegion> extra = new ArrayList<>();
            for (FarmRegion region : plot.extra()) {
                extra.add(new FarmRegion(region.minX() + delta.getX(), region.minZ() + delta.getZ(),
                        region.maxX() + delta.getX(), region.maxZ() + delta.getZ()));
            }
            FarmPlot moved = new FarmPlot(plot.id(), plot.buildingId(), plot.blueprintName(), plot.name(),
                    plot.slots(), plot.cropId(), plot.workerIds(), plot.dimension(),
                    plot.min().offset(delta), plot.max().offset(delta), plot.createdDay(), extra);
            FarmPlotManager.INSTANCE.update(moved);
        }
    }

    /** 平移畜栏：每栏的包围盒直接来自建筑，不跟着改就会被判定为"栏在地基外"。 */
    private static void shiftPens(UUID buildingId, BlockPos delta) {
        for (RanchPen pen : RanchPenManager.INSTANCE.pensOf(buildingId)) {
            RanchPen moved = new RanchPen(pen.id(), pen.buildingId(), pen.blueprintName(), pen.animalId(),
                    pen.dimension(), pen.min().offset(delta), pen.max().offset(delta),
                    pen.keeperIds(), pen.running(), pen.statusKey(), pen.updatedAt());
            RanchPenManager.INSTANCE.update(moved);
        }
    }

    /**
     * 清掉指向旧坐标的缓存与临时目标：
     * 农活/牧场的工作队列（静态绝对坐标）、畜栏可站立点缓存、以及住客/工人身上的临时导航目标。
     */
    private static void clearCaches(ServerLevel level, UUID buildingId, BlockPos delta) {
        NpcFarmService.reset();
        RanchBounds.invalidate(buildingId);
        String id = buildingId.toString();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !(id.equals(npc.getHomeId()) || id.equals(npc.getWorkplaceId()))) {
                continue;
            }
            UUID entityId = npc.getEntityUuid();
            if (entityId != null && level.getEntity(entityId) instanceof NpcEntity npcEntity) {
                npcEntity.clearMoveTargets();
            }
        }
    }

    /** 方块变了就要让寻路快照失效，否则市民会沿着旧路线撞墙。 */
    private static void markSnapshotDirty(ServerLevel level, BlockPos pos) {
        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository != null) {
            repository.markDirty(level, pos);
        }
    }

    private static void feedback(ServerPlayer player, String message) {
        if (player != null) {
            player.displayClientMessage(Component.literal(message), true);
        }
    }

    /** 一个待搬迁的方块：包围盒内相对偏移 + 方块状态 + 方块实体 NBT（没有就为 null）。 */
    private record MovedBlock(BlockPos relative, BlockState state, CompoundTag nbt) {
    }
}
