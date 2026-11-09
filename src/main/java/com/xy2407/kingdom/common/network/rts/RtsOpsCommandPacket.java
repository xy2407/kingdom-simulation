package com.xy2407.kingdom.common.network.rts;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.rts.RtsOpsService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：对当前选中的市民下达坚守或进入进攻状态
 */
public record RtsOpsCommandPacket(int action) implements CustomPacketPayload {

    public static final int ACTION_HOLD = 1;
    public static final int ACTION_ATTACK = 2;

    public static final CustomPacketPayload.Type<RtsOpsCommandPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_ops_command"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsOpsCommandPacket> STREAM_CODEC =
            StreamCodec.of(RtsOpsCommandPacket::encode, RtsOpsCommandPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsOpsCommandPacket packet) {
        buf.writeVarInt(packet.action());
    }

    private static RtsOpsCommandPacket decode(RegistryFriendlyByteBuf buf) {
        return new RtsOpsCommandPacket(buf.readVarInt());
    }

    public static void handle(RtsOpsCommandPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> RtsOpsService.apply(player, packet.action()));
    }
}
