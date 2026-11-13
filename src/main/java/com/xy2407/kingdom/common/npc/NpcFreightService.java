package com.xy2407.kingdom.common.npc;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.ItemCount;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.freight.FreightOrder;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;
import com.xy2407.kingdom.server.freight.FreightService;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.UUID;

/** 仓库员工的货运流程：走到仓库取货，送到需求方，再回仓库待命。 */
public final class NpcFreightService {

    private static final int ACTION_INTERVAL_TICKS = 20;
    private static final double ARRIVE_SQR = 9.0D;
    private static final double WALK_SPEED = 1.0D;

    private NpcFreightService() {
    }

    public static boolean isCarrying(UUID npcId) {
        return FreightService.INSTANCE.orderOfCarrier(npcId) != null;
    }

    public static void tick(NpcEntity npc, NpcData data) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return;
        }
        if (npc.tickCount % ACTION_INTERVAL_TICKS != 0) {
            return;
        }
        FreightOrder order = FreightService.INSTANCE.orderOfCarrier(data.getId());
        if (order == null) {
            npc.getNavigation().stop();
            return;
        }
        BuildingRecord warehouse = BuildingRegistry.INSTANCE.byId(order.warehouseId());
        if (warehouse == null) {
            FreightService.INSTANCE.failCarrying(level.getServer(), order, "仓库已不存在");
            return;
        }
        BlockPos destination = destinationOf(level, order);
        if (destination == null) {
            FreightService.INSTANCE.failCarrying(level.getServer(), order, "需求方已不存在");
            return;
        }
        BuildingRecord pickup = order.pickupId() == null ? warehouse
                : BuildingRegistry.INSTANCE.byId(order.pickupId());
        if (pickup == null) {
            FreightService.INSTANCE.failCarrying(level.getServer(), order, "取货点已不存在");
            return;
        }
        switch (order.state()) {
            case ASSIGNED -> {
                if (walkTo(npc, center(pickup))) {
                    order.setState(FreightOrder.State.PICKING_UP);
                }
            }
            case PICKING_UP -> {
                if (order.target() == FreightOrder.Target.WAREHOUSE) {
                    // 清仓订单：货源有多少搬多少，一件都没有才算失败
                    if (!loadOnboard(level, npc, pickup, order)) {
                        FreightService.INSTANCE.fail(order, "货源地已清空");
                        return;
                    }
                } else {
                    if (!hasAll(level, pickup, order)) {
                        FreightService.INSTANCE.fail(order, "取货点库存不足");
                        return;
                    }
                    loadOnboard(level, npc, pickup, order);
                }
                order.setState(FreightOrder.State.DELIVERING);
            }
            case DELIVERING -> {
                if (walkTo(npc, destination) && unload(level, npc, order, destination, pickup)) {
                    order.setState(FreightOrder.State.RETURNING);
                }
            }
            case RETURNING -> {
                if (walkTo(npc, center(warehouse))) {
                    FreightService.INSTANCE.complete(order);
                }
            }
            default -> {
            }
        }
    }

    private static BlockPos destinationOf(ServerLevel level, FreightOrder order) {
        if (order.target() == FreightOrder.Target.CONTAINER) {
            BlockPos pos = order.destination();
            if (pos == null) {
                return null;
            }
            return level.getBlockEntity(pos) instanceof Container ? pos : null;
        }
        if (order.target() == FreightOrder.Target.WAREHOUSE) {
            BuildingRecord target = BuildingRegistry.INSTANCE.byId(order.warehouseId());
            return target == null ? null : center(target);
        }
        BuildingRecord requester = BuildingRegistry.INSTANCE.byId(order.requesterId());
        return requester == null ? null : center(requester);
    }

    private static boolean walkTo(NpcEntity npc, BlockPos target) {
        double dx = npc.getX() - (target.getX() + 0.5D);
        double dz = npc.getZ() - (target.getZ() + 0.5D);
        if (dx * dx + dz * dz <= ARRIVE_SQR && Math.abs(npc.getY() - target.getY()) < 2.0D) {
            npc.getNavigation().stop();
            return true;
        }
        npc.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, WALK_SPEED);
        return false;
    }

    private static BlockPos center(BuildingRecord record) {
        int x = (record.min().getX() + record.max().getX()) / 2;
        int z = (record.min().getZ() + record.max().getZ()) / 2;
        return new BlockPos(x, record.min().getY(), z);
    }

    private static IItemHandler storeOf(ServerLevel level, BuildingRecord record) {
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(record);
        if (meta == null) {
            return null;
        }
        return BuildingStorageStore.INSTANCE.handlerOf(level.getServer(), record.id(), meta.storageSlots());
    }

    private static boolean hasAll(ServerLevel level, BuildingRecord warehouse, FreightOrder order) {
        IItemHandler store = storeOf(level, warehouse);
        return store != null && FreightService.hasAll(store, order.items());
    }

    /** 从货源装载订单货物；返回是否至少搬到了一件。 */
    private static boolean loadOnboard(ServerLevel level, NpcEntity npc, BuildingRecord warehouse,
                                       FreightOrder order) {
        IItemHandler store = storeOf(level, warehouse);
        if (store == null) {
            return false;
        }
        boolean loaded = false;
        for (ItemCount need : order.items()) {
            Item item = FreightService.itemOf(need.item());
            if (item == null) {
                continue;
            }
            int remaining = need.count();
            for (int slot = 0; slot < store.getSlots() && remaining > 0; slot++) {
                ItemStack stack = store.getStackInSlot(slot);
                if (stack.isEmpty() || !stack.is(item)) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                ItemStack extracted = store.extractItem(slot, take, false);
                if (extracted.isEmpty()) {
                    continue;
                }
                remaining -= extracted.getCount();
                loaded = true;
                insertOnboard(npc, store, extracted);
            }
        }
        return loaded;
    }

    /** 只往背包 24 格装货；装不下的原样放回货源。 */
    private static void insertOnboard(NpcEntity npc, IItemHandler store, ItemStack stack) {
        int remaining = stack.getCount();
        NpcInventory inventory = npc.getNpcInventory();
        for (int slot = NpcInventory.BAG_START; slot < NpcInventory.SLOT_COUNT && remaining > 0; slot++) {
            ItemStack left = inventory.insertItem(slot, new ItemStack(stack.getItem(), remaining), false);
            remaining = left.getCount();
        }
        if (remaining > 0) {
            insertIntoHandler(store, new ItemStack(stack.getItem(), remaining));
        }
    }

    private static boolean unload(ServerLevel level, NpcEntity npc, FreightOrder order,
                                  BlockPos destination, BuildingRecord pickup) {
        if (order.target() == FreightOrder.Target.CONTAINER) {
            if (!(level.getBlockEntity(destination) instanceof Container container)) {
                return false;
            }
            for (ItemCount need : order.items()) {
                takeFromOnboard(npc, need, stack -> {
                    insertIntoContainer(container, stack);
                    return ItemStack.EMPTY;
                });
            }
            return true;
        }
        UUID deliverTo = order.target() == FreightOrder.Target.WAREHOUSE
                ? order.warehouseId() : order.requesterId();
        BuildingRecord requester = BuildingRegistry.INSTANCE.byId(deliverTo);
        IItemHandler store = requester == null ? null : storeOf(level, requester);
        if (store == null) {
            return false;
        }
        if (order.target() == FreightOrder.Target.WAREHOUSE) {
            // 清仓交付：能卸多少卸多少，卸不掉的当场回族源地，货物不卡在运输员身上
            IItemHandler source = pickup == null ? null : storeOf(level, pickup);
            for (ItemCount need : order.items()) {
                takeFromOnboard(npc, need, stack -> {
                    ItemStack rest = insertIntoHandler(store, stack);
                    if (!rest.isEmpty() && source != null) {
                        rest = insertIntoHandler(source, rest);
                    }
                    // 退不回去就留在背包里，绝不让货物凭空消失
                    return rest;
                });
            }
            return true;
        }
        for (ItemCount need : order.items()) {
            takeFromOnboard(npc, need, stack -> {
                insertIntoHandler(store, stack);
                return ItemStack.EMPTY;
            });
        }
        return true;
    }

    private interface Sink {
        /** 接收一 stack，返回没接收的部分。 */
        ItemStack accept(ItemStack stack);
    }

    private static void takeFromOnboard(NpcEntity npc, ItemCount need, Sink sink) {
        Item item = FreightService.itemOf(need.item());
        if (item == null) {
            return;
        }
        int remaining = need.count();
        NpcInventory inventory = npc.getNpcInventory();
        for (int slot = NpcInventory.BAG_START; slot < NpcInventory.SLOT_COUNT && remaining > 0; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || !stack.is(item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            ItemStack extracted = inventory.extractItem(slot, take, false);
            if (extracted.isEmpty()) {
                continue;
            }
            remaining -= extracted.getCount();
            ItemStack rest = sink.accept(extracted);
            if (rest != null && !rest.isEmpty()) {
                inventory.insertItem(slot, rest, false);
            }
        }
    }

    private static ItemStack insertIntoHandler(IItemHandler store, ItemStack stack) {
        int remaining = stack.getCount();
        for (int slot = 0; slot < store.getSlots() && remaining > 0; slot++) {
            ItemStack left = store.insertItem(slot, new ItemStack(stack.getItem(), remaining), false);
            remaining = left.getCount();
        }
        return remaining <= 0 ? ItemStack.EMPTY : new ItemStack(stack.getItem(), remaining);
    }

    private static void insertIntoContainer(Container container, ItemStack stack) {
        int remaining = stack.getCount();
        int limit = Math.min(container.getMaxStackSize(), stack.getMaxStackSize());
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack current = container.getItem(slot);
            if (current.isEmpty()) {
                ItemStack placed = stack.copy();
                placed.setCount(Math.min(remaining, limit));
                container.setItem(slot, placed);
                remaining -= placed.getCount();
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(current, stack)) {
                continue;
            }
            int room = Math.min(limit - current.getCount(), remaining);
            if (room > 0) {
                current.grow(room);
                container.setChanged();
                remaining -= room;
            }
        }
    }
}
