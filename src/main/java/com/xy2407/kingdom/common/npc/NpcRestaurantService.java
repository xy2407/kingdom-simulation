package com.xy2407.kingdom.common.npc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 餐厅后厨：菜品直接来自蓝图 json（{@code BuildingMeta#menu}），
 * 厨师从建筑仓库取食材、做完把成品放回仓库，成品再由饿了的市民（顾客）自行取用。
 * 工作进度记在 NPC 身上（{@code NpcData#workTicks}）—— 与工业同一套：
 * "谁做到一半、做了多久"都在库里，重启天然接着做，不需要额外的订单表持久化。
 * 与工业的唯一差别是菜品用 {@link MenuSpec}（单成品 + 售价），且食材支持 {@code #标签}。
 */
public final class NpcRestaurantService {

    /** 每 20 tick 结算一次，避免每 tick 全量扫仓库。 */
    private static final int ACTION_INTERVAL_TICKS = 20;

    /** 一道菜的固定制作时长（tick）；MenuSpec 没有 ticks 字段，取 10 秒。 */
    private static final int DISH_TICKS = 200;

    private NpcRestaurantService() {
    }

    /** NPC 实体侧每 tick 调用（detail == COOKING 时）。 */
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
        // 按订单做菜：先接手上那张正在做的（COOKING），没有就领一张新的（PENDING → COOKING）。
        // 顺序由 RestaurantOrderStore 的插入序保证 —— 先来先做。
        RestaurantOrder order = RestaurantOrderStore.INSTANCE.firstByStatus(building.id(),
                RestaurantOrder.Status.COOKING);
        if (order == null) {
            order = RestaurantOrderStore.INSTANCE.firstByStatus(building.id(),
                    RestaurantOrder.Status.PENDING);
            if (order == null) {
                return;   // 没有订单：待着
            }
            order = order.withStatus(RestaurantOrder.Status.COOKING);
            RestaurantOrderStore.INSTANCE.put(order);
        }
        MenuSpec dish = dishOf(meta.menu(), order.dishId());
        if (dish == null) {
            return;   // 菜单里没有这道菜（蓝图换过）→ 留给顾客超时撤单
        }
        // 材料不齐：进度保留不清零 —— 补货后接着往本轮上累计。
        if (!ItemSpecs.hasAll(store, dish.inputs())) {
            return;
        }
        if (!ItemSpecs.canInsertOne(store, dish.output())) {
            // 仓库放不下成品就先不做，免得把食材白白消耗掉。
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
            // 成品已进仓库：订单转 COOKED，等服务员取菜端到座位上。
            RestaurantOrderStore.INSTANCE.put(order.withStatus(RestaurantOrder.Status.COOKED));
        }
    }

    /** 从菜单里按成品物品 id 找那道菜。 */
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

    /**
     * 后厨分工：同一家店里的员工按 UUID 字符串序排队，第一个掌勺、其余端盘子。
     * 之所以先这么分，是因为"指派岗位"的面板还没接；等能指定了改成按字段读即可。
     * 用 UUID 排序而不是列表顺序，是为了跨重启稳定 —— 否则同一个人可能今天做菜明天端菜。
     */
    public static NpcDetail detailFor(NpcData npc) {
        BuildingRecord building = buildingOf(npc);
        if (building == null) {
            return NpcDetail.COOKING;
        }
        String chefId = chefIdOf(building.id());
        return chefId != null && chefId.equals(npc.getId().toString())
                ? NpcDetail.COOKING : NpcDetail.SERVING;
    }

    /** 店里 UUID 最小（字符串序）的那名员工当厨师。 */
    private static String chefIdOf(UUID buildingId) {
        String best = null;
        String id = buildingId.toString();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !id.equals(npc.getWorkplaceId())) {
                continue;
            }
            String candidate = npc.getId().toString();
            if (best == null || candidate.compareTo(best) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 服务员：找一张做好了（COOKED）的单 → 走到那个座位 → 从仓库取一份这道菜（真消耗）
     * → 订单转 SERVING，顾客下一 tick 就会吃掉。手上没活就回岗位待着。
     */
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
            return;   // 没有要送的菜：回岗位待着
        }
        BlockPos seat = order.seat();
        if (!near(npc, seat)) {
            npc.setNavTarget(seat);
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
            return;   // 仓库里这道菜没了（可能已被别人端走）：等下一轮
        }
        RestaurantOrderStore.INSTANCE.put(order.withStatus(RestaurantOrder.Status.SERVING));
    }

    /** 从仓库里取一份指定物品（真取出）。 */
    private static ItemStack takeOne(IItemHandler store, String spec) {
        for (int slot = 0; slot < store.getSlots(); slot++) {
            ItemStack stack = store.getStackInSlot(slot);
            if (ItemSpecs.matches(stack, spec)) {
                return store.extractItem(slot, 1, false);
            }
        }
        return ItemStack.EMPTY;
    }

    /** 人是否已经站到目标点附近（水平距离）。 */
    private static boolean near(NpcEntity npc, BlockPos target) {
        double dx = npc.getX() - (target.getX() + 0.5D);
        double dz = npc.getZ() - (target.getZ() + 0.5D);
        return dx * dx + dz * dz <= 4.0D;
    }

    /** 每 100 tick 由服务端调度调用：刷新"谁在后厨上班"，并处理换班移交。 */
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

    /**
     * 换班移交：把"已离岗（不在餐厅范围内）的同事"身上累积的 tick 交给当前在岗的人，
     * 并把离岗者的进度清零（避免下一个人回来时重复计算同一段工时）。
     */
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
        return meta != null && meta.type() == BuildingType.COMMERCIAL && meta.isRestaurant();
    }

    /** 只有人真的在建筑范围内才算在岗（下班离开后进度停住，等下一次移交）。 */
    private static boolean isInside(BuildingRecord building, NpcData npc) {
        String dimension = npc.getDimension() == null ? "" : npc.getDimension();
        return building.contains(dimension, npc.getX(), npc.getY(), npc.getZ());
    }

    /** 取菜单上第一道食材齐备、且成品塞得下的菜；都做不了返回 null。 */
    private static MenuSpec firstCraftable(IItemHandler store, List<MenuSpec> menu) {
        for (MenuSpec dish : menu) {
            if (ItemSpecs.hasAll(store, dish.inputs()) && ItemSpecs.canInsertOne(store, dish.output())) {
                return dish;
            }
        }
        return null;
    }
}
