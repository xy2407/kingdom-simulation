package com.xy2407.kingdom.common.npc;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.building.ItemCount;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;
import com.xy2407.kingdom.server.building.WarehouseLinkStore;
import com.xy2407.kingdom.server.freight.FreightService;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 产物入库共用件：只搬运背包 24 格产物（主手/副手/四件护甲不动），
 * 建筑仓库存满时按「管辖仓库优先，否则最近仓库」向货运系统发起清仓订单。
 */
public final class ProduceStoring {

    /** 同一建筑两次清仓订单之间的最小间隔。 */
    private static final long PICKUP_COOLDOWN_TICKS = 600L;

    private static final Map<UUID, Long> PICKUP_COOLDOWN = new HashMap<>();

    private ProduceStoring() {
    }

    /** 背包 24 格里是否还有产物。 */
    public static boolean bagHasProduce(NpcInventory inventory) {
        if (inventory == null) {
            return false;
        }
        for (int slot = NpcInventory.BAG_START; slot < NpcInventory.SLOT_COUNT; slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 背包 24 格里是否还有空位或未满的堆。 */
    public static boolean bagHasRoom(NpcInventory inventory) {
        if (inventory == null) {
            return false;
        }
        for (int slot = NpcInventory.BAG_START; slot < NpcInventory.SLOT_COUNT; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /** 建筑仓库是否还有可插入空间。 */
    public static boolean storeHasRoom(ServerLevel level, BuildingRecord building) {
        IItemHandler store = storeOf(level, building);
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

    /**
     * 把背包 24 格产物尽量存入建筑仓库；能存多少存多少，剩余留在背包。
     *
     * @return 背包是否已清空
     */
    public static boolean depositBag(ServerLevel level, NpcEntity npc, BuildingRecord building) {
        NpcInventory inventory = npc == null ? null : npc.getNpcInventory();
        IItemHandler store = storeOf(level, building);
        if (inventory == null || store == null) {
            return !bagHasProduce(inventory);
        }
        for (int slot = NpcInventory.BAG_START; slot < NpcInventory.SLOT_COUNT; slot++) {
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
                return false;
            }
        }
        return true;
    }

    /**
     * 仓库满时发起清仓运输：先找管辖本建筑的仓库（地图架管辖清单），
     * 没有管辖就选距离最近的仓库建筑；订单按提交顺序排队执行。
     */
    public static void requestPickup(ServerLevel level, BuildingRecord source) {
        if (level == null || source == null || level.getServer() == null) {
            return;
        }
        MinecraftServer server = level.getServer();
        long now = level.getGameTime();
        Long next = PICKUP_COOLDOWN.get(source.id());
        if (next != null && now < next) {
            return;
        }
        PICKUP_COOLDOWN.put(source.id(), now + PICKUP_COOLDOWN_TICKS);
        List<ItemCount> items = snapshot(server, source);
        if (items.isEmpty()) {
            return;
        }
        BuildingRecord destination = warehouseOf(server, source);
        if (destination == null) {
            destination = nearestWarehouse(server, source);
        }
        if (destination == null) {
            return;
        }
        FreightService.INSTANCE.submitWarehousePickup(server, source.id(), source.dimension(),
                items, destination.id());
    }

    /** 数据库里的归属仓库；没绑定或已失效就返回 null，交给就近选择。 */
    private static BuildingRecord warehouseOf(MinecraftServer server, BuildingRecord source) {
        UUID warehouseId = WarehouseLinkStore.INSTANCE.warehouseOf(source.id());
        if (warehouseId == null) {
            return null;
        }
        BuildingRecord warehouse = BuildingRegistry.INSTANCE.byId(warehouseId);
        if (warehouse == null || !warehouse.dimension().equals(source.dimension())) {
            return null;
        }
        return warehouse;
    }

    /**
     * 把背包 24 格产物全部掉在脚边（货源地已消失时的兜底，不让东西悬在背包里消失）。
     */
    public static void dropBag(ServerLevel level, NpcEntity npc) {
        if (level == null || npc == null) {
            return;
        }
        NpcInventory inventory = npc.getNpcInventory();
        for (int slot = NpcInventory.BAG_START; slot < NpcInventory.SLOT_COUNT; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            net.minecraft.world.level.block.Block.popResource(level, npc.blockPosition(), stack.copy());
            inventory.setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    /** 建筑仓库范围内的可站立点，供 NPC 前往存货。 */
    public static BlockPos storePoint(ServerLevel level, BuildingRecord building) {
        BlockPos min = building.min();
        BlockPos max = building.max();
        int x = (min.getX() + max.getX()) / 2;
        int z = (min.getZ() + max.getZ()) / 2;
        for (int y = max.getY(); y >= min.getY(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.isLoaded(pos) && level.getBlockState(pos).isAir()
                    && level.getBlockState(pos.below()).isSolid()) {
                return pos;
            }
        }
        return new BlockPos(x, max.getY() + 1, z);
    }

    private static IItemHandler storeOf(ServerLevel level, BuildingRecord building) {
        if (level == null || building == null || level.getServer() == null) {
            return null;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        if (meta == null || meta.storageSlots() <= 0) {
            return null;
        }
        return BuildingStorageStore.INSTANCE.handlerOf(level.getServer(), building.id(),
                meta.storageSlots());
    }

    /** 建筑仓库当前内容清单（物品 id → 总数），作为清仓订单的货物快照。 */
    private static List<ItemCount> snapshot(MinecraftServer server, BuildingRecord source) {
        ServerLevel level = levelOf(server, source.dimension());
        IItemHandler store = storeOf(level, source);
        if (store == null) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < store.getSlots(); slot++) {
            ItemStack stack = store.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(), Integer::sum);
        }
        List<ItemCount> items = new java.util.ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            items.add(new ItemCount(entry.getKey(), entry.getValue()));
        }
        return items;
    }

    private static BuildingRecord nearestWarehouse(MinecraftServer server, BuildingRecord source) {
        BuildingRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BuildingRecord record : BuildingRegistry.INSTANCE.all()) {
            if (!record.dimension().equals(source.dimension()) || record.id().equals(source.id())) {
                continue;
            }
            BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(record);
            if (meta == null || !meta.isWork(BuildingWork.WAREHOUSE)) {
                continue;
            }
            double distance = record.min().distSqr(source.min());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = record;
            }
        }
        return best;
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }
}
