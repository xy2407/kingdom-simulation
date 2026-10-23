package com.xy2407.kingdom.common.network.area;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.area.PlotAreaKind;
import com.xy2407.kingdom.server.area.PlotAreaService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求当前维度某类型的全部已有范围（进入创建模式时高亮）。
 */
public record AreaStateRequestPacket(PlotAreaKind kind) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AreaStateRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "area_state_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AreaStateRequestPacket> STREAM_CODEC =
            StreamCodec.of(AreaStateRequestPacket::encode, AreaStateRequestPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, AreaStateRequestPacket packet) {
        buf.writeVarInt(packet.kind() == null ? -1 : packet.kind().ordinal());
    }

    private static AreaStateRequestPacket decode(RegistryFriendlyByteBuf buf) {
        return new AreaStateRequestPacket(PlotAreaKind.byOrdinal(buf.readVarInt()));
    }

    public static void handle(AreaStateRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                PlotAreaService.respond(player, packet.kind());
            }
        });
    }
}
