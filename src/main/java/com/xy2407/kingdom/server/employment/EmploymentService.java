package com.xy2407.kingdom.server.employment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.building.BuildingLevelRules;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.employment.Employment;
import com.xy2407.kingdom.common.employment.EmploymentKind;
import com.xy2407.kingdom.common.employment.EmploymentResult;
import com.xy2407.kingdom.common.employment.EmploymentTarget;
import com.xy2407.kingdom.common.farm.FarmPlot;
import com.xy2407.kingdom.common.home.HomeBeds;
import com.xy2407.kingdom.common.mining.MiningPlot;
import com.xy2407.kingdom.common.npc.NpcBrain;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcMiningService;
import com.xy2407.kingdom.common.npc.NpcRole;
import com.xy2407.kingdom.common.npc.NpcShopService;
import com.xy2407.kingdom.common.ranch.RanchPen;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.military.SoldierService;
import com.xy2407.kingdom.server.farm.FarmPlotManager;
import com.xy2407.kingdom.server.mining.MiningPlotManager;
import com.xy2407.kingdom.server.ranch.RanchPenManager;
import com.xy2407.kingdom.server.task.RegionTaskService;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 雇佣唯一入口：写入与解除各岗位载体，统一冲突判断与控制位开关
 */
public final class EmploymentService {

    public static final EmploymentService INSTANCE = new EmploymentService();

    private EmploymentService() {
    }

    public EmploymentResult hire(MinecraftServer server, UUID npcId, EmploymentTarget target) {
        if (npcId == null || target == null) {
            return EmploymentResult.fail("雇佣目标无效");
        }
        NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
        if (npc == null || npc.isDead()) {
            return EmploymentResult.fail("市民不存在");
        }
        if (npc.getRole() == NpcRole.TOURIST) {
            return EmploymentResult.fail("游客不能雇佣");
        }
        String conflict = conflict(npc, target);
        if (conflict != null) {
            return EmploymentResult.fail(conflict);
        }
        int capacity = capacity(server, target);
        if (capacity > 0 && !alreadyInTarget(npcId, target) && occupants(target) >= capacity) {
            return EmploymentResult.fail("岗位已满");
        }
        if (isWorkKind(target.kind())) {
            for (EmploymentKind kind : EmploymentKind.values()) {
                if (isWorkKind(kind)) {
                    dismissAssignment(server, npcId, kind);
                }
            }
        } else {
            dismissAssignment(server, npcId, EmploymentKind.RESIDENCE);
        }
        switch (target.kind()) {
            case BUILDING -> attachBuilding(npc, target);
            case LUMBER -> attachLumber(npc, target);
            case FARM -> {
                FarmPlot plot = farmPlotOf(target);
                if (plot == null) {
                    return EmploymentResult.fail("农田不存在");
                }
                if (plot.workerIds().size() >= capacityOf(plot.slots(), plot.workerIds())) {
                    return EmploymentResult.fail("农田岗位已满");
                }
                List<UUID> workers = new ArrayList<>(plot.workerIds());
                workers.add(npcId);
                FarmPlotManager.INSTANCE.update(plot.withWorkerIds(workers));
                npc.setWorkplaceId(target.buildingId() == null ? null : target.buildingId().toString());
            }
            case RANCH -> {
                RanchPen pen = ranchPenOf(target);
                if (pen == null) {
                    return EmploymentResult.fail("牧场不存在");
                }
                List<UUID> keepers = new ArrayList<>(pen.keeperIds());
                if (!keepers.contains(npcId)) {
                    keepers.add(npcId);
                }
                RanchPenManager.INSTANCE.update(pen.withKeeperIds(keepers));
                npc.setWorkplaceId(target.buildingId() == null ? null : target.buildingId().toString());
            }
            case MINING -> {
                MiningPlot plot = miningPlotOf(target);
                if (plot == null) {
                    return EmploymentResult.fail("矿场不存在");
                }
                List<UUID> workers = new ArrayList<>(plot.workerIds());
                if (!workers.contains(npcId)) {
                    workers.add(npcId);
                }
                MiningPlotManager.INSTANCE.update(plot.withWorkerIds(workers));
                NpcMiningService.linkWorker(npcId, plot.id());
                npc.setWorkplaceId(target.buildingId() == null ? null : target.buildingId().toString());
            }
            case RESIDENCE -> {
                if (!attachResidence(server, npc, target)) {
                    return EmploymentResult.fail("住宅没有空床位");
                }
            }
            case BUILDER -> RegionTaskService.INSTANCE.hireBySystem(npcId);
            default -> {
                return EmploymentResult.fail("不支持的岗位");
            }
        }
        applyControl(npc, target);
        return EmploymentResult.ok();
    }

