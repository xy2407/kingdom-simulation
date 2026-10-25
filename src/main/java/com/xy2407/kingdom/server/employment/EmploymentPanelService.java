package com.xy2407.kingdom.server.employment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.building.IndustryKind;
import com.xy2407.kingdom.common.employment.EmploymentKind;
import com.xy2407.kingdom.common.employment.EmploymentResult;
import com.xy2407.kingdom.common.employment.EmploymentTarget;
import com.xy2407.kingdom.common.farm.FarmPlot;
import com.xy2407.kingdom.common.home.HomeBeds;
import com.xy2407.kingdom.common.mining.MiningPlot;
import com.xy2407.kingdom.common.network.employment.EmploymentPanelStatePacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcRole;
import com.xy2407.kingdom.common.ranch.RanchPen;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.farm.FarmPlotManager;
import com.xy2407.kingdom.server.mining.MiningPlotManager;
import com.xy2407.kingdom.server.ranch.RanchPenManager;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 雇佣界面服务端：按范围目标组装候选人名册、执行入职并回推最新状态
 */
public final class EmploymentPanelService {

    private EmploymentPanelService() {
    }

    public static void open(ServerPlayer player, String buildingId, String targetId, int kindIndex) {
        if (player == null) {
            return;
        }
        EmploymentKind kind = kindOf(kindIndex);
        BuildingRecord building = resolve(player, buildingId);
        if (building == null && kind != EmploymentKind.BUILDER) {
            player.displayClientMessage(Component.literal("请站到建筑内再打开雇佣"), true);
            return;
        }
        if (building != null) {
            kind = kindFor(building, kind);
        }
        PacketDistributor.sendToPlayer(player,
                snapshot(player, buildingId, targetId, kind));
    }

    public static void submit(ServerPlayer player, String buildingId, String targetId, int kindIndex,
                              List<UUID> ids) {
        if (player == null) {
            return;
        }
        EmploymentKind kind = kindOf(kindIndex);
        BuildingRecord building = resolve(player, buildingId);
        if (building == null && kind != EmploymentKind.BUILDER) {
            player.displayClientMessage(Component.literal("雇佣目标无效"), true);
            return;
        }
        if (building != null) {
            kind = kindFor(building, kind);
        }
        EmploymentTarget target = targetOf(player, buildingId, targetId, kind);
        if (target == null) {
            player.displayClientMessage(Component.literal("雇佣目标无效"), true);
            return;
        }
        int limit = limitOf(player, buildingId, targetId, kind);
        int accepted = 0;
        int visited = 0;
        for (UUID id : ids) {
            if (limit > 0 && accepted >= limit) {
                break;
            }
            visited++;
            EmploymentResult result = EmploymentService.INSTANCE.hire(player.server, id, target);
            if (result.success()) {
                accepted++;
            } else {
                NpcData npc = NpcManager.INSTANCE.getNpc(id);
                String who = npc == null ? "市民" : npc.getName();
                player.displayClientMessage(Component.literal(who + "：" + result.reason()), true);
            }
        }
        int dropped = ids.size() - visited;
        if (dropped > 0) {
            player.displayClientMessage(Component.literal("超出岗位上限，已忽略 " + dropped + " 人"), true);
        }
        PacketDistributor.sendToPlayer(player, snapshot(player, buildingId, targetId, kind));
    }

    public static void dismiss(ServerPlayer player, String buildingId, String targetId, int kindIndex,
                               UUID npcId) {
        if (player == null || npcId == null) {
            return;
        }
        EmploymentKind kind = kindOf(kindIndex);
        BuildingRecord building = resolve(player, buildingId);
        if (building != null) {
            kind = kindFor(building, kind);
        }
        EmploymentService.INSTANCE.dismissAssignment(player.server, npcId, kind);
        PacketDistributor.sendToPlayer(player, snapshot(player, buildingId, targetId, kind));
    }

