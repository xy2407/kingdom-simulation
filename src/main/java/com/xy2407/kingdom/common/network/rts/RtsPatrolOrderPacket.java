package com.xy2407.kingdom.common.network.rts;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.rts.RtsPatrolService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 队伍巡逻指令（客户端→服务端）。
 * <p>
 * {@link #ACTION_START} 带整条路线（起点 → 中间节点 → 终点），队伍将从起点走到终点再折返，
 * {@link #ACTION_CANCEL} 解除巡逻并让队员回到「手持武器待命」。
 */
public record RtsPatrolOrderPacket(UUID teamId, int action, List<Vec3> route) implements CustomPacketPayload {

    public static final int ACTION_START = 0;
    public static final int ACTION_CANCEL = 1;

    /** 路线节点上限：折返巡逻不需要几十个点，超出的直接截断，避免包过大。 */
    public static final int MAX_NODES = 64;

    public static final Type<RtsPatrolOrderPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_patrol_order"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsPatrolOrderPacket> STREAM_CODEC =
            StreamCodec.of(RtsPatrolOrderPacket::encode, RtsPatrolOrderPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsPatrolOrderPacket packet) {
        buf.writeUUID(packet.teamId());
        buf.writeInt(packet.action());
        List<Vec3> route = packet.route() == null ? List.of() : packet.route();
        int size = Math.min(route.size(), MAX_NODES);
        buf.writeInt(size);
        for (int index = 0; index < size; index++) {
            Vec3 node = route.get(index);
            buf.writeDouble(node.x);
            buf.writeDouble(node.y);
            buf.writeDouble(node.z);
        }
    }

    private static RtsPatrolOrderPacket decode(RegistryFriendlyByteBuf buf) {
        UUID teamId = buf.readUUID();
        int action = buf.readInt();
        int size = Math.min(Math.max(buf.readInt(), 0), MAX_NODES);
        List<Vec3> route = new ArrayList<>(Math.max(size, 1));
        for (int index = 0; index < size; index++) {
            route.add(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }
        return new RtsPatrolOrderPacket(teamId, action, route);
    }

    public static void handle(RtsPatrolOrderPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> RtsPatrolService.apply(player, packet.teamId(), packet.action(), packet.route()));
    }
}
