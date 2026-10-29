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
 * 客户端 → 服务端：命令这批单位走到目标点，多个落点用黄金角螺旋错开。
 */
public record RtsMoveCommandPacket(List<UUID> entityIds, Vec3 target) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RtsMoveCommandPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_move_command"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsMoveCommandPacket> STREAM_CODEC =
            StreamCodec.of(RtsMoveCommandPacket::encode, RtsMoveCommandPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsMoveCommandPacket packet) {
        List<UUID> ids = packet.entityIds() == null ? List.of() : packet.entityIds();
        buf.writeInt(ids.size());
        for (UUID id : ids) {
            buf.writeUUID(id);
        }
        Vec3 target = packet.target() == null ? Vec3.ZERO : packet.target();
        buf.writeDouble(target.x);
        buf.writeDouble(target.y);
        buf.writeDouble(target.z);
    }

    private static RtsMoveCommandPacket decode(RegistryFriendlyByteBuf buf) {
        int size = buf.readInt();
        List<UUID> ids = new ArrayList<>(Math.max(size, 1));
        for (int i = 0; i < size; i++) {
            ids.add(buf.readUUID());
        }
        return new RtsMoveCommandPacket(ids, new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
    }

    public static void handle(RtsMoveCommandPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        List<UUID> ids = packet.entityIds();
        Vec3 target = packet.target();
        if (ids == null || ids.isEmpty() || target == null) {
            return;
        }
        context.enqueueWork(() -> {
            Set<UUID> allowed = RtsSelectionService.filterControllable(level, player, new HashSet<>(ids));
            List<NpcEntity> units = new ArrayList<>();
            List<RtsFakePlayerEntity> fakes = new ArrayList<>();
            for (UUID id : ids) {
                if (!allowed.contains(id)) {
                    continue;
                }
                Entity entity = RtsSelectionService.findEntity(level, id);
                if (entity instanceof NpcEntity npc) {
                    if (!npc.isTouristEntity()) {
                        units.add(npc);
                    }
                } else if (entity instanceof RtsFakePlayerEntity fake
                        && player.getUUID().equals(fake.getOwnerUUID())) {
                    fakes.add(fake);
                }
            }
            int total = units.size() + fakes.size();
            int cols = Math.max(1, (int) Math.ceil(Math.sqrt(total)));
            int rows = Math.max(1, (total + cols - 1) / cols);
            BlockPos center = BlockPos.containing(target);
            boolean worldOps = false;
            for (NpcEntity npc : units) {
                UUID teamId = com.xy2407.kingdom.server.rts.RtsTeamManager.teamIdOf(npc.getUUID());
                if (teamId != null
                        && com.xy2407.kingdom.server.rts.RtsTeamManager.isWorldOps(teamId)) {
                    worldOps = true;
                    break;
                }
            }
            if (worldOps) {
                for (NpcEntity npc : units) {
                    npc.setRtsPrecise(true);
                    npc.setRtsGroupTarget(center, center, false);
                }
                for (RtsFakePlayerEntity fake : fakes) {
                    fake.setMoveTarget(Vec3.atLowerCornerOf(center).add(0.5D, 0.0D, 0.5D));
                }
                return;
            }
            int index = 0;
            for (NpcEntity npc : units) {
                npc.setRtsGroupTarget(formationLanding(center, index, cols, rows), center, false);
                index++;
            }
            for (RtsFakePlayerEntity fake : fakes) {
                BlockPos landing = formationLanding(center, index++, cols, rows);
                fake.setMoveTarget(Vec3.atLowerCornerOf(landing).add(0.5D, 0.0D, 0.5D));
            }
        });
    }

    private static BlockPos formationLanding(BlockPos center, int index, int cols, int rows) {
        return center.offset(index % cols - (cols - 1) / 2, 0, index / cols - (rows - 1) / 2);
    }
}
