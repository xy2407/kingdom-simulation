package com.xy2407.kingdom.server.lumber;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.npc.NpcInventory;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 伐木工：砍伐目标由轮询扫描写入 NPC 的 persistentData（long 数组，按 NPC 均分），
 * 由 NPC 自己的 tick 推进（40 tick 一步）：走到树旁 → 砍最下方一格原木 →
 * 整棵树由代码一次性清除（原木+树叶，掉落按玩家破坏计算）→ 原地补种树苗；
 * 背包满回建筑存仓，仓库也满则停工，腾出空间后自动恢复。
 */
public final class NpcLumberService {

    public static final String NBT_TREES = "lumber_trees";

    public static final String NBT_BUILDING = "lumber_building";

    public static final String NBT_ACTIVE = "lumber_active";

    private static final int NEXT_TREE_DELAY_TICKS = 40;

    private static final double ARRIVE_SQR = 9.0D;

    private static final int STORE_GIVE_UP_TICKS = 400;

    private static final int STALL_RECHECK_TICKS = 40;

    private static final Map<UUID, Work> WORKS = new HashMap<>();

    /** 市民死亡/被移除：把它的采伐状态摘掉，避免 UUID 永久留在表里。 */
    public static void onNpcRemoved(UUID entityId) {
        if (entityId != null) {
            WORKS.remove(entityId);
        }
    }

    private static final Map<Block, Item> SAPLINGS = buildSaplings();

    private NpcLumberService() {
    }

    private static final class Work {
        private BlockPos target;
        private BlockPos stand;
        private boolean storing;
        private BlockPos storePos;
        private int storeTicks;
        private boolean stalled;
        private int stallTicks;
        private long nextTreeAt;
    }

    public static void reset() {
        WORKS.clear();
    }


    public static void appendTree(NpcEntity npc, BlockPos base) {
        if (npc == null || base == null) {
            return;
        }
        CompoundTag tag = npc.getPersistentData();
        long[] old = tag.getLongArray(NBT_TREES);
        long[] updated = java.util.Arrays.copyOf(old, old.length + 1);
        updated[old.length] = base.asLong();
        tag.putLongArray(NBT_TREES, updated);
    }

    public static void clearQueue(NpcEntity npc) {
        if (npc == null) {
            return;
        }
        CompoundTag tag = npc.getPersistentData();
        tag.remove(NBT_TREES);
        tag.remove(NBT_ACTIVE);
        Work work = WORKS.remove(npc.getUUID());
        if (work != null) {
            npc.haltFarm();
        }
    }

    public static void markActive(NpcEntity npc, UUID buildingId) {
        if (npc == null) {
            return;
        }
        CompoundTag tag = npc.getPersistentData();
        tag.putBoolean(NBT_ACTIVE, true);
        if (buildingId != null) {
            tag.putString(NBT_BUILDING, buildingId.toString());
        }
        if (npc.getCitizenId() != null) {
            var data = NpcManager.INSTANCE.getNpc(npc.getCitizenId());
            if (data != null) {
                data.getBrain().setLumberControlled(true);
            }
        }
    }

    public static void forceActive(NpcEntity npc) {
        markActive(npc, null);
        if (npc != null && npc.getCitizenId() != null) {
            var data = NpcManager.INSTANCE.getNpc(npc.getCitizenId());
            if (data != null) {
                data.getBrain().setLumberForced(true);
            }
        }
    }



