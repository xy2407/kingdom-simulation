package com.xy2407.kingdom.common.network.area;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求当前维度所有类型的已圈地块，用于全局高亮
 */
public record AreaOverviewRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AreaOverviewRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "area_overview_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AreaOverviewRequestPacket> STREAM_CODEC =
            StreamCodec.unit(new AreaOverviewRequestPacket());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AreaOverviewRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.xy2407.kingdom.server.area.PlotAreaService.respondOverview(player);
            }
        });
    }
}
