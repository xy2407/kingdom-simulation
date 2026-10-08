package com.xy2407.kingdom.common.network.rts;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.entity.RtsFakePlayerEntity;
import com.xy2407.kingdom.server.rts.RtsSelectionService;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 节点路径移动指令的网络包：途中所有单位走公共节点，终点按矩形方阵分散站位
 */
public record RtsWaypointMovePacket(List<UUID> entityIds, List<Vec3> waypoints) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RtsWaypointMovePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_waypoint_move"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsWaypointMovePacket> STREAM_CODEC =
            StreamCodec.of(RtsWaypointMovePacket::encode, RtsWaypointMovePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsWaypointMovePacket packet) {
        List<UUID> ids = packet.entityIds() == null ? List.of() : packet.entityIds();
        buf.writeInt(ids.size());
        for (UUID id : ids) {
            buf.writeUUID(id);
        }
        List<Vec3> waypoints = packet.waypoints() == null ? List.of() : packet.waypoints();
        buf.writeInt(waypoints.size());
        for (Vec3 waypoint : waypoints) {
            buf.writeDouble(waypoint.x);
            buf.writeDouble(waypoint.y);
            buf.writeDouble(waypoint.z);
        }
    }

    private static RtsWaypointMovePacket decode(RegistryFriendlyByteBuf buf) {
        int idSize = buf.readInt();
        List<UUID> ids = new ArrayList<>(Math.max(idSize, 1));
        for (int index = 0; index < idSize; index++) {
            ids.add(buf.readUUID());
        }
        int waypointSize = buf.readInt();
        List<Vec3> waypoints = new ArrayList<>(Math.max(waypointSize, 1));
        for (int index = 0; index < waypointSize; index++) {
            waypoints.add(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }
        return new RtsWaypointMovePacket(ids, waypoints);
    }

    public static void handle(RtsWaypointMovePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        List<UUID> ids = packet.entityIds();
        List<Vec3> waypoints = packet.waypoints();
        if (ids == null || ids.isEmpty() || waypoints == null || waypoints.size() < 2) {
            return;
        }
        context.enqueueWork(() -> {
            Set<UUID> allowed = RtsSelectionService.filterControllable(level, player, new HashSet<>(ids));
            List<BlockPos> nodes = new ArrayList<>(waypoints.size());
            for (Vec3 waypoint : waypoints) {
                nodes.add(BlockPos.containing(waypoint));
            }
            List<NpcEntity> units = new ArrayList<>();
            List<RtsFakePlayerEntity> fakes = new ArrayList<>();
            for (UUID id : ids) {
                if (!allowed.contains(id)) {
                    continue;
                }
                Entity entity = RtsSelectionService.findEntity(level, id);
                if (entity instanceof NpcEntity npc) {
                    units.add(npc);
                } else if (entity instanceof RtsFakePlayerEntity fake
                        && player.getUUID().equals(fake.getOwnerUUID())) {
                    fakes.add(fake);
                }
            }
            int total = units.size() + fakes.size();
            int cols = Math.max(1, (int) Math.ceil(Math.sqrt(total)));
            int rows = Math.max(1, (total + cols - 1) / cols);
            BlockPos last = nodes.get(nodes.size() - 1);
            int index = 0;
            for (NpcEntity npc : units) {
                List<BlockPos> personal = new ArrayList<>(nodes);
                personal.set(personal.size() - 1, formationLanding(last, index++, cols, rows));
                npc.setRtsWaypoints(personal);
            }
            for (RtsFakePlayerEntity fake : fakes) {
                BlockPos landing = formationLanding(last, index++, cols, rows);
                fake.setMoveTarget(waypoints.get(waypoints.size() - 1)
                        .add(landing.getX() - last.getX(), 0.0D, landing.getZ() - last.getZ()));
            }
        });
    }

    private static BlockPos formationLanding(BlockPos last, int index, int cols, int rows) {
        return last.offset(index % cols - (cols - 1) / 2, 0, index / cols - (rows - 1) / 2);
    }
}