    public static String debugState(NpcEntity npc) {
        if (npc == null) {
            return "npc=null";
        }
        CompoundTag tag = npc.getPersistentData();
        long[] trees = tag.getLongArray(NBT_TREES);
        StringBuilder text = new StringBuilder();
        text.append("active=").append(tag.getBoolean(NBT_ACTIVE));
        text.append(" trees=").append(trees.length);
        if (trees.length > 0) {
            BlockPos base = BlockPos.of(trees[0]);
            BlockState first = npc.level().getBlockState(base);
            text.append(" base=").append(base.toShortString());
            text.append(" block=").append(first.getBlock().getName().getString());
            text.append(" isLog=").append(LumberSurveyService.isLog(first));
        }
        text.append(" citizen=").append(npc.getCitizenId() != null);
        if (npc.getCitizenId() != null) {
            var data = NpcManager.INSTANCE.getNpc(npc.getCitizenId());
            text.append(" data=").append(data != null);
            if (data != null) {
                text.append(" detail=").append(data.getBrain().getDetail());
                text.append(" lumber=").append(data.getBrain().isLumberControlled());
                text.append(" sub=").append(data.getSubState());
                text.append(" hunger=").append(String.format("%.1f", data.getHunger()));
            }
        }
        Work work = WORKS.get(npc.getUUID());
        text.append(" target=").append(work == null || work.target == null
                ? "null" : work.target.toShortString());
        text.append(" storing=").append(work != null && work.storing);
        text.append(" stalled=").append(work != null && work.stalled);
        text.append(" pos=").append(npc.blockPosition().toShortString());
        return text.toString();
    }

