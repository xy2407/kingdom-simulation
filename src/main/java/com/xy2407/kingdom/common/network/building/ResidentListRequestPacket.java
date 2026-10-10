package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.building.ResidentService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求当前住宅的住户名单。建筑由服务端按玩家位置判定，客户端不能指定。
 */
public record ResidentListRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ResidentListRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "resident_list_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ResidentListRequestPacket> STREAM_CODEC =
            StreamCodec.unit(new ResidentListRequestPacket());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ResidentListRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ResidentService.respond(player);
            }
        });
    }
}
