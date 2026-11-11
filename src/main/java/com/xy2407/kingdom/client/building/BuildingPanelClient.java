package com.xy2407.kingdom.client.building;

import com.xy2407.kingdom.client.farm.FarmPanelScreen;
import com.xy2407.kingdom.client.industry.IndustryPanelScreen;
import com.xy2407.kingdom.client.ranch.RanchPanelScreen;
import com.xy2407.kingdom.common.network.building.BuildingPanelRequestPacket;
import com.xy2407.kingdom.common.network.building.BuildingPanelStatePacket;
import com.xy2407.kingdom.common.network.building.BuildingWorkChoicePacket;
import com.xy2407.kingdom.common.network.building.ResidentListPacket;
import com.xy2407.kingdom.common.network.building.RestaurantMenuPacket;
import com.xy2407.kingdom.common.network.shop.ShopPanelStatePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 建筑操作界面的客户端入口：按 Tab 请服务端判定所处建筑并分发对应面板。
 */
@OnlyIn(Dist.CLIENT)
public final class BuildingPanelClient {

    private BuildingPanelClient() {
    }

    public static void requestAtPlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return;
        }
        PacketDistributor.sendToServer(new BuildingPanelRequestPacket());
    }

    public static void accept(BuildingPanelStatePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (packet.hasPage()) {            if (FarmPanelScreen.PAGE.equals(packet.page())) {
                minecraft.setScreen(new FarmPanelScreen());
                return;
            }
            if (RanchPanelScreen.PAGE.equals(packet.page())) {
                minecraft.setScreen(new RanchPanelScreen());
                return;
            }
            if (IndustryPanelScreen.PAGE.equals(packet.page())) {
                minecraft.setScreen(new IndustryPanelScreen());
                return;
            }
            minecraft.setScreen(new BuildingPanelScreen(packet.page(), packet.name(), packet.level(),
                    packet.pos()));
            return;
        }
        String message = packet.inside()
                ? String.format("%s  Lv.%d · 建筑操作界面尚未完成", packet.name(), packet.level())
                : "这里没有已完工的建筑";
        minecraft.player.displayClientMessage(Component.literal(message), true);
    }

    public static void acceptWorkChoice(BuildingWorkChoicePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || packet.works().isEmpty()) {
            return;
        }
        minecraft.setScreen(new BuildingWorkSelectScreen(packet.buildingId(), packet.category(),
                packet.works()));
    }

    public static void acceptResidents(ResidentListPacket packet) {
        if (Minecraft.getInstance().screen instanceof BuildingPanelScreen screen) {
            screen.applyResidents(packet);
        }
    }

    public static void acceptRestaurantMenu(RestaurantMenuPacket packet) {
        if (Minecraft.getInstance().screen instanceof BuildingPanelScreen screen) {
            screen.applyRestaurantMenu(packet);
        }
    }

    public static void acceptShop(ShopPanelStatePacket packet) {
        if (Minecraft.getInstance().screen instanceof BuildingPanelScreen screen) {
            screen.applyShop(packet);
        }
    }
}
