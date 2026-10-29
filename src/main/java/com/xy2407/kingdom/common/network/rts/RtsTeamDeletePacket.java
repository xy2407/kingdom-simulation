package com.xy2407.kingdom.common.network.rts;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.rts.RtsTeamService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：解散指定队伍，队内成员全部释放
 */
public record RtsTeamDeletePacket(UUID teamId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RtsTeamDeletePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_team_delete"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsTeamDeletePacket> STREAM_CODEC =
            StreamCodec.of(RtsTeamDeletePacket::encode, RtsTeamDeletePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsTeamDeletePacket packet) {
        buf.writeUUID(packet.teamId());
    }

    private static RtsTeamDeletePacket decode(RegistryFriendlyByteBuf buf) {
        return new RtsTeamDeletePacket(buf.readUUID());
    }

    public static void handle(RtsTeamDeletePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> RtsTeamService.delete(player, packet.teamId()));
    }
}
