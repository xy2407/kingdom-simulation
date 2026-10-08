package com.xy2407.kingdom.common.network.cityhall;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：让客户端打开市政厅界面，并带上方块坐标供地图采样。
 */
public record CityHallOpenPacket(int x, int z) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CityHallOpenPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "city_hall_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CityHallOpenPacket> STREAM_CODEC =
            StreamCodec.of(CityHallOpenPacket::encode, CityHallOpenPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, CityHallOpenPacket packet) {
        buf.writeVarInt(packet.x());
        buf.writeVarInt(packet.z());
    }

    private static CityHallOpenPacket decode(RegistryFriendlyByteBuf buf) {
        return new CityHallOpenPacket(buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(CityHallOpenPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> CityHallHooks.openSink.accept(packet.x(), packet.z()));
    }
}
