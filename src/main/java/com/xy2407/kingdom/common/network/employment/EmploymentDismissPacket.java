package com.xy2407.kingdom.common.network.employment;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.employment.EmploymentPanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：在雇佣界面里直接解雇某个市民
 */
public record EmploymentDismissPacket(String buildingId, String targetId, int kind, UUID npcId)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<EmploymentDismissPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "employment_dismiss"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EmploymentDismissPacket> STREAM_CODEC =
            StreamCodec.of(EmploymentDismissPacket::encode, EmploymentDismissPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, EmploymentDismissPacket packet) {
        buffer.writeUtf(packet.buildingId, 64);
        buffer.writeUtf(packet.targetId, 64);
        buffer.writeVarInt(packet.kind);
        buffer.writeUUID(packet.npcId);
    }

    public static EmploymentDismissPacket decode(RegistryFriendlyByteBuf buffer) {
        return new EmploymentDismissPacket(buffer.readUtf(64), buffer.readUtf(64),
                buffer.readVarInt(), buffer.readUUID());
    }

    public static void handle(EmploymentDismissPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                EmploymentPanelService.dismiss(player, packet.buildingId(), packet.targetId(),
                        packet.kind(), packet.npcId());
            }
        });
    }
}
