package com.xy2407.kingdom.server.building;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.building.ItemCount;
import com.xy2407.kingdom.common.building.ItemSpecs;
import com.xy2407.kingdom.common.building.MenuSpec;
import com.xy2407.kingdom.common.network.building.RestaurantMenuPacket;
import com.xy2407.kingdom.common.npc.NpcRestaurantService;
import com.xy2407.kingdom.common.restaurant.RestaurantFurniture;
import com.xy2407.kingdom.server.restaurant.RestaurantSeatStore;

import net.minecraft.network.chat.Component;
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

    public static void reopen(ServerPlayer player) {
        BuildingRecord building = restaurantOf(player);
        if (building == null) {
            return;
        }
        if (!RestaurantFurniture.enabled()) {
            player.displayClientMessage(Component.literal(
                    "餐厅家具方块 id 尚未配置，暂时无法布置座位"), true);
            return;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        if (meta == null || meta.menu().isEmpty()) {
            player.displayClientMessage(Component.literal(
                    "开业失败：菜单至少要配置 1 道菜品"), true);
            return;
        }
        NpcRestaurantService.Roles roles = NpcRestaurantService.rolesOf(building.id());
        if (!roles.open()) {
            player.displayClientMessage(Component.literal(
                    "开业失败：需要 1 名厨师和至少 1 名服务员"), true);
            return;
        }
        int seats = RestaurantSeatStore.INSTANCE.rescan(player.getServer(), building);
        if (seats <= 0) {
            player.displayClientMessage(Component.literal(
                    "开业失败：范围内没有识别到椅子与桌子"), true);
            return;
        }
        if (!RestaurantSeatStore.INSTANCE.hasTableSeat(building.id())) {
            player.displayClientMessage(Component.literal(
                    "开业失败：至少需要 1 把椅子紧邻 1 张桌子"), true);
            return;
        }
        player.displayClientMessage(Component.literal(
                "餐厅已开业：登记 " + seats + " 个座位"), true);
        sendMenu(player);
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
        NpcRestaurantService.Roles roles = NpcRestaurantService.rolesOf(building.id());
        PacketDistributor.sendToPlayer(player,
                new RestaurantMenuPacket(roles.chefId() == null ? 0 : 1, roles.waiters(), dishes));
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

    private static BuildingRecord restaurantOf(ServerPlayer player) {
        BuildingRecord building = BuildingRegistry.INSTANCE.locate(player);
        if (building == null) {
            return null;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        return meta != null && meta.isWork(BuildingWork.RESTAURANT) ? building : null;
    }
}
