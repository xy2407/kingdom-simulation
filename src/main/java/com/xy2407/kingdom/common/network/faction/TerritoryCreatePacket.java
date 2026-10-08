package com.xy2407.kingdom.common.network.faction;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：在某个市政厅方块上立一块领地，地界范围由服务端自己算。
 */
public record TerritoryCreatePacket(String name, int hallX, int hallY, int hallZ)
        implements CustomPacketPayload {

    public static final int MAX_NAME_LENGTH = 12;

    public static final CustomPacketPayload.Type<TerritoryCreatePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "territory_create"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryCreatePacket> STREAM_CODEC =
            StreamCodec.of(TerritoryCreatePacket::encode, TerritoryCreatePacket::decode);

    public TerritoryCreatePacket {
        name = name == null ? "" : name;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, TerritoryCreatePacket packet) {
        buf.writeUtf(packet.name(), MAX_NAME_LENGTH);
        buf.writeVarInt(packet.hallX());
        buf.writeVarInt(packet.hallY());
        buf.writeVarInt(packet.hallZ());
    }

    private static TerritoryCreatePacket decode(RegistryFriendlyByteBuf buf) {
        return new TerritoryCreatePacket(buf.readUtf(MAX_NAME_LENGTH),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(TerritoryCreatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                FactionHooks.territoryCreateHandler.accept(player, packet);
            }
        });
    }
}