    public void dismiss(MinecraftServer server, UUID npcId) {
        if (npcId == null) {
            return;
        }
        for (EmploymentKind kind : EmploymentKind.values()) {
            dismissAssignment(server, npcId, kind);
        }
        NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
        if (npc != null) {
            clearControls(npc.getBrain());
        }
    }

    public void dismissAssignment(MinecraftServer server, UUID npcId, EmploymentKind kind) {
        if (npcId == null || kind == null) {
            return;
        }
        NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
        switch (kind) {
            case FARM -> {
                for (FarmPlot plot : FarmPlotManager.INSTANCE.all()) {
                    if (!plot.workerIds().contains(npcId)) {
                        continue;
                    }
                    List<UUID> workers = new ArrayList<>(plot.workerIds());
                    workers.remove(npcId);
                    FarmPlotManager.INSTANCE.update(plot.withWorkerIds(workers));
                }
            }
            case RANCH -> {
                for (RanchPen pen : RanchPenManager.INSTANCE.all()) {
                    if (!pen.keeperIds().contains(npcId)) {
                        continue;
                    }
                    List<UUID> keepers = new ArrayList<>(pen.keeperIds());
                    keepers.remove(npcId);
                    RanchPenManager.INSTANCE.update(pen.withKeeperIds(keepers));
                }
            }
            case MINING -> {
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
            case BUILDER -> RegionTaskService.INSTANCE.dismissBySystem(npcId);
            case LUMBER, BUILDING -> {
                if (npc != null) {
                    npc.setWorkplaceId(null);
                    npc.setWorkRecipeId("");
                }
            }
            case RESIDENCE -> {
                if (npc != null) {
                    HomeBeds.unbind(npc);
                }
            }
            default -> {
            }
        }
        if (npc != null) {
            NpcBrain brain = npc.getBrain();
            switch (kind) {
                case FARM -> brain.setFarmControlled(false);
                case RANCH -> brain.setRanchControlled(false);
                case MINING -> brain.setMiningControlled(false);
                case LUMBER -> brain.setLumberControlled(false);
                case BUILDER -> brain.setConstructionControlled(false);
                case BUILDING -> {
                    brain.setIndustryControlled(false);
                    brain.setRestaurantControlled(false);
                    brain.setShopControlled(false);
                    brain.setForgeControlled(false);
                    brain.setSoldierControlled(false);
                    if (NpcShopService.JOB_NAME.equals(npc.getJob())) {
                        npc.setJob("");
                    }
                    if (SoldierService.isSoldier(npc)) {
                        SoldierService.clearSoldier(npc);
                    }
                }
                default -> {
                }
            }
        }
    }

    public String conflict(NpcData npc, EmploymentTarget target) {
        if (npc == null) {
            return "市民不存在";
        }
        if (!isWorkKind(target.kind())) {
            return null;
        }
        EmploymentTarget current = current(npc);
        if (current == null || !isWorkKind(current.kind())) {
            return null;
        }
        if (target.sameWorkplace(current)) {
            return null;
        }
        return "已在" + current.describe() + "任职";
    }

    private static boolean isWorkKind(EmploymentKind kind) {
        return kind != EmploymentKind.RESIDENCE;
    }

    public String conflictAt(NpcData npc, UUID allowedBuildingId) {
        if (npc == null) {
            return null;
        }
        EmploymentTarget current = current(npc);
        if (current == null) {
            return null;
        }
        if (allowedBuildingId != null && allowedBuildingId.equals(current.buildingId())) {
            return null;
        }
        return "已在" + current.describe() + "任职";
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

    public EmploymentTarget current(NpcData npc) {
        if (npc == null) {
            return null;
        }
        UUID id = npc.getId();
        for (FarmPlot plot : FarmPlotManager.INSTANCE.all()) {
            if (plot.workerIds().contains(id)) {
                return EmploymentTarget.farm(plot.buildingId(), plot.id());
            }
        }
        for (RanchPen pen : RanchPenManager.INSTANCE.all()) {
            if (pen.keeperIds().contains(id)) {
                return EmploymentTarget.ranch(pen.buildingId(), pen.id());
            }
        }
        for (MiningPlot plot : MiningPlotManager.INSTANCE.all()) {
            if (plot.workerIds().contains(id)) {
                return EmploymentTarget.mining(plot.buildingId(), plot.id());
            }
        }
        if (RegionTaskService.INSTANCE.isEmployed(id)) {
            return EmploymentTarget.builder();
        }
        String workplace = npc.getWorkplaceId();
        if (workplace != null && !workplace.isBlank()) {
            UUID buildingId = parseUuid(workplace);
            if (buildingId != null) {
                BuildingRecord building = BuildingRegistry.INSTANCE.byId(buildingId);
                BuildingMeta meta = building == null ? null : BuildingRegistry.INSTANCE.metaOf(building);
                if (meta != null && meta.isWork(BuildingWork.LUMBER)) {
                    return EmploymentTarget.lumber(buildingId);
                }
                return EmploymentTarget.building(buildingId, npc.getWorkRecipeId(), npc.getShift());
            }
        }
        String home = npc.getHomeId();
        if (home != null && !home.isBlank()) {
            UUID homeId = parseUuid(home);
            if (homeId != null) {
                return EmploymentTarget.residence(homeId);
            }
        }
        return null;
    }

    public List<Employment> assignmentsOf(UUID npcId) {
        List<Employment> result = new ArrayList<>();
        NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
        EmploymentTarget current = current(npc);
        if (current != null) {
            result.add(Employment.of(npcId, current));
        }
        return result;
    }

    public int capacity(MinecraftServer server, EmploymentTarget target) {
        if (target == null) {
            return 0;
        }
        switch (target.kind()) {
            case BUILDER -> {
                return 0;
            }
            case FARM -> {
                return farmPlotOf(target) == null ? 0 : 1;
            }
            case RANCH -> {
                return ranchPenOf(target) == null ? 0 : 1;
            }
            case RESIDENCE -> {
                BuildingRecord building = buildingById(target.buildingId());
                ServerLevel level = levelOf(server, building);
                if (building == null || level == null) {
                    return 0;
                }
                int physical = HomeBeds.beds(level, building).size();
                return Math.min(physical, HomeBeds.capacity(building));
            }
            case BUILDING, LUMBER, MINING -> {
                return capacityOfBuilding(target);
            }
            default -> {
                return 0;
            }
        }
    }

    private int capacityOfBuilding(EmploymentTarget target) {
        BuildingRecord building = buildingById(target.buildingId());
        BuildingMeta meta = building == null ? null : BuildingRegistry.INSTANCE.metaOf(building);
        if (building == null || meta == null || meta.work() == null) {
            return 0;
        }
        int level = building.level();
        return switch (meta.work()) {
            case MINE, LUMBER -> BuildingLevelRules.industryWorkers(level);
            case SHOP -> BuildingLevelRules.shopClerks(level);
            case RESTAURANT -> 2;
            case WAREHOUSE -> BuildingLevelRules.warehouseCouriers(level);
            case BUILDER_HUT -> BuildingLevelRules.builderHutCrew(level);
            case BARRACKS -> BuildingLevelRules.militaryTroops(level);
            default -> meta.hireLimit();
        };
    }

    public int occupants(EmploymentTarget target) {
        if (target == null) {
            return 0;
        }
        switch (target.kind()) {
            case FARM -> {
                FarmPlot plot = farmPlotOf(target);
                return plot == null ? 0 : plot.workerIds().size();
            }
            case RANCH -> {
                RanchPen pen = ranchPenOf(target);
                return pen == null ? 0 : pen.keeperIds().size();
            }
            case MINING -> {
                BuildingRecord building = buildingById(target.buildingId());
                if (building == null) {
                    return 0;
                }
                Set<UUID> crew = new HashSet<>();
                for (MiningPlot plot : MiningPlotManager.INSTANCE.all()) {
                    if (building.id().equals(plot.buildingId())) {
                        crew.addAll(plot.workerIds());
                    }
                }
                return crew.size();
            }
            case RESIDENCE -> {
                int count = 0;
                String home = target.buildingId() == null ? "" : target.buildingId().toString();
                for (NpcData candidate : NpcManager.INSTANCE.allNpcs()) {
                    if (home.equals(candidate.getHomeId())) {
                        count++;
                    }
                }
                return count;
            }
            case BUILDER -> {
                return RegionTaskService.INSTANCE.allEmployees().size();
            }
            default -> {
                return workerIds(target.kind(), target.buildingId()).size();
            }
        }
    }

    private boolean alreadyInTarget(UUID npcId, EmploymentTarget target) {
        EmploymentTarget current = current(NpcManager.INSTANCE.getNpc(npcId));
        return current != null && target.sameSlot(current);
    }

    private static BuildingRecord buildingById(UUID buildingId) {
        return buildingId == null ? null : BuildingRegistry.INSTANCE.byId(buildingId);
    }

    private static ServerLevel levelOf(MinecraftServer server, BuildingRecord building) {
        if (server == null || building == null) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(building.dimension());
        return id == null ? null : server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, id));
    }

