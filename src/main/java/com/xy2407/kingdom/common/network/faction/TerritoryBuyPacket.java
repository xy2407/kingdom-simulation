package com.xy2407.kingdom.common.network.faction;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：把地图上逐个划选出来的区块并入自己的领地
 */
public record TerritoryBuyPacket(long[] chunks) implements CustomPacketPayload {

    public static final int MAX_CHUNKS = 4096;

    public static final CustomPacketPayload.Type<TerritoryBuyPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "territory_buy"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryBuyPacket> STREAM_CODEC =
            StreamCodec.of(TerritoryBuyPacket::encode, TerritoryBuyPacket::decode);

    public TerritoryBuyPacket {
        chunks = chunks == null ? new long[0] : chunks;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, TerritoryBuyPacket packet) {
        long[] chunks = packet.chunks();
        int size = Math.min(chunks.length, MAX_CHUNKS);
        buf.writeVarInt(size);
        for (int index = 0; index < size; index++) {
            buf.writeLong(chunks[index]);
        }
    }

    private static TerritoryBuyPacket decode(RegistryFriendlyByteBuf buf) {
        int size = Math.min(Math.max(buf.readVarInt(), 0), MAX_CHUNKS);
        long[] chunks = new long[size];
        for (int index = 0; index < size; index++) {
            chunks[index] = buf.readLong();
        }
        return new TerritoryBuyPacket(chunks);
    }

    public static void handle(TerritoryBuyPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                FactionHooks.territoryBuyHandler.accept(player, packet);
            }
        });
    }
}
