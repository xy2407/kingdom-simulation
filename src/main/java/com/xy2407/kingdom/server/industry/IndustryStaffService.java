package com.xy2407.kingdom.server.industry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.area.PlotArea;
import com.xy2407.kingdom.common.area.PlotAreaKind;
import com.xy2407.kingdom.common.building.BuildingLevelRules;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.employment.EmploymentKind;
import com.xy2407.kingdom.common.employment.EmploymentResult;
import com.xy2407.kingdom.common.employment.EmploymentTarget;
import com.xy2407.kingdom.common.mining.MiningPlot;
import com.xy2407.kingdom.common.network.industry.IndustryStaffActionPacket;
import com.xy2407.kingdom.common.network.industry.IndustryStaffStatePacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcMiningService;
import com.xy2407.kingdom.common.npc.NpcRole;
import com.xy2407.kingdom.common.npc.NpcShift;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.employment.EmploymentService;
import com.xy2407.kingdom.server.area.PlotAreaManager;
import com.xy2407.kingdom.server.lumber.NpcLumberService;
import com.xy2407.kingdom.server.mining.MiningPlotManager;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 工业用工服务，先在建筑一层雇人，再把工人分到具体矿坑或林地作业点
 */
public final class IndustryStaffService {

    private IndustryStaffService() {
    }

    public static void open(ServerPlayer player) {
        send(player, locate(player));
    }

