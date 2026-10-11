package com.xy2407.kingdom.common.network.cityhall;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.government.GovernmentAffairService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求办理市政厅列表里的某条政务
 */
public record CityHallAffairActionPacket(int x, int z, String affairId) implements CustomPacketPayload {

    private static final int MAX_ID_LENGTH = 64;

    public static final CustomPacketPayload.Type<CityHallAffairActionPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "city_hall_affair_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CityHallAffairActionPacket> STREAM_CODEC =
            StreamCodec.of(CityHallAffairActionPacket::encode, CityHallAffairActionPacket::decode);

    public CityHallAffairActionPacket {
        affairId = affairId == null ? "" : affairId;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, CityHallAffairActionPacket packet) {
        buf.writeVarInt(packet.x());
        buf.writeVarInt(packet.z());
        buf.writeUtf(packet.affairId(), MAX_ID_LENGTH);
    }

    private static CityHallAffairActionPacket decode(RegistryFriendlyByteBuf buf) {
        return new CityHallAffairActionPacket(
                buf.readVarInt(), buf.readVarInt(), buf.readUtf(MAX_ID_LENGTH));
    }

    public static void handle(CityHallAffairActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                GovernmentAffairService.resolveAffair(player, packet.x(), packet.z(), packet.affairId());
            }
        });
    }
}
