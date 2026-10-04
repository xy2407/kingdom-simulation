package com.xy2407.kingdom.common.network.cityhall;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：让客户端打开市政厅界面。
 *
 * <p>带上方块坐标：界面里的地图要**以这座市政厅为中心**采样世界表面，
 * 不带坐标的话客户端不知道画哪一块。</p>
 *
 * @param x 市政厅方块的世界 X
 * @param z 市政厅方块的世界 Z
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
