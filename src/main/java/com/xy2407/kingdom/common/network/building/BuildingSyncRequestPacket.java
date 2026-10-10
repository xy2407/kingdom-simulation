package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.building.BuildingSyncService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：要一份"当前维度里所有已完工建筑"的清单，用于 RTS 下画框与点选。
 */
public record BuildingSyncRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingSyncRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "building_sync_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingSyncRequestPacket> STREAM_CODEC =
            StreamCodec.unit(new BuildingSyncRequestPacket());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuildingSyncRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                BuildingSyncService.sendTo(player);
            }
        });
    }
}
