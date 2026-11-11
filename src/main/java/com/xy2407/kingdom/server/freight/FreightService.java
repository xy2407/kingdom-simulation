package com.xy2407.kingdom.server.freight;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.building.ItemCount;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.freight.FreightOrder;
import com.xy2407.kingdom.common.freight.FreightSource;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;
import com.xy2407.kingdom.server.rts.RtsSelectionService;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 货运调度：接单、低频复查仓库库存、派仓库员工执行，执行过程交给 NpcFreightService。 */
public final class FreightService {

    public static final FreightService INSTANCE = new FreightService();

    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final int RETRY_DELAY_TICKS = 200;
    private static final double WAREHOUSE_RADIUS = 96.0D;
    private static final double PRODUCER_RADIUS = 128.0D;
    private static final int MAX_ATTEMPTS = 60;
    private static final int ORDER_TIMEOUT_TICKS = 12000;
    private static final int FLAG_REFRESH_TICKS = 100;

    private static final int SOURCE_POLL_TICKS = 200;

    private final Map<UUID, FreightOrder> orders = new ConcurrentHashMap<>();
    private final Map<UUID, FreightSource> sources = new ConcurrentHashMap<>();
    private int tickCounter;

    private FreightService() {
    }

    public void reset() {
        orders.clear();
        sources.clear();
        tickCounter = 0;
    }

    public void watch(FreightSource source) {
        if (source != null && source.sourceId() != null) {
            sources.put(source.sourceId(), source);
        }
    }

    public void unwatch(UUID sourceId) {
        if (sourceId != null) {
            sources.remove(sourceId);
        }
    }

    private void pollSources(MinecraftServer server) {
        sources.values().removeIf(source -> !source.valid());
        for (FreightSource source : sources.values()) {
            List<ItemCount> missing;
            try {
                missing = source.missing();
            } catch (RuntimeException failure) {
                sources.remove(source.sourceId());
                continue;
            }
            if (missing == null || missing.isEmpty()) {
                continue;
            }
            if (source.containerPos() != null) {
                submitToContainer(server, source.dimension(), source.containerPos(), missing);
            } else if (source.buildingId() != null) {
                submit(server, source.buildingId(), source.dimension(), missing);
            }
        }
    }

    public FreightOrder submit(MinecraftServer server, UUID requesterId, String dimension, List<ItemCount> items) {
        if (server == null || requesterId == null || items == null || items.isEmpty()) {
            return null;
        }
        return register(FreightOrder.toBuilding(requesterId, dimension, items,
                server.overworld().getGameTime()));
    }

    public FreightOrder submitToContainer(MinecraftServer server, String dimension, BlockPos pos,
                                          List<ItemCount> items) {
        if (server == null || pos == null || items == null || items.isEmpty()) {
            return null;
        }
        return register(FreightOrder.toContainer(dimension, pos, items,
                server.overworld().getGameTime()));
    }

    private FreightOrder register(FreightOrder order) {
        order.setNextAttemptAt(order.createdAt());
        for (FreightOrder existing : orders.values()) {
            if (!existing.finished() && existing.sameRequirement(order)) {
                return existing;
            }
        }
        orders.put(order.id(), order);
        return order;
    }

    public void complete(FreightOrder order) {
        if (order == null) {
            return;
        }
        order.setState(FreightOrder.State.DONE);
        order.setCarrierId(null);
    }

    public void fail(FreightOrder order, String reason) {
        if (order == null) {
            return;
        }
        order.fail(reason);
    }

    public FreightOrder orderOfCarrier(UUID carrierId) {
        if (carrierId == null) {
            return null;
        }
        for (FreightOrder order : orders.values()) {
            if (order.inProgress() && carrierId.equals(order.carrierId())) {
                return order;
            }
        }
        return null;
    }

    public List<FreightOrder> activeOrders() {
        List<FreightOrder> list = new ArrayList<>();
        for (FreightOrder order : orders.values()) {
            if (!order.finished()) {
                list.add(order);
            }
        }
        return list;
    }

