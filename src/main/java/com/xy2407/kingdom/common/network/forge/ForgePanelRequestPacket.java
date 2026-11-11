package com.xy2407.kingdom.common.network.forge;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.forge.ForgePanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 请求打开锻造坊面板，服务端按玩家所在建筑下发快照
 */
public record ForgePanelRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ForgePanelRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "forge_panel_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ForgePanelRequestPacket> STREAM_CODEC =
            StreamCodec.of(ForgePanelRequestPacket::encode, ForgePanelRequestPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, ForgePanelRequestPacket packet) {
    }

    private static ForgePanelRequestPacket decode(RegistryFriendlyByteBuf buf) {
        return new ForgePanelRequestPacket();
    }

    public static void handle(ForgePanelRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ForgePanelService.respond(player);
            }
        });
    }
}
