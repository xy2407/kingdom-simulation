package com.xy2407.kingdom.server.shop;

import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.network.shop.ShopPanelStatePacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.server.building.BuildingRegistry;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 商铺面板的数据来源：营业开关、营业员与开业时登记的货架数量
 */
public final class ShopPanelService {

    private ShopPanelService() {
    }

    public static void respond(ServerPlayer player) {
        BuildingRecord building = shopOf(player);
        if (building == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, snapshot(building));
    }

    public static void apply(ServerPlayer player, boolean open) {
        BuildingRecord building = shopOf(player);
        if (building == null) {
            return;
        }
        if (open && workerName(building.id()).isBlank()) {
            player.displayClientMessage(Component.literal(
                    "开业失败：需要先雇佣 1 名营业员"), true);
            respond(player);
            return;
        }
        int shelves = ShopStore.INSTANCE.setOpen(player.getServer(), building, open);
        if (open && shelves < 0) {
            player.displayClientMessage(Component.literal(
                    "开业失败：建筑内至少需要 " + ShopStore.MIN_SHELVES + " 个商品展示架"), true);
            respond(player);
            return;
        }
        player.displayClientMessage(Component.literal(open
                ? "商铺已开业：登记 " + shelves + " 个货架"
                : "商铺已打烊"), true);
        respond(player);
    }

    private static ShopPanelStatePacket snapshot(BuildingRecord building) {
        return new ShopPanelStatePacket(building.name(), building.level(),
                ShopStore.INSTANCE.isOpen(building.id()),
                workerName(building.id()),
                ShopStore.INSTANCE.shelvesOf(building.id()).size());
    }

    private static String workerName(UUID buildingId) {
        String id = buildingId.toString();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (!npc.isDead() && id.equals(npc.getWorkplaceId())) {
                return npc.getName();
            }
        }
        return "";
    }

    private static BuildingRecord shopOf(ServerPlayer player) {
        BuildingRecord building = BuildingRegistry.INSTANCE.locate(player);
        if (building == null) {
            return null;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        return meta != null && meta.isWork(BuildingWork.SHOP) ? building : null;
    }
}
