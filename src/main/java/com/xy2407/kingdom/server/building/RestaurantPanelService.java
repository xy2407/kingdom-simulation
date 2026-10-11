package com.xy2407.kingdom.server.building;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.building.ItemCount;
import com.xy2407.kingdom.common.building.ItemSpecs;
import com.xy2407.kingdom.common.building.MenuSpec;
import com.xy2407.kingdom.common.network.building.RestaurantMenuPacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 餐厅面板的数据来源：把当前餐厅的菜单卡片与在后厨上班的人数发回客户端。
 * 菜单本身来自蓝图 json（静态），"食材够不够"要现查仓库，所以每次都现算。
 */
public final class RestaurantPanelService {

    private RestaurantPanelService() {
    }

    public static void sendMenu(ServerPlayer player) {
        BuildingRecord building = restaurantOf(player);
        if (building == null) {
            return;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        if (meta == null) {
            return;
        }
        IItemHandler store = BuildingStorageStore.INSTANCE.handlerOf(player.getServer(),
                building.id(), meta.storageSlots());
        List<RestaurantMenuPacket.Dish> dishes = new ArrayList<>();
        for (MenuSpec dish : meta.menu()) {
            boolean ready = store != null && ItemSpecs.hasAll(store, dish.inputs());
            dishes.add(new RestaurantMenuPacket.Dish(dish.id(), dish.name(),
                    dish.output().item(), dish.output().count(), dish.price(),
                    describeInputs(dish.inputs()), ready));
        }
        PacketDistributor.sendToPlayer(player,
                new RestaurantMenuPacket(chefCountOf(building.id()), dishes));
    }

    private static String describeInputs(List<ItemCount> inputs) {
        StringBuilder builder = new StringBuilder();
        for (ItemCount input : inputs) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(shortName(input.item())).append('×').append(input.count());
        }
        return builder.toString();
    }

    private static String shortName(String spec) {
        if (spec == null || spec.isBlank()) {
            return "";
        }
        String text = spec.startsWith("#") ? spec.substring(1) : spec;
        int colon = text.indexOf(':');
        if (colon >= 0) {
            text = text.substring(colon + 1);
        }
        int slash = text.lastIndexOf('/');
        return slash >= 0 ? text.substring(slash + 1) : text;
    }

    private static int chefCountOf(UUID buildingId) {
        String id = buildingId.toString();
        int count = 0;
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (!npc.isDead() && id.equals(npc.getWorkplaceId())) {
                count++;
            }
        }
        return count;
    }

    private static BuildingRecord restaurantOf(ServerPlayer player) {
        BuildingRecord building = BuildingRegistry.INSTANCE.locate(player);
        if (building == null) {
            return null;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        return meta != null && meta.type() == BuildingType.COMMERCIAL && meta.isRestaurant() ? building : null;
    }
}
