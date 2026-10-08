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
 * 客户端 → 服务端：退出 RTS，回收替身并把玩家本体放到替身最后站的位置。
 */
public record RtsFakePlayerClearPacket() implements CustomPacketPayload {

    public static final RtsFakePlayerClearPacket INSTANCE = new RtsFakePlayerClearPacket();

    public static final CustomPacketPayload.Type<RtsFakePlayerClearPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_fake_player_clear"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsFakePlayerClearPacket> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RtsFakePlayerClearPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> RtsFakePlayerService.recallFor(player));
    }
}
