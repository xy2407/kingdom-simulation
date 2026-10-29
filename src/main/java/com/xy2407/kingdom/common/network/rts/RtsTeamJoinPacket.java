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
 * 客户端 → 服务端：把一名市民加入指定队伍，服务端校验归属与占用后回推结果
 */
public record RtsTeamJoinPacket(UUID teamId, UUID memberUuid) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RtsTeamJoinPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_team_join"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsTeamJoinPacket> STREAM_CODEC =
            StreamCodec.of(RtsTeamJoinPacket::encode, RtsTeamJoinPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsTeamJoinPacket packet) {
        buf.writeUUID(packet.teamId());
        buf.writeUUID(packet.memberUuid());
    }

    private static RtsTeamJoinPacket decode(RegistryFriendlyByteBuf buf) {
        return new RtsTeamJoinPacket(buf.readUUID(), buf.readUUID());
    }

    public static void handle(RtsTeamJoinPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> {
            String error = RtsTeamService.join(player, packet.teamId(), packet.memberUuid());
            if (error != null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(error), true);
            }
        });
    }
}
