package com.xy2407.kingdom.server.freight;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.building.ItemCount;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.freight.FreightOrder;
import com.xy2407.kingdom.common.freight.FreightSource;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.persistence.FreightOrderSchema;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;
import com.xy2407.kingdom.server.rts.RtsSelectionService;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.sql.ResultSet;
import java.sql.Statement;
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
    private static final int LOAD_RETRY_DELAY_TICKS = 200;
    private static final double WAREHOUSE_RADIUS = 96.0D;
    private static final double PRODUCER_RADIUS = 128.0D;
    private static final int FLAG_REFRESH_TICKS = 100;

    private static final int SOURCE_POLL_TICKS = 200;

    private final Map<UUID, FreightOrder> orders = new ConcurrentHashMap<>();
    private final Map<UUID, FreightSource> sources = new ConcurrentHashMap<>();
    private int tickCounter;
    private int loadRetryCooldown;
    private boolean loaded;

    private FreightService() {
    }

    public void reset() {
        orders.clear();
        sources.clear();
        tickCounter = 0;
        loaded = false;
        loadRetryCooldown = 0;
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

    /**
     * 清仓运输：把货源建筑仓库里的货物（items 快照）运往指定仓库。
     * 目标仓库可为空（派发时自动重选：管辖仓库优先，否则最近仓库）。
     */
    public FreightOrder submitWarehousePickup(MinecraftServer server, UUID sourceId, String dimension,
                                              List<ItemCount> items, UUID warehouseId) {
        if (server == null || sourceId == null || items == null || items.isEmpty()) {
            return null;
        }
        FreightOrder order = FreightOrder.toWarehousePickup(sourceId, dimension, items,
                server.overworld().getGameTime());
        if (warehouseId != null) {
            order.setWarehouseId(warehouseId);
        }
        return register(order);
    }

    private FreightOrder register(FreightOrder order) {
        order.setNextAttemptAt(order.createdAt());
        for (FreightOrder existing : orders.values()) {
            if (!existing.finished() && existing.sameRequirement(order)) {
                return existing;
            }
        }
        orders.put(order.id(), order);
        save(order);
        return order;
    }

    public void complete(FreightOrder order) {
        if (order == null) {
            return;
        }
        order.setState(FreightOrder.State.DONE);
        order.setCarrierId(null);
        removeRow(order);
    }

    public void fail(FreightOrder order, String reason) {
        if (order == null) {
            return;
        }
        order.fail(reason);
        removeRow(order);
    }

    /** 订单终结时处理运输员身上的货：从哪儿取的退回哪儿；那边没了就掉在原地。 */
    public void failCarrying(MinecraftServer server, FreightOrder order, String reason) {
        if (order != null && order.carrierId() != null) {
            NpcEntity carrier = findEntity(server, order.carrierId());
            if (carrier != null && carrier.level() instanceof ServerLevel level) {
                UUID backTo = order.pickupId() != null ? order.pickupId() : order.warehouseId();
                BuildingRecord target = backTo == null ? null : BuildingRegistry.INSTANCE.byId(backTo);
                if (target != null) {
                    com.xy2407.kingdom.common.npc.ProduceStoring.depositBag(level, carrier, target);
                } else {
                    com.xy2407.kingdom.common.npc.ProduceStoring.dropBag(level, carrier);
                }
            }
        }
        fail(order, reason);
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
            if (order.finished() || order.target() != FreightOrder.Target.BUILDING
                    || !requesterId.equals(order.requesterId())) {
                // 只算「送到该建筑」的订单；清仓单是往外运，不能当成在途入库量
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
        if (!loaded) {
            if (loadRetryCooldown > 0) {
                loadRetryCooldown--;
                return;
            }
            load(server);
            if (!loaded) {
                return;
            }
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
        reapStalled(now);
        // 先来后到：按提交时间排队派发
        List<FreightOrder> pending = new ArrayList<>();
        for (FreightOrder order : orders.values()) {
            if (order.state() == FreightOrder.State.PENDING && now >= order.nextAttemptAt()) {
                pending.add(order);
            }
        }
        pending.sort((left, right) -> Long.compare(left.createdAt(), right.createdAt()));
        for (FreightOrder order : pending) {
            dispatch(server, order, now);
        }
        orders.values().removeIf(FreightOrder::finished);
    }

    /** 运输员没了（市民数据被删/死亡）才把订单退回队列；不设时间期限，建筑还在就一直等。 */
    private void reapStalled(long now) {
        for (FreightOrder order : orders.values()) {
            if (!order.inProgress()) {
                continue;
            }
            if (order.carrierId() == null) {
                requeue(order, now);
                continue;
            }
            NpcData carrier = NpcManager.INSTANCE.getNpc(order.carrierId());
            if (carrier == null || carrier.isDead()) {
                order.setCarrierId(null);
                requeue(order, now);
            }
        }
    }

    private void requeue(FreightOrder order, long now) {
        order.setState(FreightOrder.State.PENDING);
        order.setNextAttemptAt(now + RETRY_DELAY_TICKS);
        save(order);
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
        if (order.target() == FreightOrder.Target.WAREHOUSE) {
            dispatchWarehouse(server, order, now);
            return;
        }
        BlockPos reference = referencePos(order);
        if (reference == null) {
            fail(order, "需求方已不存在");
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
        save(order);
        carrier.getBrain().setFreightControlled(true);
    }

    /** 清仓订单派发：货源仍在、目标仓库有容量、仓库有闲人，即可指派运输员。 */
    private void dispatchWarehouse(MinecraftServer server, FreightOrder order, long now) {
        BuildingRecord source = BuildingRegistry.INSTANCE.byId(order.requesterId());
        if (source == null || !source.dimension().equals(order.dimension())) {
            fail(order, "货源地已不存在");
            return;
        }
        BuildingRecord warehouse = order.warehouseId() == null
                ? null : BuildingRegistry.INSTANCE.byId(order.warehouseId());
        if (warehouse == null || !isWarehouse(warehouse) || warehouse.id().equals(source.id())
                || !warehouse.dimension().equals(order.dimension())) {
            warehouse = pickDestination(server, source);
            if (warehouse == null) {
                order.setNextAttemptAt(now + RETRY_DELAY_TICKS);
                return;
            }
            order.setWarehouseId(warehouse.id());
        }
        if (!storeHasRoom(server, warehouse)) {
            // 目标仓库满了：换一个其它还有空间的仓库，换不到就等
            BuildingRecord alternative = pickWarehouseWithRoom(server, source, warehouse.id());
            if (alternative == null) {
                order.setNextAttemptAt(now + RETRY_DELAY_TICKS);
                return;
            }
            warehouse = alternative;
            order.setWarehouseId(warehouse.id());
        }
        NpcData carrier = pickWorker(server, warehouse);
        if (carrier == null) {
            order.setNextAttemptAt(now + RETRY_DELAY_TICKS);
            return;
        }
        order.setPickupId(source.id());
        order.setCarrierId(carrier.getId());
        order.setState(FreightOrder.State.ASSIGNED);
        save(order);
        carrier.getBrain().setFreightControlled(true);
    }

    /** 清仓目标：数据库里绑定的仓库优先，未绑定时按坐标就近选仓库。 */
    private BuildingRecord pickDestination(MinecraftServer server, BuildingRecord source) {
        BuildingRecord bound = boundWarehouseOf(source);
        if (bound != null && storeHasRoom(server, bound)) {
            return bound;
        }
        BuildingRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BuildingRecord record : BuildingRegistry.INSTANCE.all()) {
            if (!record.dimension().equals(source.dimension()) || record.id().equals(source.id())) {
                continue;
            }
            if (!isWarehouse(record)) {
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

    /** 换仓：非当前仓库里，数据库绑定仓库优先，其次最近的、确实还有空间的仓库。 */
    private BuildingRecord pickWarehouseWithRoom(MinecraftServer server, BuildingRecord source,
                                                 UUID excludeId) {
        BuildingRecord bound = boundWarehouseOf(source);
        if (bound != null && !bound.id().equals(excludeId) && storeHasRoom(server, bound)) {
            return bound;
        }
        BuildingRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BuildingRecord record : BuildingRegistry.INSTANCE.all()) {
            if (!record.dimension().equals(source.dimension()) || record.id().equals(source.id())
                    || record.id().equals(excludeId) || !isWarehouse(record)) {
                continue;
            }
            double distance = record.min().distSqr(source.min());
            if (distance >= bestDistance || !storeHasRoom(server, record)) {
                continue;
            }
            bestDistance = distance;
            best = record;
        }
        return best;
    }

    /** 数据库（warehouse_link）里绑定的仓库；未绑定、已失效或已不是仓库时返回 null。 */
    private static BuildingRecord boundWarehouseOf(BuildingRecord source) {
        UUID warehouseId = com.xy2407.kingdom.server.building.WarehouseLinkStore.INSTANCE
                .warehouseOf(source.id());
        if (warehouseId == null) {
            return null;
        }
        BuildingRecord warehouse = BuildingRegistry.INSTANCE.byId(warehouseId);
        if (warehouse == null || !warehouse.dimension().equals(source.dimension())) {
            return null;
        }
        return warehouse;
    }

    private static boolean storeHasRoom(MinecraftServer server, BuildingRecord building) {        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
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
            if (order.finished() || order.target() == FreightOrder.Target.WAREHOUSE) {
                // 清仓订单是「有多少搬多少」，不能把货源整仓预留掉，否则会饿死正常送货订单
                continue;
            }
            UUID holder = order.pickupId() != null ? order.pickupId() : order.warehouseId();
            if (holder == null) {
                continue;
            }
            Map<String, Integer> byItem = result.computeIfAbsent(holder, id -> new HashMap<>());
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

    private static void save(FreightOrder order) {
        if (order == null || order.id() == null) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(FreightOrderSchema.COL_ID, order.id().toString());
        values.put(FreightOrderSchema.COL_TARGET, order.target().name());
        values.put(FreightOrderSchema.COL_REQUESTER_ID,
                order.requesterId() == null ? "" : order.requesterId().toString());
        values.put(FreightOrderSchema.COL_DESTINATION,
                order.destination() == null ? ""
                        : order.destination().getX() + "," + order.destination().getY()
                                + "," + order.destination().getZ());
        values.put(FreightOrderSchema.COL_DIMENSION, order.dimension());
        values.put(FreightOrderSchema.COL_ITEMS, encodeItems(order.items()));
        values.put(FreightOrderSchema.COL_STATE, order.state().name());
        values.put(FreightOrderSchema.COL_WAREHOUSE_ID,
                order.warehouseId() == null ? "" : order.warehouseId().toString());
        values.put(FreightOrderSchema.COL_PICKUP_ID,
                order.pickupId() == null ? "" : order.pickupId().toString());
        values.put(FreightOrderSchema.COL_CARRIER_ID,
                order.carrierId() == null ? "" : order.carrierId().toString());
        values.put(FreightOrderSchema.COL_NEXT_ATTEMPT, order.nextAttemptAt());
        values.put(FreightOrderSchema.COL_ATTEMPTS, order.attempts());
        values.put(FreightOrderSchema.COL_STATE_AT, order.stateAt());
        values.put(FreightOrderSchema.COL_CREATED_AT, order.createdAt());
        values.put(FreightOrderSchema.COL_REASON, order.reason() == null ? "" : order.reason());
        values.put(FreightOrderSchema.COL_UPDATED_AT, System.currentTimeMillis());
        KingdomPersistence.submit(FreightOrderSchema.TABLE_FREIGHT_ORDER,
                FreightOrderSchema.COL_ID, order.id().toString(), values);
    }

    private static void removeRow(FreightOrder order) {
        if (order == null || order.id() == null) {
            return;
        }
        KingdomPersistence.delete(FreightOrderSchema.TABLE_FREIGHT_ORDER,
                FreightOrderSchema.COL_ID, order.id().toString());
    }

    private static String encodeItems(List<ItemCount> items) {
        JsonArray array = new JsonArray();
        for (ItemCount item : items) {
            JsonObject node = new JsonObject();
            node.addProperty("item", item.item());
            node.addProperty("count", item.count());
            array.add(node);
        }
        return array.toString();
    }

    private static List<ItemCount> decodeItems(String json) {
        List<ItemCount> items = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return items;
        }
        try {
            for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
                JsonObject node = element.getAsJsonObject();
                items.add(new ItemCount(node.get("item").getAsString(),
                        node.get("count").getAsInt()));
            }
        } catch (RuntimeException ignored) {
        }
        return items;
    }

    private static BlockPos parsePos(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static UUID parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static FreightOrder.State parseState(String raw) {
        for (FreightOrder.State state : FreightOrder.State.values()) {
            if (state.name().equals(raw)) {
                return state;
            }
        }
        return FreightOrder.State.PENDING;
    }

    private void load(MinecraftServer server) {
        if (server == null || !KingdomPersistence.isReady()) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            return;
        }
        try {
            List<FreightOrder> rows = KingdomPersistence.querySync(connection -> {
                List<FreightOrder> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + FreightOrderSchema.columns()
                             + " FROM " + FreightOrderSchema.TABLE_FREIGHT_ORDER)) {
                    while (rs.next()) {
                        FreightOrder order = readOrder(rs);
                        if (order != null) {
                            result.add(order);
                        }
                    }
                }
                return result;
            });
            for (FreightOrder order : rows) {
                orders.put(order.id(), order);
            }
            loaded = true;
            if (!rows.isEmpty()) {
                ModMain.LOGGER.info("已恢复 {} 个货运订单", rows.size());
            }
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载货运订单失败", exception);
        }
    }

    private static FreightOrder readOrder(ResultSet rs) throws java.sql.SQLException {
        UUID id = parseId(rs.getString(FreightOrderSchema.COL_ID));
        if (id == null) {
            return null;
        }
        String state = rs.getString(FreightOrderSchema.COL_STATE);
        if ("DONE".equals(state) || "FAILED".equals(state)) {
            return null;
        }
        List<ItemCount> items = decodeItems(rs.getString(FreightOrderSchema.COL_ITEMS));
        if (items.isEmpty()) {
            return null;
        }
        String rawTarget = rs.getString(FreightOrderSchema.COL_TARGET);
        FreightOrder.Target target = "CONTAINER".equals(rawTarget)
                ? FreightOrder.Target.CONTAINER
                : ("WAREHOUSE".equals(rawTarget)
                        ? FreightOrder.Target.WAREHOUSE : FreightOrder.Target.BUILDING);
        FreightOrder order = FreightOrder.restore(id, target,
                parseId(rs.getString(FreightOrderSchema.COL_REQUESTER_ID)),
                parsePos(rs.getString(FreightOrderSchema.COL_DESTINATION)),
                rs.getString(FreightOrderSchema.COL_DIMENSION),
                items, rs.getLong(FreightOrderSchema.COL_CREATED_AT));
        order.setState(parseState(state));
        UUID warehouseId = parseId(rs.getString(FreightOrderSchema.COL_WAREHOUSE_ID));
        UUID pickupId = parseId(rs.getString(FreightOrderSchema.COL_PICKUP_ID));
        UUID carrierId = parseId(rs.getString(FreightOrderSchema.COL_CARRIER_ID));
        if (warehouseId != null) {
            order.setWarehouseId(warehouseId);
        }
        if (pickupId != null) {
            order.setPickupId(pickupId);
        }
        if (carrierId != null) {
            order.setCarrierId(carrierId);
        }
        order.setNextAttemptAt(rs.getLong(FreightOrderSchema.COL_NEXT_ATTEMPT));
        order.setAttempts(rs.getInt(FreightOrderSchema.COL_ATTEMPTS));
        order.setStateAt(rs.getLong(FreightOrderSchema.COL_STATE_AT));
        return order;
    }
}
