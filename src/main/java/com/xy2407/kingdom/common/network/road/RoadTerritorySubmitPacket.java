package com.xy2407.kingdom.common.network.road;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.road.RoadService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：一次提交的领地道路列坐标（高度服务端取地表），clear=true 时仅清除道路数据。
 */
public record RoadTerritorySubmitPacket(long[] cells, boolean clear) implements CustomPacketPayload {

    private static final int MAX_CELLS = 8192;

    public static final CustomPacketPayload.Type<RoadTerritorySubmitPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "road_territory_submit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadTerritorySubmitPacket> STREAM_CODEC =
            StreamCodec.of(RoadTerritorySubmitPacket::encode, RoadTerritorySubmitPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RoadTerritorySubmitPacket packet) {
        buf.writeBoolean(packet.clear());
        int count = Math.min(packet.cells().length, MAX_CELLS);
        buf.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            buf.writeLong(packet.cells()[index]);
        }
    }

    private static RoadTerritorySubmitPacket decode(RegistryFriendlyByteBuf buf) {
        boolean clear = buf.readBoolean();
        int count = Math.min(Math.max(buf.readVarInt(), 0), MAX_CELLS);
        long[] cells = new long[count];
        for (int index = 0; index < count; index++) {
            cells[index] = buf.readLong();
        }
        return new RoadTerritorySubmitPacket(cells, clear);
    }

    public static void handle(RoadTerritorySubmitPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                RoadService.submitTerritory(player, packet.cells(), packet.clear());
            }
        });
    }
}
