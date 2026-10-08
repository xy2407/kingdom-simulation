package com.xy2407.kingdom.common.network.rts;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.rts.RtsFakePlayerService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：进入 RTS，请在我的位置放一个替身，重复发送等于把它挪回脚下。
 */
public record RtsFakePlayerSpawnPacket() implements CustomPacketPayload {

    public static final RtsFakePlayerSpawnPacket INSTANCE = new RtsFakePlayerSpawnPacket();

    public static final CustomPacketPayload.Type<RtsFakePlayerSpawnPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_fake_player_spawn"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsFakePlayerSpawnPacket> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RtsFakePlayerSpawnPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> RtsFakePlayerService.spawnFor(player));
    }
}
