package com.xy2407.kingdom.server.forge;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.ItemCount;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.forge.ForgePlan;
import com.xy2407.kingdom.common.forge.ForgeRecipePlanner;
import com.xy2407.kingdom.common.forge.ForgeStep;
import com.xy2407.kingdom.common.forge.ForgeTask;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcInventory;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.ProduceStoring;
import com.xy2407.kingdom.common.persistence.ForgeTaskSchema;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;
import com.xy2407.kingdom.server.freight.FreightService;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 锻造坊服务，按任务账本判定材料与产出，工人分工到位后逐段推进步骤
 */
public final class ForgeService {

    public static final ForgeService INSTANCE = new ForgeService();

    private static final int SUPPLY_INTERVAL_TICKS = 100;

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    private static final int CREW_REFRESH_TICKS = 100;

    private static final int SMELT_SLOTS = 2;

    private final Map<UUID, ForgeTask> tasks = new HashMap<>();

    private final Map<UUID, String> workerStep = new HashMap<>();

    private final Map<UUID, Integer> workerProgress = new HashMap<>();

    private final Map<UUID, List<UUID>> crews = new HashMap<>();

    private final Map<UUID, Long> crewRefreshedAt = new HashMap<>();

    private int supplyCounter;

    private int loadRetryCooldown;

    private boolean loaded;

    private ForgeService() {
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
        if (tasks.isEmpty()) {
            return;
        }
        boolean supplyNow = ++supplyCounter % SUPPLY_INTERVAL_TICKS == 0;
        List<UUID> finished = new ArrayList<>();
        for (ForgeTask task : tasks.values()) {
            BuildingRecord building = BuildingRegistry.INSTANCE.byId(task.buildingId());
            if (building == null) {
                finished.add(task.buildingId());
                continue;
            }
            if (task.state() == ForgeTask.State.PREPARING) {
                task.setState(ForgeTask.State.RUNNING);
            }
            ItemStackHandler store = storeOf(server, building);
            if (store == null) {
                continue;
            }
            if (task.finished()) {
                finished.add(task.buildingId());
                continue;
            }
            if (supplyNow) {
                supply(server, task, building, store);
            }
            runWorkers(server, task, building, store);
        }
        for (UUID buildingId : finished) {
            clear(buildingId);
        }
    }

    public ForgeTask taskOf(UUID buildingId) {
        return buildingId == null ? null : tasks.get(buildingId);
    }

    public ForgeTask submit(ServerLevel level, BuildingRecord building, String itemId, int count) {
        if (level == null || building == null || itemId == null || itemId.isBlank() || count <= 0) {
            return null;
        }
        if (tasks.containsKey(building.id())) {
            return null;
        }
        ItemStackHandler store = storeOf(level.getServer(), building);
        if (store == null) {
            return null;
        }
        Map<String, Integer> stock = stockOf(store);
        ForgePlan plan = ForgeRecipePlanner.plan(level, itemId, count, stock);
        if (plan.steps().isEmpty()) {
            return null;
        }
        ForgeTask task = new ForgeTask(building.id(), building.dimension(), itemId, count, plan,
                level.getGameTime());
        task.creditAll(stock);
        tasks.put(building.id(), task);
        crewRefreshedAt.remove(building.id());
        persist(task);
        assign(task, crewOf(level.getServer(), building));
        persist(task);
        supply(level.getServer(), task, building, store);
        return task;
    }

    public boolean cancel(UUID buildingId) {
        if (buildingId == null || !tasks.containsKey(buildingId)) {
            return false;
        }
        clear(buildingId);
        return true;
    }

    private void clear(UUID buildingId) {
        tasks.remove(buildingId);
        crews.remove(buildingId);
        crewRefreshedAt.remove(buildingId);
        if (buildingId != null) {
            String owner = buildingId.toString();
            workerStep.keySet().removeIf(npcId -> {
                NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
                return npc == null || owner.equals(npc.getWorkplaceId());
            });
            workerProgress.keySet().retainAll(workerStep.keySet());
        }
        KingdomPersistence.delete(ForgeTaskSchema.TABLE_FORGE_TASK,
                ForgeTaskSchema.COL_BUILDING_ID, buildingId.toString());
    }

