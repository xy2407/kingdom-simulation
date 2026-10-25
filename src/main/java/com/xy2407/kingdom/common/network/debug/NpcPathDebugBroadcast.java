package com.xy2407.kingdom.common.network.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.path.NpcPathfinder;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 把 NPC 本次寻路的航点发给附近手持木棍的玩家，用于调试观察
 */
public final class NpcPathDebugBroadcast {

    private static final double RADIUS = 64.0D;

    private NpcPathDebugBroadcast() {
    }

    public static void send(ServerLevel level, NpcEntity npc, List<NpcPathfinder.Waypoint> waypoints) {
        if (waypoints == null) {
            return;
        }
        AABB area = npc.getBoundingBox().inflate(RADIUS);
        List<ServerPlayer> viewers = level.getEntitiesOfClass(ServerPlayer.class, area,
                NpcPathDebugBroadcast::holdingStick);
        if (viewers.isEmpty()) {
            return;
        }
        List<NpcPathDebugPacket.Entry> entries = new ArrayList<>(waypoints.size());
        for (int index = 0; index < waypoints.size(); index++) {
            NpcPathfinder.Waypoint waypoint = waypoints.get(index);
            entries.add(new NpcPathDebugPacket.Entry(waypoint.pos(),
                    (byte) waypoint.move().ordinal(), index));
        }
        NpcPathDebugPacket packet = new NpcPathDebugPacket(npc.getUUID(), entries);
        for (ServerPlayer viewer : viewers) {
            PacketDistributor.sendToPlayer(viewer, packet);
        }
    }

    public static void sendTrace(ServerPlayer viewer, UUID npcId, List<NpcPathDebugPacket.Entry> entries) {
        PacketDistributor.sendToPlayer(viewer, new NpcPathDebugPacket(npcId, List.of()));
        if (entries != null && !entries.isEmpty()) {
            PacketDistributor.sendToPlayer(viewer, new NpcPathDebugPacket(npcId, entries));
        }
    }

    private static boolean holdingStick(ServerPlayer player) {
        return player.getItemInHand(InteractionHand.MAIN_HAND).is(Items.STICK);
    }
}
