package com.xy2407.kingdom.server.farm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.building.BuildingBlueprints;
import com.xy2407.kingdom.common.building.BuildingMetaLoader;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.CropSpec;
import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.farm.CropCatalog;
import com.xy2407.kingdom.common.farm.FarmPlot;
import com.xy2407.kingdom.common.network.farm.FarmPanelActionPacket;
import com.xy2407.kingdom.common.network.farm.FarmPanelStatePacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcSchedule;
import com.xy2407.kingdom.common.npc.NpcSubState;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageMenu;
import com.xy2407.kingdom.server.employment.EmploymentService;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 农业面板服务端处理：应答请求、执行操作，每次操作后整份回推最新状态。
 */
public final class FarmPanelService {

    private static final Map<String, List<CropSpec>> CROP_CACHE = new ConcurrentHashMap<>();

    private FarmPanelService() {
    }

    public static void respond(ServerPlayer player) {
        BuildingRecord building = agricultureAt(player);
        if (building == null) {
            PacketDistributor.sendToPlayer(player, FarmPanelStatePacket.outside());
            return;
        }
        PacketDistributor.sendToPlayer(player, snapshot(building));
    }

    public static void apply(ServerPlayer player, FarmPanelActionPacket packet) {
        BuildingRecord building = agricultureAt(player);
        if (building == null) {
            respond(player);
            return;
        }
        FarmPlotManager manager = FarmPlotManager.INSTANCE;
        UUID plotId = packet.plotIdOrNull();
        switch (packet.action()) {
            case FarmPanelActionPacket.ACTION_CREATE -> {
                int groundY = building.min().getY();
                BlockPos min = new BlockPos(Math.min(packet.minX(), packet.maxX()), groundY,
                        Math.min(packet.minZ(), packet.maxZ()));
                BlockPos max = new BlockPos(Math.max(packet.minX(), packet.maxX()), groundY,
                        Math.max(packet.minZ(), packet.maxZ()));
                manager.create(building, "", packet.value(), DateSystem.totalDay(player.serverLevel()),
                        min, max, allowedCrop(building, packet.text()), packet.workerIds(),
                        packet.extraRegions());
            }
            case FarmPanelActionPacket.ACTION_SET_SLOTS -> {
                FarmPlot plot = owned(manager, plotId, building);
                if (plot != null) {
                    manager.update(withSlots(plot, packet.value()));
                }
            }
            case FarmPanelActionPacket.ACTION_SET_CROP -> {
                FarmPlot plot = owned(manager, plotId, building);
                if (plot != null) {
                    manager.update(withCrop(plot, allowedCrop(building, packet.text())));
                }
            }
            case FarmPanelActionPacket.ACTION_TOGGLE_WORKER -> {
                FarmPlot plot = owned(manager, plotId, building);
                if (plot != null) {
                    UUID workerId = parseUuid(packet.text());
                    if (workerId != null) {
                        if (plot.workerIds().contains(workerId)) {
                            com.xy2407.kingdom.server.employment.EmploymentService.INSTANCE
                                    .dismissAssignment(player.server, workerId,
                                            com.xy2407.kingdom.common.employment.EmploymentKind.FARM);
                        } else {
                            var result = com.xy2407.kingdom.server.employment.EmploymentService.INSTANCE
                                    .hire(player.server, workerId,
                                            com.xy2407.kingdom.common.employment.EmploymentTarget
                                                    .farm(plot.buildingId(), plot.id()));
                            if (!result.success()) {
                                player.displayClientMessage(
                                        Component.literal(result.reason() + "，无法安排打理"), true);
                            }
                        }
                    }
                }
            }
            case FarmPanelActionPacket.ACTION_SET_AREA -> {
                FarmPlot plot = owned(manager, plotId, building);
                if (plot != null) {
                    manager.update(withArea(plot, building, packet));
                }
            }
            case FarmPanelActionPacket.ACTION_DELETE -> {
                if (owned(manager, plotId, building) != null) {
                    manager.remove(plotId);
                }
            }
            case FarmPanelActionPacket.ACTION_OPEN_STORAGE -> {
                openStorage(player, building);
                return;
            }
            default -> {
            }
        }
        respond(player);
    }

