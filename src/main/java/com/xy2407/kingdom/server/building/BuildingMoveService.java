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
 * 建筑整体迁移：读真实方块与容器 NBT，整体平移到新落点并平移关联坐标
 */
public final class BuildingMoveService {

    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int CLEAR_FLAGS = Block.UPDATE_CLIENTS;

    private static final int MAX_BLOCKS = 65536;

    private BuildingMoveService() {
    }

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

        shiftEntities(level, oldMin, oldMax, delta);

        HomeBeds.beginMigration();
        try {
            clearOld(level, snapshot, oldMin);
        } finally {
            HomeBeds.endMigration();
        }
        clearDroppedItems(level, oldMin, oldMax);

        rebuild(level, snapshot, newMin);

        BlockPos newMax = newMin.offset(oldMax.getX() - oldMin.getX(),
                oldMax.getY() - oldMin.getY(), oldMax.getZ() - oldMin.getZ());
        BuildingRecord moved = new BuildingRecord(old.id(), old.name(), old.dimension(), newMin, newMax,
                old.level(), old.rotation(), old.farmSlots(), old.createdDay());
        BuildingRegistry.INSTANCE.update(moved);

        int homes = shiftHomes(buildingId, delta);
        shiftFarms(buildingId, delta);
        shiftPens(buildingId, delta);
        clearCaches(level, buildingId, delta);

        feedback(player, String.format("建筑已迁移：%s 格，%d 个方块%s",
                Math.abs(delta.getX()) + Math.abs(delta.getY()) + Math.abs(delta.getZ()),
                snapshot.size(),
                homes > 0 ? "，随行住户 " + homes + " 人" : ""));
        BuildingSyncService.sendTo(player);
        return true;
    }

    private static void ensureLoaded(ServerLevel level, BlockPos min, BlockPos max) {
        for (int chunkX = Math.min(min.getX(), max.getX()) >> 4;
             chunkX <= Math.max(min.getX(), max.getX()) >> 4; chunkX++) {
            for (int chunkZ = Math.min(min.getZ(), max.getZ()) >> 4;
                 chunkZ <= Math.max(min.getZ(), max.getZ()) >> 4; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

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
            }
        }
    }

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

    private static void shiftPens(UUID buildingId, BlockPos delta) {
        for (RanchPen pen : RanchPenManager.INSTANCE.pensOf(buildingId)) {
            RanchPen moved = new RanchPen(pen.id(), pen.buildingId(), pen.blueprintName(), pen.animalId(),
                    pen.dimension(), pen.min().offset(delta), pen.max().offset(delta),
                    pen.keeperIds(), pen.running(), pen.statusKey(), pen.updatedAt());
            RanchPenManager.INSTANCE.update(moved);
        }
    }

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

    private record MovedBlock(BlockPos relative, BlockState state, CompoundTag nbt) {
    }
}
