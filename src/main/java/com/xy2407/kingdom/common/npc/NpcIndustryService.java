package com.xy2407.kingdom.common.npc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.building.ItemCount;
import com.xy2407.kingdom.common.building.RecipeSpec;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 工业生产：按蓝图配方生产，进度记在 NPC 身上并支持换班移交
 */
public final class NpcIndustryService {

    private static final int ACTION_INTERVAL_TICKS = 20;

    private NpcIndustryService() {
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
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        if (meta == null || meta.recipes().isEmpty()) {
            return;
        }
        IItemHandler store = BuildingStorageStore.INSTANCE.handlerOf(server, building.id(), meta.storageSlots());
        if (store == null) {
            return;
        }
        // 三级产物链：背包里还压着上一炉的产出，先存进建筑仓库
        boolean storeRoom = ProduceStoring.storeHasRoom(level, building);
        if (ProduceStoring.bagHasProduce(npc.getNpcInventory())) {
            if (storeRoom) {
                ProduceStoring.depositBag(level, npc, building);
                storeRoom = ProduceStoring.storeHasRoom(level, building);
            } else {
                ProduceStoring.requestPickup(level, building);
            }
        }
        // 仓库满 + 背包满：等运输清出空间再开工，避免硬生产吞掉产出
        if (!ProduceStoring.bagHasRoom(npc.getNpcInventory()) && !storeRoom) {
            return;
        }
        RecipeSpec recipe = recipeOf(meta, data.getWorkRecipeId());
        if (recipe == null) {
            return;
        }
        if (!hasAll(store, recipe.inputs())) {
            return;
        }
        int progress = data.getWorkTicks() + ACTION_INTERVAL_TICKS;
        if (progress < recipe.ticks()) {
            data.setWorkTicks(progress);
            return;
        }
        if (consume(store, recipe.inputs())) {
            produce(level, npc, building, recipe.outputs());
            data.setWorkTicks(0);
        }
    }

    public static void assignWorkers(MinecraftServer server) {
        Map<UUID, BuildingRecord> buildings = new HashMap<>();
        Map<UUID, List<NpcData>> crews = new HashMap<>();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead()) {
                continue;
            }
            BuildingRecord building = buildingOf(npc);
            boolean industry = building != null && isIndustry(building)
                    && !isMiningKind(building);
            NpcBrain brain = npc.getBrain();
            if (!industry) {
                if (brain.isIndustryControlled()) {
                    brain.setIndustryControlled(false);
                }
                continue;
            }
            if (brain.isRtsControlled() || brain.isConstructionControlled()) {
                continue;
            }
            brain.setIndustryControlled(true);
            buildings.put(building.id(), building);
            crews.computeIfAbsent(building.id(), id -> new ArrayList<>()).add(npc);
        }
        for (Map.Entry<UUID, List<NpcData>> entry : crews.entrySet()) {
            handOver(buildings.get(entry.getKey()), entry.getValue());
        }
    }

    private static boolean isMiningKind(BuildingRecord building) {
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        return meta != null && (meta.isWork(BuildingWork.MINE) || meta.isWork(BuildingWork.FORGE));
    }

    private static void handOver(BuildingRecord building, List<NpcData> crew) {
        if (building == null || crew.size() < 2) {
            return;
        }
        Map<String, List<NpcData>> byRecipe = new HashMap<>();
        for (NpcData npc : crew) {
            byRecipe.computeIfAbsent(npc.getWorkRecipeId() == null ? "" : npc.getWorkRecipeId(),
                    id -> new ArrayList<>()).add(npc);
        }
        for (List<NpcData> same : byRecipe.values()) {
            handOverSameRecipe(building, same);
        }
    }

    private static void handOverSameRecipe(BuildingRecord building, List<NpcData> crew) {
        if (crew.size() < 2) {
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

    private static boolean isIndustry(BuildingRecord building) {
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        return meta != null && meta.isType(BuildingType.INDUSTRY);
    }

    private static boolean isInside(BuildingRecord building, NpcData npc) {
        String dimension = npc.getDimension() == null ? "" : npc.getDimension();
        BlockPos pos = NpcManager.INSTANCE.positionOf(npc);
        return pos != null && building.contains(dimension, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
    }

    private static RecipeSpec recipeOf(BuildingMeta meta, String recipeId) {
        if (recipeId == null || recipeId.isBlank()) {
            return null;
        }
        List<RecipeSpec> specs = meta.recipes();
        for (int index = 0; index < specs.size(); index++) {
            RecipeSpec spec = specs.get(index);
            String key = spec.id() == null || spec.id().isBlank() ? "#" + index : spec.id();
            if (key.equals(recipeId)) {
                return spec;
            }
        }
        return null;
    }

    private static boolean hasAll(IItemHandler store, List<ItemCount> inputs) {
        for (ItemCount need : inputs) {
            Item item = itemOf(need.item());
            if (item == null || countOf(store, item) < need.count()) {
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

    private static boolean consume(IItemHandler store, List<ItemCount> inputs) {
        if (!hasAll(store, inputs)) {
            return false;
        }
        for (ItemCount need : inputs) {
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
        }
        return true;
    }

    /** 三级产物链：产出先入 NPC 背包，随即存入建筑仓库；存不下的催运输、掉脚边，绝不吞掉。 */
    private static void produce(ServerLevel level, NpcEntity npc, BuildingRecord building,
                                List<ItemCount> outputs) {
        NpcInventory inventory = npc.getNpcInventory();
        for (ItemCount out : outputs) {
            Item item = itemOf(out.item());
            if (item == null) {
                continue;
            }
            int remaining = out.count();
            int maxStack = new ItemStack(item).getMaxStackSize();
            while (remaining > 0) {
                int batch = Math.min(remaining, maxStack);
                ItemStack stack = new ItemStack(item, batch);
                for (int slot = NpcInventory.BAG_START; slot < NpcInventory.SLOT_COUNT
                        && !stack.isEmpty(); slot++) {
                    stack = inventory.insertItem(slot, stack, false);
                }
                if (!stack.isEmpty()) {
                    net.minecraft.world.level.block.Block.popResource(level,
                            npc.blockPosition(), stack);
                    remaining = 0;
                } else {
                    remaining -= batch;
                }
            }
        }
        ProduceStoring.depositBag(level, npc, building);
        if (!ProduceStoring.bagHasRoom(inventory)) {
            ProduceStoring.requestPickup(level, building);
        }
    }

    private static Item itemOf(String id) {
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
