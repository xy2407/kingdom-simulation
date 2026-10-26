package com.xy2407.kingdom.server.building;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.employment.EmploymentKind;
import com.xy2407.kingdom.common.home.HomeBeds;
import com.xy2407.kingdom.common.employment.EmploymentResult;
import com.xy2407.kingdom.common.employment.EmploymentTarget;
import com.xy2407.kingdom.common.network.building.ResidentActionPacket;
import com.xy2407.kingdom.common.network.building.ResidentListPacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.server.employment.EmploymentService;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 住宅住户管理：应答床位名册，执行入住、搬走与全部搬出
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

    private static void toggleResident(ServerPlayer player, BuildingRecord building, NpcData npc) {
        if (building.id().toString().equals(npc.getHomeId())) {
            EmploymentService.INSTANCE.dismissAssignment(
                    player.server, npc.getId(), EmploymentKind.RESIDENCE);
            return;
        }
        EmploymentResult result = EmploymentService.INSTANCE.hire(player.server, npc.getId(),
                EmploymentTarget.residence(building.id()));
        if (!result.success()) {
            player.displayClientMessage(Component.literal(result.reason()), true);
        }
    }

    private static void clearResidents(BuildingRecord building) {
        String buildingId = building.id().toString();
        for (NpcData npc : new ArrayList<>(NpcManager.INSTANCE.allNpcs())) {
            if (npc.isDead() || !buildingId.equals(npc.getHomeId())) {
                continue;
            }
            EmploymentService.INSTANCE.dismissAssignment(
                    null, npc.getId(), EmploymentKind.RESIDENCE);
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

    private static BuildingRecord residenceOf(ServerPlayer player) {
        BuildingRecord building = BuildingRegistry.INSTANCE.locate(player);
        if (building == null) {
            return null;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        return meta != null && meta.isType(BuildingType.RESIDENTIAL) ? building : null;
    }
}
