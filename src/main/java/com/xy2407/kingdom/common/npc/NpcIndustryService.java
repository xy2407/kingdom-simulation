package com.xy2407.kingdom.common.npc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.building.ItemCount;
import com.xy2407.kingdom.common.building.RecipeSpec;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 工业生产：配方直接来自蓝图 json（{@code BuildingMeta#recipes}），
 * 工作进度记在 NPC 身上（{@code NpcData#workTicks}）—— 跨班次保留，
 * 换班/换人时由 {@link #handOver} 移交给接班的同事，白班做一半的活由夜班接着做完。
 */
public final class NpcIndustryService {

    /** 生产推进的节拍：每 20 tick 结算一次，避免每 tick 全量扫仓库。 */
    private static final int ACTION_INTERVAL_TICKS = 20;

    private NpcIndustryService() {
    }

    /** NPC 实体侧每 tick 调用（detail == PRODUCING 时）。 */
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
        RecipeSpec recipe = recipeOf(meta, data.getWorkRecipeId());
        if (recipe == null) {
            // 没被派到具体配方就不开工：面板上"派工"才是开产开关
            return;
        }
        if (!hasAll(store, recipe.inputs())) {
            // 材料不齐：进度保留不清零 —— 下班回家或换班后再来，接着往本轮上累计。
            return;
        }
        int progress = data.getWorkTicks() + ACTION_INTERVAL_TICKS;
        if (progress < recipe.ticks()) {
            data.setWorkTicks(progress);
            return;
        }
        if (consume(store, recipe.inputs())) {
            produce(store, recipe.outputs());
            data.setWorkTicks(0);
        }
    }

    /** 每 100 tick 由服务端调度调用：刷新"谁在工业建筑上班"，并处理换班移交。 */
    public static void assignWorkers(MinecraftServer server) {
        Map<UUID, BuildingRecord> buildings = new HashMap<>();
        Map<UUID, List<NpcData>> crews = new HashMap<>();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead()) {
                continue;
            }
            BuildingRecord building = buildingOf(npc);
            boolean industry = building != null && isIndustry(building);
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

    /**
     * 换班移交：把"已离岗（不在建筑范围内）的同事"身上累积的 tick 交给当前在岗的人，
     * 并把离岗者的进度清零（避免下一个人回来时重复计算同一段工时）。
     */
    private static void handOver(BuildingRecord building, List<NpcData> crew) {
        if (building == null || crew.size() < 2) {
            return;
        }
        // 只在做同一张配方的人之间移交：各配方各算各的，不能互相顶班
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
        return meta != null && meta.type() == BuildingType.INDUSTRY;
    }

    /** 只有人真的在建筑范围内才算在岗（下班离开后进度停住，等下一次移交）。 */
    private static boolean isInside(BuildingRecord building, NpcData npc) {
        String dimension = npc.getDimension() == null ? "" : npc.getDimension();
        return building.contains(dimension, npc.getX(), npc.getY(), npc.getZ());
    }

    /** 按工人被派的那张配方 id 取配方；json 里没写 id 的配方退回按下标匹配。 */
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

    private static void produce(IItemHandler store, List<ItemCount> outputs) {
        for (ItemCount out : outputs) {
            Item item = itemOf(out.item());
            if (item == null) {
                continue;
            }
            int remaining = out.count();
            for (int slot = 0; slot < store.getSlots() && remaining > 0; slot++) {
                ItemStack left = store.insertItem(slot, new ItemStack(item, remaining), false);
                remaining = left.getCount();
            }
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