    private static BuildingRecord locate(ServerPlayer player) {
        BuildingRecord building = BuildingRegistry.INSTANCE.locate(player);
        if (building == null) {
            return null;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        if (meta == null || meta.work() == null) {
            return null;
        }
        BuildingWork work = meta.work();
        return work == BuildingWork.MINE || work == BuildingWork.LUMBER ? building : null;
    }

    static void send(ServerPlayer player, BuildingRecord building) {
        if (building == null) {
            PacketDistributor.sendToPlayer(player, IndustryStaffStatePacket.outside());
            return;
        }
        PacketDistributor.sendToPlayer(player, snapshot(player, building));
    }

    private static IndustryStaffStatePacket snapshot(ServerPlayer player, BuildingRecord building) {
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        boolean lumber = meta != null && meta.isWork(BuildingWork.LUMBER);
        List<MiningPlot> plots = lumber ? List.of() : MiningPlotManager.INSTANCE.allOf(building.id());
        List<IndustryStaffStatePacket.Plot> plotEntries = new ArrayList<>();
        if (lumber) {
            List<PlotArea> areas = PlotAreaManager.INSTANCE.allOf(building.id(), PlotAreaKind.LUMBER);
            for (PlotArea area : areas) {
                plotEntries.add(new IndustryStaffStatePacket.Plot(area.id(),
                        "林地 " + area.size() + " 格", area.workerIds().size(), "作业区"));
            }
        }
        for (MiningPlot plot : plots) {
            plotEntries.add(new IndustryStaffStatePacket.Plot(plot.id(), plot.name(),
                    plot.workerIds().size(), statusText(plot)));
        }
        List<IndustryStaffStatePacket.Worker> staff = new ArrayList<>();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !isStaffOf(npc, building)) {
                continue;
            }
            IndustryStaffStatePacket.Plot assigned = lumber
                    ? areaOf(building, npc.getId()) : mineOf(plots, npc.getId());
            staff.add(new IndustryStaffStatePacket.Worker(npc.getId(), npc.getName(), npc.getSkin(),
                    noteOf(npc), assigned == null ? null : assigned.id(),
                    assigned == null ? "" : assigned.name()));
        }
        List<IndustryStaffStatePacket.Worker> candidates = new ArrayList<>();
        String lordId = player.getUUID().toString();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || isStaffOf(npc, building) || npc.getRole() == NpcRole.TOURIST) {
                continue;
            }
            if (!lordId.equals(npc.getOwnerId())) {
                continue;
            }
            candidates.add(new IndustryStaffStatePacket.Worker(npc.getId(), npc.getName(),
                    npc.getSkin(), EmploymentService.INSTANCE.conflictAt(npc, null), null, ""));
        }
        String name = BuildingRegistry.INSTANCE.metaOf(building).name();
        return new IndustryStaffStatePacket(true, building.id(),
                name == null || name.isBlank() ? building.name() : name,
                building.level(), BuildingLevelRules.industryWorkers(building.level()),
                staff, plotEntries, candidates);
    }

    private static String statusText(MiningPlot plot) {
        return switch (plot.status()) {
            case MiningPlot.STATUS_RUNNING -> plot.hasArea() ? "开采中" : "待框选范围";
            case MiningPlot.STATUS_PAUSED -> "已暂停";
            case MiningPlot.STATUS_DONE -> "已挖完";
            default -> "待开工";
        };
    }

    private static String noteOf(NpcData npc) {
        var brain = npc.getBrain();
        if (brain.isStarving()) {
            return "饥饿";
        }
        if (npc.getWorkplaceId() == null || npc.getWorkplaceId().isBlank()) {
            return "待分配";
        }
        return "";
    }

    private static boolean isStaffOf(NpcData npc, BuildingRecord building) {
        return building.id().toString().equals(npc.getWorkplaceId());
    }

    private static IndustryStaffStatePacket.Plot mineOf(List<MiningPlot> plots, UUID npcId) {
        for (MiningPlot plot : plots) {
            if (plot.workerIds().contains(npcId)) {
                return new IndustryStaffStatePacket.Plot(plot.id(), plot.name(),
                        plot.workerIds().size(), "");
            }
        }
        return null;
    }

    private static IndustryStaffStatePacket.Plot areaOf(BuildingRecord building, UUID npcId) {
        for (PlotArea area : PlotAreaManager.INSTANCE.allOf(building.id(), PlotAreaKind.LUMBER)) {
            if (area.workerIds().contains(npcId)) {
                return new IndustryStaffStatePacket.Plot(area.id(),
                        "林地 " + area.size() + " 格", area.workerIds().size(), "");
            }
        }
        return null;
    }

    public static void apply(ServerPlayer player, IndustryStaffActionPacket packet) {
        BuildingRecord building = locate(player);
        if (building == null) {
            PacketDistributor.sendToPlayer(player, IndustryStaffStatePacket.outside());
            return;
        }
        UUID npcId = packet.npcIdOrNull();
        if (npcId == null) {
            send(player, building);
            return;
        }
        switch (packet.action()) {
            case IndustryStaffActionPacket.ACTION_HIRE -> hire(player, building, npcId);
            case IndustryStaffActionPacket.ACTION_FIRE -> fire(player, building, npcId);
            case IndustryStaffActionPacket.ACTION_ASSIGN ->
                    assign(player, building, npcId, packet.plotIdOrNull());
            case IndustryStaffActionPacket.ACTION_UNASSIGN -> unassign(player, building, npcId);
            default -> {
            }
        }
        send(player, building);
    }

    private static void hire(ServerPlayer player, BuildingRecord building, UUID npcId) {
        EmploymentResult result = EmploymentService.INSTANCE.hire(player.server, npcId,
                EmploymentTarget.building(building.id(), "", NpcShift.DAY_SHIFT));
        if (!result.success()) {
            NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
            String who = npc == null ? "这名市民" : npc.getName();
            player.displayClientMessage(Component.literal(who + " " + result.reason()), true);
        }
    }

    private static void fire(ServerPlayer player, BuildingRecord building, UUID npcId) {
        if (!isStaffOf(NpcManager.INSTANCE.getNpc(npcId), building)) {
            return;
        }
        removeFromPlots(building, npcId);
        removeFromAreas(building, npcId);
        EmploymentService.INSTANCE.dismissAssignment(player.server, npcId, EmploymentKind.MINING);
        EmploymentService.INSTANCE.dismissAssignment(player.server, npcId, EmploymentKind.LUMBER);
        EmploymentService.INSTANCE.dismissAssignment(player.server, npcId, EmploymentKind.BUILDING);
    }

    private static void assign(ServerPlayer player, BuildingRecord building, UUID npcId, UUID plotId) {
        NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
        if (npc == null || npc.isDead() || plotId == null) {
            return;
        }
        if (!isStaffOf(npc, building)) {
            player.displayClientMessage(Component.literal("先在这座建筑里雇他，再分配作业点"), true);
            return;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        if (meta != null && meta.isWork(BuildingWork.LUMBER)) {
            assignArea(building, npcId, plotId);
            return;
        }
        assignMine(building, npcId, plotId);
    }

    private static void assignMine(BuildingRecord building, UUID npcId, UUID plotId) {
        MiningPlot target = null;
        for (MiningPlot plot : MiningPlotManager.INSTANCE.allOf(building.id())) {
            if (plot.id().equals(plotId)) {
                target = plot;
                break;
            }
        }
        if (target == null) {
            return;
        }
        removeFromPlots(building, npcId);
        removeFromAreas(building, npcId);
        List<UUID> workers = new ArrayList<>(target.workerIds());
        workers.add(npcId);
        MiningPlotManager.INSTANCE.update(target.withWorkerIds(workers));
        NpcMiningService.linkWorker(npcId, target.id());
    }

    private static void assignArea(BuildingRecord building, UUID npcId, UUID areaId) {
        PlotArea target = PlotAreaManager.INSTANCE.byId(areaId);
        if (target == null || !target.buildingId().equals(building.id())) {
            return;
        }
        removeFromAreas(building, npcId);
        removeFromPlots(building, npcId);
        NpcMiningService.unlinkWorker(npcId);
        List<UUID> workers = new ArrayList<>(target.workerIds());
        if (!workers.contains(npcId)) {
            workers.add(npcId);
        }
        PlotAreaManager.INSTANCE.update(target.withWorkerIds(workers));
    }

    private static void unassign(ServerPlayer player, BuildingRecord building, UUID npcId) {
        if (!isStaffOf(NpcManager.INSTANCE.getNpc(npcId), building)) {
            return;
        }
        removeFromPlots(building, npcId);
        removeFromAreas(building, npcId);
        NpcMiningService.unlinkWorker(npcId);
    }

    private static void removeFromAreas(BuildingRecord building, UUID npcId) {
        boolean touched = false;
        for (PlotArea area : PlotAreaManager.INSTANCE.allOf(building.id(), PlotAreaKind.LUMBER)) {
            if (!area.workerIds().contains(npcId)) {
                continue;
            }
            List<UUID> workers = new ArrayList<>(area.workerIds());
            workers.remove(npcId);
            PlotAreaManager.INSTANCE.update(area.withWorkerIds(workers));
            touched = true;
        }
        if (touched) {
            NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
            if (npc != null) {
                NpcLumberService.clearQueue(NpcManager.INSTANCE.liveEntity(npc));
            }
        }
    }

    private static void removeFromPlots(BuildingRecord building, UUID npcId) {
        for (MiningPlot plot : MiningPlotManager.INSTANCE.allOf(building.id())) {
            if (!plot.workerIds().contains(npcId)) {
                continue;
            }
            List<UUID> workers = new ArrayList<>(plot.workerIds());
            workers.remove(npcId);
            MiningPlotManager.INSTANCE.update(plot.withWorkerIds(workers));
        }
    }
}
