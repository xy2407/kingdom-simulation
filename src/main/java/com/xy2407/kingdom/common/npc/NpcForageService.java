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
import com.xy2407.kingdom.common.restaurant.RestaurantSeats;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;
import com.xy2407.kingdom.server.restaurant.RestaurantOrderStore;

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

    /** 等菜计时（内存态，重启丢失无妨）：超时就撤单，免得一直占着座位。 */
    private static final Map<UUID, Integer> WAITING = new ConcurrentHashMap<>();

    /** 等菜上限 5 分钟：菜迟迟不上（缺料 / 没厨师 / 没服务员）就换地方。 */
    private static final int WAIT_LIMIT_TICKS = 20 * 60 * 5;

    private NpcForageService() {
    }

    public static void tick(NpcEntity npc, NpcData data) {
        double needed = NpcData.HUNGER_MAX - data.getHunger();
        if (needed <= 0.0D) {
            finishDining(npc, data);
            return;
        }
        // 手上有单（或人已经坐在店里但单丢了）→ 走"坐下等菜"这条分支
        RestaurantOrder order = RestaurantOrderStore.INSTANCE.byCustomer(data.getId());
        if (order == null && data.getDetail() == NpcDetail.DINING) {
            // 订单没了但人还坐在店里：多半是服务端重启把订单丢了。立刻补一张，
            // 否则这个顾客会永远坐在座位上等一份不会来的菜（这正是要解决的自愈问题）。
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
                // 到店：占个座、下一张单（落库）；从下一 tick 起走 tickDining 等服务员上菜。
                placeOrder(npc, data, heading);
            } else if (!npc.getNavigation().isInProgress()) {
                npc.clearForageTarget();
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

    /** 到店就座：按落点找到这家店，然后下单。 */
    private static boolean placeOrder(NpcEntity npc, NpcData data, BlockPos heading) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return false;
        }
        BuildingRecord restaurant = restaurantAt(level, heading);
        return restaurant != null && placeOrderAt(level, npc, data, restaurant, heading);
    }

    /** 订单丢了（重启）但人还在店里：用他脚下这家店补一张单，补上就返回新订单。 */
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

    /**
     * 下单本体：菜品取菜单第一道（后续可改成按价格/库存挑），座位从可坐点里占第一个空的。
     * 占不到座位或店里没菜单都返回 false，让上层继续找别家。
     */
    private static boolean placeOrderAt(ServerLevel level, NpcEntity npc, NpcData data,
                                        BuildingRecord restaurant, BlockPos heading) {
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(restaurant);
        if (meta == null || meta.menu().isEmpty()) {
            return false;
        }
        BlockPos seat = null;
        for (BlockPos candidate : RestaurantSeats.seats(level, restaurant)) {
            if (RestaurantOrderStore.INSTANCE.occupySeat(candidate, data.getId())) {
                seat = candidate;
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

    /**
     * 坐下等菜：走到座位 → 坐下 → 等服务员把菜送到桌上（订单变 SERVING）→ 吃掉、删单走人。
     * 等太久（店里一直做不出来）也撤单，免得占着座位不放。
     */
    private static void tickDining(NpcEntity npc, NpcData data, RestaurantOrder order) {
        BlockPos seat = order.seat();
        if (!arrived(npc, seat)) {
            if (npc.isSitting()) {
                npc.setSitting(false);
            }
            npc.setForageTarget(seat);
            return;
        }
        npc.getNavigation().stop();
        if (!npc.isSitting()) {
            npc.setSitting(true);
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

    /** 收尾：删单、放开座位、收起坐姿，并退回普通觅食状态。 */
    private static void finishDining(NpcEntity npc, NpcData data) {
        RestaurantOrderStore.INSTANCE.remove(data.getId());
        WAITING.remove(data.getId());
        if (npc.isSitting()) {
            npc.setSitting(false);
        }
        npc.clearForageTarget();
        if (data.getDetail() == NpcDetail.DINING) {
            data.setDetail(NpcDetail.FORAGING);
        }
    }

    /** 沿落点外扩一圈找建筑：findDining 给的落点允许压在包围盒外沿一两格。 */
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

    /** 菜单上的成品优先；兜底吃仓库里任何带食物属性的物品。 */
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
