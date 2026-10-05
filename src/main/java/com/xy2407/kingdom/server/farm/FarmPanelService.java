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

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 农业面板服务端处理：应答请求、执行操作，每次操作后整份回推最新状态
 */
public final class FarmPanelService {

    /** 按蓝图名缓存作物清单：每次操作都要回推状态，不能每次都读盘。 */
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
                    manager.update(withToggledWorker(plot, packet.text()));
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
                    plot.max().getX(), plot.max().getZ(), plot.min().getY(), plot.workerIds(),
                    plot.extra()));
        }
        return new FarmPanelStatePacket(true, building.name(), building.farmSlots(),
                FarmPlotManager.INSTANCE.usedSlots(building.id()), plots,
                cropOptions(building), workerOptions());
    }

    /** 面板作物槽位：优先用这座建筑蓝图 json 里的 crops，没写就退回全局默认清单。 */
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

    /** 只认面板给出的作物；不在清单里的（含空串＝取消选择）一律记成空。 */
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

    private static List<FarmPanelStatePacket.WorkerOption> workerOptions() {
        List<FarmPanelStatePacket.WorkerOption> options = new ArrayList<>();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead()) {
                continue;
            }
            options.add(new FarmPanelStatePacket.WorkerOption(npc.getId(), npc.getName(), npc.getSkin(),
                    workerNote(npc)));
        }
        return options;
    }

    /** 名册上的状态提示：让玩家在面板上就能看出这个人为什么不下地。 */
    private static String workerNote(NpcData npc) {
        if (npc.getBrain().isStarving()) {
            return "饥饿";
        }
        return NpcSchedule.subState(npc.getShift(), npc.getDayTime()) == NpcSubState.WORKING ? "" : "下班";
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

    private static FarmPlot withToggledWorker(FarmPlot plot, String rawWorkerId) {
        List<UUID> workers = new ArrayList<>(plot.workerIds());
        if (rawWorkerId == null || rawWorkerId.isBlank()) {
            workers.clear();
        } else {
            UUID workerId = parseUuid(rawWorkerId);
            if (workerId == null) {
                return plot;
            }
            if (!workers.remove(workerId)) {
                workers.add(workerId);
            }
        }
        return new FarmPlot(plot.id(), plot.buildingId(), plot.blueprintName(), plot.name(),
                plot.slots(), plot.cropId(), workers,
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