    private List<NpcData> crewOf(MinecraftServer server, BuildingRecord building) {
        long now = server.overworld().getGameTime();
        List<UUID> cached = crews.get(building.id());
        Long refreshedAt = crewRefreshedAt.get(building.id());
        if (cached == null || refreshedAt == null || now - refreshedAt >= CREW_REFRESH_TICKS) {
            String owner = building.id().toString();
            List<UUID> ids = new ArrayList<>();
            for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
                if (npc.isDead() || !owner.equals(npc.getWorkplaceId())) {
                    continue;
                }
                ids.add(npc.getId());
            }
            crews.put(building.id(), ids);
            crewRefreshedAt.put(building.id(), now);
            cached = ids;
        }
        List<NpcData> crew = new ArrayList<>(cached.size());
        for (UUID npcId : cached) {
            NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
            if (npc != null && !npc.isDead()) {
                crew.add(npc);
            }
        }
        return crew;
    }

    private void assign(ForgeTask task, List<NpcData> crew) {
        if (crew.isEmpty()) {
            return;
        }
        Set<UUID> present = new HashSet<>();
        for (NpcData npc : crew) {
            present.add(npc.getId());
        }
        task.roles().keySet().removeIf(npcId -> !present.contains(npcId));
        List<NpcData> fresh = new ArrayList<>();
        for (NpcData npc : crew) {
            if (task.roleOf(npc.getId()) == null) {
                fresh.add(npc);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        int smeltWork = 0;
        int craftWork = 0;
        for (ForgeStep step : task.plan().steps()) {
            int work = step.runs() * step.ticks();
            if (step.kind() == ForgeStep.Kind.SMELT) {
                smeltWork += Math.max(1, work / SMELT_SLOTS);
            } else {
                craftWork += work;
            }
        }
        int wantSmelt;
        if (smeltWork <= 0) {
            wantSmelt = 0;
        } else if (craftWork <= 0) {
            wantSmelt = crew.size();
        } else if (crew.size() == 1) {
            wantSmelt = smeltWork >= craftWork ? 1 : 0;
        } else {
            int ratio = Math.round(crew.size() * (float) smeltWork / (smeltWork + craftWork));
            wantSmelt = Math.max(1, Math.min(crew.size() - 1, ratio));
        }
        int currentSmelt = 0;
        for (String role : task.roles().values()) {
            if (ForgeTask.ROLE_SMELT.equals(role)) {
                currentSmelt++;
            }
        }
        for (NpcData npc : fresh) {
            if (currentSmelt < wantSmelt) {
                task.setRole(npc.getId(), ForgeTask.ROLE_SMELT);
                currentSmelt++;
            } else {
                task.setRole(npc.getId(), ForgeTask.ROLE_CRAFT);
            }
        }
    }

    private void runWorkers(MinecraftServer server, ForgeTask task, BuildingRecord building,
                            ItemStackHandler store) {
        List<NpcData> crew = crewOf(server, building);
        if (crew.isEmpty()) {
            return;
        }
        // 三级产物链：工人背包里压着的上一炉产出，先存进建筑仓库；仓库满就催运输
        ServerLevel level = levelOf(server, building.dimension());
        for (NpcData npc : crew) {
            NpcEntity entity = NpcManager.INSTANCE.liveEntity(npc);
            if (entity == null || !ProduceStoring.bagHasProduce(entity.getNpcInventory())) {
                continue;
            }
            if (ProduceStoring.storeHasRoom(level, building)) {
                ProduceStoring.depositBag(level, entity, building);
            } else {
                ProduceStoring.requestPickup(level, building);
            }
        }
        assign(task, crew);
        Map<String, Integer> stock = stockOf(store);
        for (NpcData npc : crew) {
            if (NpcManager.INSTANCE.liveEntity(npc) == null) {
                continue;
            }
            ForgeStep step = pickStep(task, stock, npc.getId());
            if (step == null) {
                workerStep.remove(npc.getId());
                workerProgress.remove(npc.getId());
                continue;
            }
            String current = workerStep.get(npc.getId());
            if (!step.key().equals(current)) {
                workerStep.put(npc.getId(), step.key());
                workerProgress.put(npc.getId(), 0);
            }
            advance(task, step, npc.getId(), building, store, stock);
        }
        repairShortage(task, stock);
    }

    private ForgeStep pickStep(ForgeTask task, Map<String, Integer> stock, UUID npcId) {
        ForgeStep.Kind locked = kindOf(task, workerStep.get(npcId));
        if (locked != null) {
            ForgeStep same = findStep(task, stock, locked);
            if (same != null) {
                return same;
            }
        }
        String role = task.roleOf(npcId);
        if (ForgeTask.ROLE_SMELT.equals(role)) {
            ForgeStep mine = findStep(task, stock, ForgeStep.Kind.SMELT);
            if (mine != null) {
                return mine;
            }
            return findStep(task, stock, ForgeStep.Kind.CRAFT);
        }
        if (ForgeTask.ROLE_CRAFT.equals(role)) {
            ForgeStep mine = findStep(task, stock, ForgeStep.Kind.CRAFT);
            if (mine != null) {
                return mine;
            }
            return findStep(task, stock, ForgeStep.Kind.SMELT);
        }
        return findStep(task, stock, null);
    }

    private static ForgeStep.Kind kindOf(ForgeTask task, String key) {
        if (key == null) {
            return null;
        }
        for (ForgeStep step : task.plan().steps()) {
            if (step.key().equals(key)) {
                return step.kind();
            }
        }
        return null;
    }

    private static ForgeStep findStep(ForgeTask task, Map<String, Integer> stock,
                                      ForgeStep.Kind kind) {
        for (ForgeStep step : task.plan().steps()) {
            if (kind != null && step.kind() != kind) {
                continue;
            }
            if (task.remainingOf(step) <= 0) {
                continue;
            }
            if (!canRun(task, stock, step)) {
                continue;
            }
            return step;
        }
        return null;
    }

    private static boolean canRun(ForgeTask task, Map<String, Integer> stock, ForgeStep step) {
        for (ItemCount input : step.inputs()) {
            if (task.ledgerOf(input.item()) < input.count()) {
                return false;
            }
        }
        for (ItemCount input : step.inputs()) {
            if (usableOf(task, stock, input.item()) < input.count()) {
                return false;
            }
        }
        return true;
    }

    private void repairShortage(ForgeTask task, Map<String, Integer> stock) {
        for (ForgeStep step : task.plan().steps()) {
            if (task.remainingOf(step) <= 0) {
                continue;
            }
            for (ItemCount input : step.inputs()) {
                if (usableOf(task, stock, input.item()) >= input.count()) {
                    continue;
                }
                ForgeStep source = producerOf(task, input.item());
                if (source == null || task.remainingOf(source) > 0) {
                    continue;
                }
                if (task.doneOf(source.key()) <= 0) {
                    continue;
                }
                task.addProgress(source.key(), -1);
                persist(task);
                return;
            }
        }
    }

    private static ForgeStep producerOf(ForgeTask task, String item) {
        for (ForgeStep step : task.plan().steps()) {
            if (step.output().equals(item)) {
                return step;
            }
        }
        return null;
    }

    private void advance(ForgeTask task, ForgeStep step, UUID npcId, BuildingRecord building,
                         ItemStackHandler store, Map<String, Integer> stock) {
        if (task.remainingOf(step) <= 0) {
            return;
        }
        int speed = step.kind() == ForgeStep.Kind.SMELT ? SMELT_SLOTS : 1;
        int progress = workerProgress.getOrDefault(npcId, 0) + speed;
        if (progress < step.ticks()) {
            workerProgress.put(npcId, progress);
            return;
        }
        NpcEntity worker = liveWorker(npcId);
        boolean storeRoom = canFit(store, step.output(), step.outputCount());
        boolean bagRoom = worker == null || canFitBag(worker, step.output(), step.outputCount());
        if (!storeRoom && !bagRoom) {
            // 仓库和背包都塞不下：不消耗原料，催运输把仓库清出空间
            workerProgress.put(npcId, step.ticks() - 1);
            if (worker != null && worker.level() instanceof ServerLevel level) {
                ProduceStoring.requestPickup(level, building);
            }
            return;
        }
        if (!canRun(task, stock, step)) {
            workerProgress.put(npcId, 0);
            return;
        }
        if (!consume(store, step.inputs())) {
            return;
        }
        produce(worker, building, store, step.output(), step.outputCount());
        for (ItemCount input : step.inputs()) {
            task.noteConsume(input.item(), input.count());
            stock.merge(input.item(), -input.count(), Integer::sum);
        }
        task.noteProduce(step.output(), step.outputCount());
        stock.merge(step.output(), step.outputCount(), Integer::sum);
        workerProgress.put(npcId, progress - step.ticks());
        task.addProgress(step.key(), 1);
        persist(task);
    }

    private void supply(MinecraftServer server, ForgeTask task, BuildingRecord building,
                        ItemStackHandler store) {
        List<ItemCount> missing = missingOf(task, store);
        if (missing.isEmpty()) {
            return;
        }
        List<ItemCount> pending = FreightService.INSTANCE.pendingOf(building.id());
        List<ItemCount> still = new ArrayList<>();
        for (ItemCount need : missing) {
            int onWay = 0;
            for (ItemCount order : pending) {
                if (order.item().equals(need.item())) {
                    onWay += order.count();
                }
            }
            int left = need.count() - onWay;
            if (left > 0) {
                still.add(new ItemCount(need.item(), left));
            }
        }
        if (still.isEmpty()) {
            return;
        }
        FreightService.INSTANCE.submit(server, building.id(), building.dimension(), still);
        for (ItemCount item : still) {
            task.credit(item.item(), item.count());
        }
        persist(task);
        task.setNextSupplyAt(server.overworld().getGameTime() + SUPPLY_INTERVAL_TICKS);
    }

    public static List<ItemCount> missingOf(ForgeTask task, ItemStackHandler store) {
        return missingOf(task, stockOf(store));
    }

    public static List<ItemCount> missingOf(ForgeTask task, Map<String, Integer> stock) {
        Set<String> internal = new HashSet<>();
        for (ForgeStep step : task.plan().steps()) {
            internal.add(step.output());
        }
        Map<String, Integer> need = new LinkedHashMap<>();
        for (ForgeStep step : task.plan().steps()) {
            int remaining = task.remainingOf(step);
            if (remaining <= 0) {
                continue;
            }
            for (ItemCount input : step.inputs()) {
                if (internal.contains(input.item())) {
                    continue;
                }
                need.merge(input.item(), input.count() * remaining, Integer::sum);
            }
        }
        List<ItemCount> missing = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : need.entrySet()) {
            int have = usableOf(task, stock, entry.getKey());
            if (have < entry.getValue()) {
                missing.add(new ItemCount(entry.getKey(), entry.getValue() - have));
            }
        }
        return missing;
    }

    private static int usableOf(ForgeTask task, Map<String, Integer> stock, String item) {
        int ledger = Math.max(0, task.ledgerOf(item));
        int held = stock == null ? 0 : Math.max(0, stock.getOrDefault(item, 0));
        return Math.min(ledger, held);
    }

    private static Map<String, Integer> stockOf(ItemStackHandler store) {
        Map<String, Integer> stock = new HashMap<>();
        if (store == null) {
            return stock;
        }
        for (int slot = 0; slot < store.getSlots(); slot++) {
            ItemStack stack = store.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            stock.merge(id, stack.getCount(), Integer::sum);
        }
        return stock;
    }

    private static ItemStackHandler storeOf(MinecraftServer server, BuildingRecord building) {
        if (server == null || building == null) {
            return null;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        int slots = Math.max(1, meta == null ? 27 : meta.storageSlots());
        return BuildingStorageStore.INSTANCE.handlerOf(server, building.id(), slots);
    }

    private static boolean consume(ItemStackHandler store, List<ItemCount> items) {
        for (ItemCount need : items) {
            Item item = itemOf(need.item());
            if (item == null) {
                return false;
            }
            int remaining = need.count();
            for (int slot = 0; slot < store.getSlots() && remaining > 0; slot++) {
                ItemStack stack = store.getStackInSlot(slot);
                if (!stack.is(item)) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                store.extractItem(slot, take, false);
                remaining -= take;
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    /** 三级产物链：产出先进工人背包 24 格，随即存进建筑仓库；装不下的留在背包等下一轮或催运输。 */
    private static void produce(NpcEntity worker, BuildingRecord building, ItemStackHandler store,
                                String itemId, int count) {
        Item item = itemOf(itemId);
        if (item == null) {
            return;
        }
        int remaining = count;
        if (worker != null) {
            NpcInventory inventory = worker.getNpcInventory();
            for (int slot = NpcInventory.BAG_START; slot < NpcInventory.SLOT_COUNT
                    && remaining > 0; slot++) {
                ItemStack left = inventory.insertItem(slot, new ItemStack(item, remaining), false);
                remaining = left.getCount();
            }
            if (remaining < count && worker.level() instanceof ServerLevel level) {
                ProduceStoring.depositBag(level, worker, building);
            }
        }
        for (int slot = 0; slot < store.getSlots() && remaining > 0; slot++) {
            ItemStack left = store.insertItem(slot, new ItemStack(item, remaining), false);
            remaining = left.getCount();
        }
        if (remaining > 0 && worker != null && worker.level() instanceof ServerLevel level) {
            net.minecraft.world.level.block.Block.popResource(level, worker.blockPosition(),
                    new ItemStack(item, remaining));
        }
    }

    /** 工人背包 24 格还能塞下多少件指定物品。 */
    private static boolean canFitBag(NpcEntity worker, String itemId, int count) {
        Item item = itemOf(itemId);
        if (item == null || worker == null) {
            return false;
        }
        NpcInventory inventory = worker.getNpcInventory();
        int room = 0;
        for (int slot = NpcInventory.BAG_START; slot < NpcInventory.SLOT_COUNT; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            int limit = Math.min(inventory.getSlotLimit(slot), item.getDefaultMaxStackSize());
            if (stack.isEmpty()) {
                room += limit;
            } else if (stack.is(item)) {
                room += Math.max(0, limit - stack.getCount());
            }
            if (room >= count) {
                return true;
            }
        }
        return room >= count;
    }

    private static NpcEntity liveWorker(UUID npcId) {
        NpcData data = NpcManager.INSTANCE.getNpc(npcId);
        return data == null ? null : NpcManager.INSTANCE.liveEntity(data);
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    private static boolean canFit(ItemStackHandler store, String itemId, int count) {
        Item item = itemOf(itemId);
        if (item == null) {
            return false;
        }
        int room = 0;
        for (int slot = 0; slot < store.getSlots(); slot++) {
            ItemStack stack = store.getStackInSlot(slot);
            int limit = store.getSlotLimit(slot);
            if (stack.isEmpty()) {
                room += Math.min(limit, item.getDefaultMaxStackSize());
            } else if (stack.is(item)) {
                room += Math.max(0, limit - stack.getCount());
            }
            if (room >= count) {
                return true;
            }
        }
        return room >= count;
    }

    private static Item itemOf(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            return null;
        }
        return BuiltInRegistries.ITEM.get(key);
    }

    private void persist(ForgeTask task) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(ForgeTaskSchema.COL_BUILDING_ID, task.buildingId().toString());
        values.put(ForgeTaskSchema.COL_DIMENSION, task.dimension());
        values.put(ForgeTaskSchema.COL_TARGET, task.target());
        values.put(ForgeTaskSchema.COL_REQUESTED, task.requested());
        values.put(ForgeTaskSchema.COL_STATE, task.state().name());
        values.put(ForgeTaskSchema.COL_PLAN, task.encodePlan());
        values.put(ForgeTaskSchema.COL_PROGRESS, task.encodeProgress());
        values.put(ForgeTaskSchema.COL_CREATED_DAY, task.createdDay());
        values.put(ForgeTaskSchema.COL_UPDATED_AT, System.currentTimeMillis());
        KingdomPersistence.submit(ForgeTaskSchema.TABLE_FORGE_TASK,
                ForgeTaskSchema.COL_BUILDING_ID, task.buildingId().toString(), values);
    }

    private void load(MinecraftServer server) {
        if (server == null || !KingdomPersistence.isReady()) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            return;
        }
        try {
            List<ForgeTask> rows = KingdomPersistence.querySync(connection -> {
                List<ForgeTask> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + ForgeTaskSchema.columns()
                             + " FROM " + ForgeTaskSchema.TABLE_FORGE_TASK)) {
                    while (rs.next()) {
                        result.add(readRow(rs));
                    }
                }
                return result;
            });
            tasks.clear();
            for (ForgeTask task : rows) {
                tasks.put(task.buildingId(), task);
            }
            loaded = true;
            if (!rows.isEmpty()) {
                ModMain.LOGGER.info("已恢复 {} 个锻造任务", rows.size());
            }
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载锻造任务失败", exception);
        }
    }

    private static ForgeTask readRow(ResultSet rs) throws java.sql.SQLException {
        ForgePlan plan = ForgeTask.decodePlan(rs.getString(ForgeTaskSchema.COL_PLAN));
        ForgeTask task = new ForgeTask(
                UUID.fromString(rs.getString(ForgeTaskSchema.COL_BUILDING_ID)),
                rs.getString(ForgeTaskSchema.COL_DIMENSION),
                rs.getString(ForgeTaskSchema.COL_TARGET),
                rs.getInt(ForgeTaskSchema.COL_REQUESTED),
                plan,
                rs.getLong(ForgeTaskSchema.COL_CREATED_DAY));
        task.loadProgress(rs.getString(ForgeTaskSchema.COL_PROGRESS));
        String state = rs.getString(ForgeTaskSchema.COL_STATE);
        task.setState("DONE".equals(state) ? ForgeTask.State.DONE : ForgeTask.State.RUNNING);
        return task;
    }

    public Set<UUID> activeBuildings() {
        return new HashSet<>(tasks.keySet());
    }
}
