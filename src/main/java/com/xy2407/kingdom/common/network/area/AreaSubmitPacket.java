package com.xy2407.kingdom.common.network.area;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.area.PlotArea;
import com.xy2407.kingdom.common.area.PlotAreaKind;
import com.xy2407.kingdom.server.area.PlotAreaService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：提交伐木/养殖范围的逐格框选（原始形状，服务端按最近建筑归属）。
 */
public record AreaSubmitPacket(PlotAreaKind kind, long[] cells) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AreaSubmitPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "area_submit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AreaSubmitPacket> STREAM_CODEC =
            StreamCodec.of(AreaSubmitPacket::encode, AreaSubmitPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, AreaSubmitPacket packet) {
        buf.writeVarInt(packet.kind() == null ? -1 : packet.kind().ordinal());
        int count = packet.cells() == null ? 0 : Math.min(packet.cells().length, PlotArea.MAX_CELLS);
        buf.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            buf.writeLong(packet.cells()[index]);
        }
    }

    private static AreaSubmitPacket decode(RegistryFriendlyByteBuf buf) {
        PlotAreaKind kind = PlotAreaKind.byOrdinal(buf.readVarInt());
        int count = Math.min(Math.max(buf.readVarInt(), 0), PlotArea.MAX_CELLS);
        long[] cells = new long[count];
        for (int index = 0; index < count; index++) {
            cells[index] = buf.readLong();
        }
        return new AreaSubmitPacket(kind, cells);
    }

    public static void handle(AreaSubmitPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                PlotAreaService.submit(player, packet.kind(), packet.cells());
            }
        });
    }
}