    public List<UUID> workerIds(EmploymentKind kind, UUID buildingId) {
        List<UUID> result = new ArrayList<>();
        switch (kind) {
            case FARM -> {
                for (FarmPlot plot : FarmPlotManager.INSTANCE.all()) {
                    if (buildingId == null || buildingId.equals(plot.buildingId())) {
                        result.addAll(plot.workerIds());
                    }
                }
            }
            case RANCH -> {
                for (RanchPen pen : RanchPenManager.INSTANCE.all()) {
                    if (buildingId == null || buildingId.equals(pen.buildingId())) {
                        result.addAll(pen.keeperIds());
                    }
                }
            }
            case MINING -> {
                for (MiningPlot plot : MiningPlotManager.INSTANCE.all()) {
                    if (buildingId == null || buildingId.equals(plot.buildingId())) {
                        result.addAll(plot.workerIds());
                    }
                }
            }
            case BUILDER -> RegionTaskService.INSTANCE.allEmployees()
                    .forEach(employee -> result.add(employee.id()));
            default -> {
                for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
                    String workplace = npc.getWorkplaceId();
                    if (workplace == null || workplace.isBlank()) {
                        continue;
                    }
                    if (buildingId == null || workplace.equals(buildingId.toString())) {
                        result.add(npc.getId());
                    }
                }
            }
        }
        return result;
    }

    private void attachBuilding(NpcData npc, EmploymentTarget target) {
        npc.setWorkplaceId(target.buildingId() == null ? null : target.buildingId().toString());
        npc.setWorkRecipeId(target.roleKey());
        if (target.shift() != null) {
            npc.setShift(target.shift());
        }
    }

    private void attachLumber(NpcData npc, EmploymentTarget target) {
        npc.setWorkplaceId(target.buildingId() == null ? null : target.buildingId().toString());
        npc.setWorkRecipeId("");
    }

    private boolean attachResidence(MinecraftServer server, NpcData npc, EmploymentTarget target) {
        BuildingRecord building = target.buildingId() == null
                ? null : BuildingRegistry.INSTANCE.byId(target.buildingId());
        if (building == null || server == null) {
            return false;
        }
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(building.dimension())));
        if (level == null) {
            return false;
        }
        net.minecraft.core.BlockPos bed = HomeBeds.assignFree(level, building);
        if (bed == null) {
            return false;
        }
        npc.setHomeId(building.id().toString());
        npc.setHomeX(bed.getX());
        npc.setHomeY(bed.getY());
        npc.setHomeZ(bed.getZ());
        return true;
    }

    private boolean isBarracks(EmploymentTarget target) {
        if (target == null || target.buildingId() == null) {
            return false;
        }
        BuildingRecord building = BuildingRegistry.INSTANCE.byId(target.buildingId());
        BuildingMeta meta = building == null ? null : BuildingRegistry.INSTANCE.metaOf(building);
        return meta != null && meta.isWork(BuildingWork.BARRACKS);
    }

    private void applyControl(NpcData npc, EmploymentTarget target) {
        NpcBrain brain = npc.getBrain();
        clearControls(brain);
        if (SoldierService.isSoldier(npc) && !isBarracks(target)) {
            SoldierService.clearSoldier(npc);
        }
        switch (target.kind()) {
            case FARM -> brain.setFarmControlled(true);
            case RANCH -> brain.setRanchControlled(true);
            case MINING -> brain.setMiningControlled(true);
            case LUMBER -> brain.setLumberControlled(true);
            case BUILDER -> brain.setConstructionControlled(true);
            case BUILDING -> {
                BuildingRecord building = target.buildingId() == null
                        ? null : BuildingRegistry.INSTANCE.byId(target.buildingId());
                BuildingMeta meta = building == null ? null : BuildingRegistry.INSTANCE.metaOf(building);
                if (meta == null) {
                    return;
                }
                if (meta.isWork(BuildingWork.RESTAURANT)) {
                    brain.setRestaurantControlled(true);
                } else if (meta.isWork(BuildingWork.SHOP)) {
                    brain.setShopControlled(true);
                    npc.setJob(NpcShopService.JOB_NAME);
                } else if (meta.isWork(BuildingWork.LUMBER)) {
                    brain.setLumberControlled(true);
                } else if (meta.isWork(BuildingWork.MINE)) {
                    brain.setMiningControlled(true);
                } else if (meta.isWork(BuildingWork.FORGE)) {
                    brain.setForgeControlled(true);
                } else if (meta.isWork(BuildingWork.BARRACKS)) {
                    brain.setSoldierControlled(true);
                    npc.setJob(SoldierService.JOB);
                } else if (meta.isType(BuildingType.INDUSTRY)) {
                    brain.setIndustryControlled(true);
                }
            }
            default -> {
            }
        }
    }

    private static void clearControls(NpcBrain brain) {
        brain.setFarmControlled(false);
        brain.setRanchControlled(false);
        brain.setMiningControlled(false);
        brain.setLumberControlled(false);
        brain.setIndustryControlled(false);
        brain.setForgeControlled(false);
        brain.setRestaurantControlled(false);
        brain.setShopControlled(false);
        brain.setConstructionControlled(false);
        brain.setSoldierControlled(false);
    }

    private static FarmPlot farmPlotOf(EmploymentTarget target) {
        for (FarmPlot plot : FarmPlotManager.INSTANCE.all()) {
            if (target.targetId() != null && target.targetId().equals(plot.id())) {
                return plot;
            }
        }
        for (FarmPlot plot : FarmPlotManager.INSTANCE.all()) {
            if (target.buildingId() != null && target.buildingId().equals(plot.buildingId())) {
                return plot;
            }
        }
        return null;
    }

    private static RanchPen ranchPenOf(EmploymentTarget target) {
        for (RanchPen pen : RanchPenManager.INSTANCE.all()) {
            if (target.targetId() != null && target.targetId().equals(pen.id())) {
                return pen;
            }
        }
        for (RanchPen pen : RanchPenManager.INSTANCE.all()) {
            if (target.buildingId() != null && target.buildingId().equals(pen.buildingId())) {
                return pen;
            }
        }
        return null;
    }

    private static MiningPlot miningPlotOf(EmploymentTarget target) {
        for (MiningPlot plot : MiningPlotManager.INSTANCE.all()) {
            if (target.targetId() != null && target.targetId().equals(plot.id())) {
                return plot;
            }
        }
        for (MiningPlot plot : MiningPlotManager.INSTANCE.all()) {
            if (target.buildingId() != null && target.buildingId().equals(plot.buildingId())) {
                return plot;
            }
        }
        return null;
    }

    private static int capacityOf(int slots, List<UUID> current) {
        return slots <= 0 ? Math.max(1, current.size()) : slots;
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
