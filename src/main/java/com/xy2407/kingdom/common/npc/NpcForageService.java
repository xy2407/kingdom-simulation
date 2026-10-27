package com.xy2407.kingdom.common.npc;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.ItemSpecs;
import com.xy2407.kingdom.common.building.MenuSpec;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.restaurant.RestaurantOrder;
import com.xy2407.kingdom.common.restaurant.RestaurantSeat;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;
import com.xy2407.kingdom.server.restaurant.RestaurantOrderStore;
import com.xy2407.kingdom.server.restaurant.RestaurantSeatStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 觅食执行服务：每周期按行装、餐厅、容器、掉落物顺序补食，都找不到就在附近随机走动再找。
 */
public final class NpcForageService {
    private static final int CONTAINER_RADIUS = 5;
    private static final int COMMERCIAL_RADIUS = 64;
    private static final double ARRIVE_DISTANCE_SQR = 6.25D;

    private static final double FORAGE_WANDER_RADIUS = 10.0D;

    private static final Map<UUID, Integer> WAITING = new ConcurrentHashMap<>();

    private static final int WAIT_LIMIT_TICKS = 20 * 60 * 5;

    private NpcForageService() {
    }

    public static void tick(NpcEntity npc, NpcData data) {
        double needed = NpcData.HUNGER_MAX - data.getHunger();
        if (needed <= 0.0D) {
            finishDining(npc, data);
            return;
        }
        RestaurantOrder order = RestaurantOrderStore.INSTANCE.byCustomer(data.getId());
        if (order == null && data.getDetail() == NpcDetail.DINING) {
            order = reorder(npc, data);
        }
        if (order != null) {
            tickDining(npc, data, order);
            return;
        }
        BlockPos heading = npc.getForageTarget();
        if (heading != null) {
            if (arrived(npc, heading)) {
                npc.clearForageTarget();
                placeOrder(npc, data, heading);
            } else {
                npc.tickTravelPath(heading);
                if (!npc.isTravelling()) {
                    npc.clearForageTarget();
                }
            }
            return;
        }
        if (!data.getBrain().consumeForageDue()) {
            return;
        }
        if (eatFromBackpack(npc, data, needed)) {
            return;
        }
        BlockPos dining = diningOf(npc);
        if (dining != null) {
            npc.setForageTarget(dining);
            return;
        }
        if (eatFromContainers(npc, data, needed)) {
            return;
        }
        if (eatFromGroundItems(npc, data, needed)) {
            return;
        }
        if (!npc.walkToRandomPoint(npc.blockPosition(), FORAGE_WANDER_RADIUS)) {
            npc.haltForage();
        }
    }

    private static boolean arrived(NpcEntity npc, BlockPos target) {
        double dx = npc.getX() - (target.getX() + 0.5D);
        double dz = npc.getZ() - (target.getZ() + 0.5D);
        return dx * dx + dz * dz <= ARRIVE_DISTANCE_SQR;
    }