    public List<ItemCount> pendingOf(UUID requesterId) {
        if (requesterId == null) {
            return List.of();
        }
        Map<String, Integer> byItem = new LinkedHashMap<>();
        for (FreightOrder order : orders.values()) {
            if (order.finished() || !requesterId.equals(order.requesterId())) {
                continue;
            }
            for (ItemCount item : order.items()) {
                byItem.merge(item.item(), item.count(), Integer::sum);
            }
        }
        List<ItemCount> result = new ArrayList<>(byItem.size());
        for (Map.Entry<String, Integer> entry : byItem.entrySet()) {
            result.add(new ItemCount(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    public void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        long now = server.overworld().getGameTime();
        tickCounter++;
        if (tickCounter % FLAG_REFRESH_TICKS == 0) {
            refreshWorkerFlags();
        }
        if (tickCounter % SOURCE_POLL_TICKS == 0) {
            pollSources(server);
        }
        if (tickCounter % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        reapStalled(server, now);
        for (FreightOrder order : orders.values()) {
            if (order.state() != FreightOrder.State.PENDING || now < order.nextAttemptAt()) {
                continue;
            }
            dispatch(server, order, now);
        }
        orders.values().removeIf(FreightOrder::finished);
    }

    private void reapStalled(MinecraftServer server, long now) {
        for (FreightOrder order : orders.values()) {
            if (!order.inProgress()) {
                continue;
            }
            if (order.carrierId() == null) {
                order.fail("没有配送员");
                continue;
            }
            if (findEntity(server, order.carrierId()) == null) {
                order.fail("配送员已不存在");
                continue;
            }
            if (order.stateAt() == 0L) {
                order.setStateAt(now);
                continue;
            }
            if (now - order.stateAt() > ORDER_TIMEOUT_TICKS) {
                order.fail("配送超时");
            }
        }
    }

    private void refreshWorkerFlags() {
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            boolean warehouseWorker = !npc.isDead() && isWarehouse(warehouseOf(npc));
            if (npc.getBrain().isFreightControlled() != warehouseWorker) {
                npc.getBrain().setFreightControlled(warehouseWorker);
            }
        }
    }

    private void dispatch(MinecraftServer server, FreightOrder order, long now) {
        if (order.incrementAttempts() > MAX_ATTEMPTS) {
            order.fail("多次尝试后仍未凑齐材料");
            return;
        }
        BlockPos reference = referencePos(order);
        if (reference == null) {
            order.fail("需求方已不存在");
            return;
        }
        Map<UUID, Map<String, Integer>> reserved = reservations();
        BuildingRecord warehouse = findWarehouse(server, order.dimension(), reference, order.items(),
                reserved);
        UUID pickupId = null;
        if (warehouse == null) {
            BuildingRecord producer = findProducer(server, order.dimension(), reference,
                    order.items(), reserved);
            BuildingRecord carrierSource = producer == null ? null
                    : findCarrierWarehouse(server, order.dimension(), reference);
            if (producer == null || carrierSource == null) {
                order.setNextAttemptAt(now + RETRY_DELAY_TICKS);
                return;
            }
            pickupId = producer.id();
            warehouse = carrierSource;
        }
        NpcData carrier = pickWorker(server, warehouse);
        if (carrier == null) {
            order.setNextAttemptAt(now + RETRY_DELAY_TICKS);
            return;
        }
        order.setPickupId(pickupId);
        order.setWarehouseId(warehouse.id());
        order.setCarrierId(carrier.getId());
        order.setState(FreightOrder.State.ASSIGNED);
        carrier.getBrain().setFreightControlled(true);
    }

    private BuildingRecord findProducer(MinecraftServer server, String dimension, BlockPos reference,
                                        List<ItemCount> items,
                                        Map<UUID, Map<String, Integer>> reserved) {
        BuildingRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BuildingRecord record : BuildingRegistry.INSTANCE.all()) {
            if (!record.dimension().equals(dimension) || isWarehouse(record)) {
                continue;
            }
            double distance = record.min().distSqr(reference);
            if (distance > PRODUCER_RADIUS * PRODUCER_RADIUS || distance >= bestDistance) {
                continue;
            }
            BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(record);
            if (meta == null || meta.storageSlots() <= 0) {
                continue;
            }
            IItemHandler store = BuildingStorageStore.INSTANCE.handlerOf(server, record.id(),
                    meta.storageSlots());
            if (store == null || !hasAvailable(store, items, reserved.get(record.id()))) {
                continue;
            }
            bestDistance = distance;
            best = record;
        }
        return best;
    }

    private BuildingRecord findCarrierWarehouse(MinecraftServer server, String dimension,
                                                BlockPos reference) {
        BuildingRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BuildingRecord record : BuildingRegistry.INSTANCE.all()) {
            if (!record.dimension().equals(dimension) || !isWarehouse(record)) {
                continue;
            }
            double distance = record.min().distSqr(reference);
            if (distance > WAREHOUSE_RADIUS * WAREHOUSE_RADIUS || distance >= bestDistance) {
                continue;
            }
            bestDistance = distance;
            best = record;
        }
        return best;
    }

    private BlockPos referencePos(FreightOrder order) {
        if (order.target() == FreightOrder.Target.CONTAINER) {
            return order.destination();
        }
        BuildingRecord requester = BuildingRegistry.INSTANCE.byId(order.requesterId());
        return requester == null ? null : requester.min();
    }

    private BuildingRecord findWarehouse(MinecraftServer server, String dimension, BlockPos reference,
                                         List<ItemCount> items,
                                         Map<UUID, Map<String, Integer>> reserved) {
        BuildingRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BuildingRecord record : BuildingRegistry.INSTANCE.all()) {
            if (!record.dimension().equals(dimension) || !isWarehouse(record)) {
                continue;
            }
            double distance = record.min().distSqr(reference);
            if (distance > WAREHOUSE_RADIUS * WAREHOUSE_RADIUS || distance >= bestDistance) {
                continue;
            }
            BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(record);
            if (meta == null) {
                continue;
            }
            IItemHandler store = BuildingStorageStore.INSTANCE.handlerOf(server, record.id(), meta.storageSlots());
            if (store == null || !hasAvailable(store, items, reserved.get(record.id()))) {
                continue;
            }
            bestDistance = distance;
            best = record;
        }
        return best;
    }

