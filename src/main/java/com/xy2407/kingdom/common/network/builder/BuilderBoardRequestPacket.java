package com.xy2407.kingdom.common.network.builder;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端请求建筑工面板全量数据，载荷为空由服务端回数据包
 */
public record BuilderBoardRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuilderBoardRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "builder_board_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuilderBoardRequestPacket> STREAM_CODEC =
            StreamCodec.of(BuilderBoardRequestPacket::encode, BuilderBoardRequestPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuilderBoardRequestPacket packet) {
    }

    private static BuilderBoardRequestPacket decode(RegistryFriendlyByteBuf buf) {
        return new BuilderBoardRequestPacket();
    }

    public static void handle(BuilderBoardRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                com.xy2407.kingdom.server.task.BuilderBoardService.respond(player);
            }
        });
    }
}
