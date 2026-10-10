package com.xy2407.kingdom.server.building;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.home.HomeBeds;
import com.xy2407.kingdom.common.network.building.ResidentActionPacket;
import com.xy2407.kingdom.common.network.building.ResidentListPacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 住宅住户管理：应答床位占用与可入住名册，执行入住 / 搬走 / 全部搬出。
 * 与养殖的 {@code RanchPanelService} 同构 —— 那边的"饲养员"在这里是"住户"。
 * 绑定关系落在 NPC 自己身上（{@code home_id} + {@code home_x/y/z}），床位由
 * {@link HomeBeds} 自动挑一张空的，一名市民只能占一张床。
 */
public final class ResidentService {

    private ResidentService() {
    }

    public static void respond(ServerPlayer player) {
        BuildingRecord building = residenceOf(player);
        if (building == null) {
            PacketDistributor.sendToPlayer(player, ResidentListPacket.outside());
            return;
        }
        PacketDistributor.sendToPlayer(player, snapshot(player, building));
    }

    public static void apply(ServerPlayer player, ResidentActionPacket packet) {
        BuildingRecord building = residenceOf(player);
        if (building == null) {
            respond(player);
            return;
        }
        switch (packet.action()) {
            case ResidentActionPacket.ACTION_TOGGLE_RESIDENT -> {
                UUID workerId = packet.workerIdOrNull();
                NpcData npc = workerId == null ? null : NpcManager.INSTANCE.getNpc(workerId);
                if (npc != null && !npc.isDead()) {
                    toggleResident(player, building, npc);
                }
            }
            case ResidentActionPacket.ACTION_CLEAR_RESIDENTS -> clearResidents(building);
            default -> {
            }
        }
        respond(player);
    }

    /** 同一个人再点一次就搬出去；已有住处的不动，免得把别人家的人挖过来。 */
    private static void toggleResident(ServerPlayer player, BuildingRecord building, NpcData npc) {
        String buildingId = building.id().toString();
        if (buildingId.equals(npc.getHomeId())) {
            HomeBeds.unbind(npc);
            return;
        }
        if (npc.getHomeId() != null && !npc.getHomeId().isBlank()) {
            player.displayClientMessage(Component.literal(npc.getName() + " 已住在别处"), true);
            return;
        }
        BlockPos bed = HomeBeds.assignFree(player.serverLevel(), building);
        if (bed == null) {
            // 住满了：床位数量就是居住上限
            player.displayClientMessage(Component.literal("床位已满，先加床或让住户搬走"), true);
            return;
        }
        npc.setHomeId(buildingId);
        npc.setHomeX(bed.getX());
        npc.setHomeY(bed.getY());
        npc.setHomeZ(bed.getZ());
    }

    private static void clearResidents(BuildingRecord building) {
        String buildingId = building.id().toString();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !buildingId.equals(npc.getHomeId())) {
                continue;
            }
            HomeBeds.unbind(npc);
        }
    }

    private static ResidentListPacket snapshot(ServerPlayer player, BuildingRecord building) {
        String buildingId = building.id().toString();
        List<UUID> residents = new ArrayList<>();
        List<ResidentListPacket.Worker> workers = new ArrayList<>();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead()) {
                continue;
            }
            if (buildingId.equals(npc.getHomeId())) {
                residents.add(npc.getId());
            }
            workers.add(new ResidentListPacket.Worker(npc.getId(), npc.getName(), npc.getSkin(),
                    workerNote(npc)));
        }
        return new ResidentListPacket(true, building.name(), building.level(),
                positionText(building), HomeBeds.beds(player.serverLevel(), building).size(),
                residents, workers);
    }

    /** 名册行尾的说明：只标"不能住进来"的原因，可入住的留空。 */
    private static String workerNote(NpcData npc) {
        if (npc.getBrain().isStarving()) {
            return "饥饿";
        }
        String homeId = npc.getHomeId();
        return homeId == null || homeId.isBlank() ? "" : "另有住处";
    }

    private static String positionText(BuildingRecord building) {
        return building.min().getX() + ", " + building.min().getY() + ", " + building.min().getZ();
    }

    /** 玩家脚下的住宅；不在住宅建筑里返回 null。 */
    private static BuildingRecord residenceOf(ServerPlayer player) {
        BuildingRecord building = BuildingRegistry.INSTANCE.locate(player);
        if (building == null) {
            return null;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        return meta != null && meta.type() == BuildingType.RESIDENTIAL ? building : null;
    }
}
