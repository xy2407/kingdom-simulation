package com.xy2407.kingdom.common.network.employment;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.employment.EmploymentPanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求打开某个建筑与岗位的雇佣名册
 */
public record EmploymentOpenRequestPacket(String buildingId, String targetId, int kind)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<EmploymentOpenRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "employment_open_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EmploymentOpenRequestPacket> STREAM_CODEC =
            StreamCodec.of(EmploymentOpenRequestPacket::encode, EmploymentOpenRequestPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, EmploymentOpenRequestPacket packet) {
        buffer.writeUtf(packet.buildingId, 64);
        buffer.writeUtf(packet.targetId, 64);
        buffer.writeVarInt(packet.kind);
    }

    public static EmploymentOpenRequestPacket decode(RegistryFriendlyByteBuf buffer) {
        return new EmploymentOpenRequestPacket(buffer.readUtf(64), buffer.readUtf(64),
                buffer.readVarInt());
    }

    public static void handle(EmploymentOpenRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                EmploymentPanelService.open(player, packet.buildingId(), packet.targetId(),
                        packet.kind());
            }
        });
    }
}
