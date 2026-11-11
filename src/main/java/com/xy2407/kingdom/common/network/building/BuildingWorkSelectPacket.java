package com.xy2407.kingdom.common.network.building;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：这座建筑要作为哪个工种使用（玩家在选择界面点的那一项）。
 */
public record BuildingWorkSelectPacket(UUID buildingId, String work) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingWorkSelectPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "building_work_select"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingWorkSelectPacket> STREAM_CODEC =
            StreamCodec.of(BuildingWorkSelectPacket::encode, BuildingWorkSelectPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuildingWorkSelectPacket packet) {
        buf.writeUUID(packet.buildingId() == null ? new UUID(0L, 0L) : packet.buildingId());
        buf.writeUtf(packet.work() == null ? "" : packet.work(), 48);
    }

    private static BuildingWorkSelectPacket decode(RegistryFriendlyByteBuf buf) {
        return new BuildingWorkSelectPacket(buf.readUUID(), buf.readUtf(48));
    }

    public static void handle(BuildingWorkSelectPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                BuildingPanelService.applyWork(player, packet);
            }
        });
    }
}
