package com.xy2407.kingdom.common.network.mining;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.mining.MiningPanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求当前采矿建筑的矿坑快照。
 */
public record MiningPanelRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MiningPanelRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "mining_panel_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MiningPanelRequestPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {
            }, buf -> new MiningPanelRequestPacket());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MiningPanelRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MiningPanelService.respond(player);
            }
        });
    }
}
