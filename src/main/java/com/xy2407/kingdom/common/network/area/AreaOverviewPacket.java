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
 * 服务端 → 客户端：当前维度全部已圈地块（类型、归属建筑与逐格集合一一对应）
 */
public record AreaOverviewPacket(PlotAreaKind[] kinds, UUID[] buildingIds, long[][] cells)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AreaOverviewPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "area_overview"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AreaOverviewPacket> STREAM_CODEC =
            StreamCodec.of(AreaOverviewPacket::encode, AreaOverviewPacket::decode);

    private static final int MAX_AREAS = 512;

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, AreaOverviewPacket packet) {
        int areas = packet.kinds() == null ? 0 : Math.min(packet.kinds().length, MAX_AREAS);
        buf.writeVarInt(areas);
        for (int index = 0; index < areas; index++) {
            PlotAreaKind kind = packet.kinds()[index];
            buf.writeVarInt(kind == null ? -1 : kind.ordinal());
            UUID id = packet.buildingIds() != null && index < packet.buildingIds().length
                    ? packet.buildingIds()[index] : null;
            if (id == null) {
                buf.writeBoolean(false);
            } else {
                buf.writeBoolean(true);
                buf.writeUUID(id);
            }
            long[] area = packet.cells() != null && index < packet.cells().length
                    ? packet.cells()[index] : new long[0];
            int count = Math.min(area.length, PlotArea.MAX_CELLS);
            buf.writeVarInt(count);
            for (int cell = 0; cell < count; cell++) {
                buf.writeLong(area[cell]);
            }
        }
    }

    private static AreaOverviewPacket decode(RegistryFriendlyByteBuf buf) {
        int areas = Math.min(Math.max(buf.readVarInt(), 0), MAX_AREAS);
        PlotAreaKind[] kinds = new PlotAreaKind[areas];
        UUID[] buildingIds = new UUID[areas];
        long[][] cells = new long[areas][];
        for (int index = 0; index < areas; index++) {
            kinds[index] = PlotAreaKind.byOrdinal(buf.readVarInt());
            buildingIds[index] = buf.readBoolean() ? buf.readUUID() : null;
            int count = Math.min(Math.max(buf.readVarInt(), 0), PlotArea.MAX_CELLS);
            long[] area = new long[count];
            for (int cell = 0; cell < count; cell++) {
                area[cell] = buf.readLong();
            }
            cells[index] = area;
        }
        return new AreaOverviewPacket(kinds, buildingIds, cells);
    }

    public static void handle(AreaOverviewPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> AreaHooks.acceptOverview(packet));
    }
}
