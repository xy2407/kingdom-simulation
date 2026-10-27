package com.xy2407.kingdom.server.rts;

import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.network.rts.RtsTeamOpPacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamSyncPacket;
import com.xy2407.kingdom.common.rts.RtsFormation;
import com.xy2407.kingdom.common.rts.RtsTeam;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** RTS 队伍服务：建队、单成员落位、阵型保存/重排，以及每 10 tick 的阵型跟随。 */
public final class RtsTeamService {

    private static final int FOLLOW_INTERVAL_TICKS = 10;
    private static final double ARRIVED_SQR = 4.0D;

    private static final Map<UUID, Long> EDITING = new ConcurrentHashMap<>();
    private static final long EDITING_TIMEOUT_MS = 300_000L;
    private static final Map<UUID, Long> LAST_STAND = new ConcurrentHashMap<>();
    private static int tickCounter;

    private RtsTeamService() {
    }

    public static void reset() {
        RtsTeamManager.clearAll();
        EDITING.clear();
        LAST_STAND.clear();
        tickCounter = 0;
    }

    public static void createFromSelection(ServerPlayer player, List<UUID> requested, int shape) {
        ServerLevel level = player.serverLevel();
        List<UUID> source = new ArrayList<>();
        if (requested != null && !requested.isEmpty()) {
            source.addAll(requested);
        } else {
            source.addAll(RtsSelectionService.getSelected(player));
        }
        List<UUID> allowed = new ArrayList<>();
        for (UUID id : source) {
            if (id == null || RtsTeamManager.inTeam(id)) {
                continue;
            }
            Entity entity = RtsSelectionService.findEntity(level, id);
            if (RtsSelectionService.canControl(player, entity)) {
                allowed.add(id);
            }
        }
        if (allowed.size() < 2) {
            sync(player);
            return;
        }
        RtsTeam team = RtsTeamManager.create(player.getUUID(), allowed.get(0),
                allowed.subList(1, allowed.size()));
        if (team != null && shape > 0) {
            RtsTeamManager.applyShape(team.teamId(), RtsFormation.Shape.fromId(shape));
        }
        sync(player);
    }

    public static void editMember(ServerPlayer player, UUID teamId, UUID member, float offX, float offZ) {
        RtsTeam team = RtsTeamManager.get(teamId);
        if (team == null || !team.ownerUuid().equals(player.getUUID())) {
            return;
        }
        if (!RtsTeamManager.setOffset(teamId, member, offX, offZ)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        Entity leader = RtsSelectionService.findEntity(level, team.leaderUuid());
        Entity target = RtsSelectionService.findEntity(level, member);
        if (leader != null && target instanceof NpcEntity npc) {
            BlockPos pos = BlockPos.containing(leader.getX() + offX, leader.getY(), leader.getZ() + offZ);
            npc.setRtsTarget(pos, false);
        }
        sync(player);
    }

    public static void setEditing(ServerPlayer player, UUID teamId, boolean editing) {
        RtsTeam team = RtsTeamManager.get(teamId);
        if (team == null || !team.ownerUuid().equals(player.getUUID())) {
            return;
        }
        if (!editing) {
            EDITING.remove(teamId);
            return;
        }
        EDITING.put(teamId, System.currentTimeMillis());
        ServerLevel level = player.serverLevel();
        for (UUID member : team.members()) {
            Entity entity = RtsSelectionService.findEntity(level, member);
            if (entity instanceof NpcEntity npc) {
                npc.getNavigation().stop();
            }
        }
    }

    private static boolean isEditing(UUID teamId) {
        Long since = EDITING.get(teamId);
        if (since == null) {
            return false;
        }
        if (System.currentTimeMillis() - since > EDITING_TIMEOUT_MS) {
            EDITING.remove(teamId);
            return false;
        }
        return true;
    }

    public static void apply(ServerPlayer player, UUID teamId, int action, int shape) {
        RtsTeam team = RtsTeamManager.get(teamId);
        if (team == null || !team.ownerUuid().equals(player.getUUID())) {
            sync(player);
            return;
        }
        switch (action) {
            case RtsTeamOpPacket.ACTION_SAVE -> RtsTeamManager.saveFormation(teamId);
            case RtsTeamOpPacket.ACTION_DISSOLVE -> {
                RtsTeamManager.dissolve(teamId);
                EDITING.remove(teamId);
            }
            case RtsTeamOpPacket.ACTION_SHAPE -> {
                RtsTeamManager.applyShape(teamId, RtsFormation.Shape.fromId(shape));
                ServerLevel level = player.serverLevel();
                Entity leader = RtsSelectionService.findEntity(level, team.leaderUuid());
                if (leader != null) {
                    for (UUID member : team.members()) {
                        if (!member.equals(team.leaderUuid())) {
                            sendToStand(level, team, member, leader);
                        }
                    }
                }
            }
            default -> {
            }
        }
        sync(player);
    }

    public static void tick(MinecraftServer server) {
        if (++tickCounter % FOLLOW_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            for (RtsTeam team : RtsTeamManager.teamsOf(player.getUUID())) {
                if (isEditing(team.teamId())) {
                    continue;
                }
                Entity leader = RtsSelectionService.findEntity(level, team.leaderUuid());
                if (leader == null) {
                    continue;
                }
                for (UUID member : team.members()) {
                    if (member.equals(team.leaderUuid())) {
                        continue;
                    }
                    Entity entity = RtsSelectionService.findEntity(level, member);
                    if (!(entity instanceof NpcEntity npc) || npc.isRemoved()) {
                        continue;
                    }
                    float[] offset = team.offsetFor(member);
                    BlockPos stand = BlockPos.containing(leader.getX() + offset[0],
                            leader.getY(), leader.getZ() + offset[1]);
                    if (npc.blockPosition().distSqr(stand) <= ARRIVED_SQR) {
                        continue;
                    }
                    long key = stand.asLong();
                    Long previous = LAST_STAND.get(member);
                    if (controlledByRts(npc) && previous != null && previous == key) {
                        continue;
                    }
                    LAST_STAND.put(member, key);
                    npc.setRtsTarget(stand, false);
                }
            }
        }
    }

    private static boolean controlledByRts(NpcEntity npc) {
        if (npc.getCitizenId() == null) {
            return false;
        }
        var data = com.xy2407.kingdom.common.npc.NpcManager.INSTANCE.getNpc(npc.getCitizenId());
        return data != null && data.getBrain().isRtsControlled();
    }

    private static void sendToStand(ServerLevel level, RtsTeam team, UUID member, Entity leader) {
        Entity entity = RtsSelectionService.findEntity(level, member);
        if (!(entity instanceof NpcEntity npc)) {
            return;
        }
        float[] offset = team.offsetOf(member);
        npc.setRtsTarget(BlockPos.containing(leader.getX() + offset[0], leader.getY(),
                leader.getZ() + offset[1]), false);
    }

    public static void sync(ServerPlayer player) {
        List<RtsTeamSyncPacket.Entry> entries = new ArrayList<>();
        for (RtsTeam team : RtsTeamManager.teamsOf(player.getUUID())) {
            entries.add(new RtsTeamSyncPacket.Entry(team.teamId(), team.leaderUuid(),
                    team.members(), team.shape().id, team.formationSaved()));
        }
        PacketDistributor.sendToPlayer(player, new RtsTeamSyncPacket(entries));
    }
}
