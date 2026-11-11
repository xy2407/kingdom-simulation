package com.xy2407.kingdom.server.mining;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.mining.MiningPlot;
import com.xy2407.kingdom.common.network.mining.MiningPanelActionPacket;
import com.xy2407.kingdom.common.network.mining.MiningPanelStatePacket;
import com.xy2407.kingdom.common.npc.*;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.common.employment.EmploymentKind;
import com.xy2407.kingdom.common.employment.EmploymentTarget;
import com.xy2407.kingdom.server.employment.EmploymentService;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 矿坑面板服务端，回推各矿坑状态并处理派工分配、暂停恢复与删除
 */
public final class MiningPanelService {

    private MiningPanelService() {
    }

    public static void respond(ServerPlayer player) {
        BuildingRecord building = miningAt(player);
        if (building == null) {
            building = MiningAreaService.nearestMiningBuilding(player.serverLevel(), player.blockPosition());
        }
        if (building == null) {
            PacketDistributor.sendToPlayer(player, MiningPanelStatePacket.outside());
            return;
        }
        List<MiningPanelStatePacket.Mine> mines = new ArrayList<>();
        for (MiningPlot plot : MiningPlotManager.INSTANCE.allOf(building.id())) {
            mines.add(new MiningPanelStatePacket.Mine(plot.id(), plot.name(),
                    plot.min().getX(), plot.min().getZ(), plot.max().getX(), plot.max().getZ(),
                    plot.surfaceY(), plot.currentY(), plot.cursor(), plot.layerSize(),
                    plot.status(), EmploymentService.aliveOnly(plot.workerIds())));
        }
        PacketDistributor.sendToPlayer(player, new MiningPanelStatePacket(true,
                building.name(), List.copyOf(mines), roster(building.id())));
    }

    public static void apply(ServerPlayer player, MiningPanelActionPacket packet) {
        BuildingRecord building = miningAt(player);
        if (building == null) {
            respond(player);
            return;
        }
        MiningPlot plot = packet.plotIdOrNull() == null
                ? null : MiningPlotManager.INSTANCE.byId(packet.plotIdOrNull());
        if (plot == null || !plot.buildingId().equals(building.id())) {
            player.displayClientMessage(Component.literal("这座建筑还没有矿坑"), true);
            respond(player);
            return;
        }
        switch (packet.action()) {
            case MiningPanelActionPacket.ACTION_TOGGLE_WORKER -> toggleWorker(player, plot,
                    packet.workerIdOrNull());
            case MiningPanelActionPacket.ACTION_PAUSE ->
                    MiningPlotManager.INSTANCE.update(plot.withStatus(MiningPlot.STATUS_PAUSED));
            case MiningPanelActionPacket.ACTION_RESUME ->
                    MiningPlotManager.INSTANCE.update(plot.withStatus(MiningPlot.STATUS_RUNNING));
            case MiningPanelActionPacket.ACTION_DELETE -> MiningPlotManager.INSTANCE.remove(plot.id());
            default -> {
            }
        }
        respond(player);
    }

    private static void toggleWorker(ServerPlayer player, MiningPlot plot, UUID workerId) {
        if (workerId == null) {
            for (UUID id : new ArrayList<>(plot.workerIds())) {
                attach(plot, id, false);
            }
            return;
        }
        NpcData npc = NpcManager.INSTANCE.getNpc(workerId);
        if (npc == null) {
            player.displayClientMessage(Component.literal("找不到这名市民"), true);
            return;
        }
        if (plot.workerIds().contains(workerId)) {
            attach(plot, workerId, false);
            return;
        }
        if (!plot.buildingId().toString().equals(npc.getWorkplaceId())) {
            player.displayClientMessage(
                    Component.literal("先在这座建筑的用工面板把他雇进来，再来分配"), true);
            return;
        }
        for (MiningPlot other : MiningPlotManager.INSTANCE.allOf(plot.buildingId())) {
            if (!other.id().equals(plot.id()) && other.workerIds().contains(workerId)) {
                List<UUID> workers = new ArrayList<>(other.workerIds());
                workers.remove(workerId);
                MiningPlotManager.INSTANCE.update(other.withWorkerIds(workers));
            }
        }
        attach(plot, workerId, true);
    }

    private static void attach(MiningPlot plot, UUID workerId, boolean in) {
        List<UUID> workers = new ArrayList<>(plot.workerIds());
        if (in && !workers.contains(workerId)) {
            workers.add(workerId);
            NpcMiningService.linkWorker(workerId, plot.id());
        } else if (!in && workers.remove(workerId)) {
            NpcMiningService.unlinkWorker(workerId);
        } else {
            return;
        }
        MiningPlot current = MiningPlotManager.INSTANCE.byId(plot.id());
        MiningPlotManager.INSTANCE.update((current == null ? plot : current).withWorkerIds(workers));
    }

    private static List<MiningPanelStatePacket.WorkerOption> roster(UUID buildingId) {
        List<MiningPanelStatePacket.WorkerOption> options = new ArrayList<>();
        String owner = buildingId == null ? "" : buildingId.toString();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !owner.equals(npc.getWorkplaceId())) {
                continue;
            }
            options.add(new MiningPanelStatePacket.WorkerOption(npc.getId(), npc.getName(),
                    npc.getSkin(), workerNote(npc, buildingId)));
        }
        return options;
    }

    private static String workerNote(NpcData npc, UUID buildingId) {
        String conflict = EmploymentService.INSTANCE.conflictAt(npc, buildingId);
        if (!conflict.isEmpty()) {
            return conflict;
        }
        if (npc.getBrain().isStarving()) {
            return "饥饿";
        }
        return NpcSchedule.subState(npc.getShift(), npc.getDayTime()) == NpcSubState.WORKING
                ? "" : "下班";
    }

    private static BuildingRecord miningAt(ServerPlayer player) {
        BuildingRecord hit = BuildingRegistry.INSTANCE.locate(player);
        if (hit == null) {
            return null;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(hit);
        if (meta == null || !meta.isWork(BuildingWork.MINE)) {
            return null;
        }
        return hit;
    }
}
