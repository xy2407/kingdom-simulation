package com.xy2407.kingdom.common.network.area;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.area.PlotArea;
import com.xy2407.kingdom.common.area.PlotAreaKind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：当前维度该类型的全部已有范围（buildingId 与逐格集合一一对应）。
 */
public record AreaStatePacket(PlotAreaKind kind, UUID[] buildingIds, long[][] cells)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AreaStatePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "area_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AreaStatePacket> STREAM_CODEC =
            StreamCodec.of(AreaStatePacket::encode, AreaStatePacket::decode);

    private static final int MAX_AREAS = 256;

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, AreaStatePacket packet) {
        buf.writeVarInt(packet.kind() == null ? -1 : packet.kind().ordinal());
        int areas = packet.buildingIds() == null ? 0 : Math.min(packet.buildingIds().length, MAX_AREAS);
        buf.writeVarInt(areas);
        for (int index = 0; index < areas; index++) {
            buf.writeUUID(packet.buildingIds()[index]);
            long[] area = packet.cells() != null && index < packet.cells().length
                    ? packet.cells()[index] : new long[0];
            int count = Math.min(area.length, PlotArea.MAX_CELLS);
            buf.writeVarInt(count);
            for (int cell = 0; cell < count; cell++) {
                buf.writeLong(area[cell]);
            }
        }
    }

    private static AreaStatePacket decode(RegistryFriendlyByteBuf buf) {
        PlotAreaKind kind = PlotAreaKind.byOrdinal(buf.readVarInt());
        int areas = Math.min(Math.max(buf.readVarInt(), 0), MAX_AREAS);
        UUID[] buildingIds = new UUID[areas];
        long[][] cells = new long[areas][];
        for (int index = 0; index < areas; index++) {
            buildingIds[index] = buf.readUUID();
            int count = Math.min(Math.max(buf.readVarInt(), 0), PlotArea.MAX_CELLS);
            long[] area = new long[count];
            for (int cell = 0; cell < count; cell++) {
                area[cell] = buf.readLong();
            }
            cells[index] = area;
        }
        return new AreaStatePacket(kind, buildingIds, cells);
    }

    public static void handle(AreaStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> AreaHooks.accept(packet));
    }
}
