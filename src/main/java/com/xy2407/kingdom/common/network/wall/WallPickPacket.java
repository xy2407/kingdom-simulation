package com.xy2407.kingdom.common.network.wall;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.wall.WallService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：RTS 点击某方块，请求按 wall_blocks 逐方块反查归属的城墙任务
 */
public record WallPickPacket(int x, int y, int z) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WallPickPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "wall_pick"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WallPickPacket> STREAM_CODEC =
            StreamCodec.of(WallPickPacket::encode, WallPickPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, WallPickPacket packet) {
        buf.writeInt(packet.x());
        buf.writeInt(packet.y());
        buf.writeInt(packet.z());
    }

    private static WallPickPacket decode(RegistryFriendlyByteBuf buf) {
        return new WallPickPacket(buf.readInt(), buf.readInt(), buf.readInt());
    }

    public static void handle(WallPickPacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> WallService.pick(player, packet.x(), packet.y(), packet.z()));
        }
    }
}
