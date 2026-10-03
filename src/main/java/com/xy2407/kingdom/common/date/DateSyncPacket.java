package com.xy2407.kingdom.common.date;

import com.xy2407.kingdom.ModMain;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端向客户端同步当前总天数的网络包
 */
public record DateSyncPacket(int totalDay) implements CustomPacketPayload {

    public static final Type<DateSyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "date_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DateSyncPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> buf.writeVarInt(packet.totalDay),
            buf -> new DateSyncPacket(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DateSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> DateSyncBridge.handle(packet.totalDay));
    }
}
