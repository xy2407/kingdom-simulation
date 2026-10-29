package com.xy2407.kingdom.common.network.cityhall;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.cityhall.CityHallTreasuryService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：领主要把钱包里的钱存进金库，或从金库提到钱包
 */
public record CityHallTreasuryActionPacket(int x, int z, boolean deposit, long amount)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CityHallTreasuryActionPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "city_hall_treasury_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CityHallTreasuryActionPacket> STREAM_CODEC =
            StreamCodec.of(CityHallTreasuryActionPacket::encode, CityHallTreasuryActionPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, CityHallTreasuryActionPacket packet) {
        buf.writeVarInt(packet.x());
        buf.writeVarInt(packet.z());
        buf.writeBoolean(packet.deposit());
        buf.writeVarLong(packet.amount());
    }

    private static CityHallTreasuryActionPacket decode(RegistryFriendlyByteBuf buf) {
        return new CityHallTreasuryActionPacket(buf.readVarInt(), buf.readVarInt(),
                buf.readBoolean(), buf.readVarLong());
    }

    public static void handle(CityHallTreasuryActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                CityHallTreasuryService.apply(player, packet.x(), packet.z(),
                        packet.deposit(), packet.amount());
            }
        });
    }
}
