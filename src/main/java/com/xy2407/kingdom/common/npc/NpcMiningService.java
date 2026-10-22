package com.xy2407.kingdom.common.npc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.IndustryKind;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.mining.MiningPlot;
import com.xy2407.kingdom.common.path.ChunkSnapshot;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;
import com.xy2407.kingdom.server.mining.MiningPlotManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 采矿工作循环：单工蛇形逐层下挖，按玩家镐速挖掘，产出与流体采集入建筑仓库。
 */
public final class NpcMiningService {

    private static final long ASSIGN_INTERVAL_TICKS = 100L;

    private static final double MINE_ARRIVE_SQR = 9.0D;

    private static final int STAND_LOOKBACK = 8;

    private static final int STAND_GIVE_UP_TICKS = 200;

    private static final int IDLE_RETRY_TICKS = 40;

    private static final long CLAIM_TIMEOUT_TICKS = 600L;

    private static final long SKIP_RETRY_TICKS = 600L;

    private static final int STORE_GIVE_UP_TICKS = 400;

    private static final int STALL_RECHECK_TICKS = 40;

    public static final String NBT_LAYER_Y = "xy2407_mining_layer_y";

    public static final String NBT_LAYER_QUEUE = "xy2407_mining_layer_queue";

    private static final Map<UUID, Work> WORKS = new HashMap<>();

    private static final Map<UUID, UUID> WORKER_PLOT = new HashMap<>();

    private static final Map<UUID, LayerClaims> CLAIMS = new HashMap<>();

    private static long assignTimer;

    private NpcMiningService() {
    }

    private static final class Work {
        private BlockPos target;
        private BlockPos stand;
        private int targetIndex;
        private double breakProgress;
        private int swingTimer;
        private double bestStandDistance = Double.MAX_VALUE;
        private int standFailTicks;
        private int giveUpCount;
        private int idleWait;
        private boolean storing;
        private BlockPos storePos;
        private int storeTicks;
        private boolean stalled;
        private int stallTicks;
    }

    private static final class LayerClaims {
        private int y;
        private final Map<Integer, UUID> owners = new HashMap<>();
        private final Map<UUID, Integer> held = new HashMap<>();
        private final Map<UUID, Long> beats = new HashMap<>();
        private final Map<Integer, Long> skippedUntil = new HashMap<>();
    }

    public static void reset() {
        WORKS.clear();
        WORKER_PLOT.clear();
        CLAIMS.clear();
        assignTimer = 0;
    }

