package com.xy2407.kingdom.server.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.building.ItemCount;
import com.xy2407.kingdom.common.forge.ForgeStep;
import com.xy2407.kingdom.common.forge.ForgeTask;
import com.xy2407.kingdom.common.network.forge.ForgePanelActionPacket;
import com.xy2407.kingdom.common.network.forge.ForgePanelStatePacket;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 锻造坊面板服务端，下发可合成清单与委托进度，处理提交与取消请求
 */
public final class ForgePanelService {

    private static final int CATALOG_LIMIT = 800;

    private static volatile List<String> catalog;

    private ForgePanelService() {
    }

    public static void respond(ServerPlayer player) {
        BuildingRecord building = forgeAt(player);
        if (building == null) {
            PacketDistributor.sendToPlayer(player, ForgePanelStatePacket.outside());
            return;
        }
        PacketDistributor.sendToPlayer(player, snapshot(player, building));
    }

    public static void apply(ServerPlayer player, ForgePanelActionPacket packet) {
        BuildingRecord building = forgeAt(player);
        if (building == null) {
            respond(player);
            return;
        }
        if (packet.action() == ForgePanelActionPacket.ACTION_CANCEL) {
            ForgeService.INSTANCE.cancel(building.id());
            respond(player);
            return;
        }
        if (packet.action() == ForgePanelActionPacket.ACTION_SUBMIT) {
            if (ForgeService.INSTANCE.taskOf(building.id()) != null) {
                player.displayClientMessage(Component.literal(
                        "这座锻造坊已有委托，先取消再下新单"), true);
                respond(player);
                return;
            }
            int count = Math.max(1, Math.min(4096, packet.count()));
            ForgeTask task = ForgeService.INSTANCE.submit(player.serverLevel(), building,
                    packet.itemId(), count);
            if (task == null) {
                player.displayClientMessage(Component.literal(
                        "这个物品没有可用的合成或烧炼配方"), true);
            } else {
                player.displayClientMessage(Component.literal(
                        "已提交委托：" + packet.itemId() + " × " + count), true);
            }
        }
        respond(player);
    }

    private static BuildingRecord forgeAt(ServerPlayer player) {
        BuildingRecord building = BuildingRegistry.INSTANCE.locate(player);
        if (building == null) {
            return null;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        return meta != null && meta.isWork(BuildingWork.FORGE) ? building : null;
    }

    private static ForgePanelStatePacket snapshot(ServerPlayer player, BuildingRecord building) {
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        String name = meta == null || meta.name() == null || meta.name().isBlank()
                ? building.name() : meta.name();
        List<ForgePanelStatePacket.Task> tasks = new ArrayList<>();
        ForgeTask task = ForgeService.INSTANCE.taskOf(building.id());
        if (task != null) {
            tasks.add(summarize(player.serverLevel(), building, task));
        }
        return new ForgePanelStatePacket(true, building.id(), name, building.level(),
                catalog(player.serverLevel()), tasks);
    }

    private static ForgePanelStatePacket.Task summarize(ServerLevel level, BuildingRecord building,
                                                        ForgeTask task) {
        List<ForgePanelStatePacket.Step> steps = new ArrayList<>();
        for (ForgeStep step : task.plan().steps()) {
            steps.add(new ForgePanelStatePacket.Step(step.kind().name(), step.output(),
                    task.doneOf(step.key()), step.runs()));
        }
        return new ForgePanelStatePacket.Task(task.target(), task.requested(),
                task.state().name(), missingText(level, building, task), steps);
    }

    private static String missingText(ServerLevel level, BuildingRecord building, ForgeTask task) {
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        int slots = Math.max(1, meta == null ? 27 : meta.storageSlots());
        ItemStackHandler store = BuildingStorageStore.INSTANCE.handlerOf(level.getServer(),
                building.id(), slots);
        if (store == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (ItemCount missing : ForgeService.missingOf(task, store)) {
            if (parts.size() >= 3) {
                break;
            }
            parts.add(shortName(missing.item()) + "×" + missing.count());
        }
        return String.join("、", parts);
    }

    private static String shortName(String itemId) {
        int colon = itemId.indexOf(':');
        return colon < 0 ? itemId : itemId.substring(colon + 1);
    }

    private static List<String> catalog(ServerLevel level) {
        List<String> cached = catalog;
        if (cached != null) {
            return cached;
        }
        Set<String> ids = new TreeSet<>();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            boolean cooking = recipe instanceof AbstractCookingRecipe;
            if (!cooking && recipe.getType() != RecipeType.CRAFTING) {
                continue;
            }
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (result.isEmpty()) {
                continue;
            }
            ids.add(BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
            if (ids.size() >= CATALOG_LIMIT) {
                break;
            }
        }
        List<String> list = new ArrayList<>(ids);
        catalog = list;
        return list;
    }

    public static void reset() {
        catalog = null;
    }
}
