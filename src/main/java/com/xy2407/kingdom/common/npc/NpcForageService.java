package com.xy2407.kingdom.common.npc;

import java.util.List;

import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.server.building.BuildingRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * 觅食执行服务，每个觅食周期按行装、餐厅、容器、掉落物的顺序补食，四处都没有就停下挨饿
 */
public final class NpcForageService {
    private static final int CONTAINER_RADIUS = 5;
    private static final int COMMERCIAL_RADIUS = 64;
    private static final double ARRIVE_DISTANCE_SQR = 6.25D;

    private NpcForageService() {
    }

    public static void tick(NpcEntity npc, NpcData data) {
        double needed = NpcData.HUNGER_MAX - data.getHunger();
        if (needed <= 0.0D) {
            npc.clearForageTarget();
            return;
        }
        BlockPos heading = npc.getForageTarget();
        if (heading != null) {
            if (arrived(npc, heading)) {
                npc.clearForageTarget();
                data.setHunger(NpcData.HUNGER_MAX);
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
        npc.haltForage();
    }

    private static boolean arrived(NpcEntity npc, BlockPos target) {
        double dx = npc.getX() - (target.getX() + 0.5D);
        double dz = npc.getZ() - (target.getZ() + 0.5D);
        return dx * dx + dz * dz <= ARRIVE_DISTANCE_SQR;
    }

    /** 城市表里最近的餐厅；城市还没有餐厅时返回 null，改走就地觅食。 */
    private static BlockPos diningOf(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return null;
        }
        return BuildingRegistry.INSTANCE.findDining(level.dimension().location().toString(),
                npc.getX(), npc.getY(), npc.getZ(), COMMERCIAL_RADIUS);
    }

    /** 先吃自己行装里的食物：背包里就有吃的，不必出门。 */
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
                // 掉落物只改本地实例不会标记同步，必须回写一份副本才会发包给客户端
                item.setItem(stack.copy());
            }
        }
        return applyFood(data, needed, remaining);
    }

    /** 吃到的量记进饱食度；一份都没吃到时返回 false，让调用方继续往下找。 */
    private static boolean applyFood(NpcData data, double needed, double remaining) {
        if (remaining >= needed) {
            return false;
        }
        data.setHunger(Math.min(NpcData.HUNGER_MAX, data.getHunger() + (needed - remaining)));
        return true;
    }
}