    public static NpcEntity nearestWorker(ServerLevel level, BlockPos center, double radius) {
        List<NpcEntity> candidates = level.getEntitiesOfClass(NpcEntity.class,
                new net.minecraft.world.phys.AABB(
                        center.getX() - radius, center.getY() - 32, center.getZ() - radius,
                        center.getX() + radius, center.getY() + 32, center.getZ() + radius));
        NpcEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (NpcEntity npc : candidates) {
            double distance = npc.distanceToSqr(center.getX(), center.getY(), center.getZ());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = npc;
            }
        }
        return best;
    }


    private static void removeFirst(NpcEntity npc) {
        CompoundTag tag = npc.getPersistentData();
        long[] old = tag.getLongArray(NBT_TREES);
        if (old.length == 0) {
            return;
        }
        if (old.length == 1) {
            tag.remove(NBT_TREES);
            return;
        }
        tag.putLongArray(NBT_TREES, java.util.Arrays.copyOfRange(old, 1, old.length));
    }


    public static void tick(NpcEntity npc) {
        if (npc == null || !(npc.level() instanceof ServerLevel level)) {
            return;
        }
        if (!npc.getPersistentData().getBoolean(NBT_ACTIVE)) {
            return;
        }
        Work work = WORKS.computeIfAbsent(npc.getUUID(), key -> new Work());
        if (work.nextTreeAt > level.getGameTime()) {
            return;
        }
        step(level, npc);
    }

    private static void step(ServerLevel level, NpcEntity npc) {
        Work work = WORKS.computeIfAbsent(npc.getUUID(), key -> new Work());
        BuildingRecord building = buildingOf(npc);
        if (work.storing) {
            if (building == null) {
                work.storing = false;
                return;
            }
            tickStore(level, npc, building, work);
            return;
        }
        if (work.stalled) {
            if (building == null) {
                work.stalled = false;
                return;
            }
            tickStalled(level, npc, building, work);
            return;
        }
        long[] trees = npc.getPersistentData().getLongArray(NBT_TREES);
        if (trees.length == 0) {
            npc.haltFarm();
            return;
        }
        BlockPos base = BlockPos.of(trees[0]);
        if (!LumberSurveyService.isLog(level.getBlockState(base))) {
            removeFirst(npc);
            work.target = null;
            work.stand = null;
            return;
        }
        if (work.target == null || !work.target.equals(base)) {
            BlockPos stand = standNear(level, base);
            if (stand == null) {
                removeFirst(npc);
                work.target = null;
                work.stand = null;
                return;
            }
            work.target = base;
            work.stand = stand;
            npc.setFarmTarget(stand);
        }
        if (!near(npc, work.stand)) {
            npc.tickTravelPath(work.stand);
            return;
        }
        npc.swing(InteractionHand.MAIN_HAND, true);
        chopTree(level, npc, base);
        removeFirst(npc);
        work.target = null;
        work.stand = null;
        work.nextTreeAt = level.getGameTime() + NEXT_TREE_DELAY_TICKS;
        if (!inventoryHasRoom(npc.getNpcInventory())) {
            work.storing = true;
            work.storePos = null;
            work.storeTicks = 0;
        }
    }


    private static void chopTree(ServerLevel level, NpcEntity npc, BlockPos base) {
        if (base == null) {
            return;
        }
        List<BlockPos> blocks = LumberSurveyService.collectTree(level, base);
        if (blocks.isEmpty()) {
            return;
        }
        Block logBlock = null;
        for (BlockPos pos : blocks) {
            BlockState state = level.getBlockState(pos);
            if (LumberSurveyService.isLog(state) && logBlock == null) {
                logBlock = state.getBlock();
            }
        }
        BlockState baseState = level.getBlockState(base);
        if (LumberSurveyService.isLog(baseState)) {
            harvest(level, npc, base, baseState);
        }
        for (BlockPos pos : blocks) {
            if (pos.equals(base)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!LumberSurveyService.isLog(state) && !(state.getBlock() instanceof LeavesBlock)) {
                continue;
            }
            harvest(level, npc, pos, state);
        }
        if (logBlock != null && level.getBlockState(base).isAir()) {
            Item sapling = SAPLINGS.get(logBlock);
            if (sapling != null) {
                level.setBlockAndUpdate(base, Block.byItem(sapling).defaultBlockState());
            }
        }
    }

    private static void harvest(ServerLevel level, NpcEntity npc, BlockPos pos, BlockState state) {
        List<ItemStack> drops = Block.getDrops(state, level, pos, null, npc, npc.getMainHandItem());
        level.destroyBlock(pos, false, npc);
        deposit(level, npc, drops);
    }

    private static void deposit(ServerLevel level, NpcEntity npc, List<ItemStack> drops) {
        NpcInventory inventory = npc.getNpcInventory();
        for (ItemStack drop : drops) {
            if (drop == null || drop.isEmpty()) {
                continue;
            }
            ItemStack remaining = drop.copy();
            for (int slot = 0; slot < inventory.getSlots() && !remaining.isEmpty(); slot++) {
                remaining = inventory.insertItem(slot, remaining, false);
            }
            if (!remaining.isEmpty()) {
                Block.popResource(level, npc.blockPosition(), remaining);
            }
        }
    }


    private static void tickStore(ServerLevel level, NpcEntity npc, BuildingRecord building, Work work) {
        if (nearStore(npc, building)) {
            transferToStore(level, npc, building);
            work.storing = false;
            work.storePos = null;
            work.storeTicks = 0;
            if (!inventoryHasRoom(npc.getNpcInventory()) && !storeHasRoom(level, building)) {
                work.stalled = true;
                work.stallTicks = 0;
                npc.haltFarm();
            }
            return;
        }
        BlockPos storePos = work.storePos;
        if (storePos == null) {
            storePos = storePoint(level, building);
            work.storePos = storePos;
        }
        npc.tickTravelPath(storePos);
        if (++work.storeTicks > STORE_GIVE_UP_TICKS) {
            work.storing = false;
            work.storePos = null;
            work.storeTicks = 0;
        }
    }

    private static void tickStalled(ServerLevel level, NpcEntity npc, BuildingRecord building, Work work) {
        work.stallTicks++;
        if (work.stallTicks == 1) {
            npc.haltFarm();
        }
        if (work.stallTicks < STALL_RECHECK_TICKS) {
            return;
        }
        work.stallTicks = 0;
        if (inventoryHasRoom(npc.getNpcInventory()) || storeHasRoom(level, building)) {
            work.stalled = false;
        }
    }

    private static boolean inventoryHasRoom(NpcInventory inventory) {
        for (int slot = NpcInventory.BAG_START; slot < NpcInventory.SLOT_COUNT; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private static void transferToStore(ServerLevel level, NpcEntity npc, BuildingRecord building) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        IItemHandler store = meta == null ? null : BuildingStorageStore.INSTANCE.handlerOf(
                server, building.id(), meta.storageSlots());
        if (store == null) {
            return;
        }
        NpcInventory inventory = npc.getNpcInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack remaining = stack.copy();
            for (int target = 0; target < store.getSlots() && !remaining.isEmpty(); target++) {
                remaining = store.insertItem(target, remaining, false);
            }
            inventory.setStackInSlot(slot, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
            if (!remaining.isEmpty()) {
                return;
            }
        }
    }

    private static boolean storeHasRoom(ServerLevel level, BuildingRecord building) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return false;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        IItemHandler store = meta == null ? null : BuildingStorageStore.INSTANCE.handlerOf(
                server, building.id(), meta.storageSlots());
        if (store == null) {
            return false;
        }
        for (int slot = 0; slot < store.getSlots(); slot++) {
            ItemStack stack = store.getStackInSlot(slot);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos storePoint(ServerLevel level, BuildingRecord building) {
        BlockPos min = building.min();
        BlockPos max = building.max();
        int x = (min.getX() + max.getX()) / 2;
        int z = (min.getZ() + max.getZ()) / 2;
        for (int y = max.getY(); y >= min.getY(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).isSolid()) {
                return pos;
            }
        }
        return new BlockPos(x, max.getY() + 1, z);
    }

    private static BlockPos standNear(ServerLevel level, BlockPos base) {
        for (int radius = 1; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    BlockPos candidate = base.offset(dx, 0, dz);
                    for (int dy = 1; dy >= -2; dy--) {
                        BlockPos stand = candidate.above(dy);
                        if (level.getBlockState(stand).isAir()
                                && level.getBlockState(stand.above()).isAir()
                                && !level.getBlockState(stand.below()).isAir()) {
                            return stand;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean near(NpcEntity npc, BlockPos target) {
        if (target == null) {
            return false;
        }
        double dx = npc.getX() - (target.getX() + 0.5D);
        double dz = npc.getZ() - (target.getZ() + 0.5D);
        double dy = Math.abs(Math.floor(npc.getY() + 0.5D) - target.getY());
        return dx * dx + dz * dz <= ARRIVE_SQR && dy <= 1.5D;
    }

    private static boolean nearStore(NpcEntity npc, BuildingRecord building) {
        BlockPos min = building.min();
        BlockPos max = building.max();
        return npc.getX() >= min.getX() - 2.0D && npc.getX() <= max.getX() + 3.0D
                && npc.getZ() >= min.getZ() - 2.0D && npc.getZ() <= max.getZ() + 3.0D
                && npc.getY() >= min.getY() - 2.0D && npc.getY() <= max.getY() + 4.0D;
    }

    private static BuildingRecord buildingOf(NpcEntity npc) {
        String raw = npc.getPersistentData().getString(NBT_BUILDING);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return BuildingRegistry.INSTANCE.byId(UUID.fromString(raw));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public static List<NpcEntity> workersFor(ServerLevel level, BuildingRecord building, int limit) {
        BlockPos min = building.min();
        BlockPos max = building.max();
        double radius = 64.0D;
        List<NpcEntity> candidates = level.getEntitiesOfClass(NpcEntity.class,
                new net.minecraft.world.phys.AABB(
                        min.getX() - radius, min.getY() - 32, min.getZ() - radius,
                        max.getX() + radius, max.getY() + 32, max.getZ() + radius));
        List<NpcEntity> result = new ArrayList<>(limit);
        double cx = (min.getX() + max.getX()) / 2.0D;
        double cz = (min.getZ() + max.getZ()) / 2.0D;
        candidates.sort((a, b) -> Double.compare(
                a.distanceToSqr(cx, a.getY(), cz), b.distanceToSqr(cx, b.getY(), cz)));
        for (NpcEntity npc : candidates) {
            if (result.size() >= limit) {
                break;
            }
            result.add(npc);
        }
        return result;
    }

    private static Map<Block, Item> buildSaplings() {
        Map<Block, Item> map = new HashMap<>();
        map.put(Blocks.OAK_LOG, Items.OAK_SAPLING);
        map.put(Blocks.SPRUCE_LOG, Items.SPRUCE_SAPLING);
        map.put(Blocks.BIRCH_LOG, Items.BIRCH_SAPLING);
        map.put(Blocks.JUNGLE_LOG, Items.JUNGLE_SAPLING);
        map.put(Blocks.ACACIA_LOG, Items.ACACIA_SAPLING);
        map.put(Blocks.DARK_OAK_LOG, Items.DARK_OAK_SAPLING);
        map.put(Blocks.CHERRY_LOG, Items.CHERRY_SAPLING);
        map.put(Blocks.MANGROVE_LOG, Items.MANGROVE_PROPAGULE);
        return map;
    }
}