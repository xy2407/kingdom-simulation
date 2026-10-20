package com.xy2407.kingdom.server.ranch;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.building.AnimalSpec;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.network.ranch.RanchPanelActionPacket;
import com.xy2407.kingdom.common.network.ranch.RanchPanelStatePacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcSchedule;
import com.xy2407.kingdom.common.npc.NpcShift;
import com.xy2407.kingdom.common.npc.NpcSubState;
import com.xy2407.kingdom.common.ranch.RanchPen;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.npc.NpcOccupation;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 养殖面板服务端处理：应答请求、执行开始/暂停与饲养员任免，每次操作后整份回推。
 */
public final class RanchPanelService {

    private RanchPanelService() {
    }

    public static void respond(ServerPlayer player) {
        BuildingRecord building = ranchAt(player);
        if (building == null) {
            PacketDistributor.sendToPlayer(player, RanchPanelStatePacket.outside());
            return;
        }
        PacketDistributor.sendToPlayer(player, snapshot(player, building));
    }

    public static void apply(ServerPlayer player, RanchPanelActionPacket packet) {
        BuildingRecord building = ranchAt(player);
        if (building == null) {
            respond(player);
            return;
        }
        RanchPen pen = penOf(building, packet.penIdOrNull());
        switch (packet.action()) {
            case RanchPanelActionPacket.ACTION_TOGGLE_RUN -> {
                if (pen != null) {
                    toggleRun(building, pen);
                }
            }
            case RanchPanelActionPacket.ACTION_SET_KEEPER -> {
                UUID workerId = packet.workerIdOrNull();
                NpcShift shift = packet.shiftOrNull();
                if (pen != null && workerId != null && shift != null) {
                    NpcData npc = NpcManager.INSTANCE.getNpc(workerId);
                    if (npc != null) {
                        boolean hired = pen.keeperIds().contains(workerId);
                        String conflict = hired ? null : NpcOccupation.conflict(npc, pen.buildingId());
                        if (conflict == null) {
                            applyKeeper(pen, npc, shift);
                        } else {
                            player.displayClientMessage(
                                    Component.literal(npc.getName() + " " + conflict + "，无法安排看护"), true);
                        }
                    }
                }
            }
            case RanchPanelActionPacket.ACTION_CLEAR_KEEPERS -> {
                if (pen != null) {
                    releaseKeepers(pen.keeperIds());
                    RanchPenManager.INSTANCE.update(pen.withKeeperIds(List.of()));
                }
            }
            default -> {
            }
        }
        respond(player);
    }

    private static void applyKeeper(RanchPen pen, NpcData npc, NpcShift shift) {
        UUID workerId = npc.getId();
        boolean hired = pen.keeperIds().contains(workerId);
        if (hired && npc.getShift() == shift) {
            RanchPenManager.INSTANCE.update(pen.withKeeperIds(without(pen.keeperIds(), workerId)));
            npc.setShift(NpcShift.DAY_SHIFT);
            return;
        }
        if (!hired) {
            RanchPenManager.INSTANCE.update(pen.withKeeperIds(with(pen.keeperIds(), workerId)));
        }
        npc.setShift(shift);
    }

    private static void releaseKeepers(List<UUID> keepers) {
        for (UUID keeper : keepers) {
            NpcData npc = NpcManager.INSTANCE.getNpc(keeper);
            if (npc != null) {
                npc.setShift(NpcShift.DAY_SHIFT);
            }
        }
    }

    private static void toggleRun(BuildingRecord building, RanchPen pen) {
        boolean wanted = !pen.running();
        for (RanchPen other : RanchPenManager.INSTANCE.pensOf(building.id())) {
            boolean value = wanted && other.id().equals(pen.id());
            if (other.running() != value) {
                RanchPenManager.INSTANCE.update(other.withRunning(value));
            }
        }
    }

    private static List<UUID> with(List<UUID> keepers, UUID workerId) {
        List<UUID> result = new ArrayList<>(keepers);
        if (!result.contains(workerId)) {
            result.add(workerId);
        }
        return result;
    }