    public static void assign(MinecraftServer server) {
        if (++assignTimer % ASSIGN_INTERVAL_TICKS != 0) {
            return;
        }
        WORKER_PLOT.clear();
        for (MiningPlot plot : MiningPlotManager.INSTANCE.all()) {
            if (MiningPlot.STATUS_DONE.equals(plot.status())) {
                continue;
            }
            for (UUID workerId : plot.workerIds()) {
                WORKER_PLOT.put(workerId, plot.id());
            }
        }
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead()) {
                continue;
            }
            boolean mining = WORKER_PLOT.containsKey(npc.getId());
            NpcBrain brain = npc.getBrain();
            if (!mining) {
                if (brain.isMiningControlled()) {
                    brain.setMiningControlled(false);
                }
                continue;
            }
            if (brain.isRtsControlled() || brain.isConstructionControlled()) {
                continue;
            }
            brain.setMiningControlled(true);
        }
    }

    public static void linkWorker(UUID npcId, UUID plotId) {
        if (npcId != null && plotId != null) {
            WORKER_PLOT.put(npcId, plotId);
        }
    }

    public static void unlinkWorker(UUID npcId) {
        if (npcId != null) {
            WORKER_PLOT.remove(npcId);
        }
    }

    public static void tick(NpcEntity npc, NpcData data) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return;
        }
        if (!onDuty(data)) {
            return;
        }
        MiningPlot plot = plotOf(data);
        if (plot == null || !MiningPlot.STATUS_RUNNING.equals(plot.status()) || !plot.hasArea()) {
            return;
        }
        if (!plot.dimension().equals(level.dimension().location().toString())) {
            return;
        }
        if (!npc.getMainHandItem().is(Items.NETHERITE_PICKAXE)) {
            givePickaxe(npc);
        }
        Work work = WORKS.computeIfAbsent(npc.getUUID(), id -> new Work());
        if (work.storing) {
            tickStore(level, npc, plot, work);
            return;
        }
        if (work.stalled) {
            tickStalled(level, npc, plot, work);
            return;
        }
        if (work.target == null) {
            if (work.idleWait > 0) {
                work.idleWait--;
                return;
            }
            long now = level.getGameTime();
            if (((now + npc.getId()) & 1L) != 0L) {
                return;
            }
            Claim claim = pickFromQueue(level, plot, npc);
            if (claim == null) {
                work.idleWait = IDLE_RETRY_TICKS;
                npc.clearFarmTarget();
                return;
            }
            work.targetIndex = claim.index();
            work.target = claim.pos();
            work.stand = standFor(level, plot, claim.index());
            work.bestStandDistance = Double.MAX_VALUE;
            work.standFailTicks = 0;
            npc.setFarmTarget(work.stand);
            return;
        }
        touchClaim(plot, npc.getUUID(), level.getGameTime());
        if (near(npc, work.stand)) {
            if (mineTick(level, npc, work.target, work)) {
                work.breakProgress = 0;
                work.giveUpCount = 0;
                clearHeadroom(level, npc, work.target);
                releaseClaim(plot.id(), npc.getUUID());
                work.target = null;
                npc.clearFarmTarget();
                if (!inventoryHasRoom(npc.getNpcInventory())) {
                    work.stand = null;
                    work.storing = true;
                    work.storeTicks = 0;
                    work.storePos = storePoint(level, plot);
                    work.bestStandDistance = Double.MAX_VALUE;
                    work.standFailTicks = 0;
                    npc.setFarmTarget(work.storePos);
                }
            }
            return;
        }
        double distance = npc.blockPosition().distSqr(work.stand);
        npc.tickTravelPath(work.stand);
        if (distance < work.bestStandDistance - 0.01D) {
            work.bestStandDistance = distance;
            work.standFailTicks = 0;
            return;
        }
        if (++work.standFailTicks < STAND_GIVE_UP_TICKS) {
            return;
        }
        work.standFailTicks = 0;
        work.giveUpCount++;
        if (work.giveUpCount >= 2) {
            work.giveUpCount = 0;
            teleportNear(level, npc, work.stand, work.target);
            return;
        }
        skipClaim(plot, work.targetIndex, npc.getUUID(), level.getGameTime());
        work.target = null;
        work.stand = null;
        npc.haltFarm();
    }

    private static void teleportNear(ServerLevel level, NpcEntity npc, BlockPos stand, BlockPos target) {
        BlockPos dest = stand != null && isStandableAt(level, stand) ? stand : null;
        if (dest == null && target != null) {
            dest = firstStandable(level, target.above(), target);
        }
        if (dest == null) {
            return;
        }
        npc.stopConstructionPath();
        npc.getNavigation().stop();
        npc.teleportTo(dest.getX() + 0.5D, dest.getY(), dest.getZ() + 0.5D);
        npc.setDeltaMovement(Vec3.ZERO);
        npc.resetFallDistance();
    }

    private static BlockPos firstStandable(ServerLevel level, BlockPos primary, BlockPos around) {
        if (isStandableAt(level, primary)) {
            return primary;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos pos = around.relative(direction);
            if (isStandableAt(level, pos)) {
                return pos;
            }
        }
        return primary;
    }

    private static void clearHeadroom(ServerLevel level, NpcEntity npc, BlockPos grid) {
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos above = grid.above(dy);
            BlockState state = level.getBlockState(above);
            if (state.isAir() || state.is(Blocks.BEDROCK) || state.is(Blocks.LADDER)) {
                continue;
            }
            List<ItemStack> drops = Block.getDrops(state, level, above, null, npc, npc.getMainHandItem());
            level.destroyBlock(above, false, npc);
            deposit(level, npc, drops);
        }
    }

    private static boolean onDuty(NpcData data) {
        NpcSubState scheduled = NpcSchedule.subState(data.getShift(), data.getDayTime());
        if (scheduled == NpcSubState.WORKING) {
            return true;
        }
        return scheduled == NpcSubState.MOVING
                && NpcSchedule.moveDetail(data.getShift(), data.getDayTime()) == NpcDetail.GOING_WORK;
    }

    private static boolean mineTick(ServerLevel level, NpcEntity npc, BlockPos grid, Work work) {
        BlockState state = level.getBlockState(grid);
        if (state.isAir() || state.is(Blocks.BEDROCK)) {
            level.destroyBlockProgress(npc.getId(), grid, -1);
            return true;
        }
        if (state.getBlock() instanceof LiquidBlock) {
            boolean source = state.getFluidState().isSource();
            level.setBlock(grid, Blocks.AIR.defaultBlockState(), 3);
            if (source) {
                boolean lava = state.getFluidState().is(Fluids.LAVA);
                deposit(level, npc, List.of(new ItemStack(lava ? Items.LAVA_BUCKET : Items.WATER_BUCKET)));
            }
            return true;
        }
        float hardness = state.getDestroySpeed(level, grid);
        if (hardness < 0.0F) {
            return true;
        }
        float speed = Math.max(1.0F, npc.getMainHandItem().getDestroySpeed(state));
        boolean harvest = !state.requiresCorrectToolForDrops()
                || npc.getMainHandItem().isCorrectToolForDrops(state);
        work.breakProgress += speed / hardness / (harvest ? 30.0F : 100.0F);
        if (work.swingTimer-- <= 0) {
            work.swingTimer = 5;
            npc.swing(InteractionHand.MAIN_HAND, true);
        }
        level.destroyBlockProgress(npc.getId(), grid,
                (int) Math.min(9, work.breakProgress * 10.0F));
        if (work.breakProgress >= 1.0F) {
            List<ItemStack> drops = Block.getDrops(state, level, grid, null, npc, npc.getMainHandItem());
            level.destroyBlock(grid, false, npc);
            level.destroyBlockProgress(npc.getId(), grid, -1);
            deposit(level, npc, drops);
            return true;
        }
        return false;
    }

    private static BlockPos standFor(ServerLevel level, MiningPlot plot, int index) {
        int from = Math.max(0, index - 1);
        for (int k = from; k >= 0 && from - k <= STAND_LOOKBACK; k--) {
            BlockPos candidate = plot.posOf(k);
            if (isStandableAt(level, candidate)) {
                return candidate;
            }
        }
        BlockPos above = plot.posOf(index).above();
        return isStandableAt(level, above) ? above : plot.posOf(index);
    }

    private static boolean isStandableAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        return ChunkSnapshot.isWalkableFloor(level.getBlockState(pos.below()))
                && ChunkSnapshot.isPassable(level.getBlockState(pos))
                && ChunkSnapshot.isPassable(level.getBlockState(pos.above()));
    }

    private record Claim(int index, BlockPos pos) {
    }

    private static Claim pickFromQueue(ServerLevel level, MiningPlot plot, NpcEntity npc) {
        long now = level.getGameTime();
        CompoundTag tag = npc.getPersistentData();
        if (tag.getInt(NBT_LAYER_Y) != plot.currentY()) {
            return null;
        }
        int[] queue = tag.getIntArray(NBT_LAYER_QUEUE);
        if (queue.length == 0) {
            return null;
        }
        LayerClaims claims = CLAIMS.computeIfAbsent(plot.id(), id -> new LayerClaims());
        if (claims.y != plot.currentY()) {
            claims.y = plot.currentY();
            claims.owners.clear();
            claims.held.clear();
            claims.beats.clear();
            claims.skippedUntil.clear();
        }
        pruneClaims(claims, now);
        Integer mine = claims.held.get(npc.getUUID());
        if (mine != null) {
            claims.beats.put(npc.getUUID(), now);
            return new Claim(mine, plot.posOf(mine));
        }
        for (int index : queue) {
            Long skipUntil = claims.skippedUntil.get(index);
            if (skipUntil != null && now < skipUntil) {
                continue;
            }
            BlockPos pos = plot.posOf(index);
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.is(Blocks.BEDROCK) || state.is(Blocks.LADDER)) {
                continue;
            }
            if (claims.owners.containsKey(index)) {
                continue;
            }
            claims.owners.put(index, npc.getUUID());
            claims.held.put(npc.getUUID(), index);
            claims.beats.put(npc.getUUID(), now);
            return new Claim(index, pos);
        }
        return null;
    }

    private static void touchClaim(MiningPlot plot, UUID npcId, long now) {
        LayerClaims claims = CLAIMS.get(plot.id());
        if (claims == null) {
            return;
        }
        pruneClaims(claims, now);
        if (claims.held.containsKey(npcId)) {
            claims.beats.put(npcId, now);
        }
    }

    private static void pruneClaims(LayerClaims claims, long now) {
        claims.beats.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= CLAIM_TIMEOUT_TICKS) {
                return false;
            }
            Integer index = claims.held.remove(entry.getKey());
            if (index != null) {
                claims.owners.remove(index);
            }
            return true;
        });
    }

    private static void releaseClaim(UUID plotId, UUID npcId) {
        LayerClaims claims = CLAIMS.get(plotId);
        if (claims == null) {
            return;
        }
        Integer index = claims.held.remove(npcId);
        if (index != null) {
            claims.owners.remove(index);
        }
        claims.beats.remove(npcId);
    }

    private static void skipClaim(MiningPlot plot, int index, UUID npcId, long now) {
        LayerClaims claims = CLAIMS.get(plot.id());
        if (claims != null) {
            claims.skippedUntil.put(index, now + SKIP_RETRY_TICKS);
        }
        releaseClaim(plot.id(), npcId);
    }

    public static void sealLayer(ServerLevel level, MiningPlot plot) {
        buildWall(level, plot, plot.currentY());
        placeLadder(level, plot, plot.currentY());
    }

    private static void buildWall(ServerLevel level, MiningPlot plot, int y) {
        for (int x = plot.min().getX() - 1; x <= plot.max().getX() + 1; x++) {
            for (int z = plot.min().getZ() - 1; z <= plot.max().getZ() + 1; z++) {
                boolean edge = x == plot.min().getX() - 1 || x == plot.max().getX() + 1
                        || z == plot.min().getZ() - 1 || z == plot.max().getZ() + 1;
                if (!edge) {
                    continue;
                }
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.COBBLESTONE) || state.is(Blocks.BEDROCK)) {
                    continue;
                }
                level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3);
            }
        }
    }

    private static void placeLadder(ServerLevel level, MiningPlot plot, int y) {
        BlockPos anchor = null;
        for (long cell : plot.cells()) {
            BlockPos base = BlockPos.of(cell);
            if (base.getX() == plot.min().getX()) {
                anchor = base;
                break;
            }
        }
        if (anchor == null) {
            for (long cell : plot.cells()) {
                anchor = BlockPos.of(cell);
                break;
            }
        }
        if (anchor == null) {
            return;
        }
        BlockPos pos = new BlockPos(anchor.getX(), y, anchor.getZ());
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.LADDER)) {
            return;
        }
        if (!state.isAir()) {
            level.destroyBlock(pos, false);
        }
        BlockPos behind = pos.west();
        if (!level.getBlockState(behind).isSolidRender(level, behind)) {
            level.setBlock(behind, Blocks.COBBLESTONE.defaultBlockState(), 3);
        }
        level.setBlock(pos, Blocks.LADDER.defaultBlockState()
                .setValue(LadderBlock.FACING, Direction.EAST), 3);
    }

    private static void deposit(ServerLevel level, NpcEntity npc, List<ItemStack> drops) {
        NpcInventory inventory = npc.getNpcInventory();
        for (ItemStack drop : drops) {
            if (drop == null || drop.isEmpty()) {
                continue;
            }
            ItemStack remaining = insertIntoInventory(inventory, drop.copy());
            if (!remaining.isEmpty()) {
                Block.popResource(level, npc.blockPosition(), remaining);
            }
        }
    }

    private static ItemStack insertIntoInventory(NpcInventory inventory, ItemStack stack) {
        ItemStack remaining = stack;
        for (int slot = 0; slot < inventory.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = inventory.insertItem(slot, remaining, false);
        }
        return remaining;
    }

    private static boolean inventoryHasRoom(NpcInventory inventory) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private static void tickStore(ServerLevel level, NpcEntity npc, MiningPlot plot, Work work) {
        BuildingRecord building = BuildingRegistry.INSTANCE.byId(plot.buildingId());
        if (building == null) {
            work.storing = false;
            return;
        }
        if (nearStore(npc, building)) {
            transferToStore(level, npc, building);
            work.storing = false;
            work.storePos = null;
            work.storeTicks = 0;
            work.bestStandDistance = Double.MAX_VALUE;
            work.standFailTicks = 0;
            if (!inventoryHasRoom(npc.getNpcInventory())) {
                work.stalled = true;
                work.stallTicks = 0;
                npc.haltFarm();
            }
            return;
        }
        BlockPos storePos = work.storePos;
        if (storePos == null) {
            storePos = storePoint(level, plot);
            work.storePos = storePos;
        }
        npc.tickTravelPath(storePos);
        if (++work.storeTicks > STORE_GIVE_UP_TICKS) {
            work.storing = false;
            work.storeTicks = 0;
            work.storePos = null;
        }
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

    private static BlockPos storePoint(ServerLevel level, MiningPlot plot) {
        BuildingRecord building = BuildingRegistry.INSTANCE.byId(plot.buildingId());
        if (building == null) {
            return plot.min();
        }
        BlockPos min = building.min();
        BlockPos max = building.max();
        int x = (min.getX() + max.getX()) / 2;
        int z = (min.getZ() + max.getZ()) / 2;
        for (int y = max.getY(); y >= min.getY(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (isStandableAt(level, pos)) {
                return pos;
            }
        }
        return new BlockPos(x, max.getY(), z);
    }

    private static void tickStalled(ServerLevel level, NpcEntity npc, MiningPlot plot, Work work) {
        work.stallTicks++;
        if (work.stallTicks == 1) {
            npc.haltFarm();
        }
        if (work.stallTicks < STALL_RECHECK_TICKS) {
            return;
        }
        work.stallTicks = 0;
        if (inventoryHasRoom(npc.getNpcInventory())) {
            work.stalled = false;
            work.idleWait = 0;
            return;
        }
        BuildingRecord building = BuildingRegistry.INSTANCE.byId(plot.buildingId());
        if (building == null || !storeHasRoom(level, building)) {
            return;
        }
        work.stalled = false;
        work.idleWait = 0;
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

    private static boolean nearStore(NpcEntity npc, BuildingRecord building) {
        BlockPos min = building.min();
        BlockPos max = building.max();
        return npc.getX() >= min.getX() - 2.0D && npc.getX() <= max.getX() + 3.0D
                && npc.getZ() >= min.getZ() - 2.0D && npc.getZ() <= max.getZ() + 3.0D
                && npc.getY() >= min.getY() - 2.0D && npc.getY() <= max.getY() + 4.0D;
    }

    private static boolean near(NpcEntity npc, BlockPos target) {
        double dx = npc.getX() - (target.getX() + 0.5D);
        double dz = npc.getZ() - (target.getZ() + 0.5D);
        double dy = Math.abs(Math.floor(npc.getY() + 0.5D) - target.getY());
        return dx * dx + dz * dz <= MINE_ARRIVE_SQR && dy <= 1.0D;
    }

    private static void givePickaxe(NpcEntity npc) {
        ItemStack pickaxe = new ItemStack(Items.NETHERITE_PICKAXE);
        pickaxe.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
        npc.setHeldItem(pickaxe);
    }

    private static MiningPlot plotOf(NpcData data) {
        UUID plotId = data.getId() == null ? null : WORKER_PLOT.get(data.getId());
        if (plotId != null) {
            MiningPlot plot = MiningPlotManager.INSTANCE.byId(plotId);
            if (plot != null) {
                return plot;
            }
        }
        UUID buildingId = parseId(data.getWorkplaceId());
        if (buildingId == null) {
            return null;
        }
        MiningPlot fallback = MiningPlotManager.INSTANCE.byBuilding(buildingId);
        if (fallback != null) {
            WORKER_PLOT.put(data.getId(), fallback.id());
        }
        return fallback;
    }

    private static BuildingRecord buildingOf(NpcData data) {
        UUID id = parseId(data.getWorkplaceId());
        return id == null ? null : BuildingRegistry.INSTANCE.byId(id);
    }

    private static boolean isMiningBuilding(BuildingRecord building) {
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        return meta != null && meta.industryKind() == IndustryKind.MINING;
    }

    private static UUID parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (RuntimeException exception) {
            return null;
        }
    }
}


