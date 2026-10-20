package com.xy2407.kingdom.server.industry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.building.ItemCount;
import com.xy2407.kingdom.common.building.RecipeSpec;
import com.xy2407.kingdom.common.network.industry.IndustryPanelActionPacket;
import com.xy2407.kingdom.common.network.industry.IndustryPanelStatePacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcSchedule;
import com.xy2407.kingdom.common.npc.NpcSubState;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.npc.NpcOccupation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 工业面板服务端：应答请求、执行派工与撤工并整份回推
 */
public final class IndustryPanelService {

    private IndustryPanelService() {
    }

    public static void respond(ServerPlayer player) {
        BuildingRecord building = industryAt(player);
        if (building == null) {
            PacketDistributor.sendToPlayer(player, IndustryPanelStatePacket.outside());
            return;
        }
        PacketDistributor.sendToPlayer(player, snapshot(building));
    }

    public static void apply(ServerPlayer player, IndustryPanelActionPacket packet) {
        BuildingRecord building = industryAt(player);
        if (building == null) {
            respond(player);
            return;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        if (meta == null) {
            respond(player);
            return;
        }
        String recipeKey = packet.recipeIdOrEmpty();
        if (recipeKey.isBlank() || !isKnownRecipe(meta, recipeKey)) {
            player.displayClientMessage(Component.literal("这张配方已不存在"), true);
            respond(player);
            return;
        }
        switch (packet.action()) {
            case IndustryPanelActionPacket.ACTION_TOGGLE_WORKER -> {
                UUID workerId = packet.workerIdOrNull();
                NpcData npc = workerId == null ? null : NpcManager.INSTANCE.getNpc(workerId);
                if (npc != null && !npc.isDead()) {
                    boolean sameSlot = building.id().toString().equals(npc.getWorkplaceId())
                            && recipeKey.equals(npc.getWorkRecipeId());
                    String conflict = sameSlot ? null : NpcOccupation.conflict(npc, building.id());
                    if (conflict == null) {
                        toggleWorker(building, recipeKey, npc);
                    } else {
                        player.displayClientMessage(
                                Component.literal(npc.getName() + " " + conflict + "，无法派工"), true);
                    }
                }
            }
            case IndustryPanelActionPacket.ACTION_CLEAR_WORKERS -> clearWorkers(building, recipeKey);
            default -> {
            }
        }
        respond(player);
    }

    private static void toggleWorker(BuildingRecord building, String recipeKey, NpcData npc) {
        String buildingId = building.id().toString();
        boolean sameSlot = buildingId.equals(npc.getWorkplaceId())
                && recipeKey.equals(npc.getWorkRecipeId());
        if (sameSlot) {
            npc.setWorkRecipeId("");
            npc.setWorkplaceId(null);
            return;
        }
        npc.setWorkplaceId(buildingId);
        npc.setWorkRecipeId(recipeKey);
    }

    private static void clearWorkers(BuildingRecord building, String recipeKey) {
        String buildingId = building.id().toString();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead()) {
                continue;
            }
            if (buildingId.equals(npc.getWorkplaceId()) && recipeKey.equals(npc.getWorkRecipeId())) {
                npc.setWorkRecipeId("");
                npc.setWorkplaceId(null);
            }
        }
    }

    private static IndustryPanelStatePacket snapshot(BuildingRecord building) {
        List<IndustryPanelStatePacket.Recipe> recipes = new ArrayList<>();
        List<RecipeSpec> specs = BuildingRegistry.INSTANCE.metaOf(building).recipes();
        for (int index = 0; index < specs.size(); index++) {
            RecipeSpec spec = specs.get(index);
            String key = recipeKey(spec, index);
            List<UUID> crew = crewOf(building, key);
            recipes.add(new IndustryPanelStatePacket.Recipe(key, spec.name(), firstOutput(spec),
                    summarize(spec.inputs()), summarize(spec.outputs()), spec.ticks(),
                    crew.isEmpty() ? "待派工" : "已派 " + crew.size() + " 人", crew));
        }
        return new IndustryPanelStatePacket(true, building.name(), building.level(),
                positionText(building), recipes, roster(building.id()));
    }

    private static List<UUID> crewOf(BuildingRecord building, String recipeKey) {
        String buildingId = building.id().toString();
        List<UUID> crew = new ArrayList<>();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead()) {
                continue;
            }
            if (buildingId.equals(npc.getWorkplaceId()) && recipeKey.equals(npc.getWorkRecipeId())) {
                crew.add(npc.getId());
            }
        }
        return crew;
    }

    private static List<IndustryPanelStatePacket.Worker> roster(UUID buildingId) {
        List<IndustryPanelStatePacket.Worker> options = new ArrayList<>();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead()) {
                continue;
            }
            options.add(new IndustryPanelStatePacket.Worker(npc.getId(), npc.getName(), npc.getSkin(),
                    workerNote(npc, buildingId)));
        }
        return options;
    }

    private static String workerNote(NpcData npc, UUID buildingId) {
        String conflict = NpcOccupation.note(npc, buildingId);
        if (!conflict.isEmpty()) {
            return conflict;
        }
        if (npc.getBrain().isStarving()) {
            return "饥饿";
        }
        return NpcSchedule.subState(npc.getShift(), npc.getDayTime()) == NpcSubState.WORKING ? "" : "下班";
    }

    private static String recipeKey(RecipeSpec spec, int index) {
        return spec.id() == null || spec.id().isBlank() ? "#" + index : spec.id();
    }

    private static boolean isKnownRecipe(BuildingMeta meta, String recipeKey) {
        List<RecipeSpec> specs = meta.recipes();
        for (int index = 0; index < specs.size(); index++) {
            if (recipeKey(specs.get(index), index).equals(recipeKey)) {
                return true;
            }
        }
        return false;
    }

    private static String firstOutput(RecipeSpec spec) {
        if (!spec.outputs().isEmpty()) {
            return spec.outputs().get(0).item();
        }
        return spec.inputs().isEmpty() ? "" : spec.inputs().get(0).item();
    }

    private static String summarize(List<ItemCount> counts) {
        if (counts.isEmpty()) {
            return "—";
        }
        StringBuilder text = new StringBuilder();
        for (ItemCount count : counts) {
            if (text.length() > 0) {
                text.append("、");
            }
            text.append(itemLabel(count.item())).append("×").append(count.count());
        }
        return text.toString();
    }

    private static String itemLabel(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "?";
        }
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            return itemId;
        }
        Item item = BuiltInRegistries.ITEM.get(key);
        return item.getDescription().getString();
    }

    private static String positionText(BuildingRecord building) {
        return building.min().getX() + ", " + building.min().getY() + ", " + building.min().getZ();
    }

    private static BuildingRecord industryAt(ServerPlayer player) {
        BuildingRecord building = BuildingRegistry.INSTANCE.locate(player);
        if (building == null) {
            return null;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        if (meta == null) {
            return null;
        }
        return meta.type() == BuildingType.INDUSTRY || !meta.recipes().isEmpty() ? building : null;
    }
}
