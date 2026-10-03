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
 * 客户端 → 服务端：命令这批单位走到目标点（RTS 的基础移动指令）。
 *
 * <p>移植自 {@code xy's_nsuk} 的同名包，做了三处减法：</p>
 * <ul>
 *   <li>去掉队伍阵型联调与队长跟随（kingdom 暂无队伍）；</li>
 *   <li>执行端从 simukraft 的导航服务换成 {@link NpcEntity#setRtsTarget(BlockPos, boolean)}，
 *       NPC 会自己进入"受指挥"状态并用自研 A* 寻路走过去；</li>
 *   <li>没有阵型时用黄金角螺旋错开落点，避免整队叠在一格。</li>
 * </ul>
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
            int index = 0;
            for (UUID id : ids) {
                if (!allowed.contains(id)) {
                    continue;
                }
                Entity entity = RtsSelectionService.findEntity(level, id);
                Vec3 landing = target.add(scatterOffset(index, 2.0D));
                if (entity instanceof NpcEntity npc) {
                    index++;
                    npc.setRtsTarget(BlockPos.containing(landing), false);
                } else if (entity instanceof RtsFakePlayerEntity fake
                        && player.getUUID().equals(fake.getOwnerUUID())) {
                    fake.setMoveTarget(target);
                }
            }
        });
    }

    /** 黄金角螺旋散布：第 0 个走正中，其余按 2 格间距错开。 */
    private static Vec3 scatterOffset(int index, double spacing) {
        if (index == 0) {
            return Vec3.ZERO;
        }
        final double goldenAngle = 2.39996D;
        double radius = spacing * Math.sqrt(index);
        double angle = index * goldenAngle;
        return new Vec3(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);
    }
}
