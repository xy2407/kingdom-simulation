package com.xy2407.kingdom.server.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.farm.FarmPlot;
import com.xy2407.kingdom.common.mining.MiningPlot;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcMiningService;
import com.xy2407.kingdom.common.ranch.RanchPen;
import com.xy2407.kingdom.server.farm.FarmPlotManager;
import com.xy2407.kingdom.server.mining.MiningPlotManager;
import com.xy2407.kingdom.server.ranch.RanchPenManager;
import com.xy2407.kingdom.server.task.RegionTaskService;

/**
 * 岗位占用查询：判断市民是否已被其它建筑、农田、牧场或建筑工队雇用。
 */
public final class NpcOccupation {

    private NpcOccupation() {
    }

    public static String conflict(NpcData npc, UUID allowedBuildingId) {
        if (npc == null) {
            return null;
        }
        String workplace = npc.getWorkplaceId();
        if (workplace != null && !workplace.isBlank() && !sameBuilding(workplace, allowedBuildingId)) {
            return "已在其它建筑上班";
        }
        UUID id = npc.getId();
        if (RegionTaskService.INSTANCE.isEmployed(id)) {
            return "已在建筑工队";
        }
        for (FarmPlot plot : FarmPlotManager.INSTANCE.all()) {
            if (plot.workerIds().contains(id) && !matches(plot.buildingId(), allowedBuildingId)) {
                return "已在农田「" + plot.name() + "」打理";
            }
        }
        for (RanchPen pen : RanchPenManager.INSTANCE.all()) {
            if (pen.keeperIds().contains(id) && !matches(pen.buildingId(), allowedBuildingId)) {
                return "已在牧场当饲养员";
            }
        }
        return null;
    }

    public static boolean isEmployed(NpcData npc) {
        return conflict(npc, null) != null;
    }

    public static void detachWork(UUID npcId) {
        if (npcId == null) {
            return;
        }
        for (FarmPlot plot : FarmPlotManager.INSTANCE.all()) {
            if (!plot.workerIds().contains(npcId)) {
                continue;
            }
            List<UUID> workers = new ArrayList<>(plot.workerIds());
            workers.remove(npcId);
            FarmPlotManager.INSTANCE.update(plot.withWorkerIds(workers));
        }
        for (RanchPen pen : RanchPenManager.INSTANCE.all()) {
            if (!pen.keeperIds().contains(npcId)) {
                continue;
            }
            List<UUID> keepers = new ArrayList<>(pen.keeperIds());
            keepers.remove(npcId);
            RanchPenManager.INSTANCE.update(pen.withKeeperIds(keepers));
        }
        for (MiningPlot plot : MiningPlotManager.INSTANCE.all()) {
            if (!plot.workerIds().contains(npcId)) {
                continue;
            }
            List<UUID> workers = new ArrayList<>(plot.workerIds());
            workers.remove(npcId);
            MiningPlotManager.INSTANCE.update(plot.withWorkerIds(workers));
        }
        NpcMiningService.unlinkWorker(npcId);
    }

    public static List<UUID> aliveOnly(List<UUID> ids) {
        List<UUID> result = new ArrayList<>();
        if (ids == null) {
            return result;
        }
        for (UUID id : ids) {
            NpcData npc = NpcManager.INSTANCE.getNpc(id);
            if (npc != null && !npc.isDead()) {
                result.add(id);
            }
        }
        return result;
    }

    public static String note(NpcData npc, UUID allowedBuildingId) {
        String conflict = conflict(npc, allowedBuildingId);
        return conflict == null ? "" : conflict;
    }

    private static boolean sameBuilding(String workplaceId, UUID allowedBuildingId) {
        return allowedBuildingId != null && workplaceId.equals(allowedBuildingId.toString());
    }

    private static boolean matches(UUID buildingId, UUID allowedBuildingId) {
        return allowedBuildingId != null && allowedBuildingId.equals(buildingId);
    }
}