    private static EmploymentPanelStatePacket snapshot(ServerPlayer player, String buildingId,
                                                       String targetId, EmploymentKind kind) {
        BuildingRecord building = buildingOf(buildingId);
        int limit = limitOf(player, buildingId, targetId, kind);
        String buildingText = building == null ? "" : building.id().toString();
        String targetText = targetId == null ? "" : targetId;
        EmploymentTarget probe = targetOf(player, buildingId, targetText, kind);
        List<EmploymentPanelStatePacket.Entry> entries = new ArrayList<>();
        List<UUID> selected = new ArrayList<>();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || npc.getRole() == NpcRole.TOURIST) {
                continue;
            }
            EmploymentTarget current = EmploymentService.INSTANCE.current(npc);
            String job = current == null ? "" : current.describe();
            String note = probe == null ? "" : EmploymentService.INSTANCE.conflict(npc, probe);
            boolean hired = occupiedHere(npc, buildingText, targetText, kind);
            entries.add(new EmploymentPanelStatePacket.Entry(npc.getId(), npc.getName(),
                    npc.getSkin(), job, note == null ? "" : note, hired));
            if (hired) {
                selected.add(npc.getId());
            }
        }
        String label = kind.label();
        String name = building == null ? "" : building.name();
        String title = name.isBlank() ? label : name + " · " + label;
        if (!targetText.isBlank() && kind == EmploymentKind.FARM) {
            FarmPlot plot = farmPlot(buildingText, targetText);
            if (plot != null) {
                title = plot.name() + " · " + label;
            }
        }
        return new EmploymentPanelStatePacket(buildingText, targetText, kind.ordinal(), title,
                limit, entries, selected);
    }

    private static boolean occupiedHere(NpcData npc, String buildingId, String targetId,
                                        EmploymentKind kind) {
        switch (kind) {
            case FARM -> {
                FarmPlot plot = farmPlot(buildingId, targetId);
                return plot != null && plot.workerIds().contains(npc.getId());
            }
            case RANCH -> {
                RanchPen pen = ranchPen(buildingId, targetId);
                return pen != null && pen.keeperIds().contains(npc.getId());
            }
            case MINING -> {
                MiningPlot plot = miningPlot(buildingId, targetId);
                return plot != null && plot.workerIds().contains(npc.getId());
            }
            case BUILDER -> {
                return com.xy2407.kingdom.server.task.RegionTaskService.INSTANCE.isEmployed(npc.getId());
            }
            case RESIDENCE -> {
                return buildingId.equals(npc.getHomeId());
            }
            default -> {
                return buildingId.equals(npc.getWorkplaceId());
            }
        }
    }

    private static int limitOf(ServerPlayer player, String buildingId, String targetId,
                               EmploymentKind kind) {
        EmploymentTarget target = targetOf(player, buildingId, targetId, kind);
        return target == null ? 0 : EmploymentService.INSTANCE.capacity(player.server, target);
    }

    private static EmploymentTarget targetOf(ServerPlayer player, String buildingId, String targetId,
                                             EmploymentKind kind) {
        BuildingRecord building = buildingOf(buildingId);
        if (building == null) {
            return kind == EmploymentKind.BUILDER ? EmploymentTarget.builder() : null;
        }
        switch (kind) {
            case FARM -> {
                FarmPlot plot = farmPlot(buildingId, targetId);
                return plot == null ? null : EmploymentTarget.farm(plot.buildingId(), plot.id());
            }
            case RANCH -> {
                RanchPen pen = ranchPen(buildingId, targetId);
                return pen == null ? null : EmploymentTarget.ranch(pen.buildingId(), pen.id());
            }
            case MINING -> {
                MiningPlot plot = miningPlot(buildingId, targetId);
                return plot == null ? null : EmploymentTarget.mining(plot.buildingId(), plot.id());
            }
            case LUMBER -> {
                return EmploymentTarget.lumber(building.id());
            }
            case RESIDENCE -> {
                return EmploymentTarget.residence(building.id());
            }
            default -> {
                return EmploymentTarget.building(building.id(), "", null);
            }
        }
    }

    private static FarmPlot farmPlot(String buildingId, String targetId) {
        UUID wanted = parseUuid(targetId);
        if (wanted != null) {
            for (FarmPlot plot : FarmPlotManager.INSTANCE.all()) {
                if (plot.id().equals(wanted)) {
                    return plot;
                }
            }
        }
        for (FarmPlot plot : FarmPlotManager.INSTANCE.all()) {
            if (buildingId != null && buildingId.equals(plot.buildingId().toString())) {
                return plot;
            }
        }
        return null;
    }

    private static RanchPen ranchPen(String buildingId, String targetId) {
        UUID wanted = parseUuid(targetId);
        if (wanted != null) {
            for (RanchPen pen : RanchPenManager.INSTANCE.all()) {
                if (pen.id().equals(wanted)) {
                    return pen;
                }
            }
        }
        for (RanchPen pen : RanchPenManager.INSTANCE.all()) {
            if (buildingId != null && buildingId.equals(pen.buildingId().toString())) {
                return pen;
            }
        }
        return null;
    }

    private static MiningPlot miningPlot(String buildingId, String targetId) {
        UUID wanted = parseUuid(targetId);
        if (wanted != null) {
            for (MiningPlot plot : MiningPlotManager.INSTANCE.all()) {
                if (plot.id().equals(wanted)) {
                    return plot;
                }
            }
        }
        for (MiningPlot plot : MiningPlotManager.INSTANCE.all()) {
            if (buildingId != null && buildingId.equals(plot.buildingId().toString())) {
                return plot;
            }
        }
        return null;
    }

    private static BuildingRecord resolve(ServerPlayer player, String buildingId) {
        BuildingRecord building = buildingOf(buildingId);
        return building != null ? building : BuildingRegistry.INSTANCE.locate(player);
    }

    private static EmploymentKind kindFor(BuildingRecord building, EmploymentKind requested) {
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        if (meta == null) {
            return requested;
        }
        if (meta.type() == BuildingType.AGRICULTURE) {
            return EmploymentKind.FARM;
        }
        if (meta.type() == BuildingType.LIVESTOCK) {
            return EmploymentKind.RANCH;
        }
        if (meta.type() == BuildingType.RESIDENTIAL) {
            return EmploymentKind.RESIDENCE;
        }
        if (meta.type() == BuildingType.INDUSTRY) {
            if (meta.industryKind() == IndustryKind.LUMBER) {
                return EmploymentKind.LUMBER;
            }
            if (meta.industryKind() == IndustryKind.MINING) {
                return EmploymentKind.MINING;
            }
            return EmploymentKind.BUILDING;
        }
        return requested;
    }

    private static BuildingRecord buildingOf(String buildingId) {
        UUID id = parseUuid(buildingId);
        return id == null ? null : BuildingRegistry.INSTANCE.byId(id);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static EmploymentKind kindOf(int index) {
        EmploymentKind[] values = EmploymentKind.values();
        return index < 0 || index >= values.length ? EmploymentKind.BUILDING : values[index];
    }
}
