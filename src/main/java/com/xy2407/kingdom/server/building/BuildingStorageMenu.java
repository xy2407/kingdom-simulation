package com.xy2407.kingdom.server.building;

import com.sighs.apricityui.network.handler.PendingMenu;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.network.building.BuildingStorageOpenPacket;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 打开建筑仓库界面：先灌数据再开屏；格数取蓝图声明的 storage_slots，上限只做防溢出保护。
 */
public final class BuildingStorageMenu {

    public static final String PAGE = "buildings/storage.html";

    /** 安全上限：防止蓝图写错把界面和存档撑爆，正常建筑用不到这么大 */
    public static final int MAX_SLOTS = 512;

    private BuildingStorageMenu() {
    }

    public static void openFor(ServerPlayer player) {
        if (player == null) {
            return;
        }
        BuildingRecord building = BuildingRegistry.INSTANCE.locate(player);
        if (building == null) {
            player.displayClientMessage(Component.literal("这里没有已完工的建筑"), true);
            return;
        }
        int capacity = BuildingRegistry.INSTANCE.storageSlotsOf(building);
        if (capacity <= 0) {
            player.displayClientMessage(Component.literal(
                    building.name() + " 没有仓库：蓝图 json 里没写 storage_slots"), true);
            return;
        }
        open(player, building, capacity);
    }

    public static boolean open(ServerPlayer player, BuildingRecord building, int capacity) {
        if (player == null || building == null) {
            return false;
        }
        int usable = Math.min(capacity, MAX_SLOTS);
        if (usable <= 0) {
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        BuildingStorageStore.INSTANCE.handlerOf(server, building.id(), usable);
        PacketDistributor.sendToPlayer(player,
                new BuildingStorageOpenPacket(building.name(), usable, usable));
        new PendingMenu(player, PAGE).bind(binding -> binding
                .saveddata(BuildingStorageStore.dataName(building.id()), usable)
                .player());
        return true;
    }
}
