package com.xy2407.kingdom.common.network.npc;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.npc.NpcRosterService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求当前维度的市民名册，服务端按玩家所在维度过滤后整份回发。
 */
public record NpcRosterRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<NpcRosterRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "npc_roster_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcRosterRequestPacket> STREAM_CODEC =
            StreamCodec.unit(new NpcRosterRequestPacket());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(NpcRosterRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                NpcRosterService.respond(player);
            }
        });
    }
}
