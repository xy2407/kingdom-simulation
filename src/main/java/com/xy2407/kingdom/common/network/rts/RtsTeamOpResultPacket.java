package com.xy2407.kingdom.common.network.rts;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.network.clientbound.RtsTeamResultBridge;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：队伍操作结果（建队、加入、退出、删除、移动）的提示与成功标记
 */
public record RtsTeamOpResultPacket(boolean success, String message)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RtsTeamOpResultPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_team_op_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsTeamOpResultPacket> STREAM_CODEC =
            StreamCodec.of(RtsTeamOpResultPacket::encode, RtsTeamOpResultPacket::decode);

    public RtsTeamOpResultPacket {
        message = message == null ? "" : message;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsTeamOpResultPacket packet) {
        buf.writeBoolean(packet.success());
        buf.writeUtf(packet.message(), 256);
    }

    private static RtsTeamOpResultPacket decode(RegistryFriendlyByteBuf buf) {
        return new RtsTeamOpResultPacket(buf.readBoolean(), buf.readUtf(256));
    }

    public static void handle(RtsTeamOpResultPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> RtsTeamResultBridge.accept(packet.success(), packet.message()));
    }
}
