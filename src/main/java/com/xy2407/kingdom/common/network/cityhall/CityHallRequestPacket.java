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
 * 客户端 → 服务端：请求刷新市政厅数据，界面开着时定时发
 */
public record CityHallRequestPacket(int x, int z) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CityHallRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "city_hall_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CityHallRequestPacket> STREAM_CODEC =
            StreamCodec.of(CityHallRequestPacket::encode, CityHallRequestPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, CityHallRequestPacket packet) {
        buf.writeVarInt(packet.x());
        buf.writeVarInt(packet.z());
    }

    private static CityHallRequestPacket decode(RegistryFriendlyByteBuf buf) {
        return new CityHallRequestPacket(buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(CityHallRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                GovernmentAffairService.refreshCityHall(player, packet.x(), packet.z());
            }
        });
    }
}