    private static FarmPanelStatePacket snapshot(BuildingRecord building) {
        List<FarmPanelStatePacket.Plot> plots = new ArrayList<>();
        for (FarmPlot plot : FarmPlotManager.INSTANCE.plotsOf(building.id())) {
            plots.add(new FarmPanelStatePacket.Plot(plot.id(), plot.name(), plot.slots(), plot.cropId(),
                    plot.hasArea(), plot.min().getX(), plot.min().getZ(),
                    plot.max().getX(), plot.max().getZ(), plot.min().getY(),
                    EmploymentService.aliveOnly(plot.workerIds()),
                    plot.extra()));
        }
        return new FarmPanelStatePacket(true, building.name(), building.farmSlots(),
                FarmPlotManager.INSTANCE.usedSlots(building.id()), plots,
                cropOptions(building), workerOptions(building.id()));
    }

    private static List<FarmPanelStatePacket.CropOption> cropOptions(BuildingRecord building) {
        List<CropSpec> specs = cropsOf(building);
        if (specs.isEmpty()) {
            return CropCatalog.all();
        }
        List<FarmPanelStatePacket.CropOption> options = new ArrayList<>(specs.size());
        for (CropSpec spec : specs) {
            options.add(new FarmPanelStatePacket.CropOption(spec.item(),
                    spec.unnamed() ? CropCatalog.labelOf(spec.item()) : spec.name()));
        }
        return options;
    }

    private static List<CropSpec> cropsOf(BuildingRecord building) {
        if (building == null || building.name() == null || building.name().isBlank()) {
            return List.of();
        }
        return CROP_CACHE.computeIfAbsent(building.name(),
                name -> BuildingMetaLoader.load(BuildingBlueprints.fileOf(name), name).crops());
    }

    private static String allowedCrop(BuildingRecord building, String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        for (FarmPanelStatePacket.CropOption option : cropOptions(building)) {
            if (option.id().equals(raw)) {
                return raw;
            }
        }
        return "";
    }

    private static List<FarmPanelStatePacket.WorkerOption> workerOptions(UUID buildingId) {
        List<FarmPanelStatePacket.WorkerOption> options = new ArrayList<>();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead()) {
                continue;
            }
            options.add(new FarmPanelStatePacket.WorkerOption(npc.getId(), npc.getName(), npc.getSkin(),
                    workerNote(npc, buildingId)));
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
        return NpcSchedule.subState(npc.getShift(), npc.getDayTime()) == NpcSubState.WORKING ? "" : "下班";
    }

    private static void openStorage(ServerPlayer player, BuildingRecord building) {
        int capacity = BuildingRegistry.INSTANCE.storageSlotsOf(building);
        if (capacity <= 0) {
            player.displayClientMessage(
                    Component.literal("这座建筑没有仓库：蓝图 json 里没写 storage_slots"), true);
            return;
        }
        BuildingStorageMenu.open(player, building, capacity);
    }

    private static BuildingRecord agricultureAt(ServerPlayer player) {
        BuildingRecord building = BuildingRegistry.INSTANCE.locate(player);
        return building != null && building.farmSlots() > 0 ? building : null;
    }

    private static FarmPlot owned(FarmPlotManager manager, UUID plotId, BuildingRecord building) {
        FarmPlot plot = manager.byId(plotId);
        return plot != null && plot.buildingId().equals(building.id()) ? plot : null;
    }

    private static FarmPlot withSlots(FarmPlot plot, int slots) {
        return new FarmPlot(plot.id(), plot.buildingId(), plot.blueprintName(), plot.name(),
                Math.max(0, slots), plot.cropId(), plot.workerIds(),
                plot.dimension(), plot.min(), plot.max(), plot.createdDay(), plot.extra());
    }

    private static FarmPlot withCrop(FarmPlot plot, String cropId) {
        return new FarmPlot(plot.id(), plot.buildingId(), plot.blueprintName(), plot.name(),
                plot.slots(), cropId, plot.workerIds(),
                plot.dimension(), plot.min(), plot.max(), plot.createdDay(), plot.extra());
    }

    private static FarmPlot withArea(FarmPlot plot, BuildingRecord building, FarmPanelActionPacket packet) {
        int minX = Math.min(packet.minX(), packet.maxX());
        int maxX = Math.max(packet.minX(), packet.maxX());
        int minZ = Math.min(packet.minZ(), packet.maxZ());
        int maxZ = Math.max(packet.minZ(), packet.maxZ());
        int groundY = building.min().getY();
        return new FarmPlot(plot.id(), plot.buildingId(), plot.blueprintName(), plot.name(),
                plot.slots(), plot.cropId(), plot.workerIds(), plot.dimension(),
                new BlockPos(minX, groundY, minZ), new BlockPos(maxX, groundY, maxZ), plot.createdDay(),
                packet.extraRegions());
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

}