    private NpcData pickWorker(MinecraftServer server, BuildingRecord warehouse) {
        String warehouseId = warehouse.id().toString();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !warehouseId.equals(npc.getWorkplaceId())) {
                continue;
            }
            if (orderOfCarrier(npc.getId()) != null) {
                continue;
            }
            if (findEntity(server, npc.getId()) == null) {
                continue;
            }
            if (npc.getBrain().isRtsControlled() || npc.getBrain().isConstructionControlled()) {
                continue;
            }
            return npc;
        }
        return null;
    }

    private NpcEntity findEntity(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (RtsSelectionService.findEntity(level, id) instanceof NpcEntity npc) {
                return npc;
            }
        }
        return null;
    }

    private BuildingRecord warehouseOf(NpcData npc) {
        String workplace = npc.getWorkplaceId();
        if (workplace == null || workplace.isBlank()) {
            return null;
        }
        try {
            return BuildingRegistry.INSTANCE.byId(UUID.fromString(workplace));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static boolean isWarehouse(BuildingRecord record) {
        if (record == null) {
            return false;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(record);
        return meta != null && meta.isWork(BuildingWork.WAREHOUSE);
    }

    public static boolean hasAll(IItemHandler store, List<ItemCount> items) {
        for (ItemCount need : items) {
            Item item = itemOf(need.item());
            if (item == null || countOf(store, item) < need.count()) {
                return false;
            }
        }
        return true;
    }

    private Map<UUID, Map<String, Integer>> reservations() {
        Map<UUID, Map<String, Integer>> result = new HashMap<>();
        for (FreightOrder order : orders.values()) {
            if (order.finished() || order.warehouseId() == null) {
                continue;
            }
            Map<String, Integer> byItem = result.computeIfAbsent(order.warehouseId(),
                    id -> new HashMap<>());
            for (ItemCount item : order.items()) {
                byItem.merge(item.item(), item.count(), Integer::sum);
            }
        }
        return result;
    }

    private static boolean hasAvailable(IItemHandler store, List<ItemCount> items,
                                        Map<String, Integer> reserved) {
        for (ItemCount need : items) {
            Item item = itemOf(need.item());
            int hold = reserved == null ? 0 : reserved.getOrDefault(need.item(), 0);
            if (item == null || countOf(store, item) - hold < need.count()) {
                return false;
            }
        }
        return true;
    }

    private static int countOf(IItemHandler store, Item item) {
        int total = 0;
        for (int slot = 0; slot < store.getSlots(); slot++) {
            ItemStack stack = store.getStackInSlot(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static Item itemOf(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            return null;
        }
        return BuiltInRegistries.ITEM.get(key);
    }
}
