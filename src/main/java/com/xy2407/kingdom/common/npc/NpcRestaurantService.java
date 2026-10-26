package com.xy2407.kingdom.common.npc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.building.ItemSpecs;
import com.xy2407.kingdom.common.building.MenuSpec;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.restaurant.RestaurantOrder;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;
import com.xy2407.kingdom.server.restaurant.RestaurantOrderStore;
import com.xy2407.kingdom.server.restaurant.RestaurantSeatStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 餐厅后厨：厨师按蓝图菜单取料加工，成品放回仓库供市民取用
 */
public final class NpcRestaurantService {

    private static final int ACTION_INTERVAL_TICKS = 20;

    private static final int DISH_TICKS = 200;

    public record Roles(UUID chefId, int waiters) {

        public boolean open() {
            return chefId != null && waiters > 0;
        }
    }

    private NpcRestaurantService() {
    }

    public static Roles rolesOf(UUID buildingId) {
        if (buildingId == null) {
            return new Roles(null, 0);
        }
        String id = buildingId.toString();
        List<UUID> ids = new ArrayList<>();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !id.equals(npc.getWorkplaceId())) {
                continue;
            }
            ids.add(npc.getId());
        }
        if (ids.isEmpty()) {
            return new Roles(null, 0);
        }
        ids.sort(Comparator.naturalOrder());
        return new Roles(ids.get(0), ids.size() - 1);
    }

    public static void tick(NpcEntity npc, NpcData data) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return;
        }
        if (npc.tickCount % ACTION_INTERVAL_TICKS != 0) {
            return;
        }
        BuildingRecord building = buildingOf(data);
        if (building == null || !isInside(building, data)) {
            return;
        }
        if (!rolesOf(building.id()).open()) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        if (meta == null || meta.menu().isEmpty()) {
            return;
        }
        IItemHandler store = BuildingStorageStore.INSTANCE.handlerOf(server, building.id(), meta.storageSlots());
        if (store == null) {
            return;
        }
        RestaurantOrder order = RestaurantOrderStore.INSTANCE.firstByStatus(building.id(),
                RestaurantOrder.Status.COOKING);
        if (order == null) {
            order = RestaurantOrderStore.INSTANCE.firstByStatus(building.id(),
                    RestaurantOrder.Status.PENDING);
            if (order == null) {
                return;
            }
            order = order.withStatus(RestaurantOrder.Status.COOKING);
            RestaurantOrderStore.INSTANCE.put(order);
        }
        MenuSpec dish = dishOf(meta.menu(), order.dishId());
        if (dish == null) {
            return;
        }
        if (!ItemSpecs.hasAll(store, dish.inputs())) {
            return;
        }
        if (!ItemSpecs.canInsertOne(store, dish.output())) {
            return;
        }
        int progress = data.getWorkTicks() + ACTION_INTERVAL_TICKS;
        if (progress < DISH_TICKS) {
            data.setWorkTicks(progress);
            return;
        }
        if (ItemSpecs.consume(store, dish.inputs())) {
            ItemSpecs.insertOne(store, dish.output());
            data.setWorkTicks(0);
            RestaurantOrderStore.INSTANCE.put(order.withStatus(RestaurantOrder.Status.COOKED));
        }
    }

    private static MenuSpec dishOf(List<MenuSpec> menu, String dishId) {
        if (dishId == null || dishId.isBlank()) {
            return null;
        }
        for (MenuSpec dish : menu) {
            if (dishId.equalsIgnoreCase(dish.output().item())) {
                return dish;
            }
        }
        return null;
    }

    public static NpcDetail detailFor(NpcData npc) {
        BuildingRecord building = buildingOf(npc);
        if (building == null) {
            return NpcDetail.COOKING;
        }
        Roles roles = rolesOf(building.id());
        return roles.chefId() != null && roles.chefId().equals(npc.getId())
                ? NpcDetail.COOKING : NpcDetail.SERVING;
    }

    public static void tickWaiter(NpcEntity npc, NpcData data) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return;
        }
        if (npc.tickCount % ACTION_INTERVAL_TICKS != 0) {
            return;
        }
        BuildingRecord building = buildingOf(data);
        if (building == null) {
            return;
        }
        if (!rolesOf(building.id()).open()) {
            npc.getNavigation().stop();
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        if (meta == null || meta.menu().isEmpty()) {
            return;
        }
        RestaurantOrder order = RestaurantOrderStore.INSTANCE.firstByStatus(building.id(),
                RestaurantOrder.Status.COOKED);
        if (order == null) {
            npc.getNavigation().stop();
            return;
        }
        BlockPos target = RestaurantSeatStore.INSTANCE.servePointOf(building.id(), order.seat());
        if (target == null) {
            target = order.seat();
        }
        if (!near(npc, target)) {
            npc.setNavTarget(target);
            return;
        }
        npc.getNavigation().stop();
        MenuSpec dish = dishOf(meta.menu(), order.dishId());
        if (dish == null) {
            return;
        }
        IItemHandler store = BuildingStorageStore.INSTANCE.handlerOf(server, building.id(), meta.storageSlots());
        if (store == null) {
            return;
        }
        if (takeOne(store, dish.output().item()).isEmpty()) {
            return;
        }
        RestaurantOrderStore.INSTANCE.put(order.withStatus(RestaurantOrder.Status.SERVING));
    }

    private static ItemStack takeOne(IItemHandler store, String spec) {
        for (int slot = 0; slot < store.getSlots(); slot++) {
            ItemStack stack = store.getStackInSlot(slot);
            if (ItemSpecs.matches(stack, spec)) {
                return store.extractItem(slot, 1, false);
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean near(NpcEntity npc, BlockPos target) {
        double dx = npc.getX() - (target.getX() + 0.5D);
        double dz = npc.getZ() - (target.getZ() + 0.5D);
        return dx * dx + dz * dz <= 4.0D;
    }

    public static void assignWorkers(MinecraftServer server) {
        Map<UUID, BuildingRecord> buildings = new HashMap<>();
        Map<UUID, List<NpcData>> crews = new HashMap<>();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead()) {
                continue;
            }
            BuildingRecord building = buildingOf(npc);
            boolean restaurant = building != null && isRestaurant(building);
            NpcBrain brain = npc.getBrain();
            if (!restaurant) {
                if (brain.isRestaurantControlled()) {
                    brain.setRestaurantControlled(false);
                }
                continue;
            }
            if (brain.isRtsControlled() || brain.isConstructionControlled()) {
                continue;
            }
            brain.setRestaurantControlled(true);
            buildings.put(building.id(), building);
            crews.computeIfAbsent(building.id(), id -> new ArrayList<>()).add(npc);
        }
        for (Map.Entry<UUID, List<NpcData>> entry : crews.entrySet()) {
            handOver(buildings.get(entry.getKey()), entry.getValue());
        }
    }

    private static void handOver(BuildingRecord building, List<NpcData> crew) {
        if (building == null || crew.size() < 2) {
            return;
        }
        List<NpcData> onDuty = new ArrayList<>();
        for (NpcData npc : crew) {
            if (isInside(building, npc)) {
                onDuty.add(npc);
            }
        }
        if (onDuty.isEmpty()) {
            return;
        }
        int pending = 0;
        for (NpcData npc : crew) {
            if (onDuty.contains(npc)) {
                continue;
            }
            pending = Math.max(pending, npc.getWorkTicks());
            if (npc.getWorkTicks() != 0) {
                npc.setWorkTicks(0);
            }
        }
        if (pending <= 0) {
            return;
        }
        NpcData receiver = onDuty.get(0);
        if (receiver.getWorkTicks() < pending) {
            receiver.setWorkTicks(pending);
        }
    }

    private static BuildingRecord buildingOf(NpcData data) {
        String id = data.getWorkplaceId();
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return BuildingRegistry.INSTANCE.byId(UUID.fromString(id));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static boolean isRestaurant(BuildingRecord building) {
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        return meta != null && meta.isWork(BuildingWork.RESTAURANT);
    }

    private static boolean isInside(BuildingRecord building, NpcData npc) {
        String dimension = npc.getDimension() == null ? "" : npc.getDimension();
        return building.contains(dimension, npc.getX(), npc.getY(), npc.getZ());
    }

    private static MenuSpec firstCraftable(IItemHandler store, List<MenuSpec> menu) {
        for (MenuSpec dish : menu) {
            if (ItemSpecs.hasAll(store, dish.inputs()) && ItemSpecs.canInsertOne(store, dish.output())) {
                return dish;
            }
        }
        return null;
    }
}