    private static List<UUID> without(List<UUID> keepers, UUID workerId) {
        List<UUID> result = new ArrayList<>(keepers);
        result.remove(workerId);
        return result;
    }

    private static RanchPanelStatePacket snapshot(ServerPlayer player, BuildingRecord building) {
        ServerLevel level = levelOf(player, building);
        List<RanchPanelStatePacket.Animal> animals = new ArrayList<>();
        for (AnimalSpec spec : BuildingRegistry.INSTANCE.metaOf(building).animals()) {
            RanchPen pen = RanchPenManager.INSTANCE.byKey(building.id(), spec.id());
            int total = 0;
            int adults = 0;
            if (pen != null) {
                int[] heads = RanchWorkService.headcount(level, pen);
                total = heads[0];
                adults = heads[1];
            }
            animals.add(new RanchPanelStatePacket.Animal(pen == null ? null : pen.id(), spec.id(),
                    spec.name(), spec.entity(), spec.feed(), spec.output(), spec.mode(), statusLabel(pen),
                    spec.limit(), total, adults, pen != null && pen.running(),
                    pen == null ? List.of() : pen.keeperIds()));
        }
        return new RanchPanelStatePacket(true, building.name(), building.level(), positionText(building),
                animals, workerOptions(building.id()));
    }

    private static List<RanchPanelStatePacket.Worker> workerOptions(UUID buildingId) {
        List<RanchPanelStatePacket.Worker> options = new ArrayList<>();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead()) {
                continue;
            }
            options.add(new RanchPanelStatePacket.Worker(npc.getId(), npc.getName(), npc.getSkin(),
                    workerNote(npc, buildingId), npc.getShift().name()));
        }
        return options;
    }

    private static String workerNote(NpcData npc, UUID buildingId) {
        String conflict = NpcOccupation.note(npc, buildingId);
        if (!conflict.isEmpty()) {
            return conflict;
        }
        if (npc.getBrain().isStarving()) {
            return "饥饿";
        }
        return NpcSchedule.subState(npc.getShift(), npc.getDayTime()) == NpcSubState.WORKING ? "" : "下班";
    }

    private static String statusLabel(RanchPen pen) {
        if (pen == null) {
            return "未建栏";
        }
        if (!pen.running()) {
            return "未开始";
        }
        return switch (pen.statusKey()) {
            case RanchWorkService.STATUS_NO_BUILDING -> "建筑不存在";
            case RanchWorkService.STATUS_NO_ANIMAL -> "配方缺失";
            case RanchWorkService.STATUS_NO_KEEPER -> "待指派饲养员";
            case RanchWorkService.STATUS_NO_FEED -> "缺饲料";
            case RanchWorkService.STATUS_GROWING -> "育肥中";
            case RanchWorkService.STATUS_FULL -> "已成栏";
            case RanchWorkService.STATUS_COLLECTING -> "出货中";
            default -> "处理中";
        };
    }

    private static String positionText(BuildingRecord building) {
        return building.min().getX() + ", " + building.min().getY() + ", " + building.min().getZ();
    }

    private static BuildingRecord ranchAt(ServerPlayer player) {
        BuildingRecord building = BuildingRegistry.INSTANCE.locate(player);
        if (building == null) {
            return null;
        }
        return BuildingRegistry.INSTANCE.metaOf(building).animals().isEmpty() ? null : building;
    }

    private static RanchPen penOf(BuildingRecord building, UUID penId) {
        RanchPen pen = RanchPenManager.INSTANCE.byId(penId);
        return pen != null && pen.buildingId().equals(building.id()) ? pen : null;
    }

    private static ServerLevel levelOf(ServerPlayer player, BuildingRecord building) {
        ResourceLocation id = ResourceLocation.tryParse(building.dimension());
        if (id == null || player.getServer() == null) {
            return player.serverLevel();
        }
        ServerLevel level = player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id));
        return level == null ? player.serverLevel() : level;
    }
}
