package com.xy2407.kingdom.common.network.builder;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端通知客户端打开建筑工面板，载荷为空仅作触发信号
 */
public record BuilderOpenPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuilderOpenPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "builder_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuilderOpenPacket> STREAM_CODEC =
            StreamCodec.of(BuilderOpenPacket::encode, BuilderOpenPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuilderOpenPacket packet) {
    }

    private static BuilderOpenPacket decode(RegistryFriendlyByteBuf buf) {
        return new BuilderOpenPacket();
    }

    public static void handle(BuilderOpenPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> BuilderHooks.openSink.accept(packet));
    }
}