    private static BlockPos diningOf(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return null;
        }
        return BuildingRegistry.INSTANCE.findDining(level.dimension().location().toString(),
                npc.getX(), npc.getY(), npc.getZ(), COMMERCIAL_RADIUS);
    }

    private static boolean placeOrder(NpcEntity npc, NpcData data, BlockPos heading) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return false;
        }
        BuildingRecord restaurant = restaurantAt(level, heading);
        return restaurant != null && placeOrderAt(level, npc, data, restaurant, heading);
    }

    private static RestaurantOrder reorder(NpcEntity npc, NpcData data) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return null;
        }
        BuildingRecord restaurant = restaurantAt(level, npc.blockPosition());
        if (restaurant == null) {
            return null;
        }
        placeOrderAt(level, npc, data, restaurant, npc.blockPosition());
        return RestaurantOrderStore.INSTANCE.byCustomer(data.getId());
    }

    private static boolean placeOrderAt(ServerLevel level, NpcEntity npc, NpcData data,
                                        BuildingRecord restaurant, BlockPos heading) {
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(restaurant);
        if (meta == null || meta.menu().isEmpty()) {
            return false;
        }
        if (!NpcRestaurantService.rolesOf(restaurant.id()).open()) {
            return false;
        }
        BlockPos seat = null;
        String dimension = level.dimension().location().toString();
        for (RestaurantSeat candidate : RestaurantSeatStore.INSTANCE.seatsOf(restaurant.id())) {
            if (!dimension.equals(candidate.dimension())) {
                continue;
            }
            if (RestaurantOrderStore.INSTANCE.occupySeat(candidate.seat(), data.getId())) {
                seat = candidate.seat();
                break;
            }
        }
        if (seat == null) {
            return false;
        }
        MenuSpec dish = meta.menu().get(0);
        RestaurantOrderStore.INSTANCE.put(new RestaurantOrder(data.getId(), restaurant.id(),
                level.dimension().location().toString(), seat, dish.output().item(),
                RestaurantOrder.Status.PENDING));
        data.setDetail(NpcDetail.DINING);
        if (heading != null) {
            npc.setForageTarget(seat);
        }
        return true;
    }

    private static void tickDining(NpcEntity npc, NpcData data, RestaurantOrder order) {
        BlockPos seat = order.seat();
        if (!arrived(npc, seat)) {
            if (npc.isSitting()) {
                npc.standUpFromSeat();
            }
            npc.setForageTarget(seat);
            return;
        }
        npc.getNavigation().stop();
        if (!npc.isSitting()) {
            npc.sitAt(seat);
        }
        if (order.status() == RestaurantOrder.Status.SERVING) {
            data.setHunger(NpcData.HUNGER_MAX);
            finishDining(npc, data);
            return;
        }
        int waited = WAITING.merge(data.getId(), 1, Integer::sum);
        if (waited > WAIT_LIMIT_TICKS) {
            finishDining(npc, data);
        }
    }

    private static void finishDining(NpcEntity npc, NpcData data) {
        RestaurantOrderStore.INSTANCE.remove(data.getId());
        WAITING.remove(data.getId());
        npc.standUpFromSeat();
        npc.clearForageTarget();
        if (data.getDetail() == NpcDetail.DINING) {
            data.setDetail(NpcDetail.FORAGING);
        }
    }

    private static BuildingRecord restaurantAt(ServerLevel level, BlockPos heading) {
        String dimension = level.dimension().location().toString();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BuildingRecord hit = BuildingRegistry.INSTANCE.at(dimension,
                        heading.getX() + dx + 0.5D, heading.getY() + 0.5D, heading.getZ() + dz + 0.5D);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static ItemStack takeMeal(IItemHandler store, BuildingMeta meta) {
        for (MenuSpec dish : meta.menu()) {
            ItemStack meal = extractOne(store, dish.output().item());
            if (!meal.isEmpty()) {
                return meal;
            }
        }
        for (int slot = 0; slot < store.getSlots(); slot++) {
            ItemStack stack = store.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.get(DataComponents.FOOD) != null) {
                return store.extractItem(slot, 1, false);
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack extractOne(IItemHandler store, String spec) {
        for (int slot = 0; slot < store.getSlots(); slot++) {
            ItemStack stack = store.getStackInSlot(slot);
            if (ItemSpecs.matches(stack, spec)) {
                return store.extractItem(slot, 1, false);
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean eatFromBackpack(NpcEntity npc, NpcData data, double needed) {
        NpcInventory inventory = npc.getNpcInventory();
        double remaining = needed;
        for (int slot = 0; slot < inventory.getSlots() && remaining > 0.0D; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null || food.nutrition() <= 0) {
                continue;
            }
            int count = Math.min(stack.getCount(), (int) Math.ceil(remaining / food.nutrition()));
            inventory.extractItem(slot, count, false);
            remaining -= (double) count * food.nutrition();
        }
        return applyFood(data, needed, remaining);
    }

    private static boolean eatFromContainers(NpcEntity npc, NpcData data, double needed) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return false;
        }
        BlockPos center = npc.blockPosition();
        double remaining = needed;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-CONTAINER_RADIUS, -CONTAINER_RADIUS, -CONTAINER_RADIUS),
                center.offset(CONTAINER_RADIUS, CONTAINER_RADIUS, CONTAINER_RADIUS))) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof Container container)) {
                continue;
            }
            remaining = consumeFromContainer(container, remaining);
            if (remaining <= 0.0D) {
                break;
            }
        }
        return applyFood(data, needed, remaining);
    }

    private static double consumeFromContainer(Container container, double remaining) {
        for (int i = 0; i < container.getContainerSize() && remaining > 0.0D; i++) {
            ItemStack stack = container.getItem(i);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null || food.nutrition() <= 0) {
                continue;
            }
            int count = Math.min(stack.getCount(), (int) Math.ceil(remaining / food.nutrition()));
            stack.shrink(count);
            container.setItem(i, stack);
            container.setChanged();
            remaining -= (double) count * food.nutrition();
        }
        return remaining;
    }

    private static boolean eatFromGroundItems(NpcEntity npc, NpcData data, double needed) {
        AABB box = npc.getBoundingBox().inflate(CONTAINER_RADIUS);
        List<ItemEntity> items = npc.level().getEntitiesOfClass(ItemEntity.class, box);
        double remaining = needed;
        for (ItemEntity item : items) {
            if (remaining <= 0.0D) {
                break;
            }
            ItemStack stack = item.getItem();
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null || food.nutrition() <= 0) {
                continue;
            }
            int count = Math.min(stack.getCount(), (int) Math.ceil(remaining / food.nutrition()));
            stack.shrink(count);
            remaining -= (double) count * food.nutrition();
            if (stack.isEmpty()) {
                item.discard();
            } else {
                item.setItem(stack.copy());
            }
        }
        return applyFood(data, needed, remaining);
    }

    private static boolean applyFood(NpcData data, double needed, double remaining) {
        if (remaining >= needed) {
            return false;
        }
        data.setHunger(Math.min(NpcData.HUNGER_MAX, data.getHunger() + (needed - remaining)));
        return true;
    }
}
