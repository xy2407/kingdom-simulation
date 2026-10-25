package com.xy2407.kingdom.common.network.employment;

import java.util.ArrayList;
import java.util.List;
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
 * 客户端 → 服务端：按点击顺序提交本次要雇佣的市民名单
 */
public record EmploymentSubmitPacket(String buildingId, String targetId, int kind, List<UUID> ids)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<EmploymentSubmitPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "employment_submit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EmploymentSubmitPacket> STREAM_CODEC =
            StreamCodec.of(EmploymentSubmitPacket::encode, EmploymentSubmitPacket::decode);

    public EmploymentSubmitPacket {
        ids = ids == null ? List.of() : List.copyOf(ids);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, EmploymentSubmitPacket packet) {
        buffer.writeUtf(packet.buildingId, 64);
        buffer.writeUtf(packet.targetId, 64);
        buffer.writeVarInt(packet.kind);
        buffer.writeVarInt(packet.ids.size());
        for (UUID id : packet.ids) {
            buffer.writeUUID(id);
        }
    }

    public static EmploymentSubmitPacket decode(RegistryFriendlyByteBuf buffer) {
        String buildingId = buffer.readUtf(64);
        String targetId = buffer.readUtf(64);
        int kind = buffer.readVarInt();
        int count = buffer.readVarInt();
        List<UUID> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ids.add(buffer.readUUID());
        }
        return new EmploymentSubmitPacket(buildingId, targetId, kind, ids);
    }

    public static void handle(EmploymentSubmitPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                EmploymentPanelService.submit(player, packet.buildingId(), packet.targetId(),
                        packet.kind(), packet.ids());
            }
        });
    }
}
