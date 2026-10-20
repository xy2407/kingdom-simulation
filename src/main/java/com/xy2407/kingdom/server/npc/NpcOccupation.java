package com.xy2407.kingdom.server.npc;

import java.util.UUID;

import com.xy2407.kingdom.common.farm.FarmPlot;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.ranch.RanchPen;
import com.xy2407.kingdom.server.farm.FarmPlotManager;
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
