package com.xy2407.kingdom.server.task;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.network.builder.BuilderBoardDataPacket;
import com.xy2407.kingdom.common.network.builder.BuilderBoardRequestPacket;
import com.xy2407.kingdom.common.network.builder.BuilderEmployeePacket;
import com.xy2407.kingdom.common.network.builder.BuilderTaskActionPacket;
import com.xy2407.kingdom.common.network.builder.BuilderAssignPacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.server.building.BuildingConstructionService;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 建筑工面板服务端：聚合雇员与三类任务并分发开始暂停终止指派雇佣指令
 */
public final class BuilderBoardService {

    private BuilderBoardService() {
    }

    public static void respond(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, buildPacket());
    }

    public static void broadcastAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            respond(player);
        }
    }

    public static BuilderBoardDataPacket buildPacket() {
        List<BuilderBoardDataPacket.EmployeeEntry> employees =
                new ArrayList<>(RegionTaskService.INSTANCE.employeeEntries());
        List<BuilderBoardDataPacket.TaskEntry> tasks = new ArrayList<>();
        tasks.addAll(buildingEntries());
        tasks.addAll(RegionTaskService.INSTANCE.taskEntries());
        return new BuilderBoardDataPacket(employees, tasks);
    }

    private static List<BuilderBoardDataPacket.TaskEntry> buildingEntries() {
        List<BuilderBoardDataPacket.TaskEntry> list = new ArrayList<>();
        for (BuildingConstructionService.TaskSnapshot snapshot
                : BuildingConstructionService.INSTANCE.snapshots()) {
            List<BuilderBoardDataPacket.NpcRef> npcs = new ArrayList<>();
            NpcData architect = NpcManager.INSTANCE.getNpc(snapshot.architectId());
            if (architect != null && !architect.isDead()) {
                npcs.add(new BuilderBoardDataPacket.NpcRef(architect.getId(),
                        architect.getName(), architect.getSkin()));
            }
            List<BuilderBoardDataPacket.MatEntry> materials = new ArrayList<>();
            for (var entry : snapshot.materials().entrySet()) {
                materials.add(new BuilderBoardDataPacket.MatEntry(entry.getKey(), entry.getValue()));
            }
            list.add(new BuilderBoardDataPacket.TaskEntry(snapshot.id(),
                    BuilderBoardDataPacket.KIND_BUILDING, snapshot.blueprintName(),
                    snapshot.dimension(), snapshot.origin().getX(), snapshot.origin().getY(),
                    snapshot.origin().getZ(), snapshot.origin().getX() + snapshot.size().getX() - 1,
                    snapshot.origin().getY() + snapshot.size().getY() - 1,
                    snapshot.origin().getZ() + snapshot.size().getZ() - 1,
                    snapshot.paused() ? "PAUSED" : "RUNNING", snapshot.cursor(),
                    snapshot.total(), npcs, materials));
        }
        return list;
    }

    public static void applyAction(ServerPlayer player, BuilderTaskActionPacket packet) {
        UUID taskId = packet.taskId();
        if (taskId == null || taskId.equals(new UUID(0L, 0L))) {
            return;
        }
        if (isRegionTask(taskId)) {
            RegionTaskService.INSTANCE.applyAction(player, taskId, packet.action());
            return;
        }
        switch (packet.action()) {
            case BuilderTaskActionPacket.ACTION_START -> {
                BuildingConstructionService.INSTANCE.setPaused(taskId, false);
                feedback(player, "施工已继续");
            }
            case BuilderTaskActionPacket.ACTION_PAUSE -> {
                BuildingConstructionService.INSTANCE.setPaused(taskId, true);
                feedback(player, "施工已暂停");
            }
            case BuilderTaskActionPacket.ACTION_CANCEL -> {
                if (BuildingConstructionService.INSTANCE.cancel(player.server, taskId)) {
                    feedback(player, "施工已终止，脚手架已清理");
                } else {
                    feedback(player, "找不到这个任务");
                }
            }
            default -> feedback(player, "未知操作");
        }
        broadcastAll(player.server);
    }

    public static void applyAssign(ServerPlayer player, BuilderAssignPacket packet) {
        UUID taskId = packet.taskId();
        UUID npcId = packet.npcId();
        if (taskId == null || npcId == null
                || taskId.equals(new UUID(0L, 0L)) || npcId.equals(new UUID(0L, 0L))) {
            return;
        }
        if (isRegionTask(taskId)) {
            RegionTaskService.INSTANCE.applyAssign(player, taskId, npcId, packet.add());
            return;
        }
        if (!packet.add()) {
            BuildingConstructionService.INSTANCE.unassign(taskId);
            feedback(player, "已撤下该建筑的建筑工");
            return;
        }
        String error = BuildingConstructionService.INSTANCE.assign(taskId, npcId);
        if (error != null) {
            feedback(player, error);
        }
    }

    public static void applyEmployee(ServerPlayer player, BuilderEmployeePacket packet) {
        UUID npcId = packet.npcId();
        if (packet.hire() || npcId == null || npcId.equals(new UUID(0L, 0L))) {
            return;
        }
        RegionTaskService.INSTANCE.applyEmployee(player, npcId, false);
    }

    private static boolean isRegionTask(UUID taskId) {
        for (com.xy2407.kingdom.common.task.RegionTask task : RegionTaskService.INSTANCE.allTasks()) {
            if (task.id().equals(taskId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidNpc(UUID npcId) {
        NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
        return npc != null && !npc.isDead();
    }

    private static void feedback(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal("建筑工：" + message), false);
    }
}
